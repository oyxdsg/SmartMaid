# SmartMaid 与其他模组的冲突分析

> 目的：回答"装上 SmartMaid 会不会和整合包里其他 mod 打架"。
> 方法：**代码级证据**（读实现 + 读内嵌依赖 jar 的元数据），不是经验推测。
> 基线：`master` @ `07f0956`；分析日期 2026-09-28。
>
> **每一条的具体改法**（文件 / API / 代码骨架 / 坑 / 验证）见配套文档
> [`IMPL_MODPACK_COMPAT.md`](./IMPL_MODPACK_COMPAT.md) —— 那里所有 API 签名都对着 26.2 的真实 jar 核实过。

---

## 〇、先更正上一轮的一个说法

**上一轮我说"全项目无 mixin"，这句是错的。**

准确情况：
- ✅ **SmartMaid 自己的代码确实没有任何 mixin**（`src/main/resources` 下无 `*.mixins.json`，无 access widener）。
- ❌ **但它内嵌（jar-in-jar）的 `player_animation_library` 1.2.6 带了 14 个 client mixin**，随 SmartMaid 一起加载。

这个区别很关键，因为 PAL 的 mixin 目标是**原版渲染类**，直接决定了与视觉类 mod 的冲突面。详见 §2。

---

## 一、冲突总表（按真实风险排序）

| # | 冲突面 | 风险 | 会撞上谁 | 影响 |
|---|---|---|---|---|
| 1 | **内嵌 PAL 与整合包已有 PAL/Emotecraft 版本打架** | 🔴 高 | 任何含 PAL / Emotecraft 的整合包 | 启动崩溃（`NoSuchMethodError`） |
| 2 | **PAL 的 14 个 client mixin 覆盖原版渲染层** | 🟠 中高 | EMF / Fresh Animations / First Person Model / 实体渲染类 | 渲染异常或崩溃 |
| 3 | **破坏方块绕过原版流程** | 🟠 中高 | 领地保护 mod、机器/容器 mod | 保护失效、机器数据丢失 |
| 4 | **放置/使用方块传 `null` 玩家** | 🟠 中 | 各类 mod 的 BlockItem / Item | NPE 崩溃 |
| 5 | **配方全量遍历 + 免工作台合成** | 🟠 中 | 大型整合包（配方数千条） | 卡顿、绕过进度门槛 |
| 6 | **战斗目标 = 所有 `Monster`** | 🟠 中 | 剧情/守护类怪物 mod | 打不该打的东西 |
| 7 | 搭路消耗整合包方块 | 🟠 中 | 任何新增方块 mod | 玩家物品被消耗 |
| 8 | 女仆数据存全局 `config/` | 🟡 低中 | 多存档玩家 | 跨存档数据串 |
| 9 | **SmartMaid 自身无 mixin、ID 全命名空间隔离** | ✅ 无 | — | 设计优势，应写进 README |

---

## 二、逐项分析

### 1. 🔴 内嵌 PAL —— 整合包里最可能真炸的一条

**证据**：`build.gradle:28-31` 用 Loom 的 `include` 把 PAL 打进 jar；PAL 元数据：

```
id       = player_animation_library
version  = 1.2.6+mc.26.2
env      = *
entrypoints = [client]
mixins   = ['player_animation_library.mixins.json']
depends  = {fabricloader>=0.19.3, minecraft>=26.2,
            fabric-resource-loader-v1, fabric-command-api-v2}
```

**为什么危险**：Fabric Loader 对嵌套 jar 的规则是 —— **若 mods/ 目录里已存在同 ID 的 mod，嵌套的那个直接跳过**。于是：

- **场景 A**：整合包装了 PAL（哪怕是别的 mod 带进来的）→ SmartMaid 实际用的是**外部版本**。若外部版本低于 1.2.6 → SmartMaid 调用新 API 时 `NoSuchMethodError` **启动崩溃**。
- **场景 B**：另一个 mod 也内嵌了不同版本 PAL → 谁先被加载谁生效，另一个 mod 崩。这类"两个 mod 各带一份库"的冲突在整合包里非常常见，且报错信息通常只提 PAL，**作者很难归因到 SmartMaid**。
- **场景 C**：PAL 是 Emotecraft 生态的库。整合包若装了 Emotecraft（动画类整合包常见），版本对齐要求更高。

**缓解建议**：
1. `fabric.mod.json` 里显式声明 `depends` / `recommends` 对 `player_animation_library` 的版本约束，让 Loader 早失败、早报错（而不是运行时崩）。
2. 评估改为**不内嵌**（走 maven 依赖 + `recommends`），把"装不装动画库"的决定权交给整合包作者 —— 代价是用户要手动装，需权衡。
3. 至少在 README 写明"本模组内嵌 PAL 1.2.6，若整合包已有其他版本请对齐"。
4. **发布前实测**：故意在 mods/ 里放一个旧版 PAL，看报错是否可读。

---

### 2. 🟠 PAL 的 client mixin 覆盖面

**证据**：PAL 的 mixin 清单（全部 `environment: client`，包 `com.zigythebird.playeranim.mixin`）：

```
AvatarMixin, AvatarRendererMixin, AvatarRenderStateMixin,
CapeLayerMixin, ElytraLayerMixin, ItemInHandLayerMixin,
LivingEntityRendererMixin, PlayerCapeModelMixin, PlayerModelMixin,
firstPerson.{HumanoidArmorLayerMixin, ItemInHandLayerMixin,
              ItemInHandRendererMixin, LevelExtractorMixin,
              LivingEntityRendererMixin}
```

**冲突对象**：
| 目标类 | 会撞上的常见 mod |
|---|---|
| `LivingEntityRenderer` | EMF（Entity Model Features）+ Fresh Animations、渲染类整合包 |
| `ItemInHandLayer` / `HumanoidArmorLayer` | 第一人称类 mod、盔甲渲染替换类 |
| `PlayerModel` / `CapeLayer` / `ElytraLayer` | 皮肤/披风类 mod |
| `firstPerson.*` | **First Person Model**、第一人称视角 mod |

**重要**：这些 mixin **随 PAL 无条件加载**，与"你是否用 `/maidanim` 播动画"无关。也就是说，一个只想用女仆挖矿的整合包，也会吃到这 14 个 mixin 的风险。

**缓解建议**：提供关闭动画子系统的配置（配置项 + 条件注册），让整合包可以只享受规则引擎能力。

---

### 3. 🟠 破坏方块绕过原版流程（功能级冲突）

**证据**：`MaidActions.breakBlock`（`MaidActions.java:379-403`）：

```java
for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, maid, tool)) { ... }
...
level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
```

**它绕过了什么**：

| 被绕过的东西 | 谁会因此出问题 |
|---|---|
| Fabric `PlayerBlockBreakEvents.BEFORE/AFTER` | **领地/保护 mod**（FTB Chunks、Flan、claims 类）→ **女仆能在别人的领地里挖方块，保护拦不住** |
| `Block.playerWillDestroy(...)` | **机器/容器/存储类 mod**（破坏时保存 NBT、生成自定义掉落）→ 女仆挖掉机器 = **机器内容物/数据丢失** |
| `level.gameEvent(GameEvent.BLOCK_DESTROY)` | 依赖 gameEvent 的感知类 mod 无响应 |
| 原版经验掉落 | 少掉经验 |
| `BlockDrops` 类回调 | 统计/任务/掉落调整 mod 不生效 |

另外 `Block.getDrops(..., maid, tool)` 把**女仆当 entity 传进 LootContext**：若有 mod 的方块掉落表假定 `THIS_ENTITY` 是 `Player`，会出现类型转换异常或异常行为。

**缓解建议**：改为走原版破坏路径（`ServerLevel.destroyBlock(pos, false, maid, 512)` 或手动补齐 `playerWillDestroy` + `gameEvent` + Fabric 事件调用），并让"是否允许破坏"可被外部 mod 否决。

> 这是整合包/服务器场景下**最需要修的功能性问题** —— 领地保护和机器数据丢失都是玩家会立刻投诉的。

---

### 4. 🟠 放置/使用方块传 `null` 玩家

**证据**：`MaidActions.java:499-500`、`515-516`：

```java
new BlockPlaceContext(maid.level(), null, InteractionHand.MAIN_HAND, stack, hit)
new UseOnContext(maid.level(), null, InteractionHand.MAIN_HAND, stack, hit)
```

（注释写着"null-player 方案，借鉴车万女仆"）

原版 `BlockItem.place` / `Item.useOn` 对空玩家是安全的，但**第三方 mod 的实现经常直接访问 `player`** —— 例如判断潜行（`player.isShiftKeyDown()`）、读手持物、取 UUID 做归属。整合包里只要有一个这样的方块被女仆放置/右键，就是一次 NPE 崩溃。

**缓解建议**：改用 Fabric 的 `FakePlayer`（`FakePlayerFactory`）作为交互主体，让 mod 拿到一个合法的玩家上下文。

---

### 5. 🟠 配方全量遍历 + 免工作台合成

**证据**：`CraftExecutor.java:309` 与 `:423` 的兜底分支 `for (RecipeHolder<?> holder : rm.getRecipes())` —— 全量扫描服务器配方表。

**两个后果**：
- **性能**：整合包（尤其机械/科技类）配方可达数千至数万条，`craft_check` / `craft` 每次全量遍历 → 卡顿。建议加索引缓存。
- **平衡**：女仆**不需要工作台就能原地合成**（这是设计意图）。但整合包作者常把"必须用某台机器"作为进度门槛 —— 只要配方类型是工作台配方，女仆就能凭空做出来，**等于绕过整合包进度系统**。这是作者非常在意的一类"冲突"。

**缓解建议**：提供"仅允许合成白名单/原版配方"的配置；或标注"原地合成"开关，默认尊重整合包设置。

---

### 6. 🟠 战斗目标 = 所有 `Monster`

**证据**：`MaidTargetFilter.java:33,36` —— 只硬编码排除了末影人与僵尸猪灵，其余 `instanceof Monster` 一律视为敌人。

整合包里凡是继承 `Monster` 的**剧情怪、守护者、召唤物、竞技场 NPC** 都会被女仆主动开打。已有配置项缺失。

**缓解建议**：改用 `EntityTypeTags` + config 排除清单（与 P3 数据驱动改造合并做）。

---

### 7. 🟠 搭路消耗整合包方块

**证据**：`MaidActions.java:51-63` 的 `VALUABLE_BLOCKS` 硬编码 ≈35 个原版方块 + `:306-316` 的硬编码优先级。

不在名单里的方块一律可被当建材消耗 → **整合包的贵重方块（其他 mod 的矿物块、装饰方块、容器）会被女仆搭路用掉**。

**缓解建议**：反转为白名单 tag（`#smartmaid:bridge_blocks`），默认只消耗便宜方块。详见 `MODPACK_COMPATIBILITY.md` §P3。

---

### 8. 🟡 女仆数据存全局 `config/`（跨存档串档）

**证据**：`MaidDataManager.java:27`、`MaidSettings.java:42`、`MaidTaskConfig.java:27` —— 全部落在 `config/smartmaid/...`，按**玩家 UUID** 命名。

`config/` 是**全局目录，不随存档隔离**。整合包玩家普遍有多个存档 → 同一个 UUID 在 A 存档的女仆装备/设置，会原样出现在 B 存档。另外整合包自带 `config/` 覆盖时，可能连玩家个人数据一起覆盖。

**缓解建议**：把"女仆装备/任务数据"这类**存档相关内容**改存世界目录（`level.dat` 同级或 `savedData`），只把"玩家偏好设置"留在 `config/`。

---

## 三、明确无风险的部分（要写进 README 当卖点）

| 项 | 证据 | 结论 |
|---|---|---|
| **SmartMaid 自身零 mixin** | `src/main/resources` 无 `*.mixins.json`、无 access widener | 不与任何 mod 抢 mixin 目标点；**对优化类 mod（Lithium/FerriteCore 等）几乎零冲突面** |
| **注册 ID 全命名空间隔离** | 实体 `smartmaid:smart_maid`（`ModEntities.java:23`）、菜单 `smartmaid:maid_inventory` / `maid_control`（`ModMenus.java:28,31`）、三个网络包 `smartmaid:maid_command` / `maid_settings` / `maid_chat`（`network/*.java`） | **零注册冲突**，不会和任何 mod 抢 ID |
| **无键位绑定** | 全文无 `KeyBinding` / `registerKeyBinding` | **无按键冲突**（客户端只有 `/maidchat` 命令开关） |
| **命令前缀独特** | `/summonmaid` `/maidtasks` `/maidai` `/maidanim` `/maidperception` | 撞名概率极低 |
| **依赖面小** | 仅 Fabric API + 内嵌 PAL/mocha（`build.gradle:20-31`） | 不引入重量级前置 |

> 结论：**SmartMaid 的冲突风险不在"改原版"，而在"它的内嵌库"和"它绕过原版流程"。** 前者靠对齐版本，后者靠改走标准 API。这是好消息 —— 都是可控的。

---

## 四、怎么验证（可执行清单）

你当前实例 mods/ 只有 3 个（fabric-api / deskpet-mod / smartmaid），**测不出冲突**。要验证得补一个"典型整合包环境"。

### 建议测试组（分批加，每组跑一遍并看崩溃日志）

| 批次 | 装什么 | 验证目标 |
|---|---|---|
| T1 基础 | Fabric API + Lithium + FerriteCore | 优化 mod 共存（预期：无冲突，验证 §三结论） |
| T2 渲染 | **EMF + ETF + Fresh Animations**、Sodium | **PAL 的渲染 mixin 冲突**（§2，最需要测） |
| T3 保护 | 任意 claims 类 mod（如 FTB Chunks） | 女仆能否在受保护区域挖方块（§3） |
| T4 机器 | Create 或任一机器类 mod | 女仆挖机器后数据是否完整（§3）；女仆放置机器方块是否 NPE（§4） |
| T5 配方 | 载入大型整合包配方集 | craft 卡顿 & 能否合成机器专属配方（§5） |
| T6 库冲突 | 在 mods/ 放一个**旧版 PAL** | 复现场景 A，确认报错可读（§1） |

### 每批的记录项
1. 能否启动到主菜单（mixin 冲突会在启动阶段炸）；
2. `logs/latest.log` 里搜 `Mixin apply failed` / `NoSuchMethodError` / `ClassNotFoundException`；
3. 召唤女仆 → 挖一个方块 → 放一个方块 → 打一只怪 → 合成一次，看有无异常；
4. 记录 mod 版本组合（整合包作者关心的就是这个）。

> 提示：日志是 **GBK 编码**，分析脚本要 `gbk` 解码。

---

## 五、修复优先级建议

| 序 | 事项 | 对应 | 说明 |
|---|---|---|---|
| 1 | 破坏方块改走原版流程 | §3 | 领地保护 + 机器数据丢失，玩家投诉最直接 |
| 2 | 声明 PAL 版本约束 + 冲突说明 | §1 | 一行元数据 + 一段 README，成本极低、收益极高 |
| 3 | 放置/使用改 FakePlayer | §4 | 消除最常见的一类整合包崩溃 |
| 4 | 战斗目标 tag 化 + 配置 | §6 | 与 P3 一并做 |
| 5 | 搭路白名单 tag | §7 | 防止真丢东西 |
| 6 | 动画子系统可关闭 | §2 | 给整合包作者控制权 |
| 7 | 配方索引化 + 合成白名单 | §5 | 性能与平衡 |
| 8 | 存档数据移出 `config/` | §8 | 多存档玩家体验 |

---

*证据索引：`build.gradle`、`fabric.mod.json`、`MaidActions.java`、`MaidBlockBreaker.java`、`MaidTargetFilter.java`、`ModNetworking.java`、`ModEntities.java`、`ModMenus.java`、`data/MaidDataManager.java`、`data/MaidSettings.java`、`entity/ai/bridge/MaidTaskConfig.java`、`libs/player_animation_library-1.2.6.jar`。*
