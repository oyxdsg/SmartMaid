package com.oyxdsg.smartmaid.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 女仆菜单视觉令牌（DESIGN_MAID_MENU_N1 §3.8）：对齐桌宠 <b>WBS 绿</b> 主题。
 *
 * <p>分层靠<b>亮度差</b>不靠描边：弹窗底 {@link #PANEL}（94% 不透明）→ 卡片 {@link #CARD} →
 * 交互反馈用淡色遮罩 {@link #HOVER}/{@link #ACCENT_SOFT}，均不描边。</p>
 */
public final class MaidTheme {

    private MaidTheme() {
    }

    public static final int BG = 0xFFF6F8F5;
    public static final int CARD = 0xFFFFFFFF;
    public static final int HOVER = 0xFFEDF0EC;
    public static final int HOVER_STRONG = 0xFFE4E9E2;
    public static final int TRACK = 0xFFEDF2ED;
    public static final int BORDER = 0xFFE2E8E1;
    public static final int TEXT = 0xFF293B30;
    public static final int TEXT_2 = 0xFF59675E;
    public static final int TEXT_3 = 0xFF6C7870;
    public static final int ACCENT = 0xFF3C8C4E;
    public static final int ACCENT_SOFT = 0xFFC3DCC6;
    public static final int BTN = 0xFF519560;
    public static final int BTN_HOVER = 0xFF46854F;
    public static final int BTN_PRESSED = 0xFF3A7444;

    /** 世界压暗遮罩：黑 45%。 */
    public static final int WORLD_OVERLAY = 0x73000000;
    /** 面板底：{@link #BG} 的 94% 不透明（留一点世界透出，保持层次）。 */
    public static final int PANEL = 0xF0F6F8F5;

    /** 面板/卡片填充（带圆角观感：角上叠一个小遮罩近似，N1 先用直角 fill）。 */
    public static void fill(GuiGraphicsExtractor ex, int x1, int y1, int x2, int y2, int argb) {
        ex.fill(x1, y1, x2, y2, argb);
    }

    /** 面板/卡片底：正常直角填充（不描边，靠亮度差分层）。 */
    public static void roundRect(GuiGraphicsExtractor ex, int x1, int y1, int x2, int y2, int argb, int radius) {
        ex.fill(x1, y1, x2, y2, argb);
    }
}
