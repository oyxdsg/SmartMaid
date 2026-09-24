package com.oyxdsg.smartmaid.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.init.ModMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 女仆管理菜单：展示女仆状态（坐姿/生命/目标），无物品槽位。
 * 状态经 ContainerData 同步客户端；按钮操作通过网络包执行。
 */
public class MaidControlMenu extends AbstractContainerMenu {
    public static final int DATA_SIT = 0;
    public static final int DATA_HEALTH = 1;
    public static final int DATA_HAS_TARGET = 2;
    // 女仆设置（档位索引，客户端按索引显示文案；修改走 MaidSettingsPayload）
    public static final int DATA_FRIENDLY_FIRE = 3;
    public static final int DATA_FOLLOW_ENABLED = 4;
    public static final int DATA_FOLLOW_START = 5;
    public static final int DATA_FOLLOW_STOP = 6;
    public static final int DATA_PROTECT_MODE = 7;
    public static final int DATA_DEATH_DROP = 8;
    public static final int DATA_MAX_HEALTH = 9;
    public static final int DATA_HUNGER_RATE = 10;
    public static final int DATA_REGEN_RATE = 11;
    public static final int DATA_COUNT = 12;

    private final SmartMaidEntity maid;
    private final ContainerData maidData;

    /** 客户端构造：实体引用为 null */
    public MaidControlMenu(int syncId, Inventory inv) {
        this(syncId, inv, null, new SimpleContainerData(DATA_COUNT));
    }

    /** 服务端构造：实体引用有效，数据实时读取 */
    public MaidControlMenu(int syncId, Inventory inv, SmartMaidEntity maid) {
        this(syncId, inv, maid, new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_SIT -> maid.isOrderedToSit() ? 1 : 0;
                    case DATA_HEALTH -> (int) maid.getHealth();
                    case DATA_HAS_TARGET -> maid.getTarget() != null ? 1 : 0;
                    case DATA_FRIENDLY_FIRE -> maid.getSettings().isFriendlyFire() ? 1 : 0;
                    case DATA_FOLLOW_ENABLED -> maid.getSettings().isFollowEnabled() ? 1 : 0;
                    case DATA_FOLLOW_START -> maid.getSettings().getFollowStartIndex();
                    case DATA_FOLLOW_STOP -> maid.getSettings().getFollowStopIndex();
                    case DATA_PROTECT_MODE -> maid.getSettings().getProtectMode().ordinal();
                    case DATA_DEATH_DROP -> maid.getSettings().getDeathDropMode().ordinal();
                    case DATA_MAX_HEALTH -> maid.getSettings().getMaxHealthIndex();
                    case DATA_HUNGER_RATE -> maid.getSettings().getHungerRateIndex();
                    case DATA_REGEN_RATE -> maid.getSettings().getRegenRateIndex();
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

    private MaidControlMenu(int syncId, Inventory inv, SmartMaidEntity maid, ContainerData data) {
        super(ModMenus.MAID_CONTROL_MENU, syncId);
        this.maid = maid;
        this.maidData = data;
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

    /** 通用设置读取：客户端 UI 按 dataIndex 取当前档位索引 */
    public int getSetting(int dataIndex) {
        return this.maidData.get(dataIndex);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
