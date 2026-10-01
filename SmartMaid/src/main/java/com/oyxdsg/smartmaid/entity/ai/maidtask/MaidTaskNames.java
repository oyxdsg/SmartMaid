package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;

/**
 * 任务显示名（服务端由 cmd + params 生成中文摘要，供队列快照/菜单展示）。
 * 客户端不拼文案（DESIGN_MAID_MENU_N1 §3.4）。
 */
public final class MaidTaskNames {

    private MaidTaskNames() {
    }

    public static String display(String cmd, JsonObject params) {
        JsonObject p = params != null ? params : new JsonObject();
        return switch (cmd) {
            case "mine" -> "挖矿 " + intp(p, "count", 8) + " 块";
            case "guard" -> "护卫 半径" + intp(p, "range", 16);
            case "attack" -> "攻击" + (p.has("target") && !p.get("target").isJsonNull()
                    ? " " + shortId(p.get("target").getAsString()) : " 最近敌对");
            case "farm" -> "耕作";
            case "build" -> "建造";
            case "collect" -> "收集";
            case "craft" -> "制作 " + shortId(str(p, "item", "?"));
            case "smelt" -> "烧炼 " + shortId(str(p, "item", "?"));
            case "eat" -> p.has("item") ? "进食 " + shortId(str(p, "item", "")) : "进食";
            case "feed" -> "喂食主人";
            case "move" -> "移动";
            case "look" -> "看向";
            case "break" -> "破坏方块";
            case "place" -> "放置方块";
            case "use" -> "使用";
            case "chestopen" -> "打开箱子";
            case "chestput" -> "存入箱子";
            case "cheststore" -> "存入箱子";
            case "chesttake" -> "取出箱子";
            case "transfer" -> "转移物品";
            case "equip" -> "装备";
            case "store" -> "收起物品";
            case "drop" -> "丢出物品";
            case "pickup" -> "拾取";
            case "sit" -> "坐下/站起";
            case "script" -> "脚本任务";
            default -> cmd;
        };
    }

    private static int intp(JsonObject p, String k, int d) {
        return p.has(k) && p.get(k).isJsonPrimitive() && p.get(k).getAsJsonPrimitive().isNumber()
                ? p.get(k).getAsInt() : d;
    }

    private static String str(JsonObject p, String k, String d) {
        return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsString() : d;
    }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }
}
