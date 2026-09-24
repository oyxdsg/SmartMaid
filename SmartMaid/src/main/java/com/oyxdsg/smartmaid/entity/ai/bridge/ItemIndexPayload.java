package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.TreeMap;

/**
 * 物品索引下发（供桌宠 NLU 做物品名模糊匹配）。
 *
 * <p>把「当前语言的物品显示名 → {@code minecraft:id}」整表推给桌宠，桌宠侧就能
 * 在本地做中文/拼音模糊匹配（「帮我合成一把稿子」→「镐子」→
 * {@code minecraft:wooden_pickaxe}），再把**权威 id** 发回来执行。</p>
 *
 * <p>为什么由模组下发、而不是桌宠自带词典：</p>
 * <ul>
 *     <li>覆盖 **mod 物品**——词典随整合包自动变化，桌宠不用维护</li>
 *     <li>名称跟随**客户端当前语言**，中文包/英文包都可匹配</li>
 *     <li>桌宠不必打包 {@code zh_cn.json}，也就不存在版本同步问题</li>
 * </ul>
 *
 * <p>名称取自客户端 {@code I18n}（语言资源在客户端）。专用服务端没有语言资源，
 * 此时 {@link #build()} 返回 {@code null}，桌宠会自动降级为「认得出名字但执行不了」，
 * **不会误执行**。</p>
 *
 * <p>结果按连接缓存一次（物品表在运行期不变）。</p>
 */
public final class ItemIndexPayload {

    /** 单次下发的名字上限（防极端整合包把一条 WS 消息撑爆） */
    private static final int MAX_ENTRIES = 4096;
    /** 过长的显示名基本不是物品名（如自定义名/tooltip 类），跳过 */
    private static final int MAX_NAME_LEN = 16;

    private static volatile JsonObject cached;

    private ItemIndexPayload() {
    }

    public static JsonObject build() {
        JsonObject message = cached;
        if (message != null) {
            return message;
        }
        Map<String, String> map = new TreeMap<>();
        try {
            for (Item item : BuiltInRegistries.ITEM.stream().toList()) {
                String id;
                try {
                    id = BuiltInRegistries.ITEM.getKey(item).toString();
                } catch (Throwable ignored) {
                    continue;
                }
                String desc = item.getDescriptionId();
                String name;
                try {
                    name = net.minecraft.client.resources.language.I18n.get(desc);
                } catch (Throwable t) {
                    // 专用服务端：没有客户端语言资源，无法提供中文名
                    return null;
                }
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (name.equals(desc)) {
                    continue; // 未翻译（返回了 key 本身）
                }
                name = name.trim();
                if (name.isEmpty() || name.length() > MAX_NAME_LEN) {
                    continue;
                }
                map.putIfAbsent(name, id);
                if (map.size() >= MAX_ENTRIES) {
                    break;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        if (map.isEmpty()) {
            return null;
        }
        JsonObject items = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            items.addProperty(e.getKey(), e.getValue());
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "item_index");
        msg.addProperty("count", map.size());
        msg.add("items", items);
        message = msg;
        cached = message;
        return message;
    }

    /** 语言/资源包切换后调用（当前未用，留作扩展点） */
    public static void invalidate() {
        cached = null;
    }
}
