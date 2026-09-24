# SmartMaid · 智能女仆模组

> 一只能在 Minecraft 里替你干活的女仆：跟随、战斗、跳跃寻路、挖矿建造，还能听懂自然语言指令。

Minecraft **26.2 / Fabric** 模组。行为由**本地规则引擎主导**（安全优先、毫秒级响应），AI 作兜底增强；
女仆由 `/summonmaid` 召唤、唯一归属玩家，可与**桌面宠物 DeskPet** 联动 —— 女仆的 AI 对话 / 语音 / 气泡由桌宠提供。

## 相关仓库（下载看这里）

| 仓库 | 是什么 | 谁需要 |
|---|---|---|
| **[Multimodal-AI-Companion](https://github.com/oyxdsg/Multimodal-AI-Companion)** | 桌面宠物 **DeskPet**（AI 对话 / 语音 / 气泡），**本模组的联动对象** | 想让女仆会聊天、会说话就**必须**装 |
| **[Multimodal-AI-Companion-Assets](https://github.com/oyxdsg/Multimodal-AI-Companion-Assets)** | 桌宠的完整动画素材包（Release 附件，解压到 `desktop-pet/assets/`） | 想让桌宠动起来 |
| **SmartMaid**（本仓库） | 游戏内的智能女仆（Minecraft 26.2 Fabric 模组） | 想在游戏里指挥女仆干活 |

> 只想单机玩女仆、不接 AI 也可以：跟随 / 战斗 / 跳跃寻路 / 挖矿建造 / 任务 / 设置菜单**完全独立可用**，
> 只有 `/maidchat` 聊天栏对话与语音朗读需要桌宠在线。

## 快速开始

```bash
# 1) 构建本模组（需要 JDK 25，Minecraft 26.2 要求）
$env:JAVA_HOME="<你的 JDK 25 安装目录>"
cd SmartMaid
gradlew.bat build
#  产物：SmartMaid/build/libs/smartmaid-<version>.jar

# 2) 放进 .minecraft/mods/（需已安装 Fabric Loader 0.18.4+ 与 Fabric API 0.158.0+26.2）

# 3) 进游戏执行
/summonmaid
```

## 目录

| 路径 | 是什么 |
|---|---|
| `SmartMaid/` | 模组工程本体（源码 / 资源 / 工具 / 开发文档） |
| `SmartMaid/README.md` | **完整文档** —— 功能特性、指令手册、架构、构建与工具脚本 |
| `LICENSE` / `NOTICE.md` | 许可与第三方声明 |

## 许可

**本项目免费开源、不收取任何费用，也不用于商业用途。**

| 内容 | 许可 |
|---|---|
| 本项目原创代码 / 文档 / 木质 GUI 贴图 | **MIT**（[`LICENSE`](LICENSE)） |
| `assets/smartmaid/emotes/*.json`（11 个表情动画） | 来自 [Emotecraft](https://github.com/KosmX/emotes)（KosmX），**GPL-3.0** |
| 女仆皮肤（`皮肤大肥鱼.png` 等） | 基于"大肥鱼"角色立绘，**CC BY-NC-SA 4.0**（署名 / 禁商用 / 相同方式共享） |

> 完整来源与条款见 [`NOTICE.md`](NOTICE.md)。**皮肤与表情数据不可用于商业用途。**
