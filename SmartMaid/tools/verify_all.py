#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多版本回归总门 —— 一条命令跑完**所有能自动化**的静态检查。

把三套检查串成一个可重复的验收动作（改完代码/升级依赖后跑一次即可）：

  A. 编译    `compile_check.py`        目标版本能否编出 0 真实错误（classtweaker 误报自动识别）
  B. Mixin   `check_mixin_targets.py`  内嵌第三方库的 Mixin 目标/注入点在该版本是否成立（**致命项会崩游戏**）
  C. 依赖    `check_dep_compat.py`     内嵌第三方库引用的类/成员在该版本是否都存在

为什么要串起来：这三者是**互相看不见的盲区** ——
编译看不出来 Mixin 注解（B），常量池核验看不出来注解（B 的职责），
而 B/C 又都以「编译能过」为前提（编不过连 jar 都没有）。
实测教训：只跑 A+C 时给出过「PAL 只差 2 个成员」的干净结论，真机却**启动即崩**（漏了 B）。

用法
----
  python tools/verify_all.py --series 26.3
  python tools/verify_all.py --series 26.2
  python tools/verify_all.py --series 26.3 --json build_logs/verify_263.json

产物：终端汇总 +（可选）JSON 报告。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _paths  # noqa: E402
from _paths import find_jdk_tool  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, ".."))
LOGS = os.path.abspath(os.path.join(REPO, "..", "build_logs"))

# 每个版本线需要的「原版 jar / Fabric API / 本地依赖目录 / 内嵌第三方 jar」
SERIES = {
    "26.2": {
        "mc_jar": None,                       # 由 loom 缓存解析
        "loom_mc": "net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar",
        "fapi": "mods/fabric-api-0.158.0+26.2.jar",
        "libs": "libs",
        "nested": ["libs/player_animation_library-1.2.6.jar"],
        "mc_range": "~26.2", "fapi_range": ">=0.158.0+26.2", "pal_range": ">=1.2.6",
    },
    "26.3": {
        "loom_mc": None,
        "mc_jar": "versions/26.3/26.3.jar",
        "fapi": "__deps__/fabric-api-26.3.jar",   # 走 build_logs/deps（不在 gameDir 下）
        "libs": "libs/mc26.3",
        "nested": ["libs/mc26.3/player_animation_library-1.2.7.jar"],
        "mc_range": "~26.3", "fapi_range": ">=0.161.0+26.3", "pal_range": ">=1.2.7",
    },
}


def mc_dir() -> str:
    return _paths.minecraft_dir(required=True)


def loom_cache() -> str:
    return os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom",
                        "minecraftMaven")


def resolve(cfg: dict) -> dict:
    """把配置里的相对路径解析成绝对路径。"""
    mc = mc_dir()
    out = dict(cfg)
    if cfg.get("loom_mc"):
        out["mc_jar"] = os.path.join(loom_cache(), cfg["loom_mc"])
    elif cfg.get("mc_jar"):
        out["mc_jar"] = os.path.join(mc, cfg["mc_jar"])
    fapi = cfg["fapi"]
    out["fapi"] = (os.path.join(LOGS, fapi.replace("__deps__/", "deps/"))
                   if fapi.startswith("__deps__/") else os.path.join(mc, fapi))
    out["libs"] = os.path.join(REPO, cfg["libs"])
    out["nested"] = [os.path.join(REPO, p) for p in cfg["nested"]]
    out["mc_libraries"] = os.path.join(mc, "libraries")
    return out


def run(argv: list[str]) -> tuple[int, str]:
    r = subprocess.run([sys.executable] + argv, capture_output=True, text=True, errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def step_compile(cfg: dict, series: str) -> dict:
    # ⚠️ 每次跑用**唯一**输出目录：compile_check.py 会先清空目标目录，而目标里往往有几百个
    #    .class —— 在带"删除保护"的环境里那会被拦成 "SAFE_DELETE_BULK_CONFIRM_REQUIRED"，
    #    于是编译步假失败。用唯一目录 = 根本没有东西要删。
    out_dir = os.path.join(tempfile.gettempdir(),
                           "sm_verify_%s_%d" % (series.replace(".", ""), time.time_ns()))
    os.makedirs(out_dir, exist_ok=True)
    rc, text = run([os.path.join(HERE, "compile_check.py"),
                    "--mc-jar", cfg["mc_jar"], "--fapi-jar", cfg["fapi"],
                    "--libs", cfg["libs"], "--libs-dir", cfg["mc_libraries"],
                    "--src", os.path.join(REPO, "src", "main", "java"),
                    "--src", os.path.join(REPO, "src", "mc%s" % series, "java"),
                    "--out", out_dir, "--max-errors", "40"])
    ok = rc == 0 and "0 个真实错误" in text
    return {"name": "A 编译", "ok": ok, "detail": _tail(text), "log": out_dir}


_RE_MIXIN_FATAL = re.compile(r"致命（会导致 Mixin 应用失败／启动崩溃）:\s*(\d+)")
# ⚠️ 收尾括号是**全角**「）」，别写成半角 —— 半角正则匹配不到，会静默拿不到数字。
_RE_MIXIN_WARN = re.compile(r"警告（Mixin 跳过该处，游戏仍可启动）\s*:\s*(\d+)")


def step_mixin(cfg: dict, series: str) -> dict:
    lines, bad = [], []
    for jar in cfg["nested"]:
        name = os.path.basename(jar)
        if not os.path.isfile(jar):
            bad.append(name + ": 文件不存在")
            continue
        rc, text = run([os.path.join(HERE, "check_mixin_targets.py"),
                        "--mod-jar", jar, "--mc-jar", cfg["mc_jar"],
                        "--libs-dir", cfg["mc_libraries"]])
        mf = _RE_MIXIN_FATAL.search(text)
        mw = _RE_MIXIN_WARN.search(text)
        fatal = int(mf.group(1)) if mf else -1
        warn = int(mw.group(1)) if mw else -1
        lines.append("%s → 致命 %s 处 / 警告 %s 处"
                     % (name, fatal if fatal >= 0 else "?", warn if warn >= 0 else "?"))
        if fatal != 0:
            bad.append("%s: 致命 %d 处（会导致启动崩溃）" % (name, fatal))
            bad += _lines_after(text, "❌ 致命", limit=6)
    return {"name": "B Mixin 目标", "ok": not bad, "detail": "\n".join(lines + bad)}


def _lines_after(text: str, marker: str, limit: int = 6) -> list[str]:
    """取出 marker 那行之后的若干条明细行（工具用缩进列出具体断点）。"""
    out, grab = [], 0
    for ln in text.splitlines():
        if grab > 0 and limit > 0:
            if not ln.strip():
                break
            out.append("      " + ln.strip())
            grab -= 1
            continue
        if marker in ln:
            out.append("      " + ln.strip())
            grab = limit
    return out


def step_dep(cfg: dict, series: str) -> dict:
    fapi_classes = os.path.join(LOGS, "deps", "fapi-classes")
    lines, bad = [], []
    for jar in cfg["nested"]:
        name = os.path.basename(jar)
        if not os.path.isfile(jar):
            continue
        rc, text = run([os.path.join(HERE, "check_dep_compat.py"), "--dep", jar,
                        "--mc-jar", cfg["mc_jar"],
                        "--extra-classpath", fapi_classes,
                        "--extra-classpath", cfg["mc_libraries"]])
        passed = "静态核对通过" in text
        lines.append("%s → %s" % (name, "通过" if passed else "**不通过**"))
        if not passed:
            bad.append(name)
            for ln in text.splitlines():
                if ln.strip().startswith("❌"):
                    bad.append("      " + ln.strip())
    return {"name": "C 依赖成员", "ok": not bad, "detail": "\n".join(lines + bad)}


def _tail(text: str, n: int = 8) -> str:
    ls = [l for l in text.splitlines() if l.strip()]
    return "\n".join(ls[-n:])


def main() -> int:
    ap = argparse.ArgumentParser(description="多版本回归总门（编译 + Mixin + 依赖）")
    ap.add_argument("--series", default="26.3", choices=sorted(SERIES))
    ap.add_argument("--json", default=None, help="把结果写到该 JSON 文件")
    args = ap.parse_args()

    if not find_jdk_tool("javac"):
        print("!! 找不到 JDK（javac）。见 tools/_paths.py 的解析顺序。", file=sys.stderr)
        return 2

    cfg = resolve(SERIES[args.series])
    for k in ("mc_jar", "fapi"):
        if not os.path.isfile(cfg[k]):
            print("!! 缺少 %s：%s" % (k, cfg[k]), file=sys.stderr)
            return 2

    print("SmartMaid 回归总门  目标版本 = %s" % args.series)
    print("  原版 jar : %s" % cfg["mc_jar"])
    print("  FabricAPI: %s" % cfg["fapi"])
    print("  本地依赖 : %s" % cfg["libs"])
    print("=" * 78)

    results = [step_compile(cfg, args.series), step_mixin(cfg, args.series), step_dep(cfg, args.series)]
    for r in results:
        print("[%s] %s" % ("通过" if r["ok"] else "失败", r["name"]))
        for line in (r["detail"] or "").splitlines():
            print("      " + line)
        print()

    verdict = all(r["ok"] for r in results)
    print("=" * 78)
    print("判定：%s" % ("✅ 全部通过（静态检查）" if verdict else "❌ 有失败项，见上"))
    if verdict:
        print("提醒：静态全绿只说明「没发现已知断点」，真机行为（GUI/渲染/手感）仍需 clienttest / 人工目测。")

    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"series": args.series, "verdict": verdict, "steps": results},
                      f, ensure_ascii=False, indent=2)
        print("报告：%s" % args.json)
    return 0 if verdict else 1


if __name__ == "__main__":
    sys.exit(main())
