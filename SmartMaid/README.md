# Smart Maid · 智能女仆模组

规则引擎驱动的 AI 女仆模组（Minecraft 26.2 / Fabric），灵感来自 Touhou Little Maid（车万女仆）。

女仆由指令召唤、唯一归属玩家、退出游戏消失（数据本地持久化）。行为由本地规则引擎主导（安全优先、毫秒级响应），AI 作兜底增强，通过 WebSocket 与桌面宠物程序联动，把桌宠的 AI 聊天 / 语音 / 气泡能力接入女仆。

**女仆战斗系统已落地（2026-09-17，C0–C5）**——规则驱动：近战（判定 3.0 / 攻击 3.2）、走位（面向敌人后退 / 侧移绕行 / 背对跳 1 格）、远程弓箭、盾牌格挡、半血进食回血；与桌宠 `eat` 指令打通。见 `DEVELOPMENT_COMBAT.md`。

**M5 双向链路 + 感知增量 diff + 一键 e2e 已通过真机端到端验证（2026-09-10）**——见 `HANDOVER.md` §二、§已知 bug 与 `DEVELOPMENT_ISSUES.md` 2026-09-10 节。

**玩家绑定设置 + 木质女仆菜单 + 聊天栏对话 + 桌宠隐退已落地（2026-09-19）**——见 `HANDOVER.md` §2026-09-19 节。

**战斗走位死区 + 盾牌免前摇 + 背包模型预览已落地（2026-09-19）**——近战死区 2.7~3.2；盾牌覆写 `getItemBlockingWith` 去掉 0.25s 架盾前摇（女仆=PVE 高手，检测到威胁即当场举盾 100% 挡箭）；女仆背包界面左上角渲染女仆自身缩小模型（跟随鼠标旋转）。

**挖矿坐标可选 + 挖掘面向 + 对话事件静默 + 生物名中文已落地（2026-09-19）**——`mine` 不给坐标自动探测周围矿物（水平 12 / 垂直 8 格）、挖掘面向目标挥镐；玩家对话女仆期间游戏事件 AI 静默（消除"一条消息多条回复"）；感知/状态里生物名翻译中文。

## 相关仓库（先看这里）

SmartMaid 是**桌面宠物 DeskPet 的 Minecraft 侧搭档**——女仆的 AI 对话 / 语音 / 气泡由桌宠提供，本模组通过 WebSocket 与它联动。**想让女仆会聊天、会说话，需要一并下载桌宠**：

| 去这里下载 | 是什么 | 地址 |
|---|---|---|
| **Multimodal-AI-Companion** | 桌面宠物 DeskPet（AI / 语音 / 气泡，**联动对象**） | <https://github.com/oyxdsg/Multimodal-AI-Companion> |
| **Multimodal-AI-Companion-Assets** | 桌宠动画素材包（Release 附件，解压到 `desktop-pet/assets/`） | <https://github.com/oyxdsg/Multimodal-AI-Companion-Assets> |

> 不装桌宠也能玩：女仆的规则引擎（跟随 / 战斗 / 跳跃 / 寻路 / 任务 / 设置菜单）**完全独立可用**，
> 只有 `/maidchat` 聊天栏对话与语音朗读需要桌宠在线。

## 功能特性

- **唯一女仆**：`/summonmaid` 召唤，每玩家仅限一只，召唤即归属玩家，无需驯服
- **生命周期**：实体 `noSave()`，退出游戏即消失；装备/物品栏每 5 秒自动持久化，重新召唤自动恢复
- **原版移动体系 + 车万女仆式跳跃**：移动完全沿用原版 `MoveControl`/`Navigation`/`travel` 物理，仅优化"触发跳跃"判断
- **跳跃动作执行器**：跨 1~3 格沟、上 1 格高台、跨沟上 1 格、下跳 1~3 格——起跳点/朝向按障碍场景参数化
- **离线精确解表**：每种障碍的起跳解 `{速度档, 起跳后撤量}` 由脚本离线计算，模组运行时只查表、不做物理计算
- **能力一致**：跳跃取固定 takeoff（走 0.30 / 跑 0.55 blocks/tick），与玩家跳跃能力一致，无需助跑
- **跳跃寻路**：自定义 `MaidWalkNodeEvaluator`，把"可跳跃跨越"的障碍/沟视为可走路径（上坡 1.5 / 下坡 3 格 / 跨沟 2~4 格）
- **L0 本地安全层**：每 tick 硬编码检测脚下岩浆/火/岩浆块 → 起跳脱离，断网/无 AI 也生效
- **跟随主人**：`MaidFollowGoal`（3~10 格距离触发/保持），右键坐/站
- **女仆 = 玩家**：背包布局与玩家完全一致（41 格：热键 0-8 / 背包 9-35 / 盔甲 36-39 / 副手 40）；主手手持热键栏第 0 格、副手可放任意物品；死亡全掉落；**女仆背包界面左上角渲染女仆自身缩小模型**（跟随鼠标旋转、显示皮肤/装备/手持，与原版玩家背包一致，复用 `InventoryScreen.extractEntityInInventoryFollowsMouse`）
- **动作动画系统**：`/maidanim` 播放 Emotecraft 动作（挥手/鼓掌/后空翻/电臀舞等 11 种），基于 Player Animation Library 的 `HumanoidAnimationController`（不依赖 Avatar，女仆直接用）
- **AI 任务系统**：`/maidtasks` 让 AI/玩家指挥女仆执行攻击（可指定目标生物，如 `attack 10 minecraft:pig` 获取食物）、护卫、喂食、进食（`eat`）、挖矿（有挖掘过程）、耕作、建造、收集、制作（原地合成）、烧炼等任务；任务运行期间接管行为决策（跟随/自动攻击/散步让路）
  - **原子层指令协议（2026-09-21，`script`）**：AI 可用**原子指令自由组装任意多步任务**——查询（`find`/`inventory`/`block_at`/`distance`…）+ 动作（`harvest`/`move`/`equip`…）通过 `as`（变量绑定）/`if`/`loop`/`assign`/`terminate` 组合成一条脚本一次下发；支持**目标产物过滤**（要橡树原木 → 金合欢被过滤）、**范围递增**（12→32）、**循环续找**（砍空未达标自动找下一棵）、**结构化回执**（reason/collected/target）注入 AI 继续决策；结构类型可插拔（`tree`/`ore` 已注册）。详见 `DESIGN_ATOMIC_PROTOCOL.md`
  - **挖矿坐标可选（2026-09-19）**：`mine` 不给 `pos` 时以女仆脚下为中心**自动探测周围矿物**（水平 12 格 / 垂直 8 格，`/maidtasks mine` 无参亦可）；挖掘时**面向目标方块**挥镐（`lookControl.setLookAt`）
- **女仆战斗系统（2026-09-17，C0–C5）**：规则驱动的自主战斗，优先级 **盾 > 吃 > 远程 > 近战 > 逼近**，且**战斗高于 AI 任务**
  - **近战**：判定边界 3.0 / 实际可攻击 3.2 格；按物品属性解析「单次伤害 × 攻击速度」选最优武器，按武器攻速节奏出手；**走位死区 2.7~3.2**（>3.2 前进拉近，否则够不着；<2.7 后退）
  - **走位**：原版 `MoveControl.strafe`（移动不转身）→ **面向敌人后退**；**背对跳**上 1 格障碍；**侧移绕行**（墙角两侧交替尝试，不可省略）
  - **远程**：弓箭（只比 `POWER` 附魔选弓）→ 自写蓄力/发射（原版 `BowItem.releaseUsing` 是 Player 专属）+ **kite**（边退边射）
  - **盾牌格挡**：检测到**箭矢将命中 / 苦力怕将爆** → 副手举盾（`BLOCKS_ATTACKS` 挡箭 100%）；**覆写 `getItemBlockingWith()` 去掉 0.25s 架盾前摇**——女仆是 PVE 高手反应极快，检测到威胁即当场举盾挡下（原版要求使用中 ≥ `blockDelayTicks` 5t 才认作举盾，贴脸箭 1~2t 内中箭时盾永远来不及生效）
  - **饱食度 + 回血**：仿玩家内部 `MaidFoodData`；`eat` 进食；战斗中血量 <50% 且退不进墙角时原地进食
  - **免伤与索敌**：**友军只指玩家**（近战 + 箭矢硬规则，`MaidArrow` 不伤玩家）；敌对排除表（末影人/僵尸猪灵不主动打）；索敌以女仆为中心、要求女仆可见；主人被攻击无条件反击
- **正交物品转移 `transfer`**：槽位系统（背包/手持/盔甲/容器），一个指令覆盖换手/收纳/穿戴/交换/放置/丢出，目标满自动交换、非装备塞盔甲槽自动掉落
- **两步存箱子**：`chestopen`（走到箱子前 + 真实开箱动画）→ `chestput`（智能堆叠：优先同类可堆叠 64/16/1，其次空槽）/ `chesttake`（取出）
- **Java 版渲染**：复用 26.2 玩家模型（`HumanoidModel`）+ 女仆皮肤；**坐姿**走原版骑乘坐姿 + 渲染整体下沉 0.75 格（模型髋高），坐下即吸附到脚下支撑面（**地面 / 半砖 / 台阶顶**），屁股不浮空；坐下期间**完全不动**（不跟随/不散步/不跳跃/不被击退推走/不传送）
- **感知模块（AI"玩家视角"）**：`/maidperception` 查看感知快照——自身状态/背包能力摘要/主人状态（含**方块坐标 `owner.pos`**，供相对指令换算）/周边实体/方块/环境/事件队列，服务端分层采样（0.25s~2s），为 AI 提供结构化决策输入（`MaidPerception`）
- **AI 指令桥接**：`/maidai <json>` 统一 JSON 指令协议（24 指令与 `/maidtasks` 一致），坐标支持相对 `~`，回执含 `id/ok/state/step/result`，`persist:true` 持久化任务配置（`.cfg`）
- **干跑查询 `craft_check`（M6-a）**：不消耗材料的配方预检——逐项回报「需要什么材料 / 女仆现有多少 / 够不够」（`options/need/have/ok`，支持 tag 类配方如「任意木板×3」）。桌宠侧 NLU 靠它在自动合成前判断并向用户解释缺料
- **物品索引下发 `item_index`（M6-b）**：握手后把「当前语言的物品显示名 → `minecraft:id`」整表推给桌宠（客户端 `I18n` 取名，覆盖 mod 物品），桌宠即可本地做中文/拼音模糊匹配（语音「稿子」→「镐子」）；专用服务端无语言资源时自动跳过
- **桌宠联动下行 (M5-a)**：女仆事件按 20s 窗口聚合落到 `<游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl`（事件 type 复用桌宠已认识的 `damage` / `summary`，桌宠侧零改动渲染中文）；状态快照随窗口附带于 `maid` 字段
- **桌宠联动上行 (M5-b)**：JDK 25 内置 WebSocket Client 连桌宠 `ws://127.0.0.1:21420`，5s 心跳 + 指数退避（1s→30s）+ 可选 token 握手；上行 `hello`/`perception`/`event`/`command_result`/`pong`，下行 `command`/`speak`/`animation`/`ping`；回调线程铁律——一律 `server.execute()` 切回服务端线程再碰世界
- **感知增量 diff (M-P1)**：差异只发 `{changed, removed}`，接收方递归合并；首帧全量、断线 reset；真机 5761B → 971B，降 **83.1%**
- **寻路降级（挖方块 + 搭路）**：原版寻路失败 / 路径没真正到目标（被墙水阻断） / 明显绕远（路径节点 > 直线距离×3）时，自动切换到**物理直线路径**——直线朝目标走，途中遇 2 格以上墙/头顶挡就**挖掉**、遇 1 格台阶直接跳、遇深沟/水面/需向上就**搭方块**（只耗普通建材，不浪费贵重物品）；直线过程中每 40 tick 重评估原版寻路，代价回落到阈值内自动**切回正常寻路**（动态可逆）
- **挖掘能力对齐玩家（2026-09-21，真机验证通过）**：挖掘执行器重构为"玩家准星命中哪个方块挖哪个"——`MaidActions.lineHit` 返回视线命中的第一个方块：
  - **被墙挡的矿** → 先挖掉挡路块再挖矿（不再转圈/假完成）
  - **埋地下的矿** → 沿视线挖土**逐层往下**直到矿
  - **够不着的高处矿** → **pillar 搭方块上去挖**：起跳后脚底离开原格（y>+1.0，原格空出、无实体碰撞）时放方块到脚下，落地站上高度+1 循环；**放块失败自动重试**（连续 8 次才放弃）、搭高期间逐 tick 锁定水平（防掉下 1 格宽柱子）、落地判定用高度；只耗普通建材
  - 日志：`Breaker 挖挡路块 / pillar 起跳·垫·上一层`
- **pillar 垫高（`MaidStraightNav` 垂直同柱，寻路降级到达位置用）**：目标在正上方且跳不上时，起跳后在脚下放方块（离线解表 `tools/maid_jump_sim.js`「pillar 垫高」段），落地站上新方块 → 高度 +1 循环直到可跳；只耗普通建材、`setWait()` 停水平移动、60t 超时保护
- **玩家绑定设置 + 游戏内女仆菜单（2026-09-19）**：Shift+右键 打开木质风设置菜单（全部原版控件 + 自绘暖棕木纹贴图）；设置与玩家 UUID 绑定持久化（`config/smartmaid/settings/<玩家UUID>.json`），改完即时生效、菜单 ContainerData 实时回显：
  - **友军伤害**（开启后女仆可伤害玩家，友军=玩家自己）
  - **自动跟随** 开关 + 触发/停止距离
  - **护主模式**：主动（遇怪就上）/ 被动（只反击打主人的怪）/ 关闭
  - **女仆属性**：最大生命、饱食消耗速度、回血速度
  - **死亡掉落**：全部掉落 / 保留装备（重召唤恢复）/ 不掉落
- **聊天栏对话模式 + 桌宠隐退（2026-09-19）**：聊天栏输入 `/maidchat` 进入对话模式，非 `/` 文本转给桌宠 AI，回复以**多行气泡**冒在女仆头顶 + 聊天栏回显 + 桌宠语音朗读；`/summonmaid` 时通知桌宠，桌宠按设置隐藏窗口（AI/STT/TTS 后端保留，女仆离线/断线自动恢复）；按住 Y 说话走桌宠语音识别
  - **对话期间事件静默（2026-09-19）**：玩家跟女仆对话后 120s 内，桌宠游戏事件 AI 线（挖矿/打怪/获得物品的互动播报）**整体静默**——不调 AI、不 speak 到女仆，避免"一条消息回复好几条"的两条线抢话
  - **生物名中文（2026-09-19）**：感知事件/状态行里的实体 id（stray/parched/pillager…）翻译为中文（流浪者/帕查德/掠夺者…），AI 上下文与气泡都看中文

## 环境要求

| 项 | 要求 |
|---|---|
| Minecraft | 26.2（`~26.2`） |
| 加载器 | Fabric Loader >= 0.18.4（开发用 0.19.3） |
| 前置 | Fabric API `0.158.0+26.2` |
| Java | >= 25（JDK 25 构建） |
| Gradle | 9.7.1（wrapper 内置） |

## 构建

```bash
# 需要 JDK 25（Minecraft 26.2 要求），先设置 JAVA_HOME 指向你的 JDK 25 安装目录
$env:JAVA_HOME="<你的 JDK 25 安装目录>"
gradlew.bat build
```

产物在 `build/libs/smartmaid-<version>.jar`，放入 `.minecraft/mods/` 即可。

> 国内网络：Gradle 发行版走腾讯镜像（`gradle/wrapper/gradle-wrapper.properties` 已配置）；公共依赖走阿里云/腾讯镜像（`build.gradle` 已配置）；Mojang 下载需代理。

## 使用方法

1. 进游戏（26.2 Fabric，需安装 fabric-api）
2. 执行 `/summonmaid` 召唤女仆（出现在你脚下，显示"女仆"名称）
3. 交互：空手右键 → 坐下/站起；给女仆穿戴装备
4. 女仆会跟随你，自动走/跑/跳跃跨越障碍、沟壑、上 1 格高台
5. 退出游戏女仆消失；下次进游戏 `/summonmaid` 自动恢复上次装备

### AI 任务指令 `/maidtasks`

```
/maidtasks attack [range] [target]  /  guard [range]  /  feed       # 战斗/护卫/喂食
/maidtasks eat [item]                                              # 女仆自己进食（item 缺省=营养最高）
/maidtasks mine [pos] [range] [count]  /  farm <pos> [range]        # 挖矿（pos 可省，自动探测周围矿物）/耕作
/maidtasks build <pos> <height>  /  collect [range]                 # 建造/收集
/maidtasks craft <item> [count]  /  smelt <item> [count]            # 制作/烧炼
/maidtasks transfer <from> <to> [count]                             # 正交物品转移
/maidtasks chestopen <pos>  /  chestput [count]  /  chesttake [slot] [count]  # 两步存/取箱子
/maidtasks move <pos>  /  look <pos>  /  break <pos>  /  place <pos>  /  use <pos>
/maidtasks equip <item>  /  store  /  drop [count]  /  pickup
/maidtasks sit  /  stop  /  cancel  /  status
```

> 槽位：`0-40` / `mainhand` / `offhand` / `head` / `chest` / `legs` / `feet`。详细说明见 [`DESIGN_AI_INTERFACE.md`](./DESIGN_AI_INTERFACE.md)。

### AI 指令协议 `/maidai`

```
/maidai {"id":"a1","cmd":"guard","params":{"range":10}}
/maidai {"id":"a2","cmd":"move","params":{"pos":[100,-60,-50]}}          # 坐标支持 ~ 相对
/maidai {"id":"a3","cmd":"mine","params":{"pos":[100,-60,-50],"range":4},"persist":true}
/maidai {"id":"a4","cmd":"status"}
/maidai {"id":"a5","cmd":"cancel"}
```

回执：`{"id":"a1","ok":true,"state":"running","step":"accepted","result":{"task":"guard"}}`。指令集与 `/maidtasks` 完全一致。

### 感知快照 `/maidperception`

执行后完整 JSON 写入 `logs/latest.log`（`[SmartMaid-Debug] 手动感知快照:`），聊天栏回显摘要。区块：`self` / `inventory` / `owner` / `nearby` / `blocks` / `env` / `events`。

### 游戏内女仆菜单（Shift+右键）

木质风设置菜单（自绘暖棕木纹贴图 + 原版控件），设置与玩家绑定、改完即时生效：
**友军伤害** / **自动跟随**（开关 + 触发/停止距离）/ **护主模式**（主动·被动·关闭）/ **最大生命** / **饱食消耗速度** / **回血速度** / **死亡掉落**（全部·保留装备·不掉落）。底部含「坐下/站起」「召回」。每点一次 `logs/latest.log` 记录 `[SmartMaid-Debug] 设置更新 <字段>=<值>`。

### 聊天栏对话 + 桌宠隐退

- `/maidchat`：进入/退出女仆对话模式。开启后聊天栏输入的普通文本不再发公共聊天，而是转给桌宠 AI；回复冒在女仆头顶（**多行气泡**）+ 聊天栏「女仆：…」+ 桌宠语音朗读。
- 按住 `Y` 说话：走桌宠语音识别（需桌宠开启语音输入），女仆在线时自动作为对女仆说的话。
- 桌宠隐退：`/summonmaid` 时通知桌宠；桌宠设置 → 游戏 勾选「召唤女仆后隐藏桌宠窗口」即隐藏窗口（AI/语音后端保留），女仆离线/断线自动恢复。

## 数据存储

- 女仆装备/物品栏：`<游戏目录>/config/smartmaid/maids/<玩家UUID>.dat`（NBT 压缩文件，自动读写）
- 女仆设置：`<游戏目录>/config/smartmaid/settings/<玩家UUID>.json`（JSON，与玩家绑定，菜单修改自动保存）

## 架构

```
SmartMaid (Fabric 26.2)
├── SmartMaid              主类：注册实体 + 命令
├── init/ModEntities       实体类型注册（ResourceKey）+ 属性注册
├── command/SummonMaidCommand   /summonmaid 指令
├── command/MaidAnimCommand     /maidanim 动作指令（开发校验）
├── command/MaidTaskCommand     ★/maidtasks AI 任务指令（全部指令入口）
├── command/MaidAICommand       ★/maidai <json> AI 指令（走 MaidAIBridge）
├── command/MaidPerceptionCommand ★/maidperception 感知快照调试
├── entity/SmartMaidEntity 女仆实体（TamableAnimal + ContainerUser + perceptionModule + combatGoal）
├── entity/MaidFoodData    女仆饱食度（仿玩家 FoodData；内部回血/饥饿，仅内部状态）
│   └── ai/
│       ├── MaidFollowGoal         跟随主人（L1 规则）
│       ├── MaidMoveControl        原版 MoveControl + setZza(1) + 跨沟检测交给执行器
│       ├── MaidActionExecutor     ★跳跃执行器：查表 → 定位起跳点 → 转身 → 起跳施速 → 空中维持
│       ├── MaidJumpTable         ★离线精确解表（tools/gen_jump_table.js 生成）
│       ├── JumpPhysics           ★物理常量 + 前向轨迹模拟（prismarine-physics 公式）
│       ├── MaidGroundPathNavigation  专用导航（注入自定义评估器）
│       ├── MaidWalkNodeEvaluator  跳跃寻路（放宽高差 + 生成跨沟邻居）
│       ├── MaidStraightNav        ★寻路降级导航器：原版失败/绕远 → 直线移动 + 挖/搭
│       ├── MaidBlockBreaker       ★挖掘执行器（走到旁→裂纹耗时→破坏进背包）
│       ├── MaidBlockPlacer        ★搭方块执行器（自动选支撑面放置，仅耗普通建材）
│       ├── MaidMeleeAttackGoal   近战攻击
│       ├── MaidDebug              调试日志开关
│       ├── MaidMonitor            移动调试监测
│       ├── MaidActions            ★能力层：移动/背包/方块/攻击/挖掘进度原子动作
│       ├── slot/                  ★正交槽位系统（ItemSlot + Slots，transfer 基础）
│       ├── craft/CraftExecutor    ★制作：反查配方 + 借位合成（无需工作台）
│       ├── maidtask/              ★AI 任务系统
│       │   ├── MaidAITask         任务抽象
│       │   ├── MaidTaskManager    调度器（aiBusy 接管行为；战斗期取消任务）
│       │   └── Attack/Guard/Feed/Eat/Mine/Farm/Build/Collect/Craft/Smelt/
│       │       MoveTo/Transfer/ChestOpen/OneShot Task
│       ├── combat/                ★女仆战斗系统（C0–C5，2026-09-17）
│       │   ├── MaidCombatGoal     战斗状态机（盾>吃>远程>近战；战斗高于任务）
│       │   ├── MaidCombatMovement 走位：面向敌人后退 / 侧移绕行 / 背对跳
│       │   ├── MaidTargetFilter   敌对排除表 + 友军仅玩家
│       │   ├── MaidWeaponSelector 近战 DPS 评分 / 弓 POWER 选择
│       │   ├── MaidRangedSkill    远程自写发射（复制 Player 专属逻辑）
│       │   ├── MaidThreatDetector 箭矢/苦力怕威胁检测
│       │   ├── MaidShieldSkill    举盾格挡（BLOCKS_ATTACKS 减伤）
│       │   └── MaidArrow          不伤玩家的箭
│       ├── perception/            ★感知模块（M-P0）：MaidPerception + PerceptionModule + 6 通道 + 事件队列
│       │   └── PerceptionDiff.java ★M-P1 增量 diff（真机 5761B → 971B，降 83.1%）
│       └── bridge/                ★AI 指令桥接 + M5 桌宠联动
│           ├── MaidAIBridge.java          JSON 指令 → 任务 → 回执（24 指令）
│           ├── MaidCommandResult.java     回执模型
│           ├── MaidTaskConfig.java        任务配置持久化
│           ├── MaidAutoTest.java          代码层自动化测试
│           ├── BridgeConfig.java          ★M5 集中配置（首次自动生成）
│           ├── MaidTelemetryWriter.java   ★M5-a 感知下行（20s 窗口 jsonl，按分钟轮转）
│           ├── MaidWsClient.java          ★M5-b 指令上行（WS Client，心跳 5s + 指数退避）
│           └── ItemIndexPayload.java      ★M6-b 物品索引下发（当前语言名→id，NLU 用）
├── data/MaidDataManager   本地数据持久化（NBT 文件）
├── data/MaidSettings      ★玩家绑定设置（JSON：友军/跟随/护主/属性/掉落，config/smartmaid/settings/<玩家UUID>.json）
├── gui/                   女仆背包/管理菜单（服务端 Menu + ContainerData 状态同步）
├── network/               GUI 动作 + 设置 + 对话网络包（MaidSettingsPayload / MaidChatPayload）
├── client/
│   ├── gui/MaidControlScreen  ★木质风设置菜单（自绘暖棕木纹贴图 + 原版控件）
│   ├── gui/WoodButton         木质九宫格按钮（宽/窄两态，悬停变亮）
│   ├── chat/MaidChatClient    ★客户端「女仆对话模式」状态（/maidchat 开关）
│   ├── renderer/          渲染（玩家模型 + 女仆皮肤 + 坐姿/手持 + 多行气泡）
│   └── animation/MaidAnimManager  ★动作动画：加载 Emotecraft JSON → PAL 控制器 → 骨骼写入模型
└── resources              皮肤贴图、木质 GUI 贴图（textures/gui/maid_*.png）、Emotecraft 动作 JSON、fabric.mod.json
libs/                     ★本地依赖（Player Animation Library + mocha，jar-in-jar 打包）
tools/
├── maid_monitor.ps1       资源/日志监测脚本
├── maid_jump_sim.js       ★速度/落点/朝向模拟（Node，无依赖）
├── gen_jump_table.js      ★离线生成 MaidJumpTable.java
├── fix_ai_skin.py         修复 AI 皮肤（镜像补齐）
├── fix_maid_skin_hands.py 修复皮肤手部缺失面
└── maid_inventory_sim.js  ★背包槽位规则验证（Node，无依赖）
├── launch_game.py         ★从 HMCL 日志提取启动命令 + 补 token + quickPlay
└── run_e2e_test.py        ★一键端到端联调：起桌宠 → 起游戏 → 轮询 → 报告；含 --check-token
config/smartmaid/
├── autotest.json          ★代码层自动测试配置（JSON 指令数组，进游戏自动执行）
├── bridge.json            ★M5 桌宠联动配置（遥测 + WS；首次自动生成）
└── maids/<UUID>.dat      背包/装备存档；.cfg AI 任务配置
    settings/<UUID>.json  ★玩家绑定设置（女仆菜单修改自动保存）
```

## 工具脚本

```bash
# 本地模拟女仆移动/跳跃（基于 prismarine-physics 公式，无需游戏）
node tools/maid_jump_sim.js

# 重新生成离线精确解表（改速度/障碍场景后运行）
node tools/gen_jump_table.js

# 验证女仆背包槽位规则（与 Java 端逻辑一致）
node tools/maid_inventory_sim.js

# 端到端联调（游戏 + 桌宠一起跑）
# 前置：用 GUI 启动器登录一次（让 HMCL 刷新 accessToken）
python tools/run_e2e_test.py --quick-play "新的世界 (11)"
#  --check-token    在跑前校验 HMCL 凭证，过期早退
# 内部逻辑：launch_game.py 从 HMCL 日志逆向提取启动命令 + 补 quick-play
```

## 动作动画（/maidanim）

- 动作来源：Emotecraft 动作 JSON（`assets/smartmaid/emotes/`，11 种，来自 Emotecraft 内置表情，**GPL-3.0**），由 PAL `UniversalAnimLoader` 解析
- 播放引擎：Player Animation Library 的 `HumanoidAnimationController`（不依赖 Avatar，女仆 TamableAnimal 直接用）
- 驱动：每游戏 tick `tick(data)` 推进时间 + 渲染帧 `setupAnim(data)` 计算骨骼 → 6 骨骼写入 `ModelPart`
- 用法：`/maidanim <waving|clap|point|palm|here|crying|backflip|twerk|kazotsky_kick|roblox_potion_dance|club_penguin_dance>`，`/maidanim none` 停止

## 路线图

- [x] 26.2 Fabric 工程 + 女仆实体 + 召唤指令
- [x] 跟随 AI + L0 安全层（岩浆/火）
- [x] 原版移动体系 + 车万女仆式跳跃优化 + 跳跃寻路
- [x] Java 版渲染 + 128×128 玩家模型皮肤（基于"大肥鱼"角色立绘生成，CC BY-NC-SA）
- [x] 数据持久化（装备/物品栏本地存储）
- [x] 跳跃动作执行器（跨沟/上高台/下跳）+ 离线精确解表
- [x] 女仆 = 玩家：背包/手持/副手规则 + 死亡全掉落 + 皮肤修复
- [x] 动作动画系统（Emotecraft JSON + PAL 引擎，/maidanim 播放）
- [x] AI 任务系统（/maidtasks：攻击/护卫/喂食/挖矿/耕作/建造/收集/制作/烧炼）
- [x] 正交槽位 transfer（换手/收纳/穿戴/交换/放置/丢出）+ 两步存箱子（开箱动画）
- [x] 感知模块（M-P0：自身/背包/主人/实体/方块/环境快照 + 事件队列）
- [x] AI 指令桥接（M4：/maidai JSON 指令协议 + 回执 + 任务配置持久化）
- [x] **桌宠联动 M5-a（感知下行：写 `<游戏目录>/deskpet/maid/*.jsonl`）**
- [x] **桌宠联动 M5-b（指令上行：WebSocket Client 连桌宠 `ws://127.0.0.1:21420`）**
- [x] **感知增量 diff（M-P1：首帧全量+后续 changed/removed，真机降 83.1%）**
- [x] **一键端到端联调（`tools/run_e2e_test.py`）**
- [x] **`craft_check` 干跑查询（M6-a：不消耗材料的配方/缺料明细）**
- [x] **`item_index` 物品索引下发（M6-b：当前语言名→id，支撑桌宠本地物品名匹配）**
- [x] **`owner.pos` 感知补全（M6-c：相对指令「在我脚下…」可换算）**
- [x] **寻路降级（挖方块 + 搭路，2026-09-15 真机验收）**：原版寻路失败/绕远 → 直线物理路径 + 破坏/搭方块，动态切回
- [x] **女仆战斗系统（C0–C5，2026-09-17）**：近战 3.2 / 走位（后退·绕行·背对跳）/ 远程弓箭 / 盾牌格挡 / 饱食度+进食回血 / 免伤玩家 / 敌对排除表；桌宠侧新增 `eat` 指令并重训 NLU
- [x] **玩家绑定设置 + 木质女仆菜单 + 聊天栏对话 + 桌宠隐退（2026-09-19）**：设置与玩家 UUID 绑定持久化、改完即时生效；菜单自绘暖棕木纹贴图（原版控件）；`/maidchat` 聊天栏对话（多行气泡回复 + 语音朗读）；召唤时通知桌宠隐退（开关在桌宠设置，AI/语音后端保留）
- [ ] 战斗系统真机回归收尾（全流程：近战/走位/背对跳/远程/盾/进食/任务抢占）
- [ ] 鱼竿攻击（spike 结论：`FishingHook` 非玩家 owner 首 tick 丢弃，原版不可用；暂不做）
- [ ] 跳跃系统完整实测标定（对角跨沟、跨 3 沟上 1 格等）— P3
- [ ] 发布前收尾（/summonmaid 权限、debug 日志关闭）— P4
- [ ] 基岩版模型（车万女仆 Q 版模型）渲染 — P5
- [ ] 战斗 AI / 物品交互等更多规则

### 已知 bug（已修复 ✅）

- ~~**`damage` 事件 `target:"?"` 攻击者类型解析遗漏**~~（HurtEvent 时序，已修复）：`SmartMaidEntity` 覆写 26.2 `hurtServer` 在最早阶段缓存攻击者 + `attackerLabel()` 类型映射表（Player→玩家名 / Projectile→射手 / 其他→短名 / 无→`unknown`），`target` 不再写 `?`；未知时遥测重要性降 NORMAL。详见 `DEVELOPMENT_ISSUES.md` 2026-09-10 §问题 5 修复记录。

## 开发记录

- 开发中踩过的坑与复盘：参见 [`DEVELOPMENT_ISSUES.md`](./DEVELOPMENT_ISSUES.md)（含"玩家式移动改造"失败教训、26.2 速度机制、跳跃物理/寻路/解表的完整坑）
- **代码层自动化测试**：26.2 单机（集成服务器）不支持 RCON，自动化测试用 `MaidAutoTest`——写 `config/smartmaid/autotest.json`（JSON 指令数组）进游戏自动执行，结果在 `logs/latest.log` 搜 `[SmartMaid-Debug] AutoTest`，执行完配置自动改名为 `autotest.done.json`
- **日志分层（不污染日志）**：周期性/每帧高频日志（感知快照、装备、Monitor、渲染、挖矿/烧炼进度）全部走 `MaidDebug.verbose()` 门控（默认关）；仅事件性日志默认输出。感知快照全量 5-6KB/条，曾让数百小时游戏累积数 GB 的 `latest.log`。

## 参考项目

- **Touhou Little Maid**（车万女仆）：<https://github.com/TartaricAcid/TouhouLittleMaid>（本项目的移动/跳跃思路参考，代码部分 MIT）
- **Emotecraft**：<https://github.com/KosmX/emotes>（表情动画 JSON 来源，GPL-3.0）
- **Player Animation Library**：<https://github.com/ZigyTheBird/PlayerAnimationLibrary>（动画播放引擎，MIT）
- **mineflayer-baritone**：实测验证（能跑但问题多），其依赖 **prismarine-physics** 是 MC 物理的权威复刻，跳跃公式来源
- **桌面宠物（DeskPet）**：<https://github.com/oyxdsg/Multimodal-AI-Companion>（AI/语音/气泡，本模组的联动对象；**要联动的先下载它**）
- **桌宠动画素材包**：<https://github.com/oyxdsg/Multimodal-AI-Companion-Assets>（桌宠的完整动作动画，Release 附件）

## 许可

**本项目免费开源、不收取任何费用，也不用于商业用途。**

| 内容 | 许可 |
|---|---|
| 本项目原创代码（Java 源码、构建脚本、文档、木质 GUI 贴图） | **MIT**（见仓库根 [`LICENSE`](../LICENSE)） |
| `entity/ai/MaidMoveControl.java` 的"触发跳跃"判断条件 | 改编自 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（代码部分 MIT，Copyright © 2019-2025 tartaric_acid），文件头已保留其版权声明 |
| `assets/smartmaid/emotes/*.json`（11 个表情动画） | 来自 [Emotecraft](https://github.com/KosmX/emotes)（KosmX）内置表情的未修改副本，**GPL-3.0** |
| 女仆皮肤图片（`textures/entity/smart_maid.png`、仓库根 `皮肤大肥鱼.png`） | 基于"大肥鱼"角色立绘制作，**CC BY-NC-SA 4.0**（须署名 / **禁止商用** / 衍生须相同方式共享） |
| `libs/` 内置第三方库 | Player Animation Library（MIT）；mochafloats（PAL 传递依赖） |

> 完整来源与条款见仓库根 [`NOTICE.md`](../NOTICE.md)。该声明也已随 jar 打包到 `META-INF/` 下，
> 二进制分发时许可信息不会丢失。**皮肤与表情数据不可用于商业用途。**
