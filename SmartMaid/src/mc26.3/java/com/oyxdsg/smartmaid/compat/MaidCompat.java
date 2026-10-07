package com.oyxdsg.smartmaid.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;

/**
 * 版本差异隔离层 —— <b>MC 26.3 实现</b>。
 *
 * <p>与 {@code src/mc26.2/java} 下那份同名类<b>签名完全一致</b>、实现不同。
 * 构建时按 {@code gradle.properties} 的 {@code mc_series} 只把其中一份加进 srcDirs，
 * 共享代码因此不需要任何条件分支。设计说明见 {@code DESIGN_MULTIVERSION.md}。</p>
 *
 * <p>本文件逐条对应 26.3 的破坏性变更（依据：对 26.3 原版 jar 的实测，见
 * {@code DESIGN_MULTIVERSION.md} §10.5）。</p>
 */
public final class MaidCompat {

    private MaidCompat() {
    }

    /**
     * 挥动手臂。
     *
     * <p>26.3 把 {@code swing(InteractionHand)} / {@code swing(InteractionHand, boolean)}
     * 换成了 {@code swing(InteractionHand, SwingAnimation, boolean)}，挥动时长改由
     * <b>物品组件</b>决定（{@code DataComponents.ATTACK_ANIMATION} / {@code INTERACT_ANIMATION}）。
     * 这里优先取手持物品自带的动画组件，没有则退回 {@link SwingAnimation#DEFAULT}。</p>
     */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        ItemStack stack = entity.getItemInHand(hand);
        SwingAnimation animation = stack.get(DataComponents.ATTACK_ANIMATION);
        entity.swing(hand, animation != null ? animation : SwingAnimation.DEFAULT, true);
    }

    /**
     * 客户端推进挥动动画。
     *
     * <p><b>26.3 起为 no-op。</b>26.2 之所以要手动补推进，是因为原版只在
     * {@code Monster}/{@code Player} 的 {@code aiStep} 里调用 {@code updateSwingTime()}，
     * 普通 Mob 的 {@code attackAnim} 永远不推进。26.3 把挥动状态重构成了
     * {@code LivingEntity.SwingState} + {@code getSwingAnimation(float)} + {@code isSwinging()}，
     * 那套「按子类手工推进」的字段与私有方法全部删除 —— 预期原版已统一处理，
     * 本 hack 不再需要。</p>
     *
     * <p>⚠️ <b>未验证项</b>：26.3 上「普通 Mob 的挥动是否真的可见」只能真机确认
     * （静态核验只能证明签名，证明不了行为）。若真机上发现挥动不可见，
     * 就在这里按新 API（{@code SwingState}）重新实现，而<b>不要</b>退回反射写字段 ——
     * 那些字段在 26.3 已经不存在了。</p>
     */
    public static void tickSwingAnim(SmartMaidEntity maid) {
        // 26.3：原版统一处理，无需补推进（见上方说明）。
    }

    /**
     * 该物品能否当熔炉燃料。
     *
     * <p>26.3 删除了 {@code net.minecraft.world.level.block.entity.FuelValues} 与
     * {@code Level#fuelValues()}，燃料改由物品组件 {@code DataComponents.COOKING_FUEL}
     * （类型 {@code CookingFuel}，含 burnTime / speedMultiplier）声明。</p>
     */
    public static boolean isFuel(Level level, ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.COOKING_FUEL);
    }

    /** {@code KeyMapping} 用的输入类型。26.3：{@code KEYSYM} 已改名 {@code KEYBOARD}。 */
    public static InputConstants.Type keyType() {
        return InputConstants.Type.KEYBOARD;
    }

    /** 按键是否按下。26.3 去掉了 {@code Window} 参数。 */
    public static boolean isKeyDown(int keyCode) {
        return InputConstants.isKeyDown(keyCode);
    }

    /** 任一按键按下即为真（用于「左右 Shift 任意一个」这类判定）。 */
    public static boolean isAnyKeyDown(int... keyCodes) {
        for (int code : keyCodes) {
            if (isKeyDown(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「鼠标主键（左键）」在本版本里的编码值 —— 26.3 用 SDL：**左键 = 1**。
     *
     * <p>⚠️ <b>这是 26.3 最隐蔽的一个坑</b>：26.2（GLFW）左键是 0、右 1、中 2；
     * 26.3（SDL）左键是 **1**、中 2、右 3。而 {@code MouseButtonEvent.button()} 直接把
     * SDL 的键号透传出来（证据：{@code SDLEventHandler.handleMouseButtonEvent} 里是
     * {@code new MouseButtonInfo(SDL_MouseButtonEvent.button(), SDL_GetModState())}）。
     * 于是自绘 Screen 里惯用的 {@code if (event.button() != 0) return;} 会把**左键点击
     * 全部当成非左键丢掉** → 按钮点了完全没反应（实测：主菜单能开，但里面点哪都没用）。</p>
     */
    public static int primaryMouseButton() {
        return org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT;
    }

    /** 该鼠标键号是不是主键（左键）。 */
    public static boolean isPrimaryMouseButton(int button) {
        return button == primaryMouseButton();
    }
}
