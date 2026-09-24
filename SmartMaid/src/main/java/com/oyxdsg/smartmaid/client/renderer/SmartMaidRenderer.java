package com.oyxdsg.smartmaid.client.renderer;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆渲染器（Java 版玩家模型 + 女仆皮肤）。
 * 坐姿：坐下时标记 isPassenger，走原版骑乘坐姿（僵尸坐船同款），并整体下沉
 * {@link #SIT_SINK} 格让屁股落到脚下支撑面（地面 / 半砖 / 台阶顶），不再浮空。
 * 装备：加原版盔甲渲染层，穿戴外观正常显示。
 */
public class SmartMaidRenderer extends HumanoidMobRenderer<SmartMaidEntity, SmartMaidRenderState, SmartMaidModel> {

    /**
     * 坐姿下沉量（格）：玩家模型髋部（body/腿的 pivot）在 12px = 0.75 格处。
     * 坐下时把整个模型下沉该距离，让屁股/大腿正好落到脚下支撑面
     * （地面 / 半砖顶 0.5 / 台阶顶 1.0）上，而不是浮在空中。
     */
    private static final float SIT_SINK = 0.75F;

    /** 上一次打印"客户端主手"的 tick（渲染帧调用远多于 tick，用它去重，避免每帧刷屏） */
    private int lastHandLogTick = -1;

    public SmartMaidRenderer(EntityRendererProvider.Context context) {
        super(context, new SmartMaidModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        // 盔甲渲染层（照抄原版）：先把 PLAYER_ARMOR（layer location 集合）bake 成 HumanoidModel，再挂到渲染层
        net.minecraft.client.renderer.entity.ArmorModelSet<HumanoidModel<SmartMaidRenderState>> armorSet =
                net.minecraft.client.renderer.entity.ArmorModelSet.bake(
                        ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<SmartMaidRenderState, SmartMaidModel, HumanoidModel<SmartMaidRenderState>>(
                this, armorSet, context.getEquipmentRenderer()));
    }

    @Override
    public SmartMaidRenderState createRenderState() {
        return new SmartMaidRenderState();
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(SmartMaidEntity entity, HumanoidArm arm) {
        // 仿玩家：空手自然下垂；手持物品抬起手臂；使用中按物品动作切换姿势
        // （拉弓 BOW_AND_ARROW / 举盾 BLOCK / 望远镜等——否则会被一律 ITEM 顶掉）
        ItemStack held = entity.getItemHeldByArm(arm);
        if (held.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        InteractionHand hand = arm == entity.getMainArm()
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (entity.isUsingItem() && entity.getUsedItemHand() == hand) {
            return switch (held.getUseAnimation()) {
                case BLOCK -> HumanoidModel.ArmPose.BLOCK;
                case BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW;
                case TRIDENT -> HumanoidModel.ArmPose.THROW_TRIDENT;
                case CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                case SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS;
                case TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN;
                case BRUSH -> HumanoidModel.ArmPose.BRUSH;
                case SPEAR -> HumanoidModel.ArmPose.SPEAR;
                default -> HumanoidModel.ArmPose.ITEM;
            };
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    /**
     * 坐姿整体下沉（世界坐标，Y 向上）。
     *
     * <p>调用时机：原版 {@code LivingEntityRenderer.submit} 在 {@code scale(state.scale)} 之后、
     * 模型的 {@code scale(-1,-1,1)} 翻转**之前**调用本方法，所以这里的 Y 就是世界的"上"，
     * 直接向下平移即可（不会撞模型坐标系 y 向下的约定）。</p>
     *
     * <p>只对"坐下"生效：真正的骑乘（船/矿车）仍由原版处理，不下沉。</p>
     */
    @Override
    protected void setupRotations(SmartMaidRenderState state, PoseStack poseStack, float bodyRot, float scale) {
        super.setupRotations(state, poseStack, bodyRot, scale);
        if (state.sitting) {
            poseStack.translate(0.0F, -SIT_SINK, 0.0F);
        }
    }

    @Override
    public void extractRenderState(SmartMaidEntity entity, SmartMaidRenderState state, float tickDelta) {
        // 女仆 = 玩家：super 已提取玩家模型需要的全部状态（mainHandItem/offHandItem/swimAmount/isFallFlying/攻击摆动等），
        // 我们不覆盖任何动作状态，保证行走/游泳/攀爬/攻击等玩家原生动作全部生效。
        super.extractRenderState(entity, state, tickDelta);
        // 坐姿映射：女仆坐下 → 标记 isPassenger，走原版玩家骑乘坐姿（玩家坐船同款）。
        // 注意：只在坐下时置 true，不覆盖真正的骑乘状态（女仆坐船时应保持原版骑乘）
        state.sitting = entity.isOrderedToSit() || entity.isInSittingPose();
        if (state.sitting) {
            state.isPassenger = true;
            // 模型整体下沉后，名字标签同步下沉，避免头顶与名字之间空出一大截
            if (state.nameTagAttachment != null) {
                state.nameTagAttachment = state.nameTagAttachment.add(0.0D, -SIT_SINK, 0.0D);
            }
        }
        // 开发调试动画 id（/maidanim 设置，同步到客户端）
        state.debugAnim = entity.getDebugAnim();
        state.tickDelta = tickDelta;
        state.maid = entity;
        // 头顶气泡：按宽度拆成多行，气泡期间隐藏原版单行名字标签
        state.bubbleLines = splitBubble(entity.getBubbleText());
        if (!state.bubbleLines.isEmpty()) {
            state.nameTag = null;
        }
        // 触发动作播放（客户端，幂等：同一 id 不重复播放）
        com.oyxdsg.smartmaid.client.animation.MaidAnimManager.play(entity, state.debugAnim);
        // 诊断：客户端实体主手物品（每 5s 一条；extractRenderState 每帧都会被调用，必须按 tick 去重）
        if (entity.tickCount % 100 == 0 && entity.tickCount != this.lastHandLogTick) {
            this.lastHandLogTick = entity.tickCount;
            MaidDebug.log("Render 客户端主手: " + MaidDebug.itemName(entity.getItemHeldByArm(entity.getMainArm())));
        }
        // 调试：确认 extractRenderState 被调用（每 40 tick，高噪由 verbose() 单独开启）
        if (MaidDebug.verbose() && entity.tickCount % 40 == 0) {
            MaidDebug.log("extractRenderState called, sitting=" + state.sitting + " passenger=" + state.isPassenger
                    + " anim=" + state.debugAnim
                    + " head=" + MaidDebug.itemName(entity.getItemBySlot(EquipmentSlot.HEAD))
                    + " chest=" + MaidDebug.itemName(entity.getItemBySlot(EquipmentSlot.CHEST)));
        }
    }

    /**
     * 多行气泡渲染：气泡激活时逐行提交名字标签（每行独立背景），
     * 否则走原版单行名字标签。
     */
    @Override
    protected void submitNameDisplay(SmartMaidRenderState state, PoseStack poseStack,
                                     SubmitNodeCollector collector, CameraRenderState camera) {
        List<Component> lines = state.bubbleLines;
        if (lines.isEmpty()) {
            super.submitNameDisplay(state, poseStack, collector, camera);
            return;
        }
        Vec3 base = state.nameTagAttachment;
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            // 第一行显示在最上：越靠前的行 Y 偏移越大
            Vec3 attach = base == null ? null : base.add(0.0D, (n - 1 - i) * 0.30D, 0.0D);
            collector.submitNameTag(poseStack, attach, 0, lines.get(i), true, state.lightCoords, camera);
        }
    }

    /** 把长气泡文本按字符数拆成多行（中文按字宽），避免一整行横在头顶。 */
    private static List<Component> splitBubble(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        final int perLine = 14;
        List<Component> lines = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i += perLine) {
            lines.add(Component.literal(text.substring(i, Math.min(len, i + perLine))));
        }
        return lines;
    }

    @Override
    public Identifier getTextureLocation(SmartMaidRenderState state) {
        return Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/entity/smart_maid.png");
    }
}
