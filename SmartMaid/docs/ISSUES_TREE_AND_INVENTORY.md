# 问题探查：砍树目标分散 + 背包满无整理（v2，按用户反馈修订）

> 2026-09-29。v1 只做探查，v1 曾误判"女仆没有斧头"，v2 已更正（见 §0）。
> **v3（同日）：两项均已实现并构建部署，见下方"实施状态"。**
> **✅ 2026-09-29 11:42 真机验收通过** —— 日志 `Harvest 收割 1…8 块（目标 8）` →
> `Harvest 结束（达成目标）collected=8/8` → `任务结束: script result=脚本完成 ticks=401`。

---

## 实施状态（2026-09-29 已实现，真机验收通过）

| 项 | 落地 | 关键文件 |
|---|---|---|
| **A. 连通性判据 = core（定稿）** | BFS 扩展边与目标池**都用 `core`**（资源本身，如 `#logs`）——即"相连的资源"：**一棵完整的树 / 一个完整的矿簇**，**不分树种矿种**（深色橡木也是树）。`produce` 不参与"哪些方块算目标" | `entity/ai/structure/HarvestTask.java#scan` |
| **A2. 起点容错** | seed 已非 core 时（move 把目标格挖了），在**半径 2** 内找最近的 core 作为 BFS 起点 | `HarvestTask#scan` + `#nearestCore` |
| **A3. find 的产物筛选不阻塞可用** | `pos` = 离女仆最近的 core（不看产物）；若整轮扫描没有产物匹配的结构 → **退化返回最近的同类结构**并标注 `produce_match:false`，而不是报 `found:false` 让女仆干站着 | `entity/ai/structure/StructureScan.java#find` / `#buildResult` |
| **B. 背包整理器** | 合并同类堆叠 + 丢弃垃圾；放不下时自动触发（挖矿掉落 + 原版拾取两条路径都接） | 新增 `entity/ai/MaidInventoryTidy.java` |
| 丢弃位置 | 女仆**身后一格**（按朝向反方向），物品 2 秒拾取冷却 + 女仆 10 秒不拾取 | `MaidInventoryTidy#dropBehind` |
| 垃圾清单 | 数据包 tag `#smartmaid:junk`（可被整合包覆盖） | `data/smartmaid/tags/item/junk.json` |
| 无垃圾可丢 | 上报 `inventory_full` 事件给 AI（含背包摘要 + `reason:"no_junk"`），600 tick 节流 | `PerceptionModule#reportEvent` |
| 开关 | `config/smartmaid/main.json` → `autoTidyWhenFull`（默认 true） | `data/SmartMaidConfig.java` |
| 测试 | AutoTest 新增 `tidy` 条目与 `freeSlots` 断言，模板加 t01–t06 | `tools/autotest.modpack-compat.json` |

**未做**：不做 `wantsToPickUp` 返回 false（硬编码拦截，用户明确排除）；不做"每类垃圾保留 N 个"。

**验收方式**：进游戏手动测（砍一棵树看是否只砍这一棵；背包满时看垃圾是否被丢到身后）；或把
`tools/autotest.modpack-compat.json` 复制成 `config/smartmaid/autotest.json` 自动跑。

---

## 二、砍树任务失败：三轮排查 → 判据分层定稿（2026-09-29）

真机日志（`latest.log`）：

```
StraightNav 降级 -> BlockPos{296,64,36} (直线=2格 原版=无路)
Move 到达 BlockPos{296,64,36}
Breaker begin BlockPos{296,64,36} (25t)                    ← move 把目标格挖了
StraightNav 挖 BlockPos{296,64,36} (目标 BlockPos{296,64,36})
Harvest start seed=BlockPos{296,64,36} count=6
Harvest scan: 目标 0 个                                     ← seed 已经是空气
```

**根因链**：

1. AI 生成的脚本是 `find → move → harvest`（桌宠侧 `prompt_min.txt` / `prompt_api.txt` 的模板里**并没有 move**，
   是模型自己加的；harvest 本身会导航到方块旁，这个 move 是多余的）
2. `move` 的目标 = `$tree.pos` = **树本身所在的原木格**（`find` 返回"离女仆最近的 core"）
3. 女仆直线走过去（寻路降级 `MaidStraightNav`），**挡路的正是那个原木 → 挖掉它站进去**
4. `harvest` 开始时 seed 已是空气；上一版 BFS 只沿 core 扩展 → **一个目标都扫不到**
5. 每轮循环重复一次，等于**一格一格把树"走"掉**；最后 `Script 终止: 超过步数上限 64`

**"左一下右一下"的真相也在这里**：旧版 BFS 沿 `core ∪ support` 扩展，从空气起点出发还能顺着**树叶**爬到
隔壁那棵树，于是砍到的是**别的树**的原木 —— 目标池因此混入整片林子（`remaining=1424`）。

**修复**：`HarvestTask.scan()` 起点容错 —— seed 不满足 matcher 时，在半径 2 内找最近的资源方块作为 BFS 起点
（半径 2 覆盖"被挖掉一两格"的情况，又不会跨到别的树上）。并在 scan 日志里打印 seed 处方块与 matcher 判定，
下次出问题可直接定位。

**给桌宠侧的建议（跨项目）**：在 prompt 里明确「**不要**为了采集而 `move` 到资源方块上 —— `harvest` 会自己
走到方块旁边；直接 `harvest(pos=$tree.pos, ...)` 即可」，可省掉一次无谓的挖树与来回跑。

### 第三轮：判据分层定稿（11:03，**推翻前两轮的修法**）

用户指出「深色橡木就不是树或者木头了吗？你的逻辑完全有问题」——**他是对的**。

前两轮错在同一处：**把「是不是同一棵树」和「是不是目标树种」混为一谈**。

| 轮次 | 做法 | 为什么错 |
|---|---|---|
| 第一轮 | 扩展边 = `core ∪ support` | 树叶把整片林子连通成一个结构（真机 `remaining=1424`）|
| 第二轮 | 扩展边 = `matcher`（产物谓词） | **用产物谓词当连通性判据** → 深色橡木被开除出"树"，附近有树却 0 目标 |
| **定稿** | **连通性 = `core`**，`produce` 只管"挑哪棵树" | 木头就是木头：深色橡木 / 白桦 / 橡木都是 `tree` 的 core |

**定稿后的职责分层**：

1. **`find` 决定"哪一棵树 / 哪个矿簇"** —— `produce` 在此做偏好（有橡木优先给橡木）；若扫完都没有产物匹配的
   结构，则**退化返回最近的同类结构**并标注 `produce_match:false`（而不是 `found:false` 让女仆干站着）
2. **`find` 返回的 `pos` = 离女仆最近的 `core`**（不再看产物谓词）
3. **`harvest` 从 seed 沿 `core` 扩展** → 把这棵相连的资源**整棵采完**；产物谓词不参与"哪些方块算目标"
4. `HarvestTask` 的**起点容错**同样改按 `core` 判定（seed 被 move 挖掉时，半径 2 找最近的 core）

**验证标准**：真机日志应出现 `Harvest scan: 目标 5~7 个（… 产物命中=false）` —— 产物不匹配也该有目标。

**补丁（11:14）：判定散落两处，我第一版只改了一半。** 定稿改完 `scan()`（收集）后真机仍报
`Harvest scan: 目标 47 个` + `Harvest 结束（无剩余目标）collected=0` —— 因为 **`tick()` 取目标时还留着
`!this.matcher.test(s)` 的过滤**：47 个目标被收集进来，又在取出时被产物谓词全部拒掉。
**已删**（取目标只判 `isAir`）。教训：同一个判据分散在"收集"和"消费"两处时，改一处必须全文搜一遍
（`grep matcher.test` 现在只剩日志与 `StructureScan` 的 find 筛选）。

---

## 三、砍了 4 块后卡死不动（11:21 修复）

真机日志：连续砍了 4 块（含 3 次 pillar 搭高爬树）后，

```
Breaker begin BlockPos{280,70,58} (25t)
StraightNav 放弃(搭路无方块) BlockPos{280,70,58}    ← 每 3 秒一次，重复 40 秒以上
```

**两个原因叠加**：

1. **Breaker 死磕不可达目标**：`MaidBlockBreaker.tick()` 的"水平太远"分支
   （以及 pillar 的水平接近分支）只做 `navigateTo(...) → return false` ——
   导航降级放弃后**没有任何出口**，于是每 20 tick 重新发起、再放弃，无限循环。
   而 `HarvestTask` 的"重试 N 次后跳过"逻辑依赖 `breaker.isActive() == false`，
   Breaker 不放手 → 上层永远不知道这个目标搞不定。
   **修复**：两处都加 `navRecentlyGaveUp(maid)` 检测（`MaidStraightNav.recentlyGaveUp`，
   60 tick 窗口）→ 放弃该目标 → 上层 retry 计数生效 → 跳过它去砍别的。

2. **搭路白名单漏了原木**：`MaidBlockPlacer` 在搭桥模式下**只认** `#smartmaid:bridge_blocks`，
   而该白名单是"泥土 / 木板 / 沙子 / 各类石头"——**没有 `#minecraft:logs`**。
   她背包里明明有 39 根原木（刚砍的 + 原有的），却报"搭路无方块"。
   **修复**：白名单加 `#minecraft:logs`（item tag，26.2 已核实存在）。

**验收**：她应该能用背包里的原木搭柱子爬上树把整棵砍完；若仍够不着（真没建材），
则跳过该目标继续砍其他可达的，而不是原地卡死。


**另一个真正的病根（桌宠侧，未改）**：prompt 模板把 produce 写死成 `#minecraft:oak_logs`
（`prompt_min.txt:42`、`prompt_api.txt:97`），于是用户说"去砍点树"也会被翻译成"砍橡木"。
"砍树"本该用 `#minecraft:logs`（或 `structure=tree` 且不传 produce）。

---

## 〇、更正：v1 的错误结论

**v1 说"女仆没有斧头、徒手砍树"—— 这是错的。**

翻存档核实（`saves/新的世界 (13)/smartmaid/maids/5fc5b52a-….dat`）：

- `minecraft:diamond_axe` 在 **mainhand**（手持位）
- 另外还有 `diamond_pickaxe` / `iron_pickaxe` / `bow` / `shield` / 全套盔甲（diamond_boots·diamond_leggings·iron_chestplate·iron_helmet）

**误判来源**：日志那行 `equipBestTool: 背包无正确工具，徒手挖 Block{minecraft:oak_leaves}`
说的是挖**树叶** —— 树叶的 `isCorrectToolForDrops` 只对**剪刀**为真，她没有剪刀，所以"无正确工具"。
**这与砍原木无关**：砍原木时钻石斧就是正确工具，日志里从未出现"徒手挖 oak_log"。

→ 因此"砍树慢"的原因里，**没有"缺工具"这一项**。下面的分析已据此修正。

---

## 一、砍树：目标分散（"左边砍一下、右边砍一下"）

### 根因（唯一主因）

**BFS 把 `support`（树叶）也当扩展边，于是从一棵树爬遍了整片树林。**

- `TreeStructure` 定义：树 = core(`#minecraft:logs`) ∪ support(`#minecraft:leaves`) 的连通组件
- `HarvestTask.scan()` 的扩展条件（`HarvestTask.java:160`）：

  ```java
  if (this.structure.coreBlock().test(qs) || this.structure.supportBlock().test(qs)) {
      queue.add(q);        // ← 树叶可扩展 → 树叶连成片 → 邻树的原木也被收进来
  }
  ```

- 日志实锤：`Harvest scan: 目标 76 个`（一棵橡树只有 4~7 个原木）→ 目标池混了十几棵树
- `pending` 是 BFS 顺序（从 seed 逐层扩散），挖的顺序自然在不同树间来回跳
- `count=8`（AI 本意"砍 8 根原木"）因此被**分散**到多棵树上，每棵都只啃一口

### 修复方案：**选定目标时以"相连的资源"为界**（用户指示，通用原则）

**核心**：目标收集只沿**资源本身（core）**扩展，不借助 support 蔓延。

| 结构 | 现状 | 改后 |
|---|---|---|
| **树** | core=原木、support=树叶 → 沿树叶跨树 ✗ | 只沿原木扩展 → 锁定一棵树 ✓ |
| **矿** | core=矿石、support=**false**（`OreStructure.java:46`）→ 本来就只沿矿石 ✓ | 无变化 ✓ |
| 其他 StructureType | 同理自动获得"连通资源优先" | ✓ |

**改动**：`HarvestTask.scan()` 的扩展条件去掉 `supportBlock()`，只保留 `coreBlock()`（一行）。
`support` 仍然保留用于 `find` 阶段的结构判定（`isStructure` 需要 core≥1 且 support≥1），语义不变。

**顺序天然正确**：`pending` 保持 BFS 顺序 = 从 seed 逐层外扩 = "相连的资源优先" ✓ 无需额外排序。

**安全性核对**：
- 树内原木本身连续（含 2×2 深色橡树、丛林巨树），**不依赖树叶桥接** ✓
- `find` 返回的 seed 必定是 core（原木）位置（日志 `find 命中: tree pos=[301,63,29]`，y=63 是树干）✓
- 只沿 core 不会漏掉同一棵树的任何原木 ✓

### 顺带的效率问题（可选，待定）

- **挖挡路树叶**：日志里 `徒手挖 oak_leaves` 刷屏 —— 女仆站位被树叶挡住时，`MaidBlockBreaker`
  会挖掉视线上第一个非目标方块。砍树时这些挖掘多数是浪费（玩家砍树不挖树叶）。
  目标锁定到单棵树后总体会快很多，但这项仍是纯损耗。
- **单步 60 秒超时**：`ScriptTask.MAX_STEP_TICKS`。目标收敛到 4~7 块后基本不会再触发；
  若要更稳可按 `count × 单块耗时` 动态放宽。
- **天气/树叶**：无。

---

## 二、背包满：掉落物捡不起来

### 现状链路（逐处核对）

```
setCanPickUpLoot(true)                  SmartMaidEntity.java:161
  → wantsToPickUp(level, stack)         SmartMaidEntity.java:799   （除抑制期外一律 true）
  → equipItemIfPossible(level, stack)   SmartMaidEntity.java:815   （只放 0-35）
  → storeToBackpack                     MaidActions.java:155       （先合并同类、再找空槽）
  → 背包满：返回全部剩余 → 原版判定"没取走" → 物品留在地上
```

**根因**：
1. **没有"背包满"的回退策略** —— 满了就是满了，不整理、不清理、不提示。
2. **全项目没有任何"整理背包"能力**（已 grep 确认）。
3. `storeToBackpack` 只合并**刚捡进来的那一堆**，不整理背包里**已有的**半满堆叠
   → 明明有空槽也可能因碎片化塞不下。

### 方案（按用户指示）

#### 2.1 不做的事（明确排除）

- ❌ **不做** `wantsToPickUp` 对垃圾返回 `false` —— 这是**硬编码拦截行为**，不允许。
- ✅ **不需要**担心"丢出去被自己捡回"：玩家会移动，女仆跟着走，垃圾留在原地。

#### 2.2 整理器（新增 `MaidInventoryTidy`）

触发：**拾取失败时**（背包满、物品没拿完）。

动作，按顺序：

1. **合并同类**：遍历 0-35，把 `ItemStack.isSameItemSameComponents` 且未满的堆叠合并
   → 释放被"半满堆叠"占掉的槽位
2. **丢弃所有垃圾**（用户指示：**直接丢光，不是"腾几个槽就停"**）
   - 通过 `MaidActions.dropToOwner` 丢到**主人脚下**（玩家能立刻捡到；主人不在线时退回女仆脚下）

3. **没有垃圾可丢 → 生成回执给 AI**（用户指示）

#### 2.3 "回执给 AI" 的通道（复用现有基建，不新造）

```
整理失败（背包满且无垃圾可丢）
  → PerceptionModule 新增 public 上报方法（内部走现有 private pushEvent）
  → eventSink（已注册）
      ├─ MaidTelemetryWriter → <游戏目录>/deskpet/maid/*.jsonl（文件通道）
      └─ MaidWsClient → WS 上行 "event" 消息
  → 桌宠 / AI 收到
```

- 现状：`PerceptionModule.addEventSink(...)` 是 public（`:146`），但 `pushEvent(...)` 是 **private**（`:259`）
  → 需要加一个 public 入口（如 `reportEvent(String type, JsonObject payload)`）。
- 事件建议：`type = "inventory_full"`，payload 带背包摘要（各类物品与数量），
  让 AI 能据此决策（例如"先回去把东西存进箱子"）。
- 回执语义与现有 `task_done` / `hurt` 等事件一致，桌宠侧无需新增协议 ✓

#### 2.4 垃圾判定

新增 item tag `#smartmaid:junk`（数据包可覆盖），默认内容：

| 类别 | 内容 |
|---|---|
| 树苗 | `#minecraft:saplings`（原版 tag，自动覆盖新树种） |
| 腐肉 | `minecraft:rotten_flesh` |
| 蘑菇 | `minecraft:brown_mushroom` / `minecraft:red_mushroom` |
| 装饰性石头 | `minecraft:diorite` / `andesite` / `granite` / `tuff` |
| 杂草类（建议） | `minecraft:short_grass` / `tall_grass` / `dead_bush` |

**安全底线**：**只丢这份清单里的**（默认不丢清单外任何东西）；另外对"工具/武器/盔甲/食物"再加一层
硬断言保护，双保险。

#### 2.5 配置项

```json
{ "autoTidyWhenFull": true }     // 背包满时自动整理；false = 保持现状（捡不到就捡不到）
```

（v1 里的 `neverPickUpJunk` / `junkKeepPerType` 已按指示删除 —— 不做拦截、不做"每类保留"。）

---

## 三、改动清单（动手时的落地范围）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `HarvestTask.java:160` | BFS 扩展只沿 core（去掉 support） |
| 2 | 新增 `MaidInventoryTidy.java` | 合并同类 + 丢弃全部垃圾 + 失败判定 |
| 3 | `SmartMaidEntity.java:815` | `equipItemIfPossible` 拿不完时触发整理并重试一次 |
| 4 | `PerceptionModule.java` | 新增 public 事件上报入口 |
| 5 | `MaidInventoryTidy` → 感知事件 | 无垃圾可丢时上报 `inventory_full` + 背包摘要 |
| 6 | 新增 `data/smartmaid/tags/item/junk.json` | 垃圾清单（可被数据包覆盖） |
| 7 | `SmartMaidConfig` / `main.json` | 新增 `autoTidyWhenFull` |

---

## 四、待确认（只剩 2 点）

1. **垃圾清单**：`#minecraft:saplings` + 腐肉 + 两种蘑菇 + 闪长岩/安山岩/花岗岩/凝灰岩
   （+ 是否要加杂草类）。**石头（stone）我特意没列**——它是正经建材。
2. **丢弃位置**：丢到**主人脚下**（`dropToOwner`，玩家能立刻捡）还是**女仆脚下**？
   我倾向主人脚下（复用现成方法，且"垃圾"里像树苗你可能真的想捡）。
