package com.oyxdsg.smartmaid.client.renderer;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 女仆渲染状态：基于 26.2 HumanoidRenderState（Java 版玩家模型渲染）。
 */
public class SmartMaidRenderState extends HumanoidRenderState {
    /** 是否处于坐下待命状态（用于渲染坐姿） */
    public boolean sitting;

    /** 头顶气泡拆行后的文本（空=无气泡；渲染器逐行提交，避免一整行过长） */
    public List<Component> bubbleLines = List.of();

    /** 开发调试：强制播放的动画 id（0=无，由 /maidanim 设置，extractRenderState 从实体读取） */
    public int debugAnim;

    /** 渲染帧插值（extractRenderState 的 tickDelta，供摆动动画平滑） */
    public float tickDelta;

    /** 女仆实体引用（动作动画 setupAnim 需要） */
    public SmartMaidEntity maid;

    public SmartMaidRenderState() {
    }
}
