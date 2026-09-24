# 原子层指令协议（Atomic Command Protocol）· 设计文档 v4

> 状态：**P1+P2+P3 全部完成 + AI 组装真机验证**——模组编译通过、桌宠 `test_nlu`/`test_maid_loop`/`test_chat_unit`/`test_chat_chain` 全过。
> 已移除「硬编码砍树模板」（违背 AI 自由组装初衷），改为 **AI 用原子指令自由组装任意 script**；
> 真实调用网页版 AI 验证组装能力（砍树/挖矿/打猪/存箱）符合预期；真机发现并修复 6 个 bug（见「真机与 AI 组装验证记录」）。
> 范围：让 AI 能**一次性规划多步指令流**，女仆模组侧按脚本顺序执行，支持**查询决策、条件分支、循环、步间变量流转**。
> 关联：`DESIGN_AI_INTERFACE.md`（既有 24 指令）、`HANDOVER.md`、桌宠侧 `desktop-pet/nlu/mod_contract.py` / `game/maid_intent.py`。

## P1 实现清单（2026-09-21，编译通过）

```
entity/ai/structure/          # 通用结构识别层（StructureRegistry）
├── StructureType.java         # 结构抽象：core/support/isStructure/scanBlockLimit
├── StructureComponent.java    # 连通组件（cores + supports）
├── StructureRegistry.java     # 注册表（tree + ore 已注册；crop/container 预留）
├── TreeStructure.java         # 树：#logs core + #leaves support + 物种对齐表（9 树种）
├── OreStructure.java          # 矿脉：矿石 tag core（coal/iron/gold…_ores），无 support；isOreProduce 推断
├── ProduceFilter.java         # produce → Predicate<BlockState>（精确/#tag/wood家族 + 物种推导）
├── StructureScan.java         # find：范围递增(12→…→32) + BFS 组件 + 产物过滤 + 物种对齐
└── HarvestTask.java           # harvest：按 produce 只挖命中方块，**按 StructureType 扩展**（树/矿通用）
entity/ai/script/              # 脚本引擎（Atomic Command Protocol §3）
├── ScriptTask.java            # 状态机：顺序 + if/loop/assign/terminate + 子任务驱动 + 步数上限
│                              #   + isContinuous（不被 Manager 60s 截断）+ 总超时 10min + 单步 60s
│                              #   + terminate result 支持 $var 解析 + harvest 按 produce 推断结构
├── ScriptContext.java         # 变量表 + 步骤日志 + 终止信号 + 执行步计数
├── ScriptRef.java             # $var.path 引用 + 整数算术(+ -) + 条件求值(eq/ne/gt/…/exists)
├── ScriptQueries.java         # 查询原子：inventory/block_at/find/find_entity/find_item/distance
└── ScriptQueryTask.java       # 查询结果包装（start 即完成，resultJson 供绑定）
改动：
├── entity/ai/maidtask/MaidAITask.java   # +resultJson()（结构化结果，缺省 null）
├── entity/ai/maidtask/MineTask.java     # +resultJson()（{collected,target}，AI `mine as ore` 可用）
├── entity/ai/bridge/MaidAIBridge.java   # +script 分支；buildTask/parsePos 改 public 复用
└── entity/ai/perception/PerceptionModule.java # task_done 事件附带任务 resultJson（脚本最终回执）
```

> AutoTest 脚本/查询/结构用例示例：`tools/autotest.script.example.json`（复制到游戏
> `config/smartmaid/autotest.json` 进游戏自动执行；script 受理回执可断言，最终回执走感知
> `task_done` 事件上行）。

## P2 实现清单（2026-09-21，桌宠侧，`test_nlu`/`test_maid_loop` 全过）

```
desktop-pet/nlu/mod_contract.py   # +SCRIPT_COMMANDS（script/harvest + 6 个查询类，独立于 COMMANDS 不破坏意图对账）
                                  # +validate_script（steps 递归校验 + 逐条 script_clamp）
                                  # +Command.raw_params（script params 原样透传，模组严格解析）
                                  # ★clamp 对含 $/+- 的 int 表达式**原样透传**（否则 harvest count 被丢弃，见验证记录）
desktop-pet/game/maid_intent.py   # +【script({...json...})】解析（JSON params + SCRIPT_ALLOWED 校验）
                                  #  script 默认 cancel_previous=true（复合任务接管女仆）
                                  # ★容错：剥「【指令：…】」前缀 + 未闭合 script（AI 漏 】）提取
desktop-pet/game/maid_link.py     # TASK_NAMES 补 script/harvest；task_done 事件提取 result.reason 生成
                                  # 「女仆脚本任务完成：砍树完成」类中文（最终回执注入）
desktop-pet/game/maid_loop.py     # script 受理回执显示脚本名（「我的任务已受理：脚本「砍橡树」」）
desktop-pet/prompt*.txt           # 4 个人格 prompt：script 用法 + 组装教学 + 「先 chestopen 再存取箱子」
```

## P3 实现清单（2026-09-21，AI 自由组装教学 + 验证）

```
desktop-pet/prompt_min.txt        # 极简标签式 prompt 同步 script 说明（计数变量初始化）
desktop-pet/prompt*.txt           # 4 人格 prompt 注入「AI 原子指令自由组装」教学：
                                  #   原子指令（查询 inventory/find/block_at/… + 动作 harvest/move/…）
                                  #   + 组装模式（as/$var/if/loop/assign/terminate）
                                  #   网页版完整版=完整教学+组装示例；API 精简版=精简教学+紧凑结构示例（省 token，仍 <6000）
                                  #   +「先 chestopen 再存取箱子」前置约束
desktop-pet/tests/test_nlu.py     # +test_script_contract（script 契约/递归校验/拒绝）
desktop-pet/tests/test_maid_loop.py # +test_dsl_script_parse（【script({...json...})】解析 + 容错）
```

> **方向更正（2026-09-21 晚）**：~~`nlu/planner.py` 硬编码砍树模板~~ 已**移除**——违背「AI 自由组装原子指令」
> 初衷。组织任务完全交给 AI：AI 按 prompt 教学用原子指令组装任意 script；执行反馈（task_done 回执
> 带 reason/collected/target）注入下一轮，AI 据此续找/修正。模组侧 `ScriptTask` 引擎是唯一执行器。
> 其余 `P4` 阶段原本的"更多结构类型"中的 `ore` **已提前落地**（AI 真机组装出 `find(structure=ore)`）。

## 真机与 AI 组装验证记录（2026-09-21）

**AI 组装能力验证**（真实调用网页版 DeepSeek，`memory=False` 隔离会话，5 个模拟任务）：砍树/凑够返回
→ 完整 script（find + loop 续找 + assign 计数 + terminate 回执，vars 初始化正确）；挖矿 → `find(ore)`+loop
或单条 `mine as ore`；打猪 → 单条 `attack(target=)`。**AI 已能自由组装多步任务**。

**API 端组装测试 + 修复（2026-09-21 晚）**：网页版组装验证通过后，补做了 **官方 API 端**（`deepseek-api` +
`prompt_api.txt` 精简人设）的同样 5 任务测试（`desktop-pet/tools/probe_ai_assembly_api.py`，每轮新建
`ChatContext` 隔离会话，等价 `memory=False`）：
- **首测暴露格式错误**：精简人设**没有 script 结构示例**（只有概念说明），AI 自由发挥出**非法 JSON 结构**——
  砍树 `{"find":{...},"as":"$tree"}` 缺 `cmd/params` 字段、loop 用字符串 while + `steps` 数组而非
  `{"while":{"var","op","val"},"body":[...]}`、`if` 写成字符串 → `validate_script` 拒绝（`steps[0] 未知指令: None`）。
- **对照实验定位根因**：同一 API 客户端 + 完整人设（`prompt.txt`，含完整示例）→ 组装完全正确；
  精简人设 → 格式错误。**根因 = prompt 教学缺结构示例，不是模型能力。**
- **修复**：`prompt_api.txt` / `prompt2_api.txt` 的 script 段追加**紧凑结构示例**（照抄格式、勿自创字段名），
  `prompt_min.txt` 的 script 伪代码改成真实 JSON 示例。
- **重测结果**：砍树/凑够返回 → script 全部通过 `validate_script`（`cmd/params/as`、`if:{var,op,val}`、
  `loop:{while,body}`、`assign`、`terminate` 结构合规，vars 正确初始化）；挖矿 → `mine(range,count)`；
  打猪 → `attack(target=minecraft:pig)`；存箱 → `chestopen`（先开箱，符合「先 chestopen 再存取」约束）。
- **回归**：`tests/test_nlu.py`（script 契约）、`tests/test_maid_loop.py`（DSL script 解析）、
  `tests/test_chat_unit.py`（prompt 组装/极简对齐）全部通过。
- 测试脚本：`desktop-pet/tools/probe_ai_assembly_api.py`（`--persona/--mode/--pet-name/--out`，结果写
  UTF-8 文件，避免 shell 编码问题）。

**真机发现并修复的 bug（6 个）**：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 砍树 `Harvest start count=0` 无限收割 → 60s 超时强杀、无回执 | `mod_contract.clamp` 对 int 参数 `int("$target_count - $collected")` 失败**丢弃** → 模组收不到 count | clamp 对含 `$`/`+-` 的表达式**原样透传**（模组 `ScriptRef` 解析） |
| 2 | 计数 `$collected` 未初始化 → 表达式解析失败 | planner 脚本 vars 缺 `collected` | vars 初始化 `collected:0`（prompt 教学同样要求） |
| 3 | script 被 Manager 60s 强杀（砍树/移动耗时超 60s） | `MaidTaskManager` 统一 60s 上限 | `ScriptTask.isContinuous()=true` + 内部**总超时 10min + 单步 60s** |
| 4 | AI 挖矿用 `structure="ore"` 报"未知结构" | 模组只有 tree 结构 | **注册 `OreStructure`**；`harvest` 按 StructureType 扩展（不再硬编码树），produce 含 `_ores` 自动推断 ore |
| 5 | AI `mine as ore` 引用 `$ore.collected` 失败 | `MineTask` 无 `resultJson()` | `MineTask.resultJson()` 返回 `{collected,target}` |
| 6 | AI 偶发 `【指令：attack(...)】` / 漏 script 的 `】` | AI 输出格式不稳定 | `maid_intent` 容错：剥「指令：」前缀 + 未闭合 script 提取 |

**产物过滤验证**：`find(structure=tree, produce=#minecraft:oak_logs)` 实测命中 `matches=["minecraft:oak_log"]`
（金合欢被过滤）✓；`find` 组件 BFS 改独立上限后 `remaining` 准确（不再低估 5 vs 60）。

**部署**：最新 jar 含 ore 结构 / MineTask.resultJson / ScriptTask 超时护栏。

## 调试与定位（真机问题排查）

模组侧日志统一走 `MaidDebug`（前缀 `[SmartMaid-Debug]`，落 `游戏/logs/latest.log`，**GBK 编码**）：

| 关键字 | 含义 |
|---|---|
| `Script start: <名> steps=N` | 脚本开始（步数） |
| `Script 步开始: <cmd> → <taskId>` | 每步下发子任务 |
| `Script find 命中: tree pos=[...] remaining=N matches=[...]` | 结构识别命中（tree/ore + 产物匹配列表） |
| `Script find 未命中: ... searched=N` | 范围内没找到（含扩大后上限） |
| `Script 步失败: <cmd>（<原因>） fail=abort/continue` | 单步失败 + 失败策略 |
| `Script 终止: <名> → <reason>（已执行 N 步）` | 结束原因 |
| `Harvest 收割 N 块（目标 ...）` / `Harvest 结束` | 收割进度/结束 |

**高噪开关**：`MaidDebug.VERBOSE=true`（`MaidDebug.java`）可看更细——每步完成结果、find 每轮范围扩大、组件产物过滤/物种对齐失败、Harvest 跳过目标。排查完改回 `false`。

**AutoTest**：`tools/autotest.script.example.json` → 游戏 `config/smartmaid/autotest.json`，日志搜
`AutoTest [id] 断言=PASS/FAIL 期望=... 实际=...`（可断言 script 受理回执）。

**桌宠侧**：
- `desktop-pet/logs/crash.log` —— DSL 解析失败（`MaidIntentParseError`）
- `desktop-pet/logs/maid_link.log` —— WS 收发摘要、task_done 事件
- `logs/perf.log` —— AI 调用耗时

定位流程：跑 AutoTest/联调 → 抓 latest.log（gbk 解码）搜 `Script` / `find` / `Harvest` / `AutoTest`
→ 对照最终回执（task_done 事件 result.reason）→ 必要时开 VERBOSE 看细节。

---

## 〇、设计决策（用户拍板）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 查询类原子做不做 | **做**。没有查询，AI 无法"先看再动" |
| 2 | 变量 / 步间数据流做不做 | **做**。`as` 绑定 + `$var.path` 引用 + `assign` 算术 |
| 3 | AI 一次性规划全部指令流 | **做**。新增 `script` 指令，模组侧 `ScriptTask` 顺序执行 |
| 4 | 条件分支 / 循环 | **做**。`if/else` + `loop`（while + max_iter） |
| 5 | 复合结构识别 | **通用化**。StructureRegistry 注册表，tree / **ore** 已注册，不写死 |
| 6 | 目标产物过滤 | **做**。`produce` 参数在解析期编译成块匹配谓词；要橡树原木 → 金合欢树被过滤 |
| 7 | 找不到扩大查询范围 | `find` 内置 `range → max_range, expand` 递增；上限防性能消耗 |
| 8 | 默认护栏值 | `max_steps=64`、`find.max_range=32`、`loop.max_iter=20`、单步 60s、脚本总 10min |
| 9 | 任务如何组织 | **AI 自由组装**（撤硬编码砍树模板）。AI 用原子指令组装任意 script，执行反馈注入 AI 续找/修正 |
| 10 | 结构类型范围 | tree + **ore**（AI 组装出 `find(structure=ore)`）；crop/container 预留 |

---

## 一、背景与痛点

现状链路"AI 发一条指令 → 女仆执行 → 回执注入"已跑通，但**组合能力为零**：

1. 一次回复只能带一条 `【指令】`（`maid_intent.parse` 只取第一条）
2. 没有查询指令，AI 无法根据游戏内实际情况决策
3. 回执无结构化数据（没有坐标/数量/是否存在）
4. 集成指令（mine/craft…）是黑盒，内部编排不可定制
5. 无失败策略 / 分支 / 循环 / 步间数据流

## 二、目标与设计原则

- **AI 下意图、本地做执行**：AI 只负责"规划 + 填参 + 定目标"，所有原子动作由模组确定性逻辑执行
- **查询即原子**：`inventory`/`block_at`/`find`/`distance` 是组合的地基，全部无副作用
- **结构识别通用化**：`StructureRegistry` 注册结构类型，`find`/`harvest` 按类型+产物过滤，新增结构不碰执行器
- **产物过滤在执行器内部**：`produce` 参数解析期编译为 `Predicate<BlockState>`，识别与收割两处都按它过滤
- **回执即上下文**：每步结构化 `result` 可被变量引用，脚本结束汇总注入下一轮 AI
- **向后兼容**：既有 24 指令、WS 协议、桌宠闭环全部不动；`script`/`find`/`harvest`/查询类均为新增

---

## 三、脚本语言（ScriptTask 执行模型）

### 3.1 控制原语（最小完备集）

| 原语 | 用法 | 说明 |
|---|---|---|
| `as` | 挂在任意指令上 | 把该步 `result` 绑定到变量，后续 `$var.path` 引用 |
| `$var.path` | 出现在任意 params | 模组侧执行前替换；`$last.<field>` 引用上一步 |
| `if` | `{"if":{"var","op","val"},"then":[...],"else":[...]}` | op ∈ `eq/ne/gt/ge/lt/le/exists`；else 可省略 |
| `loop` | `{"loop":{"while":{...},"max_iter":N,"body":[...]}}` | 条件同 if；超 max_iter 自动终止该循环 |
| `assign` | `{"assign":"var","expr":"$a + $last.count"}` | 极简整数算术（`+ -`） |
| `terminate` | `{"terminate":{"reason","result?"}}` | 提前结束整个 script，发回执 |

- **step 结构**：每步是一个对象，`{"cmd","params","as"}` 或控制原语（if/loop/assign/terminate）
- **禁止嵌套**：step 内不允许再出现 `script`
- **引擎自动变量**：`$maid.pos`（脚本开始时女仆坐标，"回来"锚点）；`$target_count`（AI 从自然语言推断的目标数量）

### 3.2 指令统一执行产物

每条指令执行产物 = `StepResult{ ok, state, result }`，`result` 为**结构化 JSON**（可被 `$var.path` 引用）。

---

## 四、原子指令集

### A. 动作类（改世界）

`move` / `look` / `break` / `place` / `use` / `equip` / `store` / `drop` / `transfer` / `attack` / `wait` / `sit` / `stop`

### B. 查询类（无副作用）

| 指令 | 参数 | 返回 result |
|---|---|---|
| `inventory` | item?（支持 `#tag`，如 `#axe`） | has, count, slot, items[] |
| `block_at` | pos | block, hardness, breakable, tool_required |
| `find` | structure, produce?, range, max_range?, expand? | found, pos, structure, produce_matches[], remaining |
| `find_entity` | type?, range | found, uuid, type, pos, dist |
| `find_item` | item?, range | found, pos, count |
| `distance` | pos | dist |

### C. 结构识别（通用，本次核心新增）

> 替代初版的 `find_tree`/`break_tree` 特判，改为**通用 `find`/`harvest` + StructureRegistry**。

---

## 五、通用结构识别层（StructureRegistry）

### 5.1 结构类型注册表

```java
// 每个 StructureType：单一事实来源，新增结构只需注册，不改执行器
interface StructureType {
    String id();                                   // "tree"
    Predicate<BlockState> coreBlock();             // 树 = #minecraft:logs
    Predicate<BlockState> supportBlock();          // 树 = #minecraft:leaves
    boolean isStructure(Component comp);           // 连通组件是否构成该结构
    List<BlockState> produces(Component comp);     // 该结构可产出物（供 produce 过滤）
    default int scanBlockLimit() { return 20_000; }
}
```

| 结构 id | core | support | 判定 | 产物 |
|---|---|---|---|---|
| `tree` | `#minecraft:logs` | `#minecraft:leaves` | 连通组件内 core≥1 且 support≥1 | 各原木/去皮/原木块（logs 家族） |
| `ore` | 矿石 tag（coal/iron/gold…_ores） | — | core≥1（单块矿也是结构） | 矿石家族 |
| `crop`(预留) | 成熟作物块 | 耕地 | `ripe_only` 时只认成熟 | 作物产物 |
| `container`(预留) | 箱子/熔炉/工作台 | — | 单方块 | 其内容 |
| … | | | | |

### 5.1.1 树叶定义（`tree` 的 support 判定，细化）

**树叶不是单一种类**，`tree` 的 support 用 **`#minecraft:leaves` tag** 判定——tag 是单一事实来源，天然覆盖全部树叶、新增树种自动纳入，**不在代码里枚举种类**。26.2 该 tag 实测覆盖以下 11 种（wiki 库实证 + MC 官方 tag 对齐）：

| 树叶 | 原木对应 |
|---|---|
| `minecraft:oak_leaves` 橡树叶 | oak_log 橡木原木 |
| `minecraft:spruce_leaves` 云杉叶 | spruce_log 云杉原木 |
| `minecraft:birch_leaves` 白桦叶 | birch_log 白桦原木 |
| `minecraft:jungle_leaves` 丛林叶 | jungle_log 丛林原木 |
| `minecraft:acacia_leaves` 金合欢叶 | acacia_log 金合欢原木 |
| `minecraft:dark_oak_leaves` 深色橡树叶 | dark_oak_log 深色橡木原木 |
| `minecraft:mangrove_leaves` 红树叶 | mangrove_log 红树原木 |
| `minecraft:cherry_leaves` 樱花叶 | cherry_log 樱花原木 |
| `minecraft:pale_oak_leaves` 苍白橡树叶 | pale_oak_log 苍白橡木原木 |
| `minecraft:azalea_leaves` 杜鹃叶 | (杜鹃丛，无对应原木) |
| `minecraft:flowering_azalea_leaves` 盛开的杜鹃叶 | (杜鹃丛，无对应原木) |

**物种对齐（严格模式）**：当 `produce` 指定了 wood 家族（如 `oak`）时，`find` 要求组件内 **core 命中该原木家族 且 support 命中对应树叶家族**——`oak_log` 配 `oak_leaves` 才算一棵"橡树"，防止"一棵桦树旁粘着两片橡树叶"被误判为橡树。对齐表即上表前 9 行的"原木↔树叶"映射；`produce` 缺省（任何树）时**只要求 support≥1（任意树叶），不做物种对齐**。杜鹃叶因无对应原木，只在"任何树"宽松模式下充当 support，不参与物种对齐。

**边界**：
- `azalea_leaves`/`flowering_azalea_leaves` 只作宽松模式的 support，不算任何物种的原木产物
- 玩家用树叶方块搭的"人造树"同样满足判定（原木+树叶连通即认），符合"树"语义
- `harvest` 默认**只收割 core（原木）**，不破坏树叶；`clear_leaves:true` 时连命中 support 一起清（可选）

### 5.2 结构识别算法（以 tree 为例）

```
find(structure="tree", produce="minecraft:oak_log", range=12, max_range=32, expand=4)

R = range
loop:
  扫描以女仆为中心的 R 范围（限 scanBlockLimit 方块）：
    ① 收集 core 方块（#logs）
    ② 对每个 core，沿「core ∪ support」做 BFS/Union-Find 连通组件，去重
    ③ 组件构成结构（tree：core≥1 且 support≥1）
    ④ ★产物过滤（两段）：
       a. core 过滤：组件内 core 集合是否命中 produce 谓词（如 #minecraft:oak_logs）
          ── 命中 → 候选；不命中（如金合欢）→ 该组件被过滤
       b. 物种对齐（仅 produce 指定 wood 家族时）：support 是否命中对应树叶家族
          （如 produce=oak → 需 oak_leaves；§5.1.1）
    ⑤ 返回第一个命中组件：{found:true, pos, structure, produce_matches[], remaining}
  R += expand；若 R > max_range → {found:false, searched:max_range}
```

### 5.3 产物过滤（`produce` 参数 → 块匹配谓词）

执行器**解析期**把 `produce` 编译为 `Predicate<BlockState>`，**识别与收割共用同一谓词**：

| produce 写法 | 编译结果 |
|---|---|
| `minecraft:oak_log` | 精确 id 匹配 |
| `#minecraft:oak_logs` / `#logs` | tag 匹配 |
| `oak` | **wood 家族展开**：oak_log / oak_wood / stripped_oak_log / stripped_oak_wood 等 |
| 缺省 | 匹配该结构全部产物（任何树） |

- 用户要 `oak_log` → 金合欢树/白桦树在识别时被过滤，`find` 只返回橡树
- `harvest` 收割时**只破坏命中谓词的方块**（同树上混有别的原木也不误伤）
- 该谓词在 `find` 与 `harvest` 之间通过 `$tree.produce_matches` 保持一致

### 5.4 收割原子 `harvest`

```json
{"cmd":"harvest","params":{"pos":"$tree.pos","produce":"minecraft:oak_log","count":4}}
{"cmd":"harvest","params":{"pos":"$ore.pos","produce":"#minecraft:iron_ores","count":4}}
```

- 走到 pos → 逐块挖命中 produce 的 core（自动换斧/镐，走 `MaidActions.equipBestToolFor`）
- **BFS 扩展按 StructureType**（树=logs∪leaves / 矿=矿石），不再硬编码树
- `structure` 参数可选：显式指定 > 从 produce 推断（含 `_ores` → ore）> 缺省 tree
- 达到 `count` 或该结构产物耗尽 → 停；返回 `{collected, remaining, pos}`

---

## 六、完整示例：砍树（含产物过滤）

AI 收到「砍一些橡树」→ 一次性规划（`as`/`if`/`loop`/`terminate`/`assign`/`find`/`harvest`）。此例与真机 AI 生成一致：

```json
{"id":"tree-1","cmd":"script","cancel_previous":true,
 "params":{
   "name":"砍橡树",
   "vars":{"target_count":6,"collected":0,"wood":"#minecraft:oak_logs"},
   "steps":[
     // ① 查斧头：有→装备；无→查徒手可破坏性
     {"cmd":"inventory","params":{"item":"#axe"},"as":"axe"},
     {"if":{"var":"axe.has","op":"eq","val":true},
      "then":[{"cmd":"equip","params":{"item":"#axe"}}],
      "else":[
        {"cmd":"block_at","params":{"pos":"$maid.pos"},"as":"ground"},
        {"if":{"var":"ground.breakable","op":"eq","val":false},
         "then":[{"terminate":{"reason":"无斧头且徒手无法破坏原木"}}]}
      ]},

     // ② 找橡树：12→16→…→32，找不到则终止+回执
     {"cmd":"find","params":{"structure":"tree","produce":"$wood",
                             "range":12,"max_range":32,"expand":4},"as":"tree"},
     {"if":{"var":"tree.found","op":"eq","val":false},
      "then":[{"terminate":{"reason":"32 格内没有橡树"}}]},

     // ③ 循环：以「已收 < 目标」为条件，收割 + 续找
     {"loop":{"while":{"var":"collected","op":"lt","val":"$target_count"},"max_iter":20,
        "body":[
          {"cmd":"move","params":{"pos":"$tree.pos"}},
          {"cmd":"harvest","params":{"pos":"$tree.pos","produce":"$wood",
                                     "count":"$target_count - $collected"},"as":"cut"},
          {"assign":"collected","expr":"$collected + $cut.collected"},
          {"if":{"var":"cut.remaining","op":"eq","val":0},
           "then":[
             {"cmd":"find","params":{"structure":"tree","produce":"$wood",
                                     "range":12,"max_range":32,"expand":4},"as":"tree"},
             {"if":{"var":"tree.found","op":"eq","val":false},
              "then":[{"terminate":{"reason":"周边没有更多橡树",
                                    "result":{"collected":"$collected","target":"$target_count"}}}]}
           ]}
        ]}},

     // ④ 收工：回起点 + 回执
     {"cmd":"move","params":{"pos":"$maid.pos"}},
     {"terminate":{"reason":"砍橡树完成",
                   "result":{"collected":"$collected","target":"$target_count"}}}
   ],
   "fail":"abort","max_steps":64,"report_each":false
  }}
```

执行语义：查斧 → 分支换斧/徒手/终止；`find` 过滤金合欢只认橡树并递增范围；`harvest` 只挖橡树原木；
循环以 `collected < target_count` 为准（达标即停），砍空当前树未达标 → 续找下一棵，找不到 → terminate 回执。
> 关键：计数变量 `collected` 必须在 `vars` 初始化为 0（否则 `$target_count - $collected` 表达式解析失败 → harvest count 失效）。

---

## 七、回执设计

### 汇总回执（默认）
```json
{"ok":true,"state":"done","step":"terminated",
 "result":{
   "task":"script","name":"砍橡树","reason":"砍橡树完成","completed":14,
   "vars":{"target_count":6,"collected":6,"wood":"#minecraft:oak_logs"},
   "steps":[
     {"cmd":"inventory","ok":true,"result":{"has":true,"count":1}},
     {"cmd":"equip","ok":true,"result":{"ok":true}},
     {"cmd":"find","ok":true,"result":{"found":true,"pos":[...],"structure":"tree","produce_matches":["minecraft:oak_log"],"remaining":8}},
     ... 全程（含 if/loop 内分支）扁平化
   ],
   "terminated":{"at":12,"reason":"砍橡树完成"}
 }}
```

### 逐步回执（`report_each:true`）
每步（含分支内）完成即发 `command_result`（`state:"running", step:N`），桌宠/AI 可 `cancel` 中途干预。

### 失败回执
任意 `terminate`/abort/超时 → `ok:false` + `reason` + 已执行 steps 摘要。

---

## 八、桌宠侧（AI 规划层）

1. `mod_contract` 增 `script`/`find`/`harvest`/查询类契约（steps 校验 + 参数 clamp，int 表达式透传）
2. `maid_intent` 扩展：`【script(steps=[...])】` 完整解析并校验；未知指令/缺必填 → 丢弃不执行；
   容错剥「指令：」前缀 + 未闭合 script
3. prompt 注入：原子指令清单 + 组装模式教学 + 组装示例 + 「先 chestopen 再存取箱子」
4. 回执注入：每步 `result` 压缩成中文行注入下一轮（`[系统] 砍橡树：inventory→有斧头；find→找到橡树(8原木)；…`）

> ~~NLU 本地计划器模板~~ 已废弃：组织任务**完全交给 AI 自由组装**（见「方向更正」）。

---

## 九、安全与性能护栏

| 项 | 约束 |
|---|---|
| 步数 | `max_steps` 默认 64（含 if/loop 展开的**实际执行步**） |
| 循环 | `max_iter` 默认 20/循环 |
| 单步超时 | 60s（ScriptTask 内部每步子任务超时，forceStop + 按 fail 策略） |
| 脚本总时长 | `ScriptTask.isContinuous()=true`（不被 Manager 60s 截断）+ 内部**总超时 10 分钟** |
| 范围上限 | `find.max_range` 默认 32；`scanBlockLimit` 默认 20000 方块 |
| 收割上限 | `harvest` BFS 目标上限 2000 |
| 副作用 | 查询类指令零副作用 |
| 递归 | 禁止 script 嵌套 script |
| 打断 | 战斗 > 脚本（沿用 `MaidTaskManager`）；坐下/死亡/`cancel` 立即终止 |
| 安全层 | L0 岩浆/火兜底不变 |
| 白名单 | step 内指令必须来自契约；`produce` 非法值 / harvest count 非数字 → 构造失败按 fail 策略 |

---

## 十、实现分期（已完成）

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 协议定稿 | ✅ |
| **P1** | 模组侧：查询原子 + StructureRegistry（tree/**ore**）+ 产物谓词编译 + `find`/`harvest` + ScriptTask 引擎 + `MaidAIBridge` script 分支 + 回执结构化 + 超时护栏 | ✅ 编译通过 |
| **P2** | 桌宠侧：`mod_contract` + DSL 解析（含容错）+ prompt 组装教学 + 回执注入 | ✅ 测试全过 |
| **P3** | ~~NLU 计划器模板~~（废弃）→ **AI 自由组装教学** + ore 结构 + AI 组装真机验证 | ✅ |
| P4(预留) | 更多结构类型（crop/container）、循环策略优化、逐步回执上报（report_each 的 WS 通道）、并行 harvest | ⏳ |

## 十一、测试

- 模组 `MaidAutoTest`：脚本顺序/分支/循环/变量/terminate/嵌套拒绝/战斗打断/步数上限/范围递增上限/**产物过滤**（金合欢与橡树混合）/树结构各树种
- 桌宠 `test_nlu`：script 契约、validate_script 递归校验/拒绝、clamp 表达式透传
- 桌宠 `test_maid_loop`：`【script({...json...})】` 解析、容错（指令：前缀 / 未闭合 script）
- 端到端真机：砍橡树全流程（有斧 / 无斧徒手 / 只有金合欢时过滤并继续找 / 无树终止）、挖矿 ore
