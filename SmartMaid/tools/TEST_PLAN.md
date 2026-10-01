# SmartMaid 本轮功能测试方案（N1 菜单 + 任务队列）

> 目标：验证本轮新增（队列后端 Q4–Q9、菜单 N1、桌宠接口）不破坏既有功能、且新功能可用。
> 分两层：**A 自动化回归（AutoTest，真机自动跑）** + **B 人工目测（界面/持久化/手感）**。

---

## A. 自动化回归（`tools/autotest.queue-full.json`）

**如何跑**：把该文件复制成 `<游戏目录>/config/smartmaid/autotest.json`（删掉旧的 `autotest.done.json`）→
进游戏（单人世界）→ AutoTest 自动召唤女仆并执行 → 搜 `latest.log` 的 `AutoTest 汇总`（跑完自动改名 `.done.json`）。
`config/smartmaid/main.json` 的 `debug:true` 打开后，还能看到 `[SmartMaid-Debug]` 队列/任务行。

**设计要点**：用一个**长期 `guard` 当 holder 占住 current**，其后所有 `queue:true` 任务都排队**不执行**，
因此断言稳定（不受女仆移动/任务跑完影响）。

| 用例 | 覆盖 | 关键断言 | 也应出现的日志 |
|---|---|---|---|
| b0 | 基线 | `short_size=0,long_size=0` | — |
| ch | 长期任务当 holder | 回执 `queued` | `任务开始: guard (LONG)` |
| m1/m2/m3 | **Q6a 同键去重** | m2 `ok=false`（duplicate/duplicate_recent） | `入队判定: move:@… → REJECT(同 m1)` |
| qp/qmu/qrm/slt/qp2 | **Q4 队列操作** | `current.cmd=guard`；上移/移除/长期搬移后 `short=3,long=1` | `入队判定` / `任务恢复` 等 |
| sgc/sg1/sg2/sgs/qps | **Q4 整组停止** | `stopGroup` ok；随后 `short=0` | — |
| gm | **Q6b guard 合并** | `merged=true`，`note="…取最大 12"` | `入队判定: guard:* → MERGED` |
| sc | **Q9 script 入队** | `queued` | — |
| diff/cz/w1..czk/stop | **Q5 战斗打断** | 无断言，看日志 | **`任务暂停(COMBAT): guard`** → **`任务恢复: guard (LONG)`** |
| fz/fg/s1/s2/sw1..scl | **Q6c 熔炉租约** | s1/s2 `queued`；期间 `short_size=1`（一烧一排队） | `Smelt start` / `Smelt 取出成品` |
| chz..chstore..chclear | **ChestStoreTask** | `queued` | `ChestOpen 开箱动画` → 存箱完成 |
| z1 | 清理 | — | — |

**通过标准**：`PASS=… FAIL=0`（若个别 FAIL，逐条按上表核对是断言数据问题还是代码问题）。

---

## B. 人工目测（自动化覆盖不到的）

### B1. 菜单 N1（`Shift+右键` 女仆）
- [ ] 打开新 **WBS 绿**菜单：世界发虚压暗、面板/卡片亮度分层、**正常直角**（非缺角）。
- [ ] 左侧 **3D 预览**完整不截断；下方 **装备概览 6 格**（4 盔甲+主手+副手）与存档一致。
- [ ] 状态条：**原版红心**（半心）+ **原版鸡腿**；饿/回血时**实时变化**（每 20t）。
- [ ] 5 入口：任务 → 任务页；对话/设置/动作 → 占位页且**返回**正常；背包 → 开女仆背包（界面无变化）。
- [ ] 底部动作：跟随我 / 坐下待命 / 召回 / 停止当前 均生效，且有 toast 回执。

### B2. 任务页
- [ ] 「＋ 添加任务」：选任务 → **stepper −/＋** 调参；涉及坐标的（移动/耕作/存箱）**三态**（准星/女仆脚下）。
- [ ] 追加后队列出现该任务（**中文名**，如"挖矿 8 块"）；同一批展开的多项**折叠**成「标签 N 项」+ **整组停止**。
- [ ] 行内按钮：正在执行→停止这一项；短期→×/↑；长期→设当前/停止/↑；清空短期/长期 均生效，有 toast。

### B3. 持久化（Q8）
- [ ] 在任务页排 2 条任务 → **退出游戏** → 重进 → `/summonmaid` →
      日志 `女仆任务队列已恢复 N 项（自动继续）`，且队列自动继续。
- [ ] 旧存档（无 `tasks` 段）重召不报错、视为空队列。

### B4. 桌宠联动（另仓库 `desktop-pet`）
- [ ] 语音/对话下发"去砍树"：模组收到 `priority=owner` +（展开多项时）`group`；
      女仆正在跑任务时该指令**插到短期队首**（当前项暂停降位，不是被拒/丢弃）。
- [ ] 重复同一指令：桌宠收到的 `command_result` 带 `reason=duplicate`，**不重复播报**。
- [ ] 例行自检：`python tests/test_maid_loop.py`、`tests/test_command_separation.py`、`tests/test_structured.py` 全绿。

---

## C. 已知不在本轮（不测）
- `ScriptTask` 跨会话接续（显式声明不支持）。
- 菜单"我脚下"坐标态（需 `owner.pos` 的 S2C，未接）。
- 收尾（文档/版本/提交）。
