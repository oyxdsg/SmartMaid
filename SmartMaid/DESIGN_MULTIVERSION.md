# SmartMaid 多版本兼容方案（调研 + 设计）

> 编写日期：2026-10-06 · 状态：**方案待评审（未开工）**
> 目标：让 SmartMaid 从「只支持 MC 26.2」扩展为「一份源码，产出多个版本的目标 jar」。
> 本文只做调研 + 设计，**不含任何代码改动**。所有涉及代码的结论都标注了文件与行号，可复核。

---

## 0. 结论摘要

1. **推荐范围：26.1 / 26.1.x、26.2、26.3 三条线**（"最近的几个版本"）。**不建议**把 1.21.x 纳入本轮（见 §3.3）。
2. **构建体系已经就位，这是最大的好消息**：MC 自 **26.1 起完全去混淆**，26.1 / 26.2 / 26.3 三条线共用同一套构建骨架
   （`net.fabricmc.fabric-loom` + 无 mappings + Java 25 + `implementation`）。
   本工程的 `build.gradle` **已经是**这套写法（已核对），因此不需要为兼容而重构构建。
3. **向后（→26.1）改动量小、以命名为准**：集中在 ~2 类 API、~11 个调用点。
4. **向前（→26.3）改动量最大**：26.3 是一次**大规模客户端渲染重构**（Blaze3D → Renderpearl、GLFW → SDL、OIT）。
   服务端逻辑预期低风险，但**客户端渲染 + 内嵌的第三方动画引擎 PAL 是最高风险项**。
5. **架构建议：一份源码 + 按版本切构建 + 差异隔离层**，而不是维护多个分支。
6. **每个版本产出独立 jar，声明独立 `minecraft` 版本范围**；**不要**打一个宽范围的"通吃 jar"
   —— Fabric Loader **不做 mod 版本握手**（不像 Forge 会给 Missing Mods 清单），宽范围会导致
   **静默的网络不兼容**（26.1/26.2/26.3 协议号分别为 **775 / 776 / 777**）。

---

## 1. 版本与生态矩阵（证据）

| MC 版本 | 发布日 | 协议 | Java | Fabric Loader | Fabric API | Loom | Gradle |
|---|---|---|---|---|---|---|---|
| 1.21.11 | 1.x 最后一版（**最后混淆版**） | — | **21** | — | 0.141.5+1.21.11 | `fabric-loom-remap` | — |
| 26.1 → 26.1.1 → 26.1.2 | 2026-03-24 / 04-01 / 04-09 | 775 | **25** | 0.19.x | 0.155.3+26.1.2 | 待核实（≤1.17） | 待核实 |
| **26.2（工程当前）** | 2026-06-16 | 776 | 25 | **0.19.3** | 0.160.0+26.2（工程用 0.158.0） | **1.17** | **9.5.1** |
| **26.3（最新）** | 2026-09-15 | 777 | 25 | **0.19.5** | **0.161.0+26.3** | **1.18-SNAPSHOT** | **9.6.0** |

关键事实：

- **Java 25 自 26.1 起是硬要求** → 26.1 / 26.2 / 26.3 三条线**同一个 JDK**，无 Java 版本分裂。这是能"一份源码"的前提。
- **26.1 起游戏完全去混淆**（含参数名）。官方映射名 = Mojang 名 = 源码名。Yarn 已停止维护。
- 去混淆带来的构建变化（Fabric 官方 porting 文档）：
  1. Loom 插件 id 从 `fabric-loom` 改为 **`net.fabricmc.fabric-loom`**
  2. **删除 `mappings` 行**
  3. `modImplementation` / `modCompileOnly` / `modApi` → **`implementation` / `compileOnly` / `api`**
  4. Java 兼容级别 **21 → 25**
  5. `remapJar` → **`jar`**
  6. access widener / class tweaker 头部：`named` → **`official`**
  7. 旧的（1.21.11 及更早）mod **不能**在 26.1 上工作，**连 compileOnly 都不行**
- **26.2 的"Gui 重组"**：`screen` 字段 / `ChatListener` 等从 `Minecraft` 移到 `Gui`；
  HUD 独立成 `Hud` 类。写法变化：`Minecraft.getInstance().screen` → `Minecraft.getInstance().gui.screen()`；
  `gui.setOverlayMessage(...)` → `gui.hud.setOverlayMessage(...)`。
  设置当前屏幕：26.2 的 `Minecraft` 上只剩 **`setScreenAndShow(Screen)`**（已用 `probe_api.py` 在本机 26.2 jar 上核实，
  `net.minecraft.client.Minecraft` 中匹配 `setScreen` 的只有这一个方法）。
- **26.2 的注册表拆分**：方块 id / 物品 id 分开存于 `BlockIds` / `BlockItemIds` / `ItemIds`，移除 `valueLookupBuilder`。
- **26.3 的渲染重构（本轮最大变数）**：`com.mojang.blaze3d.*` → `com.mojang.renderpearl.*`；
  GLFW → **SDL**；引入 **OIT**（顺序无关透明）；着色器 `#moj_import` → `#include`、uniform 必须 `layout(location=...)`。

---

## 2. 工程构建现状核对（已读源码）

| 项 | 现状 | 判定 |
|---|---|---|
| Loom 插件 | `id 'net.fabricmc.fabric-loom' version "${loom_version}"`（`build.gradle:2`） | ✅ 已是去混淆写法 |
| mappings | 无 `mappings` 行 | ✅ 无需删 |
| 依赖写法 | `implementation`（`build.gradle:22-23,28,31`） | ✅ 无需改 |
| Java | `release = 25`、`VERSION_25`（`build.gradle:49,54-55`） | ✅ |
| Gradle wrapper | 工程自带（`gradlew`） | 待核对具体版本 |
| `fabric.mod.json` | `"minecraft": "~26.2"`、`"fabric-api": ">=0.158.0+26.2"`、`java >=25`（`fabric.mod.json:17-20`） | ⚠️ 需按版本参数化 |

**结论**：构建骨架上，三条线只差 **`minecraft_version` / `loader_version` / `loom_version` / `fabric_api_version` 四个属性**
（`gradle.properties:10-13`）。不需要为多版本重建工程。

---

## 3. 版本敏感 API 清单（按扫描结果分类）

扫描方式：对 131 个 Java 文件做了全量 grep（正则见附录），逐项判定。

### 3.1 已确认"无影响"（不需要改）—— 重要，省掉大量排查

| 项目 | 相关 API | grep 结论 |
|---|---|---|
| 26.3 移除的 Fabric 注册表 | `CompostingChanceRegistry` / `FuelRegistry` / `FabricPotionBrewingBuilder` / `StrippableBlockRegistry` / `TillableBlockRegistry` / `FlattenableBlockRegistry` | **一个都没用到** |
| 26.1 Fabric 重命名族 | `ItemGroupEvents` / `KeyBindingHelper` / `EntityModelLayerRegistry` / `ColorProviderRegistry` / `ParticleFactoryRegistry` / `WorldRenderEvents` / `ScreenHandler` | **一个都没用到** |
| 网络重命名 | 工程已用新名 `PayloadTypeRegistry.serverboundPlay()` / `clientboundPlay()`（`ModNetworking.java:28,32,36,40,44,48`） | ✅ 26.1 起就是新名 |
| GUI 抽取式渲染 | `GuiGraphicsExtractor` / `extractRenderState` / `extractBackground` | ✅ **1.21.8 起就存在**，26.1 已有；26.3 的签名也未变（只有 `textWithWordWrap` 返回值、`tooltip` 重载等增量变化） |
| 26.2 注册表拆分 | `BlockIds` / `BlockItemIds` / `ItemIds` / `valueLookupBuilder` | **未使用** |
| 物品组件 | 只用 `DataComponents.BLOCKS_ATTACKS` / `FOOD` / `RECIPES`（`SmartMaidEntity.java:316,409`、`CraftExecutor.java:359,478`）；26.3 新增的是 `COMPOSTABLE` / `COOKING_FUEL` / `BREWING_FUEL` | 无交集 |
| 原始 OpenGL | `GL11` / `RenderSystem` 直接调用 | 无（渲染全走 `extract*`） |

### 3.2 需要改（向后 → 26.1）

| # | 项 | 位置 | 26.2 现状 | 26.1 预期 |
|---|---|---|---|---|
| 1 | 打开屏幕 | `client/SmartMaidClient.java:45`、`client/gui/MaidMenuScreen.java:298,303,304,305`、`client/gui/MaidSubScreen.java:81`（共 **6 处**） | `Minecraft.setScreenAndShow(Screen)` | `Minecraft.setScreen(Screen)` |
| 2 | 命令权限 | 5 个命令类 `MaidTaskCommand` / `MaidAICommand` / `MaidAnimCommand` / `MaidPerceptionCommand` / `SummonMaidCommand` 的 `register(..., PermissionCheck permission)` + `SmartMaid.java` 的环境判定 | `net.minecraft.server.permissions.PermissionCheck` + `Commands.hasPermission(PermissionCheck)` | `Commands.hasPermission(int)` 等级制（`LEVEL_ALL` / `LEVEL_GAMEMASTERS`） |

> 两项都是**命名/签名级**差异，逻辑不变。**建议顺手收敛成 1 个 `compat` 门面**（见 §4.2），
> 使 26.1 ↔ 26.2 的差异从 11 个调用点压缩到 1 个文件。

### 3.3 需要改（向前 → 26.3）—— 风险集中区

26.3 是客户端渲染的大型重构。⚠️ **注意：2026-10-06 实测（见 §10）推翻了本文初稿"服务端不受影响"的判断**——
渲染侧反而基本稳定，**真正的断点在实体挥动/攻击动画 API（`LivingEntity`）**。
NeoForge 26.2→26.3 迁移 primer 未列出 `net.minecraft.world.entity.ai` / `network.protocol` 的包类变更，
这正是初稿误判的来源：**primer 不覆盖全部，必须以实测为准**。需要关注的是：

| # | 项 | 涉及文件 | 风险 |
|---|---|---|---|
| 1 | **内嵌第三方库 PAL 1.2.6**（jar-in-jar，`build.gradle:28-29`、`libs/`） | `client/animation/MaidAnimManager.java` | **最高**。PAL 是 26.2 构建；26.3 换了整套渲染后端（Blaze3D→Renderpearl），PAL 极可能需要同步更新 |
| 2 | 实体渲染链 `HumanoidModel` / `HumanoidArmorLayer` / `ArmorModelSet` / `ModelLayers.PLAYER_ARMOR` | `client/renderer/SmartMaidRenderer.java:47,49,50`、`SmartMaidModel.java:22` | 高。26.3 改了 `EquipmentClientInfo`（trim）、`OrderedSubmitNodeCollector#submitModel` 改收 `UvMapping`、`AbstractZombieModel` 移除、`ZombieModel`/`GiantZombieModel` 改继承 `HumanoidModel`；`HumanoidModel` 新增 `setupSwimAnimation` |
| 3 | 渲染状态类 | `client/renderer/SmartMaidRenderState.java` | 中。`Camera#extractRenderState` 改收 `DeltaTracker`、`GameRenderer#render/renderLevel` 不再收参数、`ItemInHandRenderer` → `FirstPersonHandsAndItemsRenderer` |
| 4 | 菜单/屏幕 | `client/gui/*`（8 个文件） | 中。`Screen#fillCrashDetails` 移除、新增 `isInputCaptured`/`scheduleNarration`；`AbstractSelectionList`、`ScrollableLayout` 等组件有增改 |
| 5 | 数据包 tag 复核 | `resources/data/smartmaid/tags/**` | 中。26.3 新增羊毛/混凝土台阶楼梯、坐垫、草床等物品 → 需复核 `#smartmaid:junk` 等 tag 语义与 `#minecraft:*` 引用是否仍有效 |
| 6 | 贴图/模型资源 | `assets/**` | 低。26.3 动了纹饰调色板（`armor_trims.json` 移除、`palette_id`）、部分 vanilla 模型模板改名 |

### 3.4 明确不建议纳入本轮：1.21.x

- 1.21.11 是**最后一个混淆版本**：需要 mappings（Yarn/Mojmap）+ `fabric-loom-remap` 插件 + **Java 21**。
- 所有 Fabric API 与 vanilla 名称回到旧名（`PacketByteBufs`、`World`、`ItemGroupEvents`、`ScreenHandler`…），
  `PermissionCheck`、`SavedDataStorage`、`GuiGraphicsExtractor`/`extract*` 等一批目标 API 在 1.21.x 上不存在或不同。
- Fabric 官方 porting 文档明确指出：1.21.11 及更早的 mod **完全无法**在 26.1 上工作——反向同理，跨度极大。
- **成本 ≈ 一次完整移植**（等于另起一条产品线）。建议**单独立项**，不与本轮混做。

---

## 4. 方案设计

### 4.1 总体架构：一份源码 + 按版本切构建

```
SmartMaid/（唯一源码树）
   ├── build.gradle            共用（已符合去混淆写法，只需参数化）
   ├── gradle.properties       ← 版本四元组由外部注入：mc / loader / loom / fabric-api
   └── src/main/java/...
        ├── compat/            ★ 新增：版本差异隔离层（唯一的"脏"地方）
        │    └── MaidCompat.java
        └── 其余全部与版本无关
```

**为什么不用多分支**：26.1→26.3 的差异是**点状**的（几个 API），不是结构性的；
本工程文档极重、历史上有大量手工 `.bak` 快照，维护三份代码必然漂移。
用"一份源码 + 按版本切"可以让 95% 的代码只写一次。

### 4.2 版本差异隔离层（关键设计）

把版本相关的调用**全部收敛到一个门面类**，其余代码只依赖门面：

```java
// compat/MaidCompat.java —— 唯一允许出现版本分支的文件
public final class MaidCompat {
    /** 打开屏幕：26.1 = setScreen / 26.2+ = setScreenAndShow */
    public static void openScreen(@Nullable Screen screen) { ... }

    /** 命令所需权限：26.1 = int 等级 / 26.2+ = PermissionCheck */
    public static Object commandPermission(boolean dedicatedServer) { ... }
}
```

实现方式二选一（**建议 A**）：

- **A. 编译期条件裁剪（推荐）**：源码里用条件注释（如 `//? if >=26.2 {`）或按版本切 source set，
  差异只在编译期展开，**无运行期开销、无反射**。
  可评估引入社区标准多版本工具（Stonecutter / preprocessor 类）；
  ⚠️ 需先确认其对"去混淆 + 无 mappings"体系的支持情况。
- **B. 运行期能力探测**：反射查找可用方法。**不推荐**——本工程铁律是"移动走原版体系、不重写物理"，
  同样精神下不应引入反射这种不可静态验证的路径；且会拖慢启动、污染日志。

### 4.3 构建与产物

1. `gradle.properties` 的四个版本属性改为可被外部覆盖（`-Pmc_version=26.1.2` 或环境变量），
   默认值保持 26.2（不破坏现有开发流）。
2. `fabric.mod.json` 的 `minecraft` 改为**按构建版本注入**（与 `${version}` 同样的 `expand` 机制）。
   **每个版本产出独立 jar**，`minecraft` 声明该版本的窄范围（如 `~26.1` / `~26.2` / `~26.3`）。
   **禁止**宽范围通吃 jar（理由见 §0.6）。
3. 三个产物共用同一 `mod_version`（0.1.x），`archivesName` 后缀区分，例如
   `smartmaid-0.2.0+mc26.1.jar` / `+mc26.2` / `+mc26.3`。
4. Loom / Gradle 按目标版本取官方推荐值（§1 表），**不要**一套版本强撑三线。

### 4.4 第三方依赖（PAL）—— 必须先定，否则 26.3 寸步难行

`libs/player_animation_library-1.2.6` 是本地 flatDir 依赖并以 jar-in-jar 内嵌（`build.gradle:16-17,28-29`）。
26.3 渲染后端大改，**PAL 1.2.6 能否在 26.3 工作必须先验证**。三条出路：

1. PAL 官方有 26.3 版本 → 按版本引用不同 PAL 版本（`libs/` 存多份）。
2. PAL 无 26.3 版本 → 动画系统降级为**软依赖**（`fabric.mod.json` 从 `depends` 移到 `suggests`），
   缺失时禁用 `/maidanim` 并如实提示（**不做假成功**）。
3. 自维护 fork PAL（成本最高，最后考虑）。

> **✅ 已于 2026-10-06 实测（见 §10.6）：PAL 1.2.6 与 26.3 只差 2 个成员调用，且都是 1:1 等价改写。**
> 结论：**不需要降级为软依赖**，也基本不需要等官方 —— **自维护一份补丁版 PAL 的成本极低**，
> 或者直接向 PAL 作者提一个"两个 API 改名"的 issue。第 1/3 两条出路都不必走。

### 4.5 存档 / 数据契约

- 女仆数据存 `<存档>/smartmaid/maids/`（NBT），任务队列持久化在 `tasks` 段。
- 跨版本读旧档必须**版本化 + 不硬读**：需确认 `tasks` 段是否已有 `version` / `stateVersion` 字段
  （`HANDOVER_MENU_QUEUE.md` §九 已把这条列为待落地项）→ **本轮一并落地**。

---

## 5. 工作分解（建议顺序）

> 原则：**先建立"能编译三版本"的骨架并量出真实差异，再谈功能**。
> 不要靠文档猜 diff——让编译器枚举，这才是权威清单。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0 基座** | 参数化 `gradle.properties` + 按版本注入 `fabric.mod.json`；建立三版本构建脚本 | 三版本都能 `./gradlew build -x test` 通过（先不修代码，**收集编译错误清单**） |
| **P1 差异台账** | 用 `probe_api.py` 对关键类做**逐版本 dump + diff**，把 §3 的"预期"换成"实测" | 产出一份"实测 diff 表"，覆盖 §3.2/§3.3 全部条目 |
| **P2 compat 层** | 新建 `compat/MaidCompat.java`，把 §3.2 的 6 处 + 5 个命令类收敛进去 | 26.1 / 26.2 两线编译通过 |
| **P3 26.3 攻坚** | 按 P1 实测清单处理渲染链 + PAL 决策（§4.4） | 26.3 编译通过 |
| **P4 真机验证** | 装 26.1 / 26.3 实例，逐版本跑 AutoTest | 每版本 `PASS/FAIL` 记录在 `tools/TEST_PLAN.md` |
| **P5 收尾** | `fabric.mod.json` 窄范围声明、CHANGELOG、发布文案（文案走 `release-notes/`，**不进仓库**） | 三版本各自产物 + 文档 |

---

## 6. 验证矩阵

| 层 | 手段 | 说明 |
|---|---|---|
| 编译 | 逐版本 `./gradlew build` | 差异的权威来源 |
| API | `python tools/probe_api.py <类> [关键字]`（查单版本签名）；`python tools/diff_api.py --a <jarA> --b <jarB> <类>...`（**量两版差异**） | 逐版本 dump/diff，**必须 JDK 25 的 javap**；loom 会把目标版本 jar 拉到 `.gradle/caches/fabric-loom/`。**26.1+ 自带去混淆，原版 jar 可直接 javap，无需 Loom** |
| 真机 | 26.2 已有实例；**26.1 / 26.3 需新装** | 本地 `versions/` 目前只有 `26.2` 与 `fabric-loader-0.19.3-26.2` |
| 功能 | `tools/autotest.queue-full.json` → `config/smartmaid/autotest.json` | 每版本都要跑，搜 `latest.log` 的 `AutoTest`（走 `LOGGER`） |
| 协议 | 775 / 776 / 777 | 跨版本联机/网络包的硬门槛 |

---

## 7. 风险清单（按严重度）

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | ~~**PAL 1.2.6 与 26.3 渲染重构冲突**~~ → **已实测降级**（§10.6） | 原本担心"26.3 整条卡住"，实测只差 **2 个成员调用** | ① 向 PAL 作者提 issue（两个 API 改名，极可能秒合）；② 或自维护补丁版放 `libs/`。**降级为常规任务** |
| 2 | 26.3 Renderpearl / SDL 迁移的隐性依赖（armor layer、`ModelLayers`、shader 资源） | 渲染崩溃 / 静默不生效 | P1 逐类 dump diff；26.2 铁律"渲染全走 `extract*`"同样适用——**写错方法名会静默不生效** |
| 3 | 宽范围通吃 jar → 协议不匹配**静默失败** | 用户侧难以诊断 | §4.3：一版本一 jar，窄范围声明 |
| 4 | 本地缺 26.1 / 26.3 环境 | 真机验证受阻 | P4 前先装好；HMCL token 过期需用户在 GUI 登录一次 |
| 5 | 整合包生态滞后（26.3 才发布 3 周） | 打包环境拿不到配套 mod | 26.3 定位为"支持"而非"主推"，主推仍是 26.2 |
| 6 | 配套 `deskpet-mod-2.0.0.jar` 也需同版本适配 | 联动功能跨版本断链 | 与桌宠侧（另一仓库）协调，列入 P5 |
| 7 | 数据包 tag 跨版本语义漂移（26.3 新增方块/物品） | `#smartmaid:junk` 等误判 | P1 复核四个 tag 的引用清单 |

---

## 8. 未验证 / 待决策（如实列出）

**待用户决策：**

1. **范围**：本轮是否就是 26.1.x / 26.2 / 26.3 三条线？（1.21.x 建议单独立项）
2. **产物形态**：一版本一 jar（本文推荐）还是尝试宽范围 jar？
3. **26.3 的 PAL 策略**：等 PAL 更新 / 降级为软依赖 / 自维护 fork？
4. 是否引入社区多版本工具（Stonecutter 类），还是自研条件注释裁剪？

**未核实（需在 P1 实测）：**

- 26.1 的 Loom / Gradle 推荐版本（本文表中标"待核实"）。
- 26.1 是否确实用 `setScreen`（本文依据 26.2 博客的 Gui 重组推导，**未经 26.1 jar 核实**）。
- `MaidDataManager` 的 NBT 是否已带 `version` / `stateVersion` 字段。
- PAL 1.2.6 在 26.1 / 26.3 的实际可用性。
- 26.3 对 `HumanoidArmorLayer` / `ArmorModelSet` 的泛型签名是否有变。

---

## 9. 调研中顺带发现（与本次任务无关，但需处理）

1. **部署的 jar 与构建产物不一致**：游戏 `mods/` 里是 **`smartmaid-0.1.1.jar`**（10-01 11:21），
   而 `build/libs/` 里的 **`smartmaid-0.1.2.jar`（10-01 11:44）从未部署**。
   若近期真机测试发现"改动没生效"，先查这里。（`mods/` 里还堆着 ~50 个历史 `.bak_*` 快照，建议清理。）
2. **`HANDOVER_MENU_QUEUE.md` 内部自相矛盾**：§二 状态表写"代码 ✅ 已实现"，但 §一 结尾仍写
   "两件都还没开工。"、§二 下方写"别在真机上找'新菜单' —— 它还不存在。"、§五 排程也是开工前口吻。
   菜单 `MaidMenuScreen` 与队列代码均已存在 → 这几处是漏改残留，会误导接手人。

---

## 10. 26.3 实地调研结果（2026-10-06，基于本机真实 jar）

### 10.1 调研条件（为什么这次能"实地"）

- 素材：`<.minecraft>/versions/26.3/26.3.jar`（41 MB 原版 jar，用户 2026-10-06 10:42 下载）+ 已缓存的
  `minecraft-merged-deobf-26.2.jar`。
- **26.3 自带去混淆（含参数名）→ 可以直接 javap，不需要 mappings，也不需要跑 Loom。**
  这意味着一份任意版本的 jar 入手即可量差异，是本轮方法的关键。
- 新增工具 **`tools/diff_api.py`**：对两个版本的同一批类做 `javap -p` 输出 diff，
  打印"仅 A 有 / 仅 B 有"的成员。后续 P1 差异台账直接用它。
- 环境限制与**绕行方案**：Gradle 针对 26.3 的真编译**未成功**——卡在构建期依赖
  （`io.netty:netty-*:4.2.16.Final` 的 POM 需从 Maven Central 取，本机**直连与现有代理均不通**，Fabric Maven 可通）。
  **但这不影响拿到全量结论**：既然 26.1+ 去混淆、不需要 mappings，就可以**直接用 javac 编译**
  （工具 `tools/compile_check.py`，自动从原版 jar + Fabric API 聚合 jar + libs + MC libraries 组装 classpath）。
  于是本轮仍然拿到了**完整、权威的编译错误清单**（见 §10.5），而不是只靠人工挑类。
  代价是必须人工剔除一个已知误报源（见 §10.5 开头）。

### 10.2 定向 diff 结果：26.2 → 26.3

| 类 | 结论 | 对本工程 |
|---|---|---|
| `Minecraft` | **`setScreenAndShow(Screen)` 两版都在**；变化在 Blaze3D→Renderpearl、SDL 事件处理、PalettedTextureManager、Tracy | ✅ 打开屏幕无需改 |
| `Screen` | `fillCrashDetails` 移除；`updateNarratorStatus` 加 `NarrationTrigger` 参数；新增 `isInputCaptured()` / `scheduleNarration()` | ✅ 本工程 override 的 `extractRenderState` / `extractBackground` **签名未变** |
| `GuiGraphicsExtractor` | `blit` / `blitSprite` / `fill` 的 **`RenderPipeline` 参数类型由 `com.mojang.blaze3d.pipeline.RenderPipeline` 改为 `com.mojang.renderpearl.api.pipeline.RenderPipeline`**（旧重载删除）；`textWithWordWrap` 返回 `void`→`int`；`tooltip` 新增 `boolean` | ⚠️ 本工程 4 个 GUI 文件用 `RenderPipelines.GUI_TEXTURED` 调 `blit`/`blitSprite`。**`RenderPipelines.GUI_TEXTURED` 在 26.3 仍存在**（只是其类型变成了新包），源码调用点**预期无需改**，待真编译确认 |
| `InventoryScreen` | **零差异** | ✅ `extractEntityInInventoryFollowsMouse` 可用（菜单/背包预览） |
| `HumanoidModel` | 仅新增 `setupSwimAnimation` | ✅ 纯新增 |
| `HumanoidRenderState` | **零差异** | ✅ 本工程 `SmartMaidRenderState` 的基类稳定 |
| `ArmorModelSet` | **零差异** | ✅ 初稿担心的盔甲层泛型变化**没有发生** |
| `HumanoidArmorLayer` | **零差异** | ✅ |
| `ModelLayers` | 仅新增 `CUSHION` / `POPLAR_BOAT` / `POPLAR_CHEST_BOAT` | ✅ 纯新增 |
| `HumanoidMobRenderer` | 1 个 `private` 方法移除 + 新增 1 个 `public static` | ✅ 无影响 |
| `Commands` | **零差异** | ✅ 权限 API 26.2↔26.3 **稳定** |
| `net.minecraft.server.permissions.PermissionCheck` | **存在且零差异** | ✅ |
| `TamableAnimal` / `Identifier` / `AbstractContainerScreen` | **零差异** | ✅ |
| `com.mojang.blaze3d.platform.InputConstants` | **26.3 仍存在** | ✅ `SmartMaidClient` 的 import 不用改 |
| `com.mojang.blaze3d.vertex.PoseStack` | **26.3 仍存在** | ✅ `SmartMaidRenderer` 的 import 不用改 |

> 即：**blaze3d 包迁移并没有把本工程用到的两个类带走**（`InputConstants`、`PoseStack` 都还在原包）。
> 26.3 的渲染重构主要打在更底层的 Blaze3D/GLFW 实现上，本工程"全走 `extract*`"的写法正好躲开了。

### 10.3 唯一的真实断点：`LivingEntity` 挥动/攻击动画 API 被重构

**26.3 删除：**

- 方法：`swing(InteractionHand)`、`swing(InteractionHand, boolean)`、`getAttackAnim(float)`、private `getCurrentSwingDuration()`
- 字段：`public boolean swinging`、`public InteractionHand swingingArm`、`public int swingTime`、
  `public float oAttackAnim`、`public float attackAnim`

**26.3 新增：**

- `swing(InteractionHand, SwingAnimation, boolean)` / `swingAndResetAttackStrength(InteractionHand, SwingAnimation, boolean)`
- `getCurrentSwing()` → `SwingDescription`、`getSwingAnimation(float)`、`isSwinging()`、内部 `SwingState`
- 物品组件 `DataComponents.ATTACK_ANIMATION` / `INTERACT_ANIMATION`（承载 `SwingAnimation`）

**对本工程的影响（已定位到文件与行号）：**

| # | 位置 | 现状 | 26.3 处置 |
|---|---|---|---|
| 1 | `CraftExecutor.java:183`、`MaidActions.java:462,467,639`、`MaidBlockBreaker.java:104,149,257`、`ChestOpenTask.java:64`（**共 8 处**） | `maid.swing(InteractionHand.MAIN_HAND)` | 改为 `swing(hand, <SwingAnimation>, true)`；建议收敛为一个 `MaidCompat.swing(maid, hand)` 助手，8 处只改调用名 |
| 2 | `SmartMaidEntity.java:657-699` `tickSwingAnim()` | **反射取 private `getCurrentSwingDuration`**，并**直写 `swinging` / `swingTime` / `attackAnim`** 三个字段（原意：普通 Mob 收到挥动包后 `attackAnim` 不推进，挖掘/攻击看不见挥动） | ⚠️ 三字段全删 → **编译期直接报错**，不是改签名而是**重新设计**。好消息：26.3 引入 `SwingState` + `getSwingAnimation(float)` + `isSwinging()`，**很可能原生解决了这个 hack 想解决的问题** → 该 hack 应**直接删除**而非移植（待真机确认原版是否已为普通 Mob 推进挥动） |

### 10.4 结论修正

- ❌ 初稿判断"26.3 服务端逻辑不受影响"**是错的**。真实情况：**渲染侧基本稳定，实体动画侧是真断点**。
- ✅ 26.3 线的实际工作量**比初稿预估更小、更集中**：`LivingEntity` 挥动 + （待确认的）PAL。
- ⚠️ **PAL 仍未验证**：本轮无法验证内嵌 PAL 1.2.6 在 26.3 的行为（需要能跑的 26.3 实例环境）。
  这仍是 26.3 线的**第一号风险**，且必须在 P0 阶段前置验证。

---

### 10.5 【权威】全量编译结果 —— 26.3 真实需要改的 21 处

方法：`python tools/compile_check.py --mc-jar <26.3.jar> --fapi-jar <fabric-api-0.161.0+26.3.jar> --libs-dir <.minecraft>/libraries`
（源码 125 个 `.java`；详见 `build_logs/mv_263_javac.txt`）

**javac 共报 30 个 error，其中 9 个是误报**：Fabric API 通过 **class tweaker** 开放了一批 vanilla 成员
（26.3 起 `.accesswidener` 已换代成 **`.classtweaker`**），而 javac 不会应用 classtweaker，于是
`MenuType` 构造、`MenuScreens.register`、`BlockPlaceContext` 构造被误报成 `has private/protected access`。
**已核实 Fabric API 26.3 的 classtweaker 仍然开放这三者**（`fabric-menu-api-v1.classtweaker`、
`fabric-transitive-access-wideners-v1.classtweaker`）→ 确认为误报，**不用改**。

**⇒ 真实错误 = 21 个，分布在 7 个文件：**

| # | 文件 | 处数 | 错误 | 26.3 处置 |
|---|---|---|---|---|
| 1 | `client/SmartMaidClient.java` | 1 | `cannot find symbol: KEYSYM`（`InputConstants.Type.KEYSYM`） | → `InputConstants.Type.KEYBOARD` |
| 2 | `client/SmartMaidClient.java` | 2 | `isKeyDown(Window, int)` 已改为 `isKeyDown(int)`（**去掉了 Window 参数**） | 删掉 `client.getWindow()` 实参 |
| 3 | `entity/SmartMaidEntity.java` | 8 | 直写已删除字段：`swingTime`×5 / `swinging`×2 / `attackAnim`×1 | `tickSwingAnim()` 重做（见 §10.3 第 2 条，倾向**直接删掉这个 hack**） |
| 4 | `entity/ai/MaidActions.java` | 3 | `no suitable method found for swing(InteractionHand)` | → `swing(hand, SwingAnimation, true)` |
| 5 | `entity/ai/MaidBlockBreaker.java` | 3 | 同上 | 同上 |
| 6 | `entity/ai/craft/CraftExecutor.java` | 1 | 同上 | 同上 |
| 7 | `entity/ai/maidtask/ChestOpenTask.java` | 1 | 同上 | 同上 |
| 8 | `entity/ai/maidtask/SmeltTask.java` | 2 | `cannot find symbol: FuelValues` + `fuelValues()` | ⚠️ **26.3 删除了 `net.minecraft.world.level.block.entity.FuelValues`，`Level#fuelValues()` 也没了**——燃料改为**物品组件**：`DataComponents.COOKING_FUEL`（类型 `net.minecraft.world.item.component.CookingFuel`，含 `burnTime` / `speedMultiplier`）→ `SmeltTask` 改判 `stack.has(DataComponents.COOKING_FUEL)` |

**8 处 `swing` 分布**（合计 8，与 §10.3 表格一致）：
`MaidActions:462,467,639`、`MaidBlockBreaker:104,149,257`、`CraftExecutor:183`、`ChestOpenTask:64`

**收敛建议（对应 §4.2 的差异隔离层）**：第 3/4 组（`swing` ×8）→ 一个 `MaidCompat.swing(maid, hand)`；
第 1/2 组（`InputConstants`）→ 一个 `MaidCompat.isKeyDown` / 键码常量；
第 8 组（燃料）→ 一个 `MaidCompat.isFuel(stack)`；
第 3 组（挥动状态）→ 26.3 侧走新 API、26.2 侧保留原 hack，**这处最适合放进 compat 边界**。

**⚠️ 方法学教训（重要）**：本轮"人工挑类 diff"（§10.2/§10.3）只覆盖了**预选的那批类**，
因此**漏掉了两个真实问题**：`InputConstants.isKeyDown` 的签名变更、`FuelValues` 被整体删除
（`MenuScreens.register` / `MenuType` / `BlockPlaceContext` 属 classtweaker 误报，不算漏）。
结论：**多版本差异必须以编译器枚举为准；人工挑类只能用来预判和解释，不能当作完整清单**。
这也正是 §5 把"让编译器枚举真实 diff"放在 P0 的原因。

---

### 10.6 一号风险落地：PAL 1.2.6 ↔ 26.3 静态核验（结论：只差 2 个调用）

**为什么要单独查**：PAL 的 `fabric.mod.json` 写的是 **`"minecraft": ">=26.2"`（无上界）** ——
Loader 会**照单放行**，若真不兼容，失败形态是**用到动画时才 `NoSuchMethodError`**（最坏那种）。
所以必须在放进 mods 之前静态量完。

工具：`tools/check_dep_compat.py`（本轮新增）—— 解析 PAL 每个 class 的**常量池**，
抽出它引用的外部类型/成员，再对目标版本 `javap -p -s` 核对（**沿父类/接口链解析**）。

| 核对 | 26.2（基线） | 26.3 |
|---|---|---|
| 引用的游戏/加载器侧类型 | 84 个，**全部存在** | 84 个，**全部存在** |
| 引用的成员 | 209 个，**全部匹配** | 207 匹配 / **2 不匹配** |

**26.3 上唯二不兼容的两个调用：**

| PAL 调用 | 26.3 现状 | 等价改写 |
|---|---|---|
| `PoseStack.mulPose(Quaternionfc)` | 已改名（`mulPose(Matrix4fc)` / `mulPose(Transformation)` **仍在**，只有 Quaternion 那个改了名） | → `PoseStack.rotate(Quaternionfc)`（**1:1 改名**） |
| `ResourceManager.listResources(String, Predicate<Identifier>)` | 第二参由 `Predicate` 换成 `ResourceManager$Selector` | → 构造 `ResourceManager$Selector`（语义相同，写法调整） |

**结论与建议：**

- **26.3 线的第一号风险基本解除**：PAL 不是"整条卡住"，而是 **2 处、可精确改写**。
- 推荐顺序：① 先向 PAL 作者提 issue（改动就两个 API 改名，作者大概率秒合）；
  ② 若等不及，**自维护一份补丁版 PAL** 放 `libs/`（成本极低，但要记住：**patch 需要跟上游同步**，
  要写下补丁清单，避免下次升级时丢）；
  ③ ~~降级为软依赖~~ —— 已证明不必要。
- ⚠️ 仍**未**验证的是**行为级**（签名对 ≠ 行为对）：26.3 的渲染后端换成 Renderpearl/OIT 后，
  动画的实际渲染效果是否一致，只能真机看。静态核验到此为止。

**顺带**：本轮已把 `fabric-api-0.161.0+26.3.jar` 与 `player_animation_library-1.2.6.jar` 放进
`<.minecraft>/versions/26.3/mods/`（该实例是**版本隔离**的独立游戏目录，`mods/` 原本为空）。
重启后可确认 PAL 的**加载期**健康度；若它的资源加载器在启动时就调 `listResources`，
则能直接看到 `NoSuchMethodError`。

**⚠️ 实例形态提醒**：HMCL 开了**版本隔离** —— 26.3 的 `gameDir = <.minecraft>/versions/26.3`
（各自独立的 `mods/` `saves/` `logs/`）。所以：
- 部署 26.3 产物要放 **`versions/26.3/mods/`**，不是 `.minecraft/mods/`（后者是 26.2 在用的）。
- `tools/local_paths.json` 的 `minecraft_dir` 指向 `.minecraft`，**多版本部署脚本需要按版本拼路径**。

---

## 11. 专题：一份 jar 跨版本兼容的可行性

> 用户明确要求探讨"同一份 jar 兼容的情况"。结论：**26.2 ↔ 26.3 不可行；26.1 ↔ 26.2 可行；三版本合一不可行。**

### 11.1 判定表

| 组合 | 单 jar 可行性 | 依据 |
|---|---|---|
| **26.1 + 26.2** | ✅ **可行** | 差异只有 2 处、都是窄接口：`Minecraft.setScreenAndShow` vs `setScreen`；`Commands.hasPermission(PermissionCheck)` vs `int` 等级制。都可用"运行时类选择"边界隔离 |
| **26.2 + 26.3** | ❌ **不可行** | 本工程自身就有 4 类**删除/改签名**：`LivingEntity` 挥动方法+字段被删、`FuelValues` 整类被删、`InputConstants.isKeyDown` 签名变更、`KEYSYM` 改名。全都要落在"运行时类选择"边界内；且**内嵌 PAL 无法按版本切换**（见 §11.2-5） |
| **26.1 + 26.2 + 26.3** | ❌ **不可行** | 上述两条的并集 |

### 11.2 技术机理（为什么"删了一个方法"就足以否掉单 jar）

1. **Java 字节码是符号链接，不是延迟绑定到位。**
   类 A 调用 `LivingEntity.swing(InteractionHand)`，26.3 上该方法不存在 →
   `NoSuchMethodError` **在该指令首次执行时**才抛，**不是类加载时**。
   于是失败形态是"**平时能用，挖到矿那一刻崩溃**"——最难排查的一类问题。
2. **字段删除无法用反射补救语义。**
   反射能"访问"字段，但 `attackAnim` 在 26.3 已被 `SwingState` 体系取代，
   不存在可写入的等价物 → 只能重写逻辑，而重写就意味着两份代码路径。
3. **放宽 `minecraft` 版本谓词 = 主动放弃 loader 的保护。**
   Fabric Loader **不做 mod 版本握手**（不像 Forge 会列出 Missing Mods）。
   宽范围 jar 一旦在某版本上"能加载"，网络/协议不匹配（775 / 776 / 777）就是**静默失败**，
   用户侧几乎无法归因。这也是 §4.3 坚持"一版本一窄范围"的根本原因。
4. **`fabric-api` 依赖下限被迫下探。**
   单 jar 必须声明最低可用 Fabric API（26.1 的 `0.155.3+26.1.2`），
   等于在 26.3 上按 26.1 的 API 契约运行。
5. **第三方二进制对"单 jar"仍是障碍（但对多 jar 已不是）。**
   实测（§10.6）PAL 1.2.6 与 26.3 只差 **2 个成员调用**，`libs/` 里备一份补丁版即可让 **26.3 单独构建**通过
   —— 所以它**不再是多版本方案的硬阻塞**。
   但对**单 jar** 它依然是坎：**Fabric 把 PAL 当独立 mod 加载**（它自己声明 `minecraft: ">=26.2"`），
   不受你的类加载器控制 —— 你无法在同一个 jar 里按版本切换它。
   注：它的开放版本范围还意味着 **Loader 会放行、失败推迟到"用到动画那一刻"**，属于最难排查的形态。

### 11.3 若坚持单 jar，唯一像样的技术路线：运行时类选择

- **做法**：按版本各写一份实现，放在不同包，实现同一个接口：
  - `compat/api/MaidPlatform.java`（接口，所有版本共用）
  - `compat/v261/MaidPlatformImpl.java`、`compat/v262/...`、`compat/v263/...`
  - 启动时探测运行版本，`Class.forName(...)` **只加载匹配的那一份**，其余类永不解析 → 规避 `NoSuchMethodError`。
- 这是"通用 jar"的标准做法，**技术上成立**，但代价是：
  每一个跨版本差异点都必须在类选择边界之内，边界之外一丁点泄漏（一个字段、一个签名）就会在全版本上崩。
- **反射封装（每个调用点都反射）**：可行但**不推荐** —— 慢、无编译期校验、异常信息不可读，
  与本工程"不引入不可静态验证的路径"的一贯做法冲突（同"移动走原版体系、不重写物理"的精神）。
- **Multi-Release JAR**：按 **Java 版本**分支，**不按 MC 版本** → **本场景不适用**。

### 11.4 什么条件下单 jar 才成立（判据清单）

1. 所有跨版本 API 变化都只是**新增**（旧成员仍在）→ 天然成立；
2. 任何**删除 / 改签名**都必须落在"运行时类选择"边界内；
3. 所有**第三方二进制依赖**本身支持多版本（或可被软依赖化）。

本工程：(1) 部分满足（GUI / 模型 / 权限 / 屏幕全是纯新增或零差异，非常好）；
但 **(2) 被 `LivingEntity` 挥动 API 的删除打破、(3) 被 PAL 打破** → **单 jar 不成立**。

### 11.5 成本对比与建议

| 方案 | 产物数 | 差异隔离成本 | 失败形态 | 判定 |
|---|---|---|---|---|
| **一版本一 jar（同一份源码，按版本切构建）** | 3 | 低（**编译器保证**不漏） | 编译期报错 | ✅ **推荐** |
| 26.1+26.2 合一个 jar，26.3 单列 | 2 | 中 | 编译期 + 1 处边界 | ⚪ 可接受的折中 |
| 三版本合一个 jar | 1 | 高（类选择层 + 反射兜底） | ⚠️ **运行期静默崩溃 / 网络不匹配** | ❌ **不推荐** |

**建议**：按 §4 的"一份源码 + 按版本切构建 + `compat/MaidCompat`"，产出**每版本一个窄范围 jar**
（`smartmaid-0.2.0+mc26.1.jar` / `+26.2` / `+26.3`）。用户下载时按自己的游戏版本选一个 —— 这也是
CurseForge / MC 百科都能正常表达的形态（同一文件可挂多个游戏版本标签，但**每个 jar 只声明它真正支持的那一个**）。

---

## 12. 实施进度：26.3 改造已落地（2026-10-06）

> 目标范围：**先兼容 26.3**（用户指定）。26.2 行为保持不变。

### 12.1 已完成的改动

| # | 内容 | 文件 |
|---|---|---|
| 1 | **多版本构建骨架**：`src/main/java`（共享）+ `src/mc<series>/java`（版本专属），由 `gradle.properties` 的 **`mc_series`** 切换，默认 `26.2`（现有开发流不变） | `build.gradle`、`gradle.properties` |
| 2 | `fabric.mod.json` 版本声明参数化：`minecraft` / `fabric-api` / 描述里的版本号按构建注入（每版本声明自己的**窄范围**） | `fabric.mod.json`、`build.gradle` 的 `processResources` |
| 3 | **差异隔离层 `MaidCompat`**：26.2 / 26.3 各一份同名类，签名一致、实现不同 | 新增 `src/mc26.2/java/.../compat/MaidCompat.java`、`src/mc26.3/java/.../compat/MaidCompat.java` |
| 4 | 共享代码 21 处调用点收敛到 `MaidCompat` | `SmartMaidEntity`（挥动推进）、`MaidActions`／`MaidBlockBreaker`／`CraftExecutor`／`ChestOpenTask`（swing ×8）、`SmeltTask`（燃料）、`SmartMaidClient`（键位 ×3） |

`MaidCompat` 覆盖的 5 类差异：**swing** / **客户端挥动推进** / **燃料判定** / **键位类型** / **isKeyDown 签名**。

### 12.2 验证结果（工具：`tools/compile_check.py`）

| 目标 | 结果 |
|---|---|
| 26.2（回归） | ✅ **0 个真实错误**，162 个 class |
| 26.3 | ✅ **0 个真实错误**，162 个 class |
| 产物完整性 | `zipfile.testzip()` 通过；`MaidCompat.class` 在包内；PAL 已 jar-in-jar 内嵌 |

**26.3 测试包已生成并部署**：`build_logs/out/smartmaid-0.1.2-mc26.3-dev.jar`
→ `<.minecraft>/versions/26.3/mods/`（该实例是**版本隔离**目录）。

⚠️ **部署铁律**：SmartMaid 的 jar 已内嵌 PAL，**不能再把独立的 `player_animation_library-*.jar` 放进同一个 mods/**
（两个同 id 的 mod → 加载失败）。本轮已把之前放进去的独立 PAL 删除。

### 12.3 本轮的关键技术发现（踩坑记录）

1. **`javac` 遇到错误会中止**，class 文件只产出中止前已生成的**那几个** —— 实测 126 个源文件只出 1 个 class。
   所以"能编译检查出 0 错误"≠"能拿到完整产物"：必须先把错误消干净才能打包。
2. **Fabric 的 classtweaker 必须被"应用"**，否则那批被开放的 vanilla 私有成员对 javac 就是**硬错误**。
   Loom 在准备编译 classpath 时会应用 CT；我们的纯 javac 路径必须自己复刻：
   `tools/compile_check.py` 现在会**自动两趟编译** —— 第一趟编出访问错 → 按 CT 生成
   **编译用补丁 jar**（把开放成员在字节码层面改成 public，含嵌套类型的 `InnerClasses` 属性）
   → 放到 classpath 最前面再编一次。
   ⚠️ 这个补丁 jar **只在编译期用**，运行时由 Fabric 自己应用 CT。
3. **写 class 文件改写工具的两个坑**（都实测踩到）：
   - 成员的 `attributes_count` 在 `acc+6`；若先 `off += 8` 再交给"读属性计数"的辅助函数，
     会把**下一个成员的 access_flags** 当成 attributes_count → 偏移直接跑飞（报"buffer of at least 3028078 bytes"）。
   - **嵌套类型的可访问性由外层类的 `InnerClasses` 属性决定**，光改它自己 `.class` 的 access_flags 不够；
     而且 javac 的报错只提到外层类名（`ScreenConstructor has private access in MenuScreens`），
     所以匹配时**嵌套类型要按外层类名匹配**。

4. **源码里的 `fabric.mod.json` 不含 `jars` 数组** —— 那是 **Loom 打包时注入**的（来自 `include` 配置）。
   自己打包时若只把嵌套 jar 塞进 `META-INF/jars/` 却**没写 `jars`**，Fabric 不会加载它 →
   加载器直接报 **「模组 'Smart Maid' 需要 player_animation_library 的 1.2.6 及以上版本，但没有安装它！」**
   （实测被这个卡过一次，真机上第一眼就是这个错）。
   **教训**：自研打包路径必须**拿真实 Loom 产物的 `fabric.mod.json` 逐字段对照**——
   这类"元数据缺失"是纯静态检查（编译、依赖核验）**查不出来**的。
   现在 `package_jar` 会按 `--include-jar` 自动注入 `jars`，并在输出里回显，避免再漏。

### 12.4 未做 / 待验证（当时状态，**已被 §13 推进**）

> ⚠️ 本节是 12:03 的**快照**。真机第一跑发生在 12:44，结论见 **§13**。保留原文是为了留痕。

- ⚠️ **真机未验证**：本轮只做到"编译通过 + 打包 + 部署"，**没有跑过游戏**。
  → **已跑**（§13.1）：崩了，根因是 PAL 1.2.6 的 Mixin（**不是**本节预测的两个 API）。
- ⚠️ **PAL 的 2 处不兼容尚未修**（§10.6）：测试包里内嵌的仍是 26.2 版 PAL 1.2.6。
  预期表现：一旦 PAL 走到 `PoseStack.mulPose(Quaternionfc)` 或
  `ResourceManager.listResources(String, Predicate)` 就会 `NoSuchMethodError`。
  其中 `listResources` 的**参数类型变了**，不是改个名就能修 —— 需要 PAL 官方出 26.3 版，
  或对 PAL 做真正的字节码/源码级改写。动画相关功能（`/maidanim`、Emotecraft 动作）在 26.3 上**预期不可用**。
  → **已修**（§13.3）：官方 26.3 版 `1.2.7+mc.26.3` 存在，直接换用，无需改写。
  另外实测的崩溃点**不是 `NoSuchMethodError`，而是 Mixin 注入失败** —— 形态与本节的预测不同。
- ⚠️ **`MaidCompat.tickSwingAnim` 在 26.3 是空实现**（判断原版 `SwingState` 已统一处理）——
  属**推测**，需真机确认"普通 Mob 的挥动是否可见"。若不可见，按 26.3 新 API 重写，**不要**退回反射写字段。
  → **仍未验证**（§13.7）。
- 26.1 线未动（缺 26.1 的 jar，差异未实测）。
- Gradle 侧的多版本构建（`-Pmc_series=26.3`）**未实跑过** —— 仍卡在 Maven Central 依赖下载（§10.1）。
  目前多版本产物一律走 `compile_check.py --package` 这条纯 javac 通路。
  → Maven Central 现已可**直连**（HTTP 200），待补跑。

---

## 13. 真机第一跑：PAL 1.2.6 崩溃的根因与修复（2026-10-06 12:05）

### 13.1 真机结果（第一次重启）

日志确认**前三件事都对了**：

```
Loading Minecraft 26.3 with Fabric Loader 0.19.5
Loading 53 mods: … - smartmaid 0.1.2
                   \-- player_animation_library 1.2.6+mc.26.2   ← jar-in-jar 被正确识别
[Render thread/INFO]: 已生成默认配置: …config\smartmaid\main.json
[Render thread/INFO]: Smart Maid initialized (debug=false, verbose=false)
```

即：**多版本骨架、`MaidCompat` 21 处收敛、`jars` 声明修复全部生效** —— Fabric 认出了内嵌 PAL，
SmartMaid 自己的入口点也跑到了初始化完成。

崩溃发生在**紧接着加载 `ItemInHandLayer` 时**，是 **PAL 自己的 Mixin 应用失败**：

```
Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError:
  Critical injection failure: Callback method changeItemLocation(…)V in
  player_animation_library.mixins.json:ItemInHandLayerMixin from mod player_animation_library
  failed injection check, (0/1) succeeded. Scanned 0 target(s). No refMap loaded.
```

### 13.2 根因（全部实测，不是推测）

| PAL 1.2.6 的注入 | 26.3 现状 | 严重度 |
|---|---|---|
| `ItemInHandLayerMixin` @At → `PoseStack.mulPose(Lorg/joml/Quaternionfc;)V` | 方法被改名（`rotate*` 一系） | ❌ **致命**（启动即崩） |
| `CapeLayerMixin` @At → `SubmitNodeCollector.submitModel(…8 参含 CrumblingOverlay)` | 签名变回 7 参 | ❌ **致命** |
| `firstPerson.ItemInHandRendererMixin` 目标类 `ItemInHandRenderer` | **整个类被删** | ⚠️ 仅 WARN（Mixin 跳过） |

之所以「致命」而非「跳过」：PAL 的 `mixins.json` 是 `required=true` +
`injectors.defaultRequire=1` —— 注入点找不到就抛异常，而不是降级。

**顺带纠正上一版的两处说法**：
- §10.3 说「唯一的真实断点是 `LivingEntity` 挥动」—— 那是**我们自己代码**的断点；PAL 侧还有独立的两处。
- §10.6 用 `check_dep_compat.py` 得出「PAL 只差 2 个成员」—— 结论本身没错（`mulPose` 正是其中之一），
  但**不完整**：该工具只看常量池成员引用，**看不到 Mixin 注解里的注入目标**（见 §13.5）。

### 13.3 修复：换 PAL 官方 26.3 版（1.2.7+mc.26.3）

不需要任何降级/打补丁 —— **官方在 2026-09-16 就发了 26.3 版**：

| | 26.2 线 | **26.3 线** |
|---|---|---|
| PAL | `1.2.6+mc.26.2` | **`1.2.7+mc.26.3`**（Modrinth / CurseForge） |
| `depends.minecraft` | `>=26.2` | `>=26.3`（正好对上） |
| `depends.fabricloader` | `>=0.19.3` | `>=0.19.5`（本实例就是 0.19.5） |
| mixin client 数 | 14 | **15**（新增 `firstPerson.FirstPersonHandsAndItemsMixin`，对应 26.3 新类 `FirstPersonHandsAndItems` / `FirstPersonHandsAndItemsRenderer`） |
| 表达式引擎 | `team.unnamed.mocha.MochaEngine`（mochafloats 5.0.0） | **`org.redlance.mocha.runtime.MolangInterpreter`**（runtime/parser/lexer 6.0.1） |

**PAL 换了内嵌表达式引擎的包名**，所以我们的代码不能只换 jar —— `MaidAnimManager` 里那行
`c -> team.unnamed.mocha.MochaEngine.create(c)` 会编不过。工厂方法 `create(T)` 语义完全一致
（纯改名），于是新增一个隔离层把它挡掉：

```java
// src/mc26.2/java/.../compat/MaidAnimCompat.java
public static <T> team.unnamed.mocha.MochaEngine<T> createEngine(T entity)
// src/mc26.3/java/.../compat/MaidAnimCompat.java
public static <T> org.redlance.mocha.runtime.MolangInterpreter<T> createEngine(T entity)
```

调用点（共享代码）**不引用任何一侧的类型**：

```java
new HumanoidAnimationController(handler, c -> MaidAnimCompat.createEngine(c))
```

lambda 的返回类型由泛型方法按实参推导，再交给**当版本**的构造器匹配 —— 于是「包名换了」
完全被隔离在一个文件里。

### 13.4 依赖目录按版本分

```
libs/                             # 共用 + 26.2 线
  player_animation_library-1.2.6.jar
  mochafloats-5.0.0.jar
libs/mc26.3/                      # 26.3 线专属
  player_animation_library-1.2.7.jar
  mocha-runtime-6.0.1.jar         # ← 从 PAL 1.2.7 的 jar-in-jar 里抽出（仅编译需要）
  mocha-parser-6.0.1.jar
  mocha-lexer-6.0.1.jar
```

- `build.gradle`：`flatDir { dirs 'libs', "libs/mc${mcSeries}" }`；PAL 版本按 `mcSeries` 选
  （26.1/26.2 → 1.2.6，26.3 → 1.2.7），mocha 依赖同理；`fabric.mod.json` 的
  `player_animation_library` 范围也改为按版本注入（26.3 产出 `>=1.2.7`）。
- mocha 三件套**只声明 `implementation`、不 `include`** —— 运行时有 PAL 自己的 jar-in-jar 提供，
  再 include 一次会 mod id 冲突。

### 13.5 工具盲区暴露 + 新工具 `tools/check_mixin_targets.py`

**这次崩溃我们上一轮没预测到，原因是工具盲区**：`check_dep_compat.py` 解析的是**常量池里的
类型/成员引用**，而 Mixin 的注入信息全在**注解字符串**里（`@Mixin` 目标类、`@At(target="Lowner;name(desc)ret")`）——
既不是成员引用也不是普通调用，所以扫不到。

于是补了 `tools/check_mixin_targets.py`：按**规范 class 文件结构**读类级注解与常量池，抽出
`@Mixin` 目标类与注入点成员，对目标版本逐个核验，并**区分严重级别**：

- 目标类缺失 → ⚠️ 警告（Mixin 会跳过，游戏仍能起）
- 注入点成员缺失 且 `required=true`／`defaultRequire>=1` → ❌ **致命**（启动崩溃）

实测判别力（同一工具、同一 jar，只换目标版本）：

| 核验对象 | 26.3 | 26.2 |
|---|---|---|
| PAL **1.2.6** | ❌ 2 致命 + ⚠️ 1 警告（**与真机日志逐条对上**） | — |
| PAL **1.2.7** | ✅ 0 致命 / 0 警告（15/15 可解析） | ❌ 2 致命 + ⚠️ 2 警告 |

→ 后者说明 1.2.7 是**真正为 26.3 编的**，不是「碰巧不报错」。

**写这个工具踩的两个坑（都已内建修正，写进 skill）**：
1. 别用「扫一遍 Utf8 串」的偷懒办法抽 `@Mixin` 目标 —— 会把方法描述符等无关 `L…;` 串也当目标。
   本工具第一版因此把**真正会崩的** `ItemInHandRendererMixin` 误报成「可解析」。必须按注解的
   确定结构读。
2. **存在性判定只能信目标 MC jar**。本机 `.minecraft/libraries` 里躺着 HMCL 装的
   **NeoForge 21.1.51 的 `client-1.21.1-srg.jar`**（含 `ItemInHandRenderer`）—— 把 libraries
   整目录当索引，就会把「26.3 已删的类」判成「存在」。`--libs-dir` 只用于给 javap 解析第三方类。

### 13.6 本轮改动清单

- **新增** `src/mc26.2|mc26.3/java/com/oyxdsg/smartmaid/compat/MaidAnimCompat.java`（表达式引擎隔离）
- **改** `MaidAnimManager.java`：`MochaEngine.create` → `MaidAnimCompat.createEngine`
- **改** `build.gradle`：PAL/mocha 依赖按 `mcSeries` 选；`flatDir` 加版本专属目录；`pal_range` 注入
- **改** `fabric.mod.json`：`player_animation_library: "${pal_range}"`
- **改** `tools/compile_check.py`：新增 `--pal-range`；`find_jdk_tool` 挪到 `tools/_paths.py` 共用
- **新增** `libs/mc26.3/`（PAL 1.2.7 + 抽出的 mocha 6.0.1 三件套）
- **新增** `tools/check_mixin_targets.py`（Mixin 注入目标静态核验）
- **部署** `versions/26.3/mods/smartmaid-0.1.2-mc26.3-dev.jar`（163 class，内嵌 PAL 1.2.7）

双向编译验证：**26.2 与 26.3 各 0 真实错误**。

### 13.7 仍未验证（如实列出）

1. **真机第二次重启还没跑** —— 本节的静态结论尚未被运行期证实。
2. `MaidCompat.tickSwingAnim` 在 26.3 仍是**空实现**（依据：26.3 用 `SwingState` 统一处理挥动，
   那批字段与私有方法全删）。**这是推测**，需真机看「普通 Mob 挥动是否可见」。
3. PAL 在 26.3 的**渲染效果**是否与 26.2 一致（即 1.2.7 换了新渲染管线后动画对不对），
   静态核验管不到，只能肉眼看。
4. `check_mixin_targets.py` 只能证明「**没发现已知断点**」，不能证明注入点在方法体内真的命中。
5. Gradle 侧多版本构建仍未实跑（原阻塞的 Maven Central 现已可直连，待补跑）。

---


## 14. 真机第二、三跑：GUI 失效与一次崩溃的根因（2026-10-07 00:20）

### 14.1 结论先行

26.3 **迁移主体成功**（PAL 1.2.7 的 Mixin 全过、进世界、`eat` 任务下发成功）。
但用户报了两个 GUI 问题、并给来一个崩溃包，根因是**同一个变化的两个面**：

> **26.3 把 GLFW 换成了 SDL，键盘与鼠标的"键值编码"整套都变了。**

| 项 | 26.2（GLFW） | **26.3（SDL）** | 受影响的功能 |
|---|---|---|---|
| `InputConstants.KEY_E` | 69 | **8** | Shift+E 开背包（**完全没反应**） |
| `KEY_LSHIFT` / `KEY_RSHIFT` | 340 / 344 | **225 / 229** | Shift 判定（恒 false） |
| `Type` 枚举 | `KEYSYM`/`SCANCODE`/`MOUSE` | **`KEYBOARD`**/`MOUSE` | KeyMapping 注册 |
| `key.mouse.left` | **0** | **1** | **所有自绘按钮的左键点击** |
| `key.mouse.right` / `middle` | 1 / 2 | 3 / 2 | 同上 |

**证据（全部来自本机 jar 的字节码，不是推测）**：

- `26.3.jar` 引用 `org/lwjgl/glfw/GLFW` 的类 = **0 个**；引用 `org/lwjgl/sdl/SDL` = **27 个**。
- vanilla `Options` 给 `key.forward` 用 `bipush 26`（= `SDL_SCANCODE_W`；26.2 是 `GLFW_KEY_W`=87）。
- `Type.MOUSE` 的 `addKey` 表：26.2 `left=0/right=1/middle=2`，26.3 **`left=1/middle=2/right=3`**。
- `SDLEventHandler.handleMouseButtonEvent` 是
  `new MouseButtonInfo(SDL_MouseButtonEvent.button(), SDL_GetModState())` —— **SDL 键号直通**，
  与平台常量 `SDLMouse.SDL_BUTTON_LEFT = 1` 对上。

### 14.2 症状 → 根因 → 修法

| 症状 | 根因 | 修法 |
|---|---|---|
| **背包（Shift+E）完全没反应** | `GLFW.GLFW_KEY_E`(=69) 在 26.3 是 SDL scancode **8** → 注册成了无关的键，且 Shift 判定恒 false | 统一改用 **`InputConstants.KEY_E / KEY_LSHIFT / KEY_RSHIFT`**（两版**同名**，值各自正确 → **不需要 compat 分支**） |
| **主菜单能开，但点「任务队列」等一切按钮都没反应** | 自绘 `mouseClicked` 里写的是 `if (event.button() != 0) return;` —— **26.3 左键是 1**，于是每次左键都被当成"非左键"丢掉 | 新增 `MaidCompat.primaryMouseButton()` / `isPrimaryMouseButton(int)`，5 处硬编码全部改走它 |
| **崩溃：`Cannot send packets when not in game!`** | `MaidMenuScreen.tick()` 每 20t 发 `query`，不检查连接；而 Screen 会被 `Gui.tick()` 持续驱动 | 新增 **`client/gui/MaidNet.send(...)`** —— 无连接时**静默丢弃**；GUI 里 18 处发包全部改走它 |
| （同一崩溃的另一半原因） | **本模组自己的客户端测试运行器**在**标题画面**就执行了 `openMenu`（标题画面也收 `ClientTickEvents`），20 tick 后触发上面的发包 | 运行器加门：**必须 `level != null && getConnection() != null`** 才开始；未进世界时往错误通道写一条 INFO 说明 |

**注意第二个坑的隐蔽性**：它不会崩、不会报错，只是"点了没反应"，
而且**主菜单本身能开**（那是服务端发包 + `setScreenAndShow`，不经过点击判断）——
所以现象看起来像"二级页打不开"，很容易往 `Screen` 注册/渲染方向查错方向。
（本次已用 `diff_api` 排除：`Screen` 的输入/渲染契约只多了旁白相关方法；
`Minecraft.setScreenAndShow` 两版**字节码完全一致**。）

### 14.3 本轮改动

- **新增** `client/gui/MaidNet.java`（安全发包；无连接即丢弃）
- **新增** `MaidCompat.primaryMouseButton()` / `isPrimaryMouseButton(int)`（两版本各一份）
- **改** `SmartMaidClient`：键位改用 `InputConstants.KEY_*`，删除 `GLFW` 依赖；
  「Shift+E 开背包」抽成可测的 `tryOpenMaidInventory()`
- **改** 5 个 GUI 文件：鼠标左键判断（5 处）+ 发包（18 处）
- **改** `client/test/MaidClientTest`：加「必须已在世界中」的门；合成点击用 `MaidCompat.primaryMouseButton()`
- **新增** `MaidCompat.isAnyKeyDown(int...)`（左右 Shift 任一）

验证：26.2 / 26.3 **双向编译 0 真实错误**（130 源文件）；
`verify_all.py` 两线全绿；离线单测 **33 用例全绿**；26.3 包已重新部署（167 class）。

### 14.4 真机验证结果（2026-10-07 00:31）—— 本轮两个修复均已实机证实

`clienttest gui-chain` **29/29 PASS**，用时 2578ms，整局日志零新错误：

```
00:30:37  clienttest  检测到 clienttest.json，但当前不在世界中 —— 进世界后会自动开始
00:30:57  clienttest  开始客户端测试: gui-chain（29 个用例）
00:30:59  clienttest  客户端测试结束: PASS=29 FAIL=0 ERROR=0
```

环境快照（最有价值的一段）：

```
inWorld=True  screen=(null)  nearestMaid=-1
键位: key.keyboard.e  value=8  type=KEYBOARD  shiftKeys=[225, 229]
```

- **鼠标左键 = 1 已实机证实**：所有合成点击 `handled=true`，含
  `入口#0 @(286,41) rect=[195,30,182,22] → Screen=MaidTaskScreen`
  —— 即 **"打开主菜单后点任务队列打不开"已修复**。
- **键盘注册已实机证实**：`key.keyboard.e / value=8 / type=KEYBOARD`，与 vanilla 自身注册形式一致。
- **"必须已在世界中才跑"的门按设计生效**：先在标题画面记一条 INFO 等待，进世界后才开跑。
  （错误通道里那 4 条 `Cannot send packets...` 时间戳是 00:13，属**上一次崩溃**的历史记录，本轮 0 条。）

### 14.5 自动化覆盖不到的两条路径 —— 已人工实测通过（2026-10-07 00:31）

`gui-chain` 跑完后，用户**手工操作**补验了自动化覆盖不到的两条路径，**均通过**：

| 路径 | 验证形式 | 结果 |
|---|---|---|
| **有女仆的 GUI 路径**（3D 预览 / 装备概览 / 任务队列里有数据） | 用户手工操作：聊天栏 `summon` 召唤女仆后打开菜单逐项点 | ✅ 通过 |
| **真实物理 Shift+E 开背包** | 用户手工按键 | ✅ 通过 |

证据（游戏侧痕迹，非推测）：`00:31:06` 执行 `summon` → 回显「女仆已召唤」；
`00:32` 正常退出并存档，落盘 `saves/新的世界/smartmaid/maids/5fc5b52a-4356-44ba-a594-a5b925f2c77d.dat`。

> ⚠️ **必须区分"功能已通过"与"自动化已覆盖"**：上表两行是**人工实测**，**不是 `clienttest` 的结果**。
> 这两条路径**至今仍自动化覆盖不到**：
> - `gui-chain` 跑的时候 `nearestMaid=-1`、菜单以 `state=null` 打开（`state` 非空的分支没走到）；
> - `key-encoding` 探针需要**人工按住 Shift+E**，本身就没跑。
>
> 要让脚本自己覆盖，得给 `gui-chain` 加前置步骤（进世界后先 `summon` 保证 `nearestMaid >= 0`），
> 并接受 `key-encoding` 这类"必须真人按键"的用例**天然无法全自动**这一事实。

### 14.6 仍未验证（如实列出）

1. **`MaidCompat.tickSwingAnim` 在 26.3 仍是空实现**（推测 26.3 用 `SwingState` 统一处理）——
   **未经真机确认"普通 Mob 的挥动是否可见"**。若不可见，按 26.3 新 API 重写，
   **不要**退回反射写字段（那些字段在 26.3 已不存在）。
2. 26.3 下 PAL 动画的**渲染效果**是否与 26.2 一致（换了 Renderpearl/OIT 后端），只能肉眼看。
3. **26.2 线尚未部署**带这些修复的包（只有 26.3 实例被验过）。
4. `clienttest` 的**自动化覆盖率仍不完整**（见 §14.5 说明与 `TEST_SYSTEM.md` §6）。

---

## 附录：扫描用的正则


```
# A 注册表 / 重命名族（26.1 / 26.3）
FuelRegistry|CompostingChanceRegistry|CompostableRegistry|StrippableBlockRegistry|TillableBlockRegistry|
FlattenableBlockRegistry|FabricPotionBrewingBuilder|PointOfInterestHelper|ItemGroupEvents|CreativeModeTabEvents|
KeyBindingHelper|KeyMappingHelper|EntityModelLayerRegistry|ModelLayerRegistry|ColorProviderRegistry|
ParticleFactoryRegistry|ParticleProviderRegistry|WorldRenderEvents|LevelRenderEvents|ScreenHandler|valueLookupBuilder

# B 网络
PacketByteBufs|FriendlyByteBuf|createC2SPacket|createS2CPacket|PayloadTypeRegistry|CustomPayload

# C GUI / 渲染
setScreen|getScreen\(\)|\.gui\.|GuiGraphicsExtractor|extractRenderState|extractBackground|extract[A-Za-z]*\(|
extractEntityInInventory|TextureAtlasSprite|OrderedSubmitNodeCollector|HumanoidModel|EntityRenderState

# D 数据 / 权限 / 其他
BlockIds|ItemIds|BlockItemIds|DataComponents|SavedDataStorage|DimensionDataStorage|FakePlayer|
PermissionCheck|hasPermission|isAlliedTo|getItemBlockingWith|GL11|RenderSystem|Registry\.getTagOrEmpty
```
