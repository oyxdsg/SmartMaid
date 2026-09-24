#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发脚本的本机路径解析（纯标准库）。

这些脚本是本机开发辅助工具，需要知道「你的 Minecraft 目录」和「桌宠项目目录」在哪。
为避免把个人路径写死在仓库里，统一按以下优先级解析：

  1. 环境变量：SMARTMAID_MC_DIR / DESKPET_DIR
  2. tools/local_paths.json（本机专用，已在 .gitignore）
       {
         "minecraft_dir": "D:\\\\Minecraft\\\\.minecraft",
         "deskpet_dir": "C:\\\\path\\\\to\\\\desktop-pet"
       }
  3. 常见默认位置（%APPDATA%\\.minecraft 等）
  4. 都没有 → 退出并提示怎么写配置

用法：
    from _paths import minecraft_dir, deskpet_dir, deskpet_assets
    mc = minecraft_dir()
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "local_paths.json")

_HINT = (
    "\n请任选一种方式提供路径：\n"
    "  ① 设置环境变量（例如 DESKPET_DIR / SMARTMAID_MC_DIR）\n"
    "  ② 在 %s 里写：\n"
    "     {\n"
    "       \"minecraft_dir\": \"<你的 .minecraft 目录>\",\n"
    "       \"deskpet_dir\": \"<桌宠项目目录>\"\n"
    "     }\n" % CONFIG
)


def _config():
    try:
        with open(CONFIG, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except Exception as e:
        sys.stderr.write("[_paths] 读取 %s 失败：%s\n" % (CONFIG, e))
        return {}


def _resolve(env_name, config_key, candidates):
    v = os.environ.get(env_name)
    if v and os.path.isdir(v):
        return v
    v = _config().get(config_key)
    if v and os.path.isdir(v):
        return v
    for d in candidates:
        if d and os.path.isdir(d):
            return d
    return None


def _default_mc_candidates():
    appdata = os.environ.get("APPDATA")
    out = []
    if appdata:
        out.append(os.path.join(appdata, ".minecraft"))
    out.append(os.path.join(HERE, "..", ".minecraft"))
    return out


def minecraft_dir(required=True, what="Minecraft 目录（.minecraft）"):
    """定位 .minecraft 目录。"""
    p = _resolve("SMARTMAID_MC_DIR", "minecraft_dir", _default_mc_candidates())
    if p:
        return os.path.abspath(p)
    if required:
        sys.stderr.write("找不到%s。%s" % (what, _HINT))
        sys.exit(2)
    return None


def deskpet_dir(required=True):
    """定位桌宠项目目录（含 main.py）。"""
    repo = os.path.dirname(HERE)                     # SmartMaid/
    parent = os.path.dirname(repo)                   # 仓库根
    candidates = [
        # 与主仓库同级克隆 desktop-pet/，或克隆整个主仓库后在仓库根
        os.path.join(parent, "desktop-pet"),
        os.path.join(repo, "..", "desktop-pet"),
        os.path.join(os.getcwd(), "desktop-pet"),
    ]
    p = _resolve("DESKPET_DIR", "deskpet_dir", candidates)
    if p and os.path.isfile(os.path.join(p, "main.py")):
        return os.path.abspath(p)
    if required:
        sys.stderr.write("找不到桌宠项目目录（需含 main.py）。%s" % _HINT)
        sys.exit(2)
    return None


def deskpet_assets(required=True):
    """定位桌宠的动画帧目录（含 .base_frames/<view>/frame_0000.png）。"""
    p = _resolve("DESKPET_ASSETS_DIR", "deskpet_assets_dir", [])
    if p and os.path.isdir(os.path.join(p, ".base_frames")):
        return os.path.abspath(p)
    d = deskpet_dir(required=False)
    if d:
        a = os.path.join(d, "assets")
        if os.path.isdir(os.path.join(a, ".base_frames")):
            return a
    if required:
        sys.stderr.write(
            "找不到桌宠动画帧目录（需含 .base_frames/）。%s" % _HINT)
        sys.exit(2)
    return None


if __name__ == "__main__":
    for name, fn in (("minecraft_dir", minecraft_dir),
                     ("deskpet_dir", deskpet_dir),
                     ("deskpet_assets", deskpet_assets)):
        print("%-16s %s" % (name, fn(required=False) or "<未找到>"))
