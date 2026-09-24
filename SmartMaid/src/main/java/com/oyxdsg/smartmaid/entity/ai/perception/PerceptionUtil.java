package com.oyxdsg.smartmaid.entity.ai.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;

/**
 * 感知工具：坐标/物品/效果/方块分类的 JSON 摘要写入辅助。
 *
 * <p>物品序列化采用"摘要式"：{@code id/count/max_stack} + 关键信息（耐久/附魔/自定义名），
 * 省 token（AI 上下文有限），需要详情时可另行查询。</p>
 */
public final class PerceptionUtil {

    private PerceptionUtil() {
    }

    // ---------- 坐标 ----------

    /** 写入浮点坐标数组 [x, y, z]（一位小数） */
    public static void pos(JsonObject o, Vec3 v) {
        JsonArray arr = new JsonArray();
        arr.add(Math.round(v.x * 10.0D) / 10.0D);
        arr.add(Math.round(v.y * 10.0D) / 10.0D);
        arr.add(Math.round(v.z * 10.0D) / 10.0D);
        o.add("pos", arr);
    }

    /** 写入整数方块坐标数组 [x, y, z] */
    public static void blockPos(JsonObject o, BlockPos p) {
        JsonArray arr = new JsonArray();
        arr.add(p.getX());
        arr.add(p.getY());
        arr.add(p.getZ());
        o.add("pos", arr);
    }

    // ---------- 物品摘要 ----------

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 把物品摘要写入对象：{@code id/count}；{@code full=true} 时附带
     * {@code max_stack}、耐久（damage/max_damage）、附魔（enchants）、自定义名（name）。
     * 空物品不写入任何字段（JSON 里字段缺失即空槽）。
     */
    public static void writeItem(JsonObject o, ItemStack stack, boolean full) {
        if (stack.isEmpty()) {
            return;
        }
        o.addProperty("id", itemId(stack));
        o.addProperty("count", stack.getCount());
        if (full) {
            o.addProperty("max_stack", stack.getMaxStackSize());
            if (stack.isDamaged()) {
                o.addProperty("damage", stack.getDamageValue());
                o.addProperty("max_damage", stack.getMaxDamage());
            }
            ItemEnchantments ench = stack.getEnchantments();
            if (!ench.isEmpty()) {
                JsonArray list = new JsonArray();
                ench.keySet().forEach(holder -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
                    e.addProperty("level", ench.getLevel(holder));
                    list.add(e);
                });
                o.add("enchants", list);
            }
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name != null) {
                o.addProperty("name", name.getString());
            }
        }
    }

    // ---------- 状态效果 ----------

    /** 把效果集合写入数组（id/等级/剩余 tick） */
    public static void writeEffects(JsonArray list, Collection<MobEffectInstance> effects) {
        for (MobEffectInstance effect : effects) {
            JsonObject e = new JsonObject();
            e.addProperty("id", effect.getEffect().unwrapKey()
                    .map(k -> k.identifier().toString()).orElse("?"));
            e.addProperty("amp", effect.getAmplifier());
            e.addProperty("duration", effect.getDuration());
            list.add(e);
        }
    }

    // ---------- 方块分类 ----------

    /**
     * 方块类型分类：air（空气）/ water / lava（岩浆/火）/ empty（无碰撞但非空气）/
     * solid（满高实心）/ half（半高：半砖/台阶）/ low（低矮：地毯等）。
     */
    public static String classifyBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return "air";
        }
        if (state.getFluidState().typeHolder().is(FluidTags.WATER)) {
            return "water";
        }
        if (state.getFluidState().typeHolder().is(FluidTags.LAVA) || state.getBlock() == Blocks.FIRE) {
            return "lava";
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return "empty";
        }
        double maxY = shape.max(Direction.Axis.Y);
        if (maxY >= 0.95D) {
            return "solid";
        }
        if (maxY >= 0.4D) {
            return "half";
        }
        return "low";
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
