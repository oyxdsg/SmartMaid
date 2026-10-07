package com.oyxdsg.smartmaid.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 版本差异隔离层 —— <b>MC 26.2 实现</b>。
 *
 * <p>这个包（{@code src/mc26.2/java}）里放的全是「只在某个 MC 版本上编译得过」的代码。
 * 共享代码（{@code src/main/java}）一律只调本类的静态方法，<b>不直接碰版本敏感 API</b>，
 * 于是同一份业务逻辑可以在多个版本上各编一份。切换目标版本的参数见
 * {@code gradle.properties} 的 {@code mc_series}，设计说明见 {@code DESIGN_MULTIVERSION.md}。</p>
 *
 * <p>{@code src/mc26.3/java} 下有一份**同名类**，签名完全一致、实现不同。构建时按
 * {@code mc_series} 只把其中一份加进 srcDirs。</p>
 *
 * <p>覆盖的差异（26.2 → 26.3）：</p>
 * <ol>
 *   <li>{@code LivingEntity.swing(InteractionHand)} → 26.3 改为
 *       {@code swing(InteractionHand, SwingAnimation, boolean)}</li>
 *   <li>客户端挥动动画推进：26.3 删掉了 {@code swinging} / {@code swingTime} / {@code attackAnim}
 *       等字段与私有的 {@code getCurrentSwingDuration()}（见 {@link #tickSwingAnim}）</li>
 *   <li>燃料判定：26.3 删除了 {@code FuelValues} 与 {@code Level#fuelValues()}，改用物品组件</li>
 *   <li>键位：{@code InputConstants.Type.KEYSYM} → 26.3 改名 {@code KEYBOARD}</li>
 *   <li>{@code InputConstants.isKeyDown(Window, int)} → 26.3 去掉 Window 参数</li>
 * </ol>
 */
public final class MaidCompat {

    private MaidCompat() {
    }

    /** 挥动手臂。26.2：{@code swing(hand)}。 */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        entity.swing(hand);
    }

    /**
     * 客户端推进挥动动画。
     *
     * <p>26.2 的 {@code LivingEntity.updateSwingTime()} 只在 {@code Monster}/{@code Player}
     * 子类的 {@code aiStep} 里被调用，普通 Mob（含女仆）收到挥动动画包后
     * {@code attackAnim} 永远不会推进 → 挖掘/攻击挥动看不见。这里手动复制原版推进逻辑
     * （{@code oAttackAnim} 由 {@code baseTick} 自动更新，插值正常）。
     * 挥动时长 {@code getCurrentSwingDuration()} 是 private，用反射取（含物品时长/挖掘加速）。</p>
     */
    public static void tickSwingAnim(SmartMaidEntity maid) {
        int duration = 6;
        if (SWING_DURATION_METHOD != null) {
            try {
                duration = (int) SWING_DURATION_METHOD.invoke(maid);
            } catch (Exception ignored) {
            }
        }
        if (duration <= 0) {
            duration = 6;
        }
        if (maid.swinging) {
            maid.swingTime++;
            if (maid.swingTime >= duration) {
                maid.swingTime = 0;
                maid.swinging = false;
            }
        } else {
            maid.swingTime = 0;
        }
        maid.attackAnim = (float) maid.swingTime / (float) duration;
    }

    private static final java.lang.reflect.Method SWING_DURATION_METHOD;

    static {
        java.lang.reflect.Method m = null;
        try {
            m = LivingEntity.class.getDeclaredMethod("getCurrentSwingDuration");
            m.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        SWING_DURATION_METHOD = m;
    }

    /** 该物品能否当熔炉燃料。26.2：{@code Level#fuelValues().isFuel(stack)}。 */
    public static boolean isFuel(Level level, ItemStack stack) {
        if (stack.isEmpty() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return serverLevel.fuelValues().isFuel(stack);
    }

    /** {@code KeyMapping} 用的输入类型。26.2：{@code KEYSYM}。 */
    public static InputConstants.Type keyType() {
        return InputConstants.Type.KEYSYM;
    }

    /** 按键是否按下。26.2 需要显式传当前窗口。 */
    public static boolean isKeyDown(int keyCode) {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), keyCode);
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
     * 「鼠标主键（左键）」在本版本里的编码值 —— 26.2 用 GLFW：**左键 = 0**。
     *
     * <p>⚠️ 别硬编码 0：26.3 换成 SDL 后左键变成 **1**（右 3 / 中 2），
     * 沿用 `event.button() == 0` 会让**所有自绘点击静默失效**（按钮点了没反应）。
     * 详见 {@link #isPrimaryMouseButton(int)}。</p>
     */
    public static int primaryMouseButton() {
        return org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }

    /** 该鼠标键号是不是主键（左键）。 */
    public static boolean isPrimaryMouseButton(int button) {
        return button == primaryMouseButton();
    }
}
