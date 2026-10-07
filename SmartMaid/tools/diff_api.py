#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""diff_api.py —— 对比两个 Minecraft 版本的类 API 差异（javap 输出 diff）。

为什么需要它：多版本兼容的第一步不是改代码，而是**量出真实差异**。
26.1 起游戏自带去混淆（含参数名），所以可以直接 javap 反编译任意版本的 jar，
**不需要 mappings，也不需要先跑 Loom** —— 只要手上有一个版本的 jar 就能开量。
（例：HMCL 下载的原版 jar 在 `<.minecraft>/versions/<ver>/<ver>.jar`。）

用法示例：
  # 量若干个类在两个版本间的成员增删
  python tools/diff_api.py --a 26.2.jar --b 26.3.jar --label-a 26.2 --label-b 26.3 \
      net.minecraft.client.Minecraft net.minecraft.client.gui.screens.Screen

  # 类名多时写文件（每行一个）
  python tools/diff_api.py --a ... --b ... --classes-file classes.txt

  # 只想知道某关键字相关的类在两版之间的增减
  python tools/diff_api.py --find SavedData --a 26.2.jar --b 26.3.jar

路径解析：
  - javap：环境变量 SMARTMAID_JAVAP → tools/local_paths.json 的 "javap" → 常见默认（复用 probe_api）
  - MSYS 风格路径（/c/Users/...）会自动转换成 C:/Users/... （Windows javap 不认前者）
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def _win_path(p: str) -> str:
    r"""把 MSYS/Git-Bash 风格路径 (/c/Users/x) 转成 Windows 风格 (C:/Users/x)。

    Windows 上的 javap.exe 不认 /c/...，直接传会报「找不到类」。
    """
    m = re.match(r"^/([a-zA-Z])/(.*)$", p)
    if m:
        return "%s:/%s" % (m.group(1).upper(), m.group(2))
    return p


def _javap_exe(override: str | None) -> str | None:
    if override:
        return override
    try:
        from probe_api import JAVAP_CANDIDATES, find_first
        return find_first(JAVAP_CANDIDATES)
    except Exception:
        import glob
        home = os.path.expanduser("~")
        for pat in (os.path.join(home, ".hmcl", "java", "*", "*", "bin", "javap.exe"),
                    os.path.join(home, ".hmcl", "java", "*", "bin", "javap.exe"),
                    r"C:\Program Files\Java\*\bin\javap.exe"):
            hits = sorted(glob.glob(pat))
            if hits:
                return hits[-1]
    return None


def javap_lines(javap: str, jar: str, cls: str) -> tuple[list[str] | None, str]:
    """返回 (成员行列表, 错误信息)。类不存在时 return None。"""
    r = subprocess.run([javap, "-p", "-classpath", jar, cls],
                       capture_output=True, text=True, errors="replace")
    out = r.stdout or ""
    if not out.strip():
        return None, (r.stderr or "").strip()
    lines = []
    for ln in out.splitlines():
        s = " ".join(ln.split())
        if not s or s.startswith("Compiled from") or s.startswith("}") or s.endswith("{"):
            continue
        lines.append(s)
    return lines, ""


def norm(lines: list[str]) -> set[str]:
    return set(lines)


def list_classes(jar: str, kw: str) -> set[str]:
    with zipfile.ZipFile(jar) as z:
        return {n[:-6].replace("/", ".") for n in z.namelist()
                if n.endswith(".class") and "$" not in n and kw.lower() in n.lower()}


def main() -> int:
    ap = argparse.ArgumentParser(description="对比两个 MC 版本的类 API 差异")
    ap.add_argument("classes", nargs="*", help="要对比的全限定类名")
    ap.add_argument("--a", required=True, help="版本 A 的 jar（基线，如 26.2）")
    ap.add_argument("--b", required=True, help="版本 B 的 jar（目标，如 26.3）")
    ap.add_argument("--label-a", default="A")
    ap.add_argument("--label-b", default="B")
    ap.add_argument("--classes-file", help="类名清单文件（每行一个，# 开头忽略）")
    ap.add_argument("--find", metavar="KW", help="改为对比两版中含该关键字的类名清单")
    ap.add_argument("--javap", help="指定 javap 可执行文件")
    ap.add_argument("--quiet-empty", action="store_true", help="两个版本都相同的类不打印")
    args = ap.parse_args()

    a, b = _win_path(args.a), _win_path(args.b)
    for p, n in ((a, "--a"), (b, "--b")):
        if not os.path.isfile(p):
            print("!! %s 不存在：%s" % (n, p), file=sys.stderr)
            return 2

    javap = _javap_exe(args.javap)
    if not javap or not os.path.isfile(javap):
        print("!! 找不到 javap（需 JDK 25 的，否则读不了 class major 69）", file=sys.stderr)
        return 2

    print("# javap: %s" % javap)
    print("# A(%s): %s" % (args.label_a, a))
    print("# B(%s): %s" % (args.label_b, b))
    print("=" * 72)

    if args.find:
        ca, cb = list_classes(a, args.find), list_classes(b, args.find)
        only_a, only_b = sorted(ca - cb), sorted(cb - ca)
        print("类名含 '%s'： %s=%d  %s=%d" % (args.find, args.label_a, len(ca), args.label_b, len(cb)))
        print("\n仅 %s 有（%d）：" % (args.label_a, len(only_a)))
        for c in only_a[:80]:
            print("   -", c)
        print("\n仅 %s 有（%d）：" % (args.label_b, len(only_b)))
        for c in only_b[:80]:
            print("   +", c)
        return 0

    classes = list(args.classes)
    if args.classes_file:
        with open(args.classes_file, "r", encoding="utf-8") as f:
            classes += [ln.strip() for ln in f
                        if ln.strip() and not ln.strip().startswith("#")]
    if not classes:
        print("!! 没有指定类名（位置参数或 --classes-file）", file=sys.stderr)
        return 2

    changed = 0
    for cls in classes:
        la, ea = javap_lines(javap, a, cls)
        lb, eb = javap_lines(javap, b, cls)
        if la is None and lb is None:
            print("\n### %s\n    !! 两版都找不到（%s）" % (cls, (ea or eb)[:120]))
            continue
        if la is None:
            print("\n### %s\n    [仅 %s 存在]  %s" % (cls, args.label_b, eb[:120]))
            changed += 1
            continue
        if lb is None:
            print("\n### %s\n    [仅 %s 存在 → %s 已移除]  %s"
                  % (cls, args.label_a, args.label_b, eb[:120]))
            changed += 1
            continue
        sa, sb = norm(la), norm(lb)
        removed, added = sorted(sa - sb), sorted(sb - sa)
        if not removed and not added:
            if not args.quiet_empty:
                print("\n### %s\n    = 无差异（%d 个成员）" % (cls, len(sa)))
            continue
        changed += 1
        print("\n### %s" % cls)
        if removed:
            print("    - %s 有、%s 没有（%d）：" % (args.label_a, args.label_b, len(removed)))
            for x in removed:
                print("       -", x)
        if added:
            print("    + %s 新增（%d）：" % (args.label_b, len(added)))
            for x in added:
                print("       +", x)

    print("\n" + "=" * 72)
    print("共 %d 个类存在差异，%d 个类被比较" % (changed, len(classes)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
