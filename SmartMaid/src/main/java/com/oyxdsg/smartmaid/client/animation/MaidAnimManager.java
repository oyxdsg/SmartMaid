package com.oyxdsg.smartmaid.client.animation;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.animation.AnimationController;
import com.zigythebird.playeranimcore.animation.AnimationData;
import com.zigythebird.playeranimcore.animation.HumanoidAnimationController;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import com.zigythebird.playeranimcore.enums.PlayState;
import com.zigythebird.playeranimcore.loading.UniversalAnimLoader;
import net.minecraft.client.model.geom.ModelPart;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 女仆动作管理：加载 Emotecraft 动作 JSON → PAL Animation，用 HumanoidAnimationController 播放。
 *
 * <p>PAL 的 {@link HumanoidAnimationController} 不依赖 Avatar，女仆可直接使用：
 * <ol>
 *   <li>每游戏 tick：{@link #tick(SmartMaidEntity)} 推进控制器（动画时间 +1）</li>
 *   <li>渲染 setupAnim：{@link #apply(SmartMaidEntity, String, ModelPart)} 读取骨骼值写入模型</li>
 * </ol></p>
 *
 * <p>动作 JSON（Emotecraft 格式）由 {@link UniversalAnimLoader} 解析，骨骼名自动归一化
 * （rightArm → right_arm），与 {@link HumanoidAnimationController} 注册的骨骼一致。</p>
 */
public final class MaidAnimManager {
    /** 动作 id → 动作名（与 /maidanim 指令对应） */
    private static final String[] ANIM_NAMES = {
            "",          // 0 = 无
            "waving",    // 1 挥手
            "clap",      // 2 鼓掌
            "point",     // 3 指
            "palm",      // 4 展示手掌
            "here",      // 5 这里
            "crying",    // 6 哭
            "backflip",  // 7 后空翻
            "twerk",     // 8 电臀舞
            "kazotsky_kick", // 9 哥萨克踢腿舞
            "roblox_potion_dance", // 10 Roblox 药水舞
            "club_penguin_dance",  // 11 企鹅俱乐部舞
    };

    /** 已加载动作：动作名 → Animation（纯数据，服务端/客户端共用） */
    private static final Map<String, Animation> ANIMATIONS = new HashMap<>();

    /** 女仆实体 id → 动画控制器（客户端渲染用） */
    private static final Map<Integer, HumanoidAnimationController> CONTROLLERS = new HashMap<>();

    /** 女仆实体 id → 当前播放动作名 */
    private static final Map<Integer, String> CURRENT = new HashMap<>();

    private static boolean loaded = false;

    private MaidAnimManager() {
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        for (String name : ANIM_NAMES) {
            if (name.isEmpty()) {
                continue;
            }
            try (InputStream in = MaidAnimManager.class.getResourceAsStream("/assets/smartmaid/emotes/" + name + ".json")) {
                if (in == null) {
                    SmartMaid.LOGGER.warn("动作文件缺失: {}", name);
                    continue;
                }
                Map<String, Animation> map = UniversalAnimLoader.loadAnimations(in);
                // map 的 key 是动作内部名称（Component 翻译键），用文件名作为 key 存储，便于按名字播放
                Animation anim = map.values().stream().findFirst().orElse(null);
                if (anim != null) {
                    ANIMATIONS.put(name, anim);
                } else {
                    SmartMaid.LOGGER.warn("动作 {} 解析为空", name);
                }
            } catch (Exception e) {
                SmartMaid.LOGGER.error("加载动作 {} 失败", name, e);
            }
        }
        loaded = true;
        SmartMaid.LOGGER.info("已加载 {} 个动作: {}", ANIMATIONS.size(), ANIMATIONS.keySet());
    }

    public static String[] getAnimNames() {
        return ANIM_NAMES;
    }

    private static HumanoidAnimationController getController(SmartMaidEntity maid) {
        return CONTROLLERS.computeIfAbsent(maid.getId(), k -> new HumanoidAnimationController(
                (controller, data, setter) -> PlayState.CONTINUE,
                c -> team.unnamed.mocha.MochaEngine.create(c)
        ));
    }

    /** 播放指定动作（0=停止），服务端指令调用后同步到客户端通过实体数据 */
    public static void play(SmartMaidEntity maid, int animId) {
        ensureLoaded();
        String name = animId >= 0 && animId < ANIM_NAMES.length ? ANIM_NAMES[animId] : "";
        int eid = maid.getId();
        HumanoidAnimationController ctrl = getController(maid);

        String cur = CURRENT.get(eid);
        if (name.equals(cur)) {
            return;
        }
        if (cur != null) {
            ctrl.stopTriggeredAnimation();
            CURRENT.remove(eid);
        }
        if (name.isEmpty()) {
            return;
        }
        Animation anim = ANIMATIONS.get(name);
        if (anim == null) {
            SmartMaid.LOGGER.warn("动作未找到: {}", name);
            return;
        }
        ctrl.triggerAnimation(anim);
        CURRENT.put(eid, name);
        SmartMaid.LOGGER.debug("女仆 {} 播放动作 {}", eid, name);
    }

    /** 每游戏 tick 推进动画时间（实体 aiStep 客户端调用，PAL 需要 tick 字段递增） */
    public static void tick(SmartMaidEntity maid) {
        HumanoidAnimationController ctrl = CONTROLLERS.get(maid.getId());
        if (ctrl == null || !ctrl.isActive()) {
            return;
        }
        double h = Math.abs(maid.getDeltaMovement().x) + Math.abs(maid.getDeltaMovement().z);
        ctrl.tick(new AnimationData((float) (h / 2.0), 0.0F, false));
    }

    /**
     * 每渲染帧计算动画骨骼（setupAnim 中调用）。
     * process 用 tick + partialTick 计算当前关键帧值写入 activeBones，之后 get3DTransform 读取。
     */
    public static void processOnce(SmartMaidEntity maid, float tickDelta) {
        HumanoidAnimationController ctrl = CONTROLLERS.get(maid.getId());
        if (ctrl == null || !ctrl.isActive()) {
            return;
        }
        double h = Math.abs(maid.getDeltaMovement().x) + Math.abs(maid.getDeltaMovement().z);
        ctrl.setupAnim(new AnimationData((float) (h / 2.0), tickDelta, false));
    }

    /** 是否正在播放动作 */
    public static boolean isPlaying(SmartMaidEntity maid) {
        return CURRENT.containsKey(maid.getId());
    }

    /**
     * 把骨骼变换应用到模型部件（setupAnim 中调用，需先 processOnce）。
     * 骨骼名用标准下划线格式（right_arm/left_arm/right_leg/left_leg/head/torso/body）。
     */
    public static void apply(SmartMaidEntity maid, String boneName, ModelPart part) {
        HumanoidAnimationController ctrl = CONTROLLERS.get(maid.getId());
        if (ctrl == null || !ctrl.isActive()) {
            return;
        }
        PlayerAnimBone bone = new PlayerAnimBone(boneName);
        ctrl.get3DTransform(bone);
        com.zigythebird.playeranim.util.RenderUtil.translatePartToBone(part, bone, part.getInitialPose());
    }

    /** 实体移除时清理控制器 */
    public static void cleanup(int entityId) {
        CONTROLLERS.remove(entityId);
        CURRENT.remove(entityId);
    }
}
