# Smart Maid 交接文档

> 给下一个接手开发的人。记录项目背景、当前进度、关键实现、已知问题与下一步。

## 一、项目背景与目标

**一句话**：做一个"桌宠内核进入 Minecraft 成为女仆"的模组——规则引擎驱动行为（安全/移动/战斗），AI（来自桌面宠物程序）作兜底（聊天/知识/复杂指令），通过 WebSocket 与桌宠通信。

**来源**：用户在开发自己的女仆模组，下载了 Touhou Little Maid（车万女仆）1.20.1 Forge 源码做研究；同时已有桌面宠物项目（同一 GitHub 账号下的 `Multimodal-AI-Companion`）。本模组是全新的 **Minecraft 26.2 Fabric** 工程（车万女仆的模型/皮肤资源可复用，但代码不共用）。

**核心设计原则**（用户明确要求）：
1. 规则引擎主导、AI 仅兜底、本地安全优先
2. 女仆唯一、无需驯服、直接归属玩家
3. 退出游戏女仆消失，数据本地持久化（重召唤恢复）
4. **移动完全走原版体系**（MoveControl/Navigation/travel/JumpControl），跳跃触发参照车万女仆优化
5. **能力一致而非速度一致**：女仆跳跃要做到玩家能做的一切，用离线精确解表，模组运行时只查表不计算
6. **女仆 = 玩家**：把女仆当成独立的玩家个体对待——背包布局与玩家完全一致（41 格：0-8 热键/9-35 背包/36-39 盔甲/40 副手）、主手持热键栏第 0 格、副手可放任意物品、死亡物品全掉落。女仆背包 GUI 里**不**加玩家自己的格子（物品转移靠玩家丢弃→女仆拾取）。

> ⚠️ 曾尝试"玩家式移动改造"（自写 A\* + 手动 velocity + 覆盖 travel），多轮失败后回退到原版体系。**坑与教训见 `DEVELOPMENT_ISSUES.md`，务必先读。**

## 二、当前进度（最近更新：2026-09-21）

### 已完成（2026-09-21：女仆挖掘能力对齐玩家 —— 挖挡路块 + 搭高采矿，真机验证通过 ✅）

> 用户反馈"女仆能透过表面方块挖背后方块 / 挖高处矿不搭高"。经多轮真机迭代，**最终真机验证通过**：
> 高处铁矿女仆成功搭方块上去挖到。

**核心：重构 `MaidBlockBreaker` 为「对齐玩家挖掘本质（准星命中哪个方块挖哪个）」**。`tick` 决策顺序：

0. **挖掘中守卫**（`digTicks>0`）：一旦开始挖某个方块就**持续推进挖完，不被 pillar/挡路块/导航打断**
   （否则搭高后站在单格柱子上挖矿会被反复打断）
1. **目标在女仆上方且垂直够不着 → pillar 搭高**（最高优先级）：
   - 水平太远 → 先导航接近（原版 A* / 直线降级）；水平够近 → 原地搭高
2. 水平太远 → 导航接近
3. 水平够近 → **`MaidActions.lineHit` 挖视线里第一个非目标方块**（含脚下土 → 挖土逐层往下找埋地矿）
4. 能直接挖 → 裂纹 + 耗时破坏

**pillar 搭高实现要点（多轮真机踩坑后的最终版）**：
- **放块时机 = 脚底离开原格**（`脚底 y - 起跳前 y > 1.0`，原格完全空出、无实体碰撞）——**不用固定 tick**：
  固定 tick 会因 MoveControl 干扰导致起跳时机漂移，放块时身体仍在原格 → `BlockPlaceContext`
  实体碰撞检查 → 放置静默失败 → 没垫上 → 落地没升高 → 超时掉落。升空窗口内（jump 后 y>1.0 持续约 6t）**每 tick 都尝试放**
- **放块失败自动重试**：落地没升高 → 重新起跳再垫，连续失败 8 次才放弃
- **搭高期间逐 tick 锁定水平**（`navigation.stop()` + `moveControl.setWait()`）——防从 1 格宽柱子漂移掉下
- **落地判定用高度**（脚底升高 ≥0.5 格即完成一层）
- 只耗普通建材（`equipBridgeBlockFromBackpack`，无普通建材则用任意方块）
- 离线物理（`tools/maid_jump_sim.js`「pillar 垫高」段）：vy0=0.42，最高点 y=1.252 @t=6，落地 t=12

**其他修复**：
- **埋地矿挖一格就停**：挖完挡路块后**不再 `target=null`**——保持"已挖空"状态，下一 tick 自动
  `beginDig(goal)` 切回真正目标，逐层挖到矿
- **方向保护**：`needPillar` 只对目标在女仆**上方**触发（埋地矿走挖土往下，绝不反向搭高）
- **不再假报完成**：挖矿一块没挖到但有矿被跳过 → `MineTask.result()` 返回「没挖到矿（够不到或无法搭高）」

**日志**：`Breaker 挖挡路块 / pillar 起跳·垫·上一层 / pillar 无方块·连续失败放弃 / 无法搭高到...，放弃`

**验证**：`gradlew build` 通过并部署；**真机：高处铁矿搭高挖到 ✅**

- [x] **pillar 垫高（`MaidStraightNav` 垂直同柱，寻路降级到达位置用）**：目标在正上方且跳不上时，
  起跳后第 3 tick 往脚下放方块（离线解表），落地站上新方块 → 高度 +1 循环直到可跳；只耗普通建材、
  `setWait()` 停水平移动、60t 超时保护




### 已完成（2026-09-19 三次：挖矿坐标可选 + 挖掘面向 + 对话事件静默 + 生物名中文 + 指令/TTS 剥离 + 女仆第一人称）

- [x] **挖矿坐标可选（`mine` 无 pos 自动探测）**：用户反馈"挖矿还要给坐标不合理，女仆应该自己检测周围矿物"。模组 `MaidAIBridge.mine` 的 `pos` 改为可选——缺省以女仆脚下为中心 `new MineTask(maid.blockPosition(), range=12, count=8)`；`/maidtasks mine` 加无参重载（`ownerMaidPos` 取女仆脚下）。**探测范围：水平 12 格 / 垂直 8 格**（`MineTask.Y_RANGE=8`，scanArea 原来 `±range` 对称、y 也 12 太深）。桌宠侧同步：`mod_contract.mine.pos` 改可选 + range 默认 12、`taxonomy.mine` 策略 `abs_pos→pos`、`router` mine 无 pos 时不发 pos 参数、prompt 说明"挖矿不用给坐标"。真机：`把矿挖一下` → 女仆自动挖出 3 块铁矿
- [x] **挖掘朝向修正**：`MaidBlockBreaker.tick` 开始挖前没面向目标，女仆背对/侧对矿挥镐。加 `lookControl.setLookAt(目标方块中心)`，挖掘时面向矿
- [x] **对话期间游戏事件静默（两条线抢话）**：用户反馈"发一条消息女仆回复好几条"。真机日志实锤：`23:28:06 女仆回复`后 12s `23:28:18 桌宠气泡"嗯，是我刚说的"`、`23:28:27`后 14s `23:28:41 桌宠气泡"铁矿挖出来啦"`——`game_handler` 游戏事件 AI 线在对话后仍 speak 抢话。修复双层：①`game_handler._handle_game_windows` 女仆在线且 `recently_in_chat()`（**120s 窗口**，原来 6s 太小）→ 事件线整体静默（不调 AI/不播报/清累积）；②`modes._on_game_ready` 对话期间不 speak 到女仆。同时把感知事件气泡默认关（`MAID_EVENT_BUBBLE=False`，只播 AI 聊天）
- [x] **生物名翻译中文**：`maid_link.ENTITY_CN` 实体 id→中文映射（60+ 生物/怪物：stray→流浪者、parched→帕查德、pillager→掠夺者、zombie→僵尸…），`event_to_text`/`summarize_snapshot`/`maid_context._full_status` 全走 `_short_name` 翻译，AI 上下文与气泡都看中文
- [x] **指令/TTS 剥离（气泡语音不显示指令）**：`ai/client.parse_ai_output`/`strip_action_tags` 增加 `_DSL_RE` 剥离 `【指令】`（千问流式不走 MaidLoop，指令会残留进气泡/TTS）；`chat._on_delta`/`_maybe_stream_speak`/`_on_reply` 流式朗读入队前剥离；`bubble._show_ai_bubble` 最底兜底剥离（既不上屏也不进 TTS）
- [x] **女仆第一人称（AI 代入自己是女仆）**：`_maid_chat_turn` head 明确"你就是玩家在游戏里召唤的那个女仆，用第一人称「我」，不要第三人称称呼自己"；状态行去"女仆："前缀转"我的"；`_run_decision` 决策 prompt 第一人称；`maid_loop._reply_text` 回执 `[系统] 我的任务已受理`；`maid_context._full_status` "你当前状态"。真实 API 验证：三条游戏内对话全部第一人称，不再第三人称称呼女仆
- [x] **prompt 战斗认知修正**：4 个 prompt（prompt/prompt_api/prompt2/prompt2_api）女仆指令章节加"女仆会自动战斗，普通打怪不要发 attack/guard；只有明确指定目标才用 attack(target=...)"，并改掉矛盾范例（"僵尸→【attack】"）。真机 `tools/test_api_game.py`：宠物女仆不再主动发 attack（模型仍有偶发，靠剥离兜底）

### 已完成（2026-09-19 二次：战斗走位死区 + 盾牌免前摇 + 背包模型预览 + 盾牌诊断日志）

- [x] **近战走位死区修正 2.7~3.2**：原死区 `3.0±0.3`（上限 3.3）导致 3.2~3.3 之间女仆停在死区够不着打不到。`MaidCombatMovement.keepDistance` 增加 `maxDistance` 参数（死区上限，单参重载保留对称行为供进食/远程用）；`MaidCombatGoal` 近战传 `SmartMaidEntity.MAID_ATTACK_REACH`（改 public static）。效果：>3.2 前进拉近、<2.7 后退、2.7~3.2 死区
- [x] **盾牌去掉 0.25s 架盾前摇**：真机打骷髅发现"总是中箭"。诊断日志证明根因是原版 `LivingEntity.getItemBlockingWith()` 要求**使用时长 ≥ `blockDelayTicks`(5)** 才认作举盾，而骷髅贴脸射箭 1~2t 就命中，检测到威胁时举盾永远来不及生效（5 次中箭 `blockingBefore=false`、`usingTicks=0~2`）。**覆写 `SmartMaidEntity.getItemBlockingWith()`**：只要 `isUsingItem()` 且手上有 `BLOCKS_ATTACKS` 物品即认作举盾，当场生效（`isBlocking`/`applyItemBlocking` 共用此方法）。角度判定（`resolveBlockedDamage` ~100° 扇形）保留，只挡正面。修复后 14 次中箭全部 `blockingBefore=true`、实际挡下不掉血（日志 `dmg` 是格挡前原始值）
- [x] **女仆背包界面左上角渲染女仆自身缩小模型**：复用原版 `InventoryScreen.extractEntityInInventoryFollowsMouse`（跟随鼠标旋转、显示皮肤/装备/手持）。`MaidInventoryMenu` 增加 `DATA_MAID_ID`（服务端同步女仆实体 id，客户端 `level.getEntity` 取实体）；`MaidInventoryScreen` 覆写 `extractRenderState` 缓存鼠标坐标 + `extractBackground` 渲染模型
- [x] **盾牌/箭矢诊断日志**：`MaidThreatDetector.debug`（每 10t 打印周围箭矢实体：位置/速度/距女仆/射手/预测命中 tick + 女仆朝向/举盾状态）；`MaidCombatGoal.handleShield` 举盾/收盾打印方向（yaw/headRot）与时间（tick/holdTicks）；`SmartMaidEntity.hurtServer` 中箭打印 箭矢实体/射手/方向夹角 `facingErr`/举盾状态 `blockingBefore`/`usingTicks`。日志前缀 `CombatShield` / `Combat block` / `Combat hit-by-arrow`

### 已完成（2026-09-19：玩家绑定设置 + 木质菜单 + 聊天栏对话 + 桌宠隐退）

- [x] **玩家绑定设置 `data/MaidSettings.java`**：与玩家 UUID 绑定、JSON 持久化（`config/smartmaid/settings/<玩家UUID>.json`，Gson + 内存缓存）
  - 字段：友军伤害 / 自动跟随（开关 + 触发/停止距离档位）/ 护主模式（`ACTIVE`·`PASSIVE`·`OFF`）/ 死亡掉落（`ALL`·`KEEP_EQUIPMENT`·`NONE`）/ 最大生命 / 饱食消耗速度 / 回血速度
  - **服务端权威**：客户端只发「字段名 + 档位索引」（`MaidSettingsPayload` C2S），服务端 `applyField` → 落盘 → `applyToMaid` 即时生效
  - 行为接入：`MaidFollowGoal` 实时读距离/开关；`registerGoals` 三个目标选择 Goal 按护主模式门控；`canAttack`/`doHurtTarget`/`MaidArrow` 读友军伤害；`applyToMaid` 设最大生命（超限截血）；`MaidFoodData.setRates` 控制饱食消耗/回血速度；`dropAllDeathLoot` 按掉落模式决定掉哪几格（KEEP_EQUIPMENT 保留 36-40、NONE 全保留重召唤恢复）
- [x] **游戏内木质风菜单**：`MaidControlScreen` 重写 + 新增 `client/gui/WoodButton.java`，自绘暖棕木纹贴图（`textures/gui/maid_panel.png` / `maid_button*.png`，程序化生成）；设置值经 `MaidControlMenu` 的 ContainerData（`DATA_COUNT=12`）实时同步回显；按钮文字走原版 `ActiveTextCollector.extractDefaultLabel`
- [x] **聊天栏对话模式**：客户端 `/maidchat` 开关（Fabric `ClientCommands`）+ `ClientSendMessageEvents.ALLOW_CHAT` 拦截普通文本 → `MaidChatPayload` → 服务端 `MaidWsClient.sendChat` 上行 → 桌宠 AI → `chat_reply` 下行 → **多行气泡**（新增 `DATA_BUBBLE` 同步字段 + `SmartMaidRenderer.submitNameDisplay` 逐行提交名字标签）+ 聊天栏回显 + 桌宠语音朗读
- [x] **桌宠隐退 + 语音**：模组召唤/死亡发 `maid_presence`；桌宠按设置（`config.py MAID_HIDE_PET_ON_MAID`，设置→游戏 勾选）隐藏窗口并抑制桌宠气泡（TTS 保留）；Y 语音复用桌宠 STT，女仆在线时自动作对女仆说的话
- [x] 桌宠侧对话闭环：`MaidHandler.chat_turn` 走 `MaidLoop.process_reply` 剥离并下发【指令】；`world:~` 自动纠错为主人坐标；背包槽位/主人坐标注入 prompt 帮助 AI 生成可执行指令
- [x] 构建通过并部署；菜单按钮真机逐项点击，日志确认全部生效（`[SmartMaid-Debug] 设置更新 <field>=<value>`）

**遗留/已知**：
- 菜单 UI 观感待打磨（用户暂接受「凑合用」）：木质贴图为程序化生成，固定尺寸贴图规避了九宫格拼接 bug
- 桌宠侧 `desktop-pet` 改动：`config.py` / `game/maid_link.py` / `pet/handlers/maid_handler.py` / `stt_handler.py` / `bubble.py` / `modes.py` / `settings.py` / `settings_dialog.py`
- 行为层（跟随/护主/掉落/属性）逻辑已核对，真机行为验证待补日志确认

### 已完成（2026-09-11：桌宠 NLU 支撑）
- [x] **`craft_check` 干跑查询（M6-a）**：`CraftExecutor.check()` —— 走与 `craft` 相同的配方解析路径但**不消耗材料**，逐项回报 `ingredients[].{options,need,have,ok}`（tag 类配方给候选列表、多产出配方按 `out_per_craft` 折算要做的次数）。`MaidAIBridge` 里作为**查询指令**（不进任务队列、不改世界状态）处理
- [x] **`item_index` 物品索引下发（M6-b）**：`bridge/ItemIndexPayload.java` —— 握手后把「当前语言物品名 → `minecraft:id`」整表推给桌宠（客户端 `I18n` 取名，覆盖 mod 物品；`null` 表示专用服务端无语言资源）。桌宠据此本地做中文/拼音模糊匹配（语音「稿子」→「镐子」）
- [x] **`owner.pos` 感知补全（M6-c）**：`OwnerSense` 补主人**方块坐标**（原来只有 `dist`），桌宠才能把「在我脚下放方块 / 到我这儿来」换算成模组要的绝对坐标
- [x] 构建验证：JDK 25 + Gradle wrapper `BUILD SUCCESSFUL`

> 这三项都是**桌宠侧 NLU（本地意图识别 + 自动执行）**的支撑需求：桌宠要在本地判断
> 「用户是不是在指挥女仆干活」，就必须先有物品词典、配方事实、以及可换算的位置参照。

### 已完成（M5 双向链路 + 增量 diff + 自动化全打通）
- [x] 26.2 Fabric 工程搭建（loom 1.17 + JDK 25 + Gradle 9.7.1）
- [x] 女仆实体 `SmartMaidEntity extends TamableAnimal`（ResourceKey 注册 + noSave）
- [x] `/summonmaid` 指令（每玩家限一只、权限未限制、召唤即 tame、自动恢复数据）
- [x] `MaidFollowGoal` 跟随主人（3~10 格）
- [x] L0 安全层：脚下岩浆/火/岩浆块起跳脱离
- [x] **原版移动体系**：`MaidMoveControl` + `MaidGroundPathNavigation` + `MaidWalkNodeEvaluator`
- [x] **跳跃动作执行器 `MaidActionExecutor`**：跨 1~3 格沟、上 1 格高台、跨沟上 1 格、下跳 1~3 格
- [x] **离线精确解表 `MaidJumpTable`**：模组运行时只查表，不做物理计算
- [x] **速度体系**：`MOVEMENT_SPEED=0.16`；跳跃 takeoff 固定（走 0.30 / 跑 0.55，不需助跑）
- [x] Java 版渲染（HumanoidModel + 女仆皮肤）+ **盔甲外观**（`HumanoidArmorLayer` + `PLAYER_ARMOR` bake）
- [x] **坐姿**：覆写 `setOrderedToSit` 同步 `setInSittingPose`，走原版骑乘坐姿（僵尸坐船同款）
- [x] **聊天气泡** `showBubble`（名字标签实现，召唤欢迎语）
- [x] **女仆交互 GUI**：Shift+右键=管理菜单（坐下/召回/清目标）；Shift+E=女仆背包（照搬玩家背包：41 槽装备+背包+热键）
- [x] **攻击任务**：`MaidMeleeAttackGoal` + 目标选择（主人仇恨/附近怪物）+ 坐姿清目标
- [x] **拾取物品**：捡掉落物进女仆背包（只进 0-35，不穿装备）
- [x] **数据持久化** `MaidDataManager`（装备+41 格背包 NBT；正常进出恢复，**死亡清零**）
- [x] **女仆=玩家 背包规则**：主手手持热键栏第 0 格（`syncSlot(MAINHAND, 0)`）、副手（40）可放任意物品（`MaidArmorSlot.mayPlace` 放行 + 存档恢复放行）、死亡全 41 格掉落（`dropAllDeathLoot`）
- [x] **手持渲染**：`SmartMaidRenderer` 覆写 `getArmPose`（手持→ITEM 姿势），删除强制 EMPTY；`ItemInHandLayer`（HumanoidMobRenderer 自带）渲染主/副手物品
- [x] **AI 生成皮肤**（桌宠形象）+ 修复脚本 `tools/fix_ai_skin.py`
- [x] **动作动画系统**（路线B：依赖 PAL + Emotecraft 动作）：`MaidAnimManager` 加载 `assets/smartmaid/emotes/*.json`（Emotecraft 11 个动作），用 PAL `HumanoidAnimationController` 播放，`SmartMaidModel.setupAnim` 应用骨骼；`/maidanim <wave|clap|point|palm|here|crying|backflip|twerk|kazotsky_kick|roblox_potion_dance|club_penguin_dance>` 播放
- [x] 调试工具：`MaidDebug` + `MaidMonitor` + `tools/maid_monitor.ps1` + **`tools/maid_jump_sim.js`** + **`tools/gen_jump_table.js`** + **`tools/maid_inventory_sim.js`**
- [x] **AI 任务系统**（M1-M3.5）：能力层 `MaidActions`（移动/背包/方块/攻击/挖掘进度）+ 任务框架 `MaidAITask`/`MaidTaskManager`（aiBusy 接管行为）+ `/maidtasks` 指令 + 9 个任务（Attack/Guard/Feed/Mine/Farm/Build/Collect/Craft/Smelt）+ `CraftExecutor` 原地合成
- [x] **正交槽位 transfer**：`ItemSlot`/`Slots`（MaidSlot/WorldSlot/ContainerSlot）+ `TransferTask`（移动+交换+掉落语义），覆盖换手/收纳/穿戴/交换/放置/丢出
- [x] **两步存箱子**：`chestopen`（智能定位箱子 + 女仆实现 `ContainerUser` 触发真实开箱动画）→ `chestput`（智能堆叠 64/16/1 优先同类→空槽）/ `chesttake`
- [x] **命令参数规范**：全部 MC 原生类型（槽位 word/数字、物品 IdentifierArgument、坐标 BlockPosArgument），放弃自定义 ArgumentType（避免命令树同步崩溃）
- [x] 挖矿有挖掘过程（硬度×工具计时 + 裂纹 0-9）+ 掉落物直接进背包（getDrops 含时运）
- [x] **感知模块（M-P0）**：`entity/ai/perception/`——给 AI"玩家视角"的感知快照。`MaidPerception`（Gson 快照模型）+ `PerceptionModule`（分层采样调度 + 事件队列）+ 6 通道（`SelfSense` 自身/安全/动作、`InventorySense` 背包+能力摘要、`OwnerSense` 主人、`EntitySense` 周边实体、`BlockSense` 方块/容器/矿石/作物/危险、`EnvironmentSense` 环境/区块）+ `PerceptionJson`（M5 序列化入口）；`/maidperception` 指令；从 `AttackTask/MineTask/FarmTask/ChestOpenTask/SmeltTask` 抽象收拢判定逻辑到 `PerceptionBlockUtil`（行为不变）；游戏内实测数据正确
- [x] **AI 指令桥接（M4）**：`entity/ai/bridge/`——`MaidAIBridge`（JSON 指令 → 任务 → 回执）+ `MaidCommandResult`（协议回执 `{id,ok,state,step,result}`）+ `MaidTaskConfig`（任务配置持久化 `.cfg`）；`/maidai <json>` 指令；24 个指令与 `/maidtasks` 一致；自动化测试 9/9 通过（含错误回执/参数校验/相对坐标/persist）
- [x] **查询类指令（M6-a）**：`craft_check` —— 干跑配方预检（不消耗材料），逐项回报所需材料/现有/是否够（`CraftExecutor.check()`）；与 `status` 一样属于**不建任务、不改世界**的查询分支
- [x] **物品索引下发（M6-b）**：`ItemIndexPayload` —— 握手后一次性下发「当前语言物品名 → `minecraft:id`」（客户端 `I18n`，覆盖 mod 物品），桌宠在本地做中文/拼音模糊匹配（语音错字「稿子」→「镐子」）
- [x] **代码层自动化测试 `MaidAutoTest`**：服务端 tick 驱动读 `config/smartmaid/autotest.json`，自动召唤女仆 → 执行指令序列 → 回执写日志 → 标记完成（无 GUI 操作，解决 26.2 单机无 RCON 的自动化测试问题）
- [x] **桌宠联动（M5）双向链路**：
  - **下行 P0**：SmartMaid 按桌宠已认识的 schema 写 `<游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl`（20s 窗口聚合、按分钟轮转、清 2 分钟前文件）；事件 `damage` / `summary` 类型桌宠零改动即能渲染中文；额外 `maid{}` 状态块桌宠会忽略仅作联调
  - **上行 P1**：JDK 25 内置 `java.net.http.WebSocket`（零依赖），Client 连桌宠 `ws://127.0.0.1:21420`；5s 心跳、指数退避重连（1s→30s）、可选 token 握手；上行 `hello/perception/event/command_result/item_index/pong`，下行 `command→MaidAIBridge / speak→气泡 / animation / ping`
  - **配置**：`config/smartmaid/bridge.json`（启用开关、桌宠目录、WS URL/token、感知间隔、窗口 ticks）；首次运行自动生成
  - **桌宠侧**：`game/maid_link.py`（WS Server + 增量合并 + 中文映射 + 落盘 `logs/maid_link.log`）/ `mod_data.py` 增扫 `maid/` 子目录 / `pet/handlers/maid_handler.py`（1s 轮询 + `.maid_command.json` 调试注入口）
- [x] **感知增量 diff（M-P1）**：SmartMaid `PerceptionDiff.java`（首次自动全量，之后 `changed` 嵌套 + `removed` 点分路径；断线 reset 全量）+ 桌宠 `merge_snapshot` 对齐合并语义；**真机端到端全量 5761B → 增量 971B，降 83.1%**

### 已完成（2026-09-12：递归合成 + 测试基建 + 女仆生成全量）

- [x] **`CraftExecutor` 递归配方解析**：`craft()` 背包缺的直接材料可由其他配方逐层合成补足（原木→木板→木棍→铁镐）；`check()` 递归判定 + 每个 ingredient 附 `via_craft`/`craft_plan`；循环防护（展开链 visited + 深度 4，铁块↔铁锭不死递归）。**含三个 bug 修复**：
  - `ingredientNeeds` 按候选物品集合聚合（`placementInfo.ingredients()` 是每格展开列表，原 need 被低估，铁镐各显示 1）
  - `resolveItem/ensureMaterials` 全量扫描路径加 `produces()` 产物验证（否则会选中「材料可满足但产出不对」的配方，曾误报「铁锭可由金合欢木板合成」）
  - `buildInput/filledInput` 网格尺寸**硬编码 3×3 → 按配方真实宽高**（`ShapedRecipe.getWidth/getHeight`）建 `CraftingInput`：否则 2×2 工作台 / 1×2 木棍 / 1×1 原木→木板 全部 `matches` 失败 → `craft_check` 报 `found:false`（详见 DEVELOPMENT_ISSUES「问题 5」）
  - **真机验证**：`craft_check`/`craft` 铁镐（递归）、铁块拆铁锭 均 PASS；**工作台全递归链（原木→木板→工作台）真机通过**（2026-09-12）
- [x] **`MaidAutoTest` 升级为 tick 状态机**：任务型回执自动等待 ~2s 再取下一条（否则连续 craft 被 `aiBusy` 拒绝）；新增 `give` 预置背包（测试前塞材料）、`expect` 回执扁平字段断言（`result.craftable` 等，PASS/FAIL 写日志）。配置见 `config/smartmaid/autotest.json`（递归合成 3 场景）
- [x] **女仆生成全量感知推送**：`PerceptionModule.snapshotNow()`（强制所有通道采样，不受 READY_TICKS 限制）+ `MaidWsClient.onMaidTick` 首次注册时 `pushFullSnapshot`（reset diff + 立即发 `full=true`）。**效果**：女仆一出现桌宠立即有完整背包，避免「感知未就绪误报没有」
- [x] **`autotest.json` 递归合成 3 场景**：give 原木+铁锭 → `craft_check`/`craft` 铁镐（断言 craftable）；无铁块时铁锭不可合成（循环防护）；give 铁块 → 拆铁锭

**遗留问题（接手必读）**：
- **AutoTest c3 FAIL**：`craft_check` 铁锭期望 `craftable=false`，但女仆存档有 69 铁锭（技术上确实能合成铁块再拆）→ 实际 `craftable=true`。**测试场景依赖背包状态**，需先清背包（或换必然无解物品）再复测。
- **桌宠侧 AI 疯狂发无效 DSL**（`crash.log` 57 组 `MaidIntentParseError`：move 坐标错/fly 未知）——prompt 鼓励主动指挥但 AI 质量差，**需约束 prompt 或加强校验**。
- ~~**attack 无目标**~~（**已修复** 2026-09-12）：模组 `attack` 新增 `target`（实体类型 id）
  + `PerceptionBlockUtil.findNearestByType`，桌宠 NLU 把「猪」等生物名解析成 `minecraft:pig`。
  泛称「打怪」仍走「最近敌对生物」；附近无目标时如实回报「附近没有敌人」。

### 已完成（2026-09-15：寻路降级 —— 挖方块 + 搭路）

用户验收通过 ✅。给原版寻路加了"物理直线路径"兜底：**先寻路判定，代价超阈值/没路再切直线**。

**设计（动态可逆切换，用户拍板）**：
```
每次寻路 → 跑原版 A*
  ├─ 没路 / 路径到不了目标(隔墙) / 原版节点数 > 直线距离×3 → 切直线模式
  ├─ 否则 → 正常寻路
直线模式（逐 tick 移动+挖+搭）
  └─ 每 40 tick 重跑原版 A*：代价回落到阈值内 → 切回正常寻路
```

**实现**：
- `entity/ai/MaidStraightNav.java`（新）：直线降级导航器，每 tick 判定前方 1 格：
  1. 目标高于女仆（坑里/爬升）且前方 2 格墙 → **挖上方**（让头顶净空 → 1 格台阶跳 → 逐格升高爬出坑）
  2. 同高 2 格墙 → 挖墙脚（打通 2 格通道：挖空后继续挖上方）
  3. 头顶被挡 → 挖上方
  4. 前方 1 格台阶/沟壁 → **直接 `jumpControl.jump()`**（不依赖 MoveControl 苛刻的跳跃判定）
  5. 前方脚下深沟/水面（下方连续 2 格无支撑）→ 搭方块垫脚
  6. 1 格小落差/平地 → 直线走（MoveControl 下坡/平走）
  - 到达判定 = 水平 2 格 + 垂直 1.5 格 + 视线不被墙隔开
  - 无进展 200 tick / 障碍不可挖 / 背包无方块 → 放弃降级（60 tick 冷却防抖动）
- `entity/ai/MaidBlockBreaker.java`（新）：挖掘执行器状态机（走到旁 → 挥动+裂纹 → 按硬度耗时 → 破坏进背包），供直线降级与 MineTask 复用
- `entity/ai/MaidBlockPlacer.java`（新）：搭方块执行器状态机（自动换方块、自动选支撑面放置：先点下方 UP 可替换水，下方悬空点水平邻格侧面）；**bridgeMode 只消耗普通建材**（黑名单：矿物块/箱子/TNT/刷怪笼/基岩/黑曜石等，优先级：泥土/圆石/石头最优先，沙/砾石最后）
- `MaidGroundPathNavigation`：覆写 `createPath(BlockPos/Entity)`（原版 A* + `useStraight` 代价判定）+ `tick()`（直线模式接管）；阈值 `RATIO=3.0`（原版节点 > 直线距离×3 才切）、`MIN_STRAIGHT_NODES=8`
- `MaidMoveControl`：直线模式激活时跳过跨沟执行器 `tryHandle`（避免与降级逻辑打架）
- `MaidActions`：新增 `hasBlockItem/equipBlockFromBackpack/countBlockItems` + 搭路方块选择 `isBridgeBlock/hasBridgeBlock/equipBridgeBlockFromBackpack/countBridgeBlocks`
- `MineTask/BuildTask`：挖掘/放置流程重构为复用 `MaidBlockBreaker`/`MaidBlockPlacer`（删除内嵌状态机）
- `tools/run_e2e_test.py`：修复 GBK 打印崩溃（emoji 降级 ASCII）+ 新增 `--skip-token-check`（绕过 HMCL 旧 token 字段误报）

**踩坑（详见 DEVELOPMENT_ISSUES.md 2026-09-15）**：tick 覆写丢失导致直线从不驱动、MoveControl 跳跃判定条件苛刻导致卡台阶、到达判定只算水平导致一降级就结束、原版 A* 目标不可达返回"到最近可达点"部分路径（非 null）、搭方块无支撑面无限重试刷屏。

**遗留（后续可调）**：
- 阈值 `RATIO=3.0` / `STRAIGHT_EVAL_INTERVAL=40` / `MIN_STRAIGHT_NODES=8` 是硬编码常量，可按需配置
- 挖/搭交替时主手被占（placer 换方块后 breaker 徒手挖，慢但不卡）
- 一格深**水**沟（下方有水的浅沟）仍会搭（可接受：填浅水沟）

### 已完成（2026-09-16：挖掘工具自动切换 + 挖掘速度/动画对齐玩家）

- [x] **`MaidActions.equipBestToolFor`**：挖方块前自动换最优工具——遍历背包选对目标方块
  `isCorrectToolForDrops` 且 `getDestroySpeed(state)` 最高的工具换到主手（26.2 无 Tier 等级，
  用对目标方块的挖掘速度排序：钻石 8 > 铁 6 > 石 4 > 木 2，金/铜更快）；无正确工具保持徒手
  （与玩家一致：不掉落、不加速）。**每挖一个方块在 `MaidBlockBreaker.begin` 判定一次**（MineTask 与
  寻路降级挖墙共用），挖完石头换泥土自动换铲。
- [x] **挖掘速度与玩家一致**：`computeMiningTicks` 公式从 `hardness×20/speed` 修正为
  `hardness×100/speed`（原版 `Block.getDestroyProgress`：1 硬度徒手 = 5 秒 = 100 tick）。石镐挖石头 38t(1.9s)、
  铁镐挖钻石块 83t(4.2s)。
- [x] **挖掘挥动动画修复**（26.2 特有）：`updateSwingTime` 只在 `Monster`/`Player` 子类 aiStep 被调用，
  女仆（`Animal→Mob`）挥动动画不推进 → `SmartMaidEntity` 客户端 aiStep 手动复制原版推进逻辑
  （`tickSwingAnim`，反射取 private `getCurrentSwingDuration`）。`MaidBlockBreaker` 挥动改每 5 tick 一次完整动画。
- [x] **诊断日志**：`equipBestTool`（换工具结果/无工具徒手）、`Render 客户端主手`（客户端渲染层实体主手，每 5s）。
- [x] 部署验证：`diamond_block` 在 26.2 属 `needs_iron_tool`（铁镐即可掉落），非旧版常识的 needs_diamond_tool。

**踩坑**（详见 DEVELOPMENT_ISSUES.md 2026-09-16 节）：**改了代码只 compileJava 没 build 部署，用户一直测旧 jar**，
对着正常的同步链路白排查几小时——排查"改了没效果"先对比 `build/libs` 与 mods 的 jar 时间戳/搜 jar 内字符串。

### 已完成（2026-09-17：女仆战斗系统 C0–C5）

规则驱动的自主战斗（`entity/ai/combat/`），优先级 **L0 安全层 > 战斗 > 其他 AI 任务 > 常规 Goal**。
设计文档见 `DEVELOPMENT_COMBAT.md`。

- **C0 基础**：`SmartMaidEntity.isWithinMeleeAttackRange` 覆写为 **3.2 格**（原版默认 3.0）；补 `ATTACK_SPEED` 属性；
  `MaidWeaponSelector` 用 `ItemStack.forEachModifier` 解析「单次伤害 × 攻击速度」选武器；
  `MaidTargetFilter`（Monster + 排除表 末影人/僵尸猪灵 + 友军**仅玩家**）；`canAttack`/`doHurtTarget` 硬拦玩家；
  `MaidArrow`（`canHitEntity` 拒绝玩家；实体类型仍是原版 `arrow`，零注册）
- **C1 战斗 Goal `MaidCombatGoal`**（替换并删除 `MaidMeleeAttackGoal`）：近战判定 3.0 / 可攻击 3.2、按武器攻速出手；
  `MaidCombatMovement` 走位用原版 `MoveControl.strafe`（STRAFE 分支不改 `yRot`）→ **面向敌人后退**、**背对跳上 1 格**、
  **侧移绕行**（两侧都堵时交替尝试）；战斗期禁用 P10 直线降级但保留跳跃执行器；`MaidTaskManager` 战斗期拒绝/取消任务
- **C2 饱食度**：`entity/MaidFoodData`（仿玩家 `FoodData`；原版字段 private 且 `tick` 只收 `ServerPlayer`，只能自实现）；
  `EatTask` + `/maidtasks eat [item]` + `/maidai eat`；`completeUsingItem` 补饱食（原版只给玩家加）；战斗中 <50% 血优先进食
- **C3 远程**：`MaidRangedSkill`（只比 `POWER` 附魔选弓、`getProjectile` 覆写找箭、自写蓄力/发射、kite）；
  `BowItem.releaseUsing` 是 Player 专属不能复用
- **C4 盾牌**：`MaidThreatDetector`（箭矢外推将命中 / 苦力怕膨胀 >0.6）+ `MaidShieldSkill`（盾换副手 40 → `startUsingItem(OFFHAND)`）
- **C5**：`SmartMaidRenderer.getArmPose` 支持 `BOW_AND_ARROW`/`BLOCK` 等；感知 `self.status.combat`
- **真机反馈修复**：目标死亡后仍保持拉满弓（`stop()` 需释放使用状态）；墙角进食死锁（超时原地进食）；
  后退慢（STRAFE 的 `zza` 取原始 `forward`，比例值会按比例减速 → 后退必须满幅）；走位一直绕圈（默认 `side=0`，仅受阻才侧移）
- **桌宠侧同步**：新增 `eat` 指令 / 意图 + 重训 NLU（27 意图，val_acc 0.9921，人工集 89.9%，闲聊误激活 0）

> ⚠️ **鱼竿 spike 结论**：`FishingHook.tick()` 对非玩家 owner 首 tick 就 `discard()`；
> `FishingRodItem.use(Level, Player, ...)` 是 Player 专属 → **原版鱼竿对女仆不可用，暂不做**（用户拍板）。

### 已完成（2026-09-17：坐姿修正 —— 落地 + 坐下不动）

用户反馈"坐下浮空 / 坐着还能走"，两条都修掉了（细节与反编译依据见 `DEVELOPMENT_ISSUES.md` 2026-09-17 二次节）。

- **落地**：`SmartMaidEntity.snapToSupportForSit()` —— 坐下瞬间把 y 吸附到脚下最近的碰撞顶面
  （地面 / **半砖 0.5** / 台阶 1.0 / 地毯），向下最多找 3 格，找不到就不动（交重力）
- **坐在地上的观感**：`SmartMaidRenderer#setupRotations` 坐下时整体下沉 **0.75 格**（= 玩家模型髋高 12px）；
  与此同时**下沉必须写在 `setupRotations`**（原版 `submit` 里 `scale(-1,-1,1)` 翻转之前，Y 才是世界的"上"）；
  腿摆平到 -π/2（原版骑乘姿势有 9° 下倾会插进地面）；名字标签同步下沉；`isPassenger` 不再覆盖真实骑乘
- **坐下不动（逐层拦）**：`setOrderedToSit` 收敛为唯一入口（姿态+落地+清目标+锁定）；
  `applySitLock()` 每 tick 清导航/MoveControl 输入/水平速度（保留 y 给重力）；
  散步 Goal 的 `canUse`+`canContinueToUse` 加坐姿判断；导航 `tick()`/两个 `createPath()` 与
  直线降级 `MaidStraightNav`、跳跃执行器 `MaidActionExecutor` 全部坐下即让路；传送兜底坐下不生效
- **顺带**：`Render 客户端主手` 诊断日志改为按 tick 去重（原来是渲染帧级输出，4 分钟刷 500+ 行，
  违反"每帧高频日志走 `verbose()`"规范）



**P0 战斗系统真机回归收尾**：全流程（近战/走位/背对跳/远程/盾/进食/任务抢占）；日志抓 `SmartMaid-Debug` 的 `Combat` 段（`MaidDebug.VERBOSE` 可开深排查）。
> 2026-09-19 已推进：盾牌格挡真机验证通过（去前摇后 14 支箭全挡下），近战死区修正为 2.7~3.2。剩余：走位/背对跳/远程/进食/任务抢占的完整流程回归 + 盾牌"真漏箭"精确区分（可打印 `amount - applyItemBlocking(...)`，挡下为 0）。
**P0 鱼竿**：原版对女仆不可用（spike 结论见上），是否自研 `MaidFishingHook` 待定。
**P3 跳跃系统完整实测标定**：跨 3 沟、跨沟上 1 格、下跳 1~3 格、对角跨沟；日志 `Jump start/fired/landed` 取真实落点。
**P4 发布前收尾**：`/summonmaid` `/maidtasks` `/maidai` 加权限检查；`MaidDebug.ENABLED=false`（周期性高频日志已收进 `verbose()`，见开发规范）。
**P5 基岩版 Q 版模型**：参考 TouhouLittleMaid 的 `simplebedrockmodel`，需 `tlm_custom_pack` 资源（不入库，从 zip 解压），**单独立项**。

### 已知 bug（已修复 ✅）

**`damage` 事件 `target:"?"` 攻击者解析遗漏**（2026-09-10 B 方案落地）：
- `SmartMaidEntity` 覆写 26.2 的 `hurtServer(ServerLevel, DamageSource, float)`，最早阶段从 `DamageSource.getEntity()`/`getDirectEntity()` 缓存攻击者 → `getRecentAttacker()`（100 tick 新鲜度）。
- `PerceptionModule.attackerLabel()` 类型映射表：Player→玩家名 / Projectile→射手 / 其他→`minecraft:<短名>` / 无→`unknown`。
- `MaidTelemetryWriter`：`target` 默认 `unknown`，未知时重要性降 NORMAL。
- 效果：真机 `{"target":"pillager"}` 替代 `{"target":"?"}`。详见 `DEVELOPMENT_ISSUES.md` 2026-09-10 §问题 5 修复记录。

### 开发规范（用户明确要求，务必遵守）
- **以后每加一个新功能，顺手加上 debug 日志**（`MaidDebug.log`），方便出问题时**直接读 `logs/latest.log` 定位**，不要反复让玩家跑游戏贴日志。
- **日志分层（重要，小模组不污染日志）**：周期性/每帧高频日志（感知快照、装备状态、Monitor 状态+地形、渲染 extractRenderState/setupAnim、挖矿/烧炼进度）**一律走 `MaidDebug.verbose()` 门控（默认关）**，需要深排查时把 `MaidDebug.VERBOSE` 改 true；仅事件性日志（任务启停、WS、跳跃、transfer 等"有事才打"）默认输出。感知快照全量 5-6KB/条，曾导致数百小时游戏累积数 GB 的 `latest.log`。
- **日志编码坑**：Windows 中文系统下 MC 的 `latest.log` 是 **GBK 编码**，用 Python 分析必须 `gbk` 解码，否则中文日志搜不到、误判"没生效"。
- 排查渲染问题先看 `extractRenderState` / `setupAnim` 两处日志；服务端逻辑看 Server thread，客户端看 Render thread。
- **异步回调线程铁律**：WebSocket 回调在 `HttpClient` 线程池，运行 `MaidAIBridge` 任何触碰世界状态前**必须 `server.execute(...)` 切回服务端线程**；出站统一走 OUTBOX 只由服务端 tick 发送。

## 三、工程结构（`SmartMaid/`）

```
src/main/java/com/oyxdsg/smartmaid/
├── SmartMaid.java                主类：注册实体 + 命令入口 + MaidAutoTest 挂接
├── init/ModEntities.java         实体类型注册 + FabricDefaultAttributeRegistry 属性注册
├── command/SummonMaidCommand.java  /summonmaid
├── command/MaidTaskCommand.java   /maidtasks AI 任务指令（全部指令入口）
├── command/MaidPerceptionCommand.java  ★/maidperception 感知快照调试
├── command/MaidAICommand.java     ★/maidai <json> AI 指令调试（走 MaidAIBridge）
├── entity/
│   ├── SmartMaidEntity.java       女仆实体；MOVEMENT_SPEED=0.16；aiStep 驱动执行器 + ContainerUser（开箱动画）+ perceptionModule + combatGoal
│   ├── MaidFoodData.java          ★饱食度（仿玩家 FoodData；仅内部状态：进食/回血/饥饿）
│   └── ai/
│       ├── MaidFollowGoal.java    跟随主人（P3 规则）
│       ├── MaidMoveControl.java   ★原版 MoveControl + setZza(1) + 跨沟检测交给执行器
│       ├── MaidActionExecutor.java ★跳跃动作执行器：查表 → 定位起跳点 → 转身 → 起跳施速 → 空中维持
│       ├── MaidJumpTable.java     ★离线精确解表（tools/gen_jump_table.js 生成，勿手改）
│       ├── JumpPhysics.java       ★物理常量 + 前向轨迹模拟（prismarine-physics 公式）
│       ├── MaidGroundPathNavigation.java  注入 MaidWalkNodeEvaluator
│       ├── MaidWalkNodeEvaluator.java     ★寻路放宽上坡/下坡 + 生成跨沟邻居（支持对岸高 1 格）
│       ├── MaidStraightNav.java           ★寻路降级导航器（原版失败/绕远 → 直线移动 + 挖/搭，动态切回）
│       ├── MaidBlockBreaker.java          ★挖掘执行器状态机（裂纹耗时破坏，MineTask 复用）
│       ├── MaidBlockPlacer.java           ★搭方块执行器状态机（自动选支撑面，bridgeMode 只耗普通建材）
│       ├── MaidDebug.java         调试日志开关（开发默认开启）
│       ├── MaidMonitor.java       移动调试监测
│       ├── MaidActions.java       ★能力层：移动/背包/方块/攻击/挖掘进度原子动作
│       ├── slot/
│       │   ├── ItemSlot.java      ★槽位抽象（canAccept/isSwapAllowed/insert 契约）
│       │   └── Slots.java         ★解析 + MaidSlot/WorldSlot/ContainerSlot
│       ├── craft/CraftExecutor.java ★制作：反查配方 + 借位合成（无需工作台）
│       ├── maidtask/              ★AI 任务系统
│       │   ├── MaidAITask.java    任务抽象
│       │   ├── MaidTaskManager.java 调度器（aiBusy 接管行为决策；战斗期取消任务）
│       │   └── AttackTask / GuardTask / FeedTask / EatTask / MineTask / FarmTask
│       │       BuildTask / CollectTask / CraftTask / SmeltTask
│       │       MoveToTask / TransferTask / ChestOpenTask / OneShotTask
│       ├── combat/                ★女仆战斗系统（C0–C5，2026-09-17）
│       │   ├── MaidCombatGoal.java     战斗状态机（盾>吃>远程>近战；战斗高于任务；全流程 debug 日志）
│       │   ├── MaidCombatMovement.java 走位：面向敌人后退 / 侧移绕行 / 背对跳
│       │   ├── MaidTargetFilter.java   敌对排除表 + 友军仅玩家
│       │   ├── MaidWeaponSelector.java 近战 DPS 评分 / 弓 POWER 选择
│       │   ├── MaidRangedSkill.java    远程自写发射（复制 Player 专属逻辑）
│       │   ├── MaidThreatDetector.java 箭矢/苦力怕威胁检测
│       │   ├── MaidShieldSkill.java    举盾格挡
│       │   └── MaidArrow.java          不伤玩家的箭
│       ├── perception/            ★感知模块（M-P0）：AI"玩家视角"快照
│       │   ├── MaidPerception.java  快照模型（Gson，顶层 self/inventory/owner/nearby/blocks/env/events）
│       │   ├── PerceptionModule.java 分层采样调度 + 事件队列（hurt/task/敌人/水/岩浆/火）
│       │   ├── PerceptionDiff.java ★M-P1 增量 diff（首次全量，之后 changed 嵌套 + removed 点分路径）
│       │   ├── Sense.java          感知通道接口
│       │   ├── PerceptionUtil.java 坐标/物品摘要/效果/方块分类工具
│       │   ├── PerceptionBlockUtil.java ★从各 Task 收拢的判定（矿石/作物/容器/熔炉/敌对/危险）
│       │   ├── PerceptionJson.java M5 WebSocket 序列化入口（snapshot/events）
│       │   └── sense/              SelfSense / InventorySense / OwnerSense / EntitySense / BlockSense / EnvironmentSense
│       └── bridge/                ★AI 指令桥接 + M5 桌宠联动
│           ├── MaidAIBridge.java   JSON 指令 → 任务 → 回执（24 指令）
│           ├── MaidCommandResult.java  协议回执模型
│           ├── MaidTaskConfig.java 任务配置持久化（.cfg）
│           ├── MaidAutoTest.java   代码层自动化测试（读 autotest.json 执行指令序列）
│           ├── BridgeConfig.java   ★M5 集中配置（遥测 + WS + 感知间隔；首次自动生成）
│           ├── MaidTelemetryWriter.java ★M5-a 感知下行：事件→窗口 jsonl（20s/按分钟/清 2 分钟前）
│           ├── MaidWsClient.java   ★M5-b 指令上行：JDK WebSocket Client（心跳 5s + 指数退避 + token 握手）
│           └── ItemIndexPayload.java ★M6-b 物品索引下发（当前语言物品名→id，供桌宠 NLU 匹配）
├── data/MaidDataManager.java     本地持久化
├── data/MaidSettings.java        ★玩家绑定设置（JSON：友军/跟随/护主/属性/掉落）
├── client/                       渲染 + GUI + 对话
│   ├── gui/MaidControlScreen.java  ★木质风设置菜单（自绘暖棕木纹贴图 + 原版控件 + ContainerData 回显）
│   ├── gui/WoodButton.java         木质按钮（宽/窄两态贴图，文字走 extractDefaultLabel）
│   ├── chat/MaidChatClient.java    ★客户端「女仆对话模式」状态（/maidchat 开关）
│   ├── renderer/                 渲染器/模型/状态（+ 多行气泡 submitNameDisplay）
│   └── animation/MaidAnimManager.java  ★动作动画：加载 Emotecraft JSON → PAL HumanoidAnimationController → setupAnim 应用骨骼
└── command/MaidAnimCommand.java  /maidanim 播放动作（开发校验）
src/main/resources/
├── fabric.mod.json + 皮肤贴图
├── assets/smartmaid/textures/gui/maid_*.png  ★木质 GUI 贴图（maid_panel / maid_button / hover / 窄按钮，程序化生成）
└── assets/smartmaid/emotes/*.json  ★Emotecraft 动作（11 个：waving/clap/point/palm/here/crying/backflip/twerk/kazotsky_kick/roblox_potion_dance/club_penguin_dance）
libs/                            ★本地依赖（Player Animation Library + mocha，jar-in-jar 打包）
tools/
├── maid_monitor.ps1              资源/日志监测
├── maid_jump_sim.js              ★速度/落点/朝向模拟（prismarine 公式，Node 无依赖）
├── gen_jump_table.js             ★离线生成 MaidJumpTable.java
├── launch_game.py                ★从 HMCL 日志提取启动命令 + 补 token + 加 quickPlay
└── run_e2e_test.py               ★一键端到端：起桌宠 → 起游戏 → 轮询 AutoTest/遥测/WS → 出报告
                                  含 --check-token（hmcl.json accessToken 过期早退）
```

## 动作动画架构（路线 B：依赖 PAL 库）

- **依赖**：`libs/player_animation_library-1.2.6.jar`（Player Animation Library，26.2）+ 内嵌 mocha；build.gradle `implementation + include` 打包成 jar-in-jar，游戏无需单独装 PAL
- **动作来源**：Emotecraft 动作 JSON（`assets/smartmaid/emotes/`），`UniversalAnimLoader.loadAnimations` 解析（自动把 `rightArm` 归一化为 `right_arm`）
- **播放**：`MaidAnimManager.play(maid, id)`（客户端触发，服务端 `/maidanim` 设 `DATA_DEBUG_ANIM` 同步）
- **应用**：`SmartMaidModel.setupAnim` → `tickOnce(tickDelta)` 每帧推进 → 6 骨骼 `apply` → `RenderUtil.translatePartToBone` 写 ModelPart
- **关键**：用 PAL 的 `HumanoidAnimationController`（不依赖 Avatar），女仆（TamableAnimal）可直接使用

## 四、关键实现细节

### 26.2 速度机制（反编译 + prismarine-physics 确认）
- `Attributes.MOVEMENT_SPEED` 注册默认 **0.7**，玩家实例 override **0.1**。
- 地面（friction=0.6）：`getFrictionInfluencedSpeed = getSpeed()` = `speedModifier × MOVEMENT_SPEED`；稳定位移速度 ≈ **2.2 × 属性**。
- **26.2 `Mob.setSpeed` 会自动 `setZza(speed)`**——但 `moveRelative` 的 `getInputVector` 会再 `scale(speed)`，若 zza=speed 则加速度 = speed²（蜗牛）。**必须显式 `setZza(1.0F)`**。
- 空中：`getFlyingSpeed()` 固定 0.02（Mob），靠起跳惯性 + 空中持续输入（`tickJump` 每 tick `setZza(2.0F)` 维持 +0.02）。

### 跳跃物理（JumpPhysics，prismarine-physics 公式）
- 垂直顺序（关键）：**位移先于重力** `y += vy; vy = (vy-0.08)×0.98`；vy0=0.42 最大抬升 ≈1.25 格、飞行 ≈12 tick。
- 起跳 takeoff **固定**：走 0.30 / 跑 0.55 blocks/tick（离线标定，覆盖全部场景，不需助跑）。
- 起跳瞬间 `setDeltaMovement(前向 × takeoff)` 直接施加速度（避免"走到起跳点停下速度丢失"）。
- 朝向：`yaw = atan2(-tx, tz)`（MC 前向 (-sin,cos) 指向落点）。

### 跳跃执行流程（MaidActionExecutor）
1. `MaidMoveControl.tick` 检测前方障碍（中间无支撑的沟 / ≤1 格高实心障碍，`isJumpable`）且水平 2~4 格 → 交给执行器。
2. 查 `MaidJumpTable.get(dist, dy)` 拿 `{sprint, back}`（无解则放弃）。
3. 起跳点 = 当前格中心朝落点偏移 `(0.4 - back)`；MoveControl 自驱动走到起跳点。
4. 转身对准落点 → `jumpFromGround()` → 施加 takeoff 速度 → 空中每 tick 保持前进输入。
5. 落地（onGround）→ 恢复原版移动。

### 跨沟寻路（MaidWalkNodeEvaluator）
- `isNeighborValid` 放宽上坡 +1.5 / 下坡 -3。
- `getNeighbors` 覆写：额外生成"跨沟邻居"——沿 4 主轴 2~4 格、中间各格无支撑、对岸可站立（**支持对岸高 1 格高台**），每方向取最短。

### 离线解表（MaidJumpTable）
- 场景：dist∈{2,3,4} × dy∈{-3..+1} 共 15 个，全部有解。
- 解 `{sprint, back}`：走跳 0.30 / 跑跳 0.55；back 后撤使落点落入目标格（防跳过头）。
- 表内容（0.16 速度）：跨 1 沟走跳 back1.25 / 跨 2 沟走跳 back0.25 / 跨 3 沟跑跳 back1.25；上跳（dy+1）back0。

### 数据持久化
- 每 100 tick 保存装备到 `config/smartmaid/maids/<UUID>.dat`；召唤时恢复。
- AI 任务配置（`persist:true` 的指令参数）另存 `config/smartmaid/maids/<UUID>.cfg`（`MaidTaskConfig`，JSON，与背包存档分离）。

## 五、已知问题 / 待调优

1. **跳跃落点精度**：解表基于 prismarine 公式，真实落点可能有偏差（空中输入量、摩擦细节）。用日志 `Jump start/fired/landed` 校准。
2. **对角跨沟**：代码支持（方向对角、dist=round），未完整实测。
3. **下跳**（跨沟下 1~3 格）：解表有解，未实测；takeoff 可能偏大导致落点远。
4. **移动速度**：`MOVEMENT_SPEED=0.16` 行走偏快（≈7 m/s），如需"和玩家一致"可改回 0.1（但跨 3 沟上 1 格能力会下降）。
5. **命令无权限限制**：`/summonmaid` 正式版需加权限检查。
6. **调试输出默认开启**：`MaidDebug.ENABLED=true`，发布前改 false。
7. **寻路节点 y 语义**：`findAcceptedNode` 对空气格会下落到地面格，跨沟/高台节点的 y 与 feet 格可能差 1，已在实测中覆盖（跨 2 沟上 1 格正常）。
8. **26.2 单机（集成服务器）不支持 RCON**：自动化测试不能走 RCON（RCON 仅专用服务器有），用 `MaidAutoTest`（服务端 tick + `autotest.json` 配置驱动）实现代码层自动化。
9. **MaidAutoTest 批量执行丢中间事件**：同一 tick 连发多条指令时任务状态快速切换，感知事件队列采样不到中间变化（真实 AI 稀疏指令无影响；验证 hurt/发现敌人事件需真实场景）。
10. ~~**`damage` 事件 `target:"?"` 攻击者解析遗漏**~~（真机 e2e 实测发现，**已修复**）：`SmartMaidEntity.hurtServer` 缓存攻击者 + `attackerLabel()` 类型映射表，`target` 输出短名/玩家名，未知写 `unknown`。见上方 §已知 bug。
11. **HMCL `--quickPlaySingleplayer` 依赖有效 accessToken**：过期 token 让游戏拒绝进入 quickPlay 路径，日志停 `Setting user` 后无 `Preparing spawn area` / `Loading level`。详见 `DEVELOPMENT_ISSUES.md` 问题 9。
12. **首发感知窗口写盘太迟**：原 400 tick（20s）才能落盘，自动化测试窗口短时无事件输出。→ `MaidTelemetryWriter` 首窗 100 tick 强制 flush，后续按 400 tick 落。
13. **`unknown msg type: pong`**：WS 协议 pong 未识别 → 加 case，桌宠回带 ping 的 `t`（毫秒时钟对齐）。
14. **MaidMonitor 刷屏**：原每 tick 刷地形图淹没关键日志 → 改为每 100 tick + `verbose()` 门控默认关。

### 5.1 集成方必须知道的「能力语义边界」（2026-09-11 对齐时梳理）

这些不是 bug，是**模组接口的语义**；桌宠侧（或任何调用方）若按直觉传参就会失败：

| 接口 | 语义边界 | 调用方该怎么做 |
|---|---|---|
| `transfer` | `from`/`to` **都是必填** | 只说目标时，得自己先查「物品在哪个格」再拼 `inv:<n>` |
| `equip` | **只能装备到主手**，不接受目标槽位 | 要穿戴到头/胸/腿/脚 → 用 `transfer(from=inv:<格>, to=head)` |
| `drop` | **只丢主手**，不接受 `item` 参数；丢出的物品会落在**主人身边**（无主人则自己脚下），女仆 5 秒内不拾取 | 要丢指定物品 → `transfer(from=inv:<格>, to=world:<主人坐标>)` |
| `place` / `use` | 用**主手**物品，不接受 `item`/`block` 参数 | 先确保主手拿对了东西 |
| `chestput` / `chesttake` | 要求容器**已打开**，且 `chestopen` 是**异步任务**（回执只说「已受理」） | 发完 `chestopen` 要**等任务真结束**再 put/take（轮询 `status`） |
| `attack` | 支持 `target`（实体类型 id）+ `range` | 「打这头猪」→ `attack(target=minecraft:pig)` 打最近该种生物（获取食物）；不传 `target` 则打最近敌对生物 |
| `collect` / `pickup` | **同一个任务**（`CollectTask`），只差默认半径（8 / 4） | 别指望它们语义不同 |
| 相对坐标 | `params.pos` 每项支持 `"~"`（**女仆脚下**为基准），不支持中文字符串 | 相对词（我脚下/这里）要自己换算成绝对坐标或 `~` |

> 桌宠侧已把这张表固化成 `nlu/mod_contract.py`（指令契约，锚点本仓库提交），
> 并由 `clamp()` 自动丢弃非法参数——上表里的坑不会再犯。

## 六、环境与命令

```bash
# JDK 25（必填，26.2 要求）
$env:JAVA_HOME="<你的 JDK 25 安装目录>"
gradlew.bat build        # 构建，产物 build/libs/smartmaid-0.1.0.jar

# 部署到游戏目录的 mods/（游戏目录用 tools/_paths.py 的解析规则，见 tools/local_paths.json）
copy build\libs\smartmaid-0.1.0.jar "<你的 .minecraft>\mods\"

# 本地模拟跳跃/解表（Node，无依赖）
node tools/maid_jump_sim.js      # 速度/落点/朝向模拟
node tools/gen_jump_table.js     # 重新生成 MaidJumpTable.java

# 代码层自动化测试（无需 GUI/RCON）
# 写 config/smartmaid/autotest.json（JSON 指令对象数组）→ 进游戏自动执行
# 结果在 logs/latest.log 搜 [SmartMaid-Debug] AutoTest；执行完配置自动改名 autotest.done.json

# 端到端联调（桌宠 + 游戏一起跑，最高效的全链路验证）
# 1) 先用 GUI 启动一次游戏（让 HMCL 刷新 accessToken）
# 2) python tools/run_e2e_test.py --quick-play "新的世界 (11)"
#    → 自动起桌宠 + 启动游戏 + 进世界 + 等 AutoTest + 遥测 + WS + 出报告 + 关游戏
#    → 脚本内置 --check-token：token 过期早退，不浪费一轮
```

**环境坑**：
- Gradle wrapper 用腾讯镜像；公共依赖走阿里云/腾讯 maven；Mojang 下载需代理（本机 Clash 127.0.0.1:7890，需手动开）。
- 反编译 26.2：`javap -p -c -classpath <loom-cache/minecraftMaven/.../minecraft-merged-*.jar> <类>`。
- 用户游戏环境：HMCL，主玩 **26.2 Fabric**（fabric-api 0.158.0+26.2、smartmaid 都在 `.minecraft/mods/`）。
- HMCL accessToken 过期：必须在 GUI 里用 `我是启动器.exe` 启动一次，让 HMCL 自动刷新 `hmcl.json`；之后 `--launch <version>` 才带有效凭证。CLI 无法自行刷新。
- `run_e2e_test.py` 启动游戏用 `subprocess.Popen`（`DETACHED_PROCESS` + `CREATE_NEW_PROCESS_GROUP`）独立进程组，避免被 bash 回收，**必须在同一前台 Python 调用里跑完**，不要拆成多个 bash 调用。

## 七、相关项目位置

| 项目 | 路径 | 用途 |
|---|---|---|
| 本模组 | 仓库根 `SmartMaid/` | 当前工程 |
| 车万女仆源码 | 仓库根 `TouhouLittleMaid/`（gitignore，不入库）<br>上游 <https://github.com/TartaricAcid/TouhouLittleMaid> | 模型/皮肤/实现参考 |
| 车万女仆模型包 | `TouhouLittleMaid\...\tlm_custom_pack\touhou_little_maid-1.0.0\` | 模型 json + 皮肤 png |
| 桌面宠物（DeskPet） | 同级克隆 <https://github.com/oyxdsg/Multimodal-AI-Companion>（`desktop-pet/`） | 联动对象 |
| 桌宠 MC 联动 mod | 同上仓库的 `deskpet-mod/` | 构建参考 |
| mineflayer-baritone | <https://github.com/PrismarineJS/mineflayer-baritone> | 已实测（能跑但问题多），含 prismarine-physics 权威物理库 |

## 八、下一步开发建议（桌宠联动已完成，此节留作历史记录）

参考项目设计方案（用户已审阅，**P0/P1/P2 已全部实现并通过真机验证**）：
- 传输：WebSocket `ws://127.0.0.1:21420`，模组为 Client，桌宠为 Server；心跳 5s，指数退避重连（已实现）
- 模组→桌宠：`perception`（0.5~1s 状态快照，含增量 diff）/ `event`（受伤/发现）/ `command_result`（已实现）
- 桌宠→模组：`command`（走 `MaidAIBridge.execute`，落 `MaidAIBridge.queue` 切回服务端线程）/ `speak`（气泡+语音）/ `animation`（已实现）
- 安全：连接握手 token 防劫持（已实现，留空则不校验）

---

**交接给下一位**：核心链路已跑通（召唤→跟随→移动决策→跳跃→安全→持久化→渲染可见）+ 感知模块（M-P0，AI"玩家视角"快照）+ AI 指令桥接（M4，JSON 指令协议 + 回执 + 任务配置持久化）+ 代码层自动测试（MaidAutoTest）+ **M5 桌宠双向联动（下行文件通道 + 上行 WebSocket）+ M-P1 感知增量 diff（真机降 83.1%）+ 一键端到端联调脚本（run_e2e_test.py）** + **寻路降级（原版失败/绕远 → 直线物理路径 + 挖方块/搭路，动态切回，2026-09-15 真机验收）** + **玩家绑定设置 + 木质女仆菜单 + 聊天栏对话 + 桌宠隐退（2026-09-19）** + **战斗走位死区 2.7~3.2 + 盾牌免前摇 + 女仆背包模型预览（2026-09-19 二次）** + **挖矿坐标可选 + 挖掘面向 + 对话事件静默 + 生物名中文 + 指令/TTS 剥离 + 女仆第一人称（2026-09-19 三次，详见 §二最新小节）**。

**剩余主线**：① 战斗系统真机回归收尾（近战/走位/背对跳/远程/盾/进食/任务抢占；2026-09-19 已修盾牌前摇、死区 2.7~3.2）；② 女仆对话/事件联动真机回归（挖矿无坐标、两条线静默、生物中文名、指令不落屏不朗读——用户体验待验证）；③ P3 跳跃实测标定；④ P4 发布收尾（权限 + `MaidDebug.ENABLED=false`）；⑤ 女仆菜单 UI 观感打磨。`damage` 事件 `target:"?"` bug 已修复（B 方案，2026-09-10）。

M5 接口契约详见 [`DESIGN_AI_INTERFACE.md`](./DESIGN_AI_INTERFACE.md) 第七节 WS 协议；近期踩坑细节见 [`DEVELOPMENT_ISSUES.md`](./DEVELOPMENT_ISSUES.md) 2026-09-10 节。
