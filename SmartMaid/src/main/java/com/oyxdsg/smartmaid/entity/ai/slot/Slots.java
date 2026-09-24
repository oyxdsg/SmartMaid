package com.oyxdsg.smartmaid.entity.ai.slot;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 槽位解析与实现：{@link ItemSlot} 的字符串表达式解析 + 各具体槽位实现。
 *
 * <p>表达式格式（{@link #parse}）：</p>
 * <ul>
 *     <li>女仆槽：{@code inv:<0-40>} 主手=0 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手</li>
 *     <li>别名：{@code mainhand/offhand/head/chest/legs/feet}（映射到 inv 对应下标）</li>
 *     <li>地面：{@code world:<x>,<y>,<z>} — 只作转移目标（放置方块/丢出物品），不作源
 *         （地面拾取有专门的走过去过程，见 collect/pickup 指令）</li>
 *     <li>容器：{@code container:<x>,<y>,<z>[:<slot>|auto]} — 目标缺省 auto 找空槽/堆叠；源缺省 auto 找首个非空</li>
 * </ul>
 */
public final class Slots {

    private Slots() {
    }

    /** 主手槽（背包热键第 0 格） */
    public static ItemSlot mainhand() {
        return new MaidSlot(0);
    }

    /** 指定容器位置槽位（slot=-1 表示 auto：插入找空槽、提取找首个非空） */
    public static ItemSlot container(BlockPos pos, int slot) {
        return new ContainerSlot(pos, slot);
    }

    /** 判断槽位是否为"地面"（world：只能作转移目标，不能作源） */
    public static boolean isWorldSlot(ItemSlot slot) {
        return slot instanceof WorldSlot;
    }

    /** 解析槽位表达式；无法解析返回 null */
    public static ItemSlot parse(String spec) {
        if (spec == null) {
            return null;
        }
        // 兼容中文输入法的全角符号：：，、全角空格
        String s = spec.trim()
                .replace('：', ':')
                .replace('，', ',')
                .replace('\u3000', ' ')
                .replace('－', '-')
                .trim();
        switch (s) {
            case "mainhand" -> {
                return new MaidSlot(0);
            }
            case "offhand" -> {
                return new MaidSlot(40);
            }
            case "head" -> {
                return new MaidSlot(36);
            }
            case "chest" -> {
                return new MaidSlot(37);
            }
            case "legs" -> {
                return new MaidSlot(38);
            }
            case "feet" -> {
                return new MaidSlot(39);
            }
            default -> {
            }
        }
        if (s.startsWith("inv:")) {
            try {
                return new MaidSlot(Integer.parseInt(s.substring(4)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 宽容写法：inv5 或纯数字 5（都指背包槽位 5）
        if (s.startsWith("inv")) {
            try {
                return new MaidSlot(Integer.parseInt(s.substring(3)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return new MaidSlot(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            // 继续尝试其他类型
        }
        if (s.startsWith("world:")) {
            int[] xyz = parseXYZ(s.substring(6));
            return xyz == null ? null : new WorldSlot(new BlockPos(xyz[0], xyz[1], xyz[2]));
        }
        if (s.startsWith("container:")) {
            String rest = s.substring(10);
            int colon = rest.lastIndexOf(':');
            if (colon > 0 && rest.indexOf(',') < colon) {
                int[] xyz = parseXYZ(rest.substring(0, colon));
                if (xyz == null) {
                    return null;
                }
                String slotPart = rest.substring(colon + 1);
                int slot = slotPart.equals("auto") ? -1 : Integer.parseInt(slotPart);
                return new ContainerSlot(new BlockPos(xyz[0], xyz[1], xyz[2]), slot);
            }
            int[] xyz = parseXYZ(rest);
            return xyz == null ? null : new ContainerSlot(new BlockPos(xyz[0], xyz[1], xyz[2]), -1);
        }
        return null;
    }

    private static int[] parseXYZ(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 女仆自身槽位（背包/手持/盔甲，全部映射到 41 格 SimpleContainer） */
    private static final class MaidSlot implements ItemSlot {
        private final int index;

        MaidSlot(int index) {
            this.index = index;
        }

        @Override
        public BlockPos reachPos() {
            return null; // 女仆自身，无需移动
        }

        @Override
        public boolean canAccept(SmartMaidEntity maid, ItemStack stack) {
            // 盔甲槽（36-39）只接受对应部位的装备，否则 transfer 掉落而非硬塞
            if (this.index >= 36 && this.index <= 39 && !stack.isEmpty()) {
                net.minecraft.world.entity.EquipmentSlot es = switch (this.index) {
                    case 36 -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                    case 37 -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                    case 38 -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                    default -> net.minecraft.world.entity.EquipmentSlot.FEET;
                };
                return maid.isEquippableInSlot(stack, es);
            }
            return true;
        }

        @Override
        public ItemStack peek(SmartMaidEntity maid) {
            return maid.getMaidInventory().getItem(this.index);
        }

        @Override
        public ItemStack extract(SmartMaidEntity maid, int count) {
            SimpleContainer inv = maid.getMaidInventory();
            ItemStack stack = inv.getItem(this.index);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = inv.removeItem(this.index, Math.min(count, stack.getCount()));
            inv.setChanged();
            return result;
        }

        @Override
        public ItemStack insert(SmartMaidEntity maid, ItemStack stack) {
            SimpleContainer inv = maid.getMaidInventory();
            ItemStack work = stack.copy();
            ItemStack existing = inv.getItem(this.index);
            if (existing.isEmpty()) {
                inv.setItem(this.index, work);
                inv.setChanged();
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(existing, work)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int move = Math.min(room, work.getCount());
                existing.grow(move);
                work.shrink(move);
                inv.setChanged();
            }
            return work; // 槽满或占用不同物品 → 剩余原样返回
        }

        @Override
        public String toString() {
            String name = switch (this.index) {
                case 0 -> "mainhand";
                case 36 -> "head";
                case 37 -> "chest";
                case 38 -> "legs";
                case 39 -> "feet";
                case 40 -> "offhand";
                default -> String.valueOf(this.index);
            };
            return "槽位[" + name + "]";
        }
    }

    /**
     * 地面槽位：只作为<b>转移目标</b>（放置方块 / 丢出物品）。
     *
     * <p><b>不支持作为源</b>：地面→背包的拾取必须由女仆走过去通过原版机制完成
     * （见 {@code collect} / {@code pickup} 指令），避免"凭空吸物"的观感问题。</p>
     */
    private static final class WorldSlot implements ItemSlot {
        private final BlockPos pos;

        WorldSlot(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public BlockPos reachPos() {
            return this.pos;
        }

        @Override
        public ItemStack peek(SmartMaidEntity maid) {
            return ItemStack.EMPTY; // 地面不作读取源
        }

        @Override
        public ItemStack extract(SmartMaidEntity maid, int count) {
            return ItemStack.EMPTY; // 地面不作转移源，拾取走 collect/pickup（有走过去的过程）
        }

        @Override
        public String toString() {
            return "地面" + this.pos;
        }

        @Override
        public ItemStack insert(SmartMaidEntity maid, ItemStack stack) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            Level level = maid.level();
            // 方块物品 → 放置到目标格（place 成功会内部 shrink，返回剩余）
            if (stack.getItem() instanceof BlockItem blockItem) {
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(this.pos).add(0.5D, 0.5D, 0.5D),
                        net.minecraft.core.Direction.UP, this.pos, false);
                blockItem.place(
                        new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit));
                return stack;
            }
            // 非方块 → 生成掉落物丢出
            ItemEntity item = new ItemEntity(level,
                    this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D, stack);
            item.setNoPickUpDelay();
            level.addFreshEntity(item);
            // 丢出后女仆短时间内不捡（避免「丢给主人又马上被自己捡回」）
            maid.suppressPickup(100);
            return ItemStack.EMPTY;
        }
    }

    /** 容器槽位（箱子/熔炉等 Container 方块实体）；slot=-1 表示 auto */
    private static final class ContainerSlot implements ItemSlot {
        private final BlockPos pos;
        private final int slot;

        ContainerSlot(BlockPos pos, int slot) {
            this.pos = pos;
            this.slot = slot;
        }

        @Override
        public BlockPos reachPos() {
            return this.pos;
        }

        @Override
        public ItemStack peek(SmartMaidEntity maid) {
            Container container = getContainer(maid);
            if (container == null) {
                return ItemStack.EMPTY;
            }
            int idx = resolveSlot(container, this.slot, false);
            return idx < 0 ? ItemStack.EMPTY : container.getItem(idx);
        }

        @Override
        public ItemStack extract(SmartMaidEntity maid, int count) {
            Container container = getContainer(maid);
            if (container == null) {
                return ItemStack.EMPTY;
            }
            int idx = resolveSlot(container, this.slot, false);
            if (idx < 0) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = container.getItem(idx);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = container.removeItem(idx, Math.min(count, stack.getCount()));
            container.setChanged();
            return result;
        }

        @Override
        public boolean isSwapAllowed() {
            return false; // 容器满时不取出内容交换
        }

        @Override
        public ItemStack insert(SmartMaidEntity maid, ItemStack stack) {
            Container container = getContainer(maid);
            if (container == null) {
                return stack.copy(); // 无容器（可能未走到/位置不对）
            }
            ItemStack work = stack.copy();
            int size = container.getContainerSize();
            // 1. 优先：同类且未满的格子（智能堆叠，自动按 maxStackSize 判断 64/16/1）
            for (int i = 0; i < size; i++) {
                ItemStack existing = container.getItem(i);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, work)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    int move = Math.min(room, work.getCount());
                    existing.grow(move);
                    work.shrink(move);
                    container.setChanged();
                    if (work.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            // 2. 其次：空槽（含不可堆叠物品如盔甲的单放）
            for (int i = 0; i < size; i++) {
                if (container.getItem(i).isEmpty()) {
                    container.setItem(i, work);
                    container.setChanged();
                    return ItemStack.EMPTY;
                }
            }
            // 3. 容器满 → 返回剩余（TransferTask 会放回源并提示，不掉落）
            return work;
        }

        @Override
        public String toString() {
            return "容器" + this.pos + (this.slot < 0 ? "[auto]" : "[槽" + this.slot + "]");
        }

        private Container getContainer(SmartMaidEntity maid) {
            BlockEntity be = maid.level().getBlockEntity(this.pos);
            return be instanceof Container container ? container : null;
        }

        /**
         * 解析实际槽位下标。auto 时：目标找空槽/同类堆叠；源找首个非空槽。
         *
         * @param wantInsert true=作为插入目标（找空槽/可堆叠）；false=作为提取源（找非空）
         */
        private int resolveSlot(Container container, int fixed, boolean wantInsert) {
            if (fixed >= 0 && fixed < container.getContainerSize()) {
                return fixed;
            }
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (wantInsert && stack.isEmpty()) {
                    return i;
                }
                if (!wantInsert && !stack.isEmpty()) {
                    return i;
                }
            }
            return -1;
        }
    }
}
