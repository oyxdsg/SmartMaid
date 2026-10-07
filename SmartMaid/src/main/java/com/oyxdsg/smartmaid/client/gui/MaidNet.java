package com.oyxdsg.smartmaid.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * GUI 安全发包 —— **不在游戏中时静默丢弃，绝不抛异常**。
 *
 * <h2>为什么必须有这一层</h2>
 * Fabric 的 {@code ClientPlayNetworking.send} 在**没有连接**时直接抛
 * {@code IllegalStateException: Cannot send packets when not in game!}。
 * 而 {@code Screen} 会被 {@code Gui.tick()} 持续驱动 —— 只要"菜单还开着"这件事发生在
 * 没有连接的时刻，就会把游戏崩掉。真实发生过两种情形：
 * <ol>
 *   <li>**开过女仆菜单 → 退出世界 / 被踢**：残留的 Screen 继续 tick → 崩
 *       （实测崩溃点：{@code MaidMenuScreen.tick} → {@code ClientPlayNetworking.send}）；</li>
 *   <li>**客户端自动化测试在标题画面就开了菜单**（本模组自己的测试运行器，已另行加"必须已在游戏中"的门）。</li>
 * </ol>
 *
 * <p>本模组所有 GUI 里的发包都必须走这里 —— 直接把 {@code ClientPlayNetworking.send}
 * 写在 Screen 里等于埋一颗只在"退出世界"时才炸的雷。</p>
 *
 * @return true = 真的发出去了；false = 不在游戏中，已丢弃
 */
public final class MaidNet {

    private MaidNet() {
    }

    public static boolean send(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) {
            return false;
        }
        ClientPlayNetworking.send(payload);
        return true;
    }

    /** 当前是否处于"有连接、能发包"的状态（给 Screen 用来决定是否跳过 tick 逻辑）。 */
    public static boolean canSend() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getConnection() != null;
    }
}
