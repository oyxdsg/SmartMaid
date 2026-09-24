package com.oyxdsg.smartmaid.data;

import com.mojang.serialization.DataResult;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 女仆本地数据存储：装备/物品栏以 NBT 文件保存到 config/smartmaid/maids/&lt;玩家UUID&gt;.dat。
 * 女仆退出游戏即消失（noSave），重新召唤时从此文件恢复数据。
 */
public final class MaidDataManager {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("smartmaid").resolve("maids");

    private MaidDataManager() {
    }

    public static void save(SmartMaidEntity maid) {
        if (maid.level().isClientSide()) {
            return;
        }
        UUID owner = maid.getOwnerReference() == null ? null : maid.getOwnerReference().getUUID();
        if (owner == null) {
            return;
        }
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess());
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                ItemStack stack = maid.getItemBySlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                CompoundTag slotTag = new CompoundTag();
                slotTag.putString("slot", slot.getName());
                DataResult<Tag> encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
                encoded.result().ifPresent(t -> slotTag.put("item", t));
                list.add(slotTag);
            }
            tag.put("equipment", list);
            // 女仆背包（41 格）
            ListTag invList = new ListTag();
            for (int i = 0; i < maid.getMaidInventory().getContainerSize(); i++) {
                ItemStack stack = maid.getMaidInventory().getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("slot", i);
                DataResult<Tag> encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
                encoded.result().ifPresent(t -> slotTag.put("item", t));
                invList.add(slotTag);
            }
            tag.put("inventory", invList);
            Files.createDirectories(DIR);
            NbtIo.writeCompressed(tag, fileFor(owner));
        } catch (IOException e) {
            SmartMaid.LOGGER.error("保存女仆数据失败", e);
        }
    }

    public static void load(SmartMaidEntity maid) {
        if (maid.level().isClientSide()) {
            return;
        }
        UUID owner = maid.getOwnerReference() == null ? null : maid.getOwnerReference().getUUID();
        if (owner == null) {
            return;
        }
        Path file = fileFor(owner);
        if (!Files.exists(file)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess());
            ListTag list = tag.getListOrEmpty("equipment");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag slotTag = list.getCompoundOrEmpty(i);
                EquipmentSlot slot = EquipmentSlot.byName(slotTag.getStringOr("slot", ""));
                Tag itemTag = slotTag.get("item");
                if (slot == null || itemTag == null) {
                    continue;
                }
                DataResult<ItemStack> decoded = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag);
                decoded.result().ifPresent(stack -> maid.setItemSlot(slot, stack));
            }
            // 女仆背包（41 格）
            ListTag invList = tag.getListOrEmpty("inventory");
            for (int i = 0; i < invList.size(); i++) {
                CompoundTag slotTag = invList.getCompoundOrEmpty(i);
                Tag itemTag = slotTag.get("item");
                if (itemTag == null) {
                    continue;
                }
                DataResult<ItemStack> decoded = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag);
                int idx = slotTag.getIntOr("slot", -1);
                if (idx < 0 || idx >= maid.getMaidInventory().getContainerSize()) {
                    continue;
                }
                ItemStack stack = decoded.result().orElse(ItemStack.EMPTY);
                if (stack.isEmpty()) {
                    continue;
                }
                // 盔甲槽（36-39）：只允许对应部位的装备，其余移到背包区，防止"穿戴任何物品"；
                // 副手（40）像玩家一样允许任意物品，不做校验
                if (idx >= 36) {
                    EquipmentSlot es = armorSlotForIndex(idx);
                    if (es == null || (idx != 40 && !maid.isEquippableInSlot(stack, es))) {
                        idx = -1;
                        ItemStack remaining = maid.moveToBackpack(stack);
                        if (!remaining.isEmpty()) {
                            continue; // 背包满，丢弃该非法物品
                        }
                    }
                }
                if (idx >= 0) {
                    maid.getMaidInventory().setItem(idx, stack);
                }
            }
        } catch (IOException e) {
            SmartMaid.LOGGER.error("读取女仆数据失败", e);
        }
    }

    /** 女仆死亡时清空存档：重新召唤不再恢复死亡前的背包/装备（掉落物已由死亡逻辑产出） */
    public static void delete(SmartMaidEntity maid) {
        UUID owner = maid.getOwnerReference() == null ? null : maid.getOwnerReference().getUUID();
        if (owner == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(owner));
        } catch (IOException e) {
            SmartMaid.LOGGER.error("删除女仆存档失败", e);
        }
    }

    private static Path fileFor(UUID owner) {
        return DIR.resolve(owner + ".dat");
    }

    /** 背包槽索引 → 装备部位（36-39 盔甲，40 副手；其他返回 null） */
    private static EquipmentSlot armorSlotForIndex(int index) {
        return switch (index) {
            case 36 -> EquipmentSlot.HEAD;
            case 37 -> EquipmentSlot.CHEST;
            case 38 -> EquipmentSlot.LEGS;
            case 39 -> EquipmentSlot.FEET;
            case 40 -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }
}
