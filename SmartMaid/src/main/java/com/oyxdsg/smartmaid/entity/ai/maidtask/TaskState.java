package com.oyxdsg.smartmaid.entity.ai.maidtask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import net.minecraft.core.BlockPos;

/** 任务快照的坐标序列化工具（{@code [x,y,z]} ↔ {@link BlockPos}）。 */
public final class TaskState {

    private TaskState() {
    }

    public static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    public static BlockPos readPos(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static JsonArray posList(Collection<BlockPos> c) {
        JsonArray arr = new JsonArray();
        for (BlockPos p : c) {
            arr.add(pos(p));
        }
        return arr;
    }

    public static List<BlockPos> readPosList(JsonElement e) {
        List<BlockPos> list = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                BlockPos p = readPos(x);
                if (p != null) {
                    list.add(p);
                }
            }
        }
        return list;
    }
}
