package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.maidtask.AttackTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.BuildTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.ChestOpenTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.CollectTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.CraftTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.EatTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.FarmTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.FeedTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.GuardTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskManager;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MineTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MoveToTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.OneShotTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.SmeltTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.TransferTask;
import com.oyxdsg.smartmaid.entity.ai.slot.ItemSlot;
import com.oyxdsg.smartmaid.entity.ai.slot.Slots;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 任务指令：/maidtasks。
 *
 * <p><b>集成指令：</b></p>
 * <ul>
 *     <li>{@code attack [range]} / {@code guard [range]} / {@code feed} — 战斗/护卫/喂食</li>
 *     <li>{@code eat [item]} — 进食（吃背包食物；指定 item 则吃该食物）</li>
 *     <li>{@code mine <pos> [range] [count]} / {@code farm <pos> [range]} — 挖矿/耕作</li>
 *     <li>{@code build <pos> <height>} / {@code collect [range]} — 建造/收集</li>
 *     <li>{@code craft <item> [count]} / {@code smelt <item> [count]} — 制作/烧炼</li>
 * </ul>
 *
 * <p><b>基础指令（原子动作）：</b></p>
 * <ul>
 *     <li>{@code move <pos>} / {@code look <pos>} — 移动/面朝</li>
 *     <li>{@code break <pos>} / {@code place <pos>} / {@code use <pos>} — 方块操作</li>
 *     <li>{@code equip <item>} / {@code store} / {@code drop [count]} — 背包操作</li>
 *     <li>{@code pickup} / {@code sit} / {@code stop} — 拾取/坐下/停止</li>
 * </ul>
 *
 * <p><b>管理：</b>{@code cancel} / {@code status}</p>
 */
public final class MaidTaskCommand {

    private MaidTaskCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maidtasks")
                // P4 发布收尾：任务指令需权限等级 2
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                // ---- 集成指令 ----
                .then(Commands.literal("attack")
                        .executes(ctx -> dispatch(ctx.getSource(), new AttackTask(12)))
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new AttackTask(IntegerArgumentType.getInteger(ctx, "range"))))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .executes(ctx -> {
                                            EntityType<?> type = resolveEntityType(ctx.getSource(),
                                                    ctx.getArgument("target", String.class));
                                            return type == null ? 0 : dispatch(ctx.getSource(),
                                                    new AttackTask(IntegerArgumentType.getInteger(ctx, "range"), type));
                                        }))))
                .then(Commands.literal("guard")
                        .executes(ctx -> dispatch(ctx.getSource(), new GuardTask(16, false)))
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new GuardTask(IntegerArgumentType.getInteger(ctx, "range"), false)))))
                .then(Commands.literal("feed")
                        .executes(ctx -> dispatch(ctx.getSource(), new FeedTask())))
                .then(Commands.literal("eat")
                        .executes(ctx -> dispatch(ctx.getSource(), new EatTask(null)))
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .executes(ctx -> {
                                    Item item = resolveItem(ctx.getSource(), IdentifierArgument.getId(ctx, "item"));
                                    return item == null ? 0 : dispatch(ctx.getSource(), new EatTask(item));
                                })))
                .then(Commands.literal("mine")
                        .executes(ctx -> dispatch(ctx.getSource(),
                                new MineTask(ownerMaidPos(ctx.getSource()), 12, 8)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new MineTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 4, 8)))
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> dispatch(ctx.getSource(),
                                                new MineTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "range"), 8)))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> dispatch(ctx.getSource(),
                                                        new MineTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                IntegerArgumentType.getInteger(ctx, "range"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))))
                .then(Commands.literal("farm")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new FarmTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 4)))
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> dispatch(ctx.getSource(),
                                                new FarmTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "range")))))))
                .then(Commands.literal("build")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> dispatch(ctx.getSource(),
                                                new BuildTask(stackUp(BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "height"))))))))
                .then(Commands.literal("collect")
                        .executes(ctx -> dispatch(ctx.getSource(), new CollectTask(8)))
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new CollectTask(IntegerArgumentType.getInteger(ctx, "range"))))))
                .then(Commands.literal("craft")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .executes(ctx -> craft(ctx.getSource(), IdentifierArgument.getId(ctx, "item"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> craft(ctx.getSource(), IdentifierArgument.getId(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("smelt")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .executes(ctx -> smelt(ctx.getSource(), IdentifierArgument.getId(ctx, "item"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> smelt(ctx.getSource(), IdentifierArgument.getId(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                // ---- 基础指令 ----
                .then(Commands.literal("transfer")
                        .then(Commands.argument("from", StringArgumentType.word())
                                .then(Commands.argument("to", StringArgumentType.word())
                                        .executes(ctx -> transfer(ctx.getSource(),
                                                ctx.getArgument("from", String.class),
                                                ctx.getArgument("to", String.class), 64))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> transfer(ctx.getSource(),
                                                        ctx.getArgument("from", String.class),
                                                        ctx.getArgument("to", String.class),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("chestopen")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new ChestOpenTask(BlockPosArgument.getBlockPos(ctx, "pos"))))))
                .then(Commands.literal("chestput")
                        .executes(ctx -> chestPutOpened(ctx.getSource(), 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> chestPutOpened(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("chesttake")
                        .executes(ctx -> chestTakeOpened(ctx.getSource(), -1, 1))
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 255))
                                .executes(ctx -> chestTakeOpened(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "slot"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> chestTakeOpened(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("move")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        new MoveToTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 1.0D, 1.5D)))))
                .then(Commands.literal("look")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    return dispatch(ctx.getSource(),
                                            new OneShotTask("look", "已面朝目标",
                                                    () -> withMaid(ctx.getSource(), maid -> maid.getLookControl()
                                                            .setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))));
                                })))
                .then(Commands.literal("break")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    return dispatch(ctx.getSource(),
                                            new OneShotTask("break", "已挖方块",
                                                    () -> withMaid(ctx.getSource(), maid -> MaidActions.breakBlock(maid, pos))));
                                })))
                .then(Commands.literal("place")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    return dispatch(ctx.getSource(),
                                            new OneShotTask("place", "已放置方块",
                                                    () -> withMaid(ctx.getSource(), maid -> MaidActions.placeBlock(maid, pos.below(), Direction.UP))));
                                })))
                .then(Commands.literal("use")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    return dispatch(ctx.getSource(),
                                            new OneShotTask("use", "已使用物品",
                                                    () -> withMaid(ctx.getSource(), maid -> MaidActions.useItemOn(maid, pos.below(), Direction.UP))));
                                })))
                .then(Commands.literal("equip")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .executes(ctx -> {
                                    Item item = resolveItem(ctx.getSource(), IdentifierArgument.getId(ctx, "item"));
                                    return item == null ? 0 : dispatch(ctx.getSource(),
                                            new OneShotTask("equip", "已装备到主手",
                                                    () -> withMaid(ctx.getSource(), maid -> MaidActions.equipFromBackpack(maid,
                                                            stack -> stack.is(item)))));
                                })))
                .then(Commands.literal("store")
                        .executes(ctx -> dispatch(ctx.getSource(),
                                new OneShotTask("store", "已收纳主手物品",
                                        () -> withMaid(ctx.getSource(), maid -> {
                                            ItemStack hand = maid.getMainHandItem();
                                            if (!hand.isEmpty()) {
                                                ItemStack left = MaidActions.storeToBackpack(maid, hand);
                                                maid.setItemInHand(InteractionHand.MAIN_HAND, left);
                                                maid.syncInventoryArmor();
                                            }
                                        })))))
                .then(Commands.literal("drop")
                        .executes(ctx -> drop(ctx.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> drop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("pickup")
                        .executes(ctx -> dispatch(ctx.getSource(), new CollectTask(4))))
                .then(Commands.literal("sit")
                        .executes(ctx -> dispatch(ctx.getSource(),
                                new OneShotTask("sit", "已切换坐姿",
                                        () -> withMaid(ctx.getSource(), maid ->
                                                // 坐/站统一切换：落地吸附 + 移动锁定都在 setOrderedToSit 内处理
                                                maid.setOrderedToSit(!maid.isOrderedToSit()))))))
                .then(Commands.literal("stop")
                        .executes(ctx -> dispatch(ctx.getSource(),
                                new OneShotTask("stop", "已停止",
                                        () -> withMaid(ctx.getSource(), maid -> {
                                            maid.getNavigation().stop();
                                            maid.getMaidTaskManager().cancel();
                                        })))))
                // ---- 管理 ----
                .then(Commands.literal("cancel")
                        .executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource()))));
    }

    /** 正交物品转移指令：女仆自身槽位之间移动物品（背包/主手/副手/盔甲）。槽位用纯数字或单词 */
    private static int transfer(CommandSourceStack source, String fromSpec, String toSpec, int count) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        ItemSlot from = Slots.parse(fromSpec);
        ItemSlot to = Slots.parse(toSpec);
        if (from == null) {
            source.sendFailure(Component.literal("无效源槽位: " + fromSpec + "（用 0-40 / mainhand / offhand / head / chest / legs / feet）"));
            return 0;
        }
        if (to == null) {
            source.sendFailure(Component.literal("无效目标槽位: " + toSpec + "（用 0-40 / mainhand / offhand / head / chest / legs / feet）"));
            return 0;
        }
        return dispatch(source, new TransferTask(from, to, count));
    }

    /** 主手物品放入"已打开"的容器（chestput 指令） */
    private static int chestPutOpened(CommandSourceStack source, int count) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        BlockPos pos = maid.getOpenedContainer();
        if (pos == null || !(maid.level().getBlockEntity(pos) instanceof Container)) {
            source.sendFailure(Component.literal("女仆还没打开箱子（或箱子不在了），先 /maidtasks chestopen <pos>"));
            return 0;
        }
        return dispatch(source, new TransferTask(Slots.mainhand(), Slots.container(pos, -1), count));
    }

    /** 从"已打开"的容器取物品到主手（chesttake 指令） */
    private static int chestTakeOpened(CommandSourceStack source, int slot, int count) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        BlockPos pos = maid.getOpenedContainer();
        if (pos == null || !(maid.level().getBlockEntity(pos) instanceof Container)) {
            source.sendFailure(Component.literal("女仆还没打开箱子（或箱子不在了），先 /maidtasks chestopen <pos>"));
            return 0;
        }
        return dispatch(source, new TransferTask(Slots.container(pos, slot), Slots.mainhand(), count));
    }

    private static int craft(CommandSourceStack source, Identifier id, int count) {
        Item item = resolveItem(source, id);
        return item == null ? 0 : dispatch(source, new CraftTask(new ItemStack(item), count));
    }

    private static int smelt(CommandSourceStack source, Identifier id, int count) {
        Item item = resolveItem(source, id);
        return item == null ? 0 : dispatch(source, new SmeltTask(new ItemStack(item), count));
    }

    /** 解析物品 id 为 Item；未找到发 failure 并返回 null */
    private static Item resolveItem(CommandSourceStack source, Identifier id) {
        Item item = BuiltInRegistries.ITEM.get(id).map(holder -> holder.value()).orElse(null);
        if (item == null) {
            source.sendFailure(Component.literal("未找到物品: " + id));
        }
        return item;
    }

    /** 解析实体类型 id（如 pig / minecraft:pig）；未找到发 failure 并返回 null */
    private static EntityType<?> resolveEntityType(CommandSourceStack source, String id) {
        Identifier identifier = Identifier.tryParse(id);
        EntityType<?> type = identifier == null ? null
                : BuiltInRegistries.ENTITY_TYPE.get(identifier).map(holder -> holder.value()).orElse(null);
        if (type == null) {
            source.sendFailure(Component.literal("未找到实体: " + id));
        }
        return type;
    }

    private static int drop(CommandSourceStack source, int count) {
        return dispatch(source, new OneShotTask("drop", "已丢出 " + count + " 个",
                () -> withMaid(source, maid -> {
                    ItemStack hand = maid.getMainHandItem();
                    if (!hand.isEmpty()) {
                        ItemStack drop = hand.split(Math.min(count, hand.getCount()));
                        // 丢到主人身边（丢在自己脚下会被立刻捡回）
                        MaidActions.dropToOwner(maid, drop);
                        maid.syncInventoryArmor();
                    }
                })));
    }

    private static int cancel(CommandSourceStack source) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        maid.getMaidTaskManager().cancel();
        source.sendSuccess(() -> Component.literal("已取消女仆当前任务"), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        MaidTaskManager manager = maid.getMaidTaskManager();
        MaidAITask current = manager.currentTask();
        source.sendSuccess(() -> Component.literal(current == null
                ? "女仆当前无 AI 任务"
                : "女仆当前任务: " + current.taskId()), true);
        return 1;
    }

    /** 派发任务到女仆调度器 */
    private static int dispatch(CommandSourceStack source, MaidAITask task) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return 0;
        }
        boolean ok = maid.getMaidTaskManager().setTask(task);
        source.sendSuccess(() -> Component.literal(ok ? "任务已下发: " + task.taskId() : "任务前置条件不满足: " + task.taskId()), true);
        return ok ? 1 : 0;
    }

    /** 对玩家女仆执行动作（找不到女仆则发 failure） */
    private static void withMaid(CommandSourceStack source, java.util.function.Consumer<SmartMaidEntity> action) {
        SmartMaidEntity maid = findOwnerMaid(source);
        if (maid == null) {
            source.sendFailure(Component.literal("没有找到你的女仆"));
            return;
        }
        action.accept(maid);
    }

    /** 从 pos 向上堆叠 height 个方块 */
    private static List<BlockPos> stackUp(BlockPos pos, int height) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            positions.add(pos.offset(0, i, 0));
        }
        return positions;
    }

    /** 查找发送玩家的女仆 */
    private static SmartMaidEntity findOwnerMaid(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!(player.level() instanceof ServerLevel level)) {
                return null;
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                    return maid;
                }
            }
        } catch (Exception e) {
            SmartMaid.LOGGER.error("maidtasks 查找女仆失败", e);
        }
        return null;
    }

    /** 女仆当前脚下坐标（/maidtasks mine 无参时自动探测周围矿物的中心）。 */
    private static BlockPos ownerMaidPos(CommandSourceStack source) {
        SmartMaidEntity maid = findOwnerMaid(source);
        return maid == null ? BlockPos.containing(source.getPosition()) : maid.blockPosition();
    }
}
