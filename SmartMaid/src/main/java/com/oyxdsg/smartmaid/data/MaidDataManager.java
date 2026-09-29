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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 女仆数据存储：装备/物品栏以 NBT 文件保存，女仆退出游戏即消失（noSave），
 * 重新召唤时从文件恢复数据。
 *
 * <p><b>2026-09-29 改造（整合包兼容）</b>：存储位置从全局 {@code config/smartmaid/maids/}
 * （<b>不随存档隔离</b>）改为 <b>存档目录</b> {@code <存档>/smartmaid/maids/}。</p>
 *
 * <p>原因：{@code config/} 是全局目录，按玩家 UUID 命名的文件会跨存档共享 ——
 * 同一个玩家在 A 存档的女仆装备会原样出现在 B 存档（整合包玩家普遍有多个存档）。
 * 另外整合包自带 {@code config} 覆盖时，可能连玩家个人数据一起覆盖掉。</p>
 *
 * <p>兼容：旧路径的文件在首次读取时<b>一次性复制</b>到新位置（旧文件保留，便于回滚）；
 * 迁移失败则继续使用旧路径，绝不丢数据。</p>
 */
public final class MaidDataManager {

    /** 旧位置（全局 config）：仅用于一次性迁移与兜底 */
    private static final Path LEGACY_DIR =
            FabricLoader.getInstance().getConfigDir().resolve("smartmaid").resolve("maids");

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
            Path file = fileFor(maid, owner);
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
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
        Path file = fileFor(maid, owner);
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
            Files.deleteIfExists(fileFor(maid, owner));
            // 旧位置也要删：否则下次召唤会把"死亡前"的旧数据迁移回来
            Files.deleteIfExists(LEGACY_DIR.resolve(owner + ".dat"));
        } catch (IOException e) {
            SmartMaid.LOGGER.error("删除女仆存档失败", e);
        }
    }

    /**
     * 解析某玩家的女仆数据文件。
     *
     * <p>优先取<b>存档目录</b>；若不存在但旧位置（全局 config）有，则一次性复制过来；
     * 复制失败时回退到旧路径 —— 宁可继续用旧位置，也不让玩家丢装备。</p>
     */
    private static Path fileFor(SmartMaidEntity maid, UUID owner) {
        Path modern = dirFor(maid).resolve(owner + ".dat");
        if (Files.exists(modern)) {
            return modern;
        }
        Path legacy = LEGACY_DIR.resolve(owner + ".dat");
        if (!Files.exists(legacy)) {
            return modern;
        }
        try {
            Files.createDirectories(modern.getParent());
            Files.copy(legacy, modern, StandardCopyOption.COPY_ATTRIBUTES);
            SmartMaid.LOGGER.info("女仆数据已迁移到存档目录: {}", modern);
            return modern;
        } catch (IOException e) {
            SmartMaid.LOGGER.warn("女仆数据迁移失败，继续使用旧路径: {}", e.toString());
            return legacy;
        }
    }

    /** 存档目录下的女仆数据目录（按存档隔离）；拿不到服务器时回退旧路径 */
    private static Path dirFor(SmartMaidEntity maid) {
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            return LEGACY_DIR;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("smartmaid").resolve("maids");
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
