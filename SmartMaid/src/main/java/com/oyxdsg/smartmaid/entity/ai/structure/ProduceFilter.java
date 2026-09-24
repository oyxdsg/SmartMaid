package com.oyxdsg.smartmaid.entity.ai.structure;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 产物过滤（Atomic Command Protocol §5.3）：把 {@code produce} 参数在解析期编译成
 * {@code Predicate<BlockState>}，识别（find）与收割（harvest）共用同一谓词。
 *
 * <p>三种写法：精确 id（{@code minecraft:oak_log}）／tag（{@code #minecraft:oak_logs}）／
 * wood 家族（{@code oak} → oak_log/oak_wood/stripped_* 展开）。编译时顺带推导物种
 * {@code species}（仅当 produce 指向单一物种原木时），供 find 做「树叶同物种」对齐。</p>
 */
public final class ProduceFilter {

    /** 编译结果：块匹配谓词 + 推导出的物种（多物种/未知为 null） */
    public record Compiled(Predicate<BlockState> matcher, String species) {
        public boolean matchesAny() {
            return this.matcher != null;
        }
    }

    private static final String[] WOOD_SUFFIXES = {"_log", "_wood"};

    private ProduceFilter() {
    }

    public static Compiled compile(String produce) {
        if (produce == null || produce.trim().isEmpty()) {
            return new Compiled(s -> true, null);
        }
        String p = produce.trim();

        // tag：#minecraft:oak_logs / #logs
        if (p.startsWith("#")) {
            String tagName = p.substring(1);
            Identifier tagId = Identifier.tryParse(tagName);
            if (tagId == null) {
                return new Compiled(s -> false, null);
            }
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            String species = speciesFromPath(stripLogsSuffix(tagId.getPath()));
            return new Compiled(s -> s.is(tag), species);
        }

        // wood 家族：oak（无命名空间）
        if (!p.contains(":")) {
            String wood = p.toLowerCase(Locale.ROOT);
            Set<String> ids = woodFamilyIds(wood);
            return new Compiled(s -> {
                Identifier bid = BuiltInRegistries.BLOCK.getKey(s.getBlock());
                return bid != null && bid.getNamespace().equals("minecraft")
                        && ids.contains(bid.getPath());
            }, wood);
        }

        // 精确 id
        Identifier id = Identifier.tryParse(p);
        if (id == null) {
            return new Compiled(s -> false, null);
        }
        Block block = BuiltInRegistries.BLOCK.get(id).map(Holder::value).orElse(null);
        String species = speciesFromPath(id.getPath());
        return new Compiled(s -> block != null && s.getBlock() == block, species);
    }

    /** wood 家族展开：oak → oak_log / oak_wood / stripped_oak_log / stripped_oak_wood */
    private static Set<String> woodFamilyIds(String wood) {
        Set<String> out = new HashSet<>();
        for (String suffix : WOOD_SUFFIXES) {
            out.add(wood + suffix);
            out.add("stripped_" + wood + suffix);
        }
        return out;
    }

    /** 从方块 id path 推导物种：oak_log / stripped_oak_wood → oak */
    private static String speciesFromPath(String path) {
        if (path == null) {
            return null;
        }
        String p = path;
        if (p.startsWith("stripped_")) {
            p = p.substring("stripped_".length());
        }
        for (String suffix : WOOD_SUFFIXES) {
            if (p.endsWith(suffix)) {
                return p.substring(0, p.length() - suffix.length());
            }
        }
        if (p.endsWith("_leaves")) {
            return p.substring(0, p.length() - "_leaves".length());
        }
        return p;
    }

    /** tag path 去 logs 后缀：oak_logs → oak */
    private static String stripLogsSuffix(String path) {
        if (path != null && path.endsWith("_logs")) {
            return path.substring(0, path.length() - "_logs".length());
        }
        return path;
    }
}
