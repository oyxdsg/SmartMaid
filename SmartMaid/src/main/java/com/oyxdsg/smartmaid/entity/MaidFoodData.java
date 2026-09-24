package com.oyxdsg.smartmaid.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 女仆饱食度（仿玩家 {@code FoodData}，仅内部状态）。
 *
 * <p>原版 {@link net.minecraft.world.food.FoodData} 的 {@code exhaustionLevel}/{@code tickTimer}
 * 是 private、且 {@code tick(ServerPlayer)} 只接受 {@code ServerPlayer}，无法作用于女仆；
 * 这里按其反编译得到的规则等价复刻：进食加营养/饱和度、疲劳消耗、自然回血、饥饿伤害。</p>
 *
 * <p>不做持久化（女仆实体 {@code noSave()}，且饱食度暂定为内部状态）。</p>
 */
public final class MaidFoodData {

    private static final int MAX_FOOD = 20;
    /** 疲劳阈值：超过即消耗一次饱和度或饱食度 */
    private static final float EXHAUSTION_THRESHOLD = 4.0F;

    private int foodLevel = MAX_FOOD;
    private float saturationLevel = 5.0F;
    private float exhaustionLevel;
    private int tickTimer;

    /** 饱食消耗速度倍率（设置项；1.0 = 原版速度） */
    private float hungerRate = 1.0F;
    /** 自然回血速度倍率（设置项；1.0 = 原版速度，越大回血越快） */
    private float regenRate = 1.0F;

    /** 应用玩家设置里的饱食消耗 / 回血速度倍率 */
    public void setRates(float hungerRate, float regenRate) {
        this.hungerRate = Math.max(0.0F, hungerRate);
        this.regenRate = Math.max(0.1F, regenRate);
    }

    public int getFoodLevel() {
        return this.foodLevel;
    }

    public float getSaturationLevel() {
        return this.saturationLevel;
    }

    /** 是否未吃饱（<20） */
    public boolean needsFood() {
        return this.foodLevel < MAX_FOOD;
    }

    /** 是否还有余量（>6） */
    public boolean hasEnoughFood() {
        return this.foodLevel > 6;
    }

    /** 进食：按食物营养 + 饱和度补充（等价原版 {@code FoodData.eat(FoodProperties)}）。 */
    public void eat(FoodProperties food) {
        this.foodLevel = Mth.clamp(food.nutrition() + this.foodLevel, 0, MAX_FOOD);
        this.saturationLevel = Mth.clamp(food.saturation() + this.saturationLevel, 0.0F, (float) this.foodLevel);
    }

    /** 增加疲劳（攻击/跳跃等动作消耗）；按设置的饱食消耗速度倍率缩放。 */
    public void addExhaustion(float amount) {
        this.exhaustionLevel = Math.min(this.exhaustionLevel + amount * this.hungerRate, 40.0F);
    }

    /**
     * 每 tick 驱动：等价原版 {@code FoodData.tick(ServerPlayer)}，但作用于女仆（LivingEntity）。
     */
    public void tick(ServerLevel level, LivingEntity maid) {
        Difficulty difficulty = level.getLevelData().getDifficulty();

        // 疲劳 → 先扣饱和度，再扣饱食度（和平难度不扣饱食度）
        if (this.exhaustionLevel > EXHAUSTION_THRESHOLD) {
            this.exhaustionLevel -= EXHAUSTION_THRESHOLD;
            if (this.saturationLevel > 0.0F) {
                this.saturationLevel = Math.max(this.saturationLevel - 1.0F, 0.0F);
            } else if (difficulty != Difficulty.PEACEFUL) {
                this.foodLevel = Math.max(this.foodLevel - 1, 0);
            }
        }

        boolean naturalRegen = level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        boolean hurt = maid.getHealth() > 0.0F && maid.getHealth() < maid.getMaxHealth();
        // 回血间隔按设置的"回血速度"倍率缩放（倍率越大间隔越短）
        int fastInterval = Math.max(1, (int) Math.round(10.0D / this.regenRate));
        int slowInterval = Math.max(1, (int) Math.round(80.0D / this.regenRate));
        if (naturalRegen && this.saturationLevel > 0.0F && hurt && this.foodLevel >= MAX_FOOD) {
            // 高饱食 + 有饱和度：快速回血
            if (++this.tickTimer >= fastInterval) {
                float healAmount = Math.min(this.saturationLevel, 6.0F);
                maid.heal(healAmount / 6.0F);
                this.addExhaustion(healAmount);
                this.tickTimer = 0;
            }
        } else if (naturalRegen && this.foodLevel >= 18 && hurt) {
            // 饱食 >=18：缓慢回血
            if (++this.tickTimer >= slowInterval) {
                maid.heal(1.0F);
                this.addExhaustion(6.0F);
                this.tickTimer = 0;
            }
        } else if (this.foodLevel <= 0) {
            // 饥饿伤害（难度相关，与原版一致）
            if (++this.tickTimer >= 80) {
                if (maid.getHealth() > 10.0F || difficulty == Difficulty.HARD
                        || (maid.getHealth() > 1.0F && difficulty == Difficulty.NORMAL)) {
                    maid.hurtServer(level, maid.damageSources().starve(), 1.0F);
                }
                this.tickTimer = 0;
            }
        } else {
            this.tickTimer = 0;
        }
    }
}
