# 承接单：游戏内女仆菜单（N1）+ 任务队列

> **给接手这个任务的人（或 AI）。** 这是一份**入口文档** —— 读完它你就知道要做什么、读哪些文档、
> 哪些决策已经定了不要再问、按什么顺序做、怎么验证。
> 编写日期：2026-09-30 · 状态：**✅ 已实现（2026-10-01，真机验收通过）**。
>
> **实现进度（2026-10-01）**
> - 队列后端 **Q1–Q9 全部落地**：双队列调度、优先级插队、战斗暂停回队首、去重互斥（`mutexKey`/三态/时间窗/`groupId`/来源优先级/guard 合并）、熔炉租约、`Resumable` 快照（Mine/Farm/Build/Smelt/Harvest）、持久化+重召自动继续、`queue op` 协议全集（含 `pauseCurrent`/`setLongTerm`/`stopGroup`/`script` 入队）。
> - 菜单 **N1**：WBS 绿+遮罩视觉基座、一级主菜单（3D 预览/装备概览/红心+鸡腿/5 入口/4 动作）、二级页（任务队列前端：增删/上移/整组停止/参数 stepper+坐标三态；设置 8 项；对话/动作占位）、背包入口跳转、S2C 状态实时刷新 + 服务端回执 toast。
> - 桌宠侧接口打通：模组 `MaidWsClient` 转发 `priority/group/...`；桌宠 `send_command`/`_send` 支持 owner 优先级与 group 展开（跨仓库改动）。
> - 测试：离线 JUnit 33 项全绿；真机 AutoTest `autotest.queue-full.json` **PASS=30/FAIL=0**；方案见 `tools/TEST_PLAN.md`。
> - **仍有未做**：`ScriptTask` 跨会话接续（显式声明不支持；会话内零成本接续）、菜单"我脚下"坐标态、文档/发布收尾。

---

## 一、一句话任务

给 SmartMaid（MC 26.2 Fabric 女仆模组）做两件互相咬合的事：

1. **把游戏内菜单从"9 行设置面板"改造成两级菜单**（`Shift+右键` 打开），视觉走**桌宠同款 WBS 色系 + 遮罩分层**；
2. **给女仆做任务队列**（长期/短期两个队列、优先级插队、打断降位、接续、持久化），菜单的任务页是它的前端。

第 2 件是**后端改造**（唯一动后端的部分），第 1 件是纯客户端 UI。**两件都还没开工。**

---

## 二、当前状态

| 项 | 状态 |
|---|---|
| 设计 | ✅ 定稿（两份文档，见 §三） |
| 代码 | ✅ **已实现**（2026-10-01 真机验收；队列 Q1–Q9 + 菜单 N1；见顶部实现进度） |
| 版本 | `0.1.3`（`main` 公开）；开发史在 `master` |
| 现有实现 | 新菜单 `MaidMenuScreen`（Shift+右键，纯 Screen）+ 双队列任务系统；旧 `MaidControlScreen` 保留但不再是入口 |

> ℹ️ 本文件写于**动工前**，§四 的「不变量 / 已拍板决策」仍是权威口径；
> 但 **§一 / §二 / §五 里"待做、还不存在"的表述已过期** —— 实际交付状态以
> `HANDOVER.md` §二（2026-10-01、2026-10-07 两节）与 `CHANGELOG.md` 为准。

---

## 三、阅读顺序（按这个顺序读，别跳）

| 序 | 文档 | 读它是为了 |
|---|---|---|
| 1 | **本文** | 任务范围、排程、铁律 |
| 2 | `DESIGN_MAID_MENU_N1.md` | 菜单 N1 怎么落地（文件清单、协议、动作映射、验收点、三处纠正） |
| 3 | `DESIGN_MAID_TASK_QUEUE.md` | 任务队列完整设计（现状证据、双队列、优先级、去重、接续、持久化） |
| 4 | `DESIGN_MAID_MENU.md` | 上游菜单设计（信息架构、N1~N4 分期、2026-09-17 拍板） |
| 5 | `DESIGN_AI_INTERFACE.md` | 24 条指令 + WS 协议（你要复用的既有协议） |
| 6 | `DESIGN_ATOMIC_PROTOCOL.md` | `script` 原子协议（队列项可以是 script，别重复实现） |
| 7 | `DEVELOPMENT_ISSUES.md` | **踩坑复盘（必读）** —— 至少读 2026-09-15/09-16/09-29 三节 |
| 8 | `HANDOVER.md` | 项目整体交接（已有功能全貌） |
| 9 | `DEVELOPMENT_COMBAT.md` | 战斗系统 —— **本次一个字都不改**，但要知道它的优先级 |

---

## 四、已拍板的决策（**不要重新讨论**）

### 4.1 不变量（绝对不能碰）

| # | 不变量 |
|---|---|
| 1 | **背包界面完全不动** —— `MaidInventoryMenu` / `MaidInventoryScreen` / `MaidArmorSlot` / 41 格布局 / `Shift+E` 入口，一律原样保留。不补玩家 36 格、不改布局、不改协议 |
| 2 | **战斗系统完全不动** —— 保留 `MaidCombatGoal` 的 Goal 层高优先级；战斗**不进队列**，只在面板顶部占一行显示 |
| 3 | **既有指令集语义不改** —— `/maidtasks`、`/maidai`、桌宠 WS、`script`、`MaidAutoTest` 的行为必须与改前**逐条一致** |
| 4 | **`MaidAIBridge.execute` 默认语义不改** —— 缺省仍是"即时执行（抢占）"。队列只在显式标记（`queue:true` / `priority` / 菜单 op）时生效 |
| 5 | 移动一律走原版体系；命令参数只用 MC 原生类型；跳跃用离线解表 |

### 4.2 设计决策（已定，见各文档）

菜单侧（`DESIGN_MAID_MENU_N1.md` §六）：
- **色系 = 桌宠 WBS 绿**（令牌取自 `desktop-pet/core/theme.py`）；**木质贴图全部作废删除**，UI 全自绘（纯色 + 遮罩分层，**不需要任何现成贴图**）
- 两级菜单，**不往下套娃**；背包入口只做"跳转现有背包屏"
- 队列的「停止这一项」与「清空队列」是**两个不同按钮**

队列侧（`DESIGN_MAID_TASK_QUEUE.md` §十五，**14 条结论汇总**）：
- **长期/短期是两个独立队列**；短期清空才执行长期
- 优先级：P0 安全层 / P1 战斗（都不进队列）/ **P2 主人实时交互 → 插短期队首** / P3 → 追加队尾
- **打断 = `PAUSED` + 降一位，绝不取消**；长期「设为当前」**可打断短期**；长期「停止」= **永久移除**
- 去重 = **`mutexKey`（动作 + 具体子目）** + 三态判定 + 60t 时间窗 + `groupId`；**互斥拒绝 + 回执**
- `guard` 合并**取最大 range**；**一个熔炉同一时间只烧一种矿 → `smelt` 取空闲熔炉**
- 接续 = **参数级落盘 + 状态回溯**；**重召自动继续**
- **接续先做 6 类**：`Mine` / `Harvest` / `Farm` / `Build` / `Smelt` / `Script`

---

## 五、建议排程（两条线交叉，理由在括号里）

> 原则：**先做风险最高的后端底座并立刻验零回归**（队列改造会碰到所有调用方）；
> 菜单的部分可以稍后并与之解耦（纯客户端，不碰后端）。

| 阶段 | 内容 | 为什么这个顺序 |
|---|---|---|
| **1** | 队列 **Q1**（双队列数据结构 + 状态机，可离线自测）→ **Q2**（`MaidTaskManager` 接入）→ **Q3 零回归硬门槛** | Q3 必须在其他改动**之前**验证，否则后面出问题分不清是谁引入的 |
| **2** | 菜单 **步骤 1**（视觉基座：`MaidTheme` + 遮罩层 + 3 张圆角底图）→ **1b**（一级屏）→ **1c**（`MaidSubScreen` + 占位页）→ **6**（背包入口接现有屏） | 纯客户端、可独立验证；先把"能打开、能返回、视觉对"落地 |
| **3** | 队列 **Q4**（优先级插队 + 长期队列操作）→ **Q5**（战斗打断 + 对象保留）→ **Q6**（去重互斥 + guard 合并 + 熔炉租约） | 这三步是用户感知最强的行为 |
| **4** | 菜单 **步骤 2**（新协议）→ **步骤 3**（状态读取）→ **步骤 4**（任务页 = 两队列前端） | 任务页依赖队列已可用 |
| **5** | 队列 **Q7**（`Resumable` 6 类）→ **Q8**（持久化 + 自动继续） | 最重的一块，放后面 |
| **6** | 菜单 **步骤 5**（存箱/预检）+ 队列 **Q9**（协议收尾） | 收尾 |

**每一步都必须**：`gradlew build -x test` → 备份旧 jar → 部署到 mods → **核对 jar 时间戳** → 真机。
（`DEVELOPMENT_ISSUES.md` 2026-09-16 教训：只 `compileJava` 会一直测旧 jar，白排查几小时。）

---

## 六、环境与工具链

```bash
# 0) 【环境坑】Bash 若报 dirname/ls 「command not found」（PATH 被清空，gradlew 起不来）先修：
export PATH="<PortableGit 目录>/usr/bin:<PortableGit 目录>/mingw64/bin:$PATH"

# 1) 构建（必须 JDK 25 —— MC 26.2 要求）
export JAVA_HOME="<你的 JDK 25 安装目录>"
cd SmartMaid && ./gradlew build -x test --console=plain

# 2) 部署（⚠️ 启动器开了版本隔离：26.3 的 mods 在 .minecraft/versions/26.3/mods/）
cp build/libs/smartmaid-0.1.3.jar "<你的游戏目录>/.minecraft/mods/"

# 2b) 静态回归总门（改完代码/升依赖后必跑）
python tools/verify_all.py --series 26.2

# 3) 自动化测试（无需 GUI/RCON）
#    写/复制 tools/autotest.modpack-compat.json → <game>/config/smartmaid/autotest.json
#    → 进游戏自动执行 → 搜 latest.log 的 "AutoTest"（走 LOGGER）→ 跑完自动改名 .done.json

# 4) 端到端联调（桌宠 + 游戏一起跑）
python tools/run_e2e_test.py --quick-play "新的世界 (11)"   # 必须同一前台调用跑完
```

**API 核实工具**（本任务会频繁用到 —— 菜单要用 26.2 的 `Screen`/`GuiGraphicsExtractor`/`AbstractScrollArea` 等）：

```bash
python tools/probe_api.py net.minecraft.client.gui.screens.Screen extract       # 查方法
python tools/probe_api.py --bytecode <类> <方法>                                # 看内部调用了谁
python tools/probe_api.py --find TabButton                                      # 按关键字搜类名
python tools/probe_api.py --fabric events-interaction                            # 展开 Fabric 嵌套模块
```
> 工具自动解析 javap / MC jar / Fabric jar 路径（环境变量 → `tools/local_paths.json` → 默认），
> **不要写死本机路径**。对应的 skill 是 **`mc-api-probe`**（建议开工前加载一次）。

**可直接用的相关 skill**：`mc-api-probe`（API 核实）、`github-clean-publish`（发布到公开分支）。

---

## 七、铁律（踩过的坑，别重犯）

| # | 铁律 | 来源 |
|---|---|---|
| 1 | **测试/诊断输出走 `SmartMaid.LOGGER`，绝不走 `MaidDebug`** —— 后者默认关，会让"跑过了"和"没跑"无法区分 | 2026-09-29 |
| 2 | **改完必须 build + 部署 + 核对 jar 时间戳** —— 只 `compileJava` 会真机测到旧 jar | 2026-09-16 |
| 3 | **`latest.log` 是 GBK**，分析必须 gbk 解码 | 长期 |
| 4 | **Java 源码无 BOM**，用 Write/Edit 写，别用 PowerShell `Set-Content` | 长期 |
| 5 | ⚠️ **同一条消息里对同一文件发两个 Edit，其中一个可能静默丢失** → 分消息串行发，或改完立刻 Read 复核 | 2026-09-29（多次） |
| 6 | ⚠️ **block tag ≠ item tag** —— 引用只存在于 block 命名空间的 tag 会让**整个 tag 加载失败** | 2026-09-29 |
| 7 | ⚠️ 女仆**跟随玩家移动** → 测试断言必须用**绝对坐标**（框架有 `{lx}/{ly}/{lz}`、`check:{"at":"lastRun"}`） | 2026-09-29 |
| 8 | ⚠️ **同一个判据若散落在"收集"和"消费"两处，改一处必须全文 grep 一遍** | 2026-09-29 |
| 9 | **26.2 渲染全部是 `extract*`**（`extractRenderState` / `extractBackground`），写成 `render()` 会**静默不生效** | 本次核实 |
| 10 | 底层"放弃"信号（如 `MaidStraightNav.recentlyGaveUp`）**必须被消费方主动检查**，否则会死磕不可达目标 | 2026-09-29 |
| 11 | HMCL access token 会过期 → 游戏卡主菜单、`--quickPlay` 不进世界；须用户在 GUI 登录一次 | 长期 |
| 12 | 提交/发布前：`master` 与 `main` 内容差异必须为 0；工具脚本不许写死本机路径 | 2026-09-29 |

---

## 八、本次设计里最容易做错的几个点

1. **`ticks` 必须每项归零** —— `MaidTaskManager.ticks` 现在是 manager 级计时器，队列推进时若忘记归零，
   第二项会继承前一项的计时**瞬间超时**。
2. **`isAiBusy()` 必须改成 `current != null || !short.isEmpty() || !long.isEmpty()`** ——
   否则换项那 1 tick 空档里跟随 Goal 会插进来（表现为"每换一项抖一下"）。
3. **`try/finally` 保证 `startNext()` 一定可达** —— 否则会出现"当前项没了、队列里还有一堆、女仆站着不动"。
4. **打断时"挂起对象、不销毁引用"** —— 这是"接着建房子"零成本的前提；
   现状的病根是 `forceStop()` 销毁对象 + 丢引用，**不是"不能接续"**。
5. **展开逻辑放上层**（桌宠 NLU / 菜单），服务端只按 `mutexKey` 精确判重 ——
   若服务端也自己展开"所有矿物"，会和上层重复展开。
6. **`Resumable` 不实现的任务要显式声明"不支持接续"**，恢复时按"重头来"并如实回执 —— 不假装能接着做。
7. **菜单是纯 `Screen` 后没有 `ContainerData`** —— 状态必须走新 S2C 包，别想着复用 `menu.getSetting`。

---

## 九、未验证 / 待办（如实列出）

- **命令权限**（单人 vs 专用服务器）—— 只能人工验，自动化覆盖不到。
- **FakePlayer 在第三方 mod 方块上的表现**、**内置 PAL 与视觉 mod 共存** —— 需按
  `docs/MOD_COMPATIBILITY_ANALYSIS.md` 的测试组实测。
- 战斗系统真机回归收尾（近战/走位/背对跳/远程/盾/进食/任务抢占）。
- P3 跳跃系统实测标定；P5 基岩版 Q 版模型（单独立项）。
- ⚠️ **桌宠侧是跨项目改动**（另一个仓库，非 git）：`priority:"owner"` 标记、展开成多项时的 `group`、
  消费去重拒绝回执（不重复播报）。承接时需与桌宠侧协调。
- 存档里 `tasks` 段的 `version` / `stateVersion` 兼容策略，实现时要落地（旧快照不硬读）。

---

## 十、一句话给承接人

**设计已经做完，坑也标好了，风险最高的是"改 `MaidTaskManager` 会波及所有调用方"——
所以第一步验证"老路径零回归"，再去动新功能。**
有拿不准的地方，`DESIGN_MAID_TASK_QUEUE.md` §十五 的 14 条结论 + 三条类推原则基本能覆盖。
