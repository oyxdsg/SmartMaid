package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;

import java.util.EnumSet;

/**
 * 女仆战斗 Goal（见 DEVELOPMENT_COMBAT.md §三 / §四）。
 *
 * <p>取代原 {@code MaidMeleeAttackGoal}：读 {@code targetSelector} 给出的目标，
 * 按优先级 <b>盾 &gt; 吃 &gt; 远程 &gt; 近战 &gt; 逼近</b> 执行；近战自动换最优武器、
 * 按武器攻速出手；走位用 {@link MaidCombatMovement}（保持 ~3 格，可面向敌人后退 / 绕行 / 背对跳）。</p>
 *
 * <p>优先级：位于 goalSelector 第 1 位，且不检查 {@code isAiBusy()}（战斗高于 AI 任务），
 * 任务调度器 {@code MaidTaskManager} 会因 {@link #isEngaged()} 取消当前任务。</p>
 *
 * <p>可观测性（全流程测试）：目标切换、行为模式切换、停止原因、走位/背对跳/绕行/换武器/
 * 出手/进食/拉弓/射击/举盾均有 {@code Combat} 前缀日志；深排查可开 {@code MaidDebug.VERBOSE}。</p>
 */
public class MaidCombatGoal extends Goal {

    /** 与目标期望保持的距离（格） */
    private static final double MELEE_DISTANCE = 3.0D;
    /** 目标丢失后多少 tick 才彻底放弃（滞回，防止视线短暂中断就退出战斗） */
    private static final int TARGET_LOST_TIMEOUT = 60;
    /** 攻击失败时的兜底冷却 */
    private static final int FAILED_ATTACK_COOLDOWN = 10;
    /** 进食安全距离：血量过低时先远离到该距离外再吃 */
    private static final double EAT_SAFE_DISTANCE = 7.0D;
    /** 进食冷却（tick，约 1.7s） */
    private static final int EAT_COOLDOWN = 34;
    /** 进食前最多尝试拉开距离的 tick 数；超时后原地进食（防止被墙角卡住导致"不吃也不打"死锁） */
    private static final int EAT_RETREAT_LIMIT = 40;
    /** 远程起始距离：超出该距离且有弓有箭 → 用弓 */
    private static final double RANGED_MIN_DISTANCE = 6.0D;
    /** 远程 kite 保持距离（格） */
    private static final double KITE_DISTANCE = 10.0D;
    /** 远程冷却（tick，足够满蓄力射出去） */
    private static final int RANGED_COOLDOWN = 24;
    /** 发射后短暂冷却（蓄力本身已提供节奏，避免两段叠加导致过慢） */
    private static final int RANGED_RELEASE_COOLDOWN = 6;
    /** 调试用模式日志节流（verbose 时每 N tick 一条） */
    private static final int VERBOSE_INTERVAL = 10;

    /** 战斗行为模式（仅用于日志/调试） */
    private enum Mode {
        APPROACH, MELEE, RANGED, EAT, SHIELD
    }

    private final SmartMaidEntity maid;
    private final MaidCombatMovement movement = new MaidCombatMovement();

    private LivingEntity target;
    private int actionCooldown;
    private int lostTicks;
    private int eatRetreatTicks;
    private boolean engaged;
    /** 本次举盾起始 tick（诊断：举盾持续时间） */
    private int blockStartTick = -1;

    private LivingEntity lastLoggedTarget;
    private Mode lastMode;
    private String stopReason = "?";

    public MaidCombatGoal(SmartMaidEntity maid) {
        this.maid = maid;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** 是否处于战斗状态（供 {@code MaidTaskManager} / 寻路降级判断） */
    public boolean isEngaged() {
        return this.engaged;
    }

    @Override
    public boolean canUse() {
        if (this.maid.isOrderedToSit()) {
            return false;
        }
        LivingEntity candidate = this.maid.getTarget();
        if (candidate == null || !candidate.isAlive() || !this.maid.canAttack(candidate)) {
            return false;
        }
        this.target = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.maid.isOrderedToSit()) {
            this.stopReason = "sit";
            return false;
        }
        if (this.target == null) {
            this.stopReason = "no_target";
            return false;
        }
        if (!this.target.isAlive()) {
            this.stopReason = "target_dead";
            return false;
        }
        if (!this.maid.canAttack(this.target)) {
            this.stopReason = "cannot_attack";
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        this.engaged = true;
        this.actionCooldown = 0;
        this.lostTicks = 0;
        this.lastMode = null;
        this.lastLoggedTarget = null;
        MaidDebug.log("Combat start target=" + describe(this.target));
    }

    @Override
    public void stop() {
        this.engaged = false;
        this.target = null;
        // 关键：释放"使用中"状态（拉弓/进食/举盾），否则目标死亡后女仆会一直保持拉满弓
        // （LivingEntity 持续推进 use 状态，而战斗 Goal 已结束，没人再释放它）
        if (this.maid.isUsingItem()) {
            this.maid.stopUsingItem();
        }
        this.maid.getNavigation().stop();
        this.maid.getMoveControl().setWait();
        MaidDebug.log("Combat stop reason=" + this.stopReason);
    }

    @Override
    public void tick() {
        LivingEntity current = this.target;
        if (current == null || !current.isAlive()) {
            if (++this.lostTicks > TARGET_LOST_TIMEOUT) {
                this.maid.setTarget(null);
            }
            return;
        }
        this.lostTicks = 0;
        this.maid.getLookControl().setLookAt(current, 30.0F, (float) this.maid.getMaxHeadXRot());
        double dist = this.maid.distanceTo(current);

        if (current != this.lastLoggedTarget) {
            this.lastLoggedTarget = current;
            MaidDebug.log("Combat target=" + describe(current) + " dist=" + MaidDebug.fmt1(dist));
        }
        if (MaidDebug.verbose() && this.maid.tickCount % VERBOSE_INTERVAL == 0) {
            MaidDebug.log("Combat tick mode=" + this.lastMode + " dist=" + MaidDebug.fmt1(dist)
                    + " cd=" + this.actionCooldown + " hp=" + MaidDebug.fmt1(this.maid.getHealth())
                    + " food=" + this.maid.getMaidFood().getFoodLevel()
                    + " mainhand=" + MaidDebug.itemName(this.maid.getMainHandItem()));
        }

        // 盾牌格挡：威胁时无视冷却、优先级最高（盾 > 吃 > 远程 > 近战）
        if (handleShield(dist)) {
            return;
        }

        // 拉弓中若弹药耗尽/弓被换走 → 立即松弦，避免卡在"拉满弓"
        if (this.maid.isUsingItem() && this.maid.getUseItem().getItem() instanceof BowItem
                && !MaidRangedSkill.canShoot(this.maid)) {
            MaidDebug.log("Combat 松弦（无箭/弓被换走）");
            this.maid.stopUsingItem();
        }

        // 血量低于一半且有食物 → 优先进食（先远离到安全距离）
        if (shouldEat()) {
            handleEat(current, dist);
            return;
        }
        this.eatRetreatTicks = 0;

        // 6 格开外且有弓有箭 → 远程（kite），否则近战逼近
        if (dist > RANGED_MIN_DISTANCE && MaidRangedSkill.canShoot(this.maid)) {
            handleRanged(current, dist);
            return;
        }

        if (this.maid.isWithinMeleeAttackRange(current)) {
            logMode(Mode.MELEE, dist);
            this.maid.getNavigation().stop();
            // 走位：面向敌人保持 ~3 格（可后退 / 绕行 / 背对跳）；死区 2.7~3.2，
            // 超过 3.2（攻击距离）就前进拉近，否则够不着打不到
            this.movement.keepDistance(this.maid, current, MELEE_DISTANCE, SmartMaidEntity.MAID_ATTACK_REACH);
            if (this.actionCooldown > 0) {
                this.actionCooldown--;
                return;
            }
            MaidWeaponSelector.equipBestMelee(this.maid);
            if (MaidActions.attack(this.maid, current)) {
                this.maid.getMaidFood().addExhaustion(0.1F);
                this.actionCooldown = MaidWeaponSelector.meleeCooldownTicks(this.maid);
                MaidDebug.log("Combat melee attack cd=" + this.actionCooldown
                        + " weapon=" + MaidDebug.itemName(this.maid.getMainHandItem()));
            } else {
                this.actionCooldown = FAILED_ATTACK_COOLDOWN;
            }
        } else {
            logMode(Mode.APPROACH, dist);
            if (this.maid.getNavigation().isDone() || this.maid.tickCount % 10 == 0) {
                // 逼近：走原版寻路（跳跃执行器生效；战斗期已禁用直线降级）
                this.maid.getNavigation().moveTo(current, 1.0D);
            }
        }
    }

    /**
     * 盾牌格挡：检测到箭矢将命中 / 苦力怕将爆时举盾，威胁解除即收盾。
     *
     * @return true 表示本 tick 由举盾接管（不进行其他战斗行为）
     */
    private boolean handleShield(double dist) {
        boolean blocking = this.maid.isUsingItem()
                && this.maid.getUsedItemHand() == InteractionHand.OFF_HAND;
        String threat = MaidShieldSkill.hasShield(this.maid) ? MaidThreatDetector.threatLabel(this.maid) : "";
        // 诊断：每 10 tick 打印箭矢实体 + 女仆朝向/举盾状态（打骷髅排查用）
        MaidThreatDetector.debug(this.maid);
        if (!threat.isEmpty()) {
            logMode(Mode.SHIELD, dist);
            if (!blocking && MaidShieldSkill.equipShield(this.maid) && MaidShieldSkill.beginBlock(this.maid)) {
                this.blockStartTick = this.maid.tickCount;
                MaidDebug.log("Combat block 举盾 threat=" + threat
                        + " dist=" + MaidDebug.fmt1(dist)
                        + " yaw=" + MaidDebug.fmt1(this.maid.getYRot())
                        + " headRot=" + MaidDebug.fmt1(this.maid.getYHeadRot())
                        + " t=" + this.maid.tickCount);
            }
            this.maid.getNavigation().stop();
            this.maid.getMoveControl().setWait();
            return true;
        }
        if (blocking) {
            this.maid.stopUsingItem();
            int holdTicks = this.blockStartTick >= 0 ? this.maid.tickCount - this.blockStartTick : -1;
            this.blockStartTick = -1;
            MaidDebug.log("Combat block end 收盾 holdTicks=" + holdTicks
                    + " t=" + this.maid.tickCount);
        }
        return false;
    }

    /** 血量低于一半且背包有食物 → 优先进食（优先级高于攻击） */
    private boolean shouldEat() {
        return this.maid.getHealth() < this.maid.getMaxHealth() * 0.5F
                && MaidActions.findFoodSlot(this.maid, null) >= 0;
    }

    /** 进食行为：先远离到安全距离，再站着吃完；若长时间退不出去（墙角）则原地吃，避免死锁。 */
    private void handleEat(LivingEntity target, double dist) {
        logMode(Mode.EAT, dist);
        this.maid.getNavigation().stop();
        if (dist < EAT_SAFE_DISTANCE && this.eatRetreatTicks < EAT_RETREAT_LIMIT) {
            // 面向敌人后退（strafe），退到安全距离再吃
            this.eatRetreatTicks++;
            this.movement.keepDistance(this.maid, target, EAT_SAFE_DISTANCE + 1.0D);
            if (this.eatRetreatTicks == EAT_RETREAT_LIMIT) {
                MaidDebug.log("Combat eat 无法拉开距离（墙角？），改为原地进食");
            }
            return;
        }
        if (this.maid.isUsingItem()) {
            this.movement.keepDistance(this.maid, target, EAT_SAFE_DISTANCE);
            return;
        }
        if (this.actionCooldown > 0) {
            this.actionCooldown--;
            return;
        }
        int slot = MaidActions.findFoodSlot(this.maid, null);
        if (slot < 0 || !MaidActions.swapToMainhand(this.maid, slot) || !MaidActions.startEating(this.maid)) {
            MaidDebug.log("Combat eat 失败（无可用食物）");
            this.actionCooldown = FAILED_ATTACK_COOLDOWN * 2;
            return;
        }
        this.actionCooldown = EAT_COOLDOWN;
        MaidDebug.log("Combat eat start dist=" + MaidDebug.fmt1(dist)
                + " item=" + MaidDebug.itemName(this.maid.getMainHandItem()));
    }

    /** 远程行为：kite 保持距离，拉弓满蓄力后发射。 */
    private void handleRanged(LivingEntity target, double dist) {
        logMode(Mode.RANGED, dist);
        this.maid.getNavigation().stop();
        if (dist < KITE_DISTANCE) {
            // 面向敌人后退（kite），拉开到 kite 距离
            this.movement.keepDistance(this.maid, target, KITE_DISTANCE);
        } else {
            // 距离足够，站定射击
            this.maid.getMoveControl().setWait();
        }

        if (this.maid.isUsingItem()) {
            if (MaidRangedSkill.drawPower(this.maid) >= 1.0F
                    || this.maid.getTicksUsingItem() >= RANGED_COOLDOWN) {
                if (MaidRangedSkill.release(this.maid, target)) {
                    this.actionCooldown = RANGED_RELEASE_COOLDOWN;
                }
            }
        } else if (this.actionCooldown > 0) {
            this.actionCooldown--;
        } else if (MaidRangedSkill.beginDraw(this.maid)) {
            MaidDebug.log("Combat draw bow dist=" + MaidDebug.fmt1(dist));
        } else {
            this.actionCooldown = FAILED_ATTACK_COOLDOWN;
        }
    }

    /** 模式切换时打一条（避免每 tick 刷屏）。 */
    private void logMode(Mode mode, double dist) {
        if (mode != this.lastMode) {
            this.lastMode = mode;
            MaidDebug.log("Combat mode=" + mode + " dist=" + MaidDebug.fmt1(dist));
        }
    }

    private static String describe(LivingEntity entity) {
        return entity == null ? "null" : entity.getType().toShortString() + "@" + entity.blockPosition();
    }
}
