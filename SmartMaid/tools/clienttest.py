#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SmartMaid 测试驱动 —— 客户端自动化测试 + 错误接收通道的**唯一入口**。

它把三件事收在一条命令里，避免每次手工拷文件/翻日志：

  1. `run <名字>`  把 `tools/clienttest.<名字>.json` 放到
     `<游戏目录>/config/smartmaid/clienttest.json`，并清掉上一轮的报告；
  2. `report`      读 `<游戏目录>/config/smartmaid/clienttest.report.json`（客户端测试结果）；
  3. `errors`      读 `<游戏目录>/smartmaid/errors.jsonl`（**错误接收通道**，含未捕获异常）；

⚠️ 这个启动器开了**版本隔离**：26.3 的 gameDir 是 `<.minecraft>/versions/26.3`。
所以默认按 `--series` 拼路径（不给则用 `<.minecraft>` 本身，即 26.2 那份）。

用法
----
  python tools/clienttest.py list
  python tools/clienttest.py run gui-chain --series 26.3
  python tools/clienttest.py report --series 26.3
  python tools/clienttest.py errors --series 26.3 --limit 20
  python tools/clienttest.py all --series 26.3
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _paths  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, ".."))

IN_NAME = "clienttest.json"
REPORT_NAME = "clienttest.report.json"
DONE_NAME = "clienttest.done.json"


def game_dir(series: str | None) -> str:
    """按版本隔离规则解析真正的 gameDir。"""
    mc = _paths.minecraft_dir(required=True)
    if not series:
        return mc
    cand = os.path.join(mc, "versions", series)
    if not os.path.isdir(cand):
        found = sorted(glob.glob(os.path.join(mc, "versions", "*")))
        raise SystemExit("!! 找不到版本目录 %s\n   现有：%s" % (
            cand, ", ".join(os.path.basename(p) for p in found) or "(无)"))
    return cand


def config_dir(series: str | None) -> str:
    return os.path.join(game_dir(series), "config", "smartmaid")


def _read_json(path: str):
    with open(path, "rb") as f:
        raw = f.read()
    for enc in ("utf-8", "gbk"):
        try:
            return json.loads(raw.decode(enc))
        except Exception:
            continue
    raise SystemExit("!! 解析失败: %s" % path)


# --------------------------------------------------------------------- run
def cmd_list(_args) -> int:
    files = sorted(glob.glob(os.path.join(HERE, "clienttest.*.json")))
    if not files:
        print("（tools/ 下没有 clienttest.*.json）")
        return 0
    for f in files:
        name = os.path.basename(f)[len("clienttest."):-len(".json")]
        try:
            d = _read_json(f)
            print("  %-18s %2d 个用例  %s" % (name, len(d.get("cases", [])), d.get("desc", "")))
        except SystemExit:
            print("  %-18s (解析失败)" % name)
    return 0


def cmd_run(args) -> int:
    src = os.path.join(HERE, "clienttest.%s.json" % args.name)
    if not os.path.isfile(src):
        avail = [os.path.basename(f)[len("clienttest."):-len(".json")]
                 for f in glob.glob(os.path.join(HERE, "clienttest.*.json"))]
        print("!! 找不到 %s\n   可选：%s" % (src, ", ".join(avail)), file=sys.stderr)
        return 2
    cfg = config_dir(args.series)
    os.makedirs(cfg, exist_ok=True)
    for stale in (DONE_NAME, REPORT_NAME):
        p = os.path.join(cfg, stale)
        if os.path.isfile(p) and not args.keep:
            os.remove(p)
    shutil.copyfile(src, os.path.join(cfg, IN_NAME))

    done = os.path.join(cfg, DONE_NAME)
    if os.path.isfile(done) and not args.keep:
        os.remove(done)

    d = _read_json(src)
    print("✅ 已放置测试脚本 → %s" % os.path.join(cfg, IN_NAME))
    print("   名称 : %s" % d.get("name"))
    print("   用例 : %d" % len(d.get("cases", [])))
    if d.get("desc"):
        print("   说明 : %s" % d["desc"])
    if "keys" in args.name:
        print("   ⚠️ 本测试需要人工配合：进游戏后按住 Shift+E 约 5 秒。")
    print("\n下一步：启动游戏（要有世界）。跑完读取结果：")
    print("   python tools/clienttest.py report --series %s" % (args.series or ""))
    return 0


# ------------------------------------------------------------------ report
def cmd_report(args) -> int:
    cfg = config_dir(args.series)
    path = os.path.join(cfg, REPORT_NAME)
    if not os.path.isfile(path):
        done = os.path.join(cfg, DONE_NAME)
        print("（还没有报告：%s）" % path)
        if os.path.isfile(done):
            print("   但测试脚本已执行过（%s 存在）—— 报告可能写入失败，查错误通道。" % DONE_NAME)
        else:
            print("   测试脚本尚未执行。先 run，再进游戏。")
        return 1
    d = _read_json(path)
    s = d.get("summary", {})
    print("=" * 78)
    print("客户端测试: %s   判定=%s" % (d.get("name"), s.get("verdict")))
    print("mc=%s mod=%s  用时=%sms" % (d.get("minecraft"), d.get("modVersion"), d.get("elapsedMs")))
    print("PASS=%s FAIL=%s ERROR=%s / 共 %s" % (
        s.get("pass"), s.get("fail"), s.get("error"), s.get("total")))
    env = d.get("env") or {}
    if env:
        print("环境: inWorld=%s screen=%s nearestMaid=%s" % (
            env.get("inWorld"), env.get("screen"), env.get("nearestMaidId")))
        print("      键位: %s value=%s type=%s shiftKeys=%s" % (
            env.get("openInventoryKey"), env.get("openInventoryKeyValue"),
            env.get("openInventoryKeyType"), env.get("shiftKeyCodes")))
    print("-" * 78)
    for c in d.get("cases", []):
        st = c.get("status", "?")
        mark = {"PASS": "OK  ", "FAIL": "FAIL", "ERROR": "ERR "}.get(st, "?   ")
        print("  [%s] %-24s %-14s %s" % (mark, c.get("id"), c.get("op"), c.get("detail", "")))
    print("=" * 78)
    if s.get("verdict") != "PASS":
        print("❌ 有失败项。逐条看上面的 detail；若无头绪，`errors` 里通常有异常栈。")
    return 0 if s.get("verdict") == "PASS" else 1


# ------------------------------------------------------------------ errors
def cmd_errors(args) -> int:
    path = os.path.join(game_dir(args.series), "smartmaid", "errors.jsonl")
    if not os.path.isfile(path):
        print("（还没有错误记录：%s）\n   说明本会话没有上报过错误 —— 也可能是游戏还没重启过（新代码没生效）。" % path)
        return 0
    rows = []
    for line in open(path, "r", encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except Exception:
            continue
    errs = [r for r in rows if r.get("level") == "ERROR"]
    print("错误通道: %s" % path)
    print("共 %d 条（ERROR %d / WARN %d / INFO %d）" % (
        len(rows), len(errs),
        sum(1 for r in rows if r.get("level") == "WARN"),
        sum(1 for r in rows if r.get("level") == "INFO")))
    show = errs[-args.limit:] if args.limit else errs
    print("-" * 78)
    for r in show:
        print("[%s] %s (%s)  %s" % (r.get("ts"), r.get("tag"), r.get("level"), r.get("msg")))
        if r.get("exception"):
            print("        %s" % r["exception"])
            if args.stack and r.get("stack"):
                for ln in str(r["stack"]).splitlines()[:args.stack]:
                    print("        | %s" % ln)
    if not errs:
        print("（没有 ERROR 级记录）")
    return 0


def cmd_all(args) -> int:
    rc1 = cmd_report(args)
    print()
    rc2 = cmd_errors(args)
    return rc1 or rc2


def main() -> int:
    # --series 允许写在子命令前或后：argparse 的 subparser 默认值会覆盖父级，
    # 所以这里先把 argv 里的 --series X 提到最前面，统一由主解析器处理。
    argv = sys.argv[1:]
    hoisted: list[str] = []
    rest: list[str] = []
    i = 0
    while i < len(argv):
        if argv[i] == "--series" and i + 1 < len(argv):
            hoisted += [argv[i], argv[i + 1]]
            i += 2
            continue
        if argv[i].startswith("--series="):
            hoisted.append(argv[i])
            i += 1
            continue
        rest.append(argv[i])
        i += 1

    ap = argparse.ArgumentParser(description="SmartMaid 客户端测试 + 错误通道驱动")
    ap.add_argument("--series", default=None,
                    help="MC 版本目录名（版本隔离下用），如 26.3；不给则用 <.minecraft> 本身")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("list", help="列出可用测试脚本")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("run", help="部署测试脚本到游戏目录")
    p.add_argument("name", help="脚本名（tools/clienttest.<名字>.json）")
    p.add_argument("--keep", action="store_true", help="保留上一轮的报告/done 标记")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("report", help="读取客户端测试报告")
    p.set_defaults(func=cmd_report)

    p = sub.add_parser("errors", help="读取错误接收通道")
    p.add_argument("--limit", type=int, default=20, help="最多显示最近 N 条 ERROR（0=全部）")
    p.add_argument("--stack", type=int, default=0, help="每条打印 N 行栈（0=不打印）")
    p.set_defaults(func=cmd_errors)

    p = sub.add_parser("all", help="report + errors")
    p.add_argument("--limit", type=int, default=20)
    p.add_argument("--stack", type=int, default=0)
    p.set_defaults(func=cmd_all)

    args = ap.parse_args(hoisted + rest)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
