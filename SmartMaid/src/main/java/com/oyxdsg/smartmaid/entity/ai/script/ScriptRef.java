package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * 脚本变量/条件/算术解析（Atomic Command Protocol §3.1）。
 *
 * <p>变量引用：{@code $name} 或 {@code $name.a.b}（vars 的嵌套字段）；{@code $last} 指上一步
 * result。算术只支持整数 {@code + -}。条件 op ∈ {@code eq/ne/gt/ge/lt/le/exists}。</p>
 */
public final class ScriptRef {

    private ScriptRef() {
    }

    /** 递归解析 params：把字符串里的 $var 引用替换为 JSON 值（数字/数组/对象/字符串） */
    public static JsonObject resolve(JsonObject params, ScriptContext ctx) {
        JsonObject out = new JsonObject();
        if (params == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : params.entrySet()) {
            out.add(e.getKey(), resolveValue(e.getValue(), ctx));
        }
        return out;
    }

    public static JsonElement resolveValue(JsonElement e, ScriptContext ctx) {
        if (e == null || e.isJsonNull()) {
            return e;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            JsonElement ref = resolveRef(s, ctx);
            return ref != null ? ref : e;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) {
                out.add(resolveValue(x, ctx));
            }
            return out;
        }
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                out.add(en.getKey(), resolveValue(en.getValue(), ctx));
            }
            return out;
        }
        return e;
    }

    /** 字符串 → 引用值：纯引用或整数算术；都不是返回 null（保持原字符串） */
    private static JsonElement resolveRef(String s, ScriptContext ctx) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.startsWith("$")) {
            JsonElement v = refElement(s, ctx);
            if (v != null) {
                return v;
            }
        }
        if (s.indexOf('+') >= 0 || s.indexOf('-') >= 0) {
            Integer v = evalInt(s, ctx);
            if (v != null) {
                return new JsonPrimitive(v);
            }
        }
        return null;
    }

    /** 取变量引用：$name.a.b（key 是第一个 . 前部分；$last 特指上一步） */
    public static JsonElement refElement(String ref, ScriptContext ctx) {
        if (ref == null || !ref.startsWith("$")) {
            return null;
        }
        String path = ref.substring(1);
        JsonElement cur;
        if (path.equals("last") || path.startsWith("last.")) {
            cur = ctx.lastResult;
            String rest = path.equals("last") ? "" : path.substring("last.".length());
            return descend(cur, rest);
        }
        int dot = path.indexOf('.');
        String key = dot < 0 ? path : path.substring(0, dot);
        JsonElement v = ctx.vars.get(key);
        if (v == null) {
            return null;
        }
        String rest = dot < 0 ? "" : path.substring(dot + 1);
        return descend(v, rest);
    }

    private static JsonElement descend(JsonElement cur, String rest) {
        if (rest.isEmpty()) {
            return cur == null ? null : cur.deepCopy();
        }
        for (String part : rest.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            cur = cur.getAsJsonObject().get(part);
        }
        return cur == null ? null : cur.deepCopy();
    }

    /** 整数算术求值（仅 + -，替换 $var 为数字）；非整数表达式返回 null */
    public static Integer evalInt(String expr, ScriptContext ctx) {
        if (expr == null || expr.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String s = expr;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '$') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j))
                        || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                JsonElement v = refElement(s.substring(i, j), ctx);
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                sb.append(v.getAsBigDecimal().toBigInteger());
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        String t = sb.toString();
        int val = 0;
        int sign = 1;
        boolean hasNum = false;
        int k = 0;
        while (k < t.length()) {
            char c = t.charAt(k);
            if (c == ' ' || c == '\t') {
                k++;
                continue;
            }
            if (c == '+') {
                sign = 1;
                k++;
                continue;
            }
            if (c == '-') {
                sign = -1;
                k++;
                continue;
            }
            if (Character.isDigit(c)) {
                long num = 0;
                while (k < t.length() && Character.isDigit(t.charAt(k))) {
                    num = num * 10 + (t.charAt(k) - '0');
                    k++;
                }
                val += sign * (int) num;
                hasNum = true;
                sign = 1;
            } else {
                return null;
            }
        }
        return hasNum ? val : null;
    }

    /** 条件求值：{var, op, val}；val 缺省用于 exists */
    public static boolean evalCond(JsonObject cond, ScriptContext ctx) {
        if (cond == null) {
            return true;
        }
        String varSpec = cond.has("var") ? cond.get("var").getAsString() : "";
        String op = cond.has("op") ? cond.get("op").getAsString() : "exists";
        JsonElement v = varSpec.startsWith("$") ? refElement(varSpec, ctx) : refElement("$" + varSpec, ctx);
        JsonElement val = cond.has("val") ? resolveValue(cond.get("val"), ctx) : null;
        switch (op) {
            case "exists":
                return v != null;
            case "eq":
                return jsonEquals(v, val);
            case "ne":
                return !jsonEquals(v, val);
            case "gt":
                return compareNumeric(v, val) > 0;
            case "ge":
                return compareNumeric(v, val) >= 0;
            case "lt":
                return compareNumeric(v, val) < 0;
            case "le":
                return compareNumeric(v, val) <= 0;
            default:
                return false;
        }
    }

    private static boolean jsonEquals(JsonElement a, JsonElement b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive pa = a.getAsJsonPrimitive();
            JsonPrimitive pb = b.getAsJsonPrimitive();
            if (pa.isBoolean() || pb.isBoolean()) {
                return pa.isBoolean() && pb.isBoolean() && pa.getAsBoolean() == pb.getAsBoolean();
            }
            if (pa.isNumber() && pb.isNumber()) {
                return pa.getAsBigDecimal().compareTo(pb.getAsBigDecimal()) == 0;
            }
            return pa.getAsString().equals(pb.getAsString());
        }
        return a.equals(b);
    }

    private static int compareNumeric(JsonElement a, JsonElement b) {
        if (a == null || b == null || !a.isJsonPrimitive() || !b.isJsonPrimitive()) {
            return -2;
        }
        double x;
        double y;
        try {
            x = a.getAsDouble();
            y = b.getAsDouble();
        } catch (Exception e) {
            return -2;
        }
        return Double.compare(x, y);
    }
}
