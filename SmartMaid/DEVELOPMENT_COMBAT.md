# Smart Maid 女仆战斗系统 · 实现方案

> 基线：`382fff3`（2026-09-16）
> 来源：`女仆战斗系统.docx`（用户设计）+ 2026-09-16 方案评审决策
> 本文是「可直接照着写代码」的深化实现方案。已完成的实现细节见 `HANDOVER.md`，踩坑记录见 `DEVELOPMENT_ISSUES.md`。

---

## 一、设计决策（已拍板）

| 项 | 决策 | 说明 |
|---|---|---|
| 运行环境 | **单人模式** | 无其他玩家；"主人被打无条件反击"只会对怪物生效 |
| 射箭 | **仿玩家**，但**不可调用原版玩家方法**（`BowItem.releaseUsing` 有 `instanceof Player` 门槛） | 复制玩家的蓄力/发射流程自行实现 |
| 盾牌格挡 | **仿玩家**（`startUsingItem(OFFSHAND)` → `isBlocking()` → `BLOCKS_ATTACKS` 减伤） | 原版自动生效 |
| 饱食度 + 回血 | 新增，**仅内部状态**（不接 HUD / 不接感知 / 不上下行） | 只做内部回血逻辑 + debug 日志 |
| 近战判定边界 | **3.0 格** | 统一定义，消除原文档 3/4 格矛盾 |
| 女仆攻击距离 | **3.2 格** | 覆写 `isWithinMeleeAttackRange`；现无设置（默认 3.0） |
| 弓箭选枪 | **只比力量附魔等级**（`Enchantments.POWER`） | 同级取先找到的 |
| 近战攻速 | **从物品解析**（`forEachModifier` 读 `ATTACK_SPEED`/`ATTACK_DAMAGE`） | 按 DPS 选武器 |
| 敌对判定 | **排除表**（末影人/僵尸猪人默认不算敌对） | 除非主动攻击主人 |
| 主人被攻击 | **无条件反击** | `OwnerHurtByTargetGoal` |
| 索敌中心 | **以女仆为中心**，要求**女仆可见** | `hasLineOfSight` |
| 友军免伤 | **硬规则：女仆无法伤害玩家**（友军只指玩家） | 近战 `doHurtTarget` 拦截；远程用 `MaidArrow` |
| 走位 | **必须能退后（不转身）**；**侧移绕行不可省略**；**背对可跳上 1 格障碍** | 用原版 `MoveControl.strafe()` |
| 与寻路降级 | 战斗期**禁用** `MaidStraightNav`（不挖/不搭） | — |
| 与跳跃 | 战斗期**保留**跳跃执行器 + 背对跳 | — |
| 鱼竿 | **保留原版分支**；非 Player 不可用时降级为右键 use（兜底） | `FishingRodItem.use(Level, Player, ...)` 是 Player 专属 |
| 优先级 | **L0 安全层 > 战斗 > 其他 AI 任务 > 常规 Goal** | — |

---

## 二、26.2 API 预研结论（已反编译确认，含纠正）

| 能力 | 真实 API（`minecraft-merged-*.jar`） | 结论 / 坑 |
|---|---|---|
| 近战距离 | `Mob.isWithinMeleeAttackRange(LivingEntity)`；`MeleeAttackGoal.canPerformAttack` 调用它；无物品 `DataComponents.ATTACK_RANGE` 时用 `DEFAULT_ATTACK_REACH` | 默认 reach = `sqrt(12.96) - 0.6 = 3.0`（clinit 确认）。**覆写该方法即得 3.2** |
| 攻击距离组件 | `net.minecraft.world.item.component.AttackRange`（26.2 新增） | 不走组件，覆写更直接 |
| 拉弓释放 | `BowItem.releaseUsing(...)` 首行 `if (!(entity instanceof Player)) return false` | **女仆不能用**，必须自写 |
| 找弹药 | `LivingEntity.getProjectile(ItemStack)` 默认 `return ItemStack.EMPTY`（`Player` 才覆写） | **女仆必须覆写**，从女仆背包找箭 |
| 箭实体 | `net.minecraft.world.entity.projectile.arrow.Arrow`；构造 `(Level, LivingEntity, ItemStack weapon, ItemStack ammo)` 内部写死 `EntityTypes.ARROW`；`AbstractArrow.setBaseDamage/setCritArrow/shoot` | 可完全复制玩家发射；**子类仍是 ARROW 类型**（见 §5.7 误伤） |
| 蓄力 | `BowItem.getPowerForTime(int)`（public static） | 直接复用 |
| 附魔 | `EnchantmentHelper.getItemEnchantmentLevel(Holder<Enchantment>, ItemInstance)`；力量 = `Enchantments.POWER` | 弓"只比力量"用它 |
| 盾牌格挡 | `isBlocking()` = `isUsingItem()` 且使用中物品含 `DataComponents.BLOCKS_ATTACKS`；减伤在 `LivingEntity.blockUsingItem`；`BlocksAttacks.blockDelayTicks()`（盾=5t） | `startUsingItem(OFFHAND)` 即生效 |
| 使用中物品推进 | `LivingEntity.tick()` 第 5 行调 `updatingUsingItem()` | 进食/举盾倒计时**自动推进**（无挥动动画那类子类坑） |
| 进食 | `DataComponents.CONSUMABLE` + `DataComponents.FOOD`；`FoodProperties.onConsume` 字节码里 `instanceof Player` 才加饱食 | 女仆能吃到效果/音效/动画，**饱食度要自己加**（覆写 `completeUsingItem`） |
| 饱食数据 | `FoodData`：`eat(int,float)`/`eat(FoodProperties)`/`addExhaustion`；**`tick(ServerPlayer)` 强依赖 ServerPlayer** | 女仆自持 `FoodData` + 自写回血 tick |
| 近战攻速 | `ItemStack.forEachModifier(EquipmentSlot, BiConsumer<Holder<Attribute>, AttributeModifier>)`；`AttributeModifier.amount()`；`Attributes.ATTACK_SPEED/ATTACK_DAMAGE` | 直接从物品解析；需先给女仆注册 `ATTACK_SPEED` |
| 退后不转身 | `MoveControl.strafe(float forward, float right)` → `Operation.STRAFE`；其 `tick()` 分支只 `setSpeed/setZza/setXxa`，**不调 `setYRot`** | **这是"背对后退"的原生原语**（MOVE_TO 会 `setYRot` → 转身，禁用） |
| 身体朝向 | `LivingEntity.tick()` → `tickHeadTurn(f)` 以 0.3 系数把 `yBodyRot` 向目标插值 | 设 `yRot` → `yBodyRot` 平滑跟随 |
| 苦力怕引信 | `Creeper.getSwellDir()` / `getSwelling(float)` / `isIgnited()`（均 public） | 威胁检测无需反射 |
| 箭矢量 | `net.minecraft.world.entity.projectile.arrow.AbstractArrow`（`getDeltaMovement()` 可预测） | 每 tick 威胁扫描 |
| 鱼竿 | `FishingRodItem.use(Level, Player, ...)`；`FishingHook.getPlayerOwner()` | Player 专属；兜底见 §5.6 |
| 手臂姿势 | `HumanoidModel.ArmPose`：`ITEM/BLOCK/BOW_AND_ARROW/...` | 现 `SmartMaidRenderer.getArmPose` 只手握就返回 `ITEM` → **会顶掉拉弓/举盾姿势，必须改** |

---

## 三、系统架构与优先级

```
每 tick：L0 安全层（applySafetyRules，硬编码，永不让路）
   ↓
战斗层（MaidCombatGoal，goalSelector priority 1）
   ↓ 战斗激活时：取消 / 拒绝所有 AI 任务
AI 任务层（MaidTaskManager）
   ↓
常规 Goal（跟随 priority 2 / 散步 3 / 环视 4）
```

**接入方式：战斗做成高优先级 Goal**（替换现有 `MaidMeleeAttackGoal`）。理由：走位/追击必须与跟随/散步互斥，Goal 系统天然解决；"战斗高于任务"用反向门控实现：

```java
// SmartMaidEntity
public boolean isCombatActive() { return this.combatGoal.isEngaged(); }

// MaidTaskManager.setTask(...) 开头
if (maid.isCombatActive()) { /* lastRejectReason = "战斗中" */ return false; }
// MaidTaskManager.tick() 里
if (maid.isCombatActive()) { cancel(); return; }
```

- `MaidCombatGoal.canUse/canContinueToUse` **不检查 `isAiBusy`**（战斗优先）。
- 其余 Goal（跟随/散步/环视）检查 `isAiBusy || isCombatActive` 让路。
- 目标由 `targetSelector` 提供，战斗 Goal 读 `mob.getTarget()`。

---

## 四、战斗状态机与决策矩阵

```
IDLE ──(targetSelector 给出目标 且 女仆可见)──▶ ENGAGED
ENGAGED 每 tick：
  1) 威胁检测（廉价）：箭矢是否将命中 / 苦力怕是否将爆
       ├─ 命中 → BLOCK（无视冷却，0.5s，可刷新）
  2) actionCooldown>0 → 仅「走位」并行，其余跳过
  3) 冷却到 → 按优先级决策：
       血量 < 50% → EAT（先退到 >6 格）
       有鱼竿且 4~6 格 → FISH
       按距离带 → MELEE / CLOSE / RANGED+KITE / RANGED
  4) 执行动作 + 设冷却（走位/盾不占冷却）
目标死亡/丢失且 3s 内无新目标 → IDLE（滞回防抖）
```

**距离带**（女仆↔敌人中心距）

| 距离 | 有远程 + 弹药 | 无远程 / 无弹药 |
|---|---|---|
| 0–3.0 | 近战（走位保持 ~3） | 近战 |
| 3.0–6.0 | 近战（拉近到 3） | 近战 |
| 6.0–15.0 | 远程 + 走位远离（kite） | 拉近到 3 近战 |
| >15.0 | 远程（站定） | 拉近到 3 近战 |

- 近战**判定边界 3.0**，**可攻击距离 3.2**（留 0.2 缓冲，避免"判定到了够不着"）。
- 吃食物：血量 < 50% → 退到 >6 格再吃；优先级最高的攻击类动作（盾除外）。

**冷却表**（1s = 20t）

| 动作 | 冷却 | 说明 |
|---|---|---|
| 盾牌格挡 | 10（可刷新，无视冷却） | 必须 ≥ `blockDelayTicks`(5) 才有效减伤 |
| 吃食物 | 34（或按 `getUseDuration`） | 足够吃完大部分食物 |
| 近战 | `clamp(round(20 / 攻速), 14, 26)` | 剑≈14(0.7s)、斧≈26(1.3s)、其他 20(1s) |
| 远程 | 24 | 足够满蓄力射出去 |
| 鱼竿 | 20 | — |

> 原则：**威胁检测每 tick，行为决策按冷却节拍**。

---

## 五、子系统详设

### 5.1 移动与面向（走位 / 退后 / 绕行 / 背对跳）★核心

**统一入口 `MaidCombatMovement`（每 tick）**

```
1. 逼近阶段（需缩短距离：3~6 近战拉近 / 6~15 无远程拉近 / >15 拉近）
   → navigation.moveTo(target)     // MOVE_TO 会朝向移动方向 = 朝敌，且跳跃执行器生效
2. 走位阶段（保持距离 / 后退 / kite / 绕行）
   → lookControl.setLookAt(target)          // 面向敌人（yRot 转向，yBodyRot 插值跟随）
   → moveControl.strafe(forward, side)      // 原版 STRAFE：移动但不改 yRot
   → 背对跳跃（forward<0 时，见下）
```

**为什么用 `strafe` 而不是 `setWantedPosition`**：`MoveControl.tick()` 的 MOVE_TO 分支会
`mob.setYRot(atan2(dz,dx)-90)`（转向移动方向）→ 后退会转身。STRAFE 分支**不调用 `setYRot`**，
所以「面向敌人 + 向后退」得以共存。

**关键细节**
- `forward`：保持 3 格时按距离误差缓动（前进缓动、**后退满幅 -1**）；kite 时 `forward = -1`。
  ⚠️ 原版 STRAFE 分支的 `zza` 直接取 `forward` 原始值，`getInputVector` 对长度 <1 的向量**不归一化**，
  因此比例值会让后退按比例减速（曾导致"后退很慢"）——后退必须给满幅。
- **不绕圈（重要）**：默认 `side = 0`——只在「面向敌人的直线」上前后调整，并在距离 ±0.3 格死区内保持不动；
  **只有后退被挡、且背对跳也上不去时才短暂侧移绕行**。这样女仆在目标前方的一个狭窄扇形内走位，
  而不是一直绕圈（2026-09-17 真机反馈修正）。
- `side`：绕行分量（见下），仅受阻时非 0。
- **瞬间对准**：`LookControl` 有转向速率上限，攻击出手前可 `setYRot(yawToTarget)` 直接对准，
  保证 `strafe` 的局部坐标轴与「面向敌人」一致（避免"斜着后退"）。
- 每 tick 必须重发 `strafe`（STRAFE 操作执行一次后即回到 WAIT）。

**背对跳跃（与原版玩家一致：背后 1 格高障碍 → 背对跳上去）**

```java
// forward < 0（正在后退）且贴住身后障碍时
if (forward < 0.0F && maid.onGround() && maid.horizontalCollision) {
    Vec3 back = backwardDir(maid);          // 面向目标的反方向（水平归一化）
    BlockPos fb = maid.blockPosition().offset(round(back.x), 0, round(back.z));
    BlockPos fh = fb.above();
    boolean obstacle  = !state(fb).getCollisionShape(level, fb).isEmpty();
    boolean headClear =  state(fh).getCollisionShape(level, fh).isEmpty();
    double topY = state(fb).getCollisionShape(level, fb).max(Direction.Axis.Y) + fb.getY();
    boolean lowEnough = topY <= maid.getY() + 1.0D;   // ≤ 1 格
    if (obstacle && headClear && lowEnough) {
        maid.getJumpControl().jump();       // 物理 = 原版 jumpFromGround（vy0=0.42，可抬 1.25 格）
    }
}
```
- 起跳后 `strafe` 的 `zza`（后向）仍有输入 → 空中继续向后位移，落到障碍顶上（与玩家背对跳同理）。
- `MaidMoveControl.tick()` 在 `straightNav` 关闭的战斗期不拦截 STRAFE → 空中输入不丢。
- 加跳跃节流（`noJumpDelay` / 自身冷却），避免贴墙每 tick 连跳。

**绕行（不可省略）**：身后被挡且后退空间不足时，`side` 取 ±1（优先选择空间更大的一侧），
`strafe(forward≈-0.2, side=±1)` 绕敌方单位走位；两侧都被挡 → 用背对跳翻 1 格。
绕行只在**受阻**期间生效，脱困后 `side` 立即归 0（不再持续绕圈）。

### 5.2 近战（判定 3.0 / 攻击 3.2 / 武器评分）

```java
// SmartMaidEntity
private static final double MAID_ATTACK_REACH = 3.2;
@Override public boolean isWithinMeleeAttackRange(LivingEntity t) {
    return this.getAttackBoundingBox(MAID_ATTACK_REACH).intersects(t.getBoundingBox());
}
```
- **补 `ATTACK_SPEED` 属性**：`createAttributes()` 现只有 `ATTACK_DAMAGE=4`，需 `.add(Attributes.ATTACK_SPEED, 4.0D)`（玩家基线）。
- **武器评分（解析物品，不查表）**：
```java
double[] dmg = {0}, spd = {0};
stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
    if (attr.value() == Attributes.ATTACK_DAMAGE.value()) dmg[0] += mod.amount();
    if (attr.value() == Attributes.ATTACK_SPEED.value())  spd[0] += mod.amount();
});
double score = (baseAttackDamage + dmg[0]) * (baseAttackSpeed + spd[0]);   // 单次伤害 × 攻速
```
- 换武器复用 `MaidActions.equipFromBackpack`（换到主手槽 0 + `syncInventoryArmor`）。
- 攻击用 `MaidActions.attack`（`doHurtTarget(ServerLevel, ...)`），随后按武器攻速设冷却。

### 5.3 远程（仿玩家，**自写发射**）

1. **选弓：只比力量附魔** `EnchantmentHelper.getItemEnchantmentLevel(POWER, bow)`；同级取先找到的。
2. **弹药**：覆写 `SmartMaidEntity.getProjectile(ItemStack weapon)`，用
   `((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles()` 在女仆背包里找箭；
   找不到 → 不进入远程分支。
3. **蓄力**：`startUsingItem(MAINHAND)`（弓）；每 tick 看 `getTicksUsingItem()`；达到
   `BowItem.getPowerForTime(ticks) >= 1.0`（≈20t）或冷却到 24t → 释放。
4. **释放（复制玩家逻辑，`BowItem.releaseUsing` 不能用）**：
```java
float power = BowItem.getPowerForTime(ticks);
MaidArrow arrow = new MaidArrow(level, maid, bowStack, arrowStack);   // 见 §5.7
arrow.setPos(maid.getX(), maid.getEyeY() - 0.1D, maid.getZ());
Vec3 dir = aimDir(target);            // 朝目标（可选速度预判）
arrow.shoot(dir.x, dir.y, dir.z, power * 3.0F, 1.0F);
arrow.setCritArrow(power >= 1.0F);
int pow = EnchantmentHelper.getItemEnchantmentLevel(POWER, bowStack);
if (pow > 0) arrow.setBaseDamage(arrow.getBaseDamage() + pow * 0.5D + 0.5D);
level.addFreshEntity(arrow);
// 手动消耗弹药（除非无限附魔） + 弓耐久
arrowStack.shrink(1);
bowStack.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
```
5. 走位：`lookControl.setLookAt(target)` + `strafe(-1, side)`（kite，面向敌人后退）。

> 实现要点：箭的 `pickup` 设为 `DISALLOWED`——女仆 `setCanPickUpLoot(true)`，
> 否则会不停捡回自己射出的箭（无限箭循环）。`getProjectile` 覆写为从女仆背包找箭
> （原版 `LivingEntity.getProjectile` 恒空，只有 `Player` 覆写）。
>
> ⚠️ **战斗结束/切换必须 `stopUsingItem()`**：否则目标死亡后 `LivingEntity` 仍持续推进
> `use` 状态、而战斗 Goal 已结束没人释放，女仆会一直**拉满弓**（朝向后又被跟随 Goal
> 转向主人）——2026-09-17 真机反馈。`MaidCombatGoal.stop()` 已释放；另在 tick 里检测
> 「拉弓中但已无箭/弓被换走」时主动松弦。

### 5.4 盾牌格挡（仿玩家）

1. 背包找盾（物品含 `DataComponents.BLOCKS_ATTACKS`）→ 换到**副手槽 40** → `syncInventoryArmor()` 同步。
   - 副手被占 → 先挪回背包。
2. `startUsingItem(InteractionHand.OFFHAND)` → `isBlocking()` 为真 → `LivingEntity.blockUsingItem` 自动减伤。
3. 持续 10t（≥ `blockDelayTicks` 5）后 `stopUsingItem()`；威胁仍在则刷新。
4. **威胁检测（每 tick）**：
   - 箭：遍历范围内 `AbstractArrow`，用 `getDeltaMovement()` 做射线，预测 1.5s 内是否与女仆 AABB 相交。
   - 苦力怕：`Creeper` 且 `getSwellDir() > 0`、`getSwelling(1f) > 0.6`、距离 < 4。
5. 背包无盾 → 分支直接跳过（不因没盾停摆）。

### 5.5 进食与回血（仅内部状态）

**为什么不用原版 `FoodData`**：`FoodData` 的 `exhaustionLevel`/`tickTimer` 是 private，
且 `tick(ServerPlayer)` 只接受 `ServerPlayer` —— 女仆无法复用。改为自实现
`entity/MaidFoodData.java`，按其反编译规则**等价复刻**：`eat(FoodProperties)`、`addExhaustion`、
自然回血（每秒 6 档：饱食≥20 且饱和度>0 每 10t 回 1/6 饱和度血；饱食≥18 每 80t 回 1）、
饥饿伤害（难度相关，与原版一致）。

- 字段：`SmartMaidEntity` 持 `private final MaidFoodData maidFood`，`getMaidFood()` 供战斗读取。
- **吃**：`/maidtasks eat [item]` / `/maidai {"cmd":"eat","params":{"item":"minecraft:golden_apple"}}`
  → `EatTask` 选食（无 item 时选营养最高、其次饱和度最高）→ 换到主手 →
  `startUsingItem(MAINHAND)`（`LivingEntity.tick()` 自动推进并完成）。
- **补饱食**：覆写 `SmartMaidEntity.completeUsingItem()`，按 `DataComponents.FOOD` 调
  `maidFood.eat(food)`（原版 `FoodProperties.onConsume` 只在 `instanceof Player` 时加饱食）。
  食物效果（如金苹果的吸收/生命恢复）由 `Consumable` 自动应用，女仆同样生效。
- **战斗触发**：血量 <50% 且背包有食物 → `MaidCombatGoal` 优先进食（先面向敌人后退到 >7 格再吃）。
- 攻击/背对跳会给 `addExhaustion`（0.1 / 0.05）。
- ⚠️ `LivingEntity.isHurt()` 在 26.2 不存在（只在 `Player`），回血判定用「血量未满」。
- **不持久化**（女仆实体 `noSave()`）、不接 HUD / 不接感知 / 不上下行，仅 `MaidDebug.log`（事件性）。

### 5.6 鱼竿（spike 结论：原版对女仆不可用）

**spike 结果（反编译确认）**：`FishingHook.tick()` 开头就是
`Player player = getPlayerOwner(); if (player == null) { discard(); return; }`——
非玩家 owner（女仆）的鱼钩**第一 tick 就被丢弃**；`shouldStopFishing`/`retrieve`/`pullEntity`
也都依赖 `Player`。且 `FishingRodItem.use(Level, Player, InteractionHand)` 是 Player 专属，
`ItemStack.useOn`（null-player 路径）不会触发它。

**结论**：原版鱼竿分支无法用"切换+右键 use"的轻量方式实现（兜底也不成立）。
真正可用的路径只有自研 `MaidFishingHook extends FishingHook` 覆写 `tick` 支持 LivingEntity owner
（需复刻抛竿/入水/咬钩/拉拽），成本较高。**当前未实现**，等用户确认是否投入。

### 5.7 索敌 / 排除表 / 友军免伤 / 误伤硬规则

**敌对排除表 `MaidTargetFilter`**
- 默认可攻击 = `Monster`（或 `Enemy`）。
- **排除（不主动打）**：`EnderMan`、`ZombifiedPiglin`（文档点名）；常量集合便于扩展。
- **友军（永不主动攻击 / 免伤）**：**只指玩家**（单人模式下即主人）。
- 现有 `PerceptionBlockUtil.findNearestHostile` 改走同一 filter（保证 `AttackTask/GuardTask` 与战斗一致）；
  距离**以女仆为中心**、要求 `maid.hasLineOfSight(e)`。

**targetSelector**
```java
this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));                // 主人被打 → 无条件反击
this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, false,
        e -> MaidTargetFilter.isHostile(this, e)));                              // 排除表 + 可见 + 友军过滤
```

**误伤硬规则（"女仆不能伤害玩家"）**

近战（双保险）：
```java
@Override public boolean canAttack(LivingEntity t) {          // 连目标都不选玩家
    return !(t instanceof Player) && super.canAttack(t);
}
@Override public boolean doHurtTarget(ServerLevel level, Entity target) {   // 兜底硬拦截
    if (target instanceof Player) return false;
    return super.doHurtTarget(level, target);
}
```

远程——原版 `AbstractArrow.canHitEntity` 只在 owner 是 Player 时才走 PvP 判定，
女仆 owner 非 Player → **原版箭能打到主人**。靠零注册子类实现硬规则：
```java
public class MaidArrow extends Arrow {
    public MaidArrow(Level level, LivingEntity owner, ItemStack weapon, ItemStack ammo) {
        super(level, owner, weapon, ammo);          // 内部写死 EntityTypes.ARROW
    }
    @Override protected boolean canHitEntity(Entity e) {
        return !(e instanceof Player) && super.canHitEntity(e);
    }
}
```
> `Arrow(Level, LivingEntity, ItemStack, ItemStack)` 内部即 `super(EntityTypes.ARROW, ...)`，
> 故 `MaidArrow` 类型仍是原版 `ARROW`：客户端走原版 `ArrowRenderer` 渲染，
> **无需注册 EntityType、无需写渲染器**；`canHitEntity` 只在服务端命中判定生效。

### 5.8 渲染（C3 已提前完成）

`SmartMaidRenderer.getArmPose` 原来「只要手持就返回 `ITEM`」，会顶掉拉弓/举盾姿势。
已改为玩家式映射：`isUsingItem()` 时按 `getUseAction()` 返回 `BOW_AND_ARROW`（弓）/
`BLOCK`（盾）/`CROSSBOW_CHARGE`/`SPYGLASS`… 否则 `ITEM` / `EMPTY`。
进食姿势由 `Consumable.animation` 驱动，`HumanoidModel` 原生处理。
（拉弓的拉伸量由 `HumanoidRenderState.ticksUsingItem` 驱动，`super.extractRenderState` 已填充。）

---

## 六、文件清单

**新增 `entity/ai/combat/`**
- `MaidCombatGoal.java` — 状态机主循环（替换 `MaidMeleeAttackGoal` 注册）
- `MaidCombatState.java` — 状态 / 冷却
- `MaidTargetFilter.java` — 排除表 + 友军判定
- `MaidWeaponSelector.java` — 近战 DPS（`forEachModifier`）+ 弓（`POWER` 附魔）
- `MaidThreatDetector.java` — 箭矢量预测 + 苦力怕引信
- `MaidCombatMovement.java` — 逼近 / 走位 / **退后** / **绕行** / **背对跳**
- `MaidMeleeSkill.java` / `MaidRangedSkill.java` / `MaidShieldSkill.java` / `MaidEatSkill.java` / `MaidFishSkill.java`
- `MaidArrow.java` — 不伤玩家的箭

**修改**
- `SmartMaidEntity`：`ATTACK_SPEED` 属性、`FoodData` + NBT、`completeUsingItem`、
  `isWithinMeleeAttackRange`(3.2)、`canAttack`、`doHurtTarget`、`getProjectile`、
  `isCombatActive`、`registerGoals`、`aiStep`
- `MaidGroundPathNavigation`：战斗期禁用 straight 降级
- `MaidTaskManager`：战斗期 `setTask` 拒绝 / `tick` 取消
- `PerceptionBlockUtil.findNearestHostile`：接 filter
- `MaidActions`：`equipShield` / `useFishingRod` 等辅助
- `SmartMaidRenderer.getArmPose`：加 `BOW_AND_ARROW` / `BLOCK`
- `MaidDebug`：战斗状态迁移日志（事件性默认输出；高频检测走 `verbose`）

---

## 七、实施里程碑

| 序 | 内容 | 验收点 | 状态 |
|---|---|---|---|
| C0 | reach 3.2 + `ATTACK_SPEED` + 武器评分 + 排除表/友军 + `canAttack`/`doHurtTarget` + `MaidArrow` | 近战距离正确；女仆与箭都伤不到主人 | ✅ 构建通过 |
| C1 | `MaidCombatGoal` + **strafe 退后/绕行/背对跳** + 近战 + 战斗期禁降级保跳跃 + 任务让路 | 女仆能退后且全程面向敌人；背后 1 格能背对跳上；战斗中会跳 1 格 | ✅ 构建+真机调试中 |
| C2 | 饱食度 + 进食 + 回血（内部） | 半血吃食物、日志见 foodLevel 变化 | ✅ 构建通过 |
| C3 | 远程（`getProjectile` + 自写发射 + `POWER` 选弓 + kite） | 箭消耗、命中、打不到主人 | ✅ 构建通过 |
| C4 | 盾牌（威胁检测 + 举盾减伤） | 苦力怕/箭来举盾 | ✅ 构建通过 |
| C5 | 鱼竿 spike（结论：原版不可用）+ 姿势渲染 + 感知 `combat` | 手臂姿势正确 | ⏳ 鱼竿待定，其余完成 |

**每步都必须：`gradlew build` 打包 → 备份旧 jar → 部署到 mods → 真机测**（`DEVELOPMENT_ISSUES.md`
2026-09-16 教训：只 `compileJava` 不打包会导致一直测旧 jar）。

---

## 八、风险与约束

| 风险 | 说明 | 对策 |
|---|---|---|
| strafe 局部轴偏移 | `LookControl` 转向有速率上限，未对准时退后会斜 | 出手/走位前可 `setYRot` 瞬间对准 |
| 背对跳误触 | 贴墙时可能连跳 | 加跳跃节流 + 头顶净空 + 高度 ≤1 判定 |
| 绕行卡死 | 两侧皆挡 | 背对跳翻 1 格；仍失败则放弃绕行改走位方向 |
| 远程自写发射 | 力量附魔/无限附魔/耐久语义需与玩家一致 | 照抄玩家公式，附魔逐项处理 |
| 鱼竿非 Player | `FishingHook` 依赖 `getPlayerOwner()` | 先 spike；不可用走兜底（已接受） |
| 任务抢占体感 | 战斗中挖矿任务被强制取消 | 按用户决策"战斗高于其他任务"，不做恢复 |
| 弓选择 | 只比力量，同级取先找到的 | 已拍板 |

---

## 九、已确认 / 不再讨论

- 单人模式：无 PvP 问题。
- 绕行 + 退后 + 背对跳：**必须实现，不可简化**。
- 弓：只比力量附魔。
- 鱼竿：保留原版，非 Player 不可用则降级（已接受）。
- 饱食度：仅内部状态。
