package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
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

/**
 * 熔炉烧炼任务（P2）：在附近找熔炉 → 放能烧成目标的原矿 + 燃料 → 轮询成品 → 进背包，
 * count 达成或材料/熔炉缺失结束。
 */
public class SmeltTask extends MaidAITask {

    private final ItemStack target;
    private final int count;
    private int completed;
    private BlockPos furnacePos;
    private boolean done;
    private boolean missing;
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
        this.completed = 0;
        this.furnacePos = null;
        this.done = false;
        this.missing = false;
        MaidDebug.log("Smelt start 目标=" + this.target.getItem().getDescriptionId() + " x" + this.count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.completed >= this.count) {
            return;
        }
        // 1. 定位熔炉（附近 10 格内最近的）
        if (this.furnacePos == null) {
            this.furnacePos = findFurnace(maid, 10);
            if (this.furnacePos == null) {
                this.missing = true;
                maid.showBubble(net.minecraft.network.chat.Component.literal("附近没有熔炉"), 60);
                return;
            }
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
    public String result() {
        return this.missing
                ? "缺材料/熔炉，已烧 " + this.completed + " 个"
                : "已烧 " + this.completed + " 个 " + this.target.getItem().getDescriptionId();
    }

    /** 附近范围内找最近的熔炉。实现已收拢到感知工具 {@link PerceptionBlockUtil}。 */
    private BlockPos findFurnace(SmartMaidEntity maid, int range) {
        return PerceptionBlockUtil.findNearestFurnace(maid.level(), maid.blockPosition(), range);
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
        net.minecraft.world.level.block.entity.FuelValues fuelValues = level.fuelValues();
        SimpleContainer inv = maid.getMaidInventory();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (!stack.isEmpty() && fuelValues.isFuel(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
