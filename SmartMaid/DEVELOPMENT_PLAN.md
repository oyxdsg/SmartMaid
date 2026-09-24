# Smart Maid 后续开发方案

> 基线：`4ca22a6`（2026-09-10）
> 已完成：M1~M3.6 任务系统 / M-P0 感知模块 / M4 指令桥接 / MaidAutoTest / **M5 桌宠联动** / **M-P1 感知增量** / **一键 e2e 脚本** / **M6 桌宠 NLU 支撑（craft_check 干跑查询 / item_index 物品索引 / owner.pos）**
> 本文只写"接下来做什么"，已完成的实现细节见 `HANDOVER.md` / `DESIGN_AI_INTERFACE.md`。

---

## 一、结论先行

| 阶段 | 目标 | 价值 | 成本 | 前置 | 状态 |
|---|---|---|---|---|---|
| **P0** | M5-a 感知下行接入桌宠现有文件通道 | 立刻可玩 | 低 | 无 | ✅ 已实现 + 真机验证 |
| **P1** | M5-b 指令上行通道（**WebSocket**） | 端到端闭环 | 中 | P0 | ✅ 已实现 + 真机验证 |
| **P2** | M-P1 感知增量 diff | 省 AI token | 低 | 无 | ✅ 已实现 + 真机降 83.1% |
| P3 | 跳跃系统实测标定 | 移动质量 | 中 | 需进游戏 | ⏳ 待做 |
| P4 | 发布前收尾（权限 / 日志开关） | 可发布 | 低 | 无 | ⏳ 待做 |
| **P6** | `craft_check` 干跑查询（M6-a） | 桌宠 NLU 自动合成前判断缺料 | 低 | 无 | ✅ 已实现 |
| **P7** | `item_index` 物品索引下发（M6-b） | 桌宠本地物品名匹配（含 mod 物品） | 低 | 无 | ✅ 已实现 |
| **P8** | `owner.pos` 感知补全（M6-c） | 相对指令「在我脚下…」可换算 | 极低 | 无 | ✅ 已实现 |
| **P9** | `attack` 支持指定目标（M6-d） | 「杀这头猪」→ 打指定实体类型，可获取食物 | 低 | 无 | ✅ 已实现（2026-09-12） |
| **P10** | 寻路降级：挖方块 + 搭路 | 原版寻路失败/绕远 → 直线物理路径，遇障碍挖、遇沟/水/需向上搭方块 | 中 | 无 | ✅ 已实现（2026-09-15 真机验收） |
| **P11** | **女仆战斗系统（C0–C5）** | 近战/走位/远程/盾牌/进食；规则驱动、AI 兜底 | 高 | 无 | ✅ 已实现（2026-09-17，真机回归中） |
| P5 | 基岩版 Q 版模型渲染 | 观感 | 高 | 无 | ⏳ 待做 |

**既定决策**：① 下行写 `deskpet/maid/` 子目录 + 桌宠侧扫子目录；② 上行走 WebSocket `ws://127.0.0.1:21420`。
**当前进度**：**M5 双向链路 + P2 感知增量 + 一键 e2e 全部完成并通过真机端到端验证**（2026-09-10 第三次跑通）：
- 14 条 AutoTest 全部通过（含 `m10 chestput` / `m12 badcmd` 故意失败路径）
- 4 个遥测窗口写出（LOW/CRITICAL×3/NORMAL，含真实 `damage` 8 事件与 `minecraft:skeleton`/`zombie` 受伤场景）
- WS 握手 + 心跳 10ms + speak/animation/guard 下发 + 增量 diff 全闭环
- P2 真实全量 **5761B → 增量 971B，降 83.1%**（独立测试是 87.3%，真机因 `blocks` 矿石列表等大幅内容下场）
**已修复**：`damage` 事件 `target:"?"` 攻击者解析遗漏（B 方案落地，2026-09-10）：`SmartMaidEntity.hurtServer` 缓存攻击者 + `PerceptionModule.attackerLabel()` 类型映射表，`target` 输出短名/玩家名，未知写 `unknown` 且遥测降级 NORMAL。详见 DEVELOPMENT_ISSUES §2026-09-10 问题 5 修复记录。
**2026-09-11 追加（M6：桌宠 NLU 支撑）**：桌宠侧要做「本地意图识别 + 自动执行」，对模组提出三个需求，均已实现：
- **P6 `craft_check`**：干跑配方预检（不消耗材料），逐项回报所需材料/现有/够不够；tag 类配方给候选列表、多产出配方按 `out_per_craft` 折算。走与 `craft` 相同的解析路径，避免「预检说够、真做失败」
- **P7 `item_index`**：握手后一次性下发「当前语言物品名 → `minecraft:id`」整表（客户端 `I18n`，覆盖 mod 物品；专用服务端返回 null 让对方降级）
- **P8 `owner.pos`**：`OwnerSense` 补主人方块坐标（原来只有 `dist`），使「在我脚下放方块 / 到我这儿来」可换算成绝对坐标

> 对接中还梳理出**集成方必须知道的接口语义边界**（`transfer` 的 from/to 必填、`chestopen` 是异步任务、
> `equip`/`drop`/`place`/`use` 只作用于主手…），已写入 `HANDOVER.md` §5.1；
> 详细排查过程与设计取舍见 `DEVELOPMENT_ISSUES.md` §2026-09-11。

**下一步**：① 进 P3 跳跃实测 / ② 进 P4 发布收尾；用户拍板优先级。

---

## 十一、P10：寻路降级（挖方块 + 搭路） ✅ 已实现（2026-09-15 真机验收）

**目标**：女仆寻路优先走原版；原版失败 / 路径到不了目标（被墙水阻断）/ 明显绕远时，切**物理直线路径**——遇障碍挖掉、遇沟/水面/需向上搭方块；直线过程中代价回落自动切回原版。

**设计（用户拍板，动态可逆）**：
- 每次寻路先跑原版 A*：`没路 / 路径终点到不了目标(隔墙) / 节点数 > 直线距离×3` → 切直线
- 直线模式逐 tick：挖 2 格墙（坑里挖上方爬升 / 同高挖墙脚）、挖头顶、1 格台阶直接跳、深沟/水面搭方块、平地直线走
- 每 40 tick 重评估原版寻路，代价回落到阈值内 → 切回正常寻路

**实现**：`MaidStraightNav`（降级导航器）+ `MaidBlockBreaker`（挖掘执行器）+ `MaidBlockPlacer`（搭方块执行器，bridgeMode 只耗普通建材）+ `MaidGroundPathNavigation`（createPath 代价判定 + tick 接管）+ `MaidMoveControl`（直线模式跳过跨沟执行器）。

**参数**：`RATIO=3.0`（原版节点 > 直线距离×3 才切）、`MIN_STRAIGHT_NODES=8`、`STRAIGHT_EVAL_INTERVAL=40`、`IDLE_LIMIT=200`（10s 无进展放弃）、`GIVE_UP_COOLDOWN=60`（放弃冷却）。

**2026-09-21 增强（真机验证通过 ✅）**：
- **挖掘能力对齐玩家（重构 `MaidBlockBreaker`）**：玩家挖掘本质 = 准星命中哪个方块挖哪个（`MaidActions.lineHit` 返回视线命中的第一个方块）。决策顺序：① 挖掘中守卫（`digTicks>0` 持续挖完不打断）→ ② 目标在上方够不着 → **pillar 搭高**（水平远先导航、够近原地搭）→ ③ 水平太远 → 导航接近 → ④ 水平够近 → **挖视线第一个非目标方块**（含脚下土逐层往下找埋地矿）→ ⑤ 直接挖。
  - **pillar 放块时机 = 脚底离开原格**（y-起跳前 >1.0，原格空出无实体碰撞），升空窗口内每 tick 尝试；**放块失败自动重试**（连续 8 次放弃）；搭高期间逐 tick 锁定水平（防掉）；落地判定用高度。**效果：被墙挡的矿先挖墙再挖矿（不转圈）；高处矿成功搭方块上去挖（真机 ✅）**
  - 离线物理：`tools/maid_jump_sim.js`「pillar 垫高」段（vy0=0.42，最高点 y=1.252 @t=6，落地 t=12）
- **`MaidStraightNav` 垂直同柱 pillar**：寻路降级到达高处位置用；只耗普通建材、`setWait()` 停水平移动、60t 超时保护。

**验收**：真机通过——女仆能破坏墙体、逐格爬出坑、正常地形优先走原版。踩坑记录见 `DEVELOPMENT_ISSUES.md` 2026-09-15 节。

---

## 十二、女仆战斗系统 ✅ 已实现（2026-09-17，C0–C5）

**目标**：规则驱动的自主战斗——玩家指挥女仆时，战斗全由本地确定性逻辑完成（AI 只下「打谁」）。

**优先级**：L0 安全层 > 战斗 > 其他 AI 任务 > 常规 Goal。

**实现（`entity/ai/combat/`，设计文档 `DEVELOPMENT_COMBAT.md`）**：
- `MaidCombatGoal`：状态机 **盾>吃>远程>近战>逼近**；近战判定 3.0 / 可攻击 **3.2**；按武器攻速出手；全流程 `Combat` debug 日志
- `MaidCombatMovement`：原版 `MoveControl.strafe`（STRAFE 不转向）→ **面向敌人后退** / **侧移绕行**（两侧交替）/ **背对跳 1 格**
- `MaidTargetFilter`：Monster + 排除表（末影人/僵尸猪灵）+ **友军仅玩家**
- `MaidWeaponSelector`：`forEachModifier` 解析「单次伤害 × 攻击速度」；弓只比 `POWER` 附魔
- `MaidRangedSkill`：自写蓄力/发射（`BowItem.releaseUsing` 是 Player 专属）+ kite；`getProjectile` 覆写找箭
- `MaidThreatDetector` + `MaidShieldSkill`：箭矢/苦力怕威胁 → 副手举盾（`BLOCKS_ATTACKS` 减伤）
- `MaidFoodData` + `EatTask`：仿玩家饱食度/回血；`/maidtasks eat [item]` / `/maidai eat`
- `MaidArrow`：不伤玩家（实体类型仍是原版 `arrow`，零注册）
- 战斗期 **禁用 P10 直线降级**（不挖不搭）但**保留跳跃执行器**；`MaidTaskManager` 战斗期取消 AI 任务

**验收**：构建通过；桌宠侧 `eat` 指令/意图已同步并重训 NLU（27 意图）。真机全流程回归进行中。

**鱼竿**：spike 结论——`FishingHook` 非玩家 owner 首 tick 丢弃、`FishingRodItem.use` Player 专属 → **原版不可用，暂不做**（用户拍板）。

---

## 二、M5 联动：调研结论（重要，改变了原方案）

原方案（`HANDOVER.md` 第八节）是"新建 WebSocket 双工通道"。调研桌宠项目后发现**桌宠侧已经有一套跑通的 MC 联动下行通道**，可以大幅省力。

### 现有通道（桌宠 ↔ deskpet-mod）

数据目录：`<mc>/.minecraft/deskpet/`（由配好的游戏日志路径推导，见 `desktop-pet/game/mod_data.py: deskpet_dir_from_log`）

| 文件 | 内容 | schema |
|---|---|---|
| `YYYYMMDD-HHMM.jsonl` | 窗口事件，每分钟分片，一行一个 JSON | `{importance: LOW\|NORMAL\|CRITICAL, highlights: [...]}` |
| `buildings/latest.json` | 建筑感知包 | `{classification, score:{comment,suggestions}, ...}` |
| `state.json` | 环境状态 | `{server, game, mode, world_type}`（全可选） |

- **写方**：`deskpet-mod` → `WindowSink.java`（窗口 20s 聚合、文件按分钟轮转、自动清理 2 分钟前的旧文件）
- **读方**：`desktop-pet/pet/handlers/game_handler.py` → `deskpet.window_files()` 扫目录 → `read_window()` 按字节偏移增量读 → `highlights_to_text()` 转中文行 → 喂给 AI 聊天

`highlights[].type` 已定义的取值：`chat` / `broadcast` / `death` / `advancement` / `dimension` / `kill` / `item_gain` / `damage` / `summary` / `break` / `place` / `attack` / `use_item` / `move` / `join` / `leave`。

### 结论

1. **下行（MC → 桌宠）：可以零成本复用。** SmartMaid 只要按同样 schema 写文件，桌宠**现有代码不用改**就能"看到"女仆，说出"女仆受伤了 / 发现僵尸 / 在挖矿"。
2. **上行（桌宠 → MC）：桌宠目前完全没有发送通道**（全项目无 socket / websocket / 端口代码），必须新增。这是 M5 真正的成本所在。

---

## 三、P0：M5-a 感知下行接入 ✅ 已实现（2026-09-10）

**目标**：桌宠能感知女仆状态与事件。

### 实现内容

| 侧 | 改动 | 文件 |
|---|---|---|
| 模组 | 新增遥测写入器（事件 → 窗口 jsonl，20s 聚合、按分钟轮转、清理 2 分钟前文件） | `entity/ai/bridge/MaidTelemetryWriter.java` |
| 模组 | 感知模块新增事件回调 `setEventSink`，事件产生即入缓冲 | `entity/ai/perception/PerceptionModule.java` |
| 模组 | `aiStep` 每 tick 驱动遥测；`dropAllDeathLoot` 释放缓冲 | `entity/SmartMaidEntity.java` |
| 桌宠 | `window_files()` 增加 `maid/` 子目录扫描（返回相对路径，调用方无需改） | `desktop-pet/game/mod_data.py` |

- 写入位置：`<游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl`
- 配置：`config/smartmaid/bridge.json`（首次运行自动生成）
  ```json
  {"enabled": true, "deskpetDir": "", "windowTicks": 400}
  ```
  `deskpetDir` 留空即用默认位置；`enabled` 默认 **true**（开发期便于直接联调，发布前按需改 false）。
- 窗口 schema：
  ```json
  {"source":"smartmaid","seq":3,"tick":123456,"importance":"CRITICAL",
   "highlights":[{"type":"damage","player":"女仆","target":"minecraft:zombie","detail":"剩余生命 18.0"}],
   "maid":{"name":"女仆","owner":"Steve","pos":[x,y,z],"health":18.0,"task":"mine","mainhand":"..."}}
  ```
- 事件映射（复用桌宠已认识的 `type`，因此桌宠渲染逻辑零改动）：
  | 感知事件 | highlight | 桌宠渲染结果 |
  |---|---|---|
  | `hurt` | `damage` | `[受伤] 女仆 被 minecraft:zombie 伤害` |
  | `enemy_spotted` | `summary` | `[概况] 女仆发现 minecraft:zombie（8.0 格外）` |
  | `task_started` / `task_done` | `summary` | `[概况] 女仆开始执行 mine` |
  | `environment_danger` | `summary` | `[概况] 女仆进入岩浆` |
- 重要性：受伤 / 发现敌人 / 环境危险 → `CRITICAL`；任务开始结束 → `NORMAL`；无事件 → `LOW`
- 额外字段 `maid`（状态快照）桌宠会忽略，仅作联调排查用

### 验收

- ✅ 模组 `compileJava` 通过
- ✅ 桌宠侧契约测试通过：`window_files` 能列出 `maid\*.jsonl`，`read_window` 正常按偏移读取，`highlights_to_text` 渲染出上述中文行
- ✅ **真机 e2e 通过**（2026-09-10）：游戏内女仆受到 zombie/skeleton 攻击，`deskpet/maid/20260910-1834.jsonl` 等 4 个窗口相继落盘、桌宠侧 `game_handler` 旁路日志能搜到 `[概况] 女仆发现 minecraft:skeleton` / `[受伤] 女仆 被 minecraft:zombie 伤害`

### 决策记录

- **子目录方案取 (ii)**：写 `deskpet/maid/` + 桌宠侧扫子目录。理由：顶层是 deskpet-mod 的写入区，同分钟文件会被两个模组同时追加，必须隔离；桌宠侧改动仅 `window_files()` 一处、约 5 行。
- 未写 `deskpet/state.json`：该文件由 deskpet-mod 维护，避免覆盖；女仆状态放在窗口的 `maid` 字段里。

---

## 四、P1：M5-b 指令上行通道（WebSocket） ✅ 已实现（2026-09-10）

**决策：按原草案直接上 WebSocket，不做文件命令队列。**

- 模组侧：`java.net.http.WebSocket`（JDK 25 内置，**无需新增依赖**），Client 连 `ws://127.0.0.1:21420`
- 桌宠侧：`websockets` 库起 WS Server（独立线程跑 asyncio，通过队列与 PySide6 主线程通信）
- 协议：心跳 5s、指数退避重连（1s→30s）、握手 token

### 实现内容

| 侧 | 改动 | 文件 |
|---|---|---|
| 模组 | **新增** WebSocket Client：连接管理 / 握手 / 心跳 / 退避重连 / 上下行分发 | `entity/ai/bridge/MaidWsClient.java` |
| 模组 | **新增** 集中配置（遥测与 WS 共用，首次自动生成） | `entity/ai/bridge/BridgeConfig.java` |
| 模组 | 注册服务器生命周期与 tick 驱动 | `SmartMaid.java` |
| 模组 | `aiStep` 注册通道并订阅感知事件 | `SmartMaidEntity.java` |
| 桌宠 | **新增** WS Server + 感知状态合并 + 指令下发 API | `game/maid_link.py` |
| 桌宠 | **新增** Qt Handler（启停、事件播报、驱动女仆） | `pet/handlers/maid_handler.py` |
| 桌宠 | 接线：实例化、1s 心跳、退出清理、对外接口 | `pet/modes.py`、`pet/window.py` |
| 桌宠 | 配置常量 + 依赖声明 | `config.py`、`requirements.txt` |

**线程模型（关键约束，已落实）**：WebSocket 回调运行在 `HttpClient` 线程池，
收到消息一律经 `server.execute(...)` 切回服务端线程再调 `MaidAIBridge`；
发送统一由服务端 tick 从 `OUTBOX` 抽取，保证单线程串行写。

**配置**（`config/smartmaid/bridge.json` 的 `ws` 段）：
```json
{"ws": {"enabled": true, "url": "ws://127.0.0.1:21420",
        "token": "", "heartbeatSec": 5, "perceptionIntervalMs": 750}}
```
桌宠侧对应 `config.py` 的 `MAID_LINK_ENABLED / MAID_LINK_PORT / MAID_LINK_TOKEN`。

**对外接口**（桌宠侧，供对话/UI 层调用）：
`app.maid_command(cmd, params)` / `app.maid_speak(text)` / `app.maid_animate(name)` / `app.maid_state_line()` / `app.maid_link_connected`

### 验收

- ✅ 模组 `build` 通过
- ✅ 桌宠侧端到端联调通过（模拟模组客户端跑真实协议）：
  错误 token 被拒绝、握手成功、感知增量合并正确（含 `removed` 删除生效）、
  事件与回执入队、`send_command/speak/animation` 模组侧正确收到、退出无异常
- ✅ **真机端到端全闭环通过**（2026-09-10 第三次跑通）：WS 握手、心跳稳定 ~10ms、`speak` 气泡渲染、`animation` 动作播放、`guard` 任务下发成功、感知增量 diff 一并验收

### 风险与对策

| 风险 | 对策（已实施） |
|---|---|
| WS 线程触碰世界状态 | 一律 `server.execute(...)` 切回服务端线程 |
| 多线程同时 `sendText` 分帧 | 出站统一走 `OUTBOX`，仅服务端 tick 发送 |
| 桌宠 GUI 单线程 | asyncio 跑独立线程，跨线程只走 `queue.Queue` |
| 端口被占用 | 端口可配置；启动失败打日志不崩 |
| 指令滥用 | token 校验（留空则不校验）；只绑 `127.0.0.1` |

---

## 五、P2：感知增量 diff（M-P1） ✅ 已实现（2026-09-10）

**实现**：新增 `entity/ai/perception/PerceptionDiff.java`
- `next(perception)`：首次自动全量，之后增量；输出 `{seq, tick, full, changed, removed}` 或 `{seq, tick, full, snapshot}`
- `changed` 保持原嵌套结构（接收方递归合并）；`removed` 为点分路径数组
- `tick` 与 `events` 不参与 diff：前者每次必变属噪声（放信封顶层），后者由独立 `event` 消息推送
- `reset()`：重连 / 接收方状态丢失时强制下一帧全量（`MaidWsClient` 断线时自动调用）
- 附 `merge(base, message)` 参考实现，作为接收方合并语义的权威定义（桌宠侧 `maid_link.merge_snapshot` 与之对齐）

**验收结果**：

| 验证 | 全量 | 增量 | 下降 |
|---|---|---|---|
| 独立 round-trip（20 帧模拟） | 1224 B | 155 B | **87.3%** ✅ |
| **真机 e2e**（含 `blocks` 矿石列表、`events` 队列等真实负载） | **5761 B** | **971 B** | **83.1%** ✅ |

**真机** 全量 5761B → 增量 971B，降 83.1%，达成 >60% 目标；后续大部分 tick 增量稳定在 800~1100B，桌宠侧 `merge_snapshot` 无错。

> 注：真实快照（含 `blocks` 矿石列表等）远大于测试用结构，实际收益会更高。

---

## 六、P3：跳跃系统实测标定

`HANDOVER.md` 已知问题 1~3：落点精度、对角跨沟、下跳均未完整实测。

1. 用 `config/smartmaid/autotest.json` 编场景：对角跨沟 / 跨 3 沟上 1 格 / 下跳 1~3 格
2. 读日志 `[SmartMaid-Debug] Jump start/fired/landed` 取实际落点（**注意 `latest.log` 是 GBK 编码**）
3. 落点偏差 → 改参数后跑 `node tools/gen_jump_table.js` 重新生成 `MaidJumpTable.java`
4. 顺带定 `MOVEMENT_SPEED`：现为 `0.16`（行走偏快 ≈7 m/s），若要"和玩家一致"回 `0.1`，但跨 3 沟能力会下降

---

## 七、P4：发布前收尾

- `/summonmaid` `/maidtasks` `/maidai` 加权限检查（建议 `source.hasPermission(2)`）
- `MaidDebug.ENABLED` 改 `false`
- 加一个图标与 `README` 的安装说明

> ✅ 已完成（2026-09-10，提前）：**周期性/每帧高频日志全部收进 `MaidDebug.verbose()` 门控**（默认关）——感知快照、装备状态、Monitor 状态+地形、渲染 `extractRenderState`/`setupAnim`、挖矿/烧炼进度共 7 处；仅事件性日志默认输出。解决"数百小时游戏 `latest.log` 累积数 GB"问题。发布时仅剩 `ENABLED=false` 全局关闭。

---

## 八、P5：基岩版 Q 版模型渲染

- 参考 `TouhouLittleMaid` 的 `simplebedrockmodel` 包
- 需要 `tlm_custom_pack` 的模型 json + 皮肤 png（约 41MB / 1260 文件，**不入库**，从 `downloads/TouhouLittleMaid-1.20.zip` 解压）
- 大工程，建议单独立项

---

## 九、风险与注意事项

| 风险 | 说明 | 对策 |
|---|---|---|
| 桌宠只扫顶层目录 | `window_files()` 用 `os.listdir`，不递归 | 用 `deskpet/maid/` 子目录 + 桌宠侧小改 |
| 两个 mod 混写同一文件 | deskpet-mod 与 SmartMaid 都写 `deskpet/` | 子目录隔离 |
| 日志编码 | Windows 中文系统 `latest.log` 是 **GBK** | 分析脚本必须 gbk 解码 |
| 桌宠线程模型 | PySide6 单线程 GUI | 不要在 UI 线程做阻塞 IO |
| 指令注入 | 文件通道任何人都能写 | 指令带 token 校验；或限定端口仅本机 |
| 端口占用 | 若走 WS，21420 可能被占 | 文件通道天然规避 |
| 双开游戏 | 两个实例写同一目录 | 文件名加实例后缀 / 目录按存档隔离 |

---

## 十、建议排期

| 序 | 内容 | 依赖 | 说明 |
|---|---|---|---|
| 0 | ~~修 `damage` 事件 `target:"?"` bug~~（B 方案） | — | ✅ 已完成（2026-09-10）+ 日志收敛提前落地 |
| 1 | P0 感知下行接入 | — | 半天，✅ 已完成 |
| 2 | P1 指令上行 WS | P0 | 1 天，✅ 已完成 |
| 3 | P2 感知增量 diff | — | 半天，✅ 已完成 |
| 4 | P3 跳跃标定 | 需进游戏 | 1 天 |
| 5 | P4 发布收尾 | — | 半天 |
| 6 | P5 基岩版模型 | — | 单独立项 |
| 7 | P6/P7/P8 桌宠 NLU 支撑（craft_check / item_index / owner.pos） | — | ✅ 已完成（2026-09-11），构建通过 |
| 8 | **真机联调 M6**：进游戏确认 item_index 下发、craft_check 缺料明细 | 需进游戏 | 建议优先（尚未真机验证） |

**当前实际状态**：行 0~3 已完成 + 真机验证；行 7（M6）已完成但**仅编译验证，未真机联调**；行 4~6、8 未做。运行 `python tools/run_e2e_test.py --quick-play "新的世界 (11)"` 即可重放端到端验证。
