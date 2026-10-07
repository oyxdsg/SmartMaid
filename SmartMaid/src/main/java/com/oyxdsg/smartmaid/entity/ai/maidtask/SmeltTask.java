package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * 熔炉烧炼任务（P2）：在附近找<b>空闲</b>熔炉 → 放能烧成目标的原矿 + 燃料 → 轮询成品 → 进背包，
 * count 达成或材料/熔炉缺失结束。
 *
 * <p><b>熔炉租约（§8.8b）</b>：一个熔炉同一时间只被一个 smelt 任务占用。无空闲熔炉时本任务
 * 上报 {@link #isBlocked()} → 调度器暂停（{@link QueuedTask.PauseReason#BLOCKED}）并<b>让出队头</b>，
 * 熔炉空出后再恢复；被暂停（战斗/插队）时释放租约，恢复时重新 acquire。</p>
 */
public class SmeltTask extends MaidAITask implements Resumable {

    private final ItemStack target;
    private final int count;
    private final String ownerId = "smelt-" + UUID.randomUUID();
    private int completed;
    private BlockPos furnacePos;
    private boolean done;
    private boolean missing;
    /** 有熔炉但无空闲 → 阻塞让出队头（§8.8b）。 */
    private boolean blocked;
    /** 下次重扫熔炉的 tick（阻塞退避，避免每 tick 全量扫描）。 */
    private int nextScanTick;
    private int waitTicks;

    public SmeltTask(ItemStack target, int count) {
        super("smelt");
        this.target = target.copy();
        this.count = Math.max(1, count);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return !this.target.isEmpty();
    }

    @Override
    public void start(SmartMaidEntity maid) {
        FurnaceLease.releaseAll(this.ownerId);
        this.completed = 0;
        this.furnacePos = null;
        this.done = false;
        this.missing = false;
        this.blocked = false;
        this.nextScanTick = 0;
        MaidDebug.log("Smelt start 目标=" + this.target.getItem().getDescriptionId() + " x" + this.count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.completed >= this.count) {
            return;
        }
        // 1. 定位空闲熔炉（附近 10 格内最近的未被占用熔炉）
        if (this.furnacePos == null) {
            this.blocked = false;
            if (maid.tickCount < this.nextScanTick) {
                this.blocked = true; // 退避中：继续让出队头，不重复扫描
                return;
            }
            int range = 10;
            this.furnacePos = FurnaceLease.findFree(maid.level(), maid.blockPosition(), range, this.ownerId);
            if (this.furnacePos == null) {
                if (FurnaceLease.anyFurnace(maid.level(), maid.blockPosition(), range)) {
                    this.blocked = true;                 // 有熔炉但都被占用
                    this.nextScanTick = maid.tickCount + 40;
                    MaidDebug.log("Smelt 无空闲熔炉，让出队头");
                } else {
                    this.missing = true;                 // 附近根本没有熔炉
                    maid.showBubble(net.minecraft.network.chat.Component.literal("附近没有熔炉"), 60);
                }
                return;
            }
            FurnaceLease.acquire(this.furnacePos, this.ownerId);
        }
        // 2. 走到熔炉旁
        if (!MaidActions.isWithinReach(maid, this.furnacePos, 3.0D)) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, this.furnacePos, 1.0D);
            }
            return;
        }
        maid.getNavigation().stop();

        BlockEntity be = maid.level().getBlockEntity(this.furnacePos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            FurnaceLease.release(this.furnacePos, this.ownerId);
            this.furnacePos = null;
            return;
        }

        // 3. 轮询成品槽（2）
        ItemStack result = furnace.getItem(2);
        if (!result.isEmpty()) {
            ItemStack taken = result.copy();
            furnace.setItem(2, ItemStack.EMPTY);
            ItemStack left = MaidActions.storeToBackpack(maid, taken);
            if (!left.isEmpty()) {
                Block.popResource(maid.level(), this.furnacePos, left); // 背包满则掉在熔炉旁
            }
            this.completed += taken.getCount();
            MaidDebug.log("Smelt 取出成品 x" + taken.getCount() + " 累计=" + this.completed);
            return;
        }

        // 4. 补料：input(0) 空 → 放能烧成 target 的原矿
        if (furnace.getItem(0).isEmpty()) {
            ItemStack input = findSmeltableInput(maid);
            if (input.isEmpty()) {
                this.missing = true;
                maid.showBubble(net.minecraft.network.chat.Component.literal("背包里没有可烧的 " + this.target.getItem().getDescriptionId()), 60);
                return;
            }
            furnace.setItem(0, input.copy());
            input.shrink(1);
            maid.getMaidInventory().setChanged();
        }
        // fuel(1) 空 → 放燃料
        if (furnace.getItem(1).isEmpty()) {
            ItemStack fuel = findFuel(maid);
            if (fuel.isEmpty()) {
                this.missing = true;
                maid.showBubble(net.minecraft.network.chat.Component.literal("背包里没有燃料"), 60);
                return;
            }
            furnace.setItem(1, fuel.copy());
            fuel.shrink(1);
            maid.getMaidInventory().setChanged();
        }
        // 5. 等待烧炼（熔炉在区块加载时自动 tick）
        this.waitTicks++;
        if (MaidDebug.verbose() && this.waitTicks % 40 == 0) {
            MaidDebug.log("Smelt 等待烧炼中 " + this.waitTicks + " tick");
        }
    }

    @Override
    public boolean isDone() {
        return this.completed >= this.count || this.missing;
    }

    @Override
    public boolean isBlocked() {
        return this.blocked;
    }

    @Override
    public void onSuspend() {
        // 暂停（战斗/插队/让出队头）→ 释放租约，恢复时重新 acquire
        FurnaceLease.release(this.furnacePos, this.ownerId);
        this.furnacePos = null;
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        FurnaceLease.releaseAll(this.ownerId);
        this.furnacePos = null;
    }

    @Override
    public String result() {
        return this.missing
                ? "缺材料/熔炉，已烧 " + this.completed + " 个"
                : "已烧 " + this.completed + " 个 " + this.target.getItem().getDescriptionId();
    }

    // ---------- Resumable（Q7） ----------

    @Override
    public JsonObject saveState() {
        JsonObject o = new JsonObject();
        o.addProperty("version", stateVersion());
        o.addProperty("completed", this.completed);
        o.addProperty("missing", this.missing);
        if (this.furnacePos != null) {
            o.add("furnacePos", TaskState.pos(this.furnacePos));
        }
        return o;
    }

    @Override
    public void restoreState(JsonObject s) {
        if (s == null) {
            return;
        }
        this.completed = s.has("completed") ? s.get("completed").getAsInt() : 0;
        this.missing = s.has("missing") && s.get("missing").getAsBoolean();
        // 熔炉位置不回溯（熔炉可能已被占用/移除）→ 恢复时重新 acquire（§8.8b）
        this.furnacePos = null;
        this.blocked = false;
        this.nextScanTick = 0;
    }

    @Override
    public int stateVersion() {
        return 1;
    }

    @Override
    public boolean validateState(SmartMaidEntity maid, JsonObject s) {
        return !this.target.isEmpty() && maid != null;
    }

    /** 从背包找能烧成 target 的原矿 */
    private ItemStack findSmeltableInput(SmartMaidEntity maid) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return ItemStack.EMPTY;
        }
        RecipeManager rm = level.recipeAccess();
        SimpleContainer inv = maid.getMaidInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (stack.isEmpty()) {
                continue;
            }
            SingleRecipeInput input = new SingleRecipeInput(stack);
            Optional<RecipeHolder<SmeltingRecipe>> holder =
                    rm.getRecipeFor(RecipeType.SMELTING, input, level);
            if (holder.isPresent()) {
                ItemStack out = holder.get().value().assemble(input);
                if (!out.isEmpty() && ItemStack.isSameItemSameComponents(out, this.target)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 从背包找燃料 */
    private ItemStack findFuel(SmartMaidEntity maid) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return ItemStack.EMPTY;
        }
        SimpleContainer inv = maid.getMaidInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            // 「怎么判燃料」是版本敏感的：26.2 用 Level#fuelValues()，26.3 删掉它改用物品组件
            // → 走版本差异隔离层
            if (com.oyxdsg.smartmaid.compat.MaidCompat.isFuel(level, stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
