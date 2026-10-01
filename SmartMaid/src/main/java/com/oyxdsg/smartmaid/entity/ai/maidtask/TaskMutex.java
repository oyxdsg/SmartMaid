package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * 任务互斥键（{@code DESIGN_MAID_TASK_QUEUE.md} §8.1/§8.2）。
 *
 * <p>{@code mutexKey = verb + ":" + targetKey}。判定分三态（§8.3）：</p>
 * <ul>
 *   <li>同 verb + <b>同具体子目</b> → 重复（REJECT）；</li>
 *   <li>同 verb、一方为<b>泛化通配</b>（{@code *}）→ 可能重叠（ACCEPT + 软提示）；</li>
 *   <li>同 verb 但子目明确不同 → 可共存（ACCEPT）；</li>
 * </ul>
 *
 * <p>{@code script} 不参与互斥（{@link #key} 返回 null），只做整脚本签名去重。</p>
 *
 * <p>归一化只做"补命名空间 + tag 简写 + 小写"；<b>tag 与具体 id 不等价</b>（§8.2，如
 * {@code #minecraft:logs} ≠ {@code minecraft:oak_log}）。同族归一（oak 家族）暂不做，
 * 遵循"宁可漏判也不误拒"（§十三 风险 11）。</p>
 */
public final class TaskMutex {

    public static final String WILDCARD = "*";

    private TaskMutex() {
    }

    /** 归一 verb；script 返回 null（不参与互斥）。 */
    public static String verb(String cmd) {
        return switch (cmd) {
            case "smelt", "craft", "mine", "harvest", "attack", "guard", "feed", "eat",
                 "move", "look", "break", "place", "use" -> cmd;
            case "chestopen", "chestput", "chesttake", "cheststore" -> "chest";
            case "transfer", "equip", "store", "drop", "pickup" -> "carry";
            case "script" -> null;
            default -> "other:" + cmd;
        };
    }

    /**
     * 计算互斥键。
     *
     * @param base 女仆方块坐标（用于把 {@code ~} 相对坐标换算成绝对；可为 null）
     * @return mutexKey；{@code script} 返回 null（不参与互斥）
     */
    public static String key(String cmd, JsonObject params, BlockPos base) {
        String verb = verb(cmd);
        if (verb == null) {
            return null;
        }
        return verb + ":" + target(verb, params, base);
    }

    /** 两个键是否"同 verb、一方泛化通配、另一方更具体"→ 可能重叠（软提示，不拒）。 */
    public static boolean possibleOverlap(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        int ia = a.indexOf(':');
        int ib = b.indexOf(':');
        if (ia < 0 || ib < 0) {
            return false;
        }
        if (!a.substring(0, ia).equals(b.substring(0, ib))) {
            return false;
        }
        return isGeneral(a) || isGeneral(b);
    }

    private static boolean isGeneral(String key) {
        int i = key.indexOf(':');
        return i >= 0 && key.substring(i + 1).contains(WILDCARD);
    }

    private static String target(String verb, JsonObject params, BlockPos base) {
        return switch (verb) {
            case "smelt", "craft", "feed", "eat", "carry" -> itemKey(params);
            case "attack" -> entityKey(params);
            case "guard" -> WILDCARD;
            case "harvest" -> produceKey(params);
            case "mine", "move", "look", "break", "place", "use", "chest" -> posKey(params, base);
            default -> WILDCARD;
        };
    }

    private static String itemKey(JsonObject params) {
        return params.has("item") && !params.get("item").isJsonNull()
                ? normId(params.get("item").getAsString()) : WILDCARD;
    }

    private static String entityKey(JsonObject params) {
        return params.has("target") && !params.get("target").isJsonNull()
                ? normId(params.get("target").getAsString()) : WILDCARD;
    }

    private static String produceKey(JsonObject params) {
        if (params.has("produce") && !params.get("produce").isJsonNull()) {
            String p = params.get("produce").getAsString();
            if (!p.isEmpty()) {
                String n = normId(p);
                // tag 与具体 id 不等价；具体 id 做同族归一（§8.2 家族表）
                return "tree:" + (n.startsWith("#") ? n : family(n));
            }
        }
        return "tree:" + WILDCARD;
    }

    /** 同族归一：oak_log/oak_wood/stripped_oak_log/oak_sapling → minecraft:oak。 */
    static String family(String id) {
        String ns = "";
        String s = id;
        int c = s.indexOf(':');
        if (c >= 0) {
            ns = s.substring(0, c + 1);
            s = s.substring(c + 1);
        }
        if (s.startsWith("stripped_")) {
            s = s.substring("stripped_".length());
        }
        for (String suf : new String[]{"_log", "_wood", "_planks", "_sapling", "_leaves"}) {
            if (s.endsWith(suf)) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }
        return ns + s;
    }

    /** 物品/实体/方块 id 归一：小写、补 {@code minecraft:} 命名空间、tag 保留 {@code #} 前缀。 */
    public static String normId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return WILDCARD;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("#")) {
            String body = s.substring(1);
            return "#" + (body.contains(":") ? body : "minecraft:" + body);
        }
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static String posKey(JsonObject params, BlockPos base) {
        if (!params.has("pos") || !params.get("pos").isJsonArray()) {
            return WILDCARD;
        }
        JsonArray a = params.getAsJsonArray("pos");
        if (a.size() < 3) {
            return WILDCARD;
        }
        Integer x = coord(a.get(0), base == null ? null : base.getX());
        Integer y = coord(a.get(1), base == null ? null : base.getY());
        Integer z = coord(a.get(2), base == null ? null : base.getZ());
        if (x == null || y == null || z == null) {
            return WILDCARD;
        }
        return "@" + x + "," + y + "," + z;
    }

    private static Integer coord(JsonElement e, Integer base) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        String s;
        try {
            s = e.getAsString();
        } catch (Exception ex) {
            return null;
        }
        if (s.startsWith("~")) {
            if (base == null) {
                return null;
            }
            String rest = s.substring(1).trim();
            if (rest.isEmpty()) {
                return base;
            }
            try {
                return base + Integer.parseInt(rest);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
