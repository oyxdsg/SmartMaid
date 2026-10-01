# 游戏内女仆菜单 · N1 实施方案

> 上游设计：`DESIGN_MAID_MENU.md`（信息架构 + N1~N4 分期 + 2026-09-17 拍板决策）。
> 本文只写 **N1 怎么落地**：文件级改动、协议、动作映射、验收点、以及对设计稿的**纠正**。
> 状态：**✅ 已实现（2026-10-01，真机验收通过）**（2026-09-30 编写，全部 API 已用 `tools/probe_api.py` 对 26.2 jar 核实）。
>
> **修订历史**
> - **v2（2026-09-30）**：界面由「一屏 5 标签 + 右侧竖排」改为**两级菜单**（一级入口列表 → 二级功能页）。起因：用户反馈一屏太挤。
> - **v3（2026-09-30）**：
>   1. **任务面板改为「有序任务队列」**——一行一个任务，第 1 行 = 正在执行，第 2 行 = 下一个，第 3 行 = 下下个，以此类推（用户指定）。
>      ⚠️ 这**不再是纯前端改造**：需要 `MaidTaskManager` 从"单任务"扩展为**任务队列**（§3.7），是本方案唯一的后端改动。
>   2. **背包完全不动**——入口只做"原样打开现有背包屏"，不补玩家 36 格、不做同窗内嵌、不改布局（用户明确要求）。
> - **v4（2026-09-30）**：新增 **§3.8 视觉规范（色系 + 遮罩 + 层次）**——对齐桌宠，取 `desktop-pet/core/theme.py` 里的
>   **WBS 令牌**（纯色分层 + 淡色遮罩、0 描边、绿调墨色文字），并核实 26.2 的半透明遮罩与**毛玻璃模糊**均可用。
>   起因：用户要求"色系、遮罩能不能做，就和桌宠那样做出层次感"。
> - **v5（2026-09-30）**：
>   1. **色系拍板 = 桌宠 WBS 绿**；⚠️ **木质贴图全部作废并删除**，UI 完全自绘（不需要任何现成贴图）。
>   2. 任务队列**另出深化设计**：`DESIGN_MAID_TASK_QUEUE.md`（现状无队列的逐行证据 + 队列设计）；
>      本节 §3.7 只保留结论。存箱由"两项入队"改为**一个队列项**（新增 `ChestStoreTask`）。
> - **v6（2026-09-30）**：任务队列按用户完整规格**重写为 v2**（`DESIGN_MAID_TASK_QUEUE.md`）——
>   优先级与插队（P2 = 主人实时交互，插到队首）、长期/短期任务（**长期任务区**常驻、临时队列空时回到它）、
>   **打断 = 降一位而非取消**、**接续 `Resumable`**（被打断的建筑怎么接着建）、
>   **任务状态持久化**（退出游戏也保存）。
>   → 本节 §3.2 的任务页据此加了**长期任务区**，并新增「暂停中 / 恢复」状态与「停止这一项 / 清空队列」两个按钮。
> - **v7（2026-09-30）**：队列设计升 **v3**（`DESIGN_MAID_TASK_QUEUE.md`）——
>   ①**长期/短期是两个独立队列**（短期全清空才跑长期），长期队列可 `设为当前`/`停止`/调位置；
>   ②新增**去重与互斥**（signature + 时间窗 + mutexGroup + 来源优先级，解决"主人与桌宠同时下发砍树"）；
>   ③接续升级为**参数级落盘 + 状态回溯**（存中间变量，`Resumable.saveState/restoreState`）；
>   ④**重召后自动继续**（已拍板）。
>   → 本节 §3.2 任务页改为**两块队列**（长期队列每项带 `设为当前`/`停止`/`↑`）。
> - **v8（2026-09-30）**：队列设计升 **v4** —— ⚠️ **推翻 v3 的粗粒度互斥组**，改为**按"动作 + 具体子目"判重**（`mutexKey`）。
>   起因：主人说"把身上的矿物都烧一下"会合法地产生**多个熔炼任务**（烧铁锭/烧金锭/烧铜锭），
>   v3 的同组策略会把它们全当重复拒掉。配套：三态判定（同键拒 / 泛化+精确=软提示 / 子目不同=接受）、
>   `groupId` 分组、归一化规则。→ 本节 §3.2 增加"同一 group 折叠成一组显示 + 整组停止"。
> - **v9（2026-09-30 · 设计定稿）**：**设计阶段到此结束** —— 队列设计的决策已**全部拍板**
>   （`DESIGN_MAID_TASK_QUEUE.md` §十五，14 条结论）。本文与队列设计文档**均未开工**；
>   实施顺序见两份文档各自的步骤表（本文 §四 N1 步骤 / 队列 §十二 Q1~Q9）。
> - **v10（2026-10-01 · 实施完成）**：本文 N1 全部落地（`MaidTheme`/`MaidMenuScreen`/`MaidSubScreen`/
>   `MaidTaskScreen`/`MaidAddTaskScreen`/`MaidSettingsScreen`/`MaidPlaceholderScreen` +
>   `MaidOpenMenuPayload`/`MaidMenuStatePayload`/`MaidMenuActionPayload`），队列侧 Q1~Q9 同步完成。
>   实施期纠正：视觉改回**直角**、预览框放大、生命/饱食复用原版 HUD sprite；设置页移除"护主模式"入口
>   （底层 `MaidSettings.protectMode` 保留），9 项→8 项以消除与底部提示的重叠。
>   测试：离线 JUnit 33 项 + 真机 AutoTest `PASS=30/FAIL=0`（`tools/TEST_PLAN.md`）。
>   **仍未做**：`ScriptTask` 跨会话接续、"我脚下"坐标态、文档与发布收尾。
>   **后续由承接人接手，入口是 [`HANDOVER_MENU_QUEUE.md`](./HANDOVER_MENU_QUEUE.md)。**

---

## 〇、结论先行

N1 的验收目标一句话：**把 Shift+右键 的「9 行设置面板」换成一个两级菜单——一级是入口列表，二级是功能页；任务页是一份有序队列（第 1 行进行中，往下依次是接下来要执行的）；背包入口原样打开现有背包界面，一个字节都不改。**

- 一级 `MaidMenuScreen` = **纯 `Screen`**（**不挂容器**）；二级页继承 `MaidSubScreen`，用 `Minecraft.setScreen` 切换。
- 二级里 **「任务」= 有序队列列表**（本方案唯一需要**改后端**的部分，见 §3.7）。
- **「背包」= 原样打开现有 `MaidInventoryScreen`，不做任何改动**（不补玩家格、不改布局、不改协议）。
- 「对话 / 设置 / 动作」在 N1 只做**占位页**。

**不变量（本方案绝不动）**
1. 背包界面 `MaidInventoryMenu` / `MaidInventoryScreen` / `MaidArmorSlot` / 41 格布局 / Shift+E 入口 —— **原样保留**。
2. 战斗 > 任务 > 常规 Goal 的优先级 —— 不变（队列是"任务层"内部的事）。
3. 指令集与 `MaidAIBridge` 的 JSON 协议 —— 只**新增**队列操作，不修改既有 24 指令语义。

---

## 一、现状盘点（代码级，已核实）

| 现状 | 位置 | 对 N1 的影响 |
|---|---|---|
| 菜单是 `AbstractContainerScreen<MaidControlMenu>`，268×280，blit `maid_panel.png`，9 行「设置名：值」点击循环 | `client/gui/MaidControlScreen.java` | 主入口**废弃重写**（类保留不删） |
| 菜单容器无槽位，靠 `ContainerData`（12 项）同步 sit/health/hasTarget + 9 项设置 | `gui/MaidControlMenu.java` | 纯 `Screen` 后**没有 ContainerData 可用** → 状态必须换通道（§3.3） |
| 动作包只有 `int action` 四个常量（TOGGLE_SIT / RECALL / CLEAR_TARGET / OPEN_INVENTORY） | `network/MaidCommandPayload.java` | 保留；菜单另加新包 |
| 服务端接收后 `findOwnerMaid(level, player)` → 执行 → 无回执 | `network/ModNetworking.java` | 需加**回执 S2C** |
| `MaidAIBridge.execute(SmartMaidEntity, String json) → MaidCommandResult` | `entity/ai/bridge/MaidAIBridge.java:72` | 入队/执行都复用它，**不新增指令解析逻辑** |
| ⚠️ `MaidTaskManager` **同一时刻只跑一个任务**（`aiBusy` 门控；战斗期 `tick()` 直接 `cancel()`） | `entity/ai/maidtask/MaidTaskManager.java` | **队列要在这里加**（§3.7）——本次唯一后端改动 |
| 背包页：`AbstractContainerScreen<MaidInventoryMenu>`，176×166，41 个女仆槽；左上角渲染女仆 3D 模型 | `gui/MaidInventoryMenu.java`、`client/gui/MaidInventoryScreen.java` | **完全不动**，入口原样打开 |
| 女仆实体 id 已通过 `DATA_MAID_ID` 同步，客户端可 `level.getEntity(id)` 拿实体 | 同上 | 3D 预览/装备概览可**零同步**实现 |
| Shift+右键 → `player.openMenu(new MaidControlMenuProvider(maid))` | `SmartMaidEntity.java:893-898` | 改为客户端打开 `MaidMenuScreen`（纯 Screen） |
| Shift+E 由客户端 KeyMapping 拦截，发 `ACTION_OPEN_INVENTORY` | `client/SmartMaidClient.java:59-71` | **保留**，与菜单里的背包入口并存 |
| 菜单注册 2 个：`maid_inventory` / `maid_control` | `init/ModMenus.java` | 两者**都保留注册** |
| ⚠️ 现有贴图 `maid_panel.png` + `maid_button(_hover/_s/_s_hover).png`（程序化生成，木质风+描边） | `resources/assets/smartmaid/textures/gui/` | **作废、从资源里删掉**（2026-09-30 拍板：色系改 WBS，UI 全自绘纯色+遮罩，不需要木纹贴图） |

### 26.2 API 核实结果（`probe_api.py`，JDK 25 javap）

| 结论 | 细节 |
|---|---|
| 控件**全部存在** | `TabButton`、`TabNavigationBar`、`ItemDisplayWidget`、`AbstractScrollArea`、`EditBox`、`MultiLineEditBox`、`StringWidget` |
| ⚠️ `TabButton` 是 **abstract**，需 `TabManager`+`Tab` 管线 | **N1 不引入**，两级切换用普通 `Screen` |
| ⚠️ 26.2 渲染**全部是 `extract*`**：`Screen#extractRenderState/extractBackground` | 新界面**不要写 `render()`**，否则静默不生效 |
| 绘制原语齐全 | `fill(int,int,int,int,int)`、`text(Font, Component, x,y,color)`、`blit(...)`、**`item(ItemStack, x, y)`**、`blitSprite(...)`（含九宫格） |
| ✅ **半透明遮罩可用** | `fill(..., ARGB int)` 的 alpha 生效（GUI 管线带混合）→ 暗遮罩 / 淡色 hover 遮罩都能做 |
| ✅ **毛玻璃模糊可用** | `GuiGraphicsExtractor.blurBeforeThisStratum()` + `Screen#extractBlurredBackground(...)` 都存在 → 可直接用 MC 自带菜单背景模糊（需在世界里打开） |
| ✅ **渐变可用** | `fillGradient(x1,y1,x2,y2,顶色,底色)` → 顶部/底部渐隐 |
| 装备概览 7 格可用 `extractor.item(stack, x, y)` 直接画 | 无需自绘 16×16 贴图 |

---

## 二、对设计稿的纠正

### 纠正 1（重要）：TLM 的 GUI 贴图**不是 MIT**

`DESIGN_MAID_MENU.md` §4.4 写「复用 TLM 的 GUI 贴图（`maid_gui_*`，MIT）」——**这句是错的**。

- 实测 `TouhouLittleMaid/readme.md:62-63`：**Code: MIT / Assets: CC BY-NC-SA 4.0**。
- `maid_gui_main/side/button/backpack/task.png` 都是 **CC BY-NC-SA 4.0（禁商用 + 相同方式共享）**，与现有女仆皮肤同档位；该目录已 gitignore，引入 = 把 NC 资产复制进主 jar。
- 后果：不改变"只进非商业整合包"的既有结论，但会**推翻任何"贴图是 MIT / 可自由分发"的表述**（`docs/MODPACK_COMPATIBILITY.md` 附录的 README 模板已因此过时）。

→ 见 §六问题 1，建议**自绘**。

### 纠正 2：**没有 `follow` 这条指令**

设计稿 §3.1 写「`跟随我` → `follow`（取消坐下）」。实际：

- `MaidAIBridge` 的 24 条指令里**没有 `follow`**；跟随由 `MaidFollowGoal` + 设置开关/距离自动进行。
- "跟随" = **取消坐下状态**（`setOrderedToSit(false)`），属现有 `MaidCommandPayload` 的**非指令动作**。

→ 一级四动作定为：**取消坐下（=跟随我）/ 坐下待命 / 召回 / 停止当前**。前三个走 `MaidCommandPayload`；"停止当前"停止的是**当前正在执行的那一项**（队列其余项保留，见 §3.7）。

### 纠正 3：**背包不在 N1 的改动范围内**

设计稿 §3.3 提过"背包页补玩家自己 36 格"（原文标注"随手定，可改"），**已作废**。

- 背包屏**原样保留**：41 格、176×166、不补玩家格、不改 `quickMoveStack`。
- 菜单里的「背包」入口 = `minecraft.setScreen(new MaidInventoryScreen(...))`，**只做跳转**。
- "给女仆穿装备" 维持现有流程（玩家丢出 → 女仆拾取），不通过菜单改。

### 纠正 4（新增范围声明）：**任务队列是后端新能力，不是纯前端改造**

用户要求任务面板按"第 1 行进行中、第 2 行下一个…"排序 —— 这隐含**女仆要能记住并依次执行多个任务**。
而现状是 `MaidTaskManager` **一次只跑一个任务**，且**战斗期直接 `cancel()` 掉当前任务**。
→ 必须新增队列（§3.7），并定义战斗与队列的关系（§六问题 2）。**这是本方案里唯一动后端的地方**，工期与风险都要按"改后端"估。

---

## 三、技术方案

### 3.1 类清单

**新增（客户端）**

```
client/gui/
├── MaidTheme.java               ★视觉令牌（WBS 色值 + 圆角 + 遮罩 alpha + fill 封装；§3.8）
├── MaidMenuScreen.java          ★一级主菜单：遮罩 + 入口列表 + 3D 预览 + 状态条 + 4 高频动作
├── MaidSubScreen.java           二级页基类（统一遮罩 + 标题栏 + 返回按钮 + 内容区 + 状态订阅）
├── MaidTaskScreen.java          ★二级：任务队列（有序行列表 + 添加/取消/重排）
├── MaidPlaceholderScreen.java   二级：对话 / 设置 / 动作占位页（N1 只保证可打开不崩）
├── widget/MaidEntryButton.java  一级入口按钮（图标 + 名称 + 副标题）
├── widget/MaidQueueRow.java     ★队列行（序号 + 任务名 + 状态 + 参数摘要 + 操作）
├── widget/MaidStatBar.java      生命 / 饱食条（fill + text）
└── widget/MaidParamPanel.java   参数面板（数字 stepper / 下拉 / 坐标三态；在**添加任务**时就地展开）
```

> **不引入原版 `TabNavigationBar` / `TabButton`**（`TabButton` 是 abstract 且需 `TabManager`+`Tab` 管线）；
> 两级导航用普通 `Screen` + `Minecraft.setScreen(...)`，`MaidSubScreen` 统一"返回一级"行为。

**新增（服务端）**

```
entity/ai/maidtask/MaidTaskQueue.java   ★任务队列（服务端；见 §3.7）
```

**新增（网络）**

```
network/
├── MaidMenuActionPayload.java   C2S：队列/指令操作（op + 可选 cmd/params/index）
└── MaidMenuStatePayload.java    S2C：状态 + 队列快照 + 回执
```

**修改**

| 文件 | 改动 |
|---|---|
| `entity/ai/maidtask/MaidTaskManager.java` | **加队列**：当前任务结束后自动取下一项；`cancel` 只作用于当前项（§3.7） |
| `network/ModNetworking.java` | 注册 2 个新包；接收后 `server.execute` → `findOwnerMaid` → 队列操作 / `MaidAIBridge.execute` → **回发**状态 |
| `entity/SmartMaidEntity.java:893` | Shift+右键 改为客户端打开 `MaidMenuScreen` |
| `client/SmartMaidClient.java` | 登记新屏；**Shift+E 的背包入口不动** |
| `gui/MaidInventoryMenu.java` / `client/gui/MaidInventoryScreen.java` | **不改动** |
| `gui/MaidControlMenu.java` / `client/gui/MaidControlScreen.java` | 保留不删（旧引用/旧存档安全），不再是主入口 |

### 3.2 界面结构

**一级 · 主菜单**（Shift+右键 打开）

```
┌──────────────────────────────────────────────────────────────┐
│                          女仆菜单                             │
├────────────────────────┬─────────────────────────────────────┤
│                        │  ▸ 任务        队列 4 项 · 挖矿进行中 │
│                        │  ▸ 背包        41 格 · 装备          │
│     3D 女仆预览         │  ▸ 对话        与女仆聊天            │
│     （拖动旋转）        │  ▸ 设置        跟随 · 护主 · 属性     │
│                        │  ▸ 动作        11 个表情             │
├────────────────────────┴─────────────────────────────────────┤
│ ♥18/20  食16/20   状态：执行任务   队列：4 项                  │ ← 常驻状态条
├──────────────────────────────────────────────────────────────┤
│    [跟随我]   [坐下待命]   [召回]   [停止当前]                │ ← 高频动作
└──────────────────────────────────────────────────────────────┘
```

- 左侧 3D 预览 + 名字（复用 `InventoryScreen.extractEntityInInventoryFollowsMouse`，与背包页同款读法）。
- 右侧 **5 个入口**，行高大、每行 = 图标 + 名称 + 一句副标题（副标题可显示实时摘要，如队列项数）。
- 底部**常驻状态条** + **4 个高频动作**（1 次点击可达）。
- 入口数上限 5；再多功能一律进二级，**不往一级塞**。
- **背包入口的行为**：`setScreen(new MaidInventoryScreen(...))`，进现有界面，**不改它任何东西**。

**二级 · 任务（有序队列）**——用户指定的形态

```
┌──────────────────────────────────────────────────────────────┐
│ [返回]            任务                         [＋ 添加任务]  │
├──────────────────────────────────────────────────────────────┤
│ 长期队列 · 无完成指标 · 短期清空后才执行 · 可调整            │
│ 1  挖矿（直到主人喊停）  排队中  [设为当前][停止][↑]          │
│ 2  护卫 半径 10          排队中  [设为当前][停止][↑]          │
├──────────────────────────────────────────────────────────────┤
│ 短期队列 · 优先执行 · 有完成指标 · 做完即移除                 │
│ 1 ▶ 挖矿 8 块    进行中  12 格/8 块           [停止这一项]    │ ← 第 1 行 = 正在执行
│ 2 ⏸ 砍树 8 根    暂停中  被插队 · 已打断 1 次  [恢复] [×]     │ ← 降一位，不是取消
│ 3   存入箱子     排队中  x=120 y=64 z=35          [×] [↑]     │
├──────────────────────────────────────────────────────────────┤
│ 短期 3 项 · 短期清空后自动回到长期队首 · 退出游戏也保留       │
└──────────────────────────────────────────────────────────────┘
```

- **一行一项，按执行顺序自上而下排列**：第 1 行 = 正在进行，第 2 行 = 下一个，第 3 行 = 下下个…
- 每行：`序号 | 状态标记 | 任务名 | 状态 | 参数摘要 | 操作`。
  - 第 1 行状态 = `进行中`（可带进度文本），操作 =「**停止这一项**」。
  - 被插队/被打断的行状态 = `暂停中`（附「已被打断 N 次」），操作 =「**恢复**」/ `×`。
  - 其余状态 = `排队中`，操作 = `×`（移出队列）/ `↑`（上移一位，调顺序）。
- **两个独立队列**（已拍板）：**长期队列**装无完成指标的任务（如"挖矿直到主人喊停"），常驻面板；
  **短期队列**装有完成指标的任务，做完即移除。
  **调度：短期队列非空 → 只跑短期；短期清空 → 跑长期队首。**
- **长期队列每项的操作**：`设为当前`（立刻取得执行权）/ `停止`（永久移除，**需二次确认**）/ `↑`（队列内调位置）。
- 超出可视区的部分用**行内滚动**（`AbstractScrollArea` 已核实存在）或翻页，不做缩排。
- **同一 `group` 的子项折叠成一组显示**：一条命令展开出的多项（例："把身上的矿物都烧一下" → 烧铁锭/烧金锭/烧铜锭 3 个子目）
  在面板上折叠成「烧所有矿物 ▸ 3 项」，支持展开/折叠与**整组停止**。
- 底部一行**汇总**：短期项数 +「短期清空后回到长期队首」+ 持久化提示。
- ⚠️ **「停止这一项」与「清空队列」必须做成两个不同按钮** —— `cancel` 的语义已变为"只停当前项、
  后面自动继续"，字面上看不出差别（`DESIGN_MAID_TASK_QUEUE.md` §十三 风险 9）。

**添加任务（就地展开，不推新屏 → 仍然只有两级）**

```
┌──────────────────────────────────────────────────────────────┐
│ [返回]            任务                         [＋ 添加任务]  │
├──────────────────────────────────────────────────────────────┤
│  分组：[战斗] 生产  物品  移动  收纳                          │
│   ┌────────┬────────┬────────┐                               │
│   │ 护卫   │ 攻击   │ 喂食   │                               │
│   ├────────┼────────┼────────┤                               │
│   │ 进食   │  (空)  │  (空)  │                               │
│   └────────┴────────┴────────┘                               │
│  ── 参数 ──                                                   │
│   半径  [ − ]  10  [ ＋ ]       目标  [最近敌对 ▾]            │
│                                    [取消]   [追加到队列]       │
└──────────────────────────────────────────────────────────────┘
```

- 点「＋ 添加任务」→ 队列列表**就地盖上一层选择面板**（分组 + 任务格 + 参数）→ 确认后**追加到队尾**并回到列表。
- 全程不出这一页 → **严格两级**。
- 「制作」在参数确认前先发 `craft_check`，缺料就地提示并不入队。

### 3.3 状态同步

纯 `Screen` 没有 `ContainerData`。按「能本地读就别同步」分层：

| 数据 | 来源 | 通道 |
|---|---|---|
| 名字 / 生命 / 坐姿 / 位置 / 装备 / 手持 | 客户端 `level.getEntity(maidId)` | **本地读，零同步**（判空：女仆可能未加载） |
| **任务队列（核心）** | 服务端 `MaidTaskQueue` | **S2C**（打开时 + 每次队列变化 + 每 20t 节流） |
| 当前行为 / 战斗中 | 服务端 `MaidTaskManager` / `MaidCombatGoal` | 同上，同一个包 |
| 饱食度 | 服务端 `MaidFoodData` | 同上 |
| 桥接连接状态 | 服务端 `MaidWsClient` | 同上（设置页用） |
| 回执（ok/state/step/result） | `MaidCommandResult` | 同上，**随动作回发** |

推送策略：打开时发一次全量 + 队列变更即推 + 每 20 tick 节流；关屏即停。

### 3.4 协议

菜单不再只做"透传"（因为多了队列操作），所以用**带 op 的动作包**：

```jsonc
// C2S  MaidMenuActionPayload
{"op":"enqueue","cmd":"mine","params":{"range":12,"count":8}}   // 追加到队尾
{"op":"runNow","cmd":"guard","params":{"range":10}}             // 立即执行（清空队列后执行这一项）
{"op":"cancelCurrent"}                                          // 停止当前项，保留后续
{"op":"remove","index":2}                                       // 移出第 2 项（0 基或 1 基待定）
{"op":"moveUp","index":3}                                       // 上移一位
{"op":"clear"}                                                  // 清空队列
{"op":"query"}                                                  // 拉一次队列快照（轮询兜底）

// S2C  MaidMenuStatePayload
{"queue":[{"cmd":"mine","name":"挖矿","state":"running","progress":"12格/8块","params":{...}},
          {"cmd":"chestput","name":"存入箱子","state":"queued","params":{...}}],
 "combat":false,"food":18.0,"maxFood":20,"bridge":true}
{"id":"menu-7","ok":true,"state":"done","step":"已入队","result":{...}}   // 回执（同包，带 id 时表示回执）
```

- `op` 全集**由服务端白名单校验**；`params` 仍**原样透传**给 `MaidAIBridge` 解析。
- 队列快照里每个任务带**中文显示名**（服务端由 cmd + params 生成，如 `mine` + `count:8` → "挖矿 8 块"），客户端不拼文案。

### 3.5 动作映射表

| 分组 | 按钮 | 指令 | 参数（默认值） | 入队方式 |
|---|---|---|---|---|
| 一级 | 取消坐下 / 坐下待命 / 召回 | — | — | 即时动作 |
| 一级 | 停止当前 | `cancelCurrent` | — | 停当前项 |
| 战斗 | 护卫 | `guard` | `range`(10) | 入队 |
| 战斗 | 攻击 | `attack` | `range`、`target`(可空=最近敌对) | 入队 |
| 战斗 | 喂食 | `feed` | — | 入队 |
| 战斗 | 进食 | `eat` | `item`(可空=营养最高) | 入队 |
| 生产 | 挖矿 | `mine` | `pos`(可空=自动探测)、`range`(12)、`count`(8) | 入队 |
| 生产 | 耕作 | `farm` | `pos`(准星)、`range` | 入队 |
| 生产 | 建造 | `build` | `pos`(准星)、`height` | 入队 |
| 生产 | 收集 | `collect` | `range`(8) | 入队 |
| 物品 | 制作 | `craft`（先 `craft_check`） | `item`、`count` | 入队 |
| 物品 | 烧炼 | `smelt` | `item`、`count` | 入队 |
| 物品 | 存箱 | 新增 `ChestStoreTask`（内部：开箱 → 放入） | `pos`(准星) / `count` | 入队（**一个队列项、内部两步**） |
| 物品 | 取箱 | `chesttake` | `slot`、`count` | 入队 |
| 移动 | 移动 / 看向 / 破坏 / 放置 / 使用 | `move`/`look`/`break`/`place`/`use` | `pos`(准星) | 入队 |
| 收纳 | 装备 / 收起 / 丢出 / 拾取 | `equip`/`store`/`drop`/`pickup` | `item` / — / `count` / — | 入队 |

**坐标三态**（`MaidParamPanel`）：`准星所指方块`（客户端 raytrace → 绝对坐标）/ `我脚下`（感知 `owner.pos`）/ `女仆脚下`（发 `"~"`）。
⚠️ `~` 的基准是**女仆脚下**，不是玩家 —— 面板上必须写清。

**存箱是两步**（既有语义，`HANDOVER.md` §5.1）：`chestopen` 是异步任务，回执只表示受理。
→ 队列方案下**打包成一个队列项**：新增 `ChestStoreTask`（内部：开箱 → 放入）。
若拆成两个队列项，`chestopen` 失败时 `chestput` **必然失败**，就得引入"组失败"那套复杂度；
打包成一项后，**一个项 = 一个原子语义单位**，失败策略干净（见 `DESIGN_MAID_TASK_QUEUE.md` §七）。
顺带这也省掉了菜单侧轮询 `status` 的编排。

### 3.6 日志（遵守"新功能顺手加低频 debug 日志"）

- 队列变更：`[SmartMaid-Debug] 队列 <op> → 共 N 项（当前 <cmd>）`
- 每步切换：`[SmartMaid-Debug] 队列推进 → 第 N/共M 项 <cmd>`
- 菜单动作/回执：`[SmartMaid-Debug] 菜单动作 op=<op> id=<id>` / `菜单回执 id=<id> ok=<bool> state=<state>`
- 页切换**不打日志**（高频）。

### 3.7 ★ 任务队列（后端，本次唯一后端改动）

现状：`MaidTaskManager` 用 `aiBusy` 门控，**同一时刻只有一个任务**；`tick()` 里战斗激活直接 `cancel()`（**不恢复**）。

> 📄 **完整深化设计见 [`DESIGN_MAID_TASK_QUEUE.md`](./DESIGN_MAID_TASK_QUEUE.md)** ——
> 现状逐行证据、`QueuedTask`/状态机、推进点与 `isAiBusy` 修正、战斗重跑语义、失败策略与原子性、
> 协议、`status` 向后兼容扩展、回归面、待拍板 5 项。**本节只保留结论。**

方案：新增 `MaidTaskQueue`（服务端，挂在 `MaidTaskManager` 上）：

```
class MaidTaskQueue {
    Deque<QueuedTask> items;          // QueuedTask = {cmd, paramsJson, displayName}
    QueuedTask current;               // 正在执行的那一项（= items.peekFirst）
    void enqueue(cmd, params);        // 追加到队尾
    void advance();                   // 当前项 done/failed → pop → 启动下一项
    boolean cancelCurrent();          // 只停当前项，队列保留
    boolean remove(int index);        // 移出某项
    boolean moveUp(int index);        // 上移（调整顺序）
    void clear();                     // 清空
    List<Snapshot> snapshot();        // 供 S2C 下发
}
```

- `MaidTaskManager.tick()`：当前任务 `isDone()` → `queue.advance()`（自动开下一项）。
- `cancel` 指令语义改为：**停当前项**（原语义是"取消当前任务"，与此一致）。
- **队列上限**：建议 16 项（防挂机堆积）；超出时拒绝入队并回报 `queue_full`。
- ⚠️ **战斗与队列的关系必须拍板**（§六问题 2）：现在是"战斗期 `cancel()` 掉任务"。队列时代有三个选项：
  1. **PAUSED 整项重跑**（推荐）：⚠️ 任务**不支持中途恢复**（`forceStop` 只清理现场、无 revert/resume 语义）
     → 所以"暂停"= 把当前项放回队首、战斗结束后**整项重跑**（挖一半的矿要重挖），并给 `runAttempts ≤ 3` 上限防死循环；
  2. **清空队列**（= 沿用现状语义，实现最省，但玩家排的队会没）；
  3. **保留但跳过当前项**（折中）。

### 3.8 视觉规范：色系 + 遮罩 + 层次（对齐桌宠）

用户要求"和桌宠那样做出层次感"。**桌宠自己已有一套成体系的方案**，就在 `desktop-pet/core/theme.py`：
里面有两套主题 —— 全局的 Ant 蓝（`_ANT_QSS`，#1677FF 品牌蓝），以及**专为设置弹窗做的 WBS（WorkBuddy Style）绿调主题**。
WBS 的注释里明确写了三条机制，**这正是"层次感"的做法**：

> 1. **分层靠亮度差、不靠描边** —— 弹窗底 `#F6F8F5` / 卡片 `#FFFFFF`，全窗 0 描边；
> 2. **交互反馈是「淡色遮罩」** —— hover `#EDF0EC`、选中 `#C3DCC6`，都不加边框；
> 3. 动效短（150~200ms）且位移用减速曲线。

#### 令牌（直接取 WBS 的值，搬进 MC 用）

| 令牌 | 色值 | 本菜单里的用途 |
|---|---|---|
| 弹窗底 | `#F6F8F5` | 二级页/一级页的面板底（**不是纯白**，与卡片拉开亮度差） |
| 卡片 | `#FFFFFF` | 队列行、入口按钮、参数面板的底 |
| 悬停遮罩 | `#EDF0EC` | 行/按钮 hover（**叠在底上，不描边**） |
| 选中遮罩 | `#C3DCC6` | 当前选中行 / 正在执行的那一项 |
| 强调绿 | `#3C8C4E` | 进行中标记、进度填充、聚焦边 |
| 主按钮 | `#519560`（hover `#46854F` / pressed `#3A7444`） | 「＋ 添加任务」等主操作 |
| 主文字 | `#293B30` | 带绿调的墨色，**不用纯黑** |
| 次要文字 | `#59675E` | 参数摘要、状态 |
| 三级文字 | `#6C7870` | 空位、"待建设"、说明 |
| 极浅描边 | `#E2E8E1` | 仅输入框/次要按钮用（**卡片不用**） |
| 世界遮罩 | 黑 45%（`0x73000000`） | 弹窗后面压暗游戏世界 |
| 圆角 | 弹窗 24 / 卡片 16 / 控件 8 | 对应 `WBS_RADIUS_*` |

#### 层次结构（自下而上 5 层）

```
① 游戏世界（照常渲染）
② 全屏暗遮罩      fill(全屏, 黑 45%)            ← 遮罩层
③ 面板底          fill(面板区, #F6F8F5 @ ~96%)   ← 分层靠亮度差
④ 卡片            fill(行/按钮区, #FFFFFF)
⑤ 状态遮罩        hover #EDF0EC / 选中 #C3DCC6   ← 又是遮罩，仍不描边
```

#### 26.2 里怎么实现（已核实）

| 手段 | API | 说明 |
|---|---|---|
| 半透明遮罩 | `GuiGraphicsExtractor.fill(x1,y1,x2,y2, ARGB)` | ✅ 存在；GUI 管线带混合，**alpha 生效** |
| 渐变遮罩 | `fillGradient(x1,y1,x2,y2,顶色,底色)` | ✅ 存在（做顶部渐隐/底部渐隐） |
| **毛玻璃遮罩** | `Screen#extractBlurredBackground(GuiGraphicsExtractor)` + `blurBeforeThisStratum()` | ✅ 都存在 → 可直接用 MC 自带的**菜单背景模糊**，比纯压暗更"有层次" |
| 不画默认不透明底 | 覆写 `extractBackground` 只画遮罩 | 否则默认背景会把世界整个盖住，失去层次 |
| 圆角 | 用带圆角的 PNG（程序化生成）+ `blit` 九宫格（`blitSprite` 支持 NineSlice） | 或用 `fill` 拼直角（省事但圆角没了） |

> ⚠️ **两点实测须知**：① 面板底建议 **92~96% 不透明**，再低文字就开始和世界糊在一起；
> ② 毛玻璃模糊需要 `minecraft.level != null`（在世界里打开才有背景可糊），主菜单里打开看不到效果——属正常。

#### 与现有木质贴图的关系

**已拍板（2026-09-30）：色系走 WBS 绿** —— 原来的木质贴图（`maid_panel.png` / `maid_button*.png`）
**全部作废、从资源里删掉**，UI **完全自己设计**。

现在是**木质棕 + 金色四角 + 描边**，这套整体弃用。WBS 靠"纯色 + 遮罩"，只需程序化生成
**3 张圆角底图**（面板 / 卡片 / 控件），不需要木纹。
→ 顺带**彻底解决了**"TLM 贴图是 CC BY-NC-SA 不能用"的问题：**我们不需要任何现成贴图**。

---

## 四、实施顺序（每步可独立真机验证）

| 步 | 内容 | 验证 |
|---|---|---|
| **1** | **视觉基座**（§3.8）：`MaidTheme` 令牌类（WBS 色值 + 圆角）、程序化生成圆角底图 3 张（面板/卡片/控件）、遮罩层（世界暗遮罩 + 毛玻璃）、`fill` 封装 | 世界里打开能看到"世界压暗/发虚 → 面板 → 卡片"三层分明；面板底 92~96% 不透明时文字清晰 |
| **1b** | 一级 `MaidMenuScreen`（入口列表 + 3D 预览 + 状态条 + 4 高频动作）+ Shift+右键 改入口 | 打开/关闭不崩；1280×720 与 2560×1440 不越界 |
| **1c** | `MaidSubScreen` 基类 + 返回链路 + 对话/设置/动作 3 个占位页 | 进得去回得来（含 ESC）；占位页可打开不崩 |
| **2** | 新协议 `MaidMenuActionPayload` + `MaidMenuStatePayload`（先只做 `query` 与回执） | 从二级页发 `query`，界面能显示回执 |
| **3** | **后端：`MaidTaskQueue`** + `MaidTaskManager` 接入 + 战斗关系按 §六问题 2 落地 | 连发 3 条任务 → 日志显示依次执行完成；`cancel` 只停当前项 |
| **4** | 二级任务页（队列行列表 + 添加任务就地展开 + 参数三态 + 重排/移除） | 队列顺序与界面显示一致；界面操作与日志逐一对应 |
| **5** | 队列功能验证：入队/取消当前/移除/上移/清空；`chestopen+chestput` 相邻入队 | 存箱两项按序执行成功；队列满时正确拒绝 |
| **6** | 背包入口接现有 `MaidInventoryScreen` | 背包进得去回得来（返回一级）；**背包界面无任何变化** |
| **7** | 状态读取：3D 预览 + 生命/饱食 + 装备概览 7 格 | 模型跟随鼠标旋转；装备图标与存档一致；女仆未加载不崩 |

**每步都必须**：`gradlew build -x test` → 备份旧 jar → 部署到 mods → **核对 jar 时间戳** → 真机。
（`DEVELOPMENT_ISSUES.md` 2026-09-16 教训：只 `compileJava` 会一直测旧 jar。）

---

## 五、风险与坑

| # | 风险 | 对策 |
|---|---|---|
| 1 | **26.2 渲染只有 `extract*`**，写 `render()` 会静默不生效 | 覆写 `extractRenderState` / `extractBackground`（签名已核实） |
| 2 | `TabButton` 是 abstract、需 `TabManager` 管线 | 用普通 `Screen` 做两级切换（不引入 TabManager） |
| 3 | 纯 Screen 无 `ContainerData` | 状态走新 S2C；**别想复用 `menu.getSetting`** |
| 4 | **队列改变了任务系统的既有语义**（原本"一次一个、战斗即取消"） | 战斗关系先拍板（§六问题 2）再动手；`cancel` 语义统一为"停当前项" |
| 5 | 队列项失败/超时卡住后面全部项 | 单项失败 → 按 `fail` 策略（跳过/中止），**必须保证 `advance()` 一定会被调用** |
| 6 | 关界面后队列仍在跑（预期行为） | 必须明确：**队列归服务端所有，关屏不取消**；状态条在一级也能看到队列项数 |
| 7 | 客户端可能取不到女仆实体 | 本地读全部判空，显示"未知"而不是崩 |
| 8 | `~` 基准是**女仆脚下**，易与"我脚下"混淆 | 参数面板三态分别显示最终坐标预览 |
| 9 | 队列行数超出可视区 | 行内滚动（`AbstractScrollArea`）或翻页；**不缩排** |
| 10 | 日志污染 | 菜单动作/队列变更走事件级日志；页切换/状态推送走 `verbose` 或不打 |
| 11 | 改动 `MaidTaskManager` 会波及桌宠侧正在用的任务流 | 回归项：`/maidtasks` 单条指令、桌宠 WS 下发、`script` 脚本、战斗抢占 |

---

## 六、需要拍板

| # | 问题 | 我的建议 | 备选 |
|---|---|---|---|
| **1** | **任务队列与战斗的关系？**（现状：战斗期直接取消任务、**不恢复**） | **PAUSED 整项重跑**（`runAttempts ≤ 3`，UI 标「重试中」） | 清空队列（沿用现状语义、最省）／跳过当前项（折中） |
| **2** | **「添加任务」的确认行为**：追加到队尾，还是"立即执行"？ | 默认**追加到队尾**；面板上另给一个 `立即执行` 按钮 | 一律立即执行（等于没有队列，不推荐） |
| **3** | **队列上限与重排范围** | 上限 **16 项**；允许 `↑` 上移 + `×` 移除 | 不限上限（有挂机堆积风险） |
| **4** | 入队校验到哪一步、失败策略 | 只校验参数合法性（`canStart` 留到执行时）；失败默认 `skip`，连续 2 项失败降级 `abort` | 细则见 `DESIGN_MAID_TASK_QUEUE.md` §十四 |

**已拍板**

- **色系 = 桌宠 WBS 绿**（2026-09-30）：⚠️ **原来的木质贴图（`maid_panel.png` / `maid_button*.png`）全部作废、从资源里删掉**，
  UI **完全自己设计**（纯色 + 遮罩分层，不需要任何现成贴图，也彻底绕开 TLM 的 CC BY-NC-SA 授权问题）。
- **背包完全不动**；任务面板 = 一行一项的**有序队列**。
- 队列深化设计见 **`DESIGN_MAID_TASK_QUEUE.md`**（含现状证据、兼容性铁律、状态机、战斗重跑语义、协议、回归面）。

---

## 七、明确不做（N1 范围外）

- **背包的任何改动**（补玩家 36 格 / 改布局 / 同窗内嵌 / 改协议）—— 列为不变量，见 §〇
- 对话页真实收发（需新增 WS `chat` + 桌宠对接）→ **N3**
- 设置页全部设置项 + 桥接开关 → **N4**
- 动作页（11 个 Emotecraft 动作）+ `H` 键轮盘 → **N4**
- 队列的**持久化**（重召唤后恢复队列）→ 需单独立项（现状任务状态不持久化）

---

## 附：与上游文档的对齐

- `DESIGN_MAID_MENU.md` §七 的拍板项保留；其中 §4.4 的"TLM 贴图 MIT"已更正为 CC BY-NC-SA；
  §3.3 的"背包补玩家 36 格"**作废**（用户明确要求背包不动）；§3.2 的"任务格按使用频率排布"改为**有序队列**。
- 建议在 `DESIGN_MAID_MENU.md` 顶部增加指向本文的入口，并把上述三处就地标注。
