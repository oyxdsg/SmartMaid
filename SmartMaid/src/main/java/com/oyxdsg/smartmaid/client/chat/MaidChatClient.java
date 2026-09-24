package com.oyxdsg.smartmaid.client.chat;

/**
 * 客户端「女仆对话模式」状态。
 *
 * <p>用聊天栏指令 {@code /maidchat} 开关；开启后普通聊天文本不再发往服务器公共聊天，
 * 而是转给女仆 AI（见 {@code SmartMaidClient} 的聊天拦截）。</p>
 */
public final class MaidChatClient {

    private static boolean chatMode;

    private MaidChatClient() {
    }

    public static boolean isChatMode() {
        return chatMode;
    }

    /** 切换对话模式，返回切换后的状态。 */
    public static boolean toggle() {
        chatMode = !chatMode;
        return chatMode;
    }
}
