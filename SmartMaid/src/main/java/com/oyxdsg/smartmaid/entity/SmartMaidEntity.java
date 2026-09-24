package com.oyxdsg.smartmaid.entity;

import com.oyxdsg.smartmaid.data.MaidDataManager;
import com.oyxdsg.smartmaid.data.MaidSettings;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.MaidActionExecutor;
import com.oyxdsg.smartmaid.entity.ai.MaidFollowGoal;
import com.oyxdsg.smartmaid.entity.ai.MaidGroundPathNavigation;
import com.oyxdsg.smartmaid.entity.ai.MaidMonitor;
import com.oyxdsg.smartmaid.entity.ai.MaidMoveControl;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidTelemetryWriter;
import com.oyxdsg.smartmaid.entity.ai.combat.MaidCombatGoal;
import com.oyxdsg.smartmaid.entity.ai.combat.MaidTargetFilter;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskManager;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionModule;
import com.oyxdsg.smartmaid.gui.MaidControlMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 智能女仆实体（MVP，Minecraft 26.2 Fabric）。
 *
 * <p>继承 {@link TamableAnimal} 获得标准驯服/主人/坐下能力，AI 使用轻量 Goal 系统。</p>
 *
 * <p>分层设计：</p>
 * <ul>
 *     <li><b>L0 安全层</b> — {@link #applySafetyRules()} 硬编码危险拦截（岩浆/火），不依赖外部输入</li>
 *     <li><b>L1 规则层</b> — Goal 系统表达确定性行为（跟随/散步）</li>
 * </ul>
 */
public class SmartMaidEntity extends TamableAnimal implements ContainerUser {

    /** 开发调试：强制播放的动画 id（0=无，由 /maidanim 指令设置，同步到客户端渲染） */
    private static final EntityDataAccessor<Integer> DATA_DEBUG_ANIM =
            SynchedEntityData.defineId(SmartMaidEntity.class, EntityDataSerializers.INT);

    /** 头顶气泡文本（同步到客户端；渲染器按宽度拆成多行显示；空串=无气泡） */
    private static final EntityDataAccessor<String> DATA_BUBBLE =
            SynchedEntityData.defineId(SmartMaidEntity.class, EntityDataSerializers.STRING);

    /** 动作执行器：处理跨沟/跳上高台等精确跳越动作 */
    private final MaidActionExecutor actionExecutor;

    /** AI 任务调度器：驱动 AI 指令（攻击/挖矿/耕作/建造/制作等） */
    private final MaidTaskManager maidTaskManager;

    /** 感知模块：给 AI 提供"玩家视角"的感知快照（自身/背包/主人/周边/方块/环境） */
    private final PerceptionModule perceptionModule;

    /** 战斗 Goal：接管索敌后的走位/攻击（战斗高于 AI 任务；注册于 registerGoals） */
    private MaidCombatGoal combatGoal;

    /** 女仆饱食度（仿玩家，仅内部状态：进食回饱食、自然回血、饥饿伤害） */
    private final MaidFoodData maidFood = new MaidFoodData();

    /** 玩家绑定的女仆设置（护主模式/跟随/掉落/属性等；首次取用时按主人 UUID 加载） */
    private MaidSettings settings = new MaidSettings();
    /** 已加载设置对应的主人 UUID（主人变化时重新加载） */
    private UUID settingsOwner;

    /** 女仆背包：41 格，索引照搬原版玩家 {@link net.minecraft.world.entity.player.Inventory}（0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手） */
    private final SimpleContainer maidInventory = new SimpleContainer(41);

    /** 拾取抑制截止 tick：刚把物品丢给主人后，女仆在这之前不去捡（避免"丢出去又被立刻捡回"） */
    private int pickupSuppressUntil = 0;

    /** 默认显示名（气泡结束后恢复） */
    private static final Component DISPLAY_NAME = Component.literal("女仆");

    /** 聊天气泡剩余 tick（&gt;0 时头顶显示气泡文字，替代默认名字） */
    private int bubbleTicksLeft;
    /** 气泡占用 customName 前的名字（供 {@link #baseName()} 上报，避免上报气泡文案） */
    private String nameBeforeBubble;

    /** 移动兜底距离阈值：与主人距离超过该值（平方）直接传送回主人身边 */
    private static final double TELEPORT_FALLBACK_DISTANCE_SQR = 32.0D * 32.0D;

    /** 移动兜底冷却（tick）：传送后暂停一段时间，避免每 tick 反复尝试 */
    private int teleportFallbackCooldown;

    /** 当前"打开"的容器位置（chestopen 指令记录，供 chestput/chesttake 使用） */
    private BlockPos openedContainerPos;

    /** 最近一次攻击者缓存（B 方案：hurtServer 最早阶段抓取，弥补 getLastHurtByMob 被消费后为 null） */
    private Entity lastAttacker;
    /** 最近一次受伤的 tick（新鲜度保护：仅 5s 内有效） */
    private int lastAttackerTick;

    /** ContainerUser：容器交互距离（开箱动画用，参照玩家默认 5 格） */
    @Override
    public double getContainerInteractionRange() {
        return 5.0D;
    }

    /** ContainerUser：女仆无打开的容器菜单，恒 false（开箱动画由 openCount 驱动） */
    @Override
    public boolean hasContainerOpen(net.minecraft.world.level.block.entity.ContainerOpenersCounter counter, BlockPos pos) {
        return false;
    }

    /** ContainerUser：返回自身，供开箱动画取实体 */
    @Override
    public net.minecraft.world.entity.LivingEntity getLivingEntity() {
        return this;
    }

    public SmartMaidEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        // 移动控制：沿用原版 MoveControl 体系，跳跃触发参照车万女仆优化
        this.moveControl = new MaidMoveControl(this);
        this.actionExecutor = new MaidActionExecutor(this);
        this.maidTaskManager = new MaidTaskManager(this);
        this.perceptionModule = new PerceptionModule(this);
        // 拾取地上的掉落物（进女仆背包）
        this.setCanPickUpLoot(true);
        this.setCustomName(DISPLAY_NAME);
        this.setCustomNameVisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_DEBUG_ANIM, 0);
        builder.define(DATA_BUBBLE, "");
    }

    /** 头顶气泡文本（客户端渲染读取；空串=无气泡） */
    public String getBubbleText() {
        return this.entityData.get(DATA_BUBBLE);
    }

    /** 当前强制播放的调试动画 id（0=无，客户端渲染读取） */
    public int getDebugAnim() {
        return this.entityData.get(DATA_DEBUG_ANIM);
    }

    /** 设置强制播放的调试动画 id（服务端，仅开发校验用） */
    public void setDebugAnim(int id) {
        this.entityData.set(DATA_DEBUG_ANIM, id);
    }

    @Override
    public void setOrderedToSit(boolean sit) {
        // 26.2 的 TamableAnimal.setOrderedToSit 只设本地字段，不设坐姿标志（DATA_FLAGS），
        // 导致客户端 isInSittingPose() 一直 false、坐姿渲染不生效。这里补上同步标志。
        super.setOrderedToSit(sit);
        this.setInSittingPose(sit);
        if (this.level().isClientSide()) {
            return;
        }
        // 服务端：坐姿姿态 + 落到支撑面 + 锁死移动（所有入口——右键 / GUI / 指令 / AI 桥接——统一走这里）
        this.setPose(sit ? Pose.SITTING : Pose.STANDING);
        if (sit) {
            this.snapToSupportForSit();
            this.setTarget(null);
            this.applySitLock();
        }
    }

    /**
     * 坐下前的"落地"：把女仆吸附到脚下支撑面（地面 / 半砖 / 台阶 / 地毯顶面）。
     *
     * <p>从脚下所在格向下最多 3 格找最近的可站立碰撞顶面，且顶面不高于当前脚下
     * ——这样坐在**半砖**上时脚下坐标正是半砖顶（方块基座 + 0.5），
     * 渲染层再下沉髋部高度即可让屁股贴住半砖，而不是浮在半空。</p>
     */
    private void snapToSupportForSit() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        double feetY = this.getY();
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos pos = this.blockPosition().offset(0, dy, 0);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            double top = pos.getY() + shape.max(Direction.Axis.Y);
            if (top > feetY + 0.02D) {
                // 支撑面高于脚下（不该发生）：继续往下找，避免把女仆顶上去
                continue;
            }
            if (Math.abs(top - feetY) > 0.001D) {
                this.setPos(this.getX(), top, this.getZ());
                this.setDeltaMovement(0.0D, 0.0D, 0.0D);
                MaidDebug.log("Maid sit 落地: y " + MaidDebug.fmt1(feetY) + " -> " + MaidDebug.fmt1(top)
                        + " pos=" + this.blockPosition().toShortString());
            }
            return;
        }
        // 脚下 3 格内没有支撑面（悬空/深坑）：不强行挪动，交给重力自然落下
        MaidDebug.log("Maid sit 无支撑面，保持原位 pos=" + this.blockPosition().toShortString());
    }

    /**
     * 坐姿锁定：坐下期间不允许任何移动（清移动意图 + 清水平惯性）。
     *
     * <p>坐下时女仆会因为残余路径 / MoveControl 操作 / 击退 / 水流被推着走
     * （表现为"坐着走"）。这里每 tick 清一次导航与水平速度；只清水平分量，
     * 保留 y 速度让重力把她正常压在地面/半砖上。</p>
     */
    private void applySitLock() {
        if (!this.isOrderedToSit()) {
            return;
        }
        if (this.getTarget() != null) {
            this.setTarget(null);
        }
        this.getNavigation().stop();
        this.getMoveControl().setWait();
        this.setXxa(0.0F);
        this.setZza(0.0F);
        double dx = this.getDeltaMovement().x;
        double dz = this.getDeltaMovement().z;
        if (dx * dx + dz * dz > 1.0E-6D) {
            this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                // 速度：0.16 是能覆盖全部跳跃场景（跨3格沟+上1格）的最小值（离线解表标定）
                .add(Attributes.MOVEMENT_SPEED, 0.16D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                // 攻击速度基线（玩家同为 4.0），供武器攻速解析（近战 DPS 评分）使用
                .add(Attributes.ATTACK_SPEED, 4.0D)
                // 攻击/感知范围：决定索敌与追击距离
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4D);
    }

    /** 女仆近战实际可攻击距离（格）：判定边界为 3.0，留 0.2 缓冲避免"判定到了够不着" */
    public static final double MAID_ATTACK_REACH = 3.2D;

    /**
     * 覆写原版近战距离判定为固定 {@value #MAID_ATTACK_REACH} 格。
     *
     * <p>原版默认 {@code DEFAULT_ATTACK_REACH = 3.0}（或物品 {@code ATTACK_RANGE} 组件）；
     * {@link MeleeAttackGoal#canPerformAttack} 经此方法判断能否出手。</p>
     */
    @Override
    public boolean isWithinMeleeAttackRange(LivingEntity target) {
        return this.getAttackBoundingBox(MAID_ATTACK_REACH).intersects(target.getBoundingBox());
    }

    /**
     * 覆写原版架盾判定：去掉 {@code blockDelayTicks}（盾 5 tick）前摇，举盾立即生效。
     *
     * <p>原版 {@code LivingEntity.getItemBlockingWith} 要求「使用时长 ≥ 盾的
     * {@code blockDelayTicks()}（0.25s = 5 tick）」才认作举盾中——那是给"需要按住右键"的
     * 玩家设计的平衡（防止瞬发格挡）；女仆是 PVE 高手、反应极快，命中前检测到威胁即可
     * 当场举盾挡下，不受该前摇限制。</p>
     *
     * <p>由 {@code isBlocking()} 与 {@code applyItemBlocking}（格挡减伤）共用，覆写此一处
     * 即让「使用中的含 BLOCKS_ATTACKS 物品」立即生效；角度判定（{@code resolveBlockedDamage}
     * 的 ~100° 扇形）仍保留，盾只挡正面来向。</p>
     */
    @Override
    public ItemStack getItemBlockingWith() {
        if (!this.isUsingItem()) {
            return null;
        }
        ItemStack item = this.getUseItem();
        return item.has(DataComponents.BLOCKS_ATTACKS) ? item : null;
    }

    /** 友军免伤：默认不把玩家作为攻击目标（友军=玩家自己；可在女仆菜单开启"友军伤害"） */
    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Player && !this.getSettings().isFriendlyFire()) {
            return false;
        }
        return super.canAttack(target);
    }

    /** 友军免伤硬拦截：未开启友军伤害时，即使被显式指定也绝不伤害玩家 */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        if (target instanceof Player && !this.getSettings().isFriendlyFire()) {
            return false;
        }
        return super.doHurtTarget(level, target);
    }

    public MaidActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    /** AI 任务调度器（指令入口经此设置任务） */
    public MaidTaskManager getMaidTaskManager() {
        return this.maidTaskManager;
    }

    /** 感知模块（采样/查询感知快照） */
    public PerceptionModule getPerceptionModule() {
        return this.perceptionModule;
    }

    /** AI 任务是否正在接管行为决策（跟随/自动攻击/散步 Goal 据此让路） */
    public boolean isAiBusy() {
        return this.maidTaskManager.isAiBusy();
    }

    /** 是否处于战斗状态（战斗优先于 AI 任务；寻路降级据此禁用） */
    public boolean isCombatActive() {
        return this.combatGoal != null && this.combatGoal.isEngaged();
    }

    /** 女仆饱食度（内部状态） */
    public MaidFoodData getMaidFood() {
        return this.maidFood;
    }

    /**
     * 玩家绑定的女仆设置。服务端首次取用时按主人 UUID 从磁盘加载，并即时应用属性。
     * 客户端（无主人归属语义）直接返回当前实例，UI 数值由菜单 ContainerData 提供。
     */
    public MaidSettings getSettings() {
        if (this.level().isClientSide()) {
            return this.settings;
        }
        UUID owner = this.getOwnerReference() == null ? null : this.getOwnerReference().getUUID();
        if (owner != null && !owner.equals(this.settingsOwner)) {
            this.settings = MaidSettings.get(owner);
            this.settingsOwner = owner;
            this.settings.applyToMaid(this);
        }
        return this.settings;
    }

    /**
     * 远程弹药来源（仿玩家 {@code Player.getProjectile}）：原版 {@code LivingEntity.getProjectile}
     * 默认返回空，只有玩家会从背包找箭。女仆在此从自己背包找武器支持的弹药（箭）。
     */
    @Override
    public ItemStack getProjectile(ItemStack weapon) {
        if (weapon.getItem() instanceof ProjectileWeaponItem weaponItem) {
            Predicate<ItemStack> supported = weaponItem.getAllSupportedProjectiles();
            for (int i = 0; i < this.maidInventory.getContainerSize(); i++) {
                ItemStack stack = this.maidInventory.getItem(i);
                if (supported.test(stack)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 进食完成钩子：原版 {@code FoodProperties.onConsume} 只在 {@code instanceof Player} 时加饱食，
     * 女仆需要在这里按 FOOD 组件补记饱食度（食物效果由 {@code Consumable} 已先行应用）。
     */
    @Override
    protected void completeUsingItem() {
        ItemStack used = this.getUseItem().copy();
        super.completeUsingItem();
        FoodProperties food = used.get(DataComponents.FOOD);
        if (food != null) {
            this.maidFood.eat(food);
            MaidDebug.log("Maid eat " + used.getItem().getDescriptionId()
                    + " food=" + this.maidFood.getFoodLevel()
                    + " sat=" + MaidDebug.fmt1(this.maidFood.getSaturationLevel()));
        }
    }

    /** 当前"打开"的容器位置（chestopen 设置；chestput/chesttake 读取） */
    public BlockPos getOpenedContainer() {
        return this.openedContainerPos;
    }

    public void setOpenedContainer(BlockPos pos) {
        this.openedContainerPos = pos;
    }

    /**
     * 受伤入口（26.2 签名：{@code hurtServer(ServerLevel, DamageSource, float)}）。
     *
     * <p>在最早期阶段缓存攻击者：{@code DamageSource} 的 causing/direct 实体在构造时即固定，
     * 不受 {@link LivingEntity} 内部 {@code lastHurtBy*} 重置 / {@code EntityReference} 失效影响
     * ——解决感知事件 {@code hurt} 攻击者常为 {@code ?} 的问题。</p>
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        this.captureAttacker(source);
        boolean wasBlocking = this.isBlocking();
        boolean result = super.hurtServer(level, source, amount);
        // 中箭诊断：打印箭矢实体 / 女仆举盾状态（朝向 + 时间）/ 格挡情况，排查"打骷髅总是中箭"
        if (source.getDirectEntity() instanceof AbstractArrow arrow) {
            Vec3 ap = arrow.position();
            Vec3 av = arrow.getDeltaMovement();
            Entity owner = arrow.getOwner();
            // 箭矢来向 vs 女仆朝向的水平夹角（0=正对，盾牌格挡在 ~100° 内有效）
            double dx = ap.x - this.getX();
            double dz = ap.z - this.getZ();
            double arrowYawDeg = Math.toDegrees(Mth.atan2(-dx, dz));
            double facingErr = Mth.wrapDegrees(this.getYHeadRot() - (float) arrowYawDeg);
            ItemStack shieldSlot = this.getOffhandItem();
            MaidDebug.log("Combat hit-by-arrow arrow=" + arrow.getType().toShortString()
                    + " owner=" + (owner == null ? "?" : owner.getType().toShortString())
                    + " pos=(" + ap.x + "," + ap.y + "," + ap.z + ") vel=("
                    + String.format("%.1f", av.x) + "," + String.format("%.1f", av.y) + "," + String.format("%.1f", av.z) + ")"
                    + " dmg=" + String.format("%.1f", amount)
                    + " blockingBefore=" + wasBlocking
                    + " isBlocking=" + this.isBlocking()
                    + " yaw=" + String.format("%.1f", this.getYRot())
                    + " headRot=" + String.format("%.1f", this.getYHeadRot())
                    + " arrowYaw=" + String.format("%.1f", arrowYawDeg)
                    + " facingErr=" + String.format("%.1f", facingErr)
                    + " offhand=" + MaidDebug.itemName(shieldSlot)
                    + " usingTicks=" + (this.isUsingItem() ? this.getTicksUsingItem() : -1));
        }
        return result;
    }

    private void captureAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        this.lastAttacker = attacker;
        this.lastAttackerTick = this.tickCount;
    }

    /**
     * 最近攻击者（感知/遥测事件用）。
     *
     * <p>缓存新鲜度 100 tick（5s）：环境伤害（岩浆/摔落）或长期未再受伤时返回 null，
     * 由调用方映射为 {@code unknown}。</p>
     *
     * <p>命名避开 26.2 {@code Attackable.getLastAttacker()}（返回 {@code LivingEntity}）冲突。</p>
     */
    public Entity getRecentAttacker() {
        if (this.lastAttacker == null || this.tickCount - this.lastAttackerTick > 100) {
            return null;
        }
        return this.lastAttacker;
    }

    /** 女仆背包容器（41 格，索引照搬原版玩家背包） */
    public SimpleContainer getMaidInventory() {
        return this.maidInventory;
    }

    /** 把背包中的主手/副手/盔甲槽同步到实体的真实装备栏（服务端，保证穿戴/渲染/护甲生效） */
    public void syncInventoryArmor() {
        if (this.level().isClientSide()) {
            return;
        }
        // 主手：手持热键栏第 0 格（默认手持位置，仿玩家主手逻辑，任意物品可拿）
        syncSlot(EquipmentSlot.MAINHAND, 0, false);
        // 盔甲：只允许对应部位的装备
        syncSlot(EquipmentSlot.HEAD, 36, true);
        syncSlot(EquipmentSlot.CHEST, 37, true);
        syncSlot(EquipmentSlot.LEGS, 38, true);
        syncSlot(EquipmentSlot.FEET, 39, true);
        // 副手：像玩家一样可放任意物品
        syncSlot(EquipmentSlot.OFFHAND, 40, false);
    }

    private void syncSlot(EquipmentSlot slot, int index, boolean requireEquippable) {
        ItemStack stored = this.maidInventory.getItem(index);
        // 校验：盔甲槽只有能装备在该部位的物品才上装备栏，其余一律移除（防止"穿戴任何物品"）；
        // 主手/副手与玩家逻辑一致，任意物品均可持有，不做校验
        if (!stored.isEmpty() && requireEquippable && !this.isEquippableInSlot(stored, slot)) {
            MaidDebug.log("盔甲槽校验失败移除: slot=" + slot.getName() + " item=" + stored.getItem().getDescriptionId());
            // 放错槽位时气泡提示主人
            this.showBubble(Component.literal("放错位置啦：这不是" + slot.getName() + "槽"), 60);
            this.setItemSlot(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack current = this.getItemBySlot(slot);
        if (!ItemStack.isSameItemSameComponents(current, stored)) {
            this.setItemSlot(slot, stored);
        }
    }

    /** 把物品放入背包区（0-35）第一个空槽，背包满则返回原物品。 */
    public ItemStack moveToBackpack(ItemStack stack) {
        for (int i = 0; i < 36; i++) {
            if (this.maidInventory.getItem(i).isEmpty()) {
                this.maidInventory.setItem(i, stack);
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // P5 战斗：战斗 Goal（优先级高于跟随，有目标时优先战斗）
        this.combatGoal = new MaidCombatGoal(this);
        this.goalSelector.addGoal(1, this.combatGoal);
        // L1 规则：跟随主人（距离/开关由女仆设置实时控制）
        this.goalSelector.addGoal(2, new MaidFollowGoal(this, 1.0D));
        // P4 空闲行为：随机散步 / 环视（AI 任务运行时散步让路）
        this.goalSelector.addGoal(3, new AiAwareRandomStrollGoal());
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        // P5 目标选择：护主模式实时决定启用哪些目标来源
        //   主动 ACTIVE：主人被打 / 主人攻击的目标 / 附近敌对生物，都会索敌
        //   被动 PASSIVE：只在主人被打时反击
        //   关闭 OFF：不主动索敌
        SmartMaidEntity self = this;
        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                return self.getSettings().getProtectMode() != MaidSettings.ProtectMode.OFF && super.canUse();
            }
        });
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this) {
            @Override
            public boolean canUse() {
                return self.getSettings().getProtectMode() == MaidSettings.ProtectMode.ACTIVE && super.canUse();
            }
        });
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, false,
                (target, level) -> MaidTargetFilter.isHostile(self, target)) {
            @Override
            public boolean canUse() {
                return self.getSettings().getProtectMode() == MaidSettings.ProtectMode.ACTIVE && super.canUse();
            }
        });
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        // 原版地面导航 + 自定义节点评估器（允许跳跃跨越障碍/沟的寻路）
        return new MaidGroundPathNavigation(this, level);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide()) {
            // 客户端：推进女仆动作动画 tick（/maidanim 播放的 Emotecraft 动作）
            com.oyxdsg.smartmaid.client.animation.MaidAnimManager.tick(this);
            // 26.2：updateSwingTime 只在 Monster/Player 子类的 aiStep 里被调用，普通 Mob（含女仆）
            // 挥动动画不自动推进 → 手动补推进，让挖掘/攻击挥动可见
            this.tickSwingAnim();
        } else {
            // L0 本地安全层：每 tick 硬编码规则评估，优先级最高
            this.applySafetyRules();
            // 饱食度：仿玩家回血/饥饿（内部状态）
            if (this.level() instanceof ServerLevel foodLevel) {
                this.maidFood.tick(foodLevel, this);
            }
            // 调试：饱食度/回血状态（verbose 门控，避免长期刷屏）
            if (MaidDebug.verbose() && this.tickCount % 40 == 0) {
                MaidDebug.log("Maid food level=" + this.maidFood.getFoodLevel()
                        + " sat=" + MaidDebug.fmt1(this.maidFood.getSaturationLevel())
                        + " hp=" + MaidDebug.fmt1(this.getHealth()) + "/" + MaidDebug.fmt1(this.getMaxHealth()));
            }
            // L1 移动兜底：距离主人过远时直接传送回主人身边
            this.applyTeleportFallback();
            // 聊天气泡倒计时：到期恢复默认名字
            if (this.bubbleTicksLeft > 0) {
                this.bubbleTicksLeft--;
                if (this.bubbleTicksLeft == 0) {
                    this.entityData.set(DATA_BUBBLE, "");
                }
            }
            // 坐下 = 待命：锁死移动（不跟随 / 不散步 / 不跳跃 / 不被击退推走）+ 清空攻击目标
            this.applySitLock();
            // 坐下状态低频观测（verbose 门控）：确认坐下期间坐标不变（排查"坐着走"）
            if (MaidDebug.verbose() && this.isOrderedToSit() && this.tickCount % 40 == 0) {
                MaidDebug.log("Maid sit 保持 pos=" + MaidDebug.fmt1(this.getX()) + ","
                        + MaidDebug.fmt1(this.getY()) + "," + MaidDebug.fmt1(this.getZ())
                        + " onGround=" + this.onGround());
            }
            // 背包盔甲/副手槽 → 真实装备栏同步（穿戴生效）
            if (this.tickCount % 10 == 0) {
                this.syncInventoryArmor();
            }
            // 调试：服务端装备状态（每 40 tick，高噪日志由 verbose() 单独开启，避免长期累积）
            if (MaidDebug.verbose() && this.tickCount % 40 == 0) {
                MaidDebug.log("服务端装备: mainhand=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.MAINHAND))
                        + " offhand=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.OFFHAND))
                        + " head=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.HEAD))
                        + " chest=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.CHEST))
                        + " inv0=" + MaidDebug.itemName(this.maidInventory.getItem(0))
                        + " inv40=" + MaidDebug.itemName(this.maidInventory.getItem(40)));
            }
            // 动作执行器：跨沟/跳越精确执行（坐下时不执行跳跃）
            if (!this.isOrderedToSit()) {
                this.actionExecutor.tick();
            }
            // AI 任务调度器：驱动当前 AI 任务（攻击/挖矿/耕作/建造/制作）
            this.maidTaskManager.tick();
            // 感知模块：采样"玩家视角"快照 + 事件队列
            this.perceptionModule.tick();
            // 遥测下行（M5-a）：把女仆事件按窗口写入 deskpet/maid/，供桌面宠物读取
            MaidTelemetryWriter.onMaidTick(this);
            // 桌宠联动（M5-b）：注册 WebSocket 通道并订阅感知事件
            MaidWsClient.onMaidTick(this);
            // 移动调试监测：玩家/女仆状态 + 周围地形（开发用）
            MaidMonitor.tick(this);
            // 定期把装备/物品栏数据保存到本地文件（退出游戏后重新召唤可恢复）
            if (this.tickCount % 100 == 0) {
                MaidDataManager.save(this);
            }
        }
    }

    /**
     * 手动推进挥动动画（客户端专用）。
     *
     * <p>26.2 的 {@link LivingEntity#updateSwingTime()} 只在 {@code Monster}/{@code Player}
     * 子类的 {@code aiStep} 里被调用，普通 Mob（含女仆）收到挥动动画包后
     * {@code attackAnim} 永远不会推进 → 挖掘/攻击挥动看不见。这里在客户端 aiStep
     * 里手动复制原版推进逻辑（{@code oAttackAnim} 由 {@code baseTick} 自动更新，插值正常）。
     * 挥动时长 {@code getCurrentSwingDuration()} 是 private，用反射取（含物品时长/挖掘加速）。</p>
     */
    private static final java.lang.reflect.Method SWING_DURATION_METHOD;

    static {
        java.lang.reflect.Method m = null;
        try {
            m = LivingEntity.class.getDeclaredMethod("getCurrentSwingDuration");
            m.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        SWING_DURATION_METHOD = m;
    }

    private void tickSwingAnim() {
        int duration = 6;
        if (SWING_DURATION_METHOD != null) {
            try {
                duration = (int) SWING_DURATION_METHOD.invoke(this);
            } catch (Exception ignored) {
            }
        }
        if (duration <= 0) {
            duration = 6;
        }
        if (this.swinging) {
            this.swingTime++;
            if (this.swingTime >= duration) {
                this.swingTime = 0;
                this.swinging = false;
            }
        } else {
            this.swingTime = 0;
        }
        this.attackAnim = (float) this.swingTime / (float) duration;
    }

    /**
     * L0 — 本地安全规则（物理级硬编码，断网/无 AI 也生效）。
     */
    private void applySafetyRules() {
        BlockPos underPos = this.blockPosition().below();
        BlockState below = this.level().getBlockState(underPos);
        boolean inLavaLike = below.getBlock() == Blocks.LAVA
                || below.getBlock() == Blocks.FIRE
                || below.getBlock() == Blocks.MAGMA_BLOCK;
        if (inLavaLike && !this.isInWater() && !this.isInLava()) {
            this.jumpFromGround();
            this.setDeltaMovement(this.getDeltaMovement().add(
                    this.random.nextGaussian() * 0.3D, 0.3D, this.random.nextGaussian() * 0.3D));
        }
        // 生命值过低且处于危险液体中：强制脱离
        if (this.getHealth() <= 3.0F && (this.isInWater() || inLavaLike)) {
            this.jumpFromGround();
        }
    }

    /**
     * L1 — 移动兜底规则：距离主人过远时直接传送回主人身边。
     *
     * <p>触发条件：主人有效、非坐下/乘骑/被拴绳（{@link #unableToMoveToOwner()}），且与主人距离
     * 超过 {@value #TELEPORT_FALLBACK_DISTANCE_SQR} 的平方根。先尝试原版
     * {@link #tryToTeleportToOwner()}（在主人周围随机找安全落点，最多 10 次），失败则硬传送到主人脚下，
     * 避免女仆寻路失败/卡死后永久丢失在远处。</p>
     */
    private void applyTeleportFallback() {
        // 坐下 = 原地待命：不追主人、也不传送（主子走远了就留在原地等她回来）
        if (this.isOrderedToSit()) {
            return;
        }
        if (this.teleportFallbackCooldown > 0) {
            this.teleportFallbackCooldown--;
            return;
        }
        LivingEntity owner = this.getOwner();
        if (owner == null || this.unableToMoveToOwner()) {
            return;
        }
        if (this.distanceToSqr(owner) < TELEPORT_FALLBACK_DISTANCE_SQR) {
            return;
        }
        this.tryToTeleportToOwner();
        // 原版找点可能失败（如主人周围全是悬崖/水）：硬传送到主人脚下兜底
        if (this.distanceToSqr(owner) >= TELEPORT_FALLBACK_DISTANCE_SQR) {
            this.snapTo(owner.getX(), owner.getY() + 1.0D, owner.getZ(), owner.getYRot(), owner.getXRot());
        }
        this.teleportFallbackCooldown = 100;
    }

    /**
     * 显示聊天气泡：头顶文字持续 {@code ticks} tick 后消失，并恢复默认名字。
     *
     * <p>当前用名字标签（customName）实现，跟随原版名字渲染，零自定义渲染代码；
     * 后续可升级为带面板的气泡渲染。</p>
     */
    public void showBubble(Component text, int ticks) {
        if (this.level().isClientSide()) {
            return;
        }
        if (this.bubbleTicksLeft <= 0) {
            // 记下气泡期间的名字，供感知/遥测上报使用（避免上报气泡文案）
            this.nameBeforeBubble = this.getName().getString();
        }
        // 气泡走独立同步字段（客户端渲染器拆成多行），不再顶替 customName
        this.entityData.set(DATA_BUBBLE, text.getString());
        this.bubbleTicksLeft = Math.max(ticks, 1);
    }

    /**
     * 稳定显示名：气泡占用 customName 期间返回气泡前的名字。
     *
     * <p>感知/遥测上报一律用这个名字——否则每弹一次气泡，上报的 {@code name}
     * 就会在「女仆 ↔ 气泡文案」之间跳变，既误导 AI 又制造无谓的增量 diff。</p>
     */
    public String baseName() {
        return this.bubbleTicksLeft > 0 && this.nameBeforeBubble != null
                ? this.nameBeforeBubble : this.getName().getString();
    }

    /**
     * 强制传送回主人身边（GUI「召回」按钮）。
     * 先尝试原版安全找点，未贴近则硬传送到主人脚下。
     */
    public void teleportToOwner() {
        LivingEntity owner = this.getOwner();
        if (owner == null || this.level().isClientSide()) {
            return;
        }
        this.tryToTeleportToOwner();
        if (this.distanceToSqr(owner) > 9.0D) {
            this.snapTo(owner.getX(), owner.getY() + 1.0D, owner.getZ(), owner.getYRot(), owner.getXRot());
        }
        this.teleportFallbackCooldown = 100;
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
        // 刚把东西丢给主人时短暂不捡，避免「丢出去又立刻被捡回」。
        if (this.tickCount < this.pickupSuppressUntil) {
            return false;
        }
        // 其余情况女仆拾取所有掉落物（放入女仆背包）
        return true;
    }

    /** 让女仆在接下来 {@code ticks} tick 内不拾取任何掉落物（丢给主人后调用，防捡回）。 */
    public void suppressPickup(int ticks) {
        this.pickupSuppressUntil = Math.max(this.pickupSuppressUntil,
                this.tickCount + Math.max(0, ticks));
    }

    @Override
    public ItemStack equipItemIfPossible(ServerLevel level, ItemStack stack) {
        // 照抄原版语义：返回「成功取走的量」（非空 = 取走这么多），剩余留在掉落物里。
        // 只放入背包/热键区（0-35），绝不进入盔甲/副手槽（避免"穿上任何物品"）。
        ItemStack remaining = addToMaidStorage(stack);
        int taken = stack.getCount() - remaining.getCount();
        if (taken <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = stack.copy();
        result.setCount(taken);
        return result;
    }

    /** 把物品放入女仆背包区（0-35，热键+背包），返回未放入的剩余。照抄原版 SimpleContainer.addItem，但限定槽位范围。 */
    private ItemStack addToMaidStorage(ItemStack stack) {
        ItemStack remaining = stack.copy();
        // 先与同类物品堆叠
        for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            ItemStack existing = this.maidInventory.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int room = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(room);
                remaining.shrink(room);
            }
        }
        // 再放入空槽
        if (!remaining.isEmpty()) {
            for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
                if (this.maidInventory.getItem(i).isEmpty()) {
                    this.maidInventory.setItem(i, remaining);
                    remaining = ItemStack.EMPTY;
                }
            }
        }
        this.maidInventory.setChanged();
        return remaining;
    }

    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
        // 遥测：死亡即释放窗口缓冲与事件回调
        MaidTelemetryWriter.forget(this);
        // 告知桌宠女仆已离线（桌宠可据此恢复桌面窗口）
        MaidWsClient.notifyPresence(this, false);
        // 装备槽统一由下方背包全量掉落处理：先清零装备掉落率，避免主手/副手物品（同时存在于背包槽与装备槽）重复掉落
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            this.setDropChance(slot, 0.0F);
        }
        super.dropAllDeathLoot(level, source);
        // 按设置决定掉落范围：全部 41 格（热键0-8/背包9-35/盔甲36-39/副手40）/ 只掉背包0-35 / 全保留
        MaidSettings.DeathDropMode mode = this.getSettings().getDeathDropMode();
        int dropUpTo = switch (mode) {
            case ALL -> this.maidInventory.getContainerSize();
            case KEEP_EQUIPMENT -> 36;
            case NONE -> 0;
        };
        for (int i = 0; i < dropUpTo; i++) {
            ItemStack stack = this.maidInventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(level, stack);
                this.maidInventory.setItem(i, ItemStack.EMPTY);
            }
        }
        // 保留未掉落的物品到本地存档，重新召唤时恢复（全部掉落时等价于空存档）
        MaidDataManager.save(this);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // 唯一女仆：召唤即归属玩家。
        // 主人空手右键 → 坐下/站起；Shift+右键 → 女仆管理菜单（Shift+E 打开女仆背包，两者并存）
        if (this.isOwnedBy(player) && player.getItemInHand(hand).isEmpty()) {
            if (player.isShiftKeyDown()) {
                if (!player.level().isClientSide()) {
                    player.openMenu(new MaidControlMenuProvider(this));
                }
                return InteractionResult.SUCCESS;
            }
            if (!player.level().isClientSide()) {
                // 坐/站统一切换：setOrderedToSit 内部处理姿摆、落地吸附、移动锁定
                this.setOrderedToSit(!this.isOrderedToSit());
            }
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent) {
        // MVP 不支持繁殖
        return null;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        // 关闭原版繁殖流程，驯服逻辑在 mobInteract 中处理
        return false;
    }

    /** 随机散步 Goal：AI 任务运行时让路，避免抢占移动控制；坐下（待命）时不许散步 */
    private class AiAwareRandomStrollGoal extends RandomStrollGoal {
        AiAwareRandomStrollGoal() {
            super(SmartMaidEntity.this, 0.6D);
        }

        @Override
        public boolean canUse() {
            return !SmartMaidEntity.this.isAiBusy()
                    && !SmartMaidEntity.this.isOrderedToSit()
                    && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            // 坐下瞬间若正在散步：立即停（否则会"坐着走"）
            return !SmartMaidEntity.this.isOrderedToSit() && super.canContinueToUse();
        }
    }
}
