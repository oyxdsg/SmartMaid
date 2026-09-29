# SmartMaid 整合包兼容性改造 · 技术实现细化

> 配套：[`MODPACK_COMPATIBILITY.md`](./MODPACK_COMPATIBILITY.md)（方案）、[`MOD_COMPATIBILITY_ANALYSIS.md`](./MOD_COMPATIBILITY_ANALYSIS.md)（冲突分析）。
> 本文把每个修复项落到 **文件 / API / 代码骨架 / 坑 / 验证** 一级。**所有 API 签名均已对 26.2 的真实 jar 用 `javap` 核实**（非凭记忆）。
> 核实环境：`minecraft-merged-deobf-26.2.jar`、`fabric-api-0.158.0+26.2`（模块 `fabric-events-interaction-v0-5.2.7`）。
> 编写：2026-09-28。**实施：2026-09-29（T1–T9 全部落地 + 测试代码，见下表）。**

---

## 实施与验收状态（2026-09-29）

> 全部改造项（T1–T9）已实现，JDK 25 下 `gradlew build` 通过。
> **✅ 真机验收通过** —— 2026-09-29 日志：`AutoTest 汇总: PASS=17 FAIL=0 （全部通过）`。
> 测试序列：`tools/autotest.modpack-compat.json`（复制到游戏 `config/smartmaid/autotest.json` 即可复现）。

**真机验证到的结论**（每条都有日志证据）：

| 测试段 | 结果 | 证明了 |
|---|---|---|
| `c01–c09` | 9/9 PASS | **建材白名单生效**：圆石/泥土/木板/深板岩/沙砾可搭；钻石块/箱子/信标/下界合金块不可搭 |
| `g01–g03` | 3/3 PASS | `#smartmaid:never_target` 数据包 tag 正确加载 |
| `d01–d05` | 5/5 PASS | **破坏走原版流程**（方块变 air）+ **掉落进了背包** |
| `v01–v04` | 4/4 PASS | **破坏事件链接通** —— 武装否决器后破坏被拦、方块保留（等价于"领地保护能拦住女仆"） |
| 其他 | — | `main.json` 首次运行自动生成；女仆数据迁移到存档目录成功；无崩溃无异常 |

| 项 | 状态 | 实现位置 |
|---|---|---|
| **T1** 破坏走原版流程 | ✅ 已实现 | `MaidActions.breakBlock`：BEFORE事件 → `playerWillDestroy`(用返回值) → `destroyBlock(drop=false)` → 收掉落进背包 → AFTER事件 |
| **T2** 放置/使用 FakePlayer | ✅ 已实现 | 新建 `entity/ai/MaidActor.java`；`MaidActions.placeBlock` / `useItemOn` 改传 actor |
| **T3** 命令权限按环境降级 | ✅ 已实现 | `SmartMaidConfig.commandPermission(selection)` + 5 个命令 `register(dispatcher, permission)` + `SmartMaid` 注册块 |
| **T4** PAL 版本声明 / 元数据 | ✅ 已实现 | `fabric.mod.json`：depends 加 `player_animation_library >=1.2.6`、fabric-api 版本下限、`suggests modmenu`、`contact` |
| **T5** 桥接默认关闭 + 服务器跳过 | ✅ 已实现 | `BridgeConfig.defaults()` → false；`MaidWsClient.onServerStarted` 判 `server.isDedicatedServer()` |
| **T6** 建材白名单 tag | ✅ 已实现 | 新 tag `#smartmaid:bridge_blocks`（item）+ `MaidActions.isBridgeBlock` 反转为白名单（空 tag 时回退旧黑名单） |
| **T7** 战斗目标 tag 化 | ✅ 已实现 | 新 tag `#smartmaid:never_target`（entity_type）+ `MaidTargetFilter` 改 tag + config |
| **T8** 配方缓存 + 合成白名单 | ✅ 已实现 | `CraftExecutor.allCraftingRecipes/invalidateCache/craftAllowed`；`SmartMaid` 在 SERVER_STARTED 清缓存 |
| **T9** 女仆数据按存档隔离 | ✅ 已实现（**方案调整**） | `MaidDataManager` + `MaidTaskConfig` 改存 `<存档>/smartmaid/maids/`，带一次性迁移 |
| **测试代码** | ✅ 已实现 | `MaidAutoTest` 新增 `check` / `run` / `vetoNextBreak` 三类条目；测试序列 `tools/autotest.modpack-compat.json`（22 条） |

### 与本文档原计划的偏差（如实记录）

1. **T9 未采用 `SavedData`**：原计划迁到 `SavedDataStorage` 的 `SavedData`（走 `Codec`）。
   实际改为「**存档目录下的 NBT 文件**」——
   - 同样解决跨存档串档（这是 T9 的全部收益）；
   - 但**保留现有文件格式与调试能力**（`SavedData` 走 Codec 后无法直接打开 `.dat` 排查，需要额外做导出开关）；
   - 迁移逻辑更简单可控：`Files.copy` 而非"解析 + 重编码"，失败可回退旧路径。
   → 结论：收益相同、风险更低，故采用。**本文档 T9 一节的技术细节（`SavedDataType` 等）保留作参考，不代表实现方案。**
2. **T8 未做自建反向索引**：实测 `collectCandidates` 已优先走原版 `DataComponents.RECIPES`（物品自带配方 id 列表，O(1)），
   全量扫描只在物品**没带**该组件时发生。所以只做了「全量列表缓存」而非反向索引 —— 更简单且够用。
3. **新增配置文件名**：`config/smartmaid/main.json`（原文档只说"主配置文件"，未定名）。

### 测试过程中修掉的 3 个问题（都是本轮引入的，留着防复发）

1. **测试结果走了可关闭的日志开关**：`MaidAutoTest` 原先用 `MaidDebug.log()`，而 `MaidDebug.ENABLED=false`
   → 断言结果全部静音，日志里 0 条 AutoTest（"跑过了"和"没跑"无法区分）。
   → 已全部改用 `SmartMaid.LOGGER.info`，并加了 PASS/FAIL 汇总行。
   **铁律：测试/诊断输出不能走可关闭的调试开关。**
2. **item tag 里引用了只存在于 block 命名空间的 tag**：`#minecraft:base_stone_overworld` / `base_stone_nether`
   只有 block 版 → 整个 `smartmaid:bridge_blocks` 加载失败（日志有 `Couldn't load tag ...` ERROR）→ 白名单失效。
   → 已改为列具体 item id。**block tag 不能直接放进 item tag**，改 tag 前先对比两个目录的清单。
   （副作用：这次失败意外验证了"白名单为空时回退旧黑名单"的兜底设计有效。）
3. **测试断言依赖"女仆当前位置"**：`v04` 用 offset 断言，但 `v03→v04` 隔了 21 秒，女仆跟着玩家走开了
   → 断言指向别处而 FAIL（功能其实是对的）。
   → 测试框架新增 `lastRun` 机制：`run` 记录 setblock 坐标，`check` 支持 `{"at":"lastRun"}`，
   `run`/指令 JSON 支持 `{lx}/{ly}/{lz}` 占位符。**凡是用相对女仆位置的断言都不稳定，必须用绝对坐标。**

### 未做 / 仍需人工验收（自动化覆盖不到）

- **命令权限的真机验证**：单人新建存档（关作弊）`/summonmaid` 应成功；专用服务器非 OP 应被拒。权限上下文测不了。
- **FakePlayer 在真实 mod 方块上的表现**：需要装带 `playerWillDestroy` / 直接读 player 的第三方方块（如 Create 类机器）实测。
- **PAL 与视觉类 mod 的共存**：EMF / Fresh Animations / First Person Model 需按 `MOD_COMPATIBILITY_ANALYSIS.md` §四的测试组实测。
- **T8 的 `/reload` 局限**：数据包重载后新增的配方在本次运行期间不会被缓存看到（已写在代码注释里）。

---

## 〇、API 核实结果（先看这个，它改变了实现方式）

| 结论 | 证据 |
|---|---|
| ✅ **Fabric API 自带 `FakePlayer`** | `net.fabricmc.fabric.api.entity.FakePlayer extends ServerPlayer`；`FakePlayer.get(ServerLevel)` / `get(ServerLevel, GameProfile)`；含 `DEFAULT_UUID`。**不需要自己造**，在 `fabric-events-interaction-v0` 模块里 |
| ✅ `Level.destroyBlock(BlockPos, boolean, Entity, int)` **接受任意 Entity**（不限玩家） | `public boolean destroyBlock(BlockPos, boolean, Entity, int)` |
| ⚠️ **`destroyBlock` 内部不调用 `Block.playerWillDestroy`** | 字节码只有 `Block.dropResources` 与 `gameEvent` 两次关键调用，无 `playerWillDestroy` → **必须手动补** |
| ✅ `Block.playerWillDestroy(Level, BlockPos, BlockState, Player) → BlockState` 在 **`Block`** 类（不是 BlockBehaviour） | javap 确认 |
| ✅ Fabric `PlayerBlockBreakEvents.BEFORE` 是**可取消**的 | `boolean beforeBlockBreak(Level, Player, BlockPos, BlockState, BlockEntity)`，返回 `false` 即取消 |
| ✅ 26.2 权限用 `PermissionCheck`，常量齐全 | `Commands.hasPermission(PermissionCheck)`；`Commands.LEVEL_ALL / LEVEL_MODERATORS / LEVEL_GAMEMASTERS / LEVEL_ADMINS / LEVEL_OWNERS` |
| ✅ `Commands$CommandSelection` 能区分单人/专用服务器 | 枚举 `ALL` / `DEDICATED` / `INTEGRATED`（字段是 private，**按枚举值比较**即可） |
| ✅ 存档 API 在 26.2 **改名** | `net.minecraft.world.level.storage.SavedDataStorage`（旧名 `DimensionDataStorage` 已不存在）；`SavedDataType<T>` 是 **record**：`(Identifier, Supplier<T>, Codec<T>, DataFixTypes)`；入口 `ServerLevel.getDataStorage()` |
| ✅ `Entity.isAlliedTo(Entity)` 是 `final` | 不可覆写（女仆之间天然同盟，无需处理） |

---

## T1 + T2 · FakePlayer 化（破坏 / 放置 / 使用）—— 最高优先

这一项是 T1（破坏走原版流程）与 T2（消除 null player）的**共同前置**：先有 FakePlayer，后面两件事才是"换参数"。

### T1.0 通用：取一个"以主人身份"的 FakePlayer

**为什么必须用主人的 GameProfile**：`FakePlayer` 的 UUID 决定它在**领地保护、权限、统计、方块交互**里被当成谁。
用默认 UUID → 保护 mod 看到一个陌生玩家，可能直接拒绝（或错误放行）；
用**主人的 profile** → 所有基于玩家身份的判定都按主人的权限走 —— 这才是符合直觉的语义（"女仆是主人的手"）。

```java
// 新增：entity/ai/MaidActor.java（建议位置）
public static ServerPlayer actorFor(SmartMaidEntity maid) {
    if (!(maid.level() instanceof ServerLevel level)) return null;
    LivingEntity owner = maid.getOwner();
    GameProfile profile = (owner instanceof ServerPlayer sp)
            ? sp.getGameProfile()
            : new GameProfile(maid.getUUID(), "SmartMaid");
    return FakePlayer.get(level, profile);   // 按 profile 缓存，同主人复用同一实例
}
```

**坑**：
1. `FakePlayer.get()` 是按 `(level, profile)` 缓存单例 —— 同一主人反复取不会 new，无内存泄漏；但**跨维度**会各自一份。
2. FakePlayer 的 `getTeam()` 被覆写、`isInvulnerableTo` 返回 true（不会被打死），语义正确。
3. FakePlayer **不在服务器玩家列表**里（不会触发 join/leave 事件），所以"玩家进服"类逻辑不会误触发 —— 这是好事。
4. 主人离线时 `getOwner()` 可能返回 null → 回退到基于女仆 UUID 的 profile（上面已处理）。
5. **性能**：`actorFor` 每 tick 别调用，只在**真正要破坏/放置的那一刻**取一次（缓存到局部变量）。

### T1.1 破坏方块：改 `MaidActions.breakBlock`

**现状**（`MaidActions.java:379-403`）：
```java
for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, maid, tool)) { ... 塞背包 ... }
level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
```

**问题**：绕过 `PlayerBlockBreakEvents` / `playerWillDestroy` / `gameEvent` / 经验；`getDrops` 里把女仆当 entity 传。

**目标流程**（严格按原版 `ServerPlayerGameMode` 的顺序补齐）：

```java
public static boolean breakBlock(SmartMaidEntity maid, BlockPos pos) {
    if (!(maid.level() instanceof ServerLevel level)) return false;
    BlockState state = level.getBlockState(pos);
    if (state.isAir()) return true;
    BlockEntity be = level.getBlockEntity(pos);
    ServerPlayer actor = MaidActor.actorFor(maid);
    if (actor == null) return false;

    // (1) 事件前置：其他 mod / 领地保护可以否决
    if (!PlayerBlockBreakEvents.BEFORE.invoker()
            .beforeBlockBreak(level, actor, pos, state, be)) {
        MaidDebug.log("breakBlock 被事件否决 " + pos);
        return false;                       // 保护生效 → 女仆放弃该方块
    }

    // (2) mod 方块的 destroy 钩子（机器/容器保存数据的关键）
    //     返回的 state 可能被替换（如双半砖 → 单半砖），必须用返回值
    BlockState willDestroy = state.getBlock().playerWillDestroy(level, pos, state, actor);
    if (willDestroy != state) {             // mod 改了状态（未真正破坏）
        level.setBlock(pos, willDestroy, 3);
        PlayerBlockBreakEvents.AFTER.invoker()
                .afterBlockBreak(level, actor, pos, willDestroy, be);
        return true;
    }

    // (3) 走原版破坏：掉落 + gameEvent + 音效粒子 + 邻居更新
    boolean removed = level.destroyBlock(pos, false, actor, 512);

    // (4) 掉落自行收集进背包（保持现有"掉落直接进背包"的设计）
    if (removed) {
        ItemStack tool = maid.getMainHandItem();
        for (ItemStack drop : Block.getDrops(state, level, pos, be, actor, tool)) {
            if (!drop.isEmpty()) storeToBackpack(maid, drop);   // 复用现有逻辑
        }
        // 经验（可选，与玩家对齐）：Block.popExperience
    }

    PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, actor, pos, state, be);
    return removed;
}
```

**关键点**：
- `destroyBlock(pos, drop=false, actor, 512)` 的 `drop=false` 是**故意的** —— 掉落自己收（保持"进背包"设计），避免物品散落一地又要去捡。`recursionLeft=512` 是原版玩家路径用的值。
- `playerWillDestroy` 的**返回值必须用**：原版逻辑里若它返回不同 state，表示"方块没被真正破坏，只是变了状态"（典型：双半砖）。
- `PlayerBlockBreakEvents.CANCELED` 事件在玩家中途放弃挖掘时触发，这里我们一次挖完，可不发。

**坑**：
1. `Block.getDrops(...)` 的第 5 参数改传 `actor`（FakePlayer 是 ServerPlayer）→ 修掉了"mod loot 假定 Player 导致 CCE"的问题。
2. **`destroyBlock` 会触发邻居更新与流体流动** —— 与现在 `setBlock(AIR, 3)` 行为接近，但多了粒子/音效。若发现"女仆挖方块声音太大"，`destroyBlock` 无法静音（可换 `setBlock` + 手动补 3 个调用，但那样更容易漏）。
3. **不要**再手动 `level.setBlock(AIR)` —— `destroyBlock` 已经做了，重复设置会破坏 mod 的破坏逻辑。
4. `playerWillDestroy` 里 mod 可能抛出异常（因为拿到的是 FakePlayer）→ 建议 try/catch 包住并把失败**记日志而不是崩游戏**（整合包最恨"一个 mod 拖垮整个游戏"）。

**必须保持不变的既有行为**（改完逐条回归）：
- 挖掘耗时（`computeMiningTicks`，硬度 × 100 / 速度）不变
- 裂纹动画（`showMiningProgress`，0-9 级）不变
- 破坏前自动换最优工具（`equipBestToolFor`）不变
- 掉落进背包 + 背包满时 `Block.popResource` 掉在原地不变
- B 站 pillar 搭高、挖挡路块、埋地矿逐层下挖等 `MaidBlockBreaker` 决策顺序不变（本次只改**最终破坏那一步**）

### T2.1 放置方块：改 `MaidActions.placeBlock`

**现状**（`MaidActions.java:499-500`）：
```java
new BlockPlaceContext(maid.level(), null, InteractionHand.MAIN_HAND, stack, hit)
```

**改法**（公开构造签名已核实：`BlockPlaceContext(Player, InteractionHand, ItemStack, BlockHitResult)`）：
```java
public static boolean placeBlock(SmartMaidEntity maid, BlockPos pos, Direction dir) {
    ItemStack stack = maid.getMainHandItem();
    if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
    ServerPlayer actor = MaidActor.actorFor(maid);
    if (actor == null) return false;

    BlockHitResult hit = new BlockHitResult(/* 同现状 */, dir, pos, false);
    BlockPlaceContext ctx = new BlockPlaceContext(
            actor, InteractionHand.MAIN_HAND, stack, hit);   // ← 公开构造，player 非 null
    InteractionResult result = blockItem.place(ctx);
    return result.consumesAction();
}
```

> 注意：`blockItem.place(ctx)` 内部会**扣减 ctx 里的 ItemStack**，而 ctx 里放的是 `maid.getMainHandItem()` 的**同一个引用** —— 所以扣减会直接作用在女仆手上，这与现状语义一致（现状也是这么工作的），无需额外处理。**但**若改用 FakePlayer 的背包，就会扣错人的物品，所以**必须传女仆的 stack**。

### T2.2 使用物品：改 `MaidActions.useItemOn`

同上一模一样，把 `new UseOnContext(level, null, MAIN_HAND, stack, hit)` 换成公开构造 `new UseOnContext(actor, MAIN_HAND, hit)`。
⚠️ **公开构造没有 `ItemStack` 参数** → 它从 `actor.getItemInHand(hand)` 取。所以要用 `actor.setItemInHand(MAIN_HAND, maid.getMainHandItem())` 先同步（或改用 `stack.useOn(ctx)` 而不是 `stack.use(...)`；`stack.useOn(UseOnContext)` 在 26.2 仍存在，见核实表）。

**推荐**：保持 `stack.useOn(new UseOnContext(...))` 的调用形式，只把 player 换成 actor；如需公开构造，则先同步手持。

**验证**：用整合包里"放置时会读 player"的方块（如任何会判断潜行的方块）测一遍，确认不再 NPE。

---

## T3 · 命令权限：按环境自动降级（零配置解决最致命问题）

**现状**：五个命令统一 `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`。

**核心洞察**：`CommandRegistrationCallback` 的回调**自带** `Commands.CommandSelection` 参数（`SmartMaid.java:29` 里已经拿到，叫 `selection`，现在没用）。
单人整合包走 `INTEGRATED`，专用服务器走 `DEDICATED` —— **据此自动选权限等级**，不需要用户配任何东西：

```java
CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
    boolean singleplayer = selection == Commands.CommandSelection.INTEGRATED;
    // 单人：开箱可用（存档默认关作弊也能召唤）
    // 专用服务器：保持管理员级
    PermissionCheck base = singleplayer ? Commands.LEVEL_ALL : Commands.LEVEL_GAMEMASTERS;
    PermissionCheck fromConfig = ...;   // 见下，可覆盖
    PermissionCheck perm = fromConfig != null ? fromConfig : base;

    SummonMaidCommand.register(dispatcher, perm);
    MaidTaskCommand.register(dispatcher, perm);
    // 调试命令保持管理员级（或同样跟随）
    MaidAnimCommand.register(dispatcher, Commands.LEVEL_GAMEMASTERS);
    MaidPerceptionCommand.register(dispatcher, Commands.LEVEL_GAMEMASTERS);
});
```

**config 覆盖**（给整合包/服务器作者控制权），新增 `config/smartmaid/main.json`：
```json
{ "commandPermission": "auto" }   // auto | all | moderators | gamemasters
```

**坑**：
1. `CommandSelection` 的 `includeIntegrated` / `includeDedicated` 字段是 **private**，没有公开 getter → **只能按枚举值比较**（`== INTEGRATED`），不要试图反射。
2. 单人世界里"房主开局域网让朋友进来"仍是 `INTEGRATED` 注册的权限 → 朋友会拿到 `LEVEL_ALL`。可接受（本来就是合作场景），若要严谨可在权限判定里加"仅主人可用"的额外 `requires`。
3. 每个命令的 `requires(...)` 需要**接收参数的注册方法** —— 现有 `SummonMaidCommand.register(dispatcher)` 是静态无参版本，要改成 `register(dispatcher, PermissionCheck)`。**这是本项的主要改动面**（5 个文件的小改）。
4. 别把 `/maidai`（任意 JSON 指令入口）降到 `LEVEL_ALL` 之外 —— 它是**功能最强**的入口，建议即使单人也是主人专属。可考虑：`/summonmaid`、`/maidtasks` 降到 ALL，`/maidai`、`/maidanim`、`/maidperception` 保持 GM。

**验证**：单人新建存档（确认 `allow-cheats=false`）→ `/summonmaid` 应成功；进专用服务器（非 OP）→ 应被拒绝。

---

## T4 · PAL 版本约束 + 动画可关闭

### T4.1 元数据声明（成本最低、收益最高）

`fabric.mod.json` 补：
```json
"depends": {
  "fabricloader": ">=0.18.4",
  "minecraft": "~26.2",
  "java": ">=25",
  "fabric-api": ">=0.158.0+26.2",
  "player_animation_library": ">=1.2.6"
},
"suggests": { "modmenu": "*" }
```

**坑**：把 PAL 写进 `depends` 后，**若外部版本低于 1.2.6，Loader 会在启动时明确报错**（而不是运行时 `NoSuchMethodError`）。这正是我们想要的效果 —— 错误信息指向性强。
但要确认 PAL 的版本号格式：实测为 `1.2.6+mc.26.2`，SemVer 的 `>=1.2.6` 能匹配（`+` 是 build metadata，不参与比较）。

### T4.2 动画子系统可关闭（规避 §2 的 mixin 面）

**注意**：PAL 的 mixin 是**随 jar 加载**的，写 config 在 Java 层"不播动画"**不能避免 mixin 生效**。要真正规避只有两条路：
- **路 A（省钱）**：不动。接受 PAL 的 mixin 面，把风险写进 README 让整合包作者知情。
- **路 B（彻底）**：把 PAL 从 `include` 改为 `recommends`（不内嵌），动画功能在**反射/软依赖**下工作，缺库时 `/maidanim` 自动降级并提示。
  - 代价：普通用户要手动装 PAL；`MaidAnimManager` 的加载链要加容错（`FabricLoader.isModLoaded("player_animation_library")` 门控 + 类加载隔离）。
  - 收益：主 jar 变成"零 mixin"，彻底消除 §2 冲突面。

**建议**：先走路 A 并写进 README；把路 B 作为"若收到视觉整合包作者的兼容诉求"再启动的备选。

**改动面（若走路 B）**：`client/animation/MaidAnimManager.java` 是全项目唯一引用 PAL 的地方（`HumanoidAnimationController`），加一层间接即可；`SmartMaidModel.setupAnim` 里的调用点也要门控。

---

## T5 · 桥接默认关闭 + 专用服务器跳过

### T5.1 默认值反转

`BridgeConfig.defaults()`（`BridgeConfig.java:79-92`）：`enabled` / `ws.enabled` 从 `true` → `false`。

**兼容性考虑**：老用户升级后桥接会"突然不好用" → 建议加一次性迁移：**若配置文件已存在则不覆盖**（现有逻辑就是这样），所以只有**全新安装**才拿到 `false`。
→ 已经在用的用户不受影响（文件已存在，保留原值）。这正好是我们要的语义。

### T5.2 专用服务器跳过（判断方式很关键）

不要用 `FabricLoader.getEnvironmentType() == EnvType.CLIENT` —— 单人游戏的集成服务器**也在客户端进程里**，那样判断会把单人一起关掉。

**正确做法**：用 `ServerLifecycleEvents` 拿到的 `MinecraftServer` 实例判断：
```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    if (server.isDedicatedServer()) {
        SmartMaid.LOGGER.info("专用服务器：跳过桌宠桥接（桥接仅对本机桌宠有意义）");
        return;
    }
    MaidWsClient.onServerStarted(server);
});
```
同理，`MaidTelemetryWriter` 在专用服务器上也不应往服务器根目录写 `deskpet/` —— 用同一个判断门控。

**验证**：起一个专用服务器（`gradlew runServer` 或用户实例）→ 日志无桥接启动行、根目录无 `deskpet/`。

---

## T6 · 建材白名单（把"黑名单外全能消耗"反转掉）

**现状**：`MaidActions.java:51-63` 的 `VALUABLE_BLOCKS`（≈35 个原版方块的**黑名单**）+ `:306-316` 优先级硬编码。

**改法**：新增**物品 tag** `data/smartmaid/tags/item/bridge_blocks.json`（数据包可覆盖）：
```json
{
  "values": [
    "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
    "minecraft:cobblestone", "minecraft:cobbled_deepslate",
    "minecraft:stone", "minecraft:deepslate", "minecraft:mud",
    { "id": "#c:cobblestones", "required": false },
    { "id": "#c:stones", "required": false }
  ]
}
```

代码改为**白名单判定**：
```java
private static final TagKey<Item> BRIDGE_BLOCKS =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("smartmaid", "bridge_blocks"));

public static boolean isBridgeBlock(ItemStack stack) {
    if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
    if (!stack.is(BRIDGE_BLOCKS)) return false;           // ← 默认不碰未声明的东西
    return !Config.bridgeBlacklist().contains(bi.getBlock());   // config 追加黑名单
}
```

**为什么用 item tag 而不是 block tag**：女仆消耗的是 `ItemStack`（背包里的物品），直接 `stack.is(tag)` 最省事，也避免了 block↔item 反查。
**兼容 `#c:` 惯例 tag**：用 `"required": false` 让未安装相应 mod 时不报错（这是整合包友好写法的标准做法）。
**坑**：现有玩家如果一直用"任意方块都能搭路"的行为，改成白名单后**突然不能搭了**（比如背包里只有 Create 机壳）→ 建议 fallback：白名单为空时回退到"黑名单模式"并在日志提示一次。

---

## T7 · 战斗目标 tag 化

**现状**：`MaidTargetFilter.java:33` 硬编码 `instanceof EnderMan || instanceof ZombifiedPiglin`。

**改法**：排除表用 `EntityTypeTags` + config 追加：
```java
private static final TagKey<EntityType<?>> NEVER_TARGET =
        TagKey.create(Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("smartmaid", "never_target"));

public static boolean isHostile(SmartMaidEntity maid, LivingEntity target) {
    if (target == null || target == maid || !target.isAlive()) return false;
    if (isFriendly(target)) return false;
    if (target.getType().is(NEVER_TARGET)) return false;     // 数据包可覆盖
    if (Config.neverTarget().contains(target.getType())) return false;  // config 追加
    return target instanceof Monster && maid.canAttack(target) && maid.hasLineOfSight(target);
}
```
默认 `never_target` 里放 `minecraft:enderman` / `minecraft:zombified_piglin`（保持现有行为不变）。
**顺带**：`isFriendly` 只判 Player —— 整合包里如果有"友方生物"概念（其他 mod 的同伴），应允许 config 追加，否则女仆可能打别人的宠物。
**注意**：`Entity.isAlliedTo(Entity)` 是 **final**，不能覆写；如果要"不打主人的宠物"，得在 `isFriendly` 里自己判 `target instanceof TamableAnimal t && t.getOwnerUUID() == 主人UUID`。

---

## T8 · 配方索引化 + 合成白名单

**现状**：`CraftExecutor.java:309` / `:423` 的 `rm.getRecipes()` 全量遍历。

**改法**：
1. **价格很低的优化**：用原版索引替代正向全扫 —— `RecipeManager.getRecipeFor(RecipeType, RecipeInput, Level)`（3 个重载都已核实）内部走原版的 `RecipeMap` 缓存，别再自己 `stream().filter()`。
2. **反向索引**（"谁能产出 X"）才需要自建：
```java
private static final Map<Item, List<RecipeHolder<CraftingRecipe>>> CACHE = new WeakHashMap<>();
// 失效时机：ServerLifecycleEvents.SERVER_STARTED / 数据包重载（/reload、/datapack）
```
   建索引用一次 `getRecipes()`，之后查表。**必须在数据包重载后清空**，否则整合包 `/reload` 后女仆按旧配方合成。
3. **合成白名单**（防"绕过整合包进度门槛"）：新增 config `craftWhitelistMode: off | vanilla-only | tag`：
   - `off`（默认，保持现有能力）
   - `vanilla-only`：只允许 `minecraft:` 命名空间的配方
   - `tag`：只允许 `#smartmaid:craftable` tag 内的产物

**坑**：`getRecipes()` 返回 `Collection<RecipeHolder<?>>`，里面混着熔炉/酿造等所有类型 → 取索引时**必须按 `RecipeType.CRAFTING` 过滤**（现有代码已有 `instanceof CraftingRecipe` 判断，保持）。

---

## T9 · 女仆数据移出 `config/`（跨存档串档）

**现状**：`MaidDataManager.java:27`、`MaidSettings.java:42`、`MaidTaskConfig.java:27` 全部 `getConfigDir()/smartmaid/...`，按玩家 UUID 存。

**26.2 的正确 API**（已核实）：
```java
// net.minecraft.world.level.storage.SavedDataStorage（旧 DimensionDataStorage 已不存在）
SavedDataStorage storage = serverLevel.getDataStorage();

SavedDataType<MaidWorldData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("smartmaid", "maid_data"),
        MaidWorldData::new,          // Supplier<T>：创建空实例
        MaidWorldData.CODEC,         // Codec<T>
        null);                       // DataFixTypes，可空

MaidWorldData data = storage.computeIfAbsent(TYPE);
storage.set(TYPE, data);
```
`SavedDataType` 是 **record**：`(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType)`。

> **现成范例可直接抄**：26.2 自带 `net.minecraft.world.level.saveddata.WeatherData` 与
> `WanderingTraderData` 就是 `SavedData` 的标准用法（用 `python tools/probe_api.py --find SavedData` 可见），
> 照它们的结构写最不容易出错。

**迁移策略（重要，别把老玩家的女仆弄丢）**：
1. 首次读到某个 UUID 时：**先看新存档容器有没有** → 有就用；没有则**从旧的 `config/smartmaid/maids/<UUID>.dat` 读一次并写入新容器**（一次性导入），旧文件保留不删（可回滚）。
2. 之后只写新位置；旧文件加一个 `.migrated` 后缀或保留原样 + 日志说明。

**坑**：
1. `MaidSettings`（玩家偏好：友军伤害/护主/掉落档位）**其实留在 `config/` 更合适** —— 它是"玩家习惯"，跨存档沿用符合直觉。**只有"女仆装备/物品栏/任务数据"该进存档**。建议只迁 `MaidDataManager` 与 `MaidTaskConfig`。
2. 用 `SavedData` 后数据变成 NBT（走 `Codec`），**不能再直接改文件**调试了 —— 女仆存档调试手段会变（现在靠打开 `.dat`）。需要在 `MaidDebug` 里留个"导出当前女仆 NBT 到日志"的开关，补上这个可观测性缺口。
3. 存档体积：`SavedData` 会随 level.dat 相关数据一起保存，比散落的 `.dat` 文件更集中，但**每次保存会重写整份** → 女仆数据别塞太多（现在只有 41 格背包 + 装备，量很小，没问题）。

---

## 十、改造顺序与依赖关系

```
T1.0 FakePlayer 基建 ──┬─→ T1 破坏走原版流程（修实伤）
                       └─→ T2 放置/使用（消除 NPE）
T3 命令权限（独立，最影响可用性）
T4.1 PAL 版本声明（独立，10 分钟）
T5 桥接默认关闭 + 服务器跳过（独立）
T6 建材白名单 tag（独立）
T7 战斗 tag（独立，可与 T6 共用 config 基建）
T8 配方索引 + 白名单（独立）
T9 存档迁移（独立，需迁移逻辑）
```

**建议批次**：
- **批 1（可用性 + 零风险）**：T3 + T4.1 + T5 —— 解决"玩家用不了"和"库里打架"，改动小。
- **批 2（冲突硬伤）**：T1.0 + T1 + T2 —— 一起改，一起测（共用 FakePlayer 基建）。
- **批 3（数据驱动）**：T6 + T7 + config 基建 —— 一起做，共用配置文件。
- **批 4（精细）**：T8 + T9。

**每批都必须走的链**（项目既有纪律）：`gradlew build` → 备份旧 jar → 部署 → **真机验证** → 更新文档。
> 特别注意 `DEVELOPMENT_ISSUES.md` 2026-09-16 的教训：**改了代码只 `compileJava` 没 build 部署，会对着旧 jar 白排查几小时**。

---

## 十一、本项改造的回归风险清单（改完逐条确认）

| 行为 | 为什么会被 T1/T2 影响 | 怎么验 |
|---|---|---|
| 挖掘耗时与裂纹 | 破坏路径换了 | 挖石头/钻石块，对比改造前后耗时 |
| 掉落进背包 | 掉落收集逻辑重接 | 挖各类方块，确认进背包；背包满时掉原地 |
| 埋地矿逐层下挖 | `MaidBlockBreaker` 决策链依赖 `breakBlock` 返回值 | 挖埋地矿，确认继续往下而不是中断 |
| pillar 搭高 | 放置路径换成 FakePlayer | 高处矿搭高，确认不因放置失败而掉落 |
| 敌对生物战斗 | T7 改了目标过滤 | 打僵尸/骷髅，确认仍打；末影人仍不打 |
| 跟随/坐下/传送 | 完全不该受影响 | 快速回归一遍 |
| 桥接（桌宠在线时） | T5 改了默认值 | 手动开 `enabled:true`，确认 WS 正常 |
| 联机多人 | T3 改了权限 | 专用服务器非 OP 玩家应被拒绝 |

---

## 十二、怎么验收本次改造（无 GUI 操作，代码层自动化）

```bash
# 1) 构建 + 备份 + 部署
export JAVA_HOME="<JDK25>"
cd SmartMaid && ./gradlew build -x test --console=plain
cp build/libs/smartmaid-0.1.0.jar "$GAME/mods/smartmaid-0.1.0.jar"

# 2) 把测试序列放进游戏配置目录
cp tools/autotest.modpack-compat.json "$GAME/config/smartmaid/autotest.json"

# 3) 进游戏 —— 测试期间【玩家不要移动】，女仆位置漂移会导致方块坐标对不上
#    AutoTest 会自动召唤女仆并逐条执行，全部跑完把配置改名为 autotest.done.json

# 4) 看结果（Windows 中文系统日志是 GBK）
python -c "print(open(r'$GAME/logs/latest.log',encoding='gbk',errors='replace').read())" | grep AutoTest
```

**期望：22 条全部 PASS。** 各项对应的验证意图：

| 测试段 | 验证什么 | 为什么重要 |
|---|---|---|
| `c01–c09` | 建材白名单 | 圆石/泥土/木板/深板岩/沙砾 → `allowed=true`；钻石块/箱子/信标/下界合金块 → `false`。**整合包的贵重方块不再被女仆当砖头消耗** |
| `g01–g03` | `#smartmaid:never_target` 数据包 tag | tag 文件真的加载生效（末影人/僵尸猪灵在内，僵尸不在） |
| `d01–d05` | 破坏走原版流程 | 方块消失（`air`）+ **掉落进了背包** —— 说明 `destroyBlock` 路径与掉落收集都正常 |
| `v01–v04` | 破坏事件链 | 武装否决器后破坏被拦（方块保留）—— **等价于"领地保护能拦住女仆"** |

> `d05` 特意断言 `cobblestone >= 1` 而预置里**不给**圆石 —— 所以这条 PASS 只可能来自"挖石头掉的圆石"，能真正证明掉落收集链路。

**还没被自动化覆盖的**（需人工）：命令权限（单人 vs 服务器）、FakePlayer 在第三方 mod 方块上的表现、PAL 与视觉 mod 共存。

---

*API 核实记录：已沉淀为项目工具 **`SmartMaid/tools/probe_api.py`**（以后查 26.2 签名直接用）：
```bash
python tools/probe_api.py net.minecraft.world.level.Level destroyBlock   # 查方法
python tools/probe_api.py --bytecode net.minecraft.world.level.Level destroyBlock   # 看内部调用
python tools/probe_api.py --find SavedData                                # 搜类名
python tools/probe_api.py --fabric events-interaction                     # 展开 Fabric 嵌套模块
```
> 注意：26.2 的 class 是 Java 25 编译的，**必须用 JDK 25 的 javap**（JDK 17 会报 unsupported major version）。工具已默认指向 HMCL 的 mojang runtime。*

核实类：`Level`、`ServerLevel`、`Block`、`BlockBehaviour`、`BlockState`、`Commands`、`Commands$CommandSelection`、`Entity`、`LivingEntity`、`ItemStack`、`BlockItem`、`BlockPlaceContext`、`UseOnContext`、`SavedData`、`SavedDataType`、`SavedDataStorage`、`RecipeManager`、`fabric.api.entity.FakePlayer`、`fabric.api.event.player.PlayerBlockBreakEvents`。
