# SmartMaid AI 操作接口实现文档

> 状态：**M1~M3.6 + M4 JSON 指令协议 + 正交槽位 transfer + 感知模块 M-P0 + 桌宠联动 M5 + 感知增量 M-P1 全部已实现**（2026-09-10 真机端到端 100% 通过）。为女仆提供"预留 AI 接口"，使外部 AI 能操作女仆执行攻击/挖矿/耕作/建造/制作等任务，且女仆能自主操作自己背包的物品（取工具/换手持/收纳/合成/穿装备/存箱子）。

## 一、目标与原则

**目标**：外部 AI 通过统一指令协议操作女仆，AI 只下高层意图（打谁/挖哪/种什么/放哪/做哪个物品），寻路、跳跃、选工具、方块操作、背包合成全部由本地确定性逻辑完成。

**原则**：
1. **AI 下意图、本地做执行**——延续"规则引擎主导、AI 兜底"。
2. **接口与实现解耦**——AI 通道（桌宠 WebSocket / 命令 / 未来 LLM）统一走指令协议，模组内部能力不绑定任何 AI 实现。
3. **安全层不可越过**——L0（岩浆/火）优先级永远最高，AI 任务不得覆盖。
4. **女仆 = 玩家延续**——背包仍走 41 格布局、主手 = 热键0、副手任意物品的既有规则。
5. **命令参数全部用 MC 原生类型**——槽位用数字/单词（`word()`）、物品用 `IdentifierArgument`、坐标用 `BlockPosArgument`，**不用自定义 ArgumentType、不用冒号槽位**（避免命令树同步崩溃与输入坑）。

## 二、总体架构（三层 + 一桥 + 一联动）

```
[AI 通道] 桌宠 WebSocket（M5-a 下行 + M5-b 上行）/ /maidai <json>（当前）/ /maidtasks 命令（当前）
      ↓ 统一 JSON 指令（下行）/ 统一 perception 增量帧（上行）
[MaidAIBridge 指令桥接（M4）] 解析 → 校验 → 构造任务 → 派发 → 回执 MaidCommandResult
[MaidWsClient WS 客户端（M5-b）] 接收 command → 转发到 MaidAIBridge（走 server.execute() 切回服务端线程）
[MaidTelemetryWriter 遥测写入（M5-a）] 把 perception.events 写入 <游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl
[PerceptionDiff 增量（M-P1）] 每次 perception 算 diff → changed/removed 增量 payload
      ↓ MaidAITask
[MaidTaskManager 任务调度器] 每 tick 驱动当前任务；抢占/暂停/结果回执
      ↓
[MaidAITask 任务层] Attack/Guard/Feed/Mine/Farm/Build/Collect/Craft/Smelt/Transfer/...
      ↓ 原子能力调用
[SmartMaidEntity 能力层 MaidActions] 移动/寻路 / 背包操作 / 方块破坏与放置 / 攻击 / 制作
        [槽位系统 ItemSlot/Slots]  transfer 的正交基础（背包/手持/盔甲/容器）
      ↑
[感知模块 PerceptionModule（M-P0）] 给 AI"玩家视角"快照（self/inventory/owner/nearby/blocks/env/events）
[感知事件源（hurt/damage/敌人/环境危险/任务切换）] → 同时入桌宠遥测窗口 + 回执队列
```

## 三、统一指令协议

```json
// 请求（桌宠 WebSocket 接入时）
{"id":"cmd-1","cmd":"mine","params":{...},"cancel_previous":true}
// 回执
{"id":"cmd-1","ok":true,"state":"running|done|failed|cancelled",
 "step":"当前步骤","result":{...}}
```

- **JSON 指令协议已实现**（M4，`MaidAIBridge`）：`/maidai <json>` 或任何通道调 `MaidAIBridge.execute(maid, json)` 即得回执；回执含 `id/ok/state/step/result`。
- 同一时刻只执行**一个**任务；`cancel`/坐下/主人取消 → 强制终止。
- 集成指令统一带超时保护（60 秒，持续型任务除外）。
- 参数：坐标支持 `[x,y,z]` 绝对 或 `~`/`~5` 相对（以女仆位置为基准）；物品用 `Identifier`；槽位复用 `Slots.parse`。
- `persist:true` 时把该指令参数持久化到 `config/smartmaid/maids/<UUID>.cfg`（`MaidTaskConfig`，女仆重召唤后可查）。

## 四、指令集合（已实现）

### 4.1 正交物品转移（核心）

**槽位表达式**（全部 `word()` 友好：纯数字/单词，无冒号）：
- `0-40`：女仆背包/热键/盔甲/副手（0 主手 / 9-35 背包 / 36-39 盔甲 / 40 副手）
- `mainhand` / `offhand` / `head` / `chest` / `legs` / `feet`（别名）
- 兼容宽容写法：`inv5`、纯数字 `5`、全角符号自动转半角

| 指令 | 作用 |
|---|---|
| `transfer <from> <to> [count]` | 女仆自身槽位间移动物品 |

**transfer 语义（移动+交换+掉落）**：
| 目标槽状态 | 行为 |
|---|---|
| 空 | 直接放入 |
| 同类可堆叠 | 堆叠（按 maxStackSize：64/16/1），放不下剩余放回源 |
| 被不同物品占用 | **交换**两个槽位（仅女仆自身槽位） |
| 盔甲槽但非对应装备 | 直接掉落 |
| 容器满/无容器 | **不放、不掉落**，物品留在源 + 气泡提示 |

### 4.2 箱子操作（两步指令）

| 指令 | 作用 |
|---|---|
| `chestopen <pos>` | 在提示位置 4 格内**智能定位最近容器** → 走过去 → 真实开箱动画（`ChestBlockEntity.startOpen`，女仆实现 `ContainerUser`）→ 记录"当前打开的箱子" |
| `chestput [count]` | 主手物品放入已打开箱子：**优先同类可堆叠（智能 maxStackSize）→ 其次空槽**；满则留主手 + 提示 |
| `chesttake [slot] [count]` | 从已打开箱子取物品到主手（slot 缺省 = 首个非空） |

> `chestopen` 找不到容器 → 气泡"附近没有箱子"；未 open 就 put/take → 提示"先 chestopen"；箱子被移走 → 提示"箱子不在了"。

### 4.3 基础指令（原子动作）

| 指令 | 作用 |
|---|---|
| `move <pos>` | 寻路走到目标（跳跃自动生效） |
| `look <pos>` | 面朝目标 |
| `break <pos>` | 挖方块，掉落直接进背包（含挖掘进度裂纹） |
| `place <pos>` | 手持方块放置到目标格 |
| `use <pos>` | 右键使用手持物品（种子/火把） |
| `equip <item>` | 背包按物品换到主手（物品用 `IdentifierArgument`） |
| `store` | 主手物品收纳进背包空槽 |
| `drop [count]` | 丢出主手物品：丢到**主人身边**（没有主人则自己脚下），并让女仆 5 秒内不拾取，避免"丢出去又被自己捡回" |
| `pickup` | 走过去拾取周围掉落物（有过程，非瞬吸） |
| `sit` / `stop` | 坐站切换 / 停止 + 取消任务 |

### 4.4 集成指令（行为脚本）

| 指令 | 说明 |
|---|---|
| `attack [range] [target]` | 攻击：默认打最近**敌对**生物；指定 `target`（实体类型，如 `minecraft:pig`）则打最近的该种生物（可用于获取食物），目标死亡结束 |
| `guard [range]` | 护卫：自动索敌循环（持续型，cancel 停） |
| `feed` | 走到玩家面前 → 面朝 → 找背包食物 → 丢给玩家 |
| `mine [pos] [range] [count]` | 换镐 → 区域搜矿物（矿石标签）→ 有挖掘过程的挖矿 → 掉落进背包；**pos 可省**（缺省以女仆脚下为中心自动探测，水平 12 / 垂直 8 格） |
| `farm <pos> [range]` | 收成熟作物 + 空地播种 |
| `build <pos> <height>` | 从 pos 向上堆叠 height 个方块 |
| `collect [range]` | 收集掉落物（走过去拾取） |
| `craft <item> [count]` | 原地合成：按成品反查配方 → 借位合成（无需工作台）→ 消耗材料出成品 |
| `smelt <item> [count]` | 熔炉烧炼：找熔炉 → 放原矿+燃料 → 轮询取成品 |

### 4.5 管理

| 指令 | 作用 |
|---|---|
| `cancel` | 取消当前任务 |
| `status` | 查询当前任务 |

### 4.6 查询指令（**不产生任务、不改世界状态**）

| 指令 | 参数 | 作用 |
|---|---|---|
| `craft_check` | `item`（必填）、`count`（默认 1） | **干跑查询**：目标物品能不能合成、需要什么材料、女仆现有多少。**不消耗任何材料** |

回执 `result` 结构（材料不足时 `craftable=false`，逐项给出 need/have/ok）：

```json
{"found":true,"craftable":false,"need":1,"crafts":1,"out_per_craft":1,
 "ingredients":[
   {"options":["minecraft:oak_planks","minecraft:spruce_planks"],"need":3,"have":5,"ok":true},
   {"options":["minecraft:stick"],"need":2,"have":0,"ok":false}]}
```

* 候选配方优先取物品自带的 `DataComponents.RECIPES`（此时产物必然匹配，无需校验）；
  兜底全量扫描时用「填满候选项的 3×3 假网格」验证产物
* `options` 支持 tag 类配方（「任意木板×3」→ 列出候选物品 id）
* 用途：桌宠侧 NLU 在**自动合成前**判断材料是否齐备，并把「缺什么、缺多少」回报给用户/大 AI。
  实现见 `CraftExecutor.check()`。

## 五、能力层 `MaidActions`

`entity/ai/MaidActions.java`：原子动作，全部带低频 debug 日志。

| 能力 | 方法 |
|---|---|
| 寻路/距离 | `navigateTo` / `isWithinReach` |
| 背包 | `findInBackpack` / `equipFromBackpack` / `storeToBackpack` / `isFood` |
| 方块 | `breakBlock`（getDrops 掉落进背包，含时运）/ `placeBlock`（null-player）/ `useItemOn` |
| 挖掘进度 | `computeMiningTicks`（硬度×工具）/ `showMiningProgress`（裂纹 0-9） |
| 战斗 | `attack`（doHurtTarget(ServerLevel, ...)） |
| 实体查找 | `findEntities` / `findNearestEntity` / `findNearestItem` |
| 其他 | `throwItem`（投掷喂食） |

## 六、槽位系统（transfer 正交核心）

`entity/ai/slot/`：
- **`ItemSlot`** 接口：`reachPos` / `canAccept`（盔甲槽校验）/ `isSwapAllowed`（容器 false）/ `peek` / `extract` / `insert`（**契约：不改传入栈**，返回剩余）
- **`Slots`** 解析器 + 实现：`MaidSlot`（41 格）/ `WorldSlot`（只作目标：放置/丢出）/ `ContainerSlot`（智能找格）
- 工厂：`Slots.mainhand()` / `Slots.container(pos, slot)`
- **`TransferTask`**：移动+交换+掉落语义的执行器

## 七、任务层

`entity/ai/maidtask/`：
- **`MaidAITask`** 抽象（`canStart/start/tick/isDone/result/forceStop/isContinuous`）
- **`MaidTaskManager`** 调度器（aiBusy 标志让现有 Goal 让路）
- 任务：`AttackTask` `GuardTask` `FeedTask` `MineTask` `FarmTask` `BuildTask` `CollectTask` `CraftTask` `SmeltTask` `MoveToTask` `TransferTask` `ChestOpenTask` `OneShotTask`

## 八、制作 `CraftExecutor`

`entity/ai/craft/CraftExecutor.java`：按成品反查配方（优先物品 `RECIPES` component，兜底遍历）→ `PlacementInfo` 3x3 布局从背包抽材料 → `CraftingInput.matches` → 消耗合成 → 产物进背包。**无需工作台**，2x2/3x3 统一。

**`CraftExecutor.check(maid, target, count)`（干跑查询，2026-09-11 新增）**：同样的配方解析路径，但**不消耗材料**，
只回报「需要什么材料 / 现有多少 / 够不够」（`ingredients[].{options,need,have,ok}`），
供桌宠 NLU 在自动合成前判断并解释缺料。实现要点：优先用物品自带的 `RECIPES`（产物必然匹配，无需校验）；
兜底全量扫描时用一个「填满候选项的 3×3 假网格」`matches` + `assemble` 验证产物。

## 九、持久化

- 任务运行状态不持久化（女仆消失即终止，重召唤待命）。
- 背包/装备仍走 `MaidDataManager`（NBT，`.dat`）。
- **任务配置持久化已实现**（M4，`MaidTaskConfig`）：`persist:true` 的指令参数存 `config/smartmaid/maids/<UUID>.cfg`（JSON），AI 可随时 `MaidTaskConfig.get/all` 查询，女仆重召唤后配置保留。

## 十、里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 能力层 `MaidActions` + aiBusy 标志 | ✅ 已实现 |
| M2 | 任务框架 + AttackTask + /maidtasks | ✅ 已实现 |
| M3 | Mine/Farm/Build/Craft + CraftExecutor | ✅ 已实现 |
| M3.5 | SmeltTask 烧炼 | ✅ 已实现 |
| M3.6 | 正交 transfer 槽位 + 两步存箱子 + 开箱动画 | ✅ 已实现 |
| M-P0 | 感知模块（MaidPerception + 6 通道 + 事件队列 + PerceptionJson） | ✅ 已实现（2026-09-10） |
| M4 | JSON 指令协议 + MaidAIBridge + 任务配置持久化 | ✅ 已实现（2026-09-10，自动化测试 9/9 通过） |
| M-P1 | 感知增量 diff（只发变化字段省 token） | ✅ 已实现（真机 5761B → 971B，降 83.1%） |
| **M5-a** | **桌宠联动下行（写 `<游戏目录>/deskpet/maid/*.jsonl`，复用桌宠 schema）** | ✅ 已实现（2026-09-10 真机 e2e） |
| **M5-b** | **桌宠联动上行（WebSocket Client + 心跳 + 退避 + token 握手）** | ✅ 已实现（2026-09-10 真机 e2e） |
| **M6-a** | **`craft_check` 干跑查询（不消耗材料，逐项报 need/have/ok）** | ✅ 已实现（2026-09-11，供桌宠 NLU 自动合成前判断） |
| **M6-b** | **`item_index` 物品索引下发（当前语言名 → id，覆盖 mod 物品）** | ✅ 已实现（2026-09-11，握手后一次性下发） |
| **M6-c** | **`owner.pos` 感知补全（相对指令「在我脚下…」需要）** | ✅ 已实现（2026-09-11） |

## 十一、文件清单（当前）

```
entity/ai/
├── MaidActions.java              能力 API
├── MaidDebug.java                调试日志（已有）
├── MaidMoveControl.java          移动控制（已有，跳跃触发）
├── MaidActionExecutor.java       跳跃执行器（已有）
├── slot/
│   ├── ItemSlot.java             槽位抽象（canAccept/isSwapAllowed/insert 契约）
│   └── Slots.java                解析 + MaidSlot/WorldSlot/ContainerSlot
├── craft/CraftExecutor.java      配方解析/借位合成
├── maidtask/
│   ├── MaidAITask.java           任务抽象
│   ├── MaidTaskManager.java      调度器
│   ├── AttackTask / GuardTask / FeedTask / MineTask / FarmTask
│   ├── BuildTask / CollectTask / CraftTask / SmeltTask
│   ├── MoveToTask / TransferTask / ChestOpenTask / OneShotTask
├── perception/                   ★感知模块（M-P0，见 HANDOVER）
│   ├── MaidPerception.java        快照模型
│   ├── PerceptionModule.java      调度 + 事件队列
│   ├── Sense.java / PerceptionUtil.java / PerceptionBlockUtil.java / PerceptionJson.java
│   └── sense/                     Self/Owner/Inventory/Entity/Block/EnvironmentSense
└── bridge/                       ★AI 指令桥接（M4）
    ├── MaidAIBridge.java          JSON 指令 → 任务 → 回执
    ├── MaidCommandResult.java     回执模型
    ├── MaidTaskConfig.java        任务配置持久化
    ├── MaidAutoTest.java          代码层自动化测试
    └── ItemIndexPayload.java      ★物品索引下发（当前语言名→id，NLU 用）
command/MaidTaskCommand.java      /maidtasks（全指令入口）
command/MaidAICommand.java        /maidai <json>（AI 指令调试）
command/MaidPerceptionCommand.java /maidperception（感知调试）
entity/SmartMaidEntity.java       实体（+ ContainerUser + perceptionModule）
```

## 十二、技术实现要点（已验证）

- **命令参数**：槽位用 `StringArgumentType.word()`（纯数字/单词）；物品用 `IdentifierArgument`；坐标用 `BlockPosArgument`（支持 `~`）。**不要**引入自定义 ArgumentType（需注册，否则命令树同步崩溃）。
- **开箱动画**：女仆实现 `ContainerUser`（`getContainerInteractionRange` / `hasContainerOpen` / `getLivingEntity`），调 `ChestBlockEntity.startOpen(maid)` → openCount 机制 → 箱子盖打开。
- **26.2 API**：`doHurtTarget(ServerLevel, Entity)`、`Recipe.assemble(T)` 单参、`CraftingInput.of(w,h,list)`、`ServerLevel.recipeAccess()`、`Block.getDrops(..., Entity, ItemInstance)`、`ChestBlockEntity.startOpen(ContainerUser)`。
- **方块放置/使用**：`BlockPlaceContext(level, null, ...)` / `UseOnContext(level, null, ...)`（null player 方案）。
- **`insert` 契约**：不得修改传入 ItemStack（副本操作），调用方才能正确判断完全/部分放入。

---

## 十三、WebSocket 协议（M5 桌宠联动，已实现）

### 13.1 传输与连接

- **端点**：`ws://127.0.0.1:21420`（仅本机绑定）
- **角色**：模组 = Client（`MaidWsClient`，JDK 25 内置 `java.net.http.WebSocket`，零依赖）；桌宠 = Server
- **协议**：JSON 文本帧
- **握手**：可选 token（`config/smartmaid/bridge.json` + 桌宠 `config.py` 同步；留空则不校验，仅绑本机做基本安全）
- **心跳**：桌宠每 5s 发 `{"type":"ping","t":<ms>}`，模组回复 `{"type":"pong","t":<ms>}`；RTT 稳定 ~10ms（真机测量）
- **重连**：指数退避 1s → 2s → 4s → ... → 30s 上限，连接断开立即启动
- **生命周期**：注册在 `ServerLifecycleEvents.SERVER_STARTED`，SERVER_STOPPED 释放 OUTBOX / 关 ws

### 13.2 消息类型

**上行（模组 → 桌宠）**

| `type` | 载荷 | 说明 |
|---|---|---|
| `hello` | `{version, protocol, maidUuid, maidName}` | 握手后第一帧，含协议版本号 |
| `perception` | `{seq, tick, full?, snapshot, changed?, removed?}` | 增量 diff 帧：首帧 `full=true snapshot=完整`；后续 `changed` 嵌套对象 + `removed` 点分路径数组 |
| `event` | `{seq, tick, type, ts, ...payload}` | 实时事件（`damage` / `enemy_spotted` / `task_started` / `task_done` / `environment_danger` / `low_health`） |
| `command_result` | `{id, ok, state, step, result}` | 任务回执（与 `/maidai` 命令回执同结构） |
| `item_index` | `{count, items}` | **握手后下发一次**：当前语言的「物品显示名 → `minecraft:id`」整表（见 §13.5） |
| `pong` | `{t: <ms>}` | 响应桌宠 `ping` |

**下行（桌宠 → 模组）**

| `type` | 载荷 | 处理 |
|---|---|---|
| `ping` | `{t: <ms>}` | 回 `pong` 同 `t`；RTT 推算 |
| `command` | `{id, cmd, params}` | **回调线程铁律**：`server.execute(() -> MaidAIBridge.execute(...))` 切回服务端线程再派发任务 |
| `speak` | `{text, durationMs?}` | `MaidEntity.showBubble` 渲染头顶气泡（独立字段，不污染 `customName`） |
| `animation` | `{name}` | `/maidanim` 同步路径，服务端设 `DATA_DEBUG_ANIM` 客户端播放 |
| `reset_diff` | `{}` | 强制下一帧 perception 全量（桌宠侧状态丢失后用） |

### 13.3 配置

**模组侧 `config/smartmaid/bridge.json`**（首次自动生成）：
```json
{
  "enabled": true,
  "deskpetDir": "",
  "windowTicks": 400,
  "ws": {"enabled": true, "url": "ws://127.0.0.1:21420",
         "token": "", "heartbeatSec": 5, "perceptionIntervalMs": 750}
}
```

**桌宠侧 `desktop-pet/config.py`** 常量：
```python
MAID_LINK_ENABLED = True
MAID_LINK_PORT = 21420
MAID_LINK_TOKEN = ""  # 留空不校验
MAID_LINK_PERCEPTION_INTERVAL_MS = 750
```

### 13.4 下行文件通道（M5-a，感知 → 桌宠）

**目录**：`<游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl`（与 `deskpet-mod` 顶层文件隔离，避免同分钟互写）
**窗口**：默认 400 tick（20s）；首窗 100 tick 即落（启动期可见）
**轮转**：按分钟轮转；清理 2 分钟前的旧文件
**schema**（复用桌宠已知 type，零改动渲染）：
```json
{"source":"smartmaid","seq":3,"tick":123456,"importance":"CRITICAL",
 "highlights":[{"type":"damage","player":"女仆","target":"minecraft:zombie","detail":"剩余生命 18.0"}],
 "maid":{"name":"女仆","owner":"Steve","pos":[x,y,z],"health":18.0,"task":"mine","mainhand":"..."}}
```
**重要性**：damage / enemy / env danger = CRITICAL；task start/end = NORMAL；空窗 = LOW
**事件类型映射**（highlights[].type）：
| 感知事件 | type | 桌宠渲染 |
|---|---|---|
| `hurt` | `damage` | `[受伤] 女仆 被 minecraft:zombie 伤害` |
| `enemy_spotted` | `summary` | `[概况] 女仆发现 minecraft:zombie（8.0 格外）` |
| `task_started` / `task_done` | `summary` | `[概况] 女仆开始执行 mine` |
| `environment_danger` | `summary` | `[概况] 女仆进入岩浆` |

### 13.5 `item_index` 物品索引下发（NLU 支持）

握手成功后（收到 `hello_ack`）由模组**下发一次**，把「当前语言的物品显示名 → `minecraft:id`」整表交给桌宠：

```json
{"type":"item_index","count":1523,
 "items":{"铁镐":"minecraft:iron_pickaxe","工作台":"minecraft:crafting_table","...":"..."}}
```

**为什么由模组下发、而不是桌宠自带词典**
* 覆盖 **mod 物品**：词典随整合包自动变化，桌宠无需维护
* 名称跟随**客户端当前语言**（`I18n`），中文包/英文包都能匹配
* 桌宠不必打包 `zh_cn.json`，也就没有版本同步问题

**桌宠侧用途**：NLU 在本地做中文/拼音模糊匹配
（语音说「帮我合成一把**稿子**」→ 拼音匹配到「镐子」→ `minecraft:wooden_pickaxe`），
再把**权威 id** 发回来执行 —— 物品名解析不依赖游戏往返。

**实现**：`bridge/ItemIndexPayload.java`（按连接缓存；结果按名字排序保证稳定）
* 名称取自客户端 `I18n.get(item.getDescriptionId())`；返回 key 本身（未翻译）则跳过
* **专用服务端**没有客户端语言资源 → 返回 `null`，不下发该消息
  （桌宠自动降级为「认得出名字但执行不了」，**不会误执行**）
* 上限 4096 条 / 名字长度 ≤16，防止极端整合包把单条 WS 消息撑爆

### 13.5.1 感知补全：主人坐标 `owner.pos`

`OwnerSense` 原本只有 `dist`，桌宠无法把「**在我脚下**放方块」「**到我这儿来**」
这类相对指令换算成模组要的绝对坐标。现已补上：

```json
"owner":{"name":"Steve","uuid":"...","pos":[12,64,14],"dist":3.2,"health":20.0, "..."}
```

桌宠侧换算约定：`owner` 相对词 → 主人坐标；`maid` 相对词 → 模组的 `"~"`（女仆脚下）；
两者都拿不到时**不执行**，只提示。

### 13.6 感知增量 diff（M-P1）

`PerceptionDiff.next(perception)` 输出：
- 首帧：`{seq, tick, full:true, snapshot}`（snapshot 是完整 perception 副本，约 5~6KB）
- 后续：`{seq, tick, full:false, changed, removed}`（changed 嵌套对象递归 diff；removed 点分路径数组）
- 触发 reset：`reset_diff` 消息或断线（`MaidWsClient.onClose` 调 `diff.reset()`）

合并语义（桌宠侧 `maid_link.merge_snapshot`）：
```python
def merge_snapshot(base, msg):
    if msg.get('full'): return msg['snapshot']
    out = dict(base)
    for k, v in msg.get('changed', {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge_snapshot(out[k], {'changed': v, 'full': False})  # 递归
        else:
            out[k] = v
    for path in msg.get('removed', []):
        cur = out
        parts = path.split('.')
        for p in parts[:-1]: cur = cur.get(p, {})
        cur.pop(parts[-1], None)
    return out
```
**实测收益**：真机 5761B → 971B，降 **83.1%**（独立单元测试降 87.3%）。

### 13.7 桌宠侧实现关键点

`desktop-pet/game/maid_link.py`：
- `asyncio` 跑独立线程（PySide6 GUI 单线程），通过 `queue.Queue` 与主线程通信
- 增量合并语义与 §13.5 完全对齐
- 中文映射：`minecraft:zombie → 僵尸` 等词典；任务名 → 中文（`guard → 护卫`）
- 落盘 `desktop-pet/logs/maid_link.log`（含连接/握手/收发摘要），方便 e2e 后回看

`desktop-pet/game/mod_data.py`：`window_files()` 增扫 `maid/` 子目录，返回 `(<绝对路径>, <相对路径 'maid/...'>)`；调用方零改动。

`desktop-pet/pet/handlers/maid_handler.py`：1s 轮询状态、当 maid 上线且 game ready → 简短播报（8s 冷却）；支持 `.maid_command.json` 调试注入口，桌宠下一拍下发。

对外 API（供桌宠对话/UI 层调用）：
```python
app.maid_command(cmd: str, params: dict) -> dict      # 下发指令 + 等回执
app.maid_speak(text: str) -> None                      # 气泡
app.maid_animate(name: str) -> None                    # 动作
app.maid_state_line() -> str                          # 一行中文状态（debug 用）
app.maid_link_connected                              # bool，本轮 WS 是否已连
```

### 13.8 已知约束 / 待修

- ~~**伤害事件 `target:"?"`**~~（DEVELOPMENT_ISSUES §问题 5，**已修复** 2026-09-10）：`SmartMaidEntity` 覆写 26.2 `hurtServer` 最早阶段缓存攻击者（`getRecentAttacker()`）+ `PerceptionModule.attackerLabel()` 类型映射表；`target` 输出 Player 名 / Projectile 射手 / 实体短名，未知写 `unknown`，遥测降级 NORMAL
- **handshake token 留空**：单机不校验；上线时建议加 token
- **`speak` 不支持 TTS**：当前仅气泡，桌宠侧 TTS 自行实现（与本模组解耦）

---

## 十四、端到端联调工具（M5 自动化）

`SmartMaid/tools/run_e2e_test.py` —— 单脚本串联整条链路：

```bash
# 前置：GUI 启动器登录一次游戏（HMCL refresh accessToken）
python tools/run_e2e_test.py --quick-play "新的世界 (11)"
# 内部流程：
#   1) --check-token 守门（hmcl.json accessToken 过期早退，提示用户登录）
#   2) launch_game.py 从 HMCL 日志逆向提取启动命令 + quick-play
#   3) subprocess.Popen(..., DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP) 起游戏
#   4) 起桌宠 + WS Server
#   5) 轮询：autotest.done.json + deskpet/maid/*.jsonl + maid_link.log + WS 心跳
#   6) AutoTest 全过 + 至少 4 个遥测窗口 + WS 至少 1 次 speak/animation/guard 闭环 → PASS
#   7) 进程清理 + present_files 报告
```
**输出**：JSON 报告 + 可选 Markdown 总结，`logs/latest.log` 自动 gbk 解码。

**前置依赖**（一次性）：
- `config/smartmaid/autotest.json`：写一组指令序列（参考 HANDOVER §六）
- `config/smartmaid/bridge.json`：首次启动自动生成
- `desktop-pet/logs/`：写入 maid_link.log / game_handler.log
- 游戏目录 `deskpet/maid/`：写入 jsonl

**子脚本**：`launch_game.py --dry-run` 仅打印提取的启动命令，不实际启动。
