# 女仆任务队列 · 深化设计

> 起因：游戏内菜单的「任务」页要求"一行一个任务，第 1 行进行中、第 2 行下一个…"（`DESIGN_MAID_MENU_N1.md` §3.2）。
> 状态：**设计，未开工**（2026-09-30；现状代码证据均已逐行核对）。
>
> **修订**
> - v1：回答"有没有队列"，给出基础入队/推进/取消设计。
> - v2：优先级与插队、长期/短期、打断=降位不取消、接续、持久化。
> - v3：长期/短期**两个独立队列**、去重互斥、参数级落盘 + 状态回溯、重召自动继续。
> - **v4**：
>   1. ⚠️ **推翻 v3 的粗粒度互斥组** —— 改为**按"动作 + 具体子目"判重**（`mutexKey`）。
>      起因：主人说"把身上的矿物都烧一下"会合法地产生**多个熔炼任务**（烧铁锭 / 烧金锭 / 烧铜锭），
>      v3 的 `craft` 组会把它们全部当重复拒掉 —— **错的**。
>   2. 追加**三态判定**（拒绝 / 接受+软提示 / 接受），泛化请求不再被精确项挡住。
>   3. 追加 **`groupId`**：一条命令展开成的多项归一组，UI 分组显示、可整组取消。
>   4. 已拍板：长期「设为当前」**可打断短期**；长期「停止」= **移除队列**；互斥**拒绝 + 回执**。
> - **v5（本文 · 2026-09-30 定稿）**：**决策全部拍板**，见 §十五（14 条结论汇总）。
>   本轮新增 **§8.8 特殊合并规则与资源占用**：`guard` 合并取**最大 range**；
>   **一个熔炉同一时间只能烧一种矿 → `smelt` 取空闲熔炉**（熔炉租约，无空闲则 `PAUSED(BLOCKED)` 且不占队头）。
>   ⚠️ v4 的"多个 smelt 尽量复用同一熔炉"**作废**。
>   **状态：✅ 已实现（2026-10-01，真机验收通过）。** Q1~Q9 全部落地（`ScriptTask` 跨会话接续按设计**显式声明不支持**，会话内仍零成本接续）。实施见 §十二 Q1~Q9。

---

## 〇、规格落地对照

| # | 用户要求 | 落地 |
|---|---|---|
| 1 | 队列解决**优先级**与**长期/短期**概念 | §三 优先级 + §四 双队列 |
| 2 | 高优先级自动插队；**主人实时交互 = 高优先级** | §五 P2 插到短期队首，当前项 `PAUSED` 降一位 |
| 3 | 挖矿插到砍树前面，挖矿完成后继续砍树 | §四 短期队列推进 + §六 接续 |
| 4 | **长期任务 = 没有完成指标**，长期呆在面板 | §四 长期队列 |
| 5 | **短期做完，长期才开始** | §四.1 调度：短期非空→跑短期；短期空→跑长期队首 |
| 6 | 长期任务可**调整为当前 / 停止 / 队列内调位置** | §四.3（**设为当前可打断短期**；**停止 = 移除**） |
| 7 | 打断 = **排名下降一位，不是取消** | §五 `PAUSED` + 位置后移 + `interruptCount` |
| 8 | 建到一半被打断，**如何接续** | §六 `Resumable` 完整状态快照 + 回溯 |
| 9 | **任务状态和物品一样被储存** | §七 持久化（NBT `tasks` 段） |
| 10 | **自动继续** | §七.3 默认自动继续（+ 开关） |
| 11 | **重复性工作必须互斥**（主人 + 桌宠同时识别砍树） | §八 去重（同键拒绝 + 回执） |
| 12 | **参数级落盘**：存中间变量，继续时回溯状态 | §六 完整快照 + §六.4 字段清单 |
| 13 | **互斥要更智能**：一条命令展开的多个子目不能互斥 | §八 **`mutexKey` = 动作 + 具体子目** + 三态判定 |

---

## 一、现状：没有队列（逐行证据）

| 事实 | 代码位置 |
|---|---|
| **只有一个任务槽** | `MaidTaskManager.java:22` `private MaidAITask current;` |
| 新指令**默认抢占** | `MaidAIBridge.java:83`（`cancel_previous` 缺省 `true`） |
| 抢占 = 旧任务被**丢弃** | `MaidTaskManager.java:50-52` `current.forceStop(maid)` → 不排队、**不恢复** |
| `cancel_previous:false` 也**不是排队** | `MaidAIBridge.java:122-124` / `144-146` → 直接 `fail` |
| **战斗 = 丢弃当前任务** | `MaidTaskManager.java:84-88` `cancel()`，打完不继续 |
| 坐下 / 死亡 = 丢弃 | `MaidTaskManager.java:90-93` |
| 非持续型 **60s 超时** | `:19` `MAX_TASK_TICKS = 20*60` |
| 唯一的"顺序执行" = `ScriptTask` | `ScriptTask.java:147/151` `advance()` → 仍是**一条指令内含多步**，占单槽 |
| **任务状态不持久化** | `DESIGN_AI_INTERFACE.md` §九；女仆 `noSave()`，只有装备/背包走 `MaidDataManager` |
| **无任何去重/互斥** | 全项目无 signature / mutex / dedupe 相关代码 |
| ⚠️ `SmeltTask` 的 target 是**产物** | `SmeltTask.java:36` `SmeltTask(ItemStack target, int count)`，注释："放**能烧成目标**的原矿" → `smelt minecraft:iron_ingot`（不是"铁矿"） |

---

## 二、三个新概念

1. **两个独立队列**（长期 / 短期），不是一个队列加标记。
2. **优先级**只决定"插到哪"，不决定"要不要顶掉别人"。
3. **打断 ≠ 取消**：`PAUSED` + 降一位 + **对象保留**（§六.1）。

---

## 三、优先级模型

| 级别 | 是谁 | 与队列的关系 |
|---|---|---|
| **P0** | L0 安全层（岩浆/火） | **不进队列**，每 tick 硬检测 |
| **P1** | **战斗** | **不进队列**（保留 `MaidCombatGoal`）；面板顶部占一行显示"战斗中"，队列区下移 |
| **P2** | **主人实时交互**（语音 / 菜单） | **插到短期队列队首**；当前项 `PAUSED` 降一位 |
| **P3** | 桌宠事件自动线 / `script` / AutoTest | **追加到短期队列队尾** |

**P1 为什么"不进队列"**：现状战斗是 Goal 层（`MaidTaskManager.java:84-88`），塞进队列要重写战斗调度，收益低风险高。
只在 UI 上表现成"顶到第 1 行"，架构零改动。

**P2 判定**（跨项目）：桌宠 WS 的 `command` 需带 `"priority":"owner"`。
- 桌宠**语音/对话线** → `owner`（P2）
- 桌宠**游戏事件自动线** → 缺省（P3）
- 菜单下发 → 固定 P2；`script` / AutoTest → P3

---

## 四、★ 两个独立队列

> **已拍板**：长期与短期是**两个队列**；**只有当短期任务做完了，长期任务才会开始执行。**

```
长期队列 LongTermQueue（有序 · 常驻 · 无完成指标）
  1. 挖矿（直到主人喊停）        [设为当前][停止][↑]
  2. 护卫 半径 10                [设为当前][停止][↑]

短期队列 ShortTermQueue（有序 · 有完成指标 · 做完即移除）
  1 ▶ 挖矿 8 块       进行中
  2 ⏸ 砍树 8 根       暂停中（被插队，降一位）
  3   存入箱子        排队中

调度：
  if (!short.isEmpty())      run(short.head)
  else if (!long.isEmpty())  run(long.head)
```

### 4.1 调度规则

1. **短期非空** → 只执行短期（队首优先）；
2. **短期空** → 执行长期队首；
3. 短期中途来 P2 → 插短期队首；
4. **长期执行中**来了短期任务 → 长期项 `PAUSED(LONGTERM_SUSPEND)`，**保留在长期队列原位置**，短期清空后回来继续（接续见 §六）。

### 4.2 长期 / 短期怎么判定

由**任务参数**决定（不是类属性）：

| 任务 | 短期 | 长期 |
|---|---|---|
| `mine` / `harvest` | 有 `count` | **无 `count`**（一直做） |
| `guard` | — | 本身即长期（持续型） |
| `smelt` / `craft` / `feed` / `eat` / `move` / `chest*` / `transfer` | ✅ 短期 | — |
| 任意 | 显式 `"long_term":false` | 显式 `"long_term":true` |

### 4.3 长期队列的操作（已拍板）

| 操作 | 语义（已定） |
|---|---|
| **设为当前** | **立刻取得执行权，可打断短期任务** —— 当前短期项 `PAUSED` 保留（降一位）；长期项跑完回到长期队列**队首** |
| **停止** | **永久移除该项**（= 主人喊停）；UI 二次确认 |
| **上移 / 下移** | 长期队列内调位置（不跨队列） |
| **暂停 / 恢复** | 暂停 = `PAUSED`；恢复 = 回到长期队列队首 |

### 4.4 容量

| 队列 | 上限 | 满时 |
|---|---|---|
| 短期 | **16 项** | 拒绝 + `queue_full` |
| 长期 | **4 项** | 同上 |

---

## 五、插队与打断的精确语义

### 5.1 插队（P2 进入短期队列）

```
之前： 短期 [1 ▶ 砍树 8 根]
主人语音："去挖点矿"
之后： 短期 [1 ▶ 挖矿 8 块] [2 ⏸ 砍树 8 根（降一位）]
```

- 当前项**不 `forceStop` 丢弃**：`PAUSED` + **对象保留**（§六.1）+ 位置后移一位；
- 新项插队首并 `start()`；新项结束后**自动推进**到被暂停项。

### 5.2 战斗打断（P1）

- 任务层只做两件事：战斗开始 → 当前项 `PAUSED(COMBAT)` + **保留对象**；战斗结束 → 回队首继续。
- 面板战斗期间显示一行「战斗中」，队列整体下移一位（与观感一致，架构零改动）。

### 5.3 降位规则

被抢占项**不移除**，`interruptCount++`，位置 = 原位置 + 1；连续被打断会一路往后但**永远留在队列里**。

---

## 六、★ 接续：参数级落盘 + 状态回溯

### 6.1 先分清两种"继续"（关键）

| 场景 | 最佳实现 | 是否需要落盘 |
|---|---|---|
| **会话内打断**（战斗 / 插队 / 长期项挂起） | **挂起对象、不销毁** —— `PAUSED` 时保留任务对象引用，继续时直接接着 `tick()` | **不需要**（内存对象即完整状态） |
| **跨会话继续**（退出游戏 → 重召） | 序列化 → 落盘 → 恢复时回溯 | **需要** |

> ⚠️ 现状的病根**不是"不能接续"**，而是 **`forceStop` 销毁对象 + 丢弃引用**。
> 改成"挂起保留"后，会话内接续**零成本**。**落盘是给关游戏准备的。**

### 6.2 `Resumable` 契约（完整状态快照）

```java
interface Resumable {
    JsonObject saveState();                          // 完整中间状态（含中间变量）
    void restoreState(JsonObject state);             // 回溯：灌回任务对象（start() 之前）
    int stateVersion();                              // 快照版本，字段增删时兼容
    boolean validateState(SmartMaidEntity maid, JsonObject state);  // 回溯后校验环境
}
```

**策略：回溯为默认，校验为兜底**（不再是"重新扫描"路线；扫描只作为校验手段）。

### 6.3 不能落盘的东西

| 类别 | 处理 |
|---|---|
| 实体引用 | 只存 `uuid` / 类型 id；找不到 → 目标失效 |
| `ItemStack` | 可用 NBT 存（自带序列化） |
| **瞬时子状态**（挖掘裂纹 `digTicks`、寻路中间节点、挥动计时） | **不落盘**，恢复时该子步骤重新开始 |
| 世界对象（`Level` / `ServerPlayer`） | 不存，恢复时现取 |

### 6.4 逐类任务的**状态字段清单**

| 任务 | 必须落盘的中间变量 |
|---|---|
| `MineTask` | `range` / `yRange` / `count` 上限 / 扫描到的目标矿石 pos 集合 / 已挖集合 / 当前目标 pos / 已挖数量 / 扫描轮次 |
| `HarvestTask` | 结构类型 / `produce` 谓词原文 / `seed` / 已完成方块集合 / `count` / 已收数量 / 起点容错结果 |
| `FarmTask` | 目标作物 pos 集合 / 已收集合 / 已种集合 / `range` |
| `BuildTask` | 起点 pos / `height` / 已放置数量 / 当前 y |
| `SmeltTask` | **target 产物 id** / `count` / 已完成数 / **熔炉 pos** / 已投入输入 / 已投入燃料 / 等待 tick |
| `AttackTask` | 目标 `uuid` / 目标类型 id |
| **`ScriptTask`** | ★ **`vars` 变量表（`$collected` 等）/ 当前步索引 / 执行栈 / `stepLog` / 已执行步数** |
| `GuardTask` | `range`（本身不结束，无需进度） |
| `CraftTask` / `TransferTask` / `ChestStoreTask` / `FeedTask` / `EatTask` / `MoveToTask` | 瞬态且耗时短 → **不实现 `Resumable`**，恢复时重头来（回执/UI 如实标注） |

> **落地顺序**：先做 **`MineTask` / `HarvestTask` / `FarmTask` / `BuildTask` / `SmeltTask` / `ScriptTask`**。

---

## 七、持久化：与物品同级别

### 7.1 存到哪里

复用 `MaidDataManager` 的 NBT（不新开文件）：

```
<存档>/smartmaid/maids/<UUID>.dat
├── inventory        （既有：41 格）
└── tasks            （新增）
    ├── version: 1
    ├── longTerm: [ {id, groupId, cmd, params, mutexKey, state, seq, progress:{...}} ]
    ├── shortTerm:[ {id, groupId, cmd, params, mutexKey, state, pauseReason,
    │                 interruptCount, runAttempts, progress:{...}} ]
    └── lastTick
```

### 7.2 写入时机

| 时机 | 说明 |
|---|---|
| 每 100 tick | 与背包存档同频 |
| **队列结构变化**（入队/插队/推进/移除/重排） | 立即落盘 |
| **任一任务 `PAUSED` 时** | ★ 立刻落盘（"中间变量"最需要被记住的时刻） |
| 玩家退出 / 服务器停止 | 统一 flush |
| 死亡 | 清空并落盘（与现有"死亡清档"一致） |

### 7.3 恢复策略（已拍板：自动继续）

1. 恢复两个队列的结构与顺序；2. 每项 `restoreState()` → `validateState()`；
3. **自动继续**（短期优先，空则长期）；4. 校验失败项按策略处理（跳过/重头/丢弃）并**回执说明**；
5. 设置开关「重召后自动继续任务」（默认 **开**）。

### 7.4 兼容与风险

- NBT 加 `version`；**旧存档无 `tasks` 段 → 视为空队列**，不迁移不报错。
- ⚠️ 恢复时世界可能已变 → 必过 `validateState`。
- ⚠️ 存档体积：目标集合可能上千坐标 → 紧凑编码 + 单任务 `progress` ≤ **512 条**，超限降级"重头来"。
- ⚠️ 快照 `stateVersion` 变化 → 旧快照要么迁移要么放弃接续。

---

## 八、★★ 去重与互斥（v4 重写：按"动作 + 具体子目"判重）

> **为什么推翻 v3**：v3 用粗粒度互斥组（`craft` 组含 `smelt`）→ 主人说"把身上的矿物都烧一下"
> 会合法地产生**多个熔炼任务**（烧铁锭 / 烧金锭 / 烧铜锭），却被当成同一组重复**全部拒掉**。
> 互斥的目的应该是**"同一件事别下发两次"**，不是"同类型任务只准有一个"。

### 8.1 互斥键 `mutexKey` = 动作 + 具体子目

```
mutexKey = verb + ":" + targetKey
```

| cmd | verb | targetKey 从哪来 | 例子 |
|---|---|---|---|
| `smelt` | `smelt` | `item`（**产物** id） | `smelt:minecraft:iron_ingot` / `smelt:minecraft:gold_ingot` ← **两个不同键，可共存** |
| `craft` | `craft` | `item`（产物 id） | `craft:minecraft:iron_pickaxe` |
| `mine` | `mine` | 指定矿物 → `ore:<id>`；指定坐标 → `@x,y,z`；都没有 → `*` | `mine:ore:minecraft:diamond_ore` / `mine:*` |
| `harvest` | `harvest` | `produce` 归一化；没有 → `tree:*` | `harvest:tree:minecraft:oak_log` / `harvest:tree:*` |
| `attack` | `attack` | `target` 实体 id；没有 → `*` | `attack:minecraft:pig` |
| `guard` | `guard` | — → `*` | `guard:*` |
| `feed` / `eat` | 同名 | `item` 或 `*` | `eat:minecraft:golden_apple` |
| `move` / `look` / `break` / `place` / `use` | 同名 | `@x,y,z` | `move:@120,64,35` |
| `chestopen` / `chestput` / `chesttake` | `chest` | `@x,y,z` | `chest:@120,64,35` |
| `transfer` / `equip` / `store` / `drop` / `pickup` | `carry` | 物品 id 或 `*` | `carry:minecraft:iron_pickaxe` |
| `script` | — | **不参与互斥**，只参与**签名去重**（同脚本内容重复下发才拒） | — |

### 8.2 归一化规则（不做归一就判不出等价）

| 规则 | 说明 |
|---|---|
| 物品 id 补命名空间 | `iron_ingot` == `minecraft:iron_ingot` |
| tab 简写归一 | `#logs` == `#minecraft:logs` |
| **tag 与具体 id 不做等价** | `#minecraft:logs` 与 `minecraft:oak_log` 视为不同子目（除非有已知家族表，见下） |
| 已知家族表 | `oak` → `oak_log/oak_wood/stripped_oak_log…` 归一到 `tree:oak` |
| **相对坐标先换算成绝对** | `~` 必须先转绝对坐标，否则"在我脚下"和具体坐标判不出重复 |
| **数量类参数不参与键** | `count` / `range` 不进 `mutexKey`（否则"砍 8 根"和"砍 10 根"会被当两件事） |

### 8.3 三态判定（比"拒 / 不拒"更聪明）

| 情况 | 判定 | 说明 |
|---|---|---|
| **同 verb + 同具体子目** | **REJECT** | 真正的重复（两条 `smelt:minecraft:iron_ingot`）→ 回执 `duplicate` + `existing_id` |
| **泛化 vs 精确**（`mine:*` ↔ `mine:ore:diamond`；`harvest:tree:*` ↔ `harvest:tree:oak_log`） | **ACCEPT + 软提示** | 泛化请求是"你看着办"，**不该被精确项挡住**；但回执带 `possible_overlap: [ids]`，UI 标「可能重复」。**不静默** |
| **同 verb 但子目明确不同** | **ACCEPT** | ← **正是"把身上的矿物都烧一下"能展开成 3 项的原因** |

### 8.4 时间窗去重（治"两条线同时识别"）

- 同一 `mutexKey` 在 **T = 60 tick（3 秒）** 内被下发 → **只接受第一条**；
- 后续回执 `{"ok":false,"reason":"duplicate_recent","existing_id":"..."}`。

### 8.5 `groupId`：一条命令展开成多项

- 上层（桌宠 NLU / 菜单）把"把身上的矿物都烧一下"展开成 3 条 `smelt` 指令时，
  带同一个 **`groupId` + `parentLabel`（"烧所有矿物"）**；
- UI **分组显示**该组（可折叠、可整组取消）；
- **组内各项仍各自按 `mutexKey` 判重**（组内不会出现两个同键项）。
- **排序优化**：同一 group 内的多个 `smelt` 尽量**复用同一个熔炉**（避免来回跑）→ 组内按 target 排序，共享 `furnacePos` 提示。

### 8.6 来源优先级（谁赢）

| 情况 | 处理 |
|---|---|
| 主人显式（P2）与队列里已有的 P3 项同 `mutexKey` | **移除 P3 项，P2 入队**（不能因为桌宠先猜到了就拒绝主人） |
| P3 与 P3 同键 | 拒绝后者（`duplicate`） |
| P2 与 P2 同键 | 拒绝后者（并提示已有项） |

### 8.7 回执与可观测（不能静默丢弃）

```jsonc
{"ok":false,"reason":"duplicate","existing_id":"menu-2","note":"已有相同任务：烧成铁锭"}
{"ok":false,"reason":"duplicate_recent","existing_id":"menu-2"}
{"ok":false,"reason":"queue_full","queue":"SHORT"}
{"ok":true,"id":"menu-7","note":"已入队","possible_overlap":["menu-3"],"overlap_note":"队列已有更精确的同类任务"}
```

- 日志：`[SmartMaid-Debug] 入队判定：<mutexKey> → ACCEPT/REJECT(同 <existing_id>)/ACCEPT+overlap`
- 桌宠侧据此**不重复播报**（跨项目约定，写进 `HANDOVER.md` §5.1 那张"语义边界"表）。

### 8.8 特殊合并规则与资源占用（已拍板 2026-09-30）

**(a) `guard`：不允许并存，合并时取最大范围**

- `guard` 的 `mutexKey` 固定为 **`guard:*`**（不含 `range`）→ **全局只允许一个护卫任务**；
- 已有 `guard` 时又下发新的：**不新增、也不拒绝，而是把现有项的 `range` 更新为两者中的较大值**（`max`），回执 `{"ok":true,"merged":true,"note":"已并入现有护卫任务，范围取最大 12"}`；
- 依据：用户拍板"不允许，取最大范围"。
- 实现：`QueuedTask.params` 允许**白名单字段合并**（`guard` 仅 `range`），其余 cmd 不做参数合并（保持"拒绝"策略）。

**(b) 熔炉：一个熔炉同一时间只能烧一种矿，烧矿要取空闲熔炉**（已拍板）

这是**物理约束**，不是判重问题：MC 熔炉有 3 个输入槽，但语义上**不允许混烧**（否则铁锭和金锭会一起出，进度不可控）。

因此 `smelt` 执行时需要**熔炉租约（furnace lease）**：

```
FurnaceLease（服务端内存表）
  furnacePos -> { ownerTaskId, startedAtTick }
  acquire(maid, excludePositions) -> BlockPos | null   // 找空闲熔炉并登记
  release(furnacePos, taskId)                          // 完成 / 失败 / PAUSED 时释放
  isBusy(furnacePos)                                   // 已登记的跳过
```

- **"空闲"的定义**：① 未被其他任务登记；② 熔炉当前**没有正在烧炼的物品**（输入槽为空或只剩燃料）。
- `SmeltTask` 在 `PAUSED`（战斗 / 插队）时**释放租约** → 恢复时**重新 acquire**（可能换到另一个熔炉）。
- **没有空闲熔炉** → 该 `smelt` 项 `PAUSED(reason=BLOCKED)` + 上报 `task_blocked`，并且**不占用队列头**（让后面能跑的项先跑），有熔炉空出时再恢复。
- ⚠️ v4 草稿里写的"同组多个 `smelt` 尽量复用同一熔炉"**作废** —— 正确策略是**取空闲熔炉**，不复用、也不排队等同一个。
- 租约表**不持久化**（运行期资源，跨存档无意义）；重召后重新扫描环境自然重建。

---

## 九、状态机

```java
enum State { QUEUED, RUNNING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED }
enum PauseReason { COMBAT, PREEMPTED, LONGTERM_SUSPEND, USER }
```

```java
class QueuedTask {
    String id;                 // menu-1 / ws-7
    String queue;              // LONG / SHORT
    int seq;                   // 队列内序号（可重排）
    String cmd;
    JsonObject params;
    String mutexKey;           // §8.1 动作 + 具体子目
    String groupId;            // §8.5 一条命令展开出的多项共用
    String parentLabel;        // 组的显示名（"烧所有矿物"）
    String displayName;        // 服务端生成中文摘要
    State state;
    PauseReason pauseReason;
    int interruptCount;        // 被打断次数（打断=降一位）
    int runAttempts;           // 重跑次数（仅"不可接续"任务计数）
    JsonObject progress;       // Resumable.saveState()
    long enqueuedAtTick, startedAtTick, lastActiveTick;   // 时间窗去重用
    String result;
}
```

---

## 十、协议

### C2S

```jsonc
{"op":"enqueue","cmd":"mine","params":{"range":12,"count":8},"priority":"owner"}
{"op":"enqueue","cmd":"smelt","params":{"item":"minecraft:iron_ingot","count":8},
 "group":"g-1","group_label":"烧所有矿物"}          // ← 一条命令展开成多项，共用 group
{"op":"setLongTerm","id":"menu-1","long_term":true}
{"op":"promote","id":"L2"}          // 长期项「设为当前」（可打断短期）
{"op":"stop","id":"L2"}             // 长期项「停止」= 永久移除（二次确认）
{"op":"stopGroup","group":"g-1"}    // 整组停止
{"op":"move","id":"L2","to":0}      // 队列内调位置
{"op":"pauseCurrent"} / {"op":"resume","id":"menu-2"} / {"op":"cancelCurrent"}
{"op":"queueClear","queue":"SHORT"}
{"op":"query"}
```

### 既有指令的兼容扩展

```jsonc
{"cmd":"smelt","params":{...},"queue":true,"priority":"owner","long_term":false,"group":"g-1"}
```

### S2C 快照

```jsonc
{"combat":false,
 "longTerm":[{"id":"L1","cmd":"mine","name":"挖矿（直到喊停）","state":"queued","seq":0}],
 "shortTerm":[
   {"id":"menu-1","cmd":"mine","name":"挖矿 8 块","state":"running","progress":"12格/8块","interrupts":0},
   {"id":"g-1","label":"烧所有矿物","state":"queued","children":[
       {"id":"menu-2","cmd":"smelt","name":"烧铁锭 ×8","state":"queued","mutexKey":"smelt:minecraft:iron_ingot"},
       {"id":"menu-3","cmd":"smelt","name":"烧金锭 ×3","state":"queued","mutexKey":"smelt:minecraft:gold_ingot"},
       {"id":"menu-4","cmd":"smelt","name":"烧铜锭 ×5","state":"queued","mutexKey":"smelt:minecraft:copper_ingot"}]},
   {"id":"menu-5","cmd":"harvest","name":"砍树 8 根","state":"paused","pauseReason":"PREEMPTED","interrupts":1}],
 "food":18.0}
```

> 注意上面这一帧：**同一条"烧所有矿物"展开出的 3 个 `smelt` 子目，`mutexKey` 三个不同 → 全部入队**；
> 若再来一条"烧成铁锭"才会被拒。

### `status` 向后兼容扩展

保留 `{task, ai_busy}`（`MaidAIBridge.java:96-102`），**追加** `short_size` / `long_size` / `queue[]` / `long_term[]`。

---

## 十一、失败与保护

| 场景 | 策略 |
|---|---|
| 单项失败 | 默认 `SKIP` 继续；**连续 2 项失败降级 `abort`**（只清该队列） |
| 单项超时（60s） | 同失败 |
| **反复被打断** | `runAttempts` 只对**不可接续**任务计数，`≤ 3` 后 `SKIPPED`；可接续的不计次 |
| **长期任务无终点** | ① 工具耗尽/缺材料 → `PAUSED` + 上报 `task_blocked`；② 未召唤/离线时不存在（`noSave()`） |
| **长期任务静默卡死** | 无进展检测（N 分钟）→ `PAUSED` + 上报 |
| 存档体积 | 单任务 `progress` ≤ 512 条，超限降级"重头来" |
| 互斥误伤 | 主人显式 P2 永远胜过 P3；**泛化请求不被精确项拦截**（§8.3） |
| `guard` 合并 | 已有 guard 时，新 guard 只把 `range` 更新为 max，**不新增任务**（§8.8a） |
| **熔炉租约泄漏** | 任务结束 / 失败 / `PAUSED` **都必须 `release`**（`try/finally`）；泄漏会让后续所有 `smelt` 永远找不到空闲熔炉 |
| **无空闲熔炉时饿死** | 该 `smelt` 置 `PAUSED(BLOCKED)` 且**不占队列头**，让其他能跑的项先跑；有熔炉空出再恢复 |

---

## 十二、实施顺序与验收

| 步 | 内容 | 验收 |
|---|---|---|
| **Q1** | `QueuedTask` + **两个队列** + 状态机 + 插队/降位/重排/跨队列移动（离线自测） | 队列行为正确；长期不被短期饿死 |
| **Q2** | `MaidTaskManager` 双队列调度（短期优先 → 空则长期）+ `ticks` 每项归零 + `isAiBusy` 修正 + `try/finally` 保证推进 | 连发多条依次执行；短期清空后长期自动接管 |
| **Q3** | **兼容性回归（硬门槛）**：`/maidtasks`、`/maidai`、桌宠 WS、`script`、AutoTest **与改前一致** | 逐条比对，零回归 |
| **Q4** | 优先级与插队 + 长期队列操作（设为当前**可打断短期** / 停止=移除 / 调位置） | 砍树中被插挖矿 → 挖矿做完回来砍树 |
| **Q5** | 战斗打断（`PAUSED(COMBAT)` + **对象保留**）+ 战斗结束回队首 | 建到一半被僵尸打断 → 打完继续 |
| **Q6** | **去重与互斥（v4 智能版）**：`mutexKey`（动作+子目）+ 归一化 + 三态判定 + 时间窗 + `groupId` + 来源优先级 + **guard 合并（取 max）** + **熔炉租约** | ①**"烧所有矿物"展开的 3 个 smelt 子目全部入队**；②再发一条"烧铁锭"被拒且回执带 `existing_id`；③主人与桌宠同时砍树 → 只入队一次；④子目不同的砍橡树/砍白桦可共存；⑤再发 `guard range=12`（已有 `range=10`）→ **不新增任务，现有项 range 变 12**；⑥两个 smelt 项**不会同时占用同一个熔炉**；无空闲熔炉时该项 `BLOCKED` 且不占队头 |
| **Q7** | **`Resumable` 完整快照** + 6 类任务（`Mine`/`Harvest`/`Farm`/`Build`/`Smelt`/`Script`） | 会话内打断**零成本接续**，不重复已完成部分 |
| **Q8** | **持久化**（NBT `tasks` 段 + 写入时机 + 自动继续 + `validateState`） | 退出 → 重召 → 两队列与中间变量都在，且自动继续 |
| **Q9** | 协议（`op` 全集 + `group` + S2C 快照 + `status` 扩展 + 三态回执） | 界面正确；老调用方不受影响 |

**每步必须**：`gradlew build -x test` → 备份旧 jar → 部署 → **核对 jar 时间戳** → 真机。

---

## 十三、风险与回归面

| # | 风险 | 对策 |
|---|---|---|
| 1 | 改 `MaidTaskManager` 波及所有调用方 | Q3 硬门槛 |
| 2 | 推进断链 → 女仆站着不动 | `try/finally` 保证 `startNext()` 可达 |
| 3 | `ticks` 未归零 → 第二项瞬间超时 | 每项启动归零 |
| 4 | `isAiBusy` 未改 → 换项时 Goal 插入抖动 | `current != null \|\| !short.isEmpty() \|\| !long.isEmpty()` |
| 5 | 接续做错 → 重复劳动/重复消耗材料 | 回溯后先校验；消耗类操作**不做幂等假设**，宁可重头 |
| 6 | 快照字段遗漏 → 恢复后行为错乱 | 字段清单进文档（§6.4）；新增任务必须实现 `Resumable` 或显式声明"不支持接续" |
| 7 | 存档体积膨胀 | 紧凑编码 + 512 条上限 + 降级 |
| 8 | 恢复时世界已变 | 必过 `validateState` |
| 9 | **去重误伤**：主人显式请求被旧项挡住 | P2 > P3（§8.6） |
| 10 | ~~互斥组划分过粗~~（v3 问题） | **已解决**：改为 `mutexKey` = 动作 + 具体子目，且泛化请求不被精确项拦截（§8.3） |
| 11 | **归一化漏判** → 本该拒的没拒（如 `#logs` 与 `oak` 家族） | 家族表 + 真机反馈迭代；宁可漏判也不误拒 |
| 12 | 打断-重跑活锁 | `runAttempts ≤ 3`（仅不可接续任务） |
| 13 | 长期任务静默卡死 | 无进展检测 → `PAUSED` + 上报 |
| 14 | 入队校验过严导致误拒 | 入队只校验参数合法性 + 去重；`canStart` 留到执行时 |
| 15 | **展开逻辑放错层**：若服务端自己展开"所有矿物"，会与上层重复 | **展开放在上层**（桌宠 NLU / 菜单），服务端只做精确判重；`group` 字段只用于分组，不参与展开 |
| 16 | **熔炉被非女仆实体占用**（玩家自己放了东西在烧） | `isBusy` 判定要**实际读熔炉输入槽**，不只信自己的租约表；占用了就换下一个 |
| 17 | 重召后租约表清空 → 误判熔炉空闲 | 恢复时会**重新扫描环境**（`acquire` 里先查输入槽），与"租约表不持久化"配套，无冲突 |

---

## 十四、与既有设计的关系

- **`script`**：层级不同（`script` = 一条指令内含多步；队列 = 多条指令排序）。队列项**可以**是 `script`；
  `ScriptTask` 必须实现 `Resumable`（`vars` 是关键中间变量）；`script` **不参与互斥**，只参与签名去重。
- **`DESIGN_MAID_MENU_N1.md`**：任务页 = 两个队列的可视前端；长期队列每项要有「设为当前 / 停止 / 上移下移」；
  同一 `group` 的子项**折叠成一组**显示（"烧所有矿物" ▸ 3 项）。
- **战斗系统**：**完全不动**。
- **`MaidDataManager`**：任务与背包同文件、同节奏。
- **桌宠侧（跨项目）**：`priority:"owner"` 标记 + 展开成多项时的 `group`；拒绝回执要消费（不重复播报）。

---

## 十五、决策记录（**已全部拍板，2026-09-30**）

> **承接人注意：本文所有设计决策已定，不需要重新讨论。** 下表是结论汇总。

| # | 问题 | 结论 |
|---|---|---|
| 1 | 归一化范围：`#minecraft:logs` 与 `minecraft:oak_log` 是否同一子目 | **不算**（tag 是"任意原木"、oak_log 是"橡木原木"，语义有别）。只对**同族**做家族归一（`oak` → `oak_log`/`oak_wood`/`stripped_oak_log`…） |
| 2 | 泛化请求与精确项共存时是否给可见提示 | **给**：`possible_overlap` + UI 标「可能重复」，但**不拒绝** |
| 3 | `guard` 是否允许多个（不同 range） | **不允**；合并时**取最大 range**（§8.8a） |
| 4 | 时间窗去重时长 T | **60 tick（3 秒）** |
| 5 | 多个 `smelt` 与熔炉的关系 | **一个熔炉同一时间只能烧一种矿**；`smelt` **取空闲熔炉**（不复用、不排队等同一个）；无空闲 → `PAUSED(BLOCKED)` 且**不占队头**（§8.8b） |
| 6 | 接续实现范围 | 先 **6 类**：`Mine` / `Harvest` / `Farm` / `Build` / `Smelt` / `Script` |
| 7 | 长期 / 短期 | **两个独立队列**；短期清空才执行长期 |
| 8 | 长期「设为当前」 | **立刻取得执行权，可打断短期任务**（当前项 `PAUSED` 保留） |
| 9 | 长期「停止」 | **永久移除该项**（UI 二次确认） |
| 10 | 互斥策略 | **拒绝 + 回执**；不做参数合并 —— `guard` 的 `range` 取 max 是**唯一例外** |
| 11 | 重召后 | **自动继续**（+ 设置开关，默认开） |
| 12 | 优先级 | P0 安全层 / P1 战斗（两者都不进队列）/ P2 主人实时交互 → 插短期队首 / P3 → 追加队尾 |
| 13 | 展开成多个子目 | **展开放上层**（桌宠 NLU / 菜单），服务端只做精确判重 |
| 14 | `script` 与互斥 | `script` **不参与 `mutexKey` 互斥**，只参与整脚本内容的签名去重 |

**实现期若遇到本文没覆盖的新问题**，按下面三条原则类推，拿不准再回来问：
1. **同一件事不重复下发，子目不同可以共存**；
2. **主人的显式诉求优先于桌宠的自动识别**；
3. **泛化请求（"你看着办"）不该被精确请求挡住，但要有提示**。
