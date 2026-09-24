# NOTICE — SmartMaid 第三方组件与素材来源声明

本文件说明 SmartMaid 仓库中各部分内容的来源与适用许可。除下列特别标注的
文件/目录外，本仓库的全部内容（Java 源码、构建脚本、文档、GUI 贴图、表情
以外的资源）由 oyxdsg 原创编写，按仓库根目录 `LICENSE`（MIT）分发。

---

## 1. 改编自 TouhouLittleMaid 的源码（MIT）

- **上游项目**：TouhouLittleMaid（车万女仆）
  https://github.com/TartaricAcid/TouhouLittleMaid
- **上游许可**：代码部分 MIT，Copyright (c) 2019-2025 tartaric_acid
  （原文见上游仓库 `LICENSE-MIT`）
- **改编文件**：
  - `SmartMaid/src/main/java/com/tartaricacid/smartmaid/entity/ai/MaidMoveControl.java`
    （改编自上游 `MaidMoveControl`：移动控制逻辑重构为执行器模式，
    并加入直线导航降级；约 31 行与上游相同）
- **适用许可**：MIT（与上游一致）。依 MIT 条款保留上述版权与许可声明。

> 说明：SmartMaid 未打包、未引用 TouhouLittleMaid 的任何美术/模型素材，
> 也不在运行时读取上游 `touhou_little_maid` 命名空间的任何资源
> （已对全部源码与资源做过引用扫描与字节级比对核实）。

## 2. 来自 Emotecraft 的表情动画（GPL-3.0）

- **上游项目**：Emotecraft (emotes) by KosmX
  https://github.com/KosmX/emotes
- **上游许可**：GPL-3.0（整仓库，含内置表情资产模块 emotesAssets）
- **涉及文件**：`SmartMaid/src/main/resources/assets/smartmaid/emotes/`
  下 11 个 JSON 文件（backflip / clap / club_penguin_dance / crying /
  here / kazotsky_kick / palm / point / roblox_potion_dance / twerk /
  waving），为上游内置表情的**未修改副本**（已抽样做内容级比对核实）。
- **适用许可**：**GPL-3.0**。这 11 个文件随本仓库分发时保持 GPL-3.0；
  各文件 JSON 内已含原作者（KosmX）署名字段。
  因此这些文件**不适用**本仓库的 MIT 许可，二次使用请遵循 GPL-3.0。

## 3. 女仆皮肤与角色形象（CC BY-NC-SA 4.0）

- **涉及文件**：
  - `SmartMaid/src/main/resources/assets/smartmaid/textures/entity/smart_maid.png`
  - `assets/smartmaid/textures/entity/smart_maid.png`（仓库根 assets 下同名文件）
  - `皮肤大肥鱼.png`（仓库根）
- **来源**：由仓库自带脚本 `SmartMaid/tools/gen_maid_skin.py` 从桌宠项目
  "大肥鱼" 角色立绘像素映射生成。该角色形象源自 B 站 UP 主 **zipzippipe**
  的 "大肥鱼"，其上游角色设定为 **溟月[上善无形]**，以
  **CC BY-NC-SA 4.0** 授权。
- **适用许可**：上述图片文件按 **CC BY-NC-SA 4.0** 分发：
  - **BY（署名）**：使用/改编时须署名原作者链（溟月[上善无形] → zipzippipe → 本项目）；
  - **NC（非商业）**：**不得用于商业目的**；
  - **SA（相同方式共享）**：改编作品须以相同许可分发。
  该许可仅约束列出的图片文件，不传染仓库中的代码。

## 4. GUI 贴图（本项目原创，随仓库 MIT）

`SmartMaid/src/main/resources/assets/smartmaid/textures/gui/` 下的
`maid_panel.png`、`maid_button.png`、`maid_button_hover.png`、
`maid_button_s.png`、`maid_button_s_hover.png` 为本项目程序化生成的
木纹材质，与 TouhouLittleMaid 的 GUI 图集无同源关系（尺寸、布局、
像素内容均不同，已逐一比对核实），随本仓库按 MIT 分发。

## 5. 内置第三方依赖 jar（`SmartMaid/libs/`）

| jar | 上游 | 许可 |
|---|---|---|
| `player_animation_library-1.2.6.jar` | Player Animation Library (ZigyTheBird) https://github.com/ZigyTheBird/PlayerAnimationLibrary | MIT |
| `mochafloats-5.0.0.jar` | `org.redlance.mochafloats:runtime` 5.0.0，Player Animation Library 的传递依赖（作者 Redlance / dima_dencep） | 以未修改二进制形式随库分发；jar 内未内嵌许可文本，使用时请遵循上游分发条款 |

两个 jar 均以**未修改**的二进制形式内置，仅作为运行时库使用，
SmartMaid 未修改其任何字节码。

---

*本声明由维护者（oyxdsg）于 2026-09-24 依据对上游仓库的逐项核查编写；
如上游许可发生变更，以各上游仓库现行版本为准。*
