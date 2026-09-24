package com.oyxdsg.smartmaid.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.init.ModMenus;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 女仆背包菜单：槽位布局照搬原版 {@link InventoryMenu}（玩家背包界面）。
 * 女仆背包 41 格，索引与原版玩家背包一致：0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手。
 */
public class MaidInventoryMenu extends AbstractContainerMenu {
    public static final int DATA_SIT = 0;
    public static final int DATA_HEALTH = 1;
    public static final int DATA_HAS_TARGET = 2;
    /** 女仆实体 id（客户端据此渲染缩小模型预览） */
    public static final int DATA_MAID_ID = 3;
    public static final int DATA_COUNT = 4;

    /** 槽位编号：0-3 盔甲 / 4 副手 / 5-31 女仆背包(27) / 32-40 女仆热键(9) */
    public static final int ARMOR_START = 0;
    public static final int ARMOR_COUNT = 4;
    public static final int OFFHAND_INDEX = 4;
    public static final int INVENTORY_START = 5;

    private final SmartMaidEntity maid;
    private final SimpleContainer maidInventory;
    private final ContainerData maidData;

    /** 客户端构造：实体引用为 null，容器为本地同步副本 */
    public MaidInventoryMenu(int syncId, Inventory inv) {
        this(syncId, inv, null, new SimpleContainer(41), new SimpleContainerData(DATA_COUNT));
    }

    /** 服务端构造：实体引用有效，数据实时读取 */
    public MaidInventoryMenu(int syncId, Inventory inv, SmartMaidEntity maid) {
        this(syncId, inv, maid, maid.getMaidInventory(), new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_SIT -> maid.isOrderedToSit() ? 1 : 0;
                    case DATA_HEALTH -> (int) maid.getHealth();
                    case DATA_HAS_TARGET -> maid.getTarget() != null ? 1 : 0;
                    case DATA_MAID_ID -> maid.getId();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        });
    }

    private MaidInventoryMenu(int syncId, Inventory inv, SmartMaidEntity maid, SimpleContainer inventory, ContainerData data) {
        super(ModMenus.MAID_INVENTORY_MENU, syncId);
        this.maid = maid;
        this.maidInventory = inventory;
        this.maidData = data;

        // 盔甲 4 格（照搬原版坐标：x=8, y=8/26/44/62；index 与 syncSlot 一致：36=HEAD/37=CHEST/38=LEGS/39=FEET/40=OFFHAND）
        this.addSlot(new MaidArmorSlot(maid, inventory, EquipmentSlot.HEAD, 36, 8, 8, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET));
        this.addSlot(new MaidArmorSlot(maid, inventory, EquipmentSlot.CHEST, 37, 8, 26, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE));
        this.addSlot(new MaidArmorSlot(maid, inventory, EquipmentSlot.LEGS, 38, 8, 44, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS));
        this.addSlot(new MaidArmorSlot(maid, inventory, EquipmentSlot.FEET, 39, 8, 62, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS));
        // 副手（照搬原版坐标：x=77, y=62）
        this.addSlot(new MaidArmorSlot(maid, inventory, EquipmentSlot.OFFHAND, 40, 77, 62, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));

        // 女仆背包 27 格（照搬原版 addStandardInventorySlots：y=84/102/120）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, 9 + col + row * 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // 女仆热键 9 格（y=142）
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }

        this.addDataSlots(data);
    }

    public SmartMaidEntity getMaid() {
        return this.maid;
    }

    public int getSitState() {
        return this.maidData.get(DATA_SIT);
    }

    public int getHealthInt() {
        return this.maidData.get(DATA_HEALTH);
    }

    public int getHasTarget() {
        return this.maidData.get(DATA_HAS_TARGET);
    }

    public int getMaidId() {
        return this.maidData.get(DATA_MAID_ID);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < INVENTORY_START) {
            // 盔甲/副手 → 女仆背包
            if (!this.moveItemStackTo(stack, INVENTORY_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 女仆背包/热键 → 盔甲（若可装备）
            boolean moved = false;
            for (int i = 0; i < ARMOR_COUNT; i++) {
                if (this.slots.get(i).mayPlace(stack)) {
                    if (this.moveItemStackTo(stack, i, i + 1, false)) {
                        moved = true;
                        break;
                    }
                }
            }
            if (!moved) {
                // 背包 ↔ 热键
                if (index >= 32) {
                    this.moveItemStackTo(stack, INVENTORY_START, 32, false);
                } else {
                    this.moveItemStackTo(stack, 32, this.slots.size(), false);
                }
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY, original);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
