# SmartMaid 测试体系

> 目标：**把能自动化的都自动化，并且让「失败」有地方落**——不依赖人盯屏幕、不依赖人去翻 GBK 的 `latest.log`。
>
> 现状：服务端 + 客户端两层自动化；静态三层回归门；一个统一的错误接收通道。

---

## 0 一页速查

```bash
# ① 静态回归总门（改完代码/升依赖后必跑；不需要开游戏）
python tools/verify_all.py --series 26.3        # 编译 + Mixin 目标 + 依赖成员
python tools/verify_all.py --series 26.2

# ② 客户端自动化（GUI / 键位；要开游戏）
python tools/clienttest.py list
python tools/clienttest.py run gui-chain --series 26.3     # 放置测试脚本
#   → 启动游戏、进世界（测试自动跑，全程不碰鼠标键盘）
python tools/clienttest.py report --series 26.3            # 读结果
python tools/clienttest.py errors --series 26.3 --stack 8   # 读错误通道

# ③ 服务端自动化（任务/队列/寻路，既有）
#   把 tools/autotest.<名>.json 复制成 <game>/config/smartmaid/autotest.json → 进游戏
grep "AutoTest" "<game>/logs/latest.log"

# ④ 离线单测（纯逻辑，不需要游戏）
./gradlew test
```

> ⚠️ **这个启动器开了版本隔离**：`<game>` = `<.minecraft>/versions/<版本>`。
> 所以 `clienttest.py` / `autotest` 的路径都要带 `--series`。详见 `MEMORY.md` §2。

---

## 1 分层总览

| 层 | 跑在哪 | 覆盖什么 | 驱动方式 | 结果落点 |
|---|---|---|---|---|
| **离线单测** | JVM（无游戏） | 纯逻辑：队列、去重、接续、熔炉租约 | `./gradlew test` | JUnit 报告 |
| **服务端 AutoTest** | 服务端 tick | 任务下发/排队/打断/寻路/挖矿 | `config/smartmaid/autotest.json` | `latest.log` 的 `AutoTest` 行 |
| **客户端 ClientTest** | 客户端 tick | **GUI 打开链路、键位编码、Screen 命中测试** | `config/smartmaid/clienttest.json` | `clienttest.report.json` |
| **静态回归门** | 本机 Python | 编译不过、Mixin 目标不存在、第三方依赖缺成员 | `tools/verify_all.py` | 终端 + `build_logs/verify_<ver>.json` |
| **错误通道** | 两侧 | 未捕获异常 + 主动上报 | 自动 | `<game>/smartmaid/errors.jsonl` |

**为什么必须有客户端那层**：26.3 迁移时既有自动化**全部通过**，而真机仍然「启动即崩」和「面板打不开」——
因为 AutoTest 跑在服务端线程，看不到 GUI 与键位。这两处恰好是 26.3 改动最狠的地方（Screen/Gui 重组、GLFW→SDL）。
**自动化覆盖不到的那一层，就是事故会发生的层。**

---

## 2 客户端自动化 `MaidClientTest`

### 设计约束（来自本项目铁律）

**绝不动用户的鼠标键盘。** 所以：
- 驱动入口是**文件**（`clienttest.json`），不是热键；
- 「点击」是**在代码里合成屏幕坐标事件**并直接调 `Screen#mouseClicked(...)`，不走 OS 输入；
- 「按键」不模拟硬件，而是**直接调用业务处理函数**（`SmartMaidClient#tryOpenMaidInventory`），
  或**采样真实输入状态**（`pollKeys`，需要人真的按住键）。
- 任何异常都在运行器内被吞掉并上报，**测试永不让游戏崩**。
- **必须等「进了世界」才开始**（`mc.level != null && mc.getConnection() != null`）。
  这不只是洁癖：Screen 会被 `Gui.tick()` 持续驱动，而 Screen 里要发包；
  Fabric 在无连接时发包会抛 `Cannot send packets when not in game!`。
  **真机踩过**：脚本在**标题画面**就跑了 `openMenu`，20 tick 后游戏崩溃（首次真机测试的唯一事故）。
  未进世界时运行器会往错误通道写一条 INFO 说明原因，不会静默什么都不做。

### 与之配套的生产侧修复：`MaidNet`

同一次事故还暴露了模组的**真实缺陷**：`Screen` 里直接调 `ClientPlayNetworking.send`，
只要玩家"开着菜单退出世界/被踢"，残留的 Screen 继续 tick 就会崩。
现在所有 GUI 发包统一走 `client/gui/MaidNet.send(...)` —— **无连接时静默丢弃**，绝不抛异常。
（`MaidMenuScreen.tick` / `MaidTaskScreen.tick` 每秒一次 `query` 是最容易触发的那个。）

### 支持的 op

| op | 参数 | 作用 |
|---|---|---|
| `assertKeyMapping` | `expect`（如 `key.keyboard.e`） | 断言 `OPEN_INVENTORY` 注册的默认键名 |
| `pollKeys` | `ticks` | 采样 LShift/RShift/`isDown` 的真实状态（验证编码） |
| `openMenu` | `maidId`(默认取最近女仆，没有则 -1)、`food` | 直接开女仆主菜单（等价于收到 S2C 包） |
| `clickEntry` | `index` 0..4 | 在 `MaidMenuScreen` 上点第 i 个入口（**走真实命中测试**） |
| `clickAction` | `index` 0..3 | 点第 i 个高频动作 |
| `clickAt` | `x`,`y` | 任意坐标点一下（排查命中区域用） |
| `clickBack` | — | 点二级页的「返回」 |
| `assertScreen` | `expect`（简单类名，或 `(null)`） | 断言当前 `Screen` |
| `invokeOpenInventory` | — | 直接跑「Shift+E 开背包」的处理逻辑 |
| `closeScreen` | — | 关闭当前 Screen |
| `wait` | `ticks` | 等待（让包/状态刷新落定） |
| `note` | `msg` | 往报告里写一条信息 |

### 结果报告 `clienttest.report.json`

```jsonc
{
  "name": "gui-chain", "minecraft": "26.3", "modVersion": "0.1.2",
  "summary": { "total": 29, "pass": 28, "fail": 1, "error": 0, "verdict": "FAIL" },
  "env": {                       // 出问题时最有用的一段：当场环境的实况快照
    "inWorld": true, "screen": "MaidMenuScreen", "nearestMaidId": 1234,
    "openInventoryKey": "key.keyboard.e", "openInventoryKeyValue": 8,
    "openInventoryKeyType": "KEYBOARD", "shiftKeyCodes": [225, 229]
  },
  "cases": [ { "id": "...", "op": "...", "status": "PASS|FAIL|ERROR", "detail": "..." } ]
}
```

`env` 里的 `openInventoryKeyValue` 是**版本指纹**：26.2（GLFW）应为 **69**，26.3（SDL）应为 **8**。
数值不对 → 键位注册错了 → 按键必然"没反应"。

### 现有脚本

| 脚本 | 需要人工 | 覆盖 |
|---|---|---|
| `tools/clienttest.gui-chain.json` | 否 | 主菜单 → 任务/设置/动作/对话页 → 返回 → 4 个高频动作 → 背包入口（29 用例） |
| `tools/clienttest.key-encoding.json` | **是**（要求按住 Shift+E 约 5 秒） | 按键编码是否读得到 |

---

## 3 错误接收通道 `MaidErrorSink`

- **落点**：`<game>/smartmaid/errors.jsonl`，JSON Lines，append-only，> 2 MiB 自动轮转成 `errors.1.jsonl`。
- **来源**：
  1. **未捕获异常** —— 安装全局 `UncaughtExceptionHandler`，并**链式调用**原有处理器，
     只多记一条，不改变崩溃行为；
  2. **主动上报** —— `MaidErrorSink.report(tag, msg, throwable)`，给"被我们 catch 住的功能失败"留痕
     （客户端测试的每个非 PASS 用例都会自动上报）。
- **为什么不用 `latest.log` 就够**：日志是 GBK、混着几十个 mod 的输出、异常常被框架包一层或吞掉；
  这个通道**只含本模组、机器可读、可跨会话累积**。
- **安全性**：写入失败只打一条 WARN 就放弃，**上报本身绝不把游戏搞崩**。

读取：

```bash
python tools/clienttest.py errors --series 26.3 --stack 8
```

---

## 4 静态回归总门 `tools/verify_all.py`

串起三套**互相看不见的**检查——这是踩过坑才明白的：

| 检查 | 能发现 | **看不见** |
|---|---|---|
| A 编译（`compile_check.py`） | 版本 API 改名/删除/签名变化、classtweaker 误报 | **Mixin 注解**（不是常量池引用） |
| B Mixin 目标（`check_mixin_targets.py`） | `@Mixin` 目标类缺失、`@At` 注入点不存在（**致命项会启动崩**） | 运行期是否真的命中（ordinal/指令序） |
| C 依赖成员（`check_dep_compat.py`） | 第三方 jar 引用的类/成员不存在 | 同上，也看不见 Mixin |

**实测教训**：只跑 A+C 时得到过「PAL 在 26.3 只差 2 个成员」的干净结论，真机却**启动即崩**——
漏的正是 B。所以三者必须一起跑，`verify_all.py` 就是把这件事变成一条命令。

⚠️ 静态全绿**不等于**能跑。它只说明「没发现已知断点」。GUI/渲染/手感必须靠 `clienttest` + 人工目测。

---

## 5 本次（26.3 迁移）实际验证到的东西

| 现象 | 是哪一层发现的 |
|---|---|
| PAL Mixin 注入失败 → 启动崩溃 | **B**（`check_mixin_targets.py`，事后补的） |
| 存档里 jar-in-jar 漏 `jars` 声明 → 报"依赖未安装" | 真机日志（静态三层都查不出来） |
| `swing` / `FuelValues` / `isKeyDown` 等 21 处 | **A**（编译器枚举） |
| 键位注册成 SDL scancode → Shift+E 没反应 | **客户端 ClientTest** 的 `env.openInventoryKeyValue`（=8） |
| **鼠标左键编码 0→1 → 主菜单里点哪都没反应** | **客户端 ClientTest** 的 `clickEntry`（修复前 `handled` 全 false，修复后全 true） |
| Screen 内发包无连接检查 → 退出世界即崩 | 真机崩溃 + **错误通道**（`errors.jsonl` 里逐条带栈） |
| 测试运行器在标题画面开屏 → 自己把游戏搞崩 | **错误通道**（`clienttest/*` 逐用例记录，一眼看出是哪一步） |
| GUI 打开链路整体是否成立 | **客户端 ClientTest** `gui-chain` → **29/29 PASS**（2026-10-07 00:31） |
| 有女仆的 GUI 路径（3D 预览 / 装备概览 / 队列有数据） | **人工实测通过**（2026-10-07，召唤女仆后手工操作） |
| 真实物理 Shift+E 开背包 | **人工实测通过**（同日，手工按键） |

> 这条链路证明了一件事：**"既有自动化全绿而真机出问题"的根因是覆盖面缺口，不是自动化没用。**
> 补上客户端那层之后，同一批问题在下一次改动里会被立刻抓住。

> ⚠️ **"功能通过" ≠ "自动化覆盖"**。上表最后两行是**用户手工操作实测**（真鼠标、真键盘），
> **不是 `clienttest` 跑出来的** —— 那两条路径**至今仍自动化覆盖不到**（`gui-chain` 跑时
> `nearestMaid=-1`、菜单以 `state=null` 打开；`key-encoding` 需人工按住键）。详见 §6。

---

## 6 待补（如实列出）

- **有女仆的 GUI 路径**：功能已由**人工实测**通过（2026-10-07），但**自动化仍未覆盖** ——
  `gui-chain` 目前跑在 `nearestMaid=-1`、菜单以 `state=null` 打开，
  3D 预览 / 装备概览 / 任务队列里有数据这几个分支没走到。
  要自动化覆盖，需给 `gui-chain` 加前置步骤：进世界后先 `summon`，再断言 `nearestMaid >= 0`。
- **真实物理按键**：功能已由**人工实测**通过（Shift+E 能开背包），但 `key-encoding` 探针
  （需人工按住 Shift+E 约 5 秒）**尚未跑过**，物理键位 → 业务动作的完整闭环在自动化侧仍是空白。
- `MaidAutoTest`（服务端）与 `clienttest`（客户端）目前是两套运行器、两种脚本格式，
  尚未统一成一个文件里的两侧用例；也没做「服务端 + 客户端同时断言」的联动用例
  （例如：客户端点"背包入口" → 断言服务端真的开了容器）。
- `verify_all.py` 只覆盖内嵌第三方 jar 与本模组编译；**没有**把 `clienttest` / `autotest`
  的真机结果纳入统一判定（那需要游戏跑过之后才能汇总）。
- 26.2 线尚未在真机跑过 `gui-chain`（只验过 26.3）。
- 没有 CI。上面所有命令都是本机手动跑。
