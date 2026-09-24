# Smart Maid 开发问题记录

> 记录开发过程中踩过的坑：现象、排查过程、根因、解决方式与核心教训。
> 重点：2026-09-05 的"玩家式移动改造"失败复盘——绕开原版移动体系自己实现物理，是本次最大的坑。

---

## 2026-09-05：移动系统重构失败复盘（重要）

### 背景与目标

用户希望女仆像玩家一样移动：跳上 1 格高方块、跳过沟壑、持续按 W。期间参考了 **Mineflayer-Baritone**（动作系统 + 执行器）的思路，尝试把女仆移动改为"动作系统 A\* 寻路 + 手动 velocity 执行 + 覆盖 travel"。

**多轮尝试全部失败**（召唤崩溃、不移动、方向反、跳不上、冻结、卡死、旋转），最终**回到"原版移动体系 + 车万女仆式跳跃优化"**（照搬 TouhouLittleMaid 的 MaidMoveControl），彻底正常。

**一句话根因**：绕开了 Minecraft 原版成熟可靠的移动体系（MoveControl / Navigation / travel / JumpControl），试图从零实现"玩家式移动物理"——这在 MC 里注定踩坑。车万女仆的正确做法是**完全用原版骨架 + 只优化跳跃触发条件**。

---

### 问题 1：`/summonmaid` 召唤女仆不生成（NPE）

- **现象**：命令执行后女仆不出现，聊天只提示"试图执行该命令时出现错误"，日志无堆栈（被 MC 吞掉）。
- **排查**：给 `SummonMaidCommand` 加 try-catch 打印堆栈 → 定位到：
  ```
  NullPointerException: Cannot invoke "NodeEvaluator.setCanFloat(boolean)"
  because "this.nodeEvaluator" is null
  ```
- **根因**：自定义 `MaidPlayerNavigator.createPathFinder()` 只 `new PathFinder(new WalkNodeEvaluator(), 0)`，**没给 `this.nodeEvaluator` 赋值**。原版 `GroundPathNavigation` 会在 `createPathFinder` 里设 `this.nodeEvaluator`，实体加入世界首次 tick 时原版逻辑调 `navigation.setCanFloat` → 访问 null 的 nodeEvaluator → NPE。
- **解决**：`createPathFinder` 里先 `this.nodeEvaluator = new WalkNodeEvaluator();`。

### 问题 2：女仆不移动，只能垂直跳（26.2 zza 输入链重构）

- **现象**：女仆跳得起（vy 有效、y 变化大），但水平完全不动；诊断显示 `zza=1.0 speed=1.3` 都已设置，`vel.x/z` 却恒为 0。
- **排查**：反编译 `LivingEntity.travel` → 26.2 移动输入链已重构，`travel` 走 `handleRelativeFrictionAndCalculateMovement`；**服务端实体上 `setZza/setSpeed` 不再可靠驱动水平位移**（`zza` 只被 `applyInput()` 做 `*=0.98` 衰减，moveRelative 用的是传入的 travelVector 但服务端实体不填）。
- **解决**：改为手动 `setDeltaMovement` 设水平速度。⚠️ 但这只是进入了下一个坑（见问题 4）。

### 问题 3：女仆方向反（yaw 换算错误）

- **现象**：女仆朝目标反方向走（要往西却往北）。
- **根因**：把 `yaw` 换算成 velocity 水平分量时，MC 前向向量是 `(-sin(yaw), 0, cos(yaw))`，我用了 `(sin, 0, cos)`，方向反了 180°。
- **解决**：改用**目标方向向量** `(dx, dz)` 归一化 × 速度，完全不经过 yaw 换算（零歧义）。

### 问题 4：跳不上 1 格方块（贴墙时水平被墙挡）

- **现象**：女仆能跳（y 升 1.3 格，超过 1 格墙顶），但跳起时水平（z）不推进，卡在墙边永远上不去。
- **排查**：反复尝试单步 `move(vel)`、分两步 `move`，均失败。
- **根因**：覆盖 `travel` 后手动 `move(MoverType.SELF, vel)` 一次性应用三维位移，`Entity.move()` 的碰撞轴处理顺序导致**贴墙起跳瞬间水平先被墙挡**；而"脚底过顶后水平再推进"依赖原版 `travel` 内部的复杂处理，我的简化版没有。
- **教训**：**覆盖 travel 手动实现移动物理不可靠**——碰撞、摩擦、轴处理、onGround 状态、重力全部要自己重造，一环错全盘崩。

### 问题 5：分两步 move（先垂直后水平）导致实体冻结

- **现象**：为让"脚底先过障碍顶再水平推进"，在 travel 里先 `move(SELF, (0, vy, 0))` 再 `move(SELF, (vx, 0, vz))` → 女仆**完全冻结**（连 y 都不动、onGround=false 悬空）。
- **根因**：一次 tick 内两次调用 `Entity.move()`，第二次调用基于第一次已更新的碰撞状态（onGround/horizontalCollision）执行，破坏了 move() 内部的状态机。
- **解决**：放弃分步；最终方案是**不覆盖 travel**，回到原版移动体系。

### 问题 6：贴墙卡死（起跳时机套错了 Mineflayer-Baritone 的场景）

- **现象**：套用 Baritone `_shouldJumpNow`（走到起跳格边缘才跳），贴墙时 `distanceToEdge = 0.3`（半宽）恒大于阈值 0.1，**永远不跳，女仆卡死**。
- **根因**：`_shouldJumpNow`（边缘判定）只适用于"跨沟/助跑"场景；**贴墙跳 1 格墙**该用 Baritone 的 **`_shouldAutoJump`**（前方 1 格是实心方块 + 上方净空 → 自动跳）。我套错了场景。
- **教训**：套用参考实现前，必须先读懂它适用什么场景，而不是照抄函数。

### 问题 7：女仆原地"旋转跳跃"

- **现象**：女仆原地跳 + 方向（yRot）剧烈变化，看起来在转圈。
- **根因**：跟随目标重算导致路径目标在多个格子间切换（如 `JUMP_UP(48,-61,-23)` ↔ `WALK(48,-62,-22)`），executor 每 tick 按目标算方向 → 目标变方向就变。
- **解决**：跳跃方向锁定（起跳时锁定朝向与前进方向，空中直线推进不转向）；跟随只在目标格变化时才重算路径。

### 问题 8：findPath 频繁 budget-exhausted

- **现象**：自写 A\* 在复杂地形（地下多层、目标 y 差 10+ 格）4096 节点预算内找不到路，女仆无路径卡住。
- **根因**：自写 A\* 单格步进爬高、预算有限；属于"自建寻路"的性能/可行性问题。
- **解决**：改用原版 `PathFinder`（其预算/剪枝/启发经过充分验证），问题消失。

---

## 核心教训（务必遵守）

1. **Minecraft 里做"类玩家移动"的正确方式 = 原版移动体系 + 最小定制**，不是重写移动物理。
   - 原版 `MoveControl` 已处理：转向、速度、碰撞、重力、摩擦、卡墙跳。
   - 只需像车万女仆那样，在 `MoveControl.tick()` 的 MOVE_TO 分支里优化"何时触发跳跃"的判断条件。
2. **服务端实体 ≠ 客户端玩家**：`zza/xxa` 输入链、物理来源、移动包都不同，不能直接移植客户端 bot（Mineflayer）的执行逻辑。
3. **先读参考实现再动手**：车万女仆 `MaidMoveControl`（TouhouLittleMaid）就是 26.2 下"跳跃增强"的正确蓝本。
4. **遇到"移动完全不动 / 冻结 / 不落地"这类基础问题**，先怀疑自己绕开了原版移动体系，而不是继续调参数/加补丁。
5. **单点修补（看日志→猜→改→再测）效率极低**，遇到反复失败应停下来系统比对参考实现。

---

## 最终正确方案（当前实现）

```
寻路:  原版 GroundPathNavigation + MaidWalkNodeEvaluator（放宽 1 格高差/深沟的寻路）
移动:  MaidMoveControl = 原版 MoveControl + 车万女仆式跳跃触发优化
跳跃:  原版 jumpFromGround()（0.42 初速度，物理全交给原版）
物理:  super.travel()（陆地完全原版，不覆盖）
行为:  MaidFollowGoal → navigation.moveTo(owner, 1.0)
```

- `MaidMoveControl` 跳跃触发（照搬车万女仆）：
  - 目标 Y 差 > 跨步高度(0.6) 且水平距离近（前方要抬 1 格）→ `jumpControl.jump()`
  - 脚下方块有碰撞、头顶低于方块顶、且非门/栅栏/可攀爬 → 跳起防卡
- `MaidWalkNodeEvaluator`：`isNeighborValid` 先走 `super`，失败后放宽上坡 +1.5 / 下坡 -3 格。

---

## 其他环境坑（记录备用）

- **PowerShell 5.1 写 `.ps1` 必须 UTF-8 带 BOM**，否则中文乱码导致解析失败。
- **Java 源码必须无 BOM**，PowerShell `Set-Content -Encoding UTF8` 会加 BOM 导致 javac 报"非法字符"。
- **`sendFailure` 直接收 `Component`**（非 Supplier），`sendSuccess` 才收 Supplier。
- **`Level.isClientSide` 是方法**（`level().isClientSide()`），不是字段。
- **`isImmobile()` / `noJumpDelay` 等在 LivingEntity 是 protected**，跨包需反射或放在同包。
- **`jumpFromGround()` 在冲刺（isSprinting）时会额外加水平速度**（沿 yRot），这是"跑跳"水平推进的物理来源。

---

## 2026-09-06：跳跃系统开发复盘（重要）

女仆跳跃/跨沟系统的完整开发记录。核心教训：**MC 物理是确定性、可逆的，直接读 prismarine-physics（mineflayer 官方物理库，完整复刻 MC 原版）拿权威公式，比反编译/手算/实测都可靠**。

### 问题 1：女仆"蜗牛"速度（MOVEMENT_SPEED 从 0.28 改 0.1 后）

- **现象**：速度慢得像蜗牛，明显低于"玩家一致"应有的 4.3 m/s。
- **排查**：反编译 26.2 移动链：`MoveControl.setSpeed → Mob.setSpeed(自动 setZza(speed)) → travelVector=(xxa,yya,zza) → moveRelative(getFrictionInfluencedSpeed(friction), travelVector)`。
- **根因**：`MaidMoveControl` 覆写 `tick()` 时**只 setSpeed、漏了显式 `setZza(1)`**。而 26.2 的 `Mob.setSpeed` 会自动 `setZza(speed)`，`moveRelative` 里 `getInputVector(travelVector, speed, yaw)` 又会 `scale(speed)`——于是 `zza=speed` 时加速度变成 **speed²**（平方衰减）：
  - 0.1 → 稳定速度仅 0.02 blocks/tick ≈ 0.44 m/s（蜗牛）
- **解决**：MOVE_TO 分支显式 `setZza(1.0F) + setXxa(0.0F)`，accel 恢复线性 = speed。

### 问题 2：26.2 速度机制（反编译确认）

- `Attributes.MOVEMENT_SPEED` 注册默认 **0.7**（不是 1.20 的 0.1），但玩家实例 override 为 **0.1**。
- 地面（friction=0.6）：`getFrictionInfluencedSpeed = getSpeed()` = `speedModifier × MOVEMENT_SPEED`。
- 空中：`getFlyingSpeed()` 固定 **0.02**（Mob 无乘客），与地面速度无关——MC 原版就是"空中靠起跳惯性"。
- 地面稳定位移速度 ≈ **2.2 × 速度属性**（0.1→4.4 m/s、0.13→5.7 m/s，与玩家实测吻合）。

### 问题 3：垂直顺序（位移先于重力）

- **现象**：手算跳跃最大抬升只有 0.83 格，却跳不上 1 格高台（与"玩家能跳 1 格"矛盾）。
- **根因**：MC 每 tick 顺序是 **位移先于重力**——`y += vy; vy = (vy - 0.08) × 0.98`，不是先重力后位移。正确结果：vy0=0.42 最大抬升 **≈1.25 格**、飞行 **≈12 tick**（此前误算 10 tick / 0.83 格）。
- **解决**：`JumpPhysics.computeFlightTicks` / 所有模拟脚本改用正确顺序。

### 问题 4：起跳朝向 yaw 公式错位

- **现象**：女仆朝错误方向跳（差 90°）。
- **根因**：MC 前向 = `(-sin(yaw), 0, cos(yaw))`，要指向落点方向 `(tx, tz)` 应 `yaw = atan2(-tx, tz)`；写成了 `atan2(-tz, -tx)`（错位）。
- **解决**：`MaidActionExecutor` 修正为 `Mth.atan2(-tx, tz)`。

### 问题 5：空中没持续给速度（跳跃距离过短）

- **现象**：起跳后落点太短（跑跳只有 ~3.3 格），与玩家 4 格不符。
- **根因**：`tickJump` 阶段 MoveControl 让路后，`applyInput` 每 tick 把 `zza ×0.98` 衰减到 0，空中前进输入消失，只剩惯性。
- **解决**：空中每 tick `setZza(2.0F)`（>1 归一化满速），`moveRelative` 持续 +0.02（prismarine 的 `airborneAcceleration`）维持速度。

### 问题 6：起跳瞬间速度不足（掉沟）——"走到起跳点停下"

- **现象**：日志显示 `Jump fired vel 水平仅 0.156`，起跳前地面速度只有 0.103，落点 2.24 < 3 格掉沟。
- **根因**：`POSITIONING` 用 `MoveControl.setWantedPosition(edgePos)` "走到起跳点**停下**"，MoveControl 接近目标减速，起跳瞬间速度几乎丢光。玩家跑跳是"**全速冲过起跳点**"。
- **解决**：**起跳瞬间直接施加离线标定的 takeoff 速度**（走 0.30 / 跑 0.55 blocks/tick，与助跑/地面速度完全解耦），符合"能力一致、不需助跑"。

### 问题 7：跨沟寻路断链（不起跳）

- **现象**：跨 2 格沟 + 对岸高 1 格完全不起跳。
- **根因**：`MaidWalkNodeEvaluator` 的跨沟邻居只生成"同层落点"，对岸高 1 格时 `isLandableCell` 失败 → 寻路无路径到高台 → `MoveControl.wanted` 到不了对岸 → 执行器不触发。
- **解决**：`addGapJumpNeighbors` 对岸落点先试同层、再试 +1 层（高台），生成高台格邻居。同时 `isGap → isJumpable`：同时识别"沟"（中间无支撑）与"≤1 格高实心障碍"。

### 问题 8：近距离跨沟跳过头

- **现象**：固定 takeoff 落点远（走 3.26 / 跑 5.13 格），跨 1 格沟会跳过对岸窄台。
- **解决**：离线解表按"**落点落入目标格**"求解 `back`（起跳点后撤量）：跨 1 沟 1.25 / 跨 2 沟 0.25 / 跨 3 沟跑跳 1.25；上跳（dy+1）撞高台不过头用 back=0。

### 架构决策（用户明确要求）

1. **能力一致而非速度一致**：女仆跳跃要做到玩家能做的一切（跳 1 格、跨 2~3 沟、上 1 格高台），跳跃 takeoff 用**固定值**（走 0.30 / 跑 0.55），**不需要助跑**。
2. **模组不做运行时物理计算**：每种障碍（dist×dy）的起跳解 `{sprint, back}` 由 **`tools/gen_jump_table.js` 离线预计算**，生成 `MaidJumpTable.java` 常量表；模组运行时**只查表**执行。
3. **移动速度体系**：`MOVEMENT_SPEED=0.16`（行走），跳跃 takeoff 与其解耦。

### 权威参考

- **prismarine-physics**（`node_modules/prismarine-physics/index.js`）：完整复刻 MC 物理（地面加速度 = 属性 × 0.1627714/inertia³、空中 0.02、起跳 vy0=0.42 + 疾跑 0.2 冲量、垂直先位移后重力）。**MC 移动/跳跃问题先查它，不要自己反编译**。
- 本地模拟工具：`SmartMaid/tools/maid_jump_sim.js`（速度/落点/朝向验证）、`tools/gen_jump_table.js`（生成解表）。
- 反编译方法：`javap -p -c -classpath <merged-jar> <类>`（jar 在 `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*.jar`）。

---

## 2026-09-07：渲染 / 交互 / 背包大修复盘（重要）

### 〇、开发规范（用户明确要求，务必遵守）

**以后每加一个新功能，顺手加上 debug 日志**（用 `MaidDebug.log`），方便出问题时**直接读游戏日志**（`logs/latest.log`）定位，而不是反复让玩家跑游戏贴日志。

- **日志要低频**：每 40 tick 或每 200 帧打一次即可，避免刷屏淹没其他日志（`MaidMonitor` 每 tick 刷地形图把关键日志淹没，就是这个教训）。
- **日志编码坑（非常重要）**：MC 在 Windows 中文系统的 `logs/latest.log` 是 **GBK 编码**，不是 UTF-8！用 Python/编辑器读日志分析时**必须 `gbk` 解码**，否则中文日志全部搜不到，会误判"日志没输出 / 代码没生效 / 功能没触发"，白排查半天。
- 排查渲染/客户端问题，日志在 **Render thread**；服务端逻辑在 **Server thread**，两者都在 `latest.log`。

### 一、26.2 渲染体系（RenderState 分离）——坐姿/装备外观相关的坑

26.2 实体渲染链路：
```
EntityRenderDispatcher.extractEntity(entity, tickDelta)
  → renderer.createRenderState(entity, tickDelta)  [final]
    → extractRenderState(Entity, EntityRenderState, F)    [bridge]
      → extractRenderState(Mob, HumanoidRenderState, F)   [bridge]
        → extractRenderState(T, S, F)                     [泛型方法，覆写点]
submit: submitNodeCollector.submitModel(model, state, ...)  // 主模型
  → model.setupAnim(state)  // layers 阶段调用
```

- **覆写 `extractRenderState` 用泛型签名 `(T,S,float)`**（javap 确认编译器会生成 bridge 链，运行时正确分派到覆写）。javap 显示的 `(Mob, HumanoidRenderState)` / `(Entity, EntityRenderState)` 是编译器 bridge，**不可覆写**（会擦除冲突）。
- **主模型动画入口**：26.2 `HumanoidModel.setupAnim` **不处理 `Pose.SITTING`**，坐姿 = 骑乘坐姿（`state.isPassenger == true` 时腿前伸弯曲）——僵尸坐船同款。
- 渲染结果由 `extractRenderState` 设置的 state 驱动，`setupAnim` 消费 state。**排查"模型不动/动画不生效"先确认这两处日志**。

### 二、本次修复的 bug 根因清单

1. **坐姿不生效 → `setOrderedToSit` 不设置坐姿标志**
   26.2 `TamableAnimal.setOrderedToSit` 反编译只做 `this.orderedToSit = sit`（**普通字段，非 SynchedEntityData**），**不调 `setInSittingPose()`**（后者才写 `DATA_FLAGS` 同步到客户端）。→ 客户端 `isOrderedToSit()/isInSittingPose()` 恒 false → `sitting=false` → 坐姿永远不触发。
   **修复**：女仆覆写 `setOrderedToSit`，同时调 `setInSittingPose(sit)`。

2. **拾取物品复制 bug → `equipItemIfPossible` 返回值语义反了**
   26.2 `Mob.pickUpItem` 用 `remaining = equipItemIfPossible(stack.copy())`，`remaining 非空 = 取走这么多`。误写成返回"没放进背包的剩余"（`SimpleContainer.addItem` 返回值）→ 方向反了：物品放进背包但地上 ItemEntity 没被消耗，每 tick 重复拾取 → 捡一把剑复制满背包。
   **修复**：返回"成功取走的量"（`stack.getCount() - addItem 剩余`）。

3. **女仆"穿任何物品" → 拾取 `addItem` 会进盔甲槽**
   `SimpleContainer.addItem` 遍历全部 41 格找空位，背包区(0-35)满时物品会落进盔甲槽(36-40) → 女仆"穿上"任何捡到的东西。
   **修复**：拾取单独实现"只放 0-35"（先同类堆叠再找空槽）。

4. **盔甲"一瞬间显示然后消失" → 盔甲槽 index 映射错乱（真凶）**
   GUI `MaidArmorSlot` 用了原版玩家背包 index（`39=HEAD/38=CHEST/37=LEGS/36=FEET`），而同步校验 `syncSlot` 用的是 `36=HEAD/37=CHEST/38=LEGS/39=FEET` → **两套 index 对不上**：放对胸甲槽（写 maidInventory 38），`syncSlot(LEGS,38)` 把 38 当腿部校验 → `isEquippableInSlot(胸甲, LEGS)=false` → 移除（先显示后消失）。
   **修复**：统一为 `36=HEAD/37=CHEST/38=LEGS/39=FEET/40=OFFHAND`，GUI 与校验一致。

5. **穿盔甲崩溃 → `HumanoidArmorLayer` 泛型/模型类型错误**
   直接传 `ModelLayers.PLAYER_ARMOR`（类型 `ArmorModelSet<ModelLayerLocation>`）给 `HumanoidArmorLayer`（需要 `ArmorModelSet<A extends HumanoidModel>`）→ 裸类型绕过编译检查，运行时 `ClassCastException: ModelLayerLocation cannot be cast to HumanoidModel`。
   **修复**：`ArmorModelSet.bake(PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new)` 先把 layer location 集合 bake 成 HumanoidModel 集合。

6. **死亡掉物后重召唤又恢复 → 存档没删**
   女仆死亡掉光物品，但 `config/smartmaid/maids/*.dat` 还在 → 重召唤 `load` 恢复死亡前背包。
   **修复**：`dropAllDeathLoot` 里调 `MaidDataManager.delete`（死亡清零）；正常进出游戏仍持久化。

7. **Java 源码被 PowerShell 写坏（BOM/乱码）**
   `Set-Content -Encoding utf8` 给 Java 源码加 BOM（`\ufeff`）+ 破坏中文 → javac 报"非法字符"，注释吞代码。
   **修复**：改 Java 源码一律用 write 工具；若文件被污染，用 Python 二进制去 BOM、重新写。

### 三、经验沉淀

- **先确认"代码真的生效了"再排查逻辑**：构建显示 `UP-TO-DATE` 不代表没编译（时间戳/缓存），用 `javap` 查编译产物或 jar 内 class 是否有新字符串/方法，用 Python 按字节搜（避开控制台编码）。
- **判断渲染问题**：`extractRenderState` / `setupAnim` 是否被调用、`state` 值对不对，先看这两处 debug 日志，再决定动哪一层。
- **26.2 语义变化**：`equipItemIfPossible` 返回"取走量"、`setOrderedToSit` 不同步客户端、`HumanoidModel` 不处理 SITTING——**别照搬 1.20 的常识，先反编译确认**。

---

## 2026-09-08：动作动画系统（Emotecraft JSON + Player Animation Library）

### 背景

女仆坐姿/动作难看的根因是**自己硬编码姿势数值**。用户要求"延用别人做好的动作"——最终选定 **Emotecraft**（动作库，含 11 个现成动作 JSON）+ **Player Animation Library**（PAL，26.2 动画引擎，`HumanoidAnimationController` 不依赖 Avatar）。

### 关键设计决策

- **路线 B**：依赖 PAL 库（jar-in-jar 打包），直接用 `HumanoidAnimationController` 播放 Emotecraft JSON 动作，不自建插值算法。
- PAL 的 `HumanoidAnimationController` 构造只需 `(AnimationStateHandler, BONE_POSITIONS, MochaEngine工厂)`，**不依赖 Avatar 实体**，女仆（TamableAnimal）可直接用。
- Emotecraft JSON 由 PAL `UniversalAnimLoader.loadAnimations(InputStream)` 解析（自动把 `rightArm` 归一化为 `right_arm`）。
- `MochaEngine.create(c)` 一行创建表达式引擎（PAL 构造器强制要，不能传 null）。

### 踩坑记录

1. **PAL 打包 → loom include 报错**：`include files(...)` 报"not a module component"。
   **修复**：用 `flatDir { dirs 'libs' }` 仓库 + `implementation/include "player_animation_library:player_animation_library:1.2.6"`（jar 改名成 `name-version.jar`）。
2. **MochaEngine 编译找不到**：它在 PAL 内嵌 jar `META-INF/jars/mochafloats-5.0.0.jar`，编译 classpath 看不到。
   **修复**：把 mochafloats 也放 libs，`implementation "mochafloats:mochafloats:5.0.0"`（编译用，运行由 PAL jar-in-jar 提供）。
3. **动作"未找到"**：`UniversalAnimLoader` 返回 Map 的 key 是动作内部名（Component 翻译键 `{translate:...}`），不是文件名。用 `ANIMATIONS.get("clap")` 查不到。
   **修复**：按文件名 `ANIMATIONS.put(name, anim)`（取 map 第一个值）。
4. **动作触发但无效果/手臂消失**：PAL 控制器需要两步驱动——**每游戏 tick `tick(data)` 推进时间 + 渲染帧 `setupAnim(data)`/`process(data)` 计算骨骼值**（写入内部 activeBones），之后 `get3DTransform(bone)` 才读得到。漏了 `process` 会导致骨骼值全空 → 手臂被覆盖成消失姿态。
   **修复**：`MaidAnimManager.tick`（实体 aiStep 客户端每 tick）+ `processOnce`（渲染帧 setupAnim）+ `apply`（get3DTransform → translatePartToBone）。
5. **动画不播放**：动画 handler 返回 `PlayState.STOP` 会让 `process` 直接进停止分支、不计算骨骼。
   **修复**：handler 返回 `PlayState.CONTINUE`。

### 架构

```
assets/smartmaid/emotes/*.json   Emotecraft 动作（11 种）
MaidAnimManager                  加载 JSON → PAL Animation；管理 HumanoidAnimationController
SmartMaidModel.setupAnim         tick 推进 → 6 骨骼（head/torso/right_arm/left_arm/right_leg/left_leg）apply
/maidanim 指令                   服务端设 DATA_DEBUG_ANIM → 客户端触发播放
libs/                           Player Animation Library + mocha（jar-in-jar）
```

### 经验沉淀

- **PAL（Player Animation Library）是"骨架绑定 Avatar + 通用控制器"双层设计**：`PlayerAnimationController` 绑定 Avatar，但 `HumanoidAnimationController` 是通用的，任何 HumanoidModel 实体都能用——这正是"女仆=玩家"的落地。
- **动画库的控制器需要显式驱动**：`tick`（推进）+ `setupAnim/process`（计算骨骼）+ `get3DTransform`（读取）三步缺一不可，不能只调其中一个。
- **jar-in-jar 打包**：第三方 mod 库用 flatDir + `include`，内嵌依赖（mocha）额外放 libs 供编译。

---

## 2026-09-09：AI 任务系统 / transfer 槽位 / 箱子操作的坑

### 问题 1：自定义 ArgumentType 导致进不了游戏（Unrecognized argument type）

- **现象**：部署自定义 `TokenArgumentType`（允许冒号的命令参数）后，进世界崩溃：
  ```
  Unrecognized argument type ...TokenArgumentType
  at ArgumentTypeInfos.byClass → ClientboundCommandsPacket → PlayerList.placeNewPlayer
  ```
- **根因**：Fabric 中自定义参数类型**必须** `ArgumentTypeRegistry.registerCustomArgumentType` 注册，否则命令树同步给客户端时序列化崩溃。
- **解决**：**彻底放弃自定义 ArgumentType**，全部改用 MC 原生参数类型（见问题 2）。教训：为一个小需求引入需注册的自定义类型，性价比低。

### 问题 2：Brigadier `word()` 不允许冒号/逗号（"参数后应有空格分隔"）

- **现象**：`/maidtasks transfer inv:5 mainhand` 报"参数后应有空格分隔，但此处已到文本结尾"，光标在参数尾。
- **根因**：`StringReader.isAllowedInUnquotedString` 只放行字母数字 `. - _`，**冒号 `:` 和逗号 `,` 都会让 `word()` 提前截断**，接着发现非空格字符 → 报"期望空格"。
- **解决**：**槽位语法改用纯数字/单词**（`0-40` / `mainhand` / `chest`…），物品用 `IdentifierArgument`，坐标用 `BlockPosArgument`——全部 MC 原生、无冒号、tab 可补全。**通用规则：命令参数别用需要 `:`/`,` 的自定义格式，要么原生类型，要么换语法。**

### 问题 3：中文输入法的全角冒号 `：` 解析失败

- **现象**：用户输入 `inv：5`（全角冒号）报"无效源槽位"。
- **根因**：全角 `：`（U+FF1A）≠ 半角 `:`，`startsWith("inv:")` 不匹配。
- **解决**：`Slots.parse` 统一把全角符号转半角（`：`→`:`、`，`→`,`、全角空格→空格）。

### 问题 4：transfer "转移 0 个" / 女仆"只是点头"

- **现象**：transfer 结果"转移 0 个，目标满剩余"，用户以为命令没执行；chestopen 只 `lookAt` 一下（点了一下头）就完成。
- **根因 1**：transfer 是"移动"语义，目标被占就失败放回，无反馈 → 用户觉得没执行。
- **根因 2**：chestopen 女仆本就站在箱子旁（<2.5 格），1 tick 瞬完成，只转了下头；`levelEvent(1005)` 只播音效不触发开箱动画。
- **解决**：
  - transfer 升级为**移动+交换+掉落**语义：目标满/被占 → 交换（仅女仆槽）；非装备塞盔甲槽 → 掉落；容器满 → 放回源+提示。
  - chestopen 智能定位（4 格内找最近容器）+ 女仆实现 `ContainerUser` 调 `ChestBlockEntity.startOpen(maid)` 触发**真实开箱动画**（openCount 机制）。

### 问题 5：存箱子"满"/"空"误报——chestopen 记录的不是箱子位置

- **现象**：箱子有位置显示"满"，有东西取显示"空"。
- **根因**：`chestopen ~ ~ ~` 的 `~ ~ ~` 是**玩家所在格**，不是箱子格 → 后续 chestput/chesttake 在该位置 `getBlockEntity` 拿不到容器 → insert 返回原栈（当"满"）、extract 返回空（当"空"）。
- **解决**：`chestopen` 在提示位置周围 4 格**智能定位最近的 Container 方块**，记录实际箱子位置；命令层再校验容器存在，找不到明确提示。

### 问题 6：`ContainerUser` 接口实现细节

- 26.2 中 `ChestBlockEntity.startOpen(ContainerUser)` 用 `ContainerUser`（空接口 + `getContainerInteractionRange()` / `hasContainerOpen(...)` / `getLivingEntity()`），**`Mob` 不实现它**，`LivingEntity` 也不实现。
- **解决**：让 `SmartMaidEntity implements ContainerUser`，实现三个方法（范围 5.0、hasContainerOpen 恒 false、getLivingEntity 返回 this）。女仆即可触发真实开箱动画。

### 问题 7：`insert` 契约——不得修改传入栈

- **坑**：`ItemSlot.insert` 若直接 `stack.shrink()` 修改传入参数，调用方用 `left.getCount() == extracted.getCount()` 判断"是否完全放入"会失效（两者是同一引用，都变了）。
- **解决**：约定 **insert 用副本操作**（`stack.copy()`），返回剩余新栈；调用方据此准确区分 完全放入 / 部分堆叠 / 完全放不下（走交换/放回/掉落）。

### 问题 8：HMCL 内存不足导致启动 OOM（非代码问题）

- **现象**：游戏启动 Datafixer 阶段 `OutOfMemoryError`，进不去。
- **根因**：HMCL 给游戏只分配了 `-Xmx112m`。
- **解决**：HMCL 版本设置里把最大内存调到 ≥4GB。
- **教训**：崩溃先看 `hmcl.log`/`minecraft.log` 尾部，区分代码问题与环境问题；`OutOfMemoryError` 常被误认为 mod 崩溃。

### 经验沉淀

- **命令参数优先级**：MC 原生类型（word 数字/单词、IdentifierArgument、BlockPosArgument、ResourceArgument）→ 永远先于自定义 ArgumentType。
- **槽位/物品的"位置"语义**：相对坐标 `~` 指执行者所在格，实体定位容器要"搜索附近"，不能假定 `~` 就是目标方块。
- **开箱/开盖动画**：由方块实体 `openCount` 机制驱动（`startOpen/stopOpen`），`levelEvent` 只播音效；非 Player 实体需实现 `ContainerUser`。

### 问题 9：HMCL token 过期导致 quickPlay 不生效（端到端自动化的死结）

- **现象**：用 `run_e2e_test.py --quick-play "新的世界 (11)"` 启动游戏后，进程 19:52:10 启动、19:52:23 走到主菜单，最后只剩 `Failed to fetch user properties / 401`，没有任何 `Setting user: arcoyx` 后的世界加载活动；4 个遥测窗口都是上次的，没有这轮新数据。
- **根因**：`hmcl.json` 里 arcoyx 账号的 `accessToken` 过期（JWT.exp = 2025-08-14，距今超 1 年），HMCL 启动游戏时不会自动刷新 token，过期的 token 让游戏拒绝进入 `--quickPlaySingleplayer` 路径（虽然不会强制退出，但 world loading 永远到不了）。`run_e2e_test.py` timeout 后 taskkill 掉了一直待在主菜单的进程。
- **尝试过的解法**：
  1. 试 Microsoft OAuth2 refresh_token endpoint 用 `00000000402b2508` / `000000004C8C68AB` / Azure 等 7+ 个 client_id → 全部 `400 invalid_grant` 或 `client does not exist`，因为 HMCL 用的 client_id 没公开文档，不知道是哪个（实际上也无法查）。
  2. 用 `python launch_game.py --dry-run` → 确认参数正确附加到 `--width 854 --height 480` 之后。
- **最终解**：必须用户在 GUI 里启动一次 HMCL，自动刷新 token；之后 `--launch <version>` 才带有效凭证，quickPlay 才会进世界。
- **教训**：
  - **死结模式**：自动化联调依赖外部系统的 CLI/数据契约时，凭证是关键薄弱点；**先在 `run_e2e_test.py` 里加 `--check-token`，过期就早退出并提示**，不要让 `latest.log` 被无效凭证塞满再失败。
  - **事件注册的时机**：`MaidWsClient` 注册在 `ServerLifecycleEvents.SERVER_STARTED`，**主菜单阶段没有 server，WS 永远不连**——token 失效时，模组根本不知道自己被挡住了，因为服务端 tick 没起来。
  - **游戏卡主菜单时 what to look for**：日志停在 `Indigo renderer`/`LWJGL 3.4.1` + `Backend library`，**没有任何 `Preparing spawn area` / `Loading level`** = 卡主菜单 = token 问题。

---

## 2026-09-10：M5 / M-P1 真机端到端联调复盘（重要）

M5 双向链路（感知下行 + 指令上行）+ M-P1 增量 diff 全部通过真机验证；本节记录这场真机联调全过程踩到的小坑与最终修复，与第九题的"假死结→真通"后续。

### 〇、本日真机联调时间线

| 阶段 | 时间 | 内容 | 结果 |
|---|---|---|---|
| 1. 代码与联调 | 上午 | P0/P1/P2 全部代码就绪（SmartMaid + 桌宠）；端到端测试脚本就位 | 单元 + 契约 + 模拟客户端全过 |
| 2. 部署 + 第一次 e2e | 下午 | HMCL 启动游戏进世界 → 14 条 AutoTest 全过（5s 内执行完） | ✅ 游戏内脚本全过 |
| 3. 读日志发现遥测未出 | — | `latest.log` 搜 `[SmartMaid-Debug] Telemetry`，没有 400 tick 后的写盘 | ❌ 第一个真机坑 |
| 4. 修首窗 100 tick 即落盘 | 17:30 | `MaidTelemetryWriter` 首窗阈值 400 → 100 tick（5s） | 部署 |
| 5. 读日志发现 WS 通但无 `damage` 高亮 | — | `unknown msg type: pong` / Monitor 刷屏淹没关键日志 | ❌ 第二组小坑 |
| 6. 加 pong case + 桌宠回 ping.t + Monitor 100 tick + verbose=false | 17:55 | 重新构建部署 | — |
| 7. 跑通 → 发现遥测真出 | 18:25 | 4 个窗口、含 skeleton/zombie 受伤，但桌宠渲染出"伤害 玩家:?"（攻击者解析错误） | 气泡污染名字 |
| 8. 修 `baseName()` + 重跑 | 18:45 | — | ✅ |
| 9. 用户提"这完全可以自动化" → 写 launch_game / run_e2e_test | 19:00 | 撞 HMCL token 过期（第九题） | ❌ 撞死结 |
| 10. 加 `--check-token` 早退 + 用户 GUI 登一次 | 19:45 | — | — |
| 11. 第三次真机 e2e（最终通过） | 20:00 | 14 AutoTest + 4 遥测窗口 + WS 全闭环 + P2 降 83.1% | ✅ 100% |

### 问题 1：首窗 400 tick 太迟——e2e 看不到事件

- **现象**：自动化测试 5 秒（≈100 tick）就结束，第一个 400 tick（20s）窗口还没到，永远看不到第一份 `deskpet/maid/*.jsonl`。
- **根因**：`MaidTelemetryWriter.flushWindow` 只在 `tick - lastFlushTick >= windowTicks(400)` 触发，没有考虑"这是游戏的第一个窗口"——首窗必须立即落才能在 e2e 看到。
- **解决**：维护 `firstWindow` boolean，首窗满 100 tick 即落（"启动期窗口"），后续按 400 tick 节奏。同时把空窗口也写入 `importance: LOW`（让 e2e 能确认"模组真的在跑"）。
- **教训**：**任何"按时间窗口聚合"的输出，都要考虑"用户第一次想看的时刻"**——开发期的瞬时验证不能强求等一个完整窗口。

### 问题 2：`unknown msg type: pong`

- **现象**：桌宠发的 WS pong（响应 ping）未在 `MaidWsClient` 识别 → "[WS] 未知消息类型: pong"，心跳流日志被噪声淹没。
- **解决**：补 `case "pong"`（忽略，仅刷新 RTT 计算）；同时让桌宠回带 ping 的 `t`（毫秒时钟），对齐两端时间基。
- **教训**：**协议实现的"忽略"也是协议的一部分**——pong 是必要消息不能当作垃圾丢弃，但要在协议层声明"我们收到了，无需动作"。

### 问题 3：MaidMonitor 每 tick 刷屏

- **现象**：`MaidMonitor` 每 tick 打 `Blah blah 地形图 …` 一长串，把同 tick 内关键的 `[SmartMaid-Debug] …` 全部冲走。AutoTest 已过但日志搜不到关键事件。
- **根因**：移动调试对开发期太重，每 tick 输出地形图片段。
- **解决**：① 间隔改 100 tick（5s）；② 新增 `verbose()` 门控——默认 `false`，需要深排查才手动开。同步修 `MaidDebug.log` 调用点全部加 `verbose()` 检查。
- **教训**：**调试日志的反噬**——开发期为了"看到更多"开了高频日志，结果真出问题时刻意看到的关键日志被冲走。必须分层（默认开 vs verbose 开）且默认低频。

### 问题 4：女仆名字渲染出气泡文案（getName 污染）

- **现象**：测试日志里 `getName()` 偶尔返回类似"女仆受到 minecraft:zombie 攻击！"（气泡文本），不是"女仆"。
- **根因**：早先实现的 `showBubble` 思路是"把气泡文案塞进 `customName`"，但同时又设置了 `customName`（持久化）→ 读 `getName()` 会拿气泡文案。
- **解决**：① 抽出 `baseName()` 返回真正的"女仆"，`getName()` 仍走原版；② `showBubble` 改用独立字段 `DataComponent` 暂存气泡文案（不进入名字），仅客户端 `extractRenderState` 渲染头顶。
- **教训**：**覆写 `getName` 之前必须确认它在你之前被谁写过**——`customName` 同时被气泡和持久化占用时，要有清晰的主名/气泡分离。

### 问题 5：`damage` 事件 `target:"?"` 攻击者类型解析遗漏（已修复 ✅）

- **现象**：真机 e2e 4 个窗口里有 8 条 damage 事件，但 `target` 字段多数写 `?`（如 `{"type":"damage","player":"女仆","target":"?","detail":"剩余生命 18.0"}`）——AI 看不懂。
- **根因**：HurtEvent 投递时序——`LivingEntity.hurt` 内部已经把 `lastHurtByPlayer` / `lastHurtByEntity` 重置回 `null`，再走 `EntitySense.captureHurt` 时拿不到攻击者。fallback 写 `?`。
- **推荐修法（B 方案 ~30 min）**：
  1. `MaidEntity` 维护最近攻击者缓存，`actuallyHurt` / `hurt` 钩子里抓一次（MC API：`LivingEntity.lastHurtByPlayer` / `lastHurtByEntity` 在受伤流程最早期仍然有效）；
  2. 类型映射表（Player→玩家名 / Projectile→射手实体类型 / LivingEntity→`minecraft:<type>`）；
  3. fallback 写 `unknown` 而非 `?`，事件重要性降为 NORMAL。
- **教训**：**事件采样的时序错位**——MC 事件链里很多字段是"瞬时快照"，frame 外的字段（如 `lastHurtByEntity`）在事件回调进入时早已被消费方清掉。要么在触发点（hurt 方法的最早处）缓存，要么用 net/minecraft/world/entity/Entity 直接反向查 `EntitiesAttackableByMob`。

### 修复记录（B 方案落地）

- **26.2 API 关键发现**：`LivingEntity.hurt(DamageSource, float)` 已改名 **`hurtServer(ServerLevel, DamageSource, float)`**（public 可覆写，原方法不存在）；`getLastHurtByMob()` 基于 `EntityReference`，攻击者被消费后解析为 null。
- **`SmartMaidEntity`**：覆写 `hurtServer`，在受伤流程最早阶段从 `DamageSource.getEntity()`（间接攻击者，优先）→ `getDirectEntity()` 抓取并缓存攻击者；新增 `getRecentAttacker()`（100 tick 新鲜度）。命名特意避开 26.2 `Attackable.getLastAttacker()` 接口（返回 `LivingEntity`）冲突。
- **`PerceptionModule`**：受伤事件改读缓存攻击者，`attackerLabel()` 类型映射表——Player→玩家名 / Projectile→射手（递归映射）/ 其他→`EntityType.toShortString()`（短名，如 `pillager`）/ 无→`unknown`。
- **`MaidTelemetryWriter`**：`target` 默认 `?` → `unknown`；攻击者未知（环境伤害/解析失败）时窗口重要性 CRITICAL → NORMAL，避免误报"被谁打了"。
- 效果：真机 `{"target":"pillager"}` 替代 `{"target":"?"}`；玩家射箭显示玩家名。

### 问题 6：HMCL `--quickPlaySingleplayer` 凭证死结的"假死结→真通"后续

- 复盘：第九题判定"token 过期无法自动化"是当时结论。后尝试路径：
  1. `--check-token` 在 `run_e2e_test.py` 加早退——已实施；
  2. 用户用 `我是启动器.exe` 在 GUI 启动一次游戏，让 HMCL 自动把新 `accessToken` 写到 `hmcl.json`；
  3. 之后再跑 `--quick-play`，游戏**正常进世界**，进 e2e 自动测试。
- 这印证了"自动化外部依赖要在边界处守门"的原则：先把守卫加了 + 提供了人工恢复路径（一次 GUI 登录），后续才能无人值守。
- **教训**：
  1. **"死结"判定要先穷尽系统自带恢复路径**——HMCL 不是"不刷新 token"，而是只通过 GUI 自带的登录路径刷新（启动器 = 官方维护的 OAuth 客户端）。CLI 调用走的是另一条路。
  2. **"凭证在文件系统里""能人工让它有效"是"半死结"**——加个 GUI 一次性恢复 + 持久化 = 后续所有轮次都跑通。

### 问题 7：`run_e2e_test.py` 子进程被 bash 回收

- **现象**：用 `cmd /c start` / `nohup &` 启游戏后立刻退出 bash 调用 → Python 进程退出时把游戏 child 也带走了；但如果主调用不退出又会撞 timeout。
- **根因**：Python `subprocess.Popen` 默认 `start_new_session=False`（POSIX）/ 共享 job object（Windows）。
- **解决**：用 `subprocess.Popen(..., creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP, close_fds=True)`（仅 Windows），游戏脱离当前 job，独立运行；脚本主进程 sleep 轮询直到游戏退出 / timeout。
- **教训**：**长生命周期的 child 必须 DETACHED**——任何"先启动再等结果"的脚本模式，child 脱离了父 job/会话才能稳定。

### 问题 8：e2e 脚本必须同一前台 Python 调用跑完

- **现象**：把 e2e 拆成"启动游戏 → 等游戏 → 读日志" 多个 bash 调用，因游戏写在第一个 bash 的子进程组里、第一个调用结束后游戏被回收。
- **解决**：整个 e2e 写入**同一个 `python tools/run_e2e_test.py …`** 调用里，child DETACHED 启动游戏后脚本继续 sleep 轮询，`present_files` 报告。
- **教训**：**长跑脚本不要试图"分阶段"**——你的 120s 看似够，但游戏加载+进世界+ AutoTest 14 条+4 窗口+WS 全闭环，realistic 是 3~5min，单次调用 5min 是合理上限（撞 timeout 再说）。

### 经验沉淀

- **真机联调五件套**：① 临时把窗口尺寸压小到 854×480（CI 友好）；② `--quick-play` 直接进世界；③ `autotest.json` 写代码层测试；④ `--check-token` 守门；⑤ `run_e2e_test.py` 一键串联。任意一环缺失都会回到"手动跑游戏贴日志"的低效循环。
- **mod 自动化测试的两条独立路径**：代码层（`MaidAutoTest`）和真机层（`run_e2e_test.py`）。前者测**逻辑**，后者测**集成**——例如 WS 心跳、感知事件、桌宠渲染三者只有在真机层才能一起验。
- **协议设计的"沉默"**：pong/heartbeat/ack 一类消息必须**显式识别但显式忽略**，否则会被当成"未知消息"刷日志。让协议 parser 输出"已识别"vs"未识别"两类结果，调试时一眼能看出协议版本/客户端兼容问题。

---

## 2026-09-11：桌宠 NLU 对接暴露的接口语义问题 + M6 三项接口

桌宠侧要做「本地意图识别 + 自动执行」（说「帮我合成一把稿子」→ 本地识别意图 → 查配方查背包 → 材料够就自动合成），
对接过程中暴露出**模组接口的语义边界**问题（不是 bug，但调用方按直觉用就一定失败），
并据此新增了三个接口。本节记录排查与设计取舍。

### 问题 1：`transfer` 的 `from`/`to` 都是必填，但调用方只会想到给 `to`

- **现象**：桌宠按「把铁锭放到主手」拼参数时只发了 `to=mainhand` → 回执 `无效源槽位: `，任务不执行。
- **根因**：`transferTask` 对 `from`/`to` 分别 `Slots.parse`，空串解析为 null → 直接判失败。
  这是**正确**的（不知道从哪拿，语义不完整），但调用方很难自己想到「必须先查物品在哪个格」。
- **解决**：调用方（桌宠 `nlu/router.py`）用**背包快照**（`perception.inventory.slots[].i`）反查物品所在格 → 拼 `inv:<n>`。
  模组侧不改。同时在桌宠侧固化成「指令契约」，把 `required` 参数标出来，缺了就地拦下、不发畸形请求。
- **教训**：**必填参数要在协议层显式声明**（谁必填、什么类型、默认值），
  否则每个调用方都会用自己的直觉去猜，最后表现为「模组报错」而不是「我参数没给全」。

### 问题 2：`chestopen` 是**异步任务**，回执只表示「已受理」——立刻 `chestput` 必失败

- **现象**：桌宠连续发 `chestopen` → `chestput`，第二条回执报「女仆还没打开箱子（或箱子不在了），先 chestopen」。
- **根因**：`chestopen` 回执 `state=running` 只说明**任务已入队**；真正把容器记为「已打开」
  （`maid.setOpenedContainer(target)`）是在 `ChestOpenTask.tick()` 里，女仆得先走到箱子旁（若干 tick）。
- **解决**：调用方发完 `chestopen` 后**轮询 `status` 直到当前任务不再是 `chestopen`**（桌宠侧 `MaidLink.wait_task_done`，超时 4s）。
  模组侧不改（异步任务本身没错）。
- **教训**：**「受理」与「完成」必须能被调用方区分**。协议里 `command_result.state` 已有
  `running/done/failed/cancelled`，但调用方往往只判断 `ok`；有前置依赖的指令组合，必须等 `done`。

### 问题 3：`equip`/`drop`/`place`/`use` 只作用于**主手**，而自然语言天天说「戴头盔」「丢铁锭」

- **现象**：需要「把头盔戴到头部」「丢掉背包里的铁锭」「（用指定方块）在那边放一个」，
  但模组这几个指令的语义是「对**主手**做动作」，且不接受目标槽位/目标物品参数。
- **决策**：**不改模组**，由调用方用已有的 `transfer` 组合补齐：
  | 自然语言 | 组合方案 |
  |---|---|
  | 把头盔戴上 | `transfer(from=inv:<头盔格>, to=head)` |
  | 丢掉背包里的铁锭 | `transfer(from=inv:<铁锭格>, to=world:<女仆坐标>)` |
  | 在那边放个方块 | 先确保主手是目标方块（必要时先 `transfer` 到 mainhand），再 `place` |
  | 丢出/穿上到具体槽位 | 同上，全部落到 `transfer` |
- **理由**：`transfer` 已经是正交能力（`MaidSlot`/`WorldSlot`/`ContainerSlot`），
  再给 `equip`/`drop` 加 slot/item 参数会造成**同一个能力两套入口**，语义更乱。
  这也符合「**能力层正交、指令层薄**」的既有设计。
- **教训**：自然语言里的「动作 + 对象」未必对应一个指令。**先看能力层能不能组合出来**，
  别急着往指令上加参数。

### 问题 4：`params.pos` 只支持绝对坐标和 `~`（女仆基准），**缺主人坐标**

- **现象**：桌宠拿到「在**我脚下**放个方块」无法换算——模组只接受绝对坐标 / `~`，
  而 `~` 是**女仆**脚下，不是主人脚下。
- **根因**：`OwnerSense` 只上报了 `dist`（距离），没有坐标。
- **解决**：`OwnerSense` 补 `PerceptionUtil.blockPos(ownerObj, player.blockPosition())` → `owner.pos`。
  调用方约定：`owner` 相对词 → 主人坐标；`maid` 相对词 → `~`；**两者都拿不到时不执行**（只提示，不瞎放）。
- **教训**：**感知是「决策输入」，缺一个字段就会让一整类指令无法执行**。
  设计感知通道时按「调用方会用它做什么判断」来定字段，而不是「能拿到什么就先报什么」。

### 问题 5：`item_index` 取名踩了两个坑

- **坑 1：`I18n` 是客户端类**。物品中文名只能走客户端语言资源；**专用服务端没有**。
  → `ItemIndexPayload.build()` 用 `try/catch(Throwable)` 兜住，拿不到就返回 `null`，
  **不下发该消息**；调用方（桌宠）自动降级为「认得出名字但执行不了」，**不会误执行**。
- **坑 2：未翻译时会返回 key 本身**（`I18n.get("item.minecraft.x")` 拿到 `item.minecraft.x`）。
  → 判定 `name.equals(descId)` 就跳过，避免把一堆 key 当物品名发出去。
- 附带：按名字排序输出（避免每次下发顺序抖动，便于比对/缓存）；上限 4096 条 + 名字长度 ≤16（防极端整合包撑爆单条 WS 消息）。
- **教训**：**跨端下发"全量索引"这类大消息，先想清楚对方的降级路径**——
  「拿不到就什么都不发 + 对方降级」比「发一半/发错的」安全得多。

### 问题 6：`craft_check` 的两处实现取舍

- **候选配方**：优先用物品自带的 `DataComponents.RECIPES`（此时**产物必然是该物品**，无需校验）；
  兜底全量扫描时，用一个「给每个 ingredient 填第一个候选项的 3×3 假网格」`matches` + `assemble` 验证产物
  （`Recipe` 在 26.2 没有 `getResultItem`，只能这样)。
- **tag 类配方**（「任意木板×3」）：`Ingredient.items()` 列出候选 id（上限 16 个）→ 背包现有量按
  **所有候选项匹配总和**统计，而不是只认第一个候选——否则「有云杉木板但没橡木木板」会被误判为缺料。
- **多产出配方**（如 1 木 → 4 木板）：返回 `out_per_craft`，`need` 按 `ceil(count / out_per_craft)` 折算，
  避免「要 4 个木板却说缺 4 个原木」。
- **教训**：**干跑查询必须和真执行走同一条配方解析路径**，否则会出现「预检说够、真做却失败」的假阳性。

### 经验沉淀

- **协议层要自描述**：参数必填/类型/默认值/取值域，缺一个都会被调用方猜错。
  桌宠侧已把这张表固化成 `nlu/mod_contract.py`（锚点本仓库提交 `d46056b`），并用 `clamp()` 自动裁剪非法参数。
- **区分「受理」与「完成」**：有前置依赖的指令组合（`chestopen`→`chestput`）必须等 `done`。
- **能组合就别加参数**：`transfer` 正交能力覆盖了「穿戴/丢出/放置到槽位」的绝大多数诉求。
- **感知按用途设计**：`owner.pos` 这种"看起来可有可无"的字段，直接决定一类指令能不能用。
- **跨端大消息要有降级路径**：拿不到就明确"不发 + 对方降级"，不要发残缺数据。

---

## 2026-09-12：感知 diff「首帧全量基于残缺快照」导致负收益（已修复）

### 问题 1：diff 负收益（-187.7%）

- **现象**：真机联调中感知增量 diff 降幅为 **-187.7%**（全量均 270B、增量均 777B），
  而 9-10 那轮是 +83.1%。桌宠侧统计口径：`全量均 270B、增量均 777B（降 -187.7%）`。
- **根因**：`PerceptionModule.tick()` 第 1 个 tick（女仆刚出生）时 `previous == null`，
  未到采样间隔的慢通道（`env`=40tick / `inventory`=20tick）**既不 collect 也不 copySection**
  → 首帧快照残缺（只有 self/nearby/owner/blocks，约 270B）。
  而 `MaidWsClient.sendPerception` 把这个残缺快照当 **diff 的首次全量基线**。
  之后各通道陆续补齐 → 增量携带大量首帧缺失字段 → 增量 > 全量。
- **修复**（`PerceptionModule.java`）：
  ```java
  // 所有通道 intervalTicks 的 LCM（self=5 / inventory=20 / owner=10 / nearby=10 / blocks=10 / env=40 → 40）
  private static final int READY_TICKS = 40;
  // tick() 末尾：
  this.current = (this.maid.tickCount >= READY_TICKS) ? data : null;
  ```
  未到完整 tick 前 `current()` 返回 null，`sendPerception` 见 null 跳过 → **首帧全量必然基于完整快照**。
- **验证**：真机全量均 3585B → 增量均 308B（**降 91.4%**）。

### 问题 2：桌宠 NLU「帮我做钻石稿」抽错物品（桌宠侧修复，涉及本模组）

- **现象**：桌宠把「钻石稿」的产出物抽成「钻石」（不可合成 → 不执行 → 女仆无反应）。
- **根因**：同起点子串消歧按分数选，`钻石`(exact 1.0) 抢了 `钻石镐`(pinyin 0.95)。
- **修复**（桌宠 `nlu/slots.py::pick_target`）：同起点优先**更长匹配**。
- **教训**：跨端物品名消歧时，「子串 + 高置信」的组合会误伤更长更完整的候选；
  语义完整度（匹配长度）应优先于分数。

---

## 2026-09-12：递归合成实现踩坑（模组侧 CraftExecutor）

### 问题 1：`placementInfo.ingredients()` 是「每格展开」列表 → need 被低估

- **现象**：`craft_check` 铁镐回执里每个材料 `need=1`（应 3 铁锭 + 2 木棍），且背包只 1 个铁锭也会判「够」。
- **根因**：`CraftingRecipe.placementInfo().ingredients()` 返回的是**每个网格位置一条**的展开列表
  （铁镐 = `[铁锭,铁锭,铁锭,木棍,木棍]`），原 `perCraftNeeds` 按展开下标 `need[idx]++` → 得到 `[1,1,1,1,1]`，
  与「每种材料各几个」语义不符；`check()`/递归的 `need` 判断与真实合成（`buildInput` 逐格匹配）不一致。
- **修复**：新增 `ingredientNeeds()` 按**候选物品集合相同**聚合（`sameIngredient` 比较 `items()` 集合），
  展开 `[I,I,I,S,S]` → 聚合 `[3,2]`；describe/ensure/resolve 三处统一用它。
- **教训**：MC 的 `PlacementInfo.ingredients()` 语义是「展开」不是「去重」，做聚合统计前先验证列表语义；
  `check()` 的 need 必须与 `craft()` 真实消耗一致，否则「预检说够、真做失败」。

### 问题 2：递归「全量扫描路径」未验证配方产物 → 误选配方

- **现象**：`craft_check` 铁锭在背包只有金合欢木板时竟返回 `craftable=true`，`craft_plan` 出现 `acacia_planks`。
- **根因**：铁锭/铁块等物品的 `DataComponents.RECIPES` 为空 → `collectCandidates` 走**全量扫描**
  （遍历所有 crafting 配方），而 `resolveItem`/`ensureMaterials` **没像 `check()` 那样用 `produces()`
  验证配方产物 = 目标物品** → 选中「材料可满足但产出不对」的配方（如需要木板的某个配方），
  假想它产出铁锭/铁块。
- **修复**：`resolveItem`/`ensureMaterials` 对每个候选配方加 `produces(maid, recipe, new ItemStack(item))`
  过滤（与 `check()` 的 `trusted=false` 分支一致）。
- **教训**：递归/干跑与真执行必须走**同一条配方筛选逻辑**；凡涉及「按产物找配方」的地方
  （RECIPES 为空时），一定要 `produces()` 验证产物，否则会「用错配方」得出荒谬结论。

### 问题 3：女仆刚生成感知未就绪 → 桌宠误报「背包里没有」

- **现象**：桌宠侧「穿金胸甲」报「背包里没有」，但 `/maidperception` 显示背包确实有。
- **根因**：`PerceptionModule` 有 `READY_TICKS=40` 约束（未到完整 tick 前 `current()` 返回 null），
  女仆刚召唤/桌宠刚重连时 WS 首帧全量未到 → 桌宠 `inventory_slots()` 拿空 → 反查失败。
- **修复**（双层）：
  1. 模组：`PerceptionModule.snapshotNow()` 强制所有通道立即采样 + `MaidWsClient` 首次注册通道时
     `pushFullSnapshot`（重置 diff、立即发 `full=true` 全量）——女仆一出现桌宠就有完整背包。
  2. 桌宠：`_inventory_ready()` 感知未就绪时提示「感知尚未就绪」而非误导性的「背包里没有」。
- **教训**：任何「按状态反查」的逻辑都要考虑**感知首帧窗口期**；WS 首帧全量的时序是
  「连接 → 握手 → 感知就绪」三阶段，外部调用方不能假定拿到时感知一定就绪。

### 问题 4：桌宠「穿金胸甲」被 AI 兜底 DSL 撤销（桌宠侧，涉及本模组）

- **现象**：NLU 执行 `transfer(from=inv:0, to=chest)` 穿上金胸甲（模组日志 `Transfer 完成`），
  但用户看到「没穿上」。
- **根因**：NLU 注入文本是程序式「已执行「转移物品」」，**AI 不知道「转移到胸甲槽 = 穿上」**，
  回复里夹带 `【equip(item=金胸甲)】` → 桌宠下发 `equip` → 女仆把金胸甲从胸甲槽**拿回主手**。
- **修复**（桌宠侧）：`_ChatWorker` 在 NLU 已执行时抑制 AI DSL（剥离不下发）；
  `_generic_summary` 用 `_describe_executed` **语义化告知**「本地程序已自动完成：穿戴到胸部 ✅」+ 明确「不要重复执行/发指令」。
- **教训**：NLU 与 AI DSL 两条链路并存时，**执行结果必须用 AI 能懂的语义告知**，
  否则大模型会按自己的理解「补一手」，撤销本地程序的执行效果。

### 问题 5：`craft_check` 对非 3x3 配方一律 `found=false`（工作台/木棍/木板全中招）

- **现象**：用户「合成一个工作台」→ `craft_check` 返回 `{"found":false,"craftable":false}`，
  桌宠报「没有「工作台」的合成配方」，AI 只得瞎编「要四块木板」——而背包明明有很多原木（能合成木板）。
- **根因**：`CraftExecutor.buildInput`/`filledInput` 把网格**硬编码 `CraftingInput.of(3,3,...)`**。
  MC 26.2 的 `ShapedRecipePattern.matches` 要求 **input 尺寸与配方尺寸严格相等**
  （`input.width()==width && input.height()==height`），所以只有 3x3 配方（铁镐/铁头盔）能 matches；
  2x2（工作台）、1x2（木板→木棍）、1x1（原木→木板）**全部 matches 失败** →
  `produces()` 全 false → 候选被过滤干净 → `found=false`。
- **连带影响**：递归合成链路（原木→木板→木棍→目标）**从来没真正生效过**——中间物配方全是非 3x3，
  它们的 `produces()` 都失败。之前「合成铁镐」能成，是因为背包里木棍/铁锭都是现成的，没触发递归。
- **修复**（`CraftExecutor`）：新增 `recipeWidth`/`recipeHeight`——`ShapedRecipe` 用
  `getWidth()/getHeight()`，`ShapelessRecipe` 位置无关按 3 兜底；`buildInput`/`filledInput`
  按真实宽高 `new ItemStack[width*height]` + `CraftingInput.of(width,height,...)`。
  `slotsToIngredientIndex()` 本就是 row-major 的 `width*height` 布局（空位 -1），
  直接按序填 `grid[i]` 即可，无需位置映射。
- **验证方式**：反编译 `minecraft-merged.jar` 里 `ShapedRecipePattern.matches` 字节码确认
  「尺寸严格相等」的判定；`javap -p -c -classpath <merged-jar> net.minecraft.world.item.crafting.ShapedRecipePattern`。
- **验证结果（2026-09-12）**：真机通过——「合成工作台」走全递归链（原木→木板→工作台），
  `craft_check` 返回 `found:true + craftable:true`（含 `via_craft` 计划），女仆实际做出工作台 ✅。
- **教训**：**用 `CraftingInput.of(width,height,...)` 前先搞清楚配方的真实宽高**——
  `PlacementInfo` 本身不带宽高（只有 `slotsToIngredientIndex` 的 row-major 列表），
  宽高要从 `ShapedRecipe.getWidth/getHeight` 拿，不能想当然按 3x3 硬编码。

---

## 2026-09-15：寻路降级（挖方块 + 搭路）开发踩坑复盘（重要）

给原版寻路加"物理直线路径"兜底（失败/绕远 → 直线 + 挖/搭 + 动态切回），
过程中连续踩了 8 个坑，**多轮真机验证才通过**。本节按"根因 → 修复"记录，避免重蹈覆辙。

### 问题 1：降级激活了但女仆永远不动（最严重）—— tick() 覆写丢失

- **现象**：日志里 `StraightNav 降级 -> ...` 正常触发（createPath 里的 setTarget 生效），
  但之后**没有任何**降级日志（挖/搭/移动/到达全无），女仆站住不动。
- **排查**：逐层怀疑（navigation.tick 没调？isActive false？），最后通读文件发现——
  **`MaidGroundPathNavigation` 里根本没有 `tick()` 覆写**！某次用 write 重写整个文件（加 reachesTarget 那轮）时，
  把之前写好的 `tick()` 覆写弄丢了。原版 `PathNavigation.tick()` 被调用（path=null 啥也不做）→ 直线模式从不驱动。
- **修复**：加回 `tick()` 覆写（active 时调 straightNav.tick）。
- **教训**：**用 write 整体重写文件时，必须对照旧文件核对所有覆写方法是否保留**。这种"整文件重写丢方法"的 bug
  日志完全看不出来（降级触发正常，只是驱动层没了），极易浪费大量排查时间。

### 问题 2：卡在 1 格台阶/沟里出不来 —— MoveControl 跳跃判定条件苛刻

- **现象**：女仆在坑里/台阶前判定"前方 1 格台阶"（应跳），但一直不动。
- **根因**：`MaidMoveControl.needJump` 的条件是 `dx²+dz² < max(1.0F, bbWidth)`（水平距离²<1.0）。
  女仆正对台阶、到台阶格中心水平距离恰为 1.0 → `1.0 < 1.0` 永远 false → **永不触发跳跃**。
- **修复**：直线模式下**不再依赖 MoveControl 的跳跃判定**，1 格台阶直接 `jumpControl.jump()`。
- **教训**：原版/现有跳跃触发条件是为"移动中"设计的，站在障碍正前方时距离恰好卡在边界不触发。
  需要精确翻越时，直接调 jumpControl 更可靠。

### 问题 3：一降级就"到达"结束 —— 到达判定只算水平距离

- **现象**：降级刚激活就结束（日志"到达"），女仆不动。目标在 y=-58、女仆在 y=-60（高 2 格），
  水平距离 1 格 → 原 `isWithinReach`（只看水平）判"已到达"。
- **修复**：到达判定改 3D——水平 2 格 + 垂直 1.5 格 + 视线不被墙隔开。
- **教训**：跟随/移动的"到达"必须考虑垂直差；目标在高低处时只比水平距离会误判。

### 问题 4：降级触发不了（到墙边停）—— 原版 A* 目标不可达返回"部分路径"而非 null

- **现象**：墙完全封死，女仆走到墙边停，日志**没有** `StraightNav 降级`。
- **根因**：原版 A* 在目标不可达时**不一定返回 null**——`createPath(Entity)` 会返回一条
  "到目标附近最近可达点"的部分路径（非 null，日志里 path=2节点）。降级条件只看 `path==null` 永远不触发。
- **修复**：降级条件改为 `path==null || !reachesTarget(maid, path, target) || 节点数>阈值`。
  `reachesTarget`：终点距目标水平 >2 格 → 没到；**高度差 >1.5（坡/爬升）→ 视为可达**（避免误伤坡度）；
  同高且近才做视线检测（隔墙 → 没到）。
- **教训**：**不要假设 A* 失败就返回 null**。MC 的 PathFinder 对"实体目标"会尝试最近可达点。
  判断"是否真到目标"要比对路径终点与目标位置 + 视线。

### 问题 5：搭方块无限重试刷屏卡死 —— findClickFace 找不到支撑面

- **现象**：日志 `Placer 无支撑面 (x,y,z)` 每 tick 刷屏，女仆卡死。
- **根因**：判定"前方脚下无支撑要搭"，但目标格四周全悬空（深谷/虚空上空），
  `findClickFace`（先点下方 UP、再点水平邻格）找不到任何可点击支撑面 → begin 成功但 tick 永远失败，
  下 tick 又判定又搭 → 无限循环。
- **修复**：`begin` 预检 `findClickFace`，无支撑面直接拒绝搭（并让降级放弃）；`tick` 无支撑面时 abort。
- **教训**：状态机开始前先预检"这个动作能不能成"，避免"开始→永远失败→重试"的无限循环。

### 问题 6：乱搭 / 滥用挖墙 —— reachesTarget 视线检测误伤坡面

- **现象**：女仆在正常坡度地形上动不动就降级挖/搭（用户："滥用"）。
- **根因**：`reachesTarget` 的 clip 视线检测从路径终点到目标，经过坡面/山体时被挡 → 误判"没到目标" → 降级。
- **修复**：**高度差 >1.5（坡/爬升）一律视为可达**（原版路径合理，女仆能自己走上去），
  只有同高且隔墙才判没到。阈值也从 1.5 倍放宽到 3 倍。
- **教训**：视线检测（clip）对"地形起伏"极敏感，用在寻路判定上会误伤坡/山；
  判定"能否到达"应优先看高度差语义，视线只用于"同高度被墙隔开"这类明确阻断。

### 问题 7：坑里水平挖不上升 —— 需要向上时挖错了方块

- **现象**：女仆在坑里，目标在地面（高 4 格），女仆判定"前方 2 格墙"→ 挖**脚部**方块 → 水平挖穿坑壁，
  一直停留在坑底高度，不上升。
- **根因**：2 格墙处理统一挖 ahead（脚部），是"水平穿墙"语义；坑里需要的是"向上爬升"。
- **修复**：目标明显高于女仆（`needUp = target.y - from.y > 1`）时，2 格墙改**挖上方**（ahead.above()），
  让头顶净空 → 触发 1 格台阶跳 → 逐格升高爬出坑；同高/向下才挖脚部水平穿墙。
- **教训**：同一个"挖"动作，水平穿墙和垂直爬升的目标方块完全不同；要按"目标在哪个方向"选挖哪格。

### 问题 8：设计反复 —— 从"直线飞向最终目标"到"逐格推进"再到"直线走+遇障处理"

- 第 1 版：MoveControl 直线飞向**最终目标**（wantedY=目标高度）→ 高度差导致乱跳/乱搭。
- 第 2 版：逐格推进（每 tick 决策下一格）→ 与 MoveControl 跳跃判定耦合，卡台阶。
- 最终版：**直线朝目标（wantedY=当前高度，水平走）+ 每 tick 检测前方 1 格**，
  只有走不过去才打断处理（挖/跳/搭）；配合**动态切换**（每 40 tick 重评估原版，代价回落切回）。
- **教训**：实体移动类功能，先想清楚"移动目标"的语义（最终目标 vs 下一格 vs 方向）；
  直线物理移动用"朝目标水平走 + 障碍触发处理"最贴合直觉，逐格决策容易和现有移动体系打架。
- **动态切换**：降级不是"一条道走到黑"，`createPath` 每次重新评估原版代价，
  代价回落到阈值内（接近目标/绕路变短）自动 `clear` 切回原版——用户要求的"阈值又小于就切回"。

---

## 2026-09-16：挖掘工具切换 / 挥动动画 / 挖掘速度 / 部署验证（重要，含检讨）

给寻路降级/挖矿加"自动换最优工具 + 玩家一致挖掘"时连踩多个坑，
**最严重的是"改了代码但没打包部署，用户一直测旧 jar"，导致对着不存在的 bug 反编译几个小时**。

### 问题 1（最严重）：改了代码只 compileJava 没 build，用户测试的一直是旧 jar

- **现象**：连续两轮用户反馈"没有挖掘动画""手里没显示切换工具"，怎么改都没效果。
- **排查**：反复反编译 26.2 服务端→客户端装备同步链路——`setItemSlot` → `detectEquipmentUpdates`（aiStep 每 tick）
  → `ClientboundSetEquipmentPacket` → 客户端 `handleSetEquipment` → `getItemHeldByArm` →
  `extractArmedEntityRenderState` → `ItemInHandLayer`——**全部正常**，始终找不到 bug。
- **根因**：每次只跑 `compileJava`（编译进 `build/classes`），**没跑 `build` 打包 jar，也没部署到 mods**。
  用户 mods 里的 `smartmaid-0.1.0.jar` 还是昨天的，今天所有改动都没进去。日志里压根没有新增的
  `equipBestTool` / `Render 客户端主手` 诊断行。
- **解决**：`gradlew build` 打包 → 备份旧 jar（`.bak_YYYYMMDD`）→ copy 新 jar 到 mods → 立即生效。
- **教训**：
  1. **排查"改了没效果"第一步永远是确认部署的 jar 是否含新代码**——对比 `build/libs` 与 mods 目录 jar 的时间戳，
     或用 python 搜 jar 内 class 里的新字符串（如 `equipBestTool`）。
  2. 本文件 2026-09-07 已写"先确认代码真的生效了再排查逻辑"，这次又犯——对着服务端同步链反复验证，
     而用户那边根本没运行到新代码。**开发流程必须闭环：改码 → compileJava → `build` 打包 → 部署 → 真机测**。
  3. 新增可观测日志（`equipBestTool` / `Render 客户端主手`）是为了定位"哪一环断了"，但前提是用户跑的是含这些日志的 jar。

### 问题 2：女仆挖掘/攻击没有挥动动画（26.2 `updateSwingTime` 只在 Monster/Player 调用）

- **现象**：服务端每 tick 调 `swing()` 广播 `ClientboundAnimatePacket`，客户端却手臂完全不动。
- **排查**：反编译确认 swing 广播逻辑正常（客户端会 `setSwinging(true)`）；全 jar 搜 `updateSwingTime` 引用，
  只有 `Monster`/`RemotePlayer`/`Player`/`Mannequin`/`LivingEntity`（仅定义）——**`Mob`/`Animal`/`TamableAnimal` 都不调用**。
- **根因**：26.2 中推进挥动动画的是 `LivingEntity.updateSwingTime()`（维护 `swinging/swingTime/attackAnim`，
  渲染层 `HumanoidModel.setupAnim` 靠 `state.attackTime` 摆动手臂），它**只在 `Monster`/`Player` 子类的 `aiStep` 开头被调用**。
  女仆继承 `TamableAnimal → Animal → Mob`（非 Monster/Player）→ `attackAnim` 恒 0 → 挥动动画永远不显示。
  "服务端广播了动画包" ≠ "客户端动画被推进"。
- **解决**：`SmartMaidEntity` 客户端 `aiStep` 手动复制原版 `updateSwingTime` 逻辑推进
  `swingTime/attackAnim`（`oAttackAnim` 由 `baseTick` 自动更新，插值正常）。`getCurrentSwingDuration()` 是
  private，用反射取（含物品挥动时长/挖掘加速效果）。
- **教训**：**动画/渲染类问题先怀疑"动画时间谁在推进"**。原版很多动画推进钩子挂在特定子类里，
  不能假设"包广播了就一定显示"；"数据传输通" ≠ "渲染消费通"，两层要分开查。

### 问题 3：挖掘速度比玩家快 5 倍（公式少系数）

- **现象**：石镐挖石头 8 tick（0.4s），用户反馈"太快了"。
- **根因**：`computeMiningTicks` 用 `hardness × 20 / speed`，而原版玩家挖掘 tick =
  `hardness × 100 / digSpeed`（非创造；1 硬度徒手 = 5 秒 = 100 tick）。少了 5 倍系数。
- **解决**：改为 `hardness × 100 / speed`。石镐挖石头 → 38 tick（1.9s，玩家一致）；铁镐挖钻石块 → 83 tick（4.2s）。
- **教训**：**"模拟玩家挖掘"必须照抄原版 `Block.getDestroyProgress` 公式**（`hardness × 100 / digSpeed`），
  不能凭"20 tps"想当然写 20。玩家直觉是"1 硬度 ≈ 5 秒徒手"，别记成 1 秒。

### 问题 4：钻石块其实铁镐就能挖（26.2 tag 变更）

- **现象**：判断"钻石块需要钻石镐才掉落"，用户纠正"铁镐就可以"。
- **根因**：1.20 时代 `diamond_block` 在 `needs_diamond_tool`；**26.2 已改为在 `needs_iron_tool`**
  （`needs_diamond_tool` 只剩黑曜石/哭泣黑曜石/下界合金块）。
- **验证**：读 merged jar 的 `data/minecraft/tags/block/needs_*.json`（`needs_iron_tool` 含 diamond_block）。
- **教训**：**方块挖掘等级/tag 的常识会随版本变**，判断"什么工具能挖什么方块"直接读当前版本 tag 数据，
  别用旧版本记忆。

### 问题 5：26.2 无 Tier 等级，最优工具用"对目标方块挖掘速度"判据

- 26.2 移除 `Tier`/`DiggerItem`，工具改为 `DataComponents.TOOL`（`Tool` 组件，两条 rule：
  `deniesDrops(incorrectBlocksForDrops)` 拒绝 + `minesAndDrops(mineable tag, speed)` 放行）。
- `ItemStack.isCorrectToolForDrops(state)` 语义已验证：石头+石镐 true / 铁矿石+石镐 false / 泥土+铲 true。
- `equipBestToolFor`：遍历背包选 `isCorrectToolForDrops` 且 `getDestroySpeed(state)` 最高的工具换到主手
  （速度高即材料等级高：钻石 8 > 铁 6 > 石 4 > 木 2，金/铜更快）；无正确工具保持徒手（与玩家一致：不掉落）。
- 每挖一个方块在 `MaidBlockBreaker.begin` 判定一次（MineTask 与寻路降级挖墙共用），挖完石头换泥土会自动换铲。
- **教训**：旧版"挖掘等级"概念在 26.2 没有对应 API，用"对目标方块的挖掘速度"做排序最贴合直觉且无需查表。

### 经验沉淀

- **排查"改了没效果" = 先验部署**（jar 时间戳 / jar 内字符串），再谈逻辑。
- **动画问题分两层查**：数据是否到客户端（包/同步）与动画时间是否推进（updateSwingTime 类钩子）。
- **玩家一致的速度/判定**：公式照抄原版（`hardness × 100 / digSpeed`），工具/掉落判定照读当前版本 tag。
- **新增诊断日志的价值前提**：用户跑的是含日志的 jar。

---

## 2026-09-17：女仆战斗系统开发踩坑复盘（重要）

规则驱动的自主战斗（近战/走位/远程/盾/进食，C0–C5，见 `DEVELOPMENT_COMBAT.md`）。
本轮最大的教训：**26.2 有一批 API 是 Player 专属或不对非玩家生效，写之前必须反编译确认**，
否则会写出「编译通过但女仆根本不执行」的代码。

### 问题 1：`LivingEntity.isHurt()` 不存在（只在 `Player` 上）

- **现象**：`MaidFoodData` 用 `maid.isHurt()` 判回血 → 编译报「找不到符号」。
- **根因**：26.2 的 `isHurt()` 只定义在 `Player`（语义是「血量 >0 且 < 上限」），`LivingEntity` 没有。
- **解决**：女仆回血判定用等价条件 `getHealth() > 0 && getHealth() < getMaxHealth()`。
- **教训**：原版把一些「玩家语义」方法挂在 `Player` 而非 `LivingEntity`，别想当然在基类上调用。

### 问题 2：`BowItem.releaseUsing` / `FishingRodItem.use` 是 Player 专属

- **现象**：想「仿玩家」直接用原版发射/用鱼竿 → 编译能过但运行无效。
- **根因**：反编译 `BowItem.releaseUsing` 首行 `if (!(entity instanceof Player)) return false`；
  `FishingRodItem.use(Level, Player, InteractionHand)` 形参就是 `Player`。
- **解决**：远程**复制**玩家的蓄力/发射流程自实现（`MaidRangedSkill`）；
  箭弹药走覆写 `LivingEntity.getProjectile`（原版默认返回空，只有 `Player` 覆写）。
- **教训**：`Item` 上以 `Player` 为形参、或方法体内 `instanceof Player` 的逻辑，女仆一律用不了；
  先 `javap` 看门槛，再决定复制还是绕过。

### 问题 3：`FishingHook` 对非玩家 owner 首 tick 就 `discard()`（鱼竿不可用）

- **现象**：spike 想手动 `new FishingHook(...)` + `setOwner(maid)` 抛竿。
- **根因**：`FishingHook.tick()` 开头 `Player player = getPlayerOwner(); if (player == null) { discard(); return; }`
  —— 非玩家 owner 的鱼钩第一 tick 就没了；`shouldStopFishing`/`retrieve`/`pullEntity` 也都依赖 `Player`。
- **解决**：**原版鱼竿对女仆不可用**，暂不做（用户拍板）。要真做只能自研 `MaidFishingHook` 覆写 tick。
- **教训**：spike 优先——先花 20 分钟反编译确认可行性，别直接动手写大段代码。

### 问题 4：`MoveControl.strafe` 的 `speedModifier` 固定 0.25（走位过慢）

- **现象**：用原版 `strafe` 走位，速度只有走路的 1/4。
- **根因**：`MoveControl.strafe()` 内部把 `speedModifier = 0.25`（原版给远程 kite 用的谨慎速度）。
- **解决**：`MaidMoveControl.combatStrafe(fwd, right, speed)` → 调 super 后再覆盖 `speedModifier`。
- **教训**：原版 `strafe` 不是为近战走位设计的，速度/语义都要按需覆盖。

### 问题 5：后退很慢 —— STRAFE 的 `zza` 取**原始** `forward` 值

- **现象**：真机反馈「女仆后退得很慢」。
- **根因**：STRAFE 分支 `setSpeed(speedModifier × MOVEMENT_SPEED)`，但 `setZza(strafeForwards)` 用的是
  **原始 forward**；而 `Entity.getInputVector` 对长度 <1 的向量**不归一化**，会再按 speed 缩放 →
  我按距离误差给的 `forward = error*0.5`（常是小负数）→ 实际后退只有满速的 ~1/4。
- **解决**：后退用满幅 `forward = -1`（前进仍缓动）。
- **教训**：**strafe 的 `forward` 是方向幅度**（不是纯比例），后退必须给满幅。

### 问题 6：MOVE_TO 会 `setYRot` 转向移动方向 → 后退会转身

- **现象**：用 `setWantedPosition` 后退时，女仆转身背对敌人。
- **根因**：`MoveControl.tick()` 的 MOVE_TO 分支 `setYRot(atan2(dz,dx)-90)`；STRAFE 分支**不调 `setYRot`**。
- **解决**：走位/后退/kite 一律用 `strafe`（+ `LookControl` 朝敌）；只有「逼近」用 `navigation.moveTo`（朝敌无冲突）。
- **教训**：要「面向 A 向 B 移动」，MC 里只能用 STRAFE（或用别的办法解耦朝向与移动）。

### 问题 7：走位一直绕圈

- **现象**：真机反馈「女仆一直绕圈」。
- **根因**：走位始终 `side=±1` + `forward` 在 0 附近 → 持续侧向移动 = 圆周运动。
- **解决**：默认 `side=0`，只在「后退被挡且背对跳也上不去」时才侧移绕行；加距离死区（±0.3 格不动）。
- **教训**：**「走位」≠「持续侧移」**；保持距离应是前后调整，侧移只在受阻时用于绕行。

### 问题 8：目标死亡后女仆一直拉满弓对着主人

- **现象**：敌人死后女仆不松弦，对着（被跟随 Goal 转向的）主人拉满弓。
- **根因**：战斗 Goal 在目标死亡时确实 `stop()` 了，但 `stop()` **没有释放 use 状态**；
  `LivingEntity` 每 tick 继续推进弓的蓄力，而战斗 Goal 已结束没人松弦。
- **解决**：`MaidCombatGoal.stop()` 里 `if (isUsingItem()) stopUsingItem()`；
  另在 tick 里检测「拉弓中但已无箭 / 弓被换走」主动松弦。
- **教训**：**任何「开始使用物品」的行为，在行为结束时都必须释放**（拉弓/进食/举盾同理）。

### 问题 9：墙角进食死锁 —— 不吃也不打

- **现象**：真机反馈「被逼到墙角时不攻击也不远离，卡在里面」。
- **根因**：血量 <50% → 进入 EAT 模式，规则要求**先退到 7 格外再吃**；墙角退不出去 →
  永远不满足「到安全距离」→ **既不吃也不攻击**，原地死锁。
- **解决**：加 40 tick 超时——退不出去就**原地进食**（`Combat eat 无法拉开距离（墙角？），改为原地进食`）。
- **教训**：**带「前置条件」的行为必须有超时/降级**，否则条件在受限环境里永不满足 → 死锁。

### 问题 10：渲染 `getArmPose` 一律 `ITEM` 顶掉拉弓/举盾姿势

- **现象**：拉弓/举盾时手臂姿势不对（仍是普通持物）。
- **根因**：`SmartMaidRenderer.getArmPose` 原实现「只要手持就返回 `ITEM`」。
- **解决**：按 `isUsingItem()` + `getUseAction()` 映射 `BOW_AND_ARROW`/`BLOCK`/`CROSSBOW_CHARGE`…
- **教训**：覆写原版渲染钩子时，别用「兜底返回」把 vanilla 的姿势分支写死。

### 问题 11：箭的 `pickup` + 女仆拾取 = 无限箭循环

- **现象**：担心女仆射出的箭被她自己 `setCanPickUpLoot(true)` + `wantsToPickUp` 立刻捡回。
- **解决**：`MaidArrow` 发射时设 `pickup = DISALLOWED`。
- **教训**：给「会自己捡东西」的实体射箭，务必禁用箭的拾取。

### 问题 12：`MaidArrow` 不需要注册新实体

- **要点**：`Arrow(Level, LivingEntity, ItemStack, ItemStack)` 内部写死 `EntityTypes.ARROW` →
  子类 `MaidArrow` 的类型仍是原版 `arrow`：客户端走原版 `ArrowRenderer`，**零注册**；
  `canHitEntity` 覆写只在服务端命中判定生效（硬免伤玩家）。
- **教训**：想给原版投射物加一点行为，优先「用原版类型的子类」而不是新注册实体 + 渲染器。

### 问题 13：战斗判定分散 → 收拢与一致

- **要点**：敌对判定原先散在 `AttackTask` / `GuardTask`，新增战斗又要一套 → 统一到 `MaidTargetFilter`
  （Monster + 排除表 + 友军仅玩家 + 女仆可见），`PerceptionBlockUtil.findNearestHostile` 改调它。
- **教训**：多入口触发同一能力（AI 指令 / 自主战斗）时，**判定必须单一实现**，否则行为不一致。

### 问题 14：桌宠指令契约必须同步（否则测试红 / NLU 不认识）

- **要点**：模组新增 `eat` 指令后，桌宠 `nlu/mod_contract.py`、`taxonomy.py`、`synth.py`、`hard_cases.py`、
  `tests/test_nlu.py`、`game/maid_link.py` 都要同步，并**重训 NLU**（意图集变了，旧模型维度不匹配）。
- **验证**：`python tests/test_nlu.py` 全过（契约 28 条、integrated=14）；重训 val_acc 0.9921、人工集 89.9%、闲聊误激活 0。
- **教训**：`mod_contract` 是「模组指令层的单一事实来源」，模组改指令 → 桌宠同步 + 重训是**流程的一部分**。

### 经验沉淀（战斗系统）

- **26.2 的 Player 专属 API 清单**（女仆用不了，改用复制/覆写）：`BowItem.releaseUsing`、
  `FishingRodItem.use`、`FishingHook.getPlayerOwner` 链路、`LivingEntity.getProjectile`（只有 Player 覆写）、
  `FoodData.tick(ServerPlayer)`。
- **「面向 A 向 B 移动」= `MoveControl.strafe`**（不转向）；`forward` 幅度要满幅才不减速。
- **所有「使用物品」行为（弓/盾/吃）都要在结束时释放** use 状态。
- **带前置条件的行为必须能超时降级**（墙角进食死锁）。
- **spike 优先**：反编译 20 分钟，省掉几小时「编译过但跑不通」。

---

## 2026-09-17（二次）：坐姿修正 —— 浮空 + "坐着走"

用户反馈：右键坐下后女仆**浮在空中**（不是坐在支撑面上），而且**坐着还能走**。两个问题都不在战斗系统里，
根因一个是"渲染没有下沉"，一个是"没有任何一处拦住坐下时的移动"。

### 问题 1：坐姿浮空 —— 只有骑乘姿势，没有整体下沉

- **现象**：女仆坐下的姿势是"玩家坐船"那种（腿向前伸），但**整个人停在站立高度**，
  屁股离地约 0.75 格，看起来像浮在半空。
- **根因**：`SmartMaidRenderer` 把坐下映射成 `state.isPassenger = true` → `HumanoidModel.setupAnim`
  走骑乘分支（腿前伸）。但**骑乘姿势的"坐姿观感"是靠载具（船/矿车）本身的几何撑起来的**：
  实体坐标就是载具位置，原版不给乘客做任何 Y 偏移。女仆并没有真的骑在什么东西上，
  实体坐标 = 脚下地面 → 腿/髋停在髋高（0.75 格）处 → 浮空。
- **模型几何（反编译确认，用于定量）**：`HumanoidModel` 的 body/腿 pivot 在 **y=12px**；
  26.2 渲染链在 `scale(-1,-1,1)` 翻转后有一个 `translate(0, -1.501, 0)`，
  净映射为 `world_y = 1.501 - model_y`：脚底（model y=24px=1.5）→ 0，髋（12px=0.75）→ **0.75 格**。
  → **下沉量 = 髋高 = 0.75 格**，与支撑面高度无关（脚底永远在被站立的表面上）。
- **修复**：
  1. `SmartMaidRenderer#setupRotations` 覆写，坐下时 `poseStack.translate(0, -0.75, 0)`。
     ⚠️ **位置选择是关键**：`LivingEntityRenderer.submit` 的顺序是
     `scale(state.scale)` → **`setupRotations`** → `scale(-1,-1,1)` → `scale(state)` → `translate(0,-1.501,0)` → `submitModel`。
     只有 `setupRotations` 里 **Y 轴还是世界的"上"**（翻转之前），平移方向不会撞上"模型 y 向下"的约定；
     写在 `scale()`/模型里都要反推符号。
  2. `SmartMaidModel.setupAnim`：坐下时把双腿 `xRot` 摆平到 **-π/2**（原版骑乘姿势带约 9° 下倾，
     不摆平的话脚会往支撑面里插 0.12 格）。
  3. 名字标签（`EntityRenderState.nameTagAttachment`）同步下沉同样的量，否则坐姿时头顶与名字空出一大截。
  4. 顺手修 `state.isPassenger = state.sitting` 的旧写法——它会**覆盖真实骑乘状态**；
     改成只在坐下时置 true（女仆真的坐船时保持原版骑乘）。
- **支撑面坐标**：`SmartMaidEntity#snapToSupportForSit()` 从脚下向下最多 3 格找
  `getCollisionShape().max(Y) + pos.y` 的最近顶面（且不高于当前脚下），坐下瞬间把 y 吸附过去——
  地面 / **半砖顶 0.5** / 台阶顶 1.0 / 地毯都成立；脚下 3 格内无支撑面就不动，交给重力。

### 问题 2：「坐着走」

- **现象**：坐下后女仆还能走动。
- **根因**：**没有任何一处统一拦住坐下时的移动**。逐条查出的漏点：
  - `AiAwareRandomStrollGoal.canUse()` 只查 `isAiBusy()`，不查坐姿 → 坐下后照样触发随机散步
    （且没覆写 `canContinueToUse`，坐下瞬间正在散步也不会停）；
  - `MaidGroundPathNavigation.tick()` 的直线降级分支（`straightNav`）不查坐姿 → 降级期间坐下继续被驱动；
  - `MaidActionExecutor.tick()`（跳跃执行器）不查坐姿；
  - `applyTeleportFallback()`：主人走远 >32 格会把坐着的女仆**传送**过去；
  - 残余路径 / `MoveControl` 操作 / 击退 / 水流推挤造成的滑动。
- **修复（服务端逐层拦住）**：
  - `setOrderedToSit` 收敛为**唯一入口**：姿态 + 落地吸附 + 清目标 + `applySitLock()`；
    `mobInteract` / `/maidtasks sit` / `/maidai sit` / GUI 网络包里的重复逻辑全部删掉
    （原来 GUI 那条入口连 `setPose` 都没有 → 现在统一）。
  - `applySitLock()`（每 tick，坐下时）：`navigation.stop()` + `moveControl.setWait()` +
    `setXxa/setZza(0)` + 清水平速度（**保留 y**，让重力正常压在地面/半砖上）。
    只清水平分量是刻意的：击退、水流、残余路径都是水平位移。
  - `AiAwareRandomStrollGoal`：`canUse` + `canContinueToUse` 都加坐姿判断。
  - `MaidGroundPathNavigation`：`tick()` 坐下时清直线降级并 `stop()`，两个 `createPath()` 坐下直接返回 `null`
    （不产生任何新路径，也顺带堵住降级激活）。
  - `MaidStraightNav.tick()` / `MaidActionExecutor.tick()`：坐下直接结束/空转。
  - `applyTeleportFallback()`：坐下直接返回（"坐下 = 原地待命/看家"，主子走远也留在原地）。
- **教训**：
  1. **"坐下不动"必须逐层拦**：Goal 层（散步/跟随/战斗）、导航层（原版 + 直线降级）、执行器层（跳跃）、
     速度层（残余速度/击退/水流）、兜底层（传送）——只堵一处就会从别处漏出来。
  2. **渲染用的位姿平移，先看清 `submit` 里的调用顺序**再决定挂哪个钩子；错一个阶段符号就反了。
  3. **模型几何常量（髋高 12px = 0.75 格）可以从反编译里直接读出来**，比"调参试出来"可靠。

---

## 2026-09-19：盾牌挡箭无效排查（"总是中箭"）+ 背包模型预览 + 近战死区

用户反馈"女仆打骷髅总是中箭"，经诊断日志定位到原版盾牌**架盾前摇**问题；同时收尾做了背包缩小模型预览与近战死区修正。

### 问题 1：盾牌"总是挡不住箭"——原版 `blockDelayTicks` 5 tick 前摇（根因）

- **现象**：女仆打骷髅频繁中箭，盾在副手却从不生效。
- **诊断手段**：加三类日志——`CombatShield`（每 10t 打印箭矢实体：位置/速度/距女仆/射手/预测命中 tick + 女仆朝向/举盾状态）、`Combat block`（举盾/收盾的方向 yaw/headRot 与时间 tick/holdTicks）、`Combat hit-by-arrow`（中箭时箭矢实体/射手/方向夹角 `facingErr`/`blockingBefore`/`usingTicks`）。
- **日志结论**：5 次中箭全部 `blockingBefore=false`、`isBlocking=false`、`usingTicks=0~2`；方向无问题（`facingErr` 均在 -30°~+40°）。即**女仆确实举盾了，但原版判定"举盾中"还没成立**。
- **根因（反编译确认）**：`LivingEntity.getItemBlockingWith()` 要求
  `useDuration - useItemRemaining >= BlocksAttacks.blockDelayTicks()`（盾 = 0.25s = 5 tick）才返回盾 →
  `isBlocking()` 为 true。这是给"玩家按住右键常驻举盾"设计的平衡（防瞬发格挡）；而骷髅贴脸射箭
  （女仆 3 格近战，箭 1~2 tick 就命中），威胁检测到→举盾→5 tick 延迟还没过，箭已中身。
- **修复**：覆写 `SmartMaidEntity.getItemBlockingWith()`——只要 `isUsingItem()` 且手上物品含
  `BLOCKS_ATTACKS` 即认作举盾，**去掉 5 tick 前摇当场生效**（`isBlocking()` 与 `applyItemBlocking`
  共用此方法，一处覆写全链路生效）。角度判定（`resolveBlockedDamage` 的 ~100° 扇形）保留，只挡正面。
  **用户拍板：女仆是 PVE 高手、反应极快，不受原版玩家平衡性前摇限制**。
- **验证**：修复后 14 次中箭全部 `blockingBefore=true`、`isBlocking=true`；盾挡箭 = **100% 完全挡下**
  （原版盾挡箭矢非穿透直接弹开不掉血，不是按比例减免——**设计常识纠正**）。日志 `dmg` 是格挡前的原始值，
  不能凭它判断"漏箭"；要看实际扣血需打 `amount - applyItemBlocking(...)`。
- **教训**：
  1. **玩家式机制的"平衡前摇"对 AI 是硬门槛**：玩家靠"常驻按住"绕过前摇，AI"检测到再起手"永远来不及——
     检测驱动的反应型动作，要检查原版是否有 `blockDelayTicks` 这类生效延迟，有就该覆写掉。
  2. **诊断日志要打"判定前状态"**（`blockingBefore`）+ **带时间/方向**（tick、facingErr），
     才能区分"没举盾 / 举了但原版不认 / 方向不对"三种情况。
  3. **`hurtServer` 入口的 `amount` 是格挡前原始伤害**：不要拿它判断"是否被挡下"，挡下后实际扣血是 0。

### 问题 2：近战死区上限 3.3 导致"够不着打不到"

- **现象**：女仆在 3.2~3.3 格时停在死区不动，实际攻击距离只有 3.2，够不着。
- **根因**：死区为对称 `3.0±0.3`（2.7~3.3），上限超过了攻击距离 3.2。
- **修复**：`MaidCombatMovement.keepDistance` 增加 `maxDistance`（死区上限）参数，近战传
  `SmartMaidEntity.MAID_ATTACK_REACH`(3.2)；单参重载保留对称行为供进食/远程用。死区 = **2.7~3.2**。

### 问题 3：女仆背包界面没有"缩小模型预览"（体验补全）

- **现状**：原版玩家背包左上角有跟随鼠标旋转的人物模型，女仆背包（Shift+E）只有槽位。
- **实现**：复用原版 `InventoryScreen.extractEntityInInventoryFollowsMouse`——`MaidInventoryMenu`
  新增 `DATA_MAID_ID`（ContainerData 同步女仆实体 id，客户端 `level.getEntity` 取实体）；
  `MaidInventoryScreen` 覆写 `extractRenderState` 缓存鼠标坐标 + `extractBackground` 里渲染女仆自身模型
  （显示皮肤/装备/手持，位置 26..75/8..78，与原版一致）。
- **教训**：GUI 小模型预览是原版静态方法，直接复用即可，**不要自研渲染**；实体 id 经 ContainerData 同步，
  客户端按 id 取实体（女仆不在加载范围内就跳过渲染，不崩）。

---

## 2026-09-19（二次）：挖矿坐标 / 挖掘朝向 / 两条线抢话 / 生物中文名 / 指令进气泡与 TTS

### 问题 1：挖矿强制要坐标，女仆"不会自己找矿"（体验设计）

- **现象**：玩家说「矿挖一下」，AI 回复"挖矿得给个具体坐标呀，你脚下这片我不清楚要挖哪儿"。
- **根因**：模组 `MaidAIBridge.mine` 强制 `pos` 必填（`parsePos` 失败即建任务失败）；桌宠 `mod_contract.mine.pos`
  也 `required=True`、`taxonomy` mine 策略 `abs_pos`（长任务必须绝对坐标），AI 只能反问坐标。
- **设计决策（用户拍板）**：**女仆应该自己检测周围的矿物**。`mine` 不给 `pos` 时以女仆脚下为中心自动探测。
- **修复（模组+桌宠双侧）**：
  - 模组 `MaidAIBridge.mine`：pos 可选，缺省 `maid.blockPosition()`；`/maidtasks mine` 加无参重载（`ownerMaidPos`）。
  - 探测范围：**水平 12 格 / 垂直 8 格**（`MineTask.Y_RANGE=8`）——用户说"8 格太小 12 合理，y 轴可以小一些 8 格"。
  - 桌宠 `mod_contract.mine.pos` 改可选 + range 默认 12；`taxonomy.mine` `abs_pos→pos`；`router` mine 无 pos 时不发 pos 参数；
    prompt 说明"挖矿不用给坐标，女仆自动找"。
- **教训**：**"女仆=玩家"意味着很多指令的定位语义该是"自己找/自己判断"，不是等用户喂坐标**；
  指令的可选参数设计要贴合「AI 能说人话指挥」——坐标类长任务默认"以自己为中心自动找"更自然。

### 问题 2：挖掘时面部朝向不对（女仆背对/侧对矿挥镐）

- **现象**：女仆走到矿旁挥镐，但脸没对着矿，朝的是之前走路/跟随的方向。
- **根因**：`MaidBlockBreaker.tick` 走到目标旁后直接 `digTicks++` 开挖，**没让女仆面向目标方块**。
- **修复**：开挖前 `maid.getLookControl().setLookAt(target.getX()+0.5, target.getY()+0.5, target.getZ()+0.5)`。
- **教训**：**"对着目标做动作"必须先 `setLookAt`**——寻路/移动类代码只保证位置不保证朝向，
  玩家视角里"脸没对着挖的矿"非常出戏。

### 问题 3：发一条消息，女仆回复好几条（两条 AI 线抢话）

- **现象**：游戏内跟女仆说话，同一句触发多个回复。真机日志实锤：
  ```
  23:28:06 女仆：镐子挥起来了…            ← 对话线回复 1
  23:28:18 桌宠气泡: 嗯，是我刚说的。挖矿还在进行  ← 第二条回复！
  23:28:27 女仆：铁矿是吧，我这就刨给你！  ← 对话线回复
  23:28:41 桌宠气泡: 铁矿挖出来啦，赶紧捡    ← 第二条回复！
  ```
- **根因**：**两条 AI 链路并存**——
  ① **女仆对话线**（`MaidHandler._maid_chat_turn`，玩家跟女仆说话直接回复）；
  ② **游戏事件线**（`game_handler._handle_game_windows`，`deskpet/` 窗口事件→AI 互动播报→`_on_game_ready` speak 到女仆）。
  玩家对话女仆后 12~14 秒，游戏事件线也调 AI 生成"嗯，是我刚说的"这类回复并 speak 到女仆，观感="一条消息多条回复"。
- **修复（双层静默）**：
  - `maid_handler.recently_in_chat()`：记录最近对话时间，窗口 **6s → 120s**（对话后 2 分钟内算"正对话"）；
  - `game_handler._handle_game_windows`：女仆在线且 `recently_in_chat()` → 事件线**整体静默**（不调 AI/不播报/清累积）；
  - `modes._on_game_ready`：对话期间不 `speak` 到女仆（只显示桌宠气泡）。
- **教训**：**"玩家在跟 X 说话"是一个需要全局广播的状态**——所有可能插嘴的链路（游戏事件/AI 决策/播报）
  都要先问"现在是不是玩家正在跟对象对话"。只有一层抑制挡不住，要"事件源不生成 + 出口不 emit"双层。
- **顺带**：感知事件气泡默认关（`MAID_EVENT_BUBBLE=False`），"只播 AI 聊天"——发现敌人/被打/任务启停不再刷气泡。

### 问题 4：生物名不翻译（AI 上下文和气泡显示 stray/parched/pillager 英文）

- **现象**：女仆回复"刚把围上来的stray和parched清完"、播报"女仆发现了parched（10.8 格外）"。
- **根因**：`_short_name` 只 `ident.split(":")[-1]` 取英文短名，无中文字典。
- **修复**：`maid_link.ENTITY_CN` 实体 id→中文映射（60+ 生物/怪物），`event_to_text`/`summarize_snapshot`/
  `maid_context._full_status` 全走 `_short_name` 翻译。stray→流浪者、parched→帕查德、pillager→掠夺者。
- **教训**：**所有"展示给玩家/AI"的实体 id 都要过一遍中文字典**——模组事件流全是 `minecraft:xxx`，
  直接透传英文既丑又不专业；中文名映射是联动层的基础设施，一次建好全局受益。

### 问题 5：指令【】泄漏进气泡和 TTS（千问流式路径）

- **现象**：气泡/语音里出现 `【attack(range=1)】` 原文，玩家看到听到很难受。
- **根因**：`parse_ai_output`/`strip_action_tags` 只剥半角动作标签 `[`/`{`，**不剥全角 `【指令】`**；
  千问流式 `_run_stream` 不接 DSL（已知缺口），增量直接进气泡 + `_maybe_stream_speak` 逐句朗读——**指令在剥离前就被读了**。
- **修复（显示+语音双层兜底）**：
  - `ai/client._DSL_RE`：`parse_ai_output`/`strip_action_tags` 同时剥 `【…】`；
  - `chat._on_delta`/`_maybe_stream_speak`/`_on_reply`：流式朗读入队前剥离；
  - `bubble._show_ai_bubble`：最底层统一气泡+语音入口兜底剥离（防任何上游漏网）。
- **教训**：**流式输出必须"先剥离再入队"**——增量朗读在剥离逻辑之前就会读出来；
  显示和语音要各设一道兜底，因为上游剥离点（DSL 闭环）不是所有后端都走（千问流式已知缺口）。

### 问题 6：AI 不知道自己是女仆（第三人称称呼"女仆"）

- **现象**：游戏内女仆对话时，AI 以第三人称说"女仆""她"，而不是第一人称"我"。
- **根因**：`_maid_chat_turn` head 说"玩家正在和自己的游戏女仆对话"——把女仆当对象；
  全局人设是"桌面女仆大肥鱼"，与游戏内女仆是两个形象，AI 认知错位。
- **修复**：head 明确"**你就是玩家在游戏里召唤的那个女仆**，用第一人称「我」，不要把女仆当别人"；
  状态行去"女仆："前缀转"我的"；决策 prompt/回执注入/状态注入全部第一人称。
- **教训**：**"把身份告诉 AI"要一次性说透**——"你是 X"比"玩家在和 X 说话"强得多；
  且所有注入 AI 的上下文（状态/回执）的措辞要和身份一致，否则 AI 会从上下文里学回第三人称。

