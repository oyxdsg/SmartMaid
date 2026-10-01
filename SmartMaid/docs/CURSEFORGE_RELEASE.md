# CurseForge 发布说明

> 上传新文件时，把下面「English」段整段粘贴到 CurseForge 的 **Changelog** 输入框即可。
> 文件：`smartmaid-0.1.2.jar` · 游戏版本 `26.2` · 加载器 `Fabric` · 发布类型 `Release`。

---

## English

### New: Task queue

Your maid now works through a queue instead of forgetting everything after one job.

- **Two queues** — long-term and short-term. Long-term tasks run once the short-term queue is empty.
- **Priority** — your commands jump to the **front** of the queue (the current task pauses and yields, it is not dropped); DeskPet-detected tasks go to the back.
- **Interrupts don't lose work** — combat, sitting down, or being called off only **pauses** the task. It resumes automatically when she is free, instead of restarting.
- **Progress resumes** — mining / farming / building / smelting / harvesting continue from where they were interrupted.
- **Survives logout** — the queue is saved to your world; recall the maid and she picks up where she left off.
- **No duplicate orders** — the same job will not be queued twice, while different jobs (e.g. smelting iron and gold) can coexist.
- **Furnace booking** — smelting automatically finds an idle furnace; one furnace smelts one ore type at a time.
- New task: **store items into chests**.

### New: In-game menu

Press `Shift + Right-click` on the maid to open a new two-level menu.

- Green theme with a translucent world overlay and layered panels.
- **3D maid preview**, plus an **equipment overview** (4 armor slots + main hand + off hand).
- **Live health / hunger bars** (vanilla heart + drumstick sprites).
- **Task page** — add tasks (number steppers), delete, move up, stop a whole group, clear all; positional tasks support **crosshair target** or **maid's feet**.
- **Settings page** — 8 options, click to toggle.
- Action buttons: **Follow me / Sit down / Recall / Stop current**, each with a confirmation toast.
- The backpack entry opens the existing inventory screen, unchanged.

### Improvements

- DeskPet commands now carry priority and grouping; a batch of expanded tasks collapses into one group in the menu and can be stopped as a whole.

### Known limitations

- Script tasks do not resume across a re-summon (in-game interrupts still resume).
- Menu coordinates currently support crosshair / maid's feet only.
- Combat is not part of the queue (it always keeps the highest priority).

---

## 中文（对照，可只贴英文）

### 新增：任务队列

女仆现在会排队干活，而不是做一件忘一件。

- **双队列** —— 长期任务与短期任务两条队列，短期做完了才接着做长期。
- **优先级** —— 你亲口下的指令插到队首（正在做的事暂停让位，不丢弃），桌宠自动识别的指令排到队尾。
- **打断不丢活** —— 战斗、坐下、被叫停时任务只是暂停，忙完自动接着做，不会从头再来。
- **进度接续** —— 挖矿 / 耕作 / 建造 / 烧炼 / 采集被打断后，能从中断处继续。
- **退出也不丢** —— 队列存进存档，重新召唤女仆后自动接着做。
- **不重复下发** —— 同一件事不会排两遍；不同的事（比如烧铁矿和烧金矿）可以同时排。
- **熔炉不打架** —— 烧炼自动找空闲熔炉，一座熔炉同一时间只烧一种矿。
- 新增存取箱子任务。

### 新增：游戏内菜单

`Shift + 右键` 女仆打开全新的两级菜单。

- 绿色系配色 + 世界半透明遮罩，面板与卡片分层。
- 左侧 3D 女仆预览，下方装备概览（4 件盔甲 + 主手 + 副手）。
- 生命 / 饥饿状态条实时刷新（原版红心 + 鸡腿贴图）。
- 任务页 —— 添加任务（数字参数可加减微调）、删除、上移、整组停止、一键清空；位置任务可选准星指向或女仆脚下。
- 设置页 8 项，点一下即改。
- 底部动作按钮：跟随我 / 坐下待命 / 召回 / 停止当前，每次操作都有回执提示。
- 背包入口直接跳转原有背包界面，背包本身没有任何改动。

### 改进

- 桌宠指令带优先级与分组：一批展开的多个任务在菜单里折叠成一组，可以整组停止。

### 已知限制

- 脚本任务不支持跨重新召唤接续（游戏内被打断仍可继续）。
- 菜单坐标暂只支持准星 / 女仆脚下。
- 战斗系统未纳入队列（始终保持最高优先级）。

---

**依赖：** Minecraft 26.2 · Fabric Loader 0.18.4+ · Fabric API 0.158.0+26.2 · Java 25
**可选：** 桌面宠物 DeskPet（提供 AI 对话与语音朗读）—— <https://github.com/oyxdsg/Multimodal-AI-Companion>
