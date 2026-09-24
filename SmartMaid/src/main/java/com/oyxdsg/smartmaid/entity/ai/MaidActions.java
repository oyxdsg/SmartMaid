package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.SimpleContainer;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 女仆能力层（M1）：AI 任务可用的原子动作集合。
 *
 * <p>每个方法操作 {@link SmartMaidEntity}，全部带低频 debug 日志，服务端执行。</p>
 *
 * <p>与现有体系关系：移动复用 {@code navigation.moveTo}（跳跃由 {@link MaidActionExecutor}
 * 自动接管）；方块破坏用原版 {@code destroyBlock} 生成掉落物，靠女仆 {@code setCanPickUpLoot}
 * 自动拾取进背包（0-35）；方块放置借鉴车万女仆 null-player {@link BlockPlaceContext} 方案。</p>
 */
public final class MaidActions {

    /** 贵重/危险/特殊方块：搭路绝不消耗（矿物块、箱子、TNT、刷怪笼、基岩等） */
    private static final Set<Block> VALUABLE_BLOCKS = new HashSet<>(List.of(
            Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.NETHERITE_BLOCK,
            Blocks.RAW_IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.RAW_COPPER_BLOCK,
            Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.COAL_BLOCK,
            Blocks.QUARTZ_BLOCK, Blocks.AMETHYST_BLOCK,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX,
            Blocks.TNT, Blocks.RESPAWN_ANCHOR, Blocks.BEACON, Blocks.CONDUIT,
            Blocks.SPAWNER, Blocks.DRAGON_EGG, Blocks.BEDROCK,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.JUKEBOX, Blocks.CARTOGRAPHY_TABLE, Blocks.GRINDSTONE, Blocks.LOOM));

    private MaidActions() {
    }

    // ---------- 移动 ----------

    /** 寻路到目标格中心（跳跃自动生效，无需额外处理） */
    public static boolean navigateTo(SmartMaidEntity maid, BlockPos pos, double speed) {
        if (maid.level().isClientSide()) {
            return false;
        }
        return maid.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, speed);
    }

    /** 距目标格中心的水平距离是否在 range 内 */
    public static boolean isWithinReach(SmartMaidEntity maid, BlockPos pos, double range) {
        double dx = maid.getX() - (pos.getX() + 0.5D);
        double dz = maid.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * 是否能「直接挖到」目标方块（对齐玩家能力）：水平够得着 + 垂直够得着 + 视线不被挡。
     *
     * <p>三者任一不满足都不能直接挖：被墙挡 → 先挖开挡路方块；够不着（太高/太低）→
     * 先搭高/下到可挖位置。调用方（挖掘执行器）据此决定「继续导航（让寻路降级挖墙/搭高）」
     * 而不是直接开挖。</p>
     */
    public static boolean canMineBlock(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide()) {
            return true;
        }
        // 水平够得着（同 isWithinReach 3 格）
        double dx = maid.getX() - (pos.getX() + 0.5D);
        double dz = maid.getZ() - (pos.getZ() + 0.5D);
        if (dx * dx + dz * dz > 9.0D) {
            return false;
        }
        // 垂直够得着：以女仆身体中心为基准，可挖范围 [-2.0, +2.5]（脚下一格 ~ 头顶两格）
        double dy = (pos.getY() + 0.5D) - (maid.getY() + 0.9D);
        if (dy < -2.0D || dy > 2.5D) {
            return false;
        }
        // 视线不被非目标方块挡（玩家准星被挡就点不到）
        return canReachSurface(maid, pos);
    }

    // ---------- 背包操作（41 格：0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手） ----------

    /** 在背包 0-40 查找符合条件物品的槽位（-1 = 未找到） */
    public static int findInBackpack(SmartMaidEntity maid, Predicate<ItemStack> predicate) {
        SimpleContainer inv = maid.getMaidInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (predicate.test(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从背包取符合条件物品放到主手（热键0，与槽0交换），并同步装备栏。
     *
     * @return true 表示主手已持有目标物品
     */
    public static boolean equipFromBackpack(SmartMaidEntity maid, Predicate<ItemStack> predicate) {
        SimpleContainer inv = maid.getMaidInventory();
        if (predicate.test(inv.getItem(0))) {
            return true;
        }
        int slot = findInBackpack(maid, predicate);
        if (slot <= 0) {
            return false;
        }
        ItemStack hand = inv.getItem(0);
        inv.setItem(0, inv.getItem(slot));
        inv.setItem(slot, hand);
        inv.setChanged();
        maid.syncInventoryArmor();
        MaidDebug.log("equip: 背包槽 " + slot + " -> 主手");
        return true;
    }

    /** 把物品收纳进背包区（0-35）：先同类堆叠，再找空槽；返回未放下的剩余。 */
    public static ItemStack storeToBackpack(SmartMaidEntity maid, ItemStack stack) {
        SimpleContainer inv = maid.getMaidInventory();
        ItemStack remaining = stack.copy();
        for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            ItemStack existing = inv.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int room = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(room);
                remaining.shrink(room);
            }
        }
        for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, remaining);
                remaining = ItemStack.EMPTY;
            }
        }
        inv.setChanged();
        return remaining;
    }

    /** 是否为可食用食物（有 FOOD data component） */
    public static boolean isFood(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null;
    }

    /**
     * 找食物槽：{@code itemFilter} 为空时选营养最高（其次饱和度最高）的食物；否则选该物品。
     *
     * @return 背包槽位（-1 = 未找到）
     */
    public static int findFoodSlot(SmartMaidEntity maid, Item itemFilter) {
        SimpleContainer inv = maid.getMaidInventory();
        int bestSlot = -1;
        int bestNutrition = -1;
        float bestSaturation = -1.0F;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (itemFilter != null && !stack.is(itemFilter)) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            if (food.nutrition() > bestNutrition
                    || (food.nutrition() == bestNutrition && food.saturation() > bestSaturation)) {
                bestNutrition = food.nutrition();
                bestSaturation = food.saturation();
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** 把指定背包槽换到主手（热键 0）并同步装备栏。 */
    public static boolean swapToMainhand(SmartMaidEntity maid, int slot) {
        SimpleContainer inv = maid.getMaidInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) {
            return false;
        }
        if (slot != 0) {
            ItemStack hand = inv.getItem(0);
            inv.setItem(0, inv.getItem(slot));
            inv.setItem(slot, hand);
            inv.setChanged();
        }
        maid.syncInventoryArmor();
        return true;
    }

    /** 开始进食主手食物（原版 startUsingItem；LivingEntity.tick 自动推进并在完成时消费）。 */
    public static boolean startEating(SmartMaidEntity maid) {
        if (maid.getMainHandItem().get(DataComponents.FOOD) == null) {
            return false;
        }
        maid.startUsingItem(InteractionHand.MAIN_HAND);
        return true;
    }

    // ---------- 方块操作 ----------

    /**
     * 目标方块是否「表面可直达」（对齐玩家能力）：从女仆眼睛向目标方块中心做射线，
     * 若射线被**非目标方块的碰撞体**挡住（如透过一层墙挖墙后的方块），返回 false。
     *
     * <p>玩家挖掘时必须点得到目标表面——前面隔一个方块就挖不到，得先挖开遮挡物。
     * 女仆的直线降级/挖矿/收割都应遵守同一规则，避免「穿墙挖」。</p>
     */
    public static boolean canReachSurface(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide()) {
            return true;
        }
        BlockPos hit = lineHit(maid, pos);
        // 射线直接命中目标方块本身（或未命中任何方块——目标已空）→ 可挖
        return hit == null || hit.equals(pos);
    }

    /**
     * 从女仆眼睛向目标方块中心做射线，返回命中的**第一个方块**（含目标自身）。
     * 玩家挖掘的本质就是「准星命中哪个方块挖哪个」——被挡时命中的是挡路块，不是目标。
     *
     * @return 命中的方块 pos；未命中（目标已空/路径全空）返回 null
     */
    public static BlockPos lineHit(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide()) {
            return null;
        }
        Vec3 eye = maid.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        BlockHitResult hit = maid.level().clip(new ClipContext(
                eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }

    /** 背包（含主手）是否持有可放置方块物品 */
    public static boolean hasBlockItem(SmartMaidEntity maid) {
        return maid.getMainHandItem().getItem() instanceof BlockItem
                || findInBackpack(maid, s -> s.getItem() instanceof BlockItem) >= 0;
    }

    /** 从背包找任意方块物品换到主手（搭路/建造前置）；找不到返回 false */
    public static boolean equipBlockFromBackpack(SmartMaidEntity maid) {
        if (maid.getMainHandItem().getItem() instanceof BlockItem) {
            return true;
        }
        return equipFromBackpack(maid, s -> s.getItem() instanceof BlockItem);
    }

    /** 统计背包区（0-35）可放置方块物品总个数（搭路余量判断用） */
    public static int countBlockItems(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem) {
                count += s.getCount();
            }
        }
        return count;
    }

    // ---------- 搭路方块选择（只用普通建材，不浪费贵重物品） ----------

    /** 是否为可用来搭路的方块：必须是非贵重/非危险/非特殊的 BlockItem */
    public static boolean isBridgeBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return !VALUABLE_BLOCKS.contains(blockItem.getBlock());
    }

    /** 搭路方块优先级：分数越小越优先消耗（0=泥土/圆石/石头等最普通；重力方块排最后） */
    private static int bridgePriority(Block block) {
        if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.COBBLESTONE
                || block == Blocks.STONE || block == Blocks.COBBLED_DEEPSLATE
                || block == Blocks.DEEPSLATE || block == Blocks.MUD) {
            return 0;
        }
        if (block == Blocks.SAND || block == Blocks.GRAVEL) {
            return 9; // 重力方块悬空会下落，最后才用
        }
        return 3; // 木板/原木/花岗岩等普通建材
    }

    /** 背包（含主手）是否有可搭路的普通方块 */
    public static boolean hasBridgeBlock(SmartMaidEntity maid) {
        return isBridgeBlock(maid.getMainHandItem())
                || findInBackpack(maid, MaidActions::isBridgeBlock) >= 0;
    }

    /**
     * 从背包按优先级挑一个可搭路的普通方块换到主手（越普通/数量越多越优先）。
     *
     * @return true 表示主手已持有可搭路方块
     */
    public static boolean equipBridgeBlockFromBackpack(SmartMaidEntity maid) {
        if (isBridgeBlock(maid.getMainHandItem())) {
            return true;
        }
        SimpleContainer inv = maid.getMaidInventory();
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!isBridgeBlock(s)) {
                continue;
            }
            Block block = ((BlockItem) s.getItem()).getBlock();
            // 优先级为主，数量次之（同优先级先消耗数量多的）
            int score = bridgePriority(block) * 100 - Math.min(s.getCount(), 99);
            if (score < bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack hand = inv.getItem(0);
        inv.setItem(0, inv.getItem(bestSlot));
        inv.setItem(bestSlot, hand);
        inv.setChanged();
        maid.syncInventoryArmor();
        MaidDebug.log("equipBridgeBlock: 槽 " + bestSlot + " -> 主手 " + MaidDebug.itemName(inv.getItem(0)));
        return true;
    }

    /** 统计背包区（0-35）可搭路的普通方块总个数 */
    public static int countBridgeBlocks(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (isBridgeBlock(s)) {
                count += s.getCount();
            }
        }
        return count;
    }

    /**
     * 破坏指定方块并把掉落物直接收进背包（0-35）：swing + 工具耐久损耗 +
     * {@link Block#getDrops} 按工具（含时运附魔）计算掉落 → {@link #storeToBackpack}。
     * 背包满的剩余用 {@link Block#popResource} 掉到方块位置（避免凭空消失）。
     */
    public static boolean breakBlock(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (!isWithinReach(maid, pos, 4.0D)) {
            return false;
        }
        maid.swing(InteractionHand.MAIN_HAND);
        ItemStack tool = maid.getMainHandItem();
        if (!tool.isEmpty()) {
            tool.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
        }
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, maid, tool)) {
            ItemStack left = storeToBackpack(maid, drop);
            if (!left.isEmpty()) {
                Block.popResource(level, pos, left);
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        MaidDebug.log("breakBlock " + pos + " " + state.getBlock());
        return true;
    }

    /**
     * 挖掘前自动换"最优工具"（对齐玩家挖掘判定）：从背包选对目标方块
     * {@code isCorrectToolForDrops} 且挖掘速度最高的工具换到主手。
     * 26.2 已移除 Tier 等级与 DiggerItem，改由 {@code Tool} 组件驱动——
     * 用「对目标方块的挖掘速度」作为最优判据（速度高即材料等级高，金/铜更快）。
     * 每挖一个方块判定一次，保证换手（石头→镐、泥土→铲、木头→斧）。
     *
     * <p>无正确工具时保持徒手，符合玩家判定（徒手挖需工具的方块不掉落）。</p>
     *
     * @return true 表示主手已持有对目标方块有效的工具
     */
    public static boolean equipBestToolFor(SmartMaidEntity maid, BlockState state) {
        SimpleContainer inv = maid.getMaidInventory();
        int bestSlot = -1;
        float bestSpeed = -1.0F;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            MaidDebug.log("equipBestTool: 背包无正确工具，徒手挖 " + state.getBlock());
            return false;
        }
        // 主手已是正确工具且速度不低于背包最优 → 不换
        ItemStack hand = inv.getItem(0);
        if (hand.isCorrectToolForDrops(state) && hand.getDestroySpeed(state) >= bestSpeed) {
            return true;
        }
        inv.setItem(0, inv.getItem(bestSlot));
        inv.setItem(bestSlot, hand);
        inv.setChanged();
        // 无条件强制同步主手到客户端（setItemInHand 走 setItemSlot，绕过 syncSlot 的
        // isSameItemSameComponents 跳过判断），保证渲染层立即显示换好的工具
        maid.setItemInHand(InteractionHand.MAIN_HAND, inv.getItem(0));
        maid.syncInventoryArmor();
        MaidDebug.log("equipBestTool: 槽 " + bestSlot + " -> 主手 " + MaidDebug.itemName(inv.getItem(0)));
        return true;
    }

    /**
     * 计算挖掘目标方块所需的总 tick（与玩家挖掘速度一致）：
     * 挖掘 tick = hardness × 100 / digSpeed（非创造，1 硬度徒手 ≈ 5 秒 = 100 tick；
     * 拿对工具 isCorrectToolForDrops 时按工具速度加速）。至少 5 tick 保证有挖掘过程。
     *
     * @return -1 表示方块不可破坏
     */
    public static int computeMiningTicks(SmartMaidEntity maid, BlockPos pos) {
        BlockState state = maid.level().getBlockState(pos);
        float hardness = state.getDestroySpeed(maid.level(), pos);
        if (hardness < 0.0F || state.isAir()) {
            return -1;
        }
        if (hardness == 0.0F) {
            return 1;
        }
        double ticks = Math.ceil(hardness * 100.0D); // 徒手基准：1 硬度 ≈ 5 秒
        ItemStack tool = maid.getMainHandItem();
        if (!tool.isEmpty() && tool.isCorrectToolForDrops(state)) {
            float speed = tool.getDestroySpeed(state);
            if (speed > 0.0F) {
                ticks = Math.ceil(hardness * 100.0D / speed);
            }
        }
        return Math.max(5, (int) ticks);
    }

    /** 显示/更新方块挖掘裂纹进度（stage 0-9；传 -1 清除裂纹） */
    public static void showMiningProgress(SmartMaidEntity maid, BlockPos pos, int stage) {
        if (maid.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(maid.getId(), pos, Math.max(-1, Math.min(9, stage)));
        }
    }

    /**
     * 手持方块放置到目标格（null-player 方案，借鉴车万女仆）。
     * 放置成功由 {@link BlockItem#place} 内部扣减手持物品。
     */
    public static boolean placeBlock(SmartMaidEntity maid, BlockPos pos, Direction dir) {
        ItemStack stack = maid.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos).add(dir.getStepX() * 0.5D, dir.getStepY() * 0.5D, dir.getStepZ() * 0.5D),
                dir, pos, false);
        InteractionResult result = blockItem.place(
                new BlockPlaceContext(maid.level(), null, InteractionHand.MAIN_HAND, stack, hit));
        boolean ok = result.consumesAction();
        MaidDebug.log("placeBlock " + pos + " " + stack.getItem().getDescriptionId() + " -> " + ok);
        return ok;
    }

    /** 右键使用手持物品于目标格（播种/火把/骨粉等；null-player 方案） */
    public static boolean useItemOn(SmartMaidEntity maid, BlockPos pos, Direction dir) {
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos).add(dir.getStepX() * 0.5D, dir.getStepY() * 0.5D, dir.getStepZ() * 0.5D),
                dir, pos, false);
        InteractionResult result = stack.useOn(
                new UseOnContext(maid.level(), null, InteractionHand.MAIN_HAND, stack, hit));
        boolean ok = result.consumesAction();
        MaidDebug.log("useItemOn " + pos + " " + stack.getItem().getDescriptionId() + " -> " + ok);
        return ok;
    }

    // ---------- 战斗 ----------

    /** 近战攻击目标（注意 26.2 签名：doHurtTarget 需要 ServerLevel 参数） */
    public static boolean attack(SmartMaidEntity maid, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return false;
        }
        maid.swing(InteractionHand.MAIN_HAND);
        boolean ok = maid.doHurtTarget(level, target);
        MaidDebug.log("attack " + target.getType().toShortString() + " -> " + ok);
        return ok;
    }

    // ---------- 实体查找 ----------

    /** 在范围内按类型 + 谓词查找实体列表（索敌/找掉落物） */
    public static <T extends Entity> List<T> findEntities(SmartMaidEntity maid, Class<T> clazz, double range, Predicate<T> predicate) {
        AABB box = maid.getBoundingBox().inflate(range);
        return maid.level().getEntities(EntityTypeTest.forClass(clazz), box, predicate);
    }

    /** 找最近的匹配实体 */
    public static <T extends Entity> T findNearestEntity(SmartMaidEntity maid, Class<T> clazz, double range, Predicate<T> predicate) {
        return findEntities(maid, clazz, range, predicate).stream()
                .min(Comparator.comparingDouble(e -> maid.distanceToSqr(e)))
                .orElse(null);
    }

    /** 找最近的掉落物 */
    public static ItemEntity findNearestItem(SmartMaidEntity maid, double range, Predicate<ItemStack> itemFilter) {
        return findNearestEntity(maid, ItemEntity.class, range,
                e -> e.isAlive() && (itemFilter == null || itemFilter.test(e.getItem())));
    }

    // ---------- 其他 ----------

    /** 向 target 位置生成投掷物品实体（喂食"丢出来"） */
    public static void throwItem(SmartMaidEntity maid, ItemStack stack, LivingEntity target, double speed) {
        if (maid.level().isClientSide()) {
            return;
        }
        Level level = maid.level();
        Vec3 eye = maid.getEyePosition();
        Vec3 to = target.getEyePosition().subtract(eye).normalize();
        Vec3 spawn = eye.add(to.scale(0.6D));
        net.minecraft.world.entity.item.ItemEntity itemEntity =
                new net.minecraft.world.entity.item.ItemEntity(level, spawn.x, spawn.y, spawn.z, stack);
        itemEntity.setDeltaMovement(to.scale(speed));
        itemEntity.setNoPickUpDelay();
        level.addFreshEntity(itemEntity);
    }

    /**
     * 把物品丢到**主人身边**（主人脚下），并让女仆短时间内不去拾取。
     *
     * <p>用于「把 X 丢给我」这类给物品场景：丢在女仆自己脚下会被她的拾取逻辑
     * （{@code setCanPickUpLoot(true)} + {@code wantsToPickUp} 恒真）立刻收进背包，
     * 等于没给。丢到主人所在位置后主人能直接捡到；同时 {@link SmartMaidEntity#suppressPickup(int)}
     * 让女仆 5 秒内不捡，避免抢走。没有主人时退回丢在自己脚下。</p>
     */
    public static void dropToOwner(SmartMaidEntity maid, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Level level = maid.level();
        LivingEntity owner = maid.getOwner();
        double x = owner != null ? owner.getX() : maid.getX();
        double y = (owner != null ? owner.getY() : maid.getY()) + 0.25D;
        double z = owner != null ? owner.getZ() : maid.getZ();
        ItemEntity item = new ItemEntity(level, x, y, z, stack);
        item.setNoPickUpDelay();
        level.addFreshEntity(item);
        maid.suppressPickup(100);
        MaidDebug.log("dropToOwner " + stack.getItem().getDescriptionId()
                + " x" + stack.getCount() + " -> "
                + (owner != null ? owner.getName().getString() : "self"));
    }
}
