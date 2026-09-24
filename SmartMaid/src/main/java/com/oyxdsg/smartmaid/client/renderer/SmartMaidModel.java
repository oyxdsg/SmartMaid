package com.oyxdsg.smartmaid.client.renderer;

import com.oyxdsg.smartmaid.client.animation.MaidAnimManager;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * 女仆模型：复用 Java 版玩家模型（HumanoidModel）。
 *
 * <p>指导思想：女仆 = 玩家。模型不覆盖任何姿势——行走/跑/游泳/攀爬/坐姿/攻击摆动/手持物品
 * 全部由玩家模型原生的 {@link HumanoidModel#setupAnim} 计算，与玩家表现一致。</p>
 *
 * <p>坐姿：女仆坐下时由 {@code SmartMaidRenderer} 映射为 {@code state.isPassenger = true}，
 * 走原版玩家骑乘坐姿（玩家坐船同款），并在渲染器侧整体下沉 0.75 格（髋高）+ 双腿摆平，
 * 使屁股/大腿贴住脚下支撑面（地面 / 半砖 / 台阶顶）。</p>
 *
 * <p>动作动画：super.setupAnim 之后，把 PAL 动画控制器计算的骨骼值写入对应 ModelPart
 * （/maidanim 播放的 Emotecraft 动作），叠加在玩家原生动作之上。</p>
 */
public class SmartMaidModel extends HumanoidModel<SmartMaidRenderState> {
    private int logTick;

    public SmartMaidModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(SmartMaidRenderState state) {
        // 玩家模型原生处理全部基础动作（含 isPassenger 骑乘坐姿、游泳、攻击摆动等）
        super.setupAnim(state);
        // 坐下：双腿摆平。原版骑乘姿势腿有约 9° 下倾（脚会插进地面），摆成水平后
        // 腿正好平放在支撑面（地面/半砖顶）上，配合渲染器整体下沉 = 坐在地上的观感
        if (state.sitting) {
            float flat = -(float) (Math.PI / 2.0D);
            this.rightLeg.xRot = flat;
            this.leftLeg.xRot = flat;
        }
        // 动作动画：把 PAL 骨骼值写入模型（叠加）
        applyEmotes(state);
        // 高噪调试（每 200 帧），由 verbose() 单独开启，默认不打
        if (MaidDebug.verbose() && this.logTick++ % 200 == 0) {
            MaidDebug.log("setupAnim called, sitting=" + state.sitting + " passenger=" + state.isPassenger
                    + " anim=" + state.debugAnim);
        }
    }

    /** 应用 /maidanim 播放的动作动画（PAL 骨骼 → ModelPart） */
    private void applyEmotes(SmartMaidRenderState state) {
        SmartMaidEntity maid = state.maid;
        if (maid == null || !MaidAnimManager.isPlaying(maid)) {
            return;
        }
        // 每帧计算动画骨骼，然后应用到各骨骼
        MaidAnimManager.processOnce(maid, state.tickDelta);
        MaidAnimManager.apply(maid, "head", this.head);
        MaidAnimManager.apply(maid, "torso", this.body);
        MaidAnimManager.apply(maid, "right_arm", this.rightArm);
        MaidAnimManager.apply(maid, "left_arm", this.leftArm);
        MaidAnimManager.apply(maid, "right_leg", this.rightLeg);
        MaidAnimManager.apply(maid, "left_leg", this.leftLeg);
    }
}
