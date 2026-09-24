package com.oyxdsg.smartmaid.entity.ai.structure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构类型注册表（Atomic Command Protocol §5.1）：id → StructureType。
 * 内置 tree；后续 crop / ore_vein / container 等在此注册即可，执行器零改动。
 */
public final class StructureRegistry {

    private static final Map<String, StructureType> TYPES = new LinkedHashMap<>();

    static {
        register(new TreeStructure());
        register(new OreStructure());
    }

    private StructureRegistry() {
    }

    public static void register(StructureType type) {
        if (type != null && type.id() != null) {
            TYPES.put(type.id(), type);
        }
    }

    /** 取结构类型；未知返回 null */
    public static StructureType get(String id) {
        return id == null ? null : TYPES.get(id);
    }

    /** 取结构类型；未知或 id 为空时回退到 tree */
    public static StructureType getOrTree(String id) {
        StructureType t = get(id);
        return t != null ? t : TYPES.get("tree");
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }
}
