#!/usr/bin/env python3
"""probe_api.py —— 核实 Minecraft 26.2 / Fabric API 的真实 API 签名。

为什么需要它：26.2 不再发布 mappings，直接使用官方类名，但**类名和方法签名与旧版
wiki/记忆差异很大**（例：`DimensionDataStorage` → `SavedDataStorage`）。写兼容代码前
先核实签名，能省掉一轮"编不过 / 运行时 NoSuchMethodError"。

依赖：
  - JDK 25 的 javap（26.2 的 class 文件 major version 69，JDK 17 的 javap 读不了）
    默认找 HMCL 的 mojang runtime，可用 --javap 覆盖
  - loom 缓存里的 minecraft-merged-deobf jar，可用 --jar 覆盖

用法示例：
  # 查某个类里与关键字匹配的方法
  python tools/probe_api.py net.minecraft.world.level.Level destroyBlock gameEvent

  # 查看字节码（确认某方法内部调用了什么）
  python tools/probe_api.py --bytecode net.minecraft.world.level.Level destroyBlock

  # 在 jar 里按关键字搜索类名
  python tools/probe_api.py --find SavedData

  # 查 Fabric API 的类（自动展开聚合 jar 里的嵌套模块，无需手动解压）
  python tools/probe_api.py --fabric FakePlayer PlayerBlockBreakEvents

  # 列出某个类的全部成员
  python tools/probe_api.py --all net.minecraft.world.item.crafting.RecipeManager

路径探测顺序（三个路径都可用同一套覆盖）：
  ① 环境变量 SMARTMAID_JAVAP / SMARTMAID_MC_JAR / SMARTMAID_FABRIC_JAR
  ② tools/local_paths.json（本机专用，已 gitignore，形如 {"javap": "...", "mc_jar": "...", "fabric_jar": "..."}）
  ③ 常见默认位置（用 ~ 展开，不写死用户名）
"""
from __future__ import annotations

import argparse
import glob
import io
import os
import subprocess
import sys
import tempfile
import zipfile

# ---- 路径探测：环境变量 → tools/local_paths.json（gitignore，本机专用）→ 常见默认 ----
# 默认值一律用 expanduser("~") 拼接，**不写死本机用户名**。
HOME = os.path.expanduser("~")


def _local_paths() -> dict:
    """读 tools/local_paths.json（本机专用）—— 复用项目统一的 _paths 模块。"""
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from _paths import config as _paths_config
        return _paths_config()
    except Exception:
        return {}


_LOCAL = _local_paths()


def _javap_candidates() -> list[str]:
    out = []
    if os.environ.get("SMARTMAID_JAVAP"):
        out.append(os.environ["SMARTMAID_JAVAP"])
    if _LOCAL.get("javap"):
        out.append(_LOCAL["javap"])
    out += [
        os.path.join(HOME, ".hmcl", "java", "*", "bin", "javap.exe"),
        os.path.join(HOME, ".hmcl", "java", "*", "*", "bin", "javap.exe"),
        os.path.join(HOME, ".jdks", "*", "bin", "javap.exe"),
        os.path.join(HOME, "AppData", "Local", "Programs", "*", "bin", "javap.exe"),
        r"C:\Program Files\Java\*\bin\javap.exe",
        r"C:\Program Files\Eclipse Adoptium\*\bin\javap.exe",
    ]
    return out


def _jar_globs() -> list[str]:
    out = []
    if os.environ.get("SMARTMAID_MC_JAR"):
        out.append(os.environ["SMARTMAID_MC_JAR"])
    if _LOCAL.get("mc_jar"):
        out.append(_LOCAL["mc_jar"])
    out.append(os.path.join(HOME, ".gradle", "caches", "fabric-loom", "minecraftMaven",
                            "net", "minecraft", "minecraft-merged-deobf", "*",
                            "minecraft-merged-deobf-*.jar"))
    return out


def _fabric_globs() -> list[str]:
    out = []
    if os.environ.get("SMARTMAID_FABRIC_JAR"):
        out.append(os.environ["SMARTMAID_FABRIC_JAR"])
    if _LOCAL.get("fabric_jar"):
        out.append(_LOCAL["fabric_jar"])
    out += [
        os.path.join(HOME, "Desktop", "*", "*", "mods", "fabric-api-*.jar"),
        os.path.join(HOME, "Desktop", "*", "mods", "fabric-api-*.jar"),
        os.path.join(HOME, "AppData", "Roaming", ".minecraft", "mods", "fabric-api-*.jar"),
    ]
    return out


JAVAP_CANDIDATES = _javap_candidates()
JAR_GLOBS = _jar_globs()
FABRIC_GLOBS = _fabric_globs()


def find_first(patterns: list[str]) -> str | None:
    for pat in patterns:
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    return None


def find_fabric_jar() -> str | None:
    return find_first(FABRIC_GLOBS)


def javap(javap_exe: str, classpath: str, cls: str, bytecode: bool) -> str:
    args = [javap_exe, "-p"]
    if bytecode:
        args.append("-c")
    args += ["-classpath", classpath, cls]
    r = subprocess.run(args, capture_output=True, text=True, errors="replace")
    return r.stdout or "", r.stderr or ""


def print_matches(out: str, pats: list[str], show_all: bool) -> int:
    hits = 0
    for line in out.splitlines():
        s = line.strip()
        if not s:
            continue
        if show_all or not pats or any(p in s for p in pats):
            print("   ", s)
            hits += 1
    return hits


def extract_fabric_module(fabric_jar: str, keyword: str) -> str | None:
    """把 fabric-api 聚合 jar 里名字含 keyword 的嵌套模块解压到临时目录，返回目录。"""
    with zipfile.ZipFile(fabric_jar) as fz:
        target = next((n for n in fz.namelist()
                       if n.startswith("META-INF/jars/") and n.endswith(".jar")
                       and keyword.lower() in n.lower()), None)
        if target is None:
            print(f"!! 未找到含 '{keyword}' 的嵌套模块", file=sys.stderr)
            return None
        out_dir = os.path.join(tempfile.gettempdir(), "probe_api_" + os.path.basename(target)[:-4])
        os.makedirs(out_dir, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(fz.read(target))) as inner:
            inner.extractall(out_dir)
        return out_dir


def main() -> int:
    ap = argparse.ArgumentParser(description="核实 26.2 / Fabric API 签名")
    ap.add_argument("target", nargs="?", help="类名（全限定），或 --fabric 模式下的关键字")
    ap.add_argument("grep", nargs="*", help="要匹配的关键字（方法名等）")
    ap.add_argument("--jar", help="指定 minecraft jar")
    ap.add_argument("--javap", help="指定 javap 可执行文件")
    ap.add_argument("--bytecode", action="store_true", help="输出字节码（-c）")
    ap.add_argument("--all", action="store_true", help="不加过滤，输出全部成员")
    ap.add_argument("--find", metavar="KW", help="在 jar 里按关键字搜索类名")
    ap.add_argument("--fabric", action="store_true",
                    help="在 fabric-api 的嵌套模块里查找类（target=模块关键字，grep=类名关键字）")
    args = ap.parse_args()

    javap_exe = args.javap or find_first(JAVAP_CANDIDATES)
    if not javap_exe or not os.path.isfile(javap_exe):
        print("!! 找不到 javap（26.2 需要 JDK 25 的 javap）", file=sys.stderr)
        return 2

    jar = args.jar or find_first(JAR_GLOBS)
    if not jar or not os.path.isfile(jar):
        print("!! 找不到 minecraft-merged-deobf jar，请用 --jar 指定", file=sys.stderr)
        return 2

    if args.find:
        with zipfile.ZipFile(jar) as z:
            hits = sorted({n for n in z.namelist()
                           if args.find.lower() in n.lower() and n.endswith(".class") and "$" not in n})
        print(f"# jar: {jar}")
        print(f"# 类名含 '{args.find}' 的条目：{len(hits)}")
        for h in hits[:120]:
            print("   ", h)
        return 0

    if args.fabric:
        fapi = find_fabric_jar()
        if not fapi:
            print("!! 找不到 fabric-api jar，请检查游戏目录", file=sys.stderr)
            return 2
        module_kw = args.target or "events-interaction"
        pats = args.grep or []
        if pats:
            print(f"# fabric-api: {fapi}")
            with zipfile.ZipFile(fapi) as fz:
                classes = []
                for n in fz.namelist():
                    if n.startswith("META-INF/jars/") and n.endswith(".jar"):
                        with zipfile.ZipFile(io.BytesIO(fz.read(n))) as inner:
                            classes += [c for c in inner.namelist()
                                        if c.endswith(".class") and any(
                                            p.lower() in c.lower() for p in pats)]
            for c in sorted(set(classes))[:80]:
                print("   ", c)
            print("提示：要查方法签名，把模块关键字传给 target、并解压后的类名传给 --jar 目录：")
            print(f"  python {os.path.basename(__file__)} --fabric {module_kw}")
            print("  （或直接用 --jar <解压目录> 查类）")
            return 0
        d = extract_fabric_module(fapi, module_kw)
        if not d:
            return 1
        print(f"# 已解压模块到 {d}")
        print(f"# 用 --jar {d} 指定后即可查询具体类")
        return 0

    cls = args.target
    if not cls:
        ap.print_help()
        return 1
    out, err = javap(javap_exe, jar, cls, args.bytecode)
    print(f"# javap: {javap_exe}")
    print(f"# jar:   {jar}")
    print("=" * 72)
    print("CLASS:", cls, "(bytecode)" if args.bytecode else "")
    if not out.strip():
        print("  ERROR:", (err or "").strip()[:400])
        return 1
    n = print_matches(out, args.grep, args.all)
    if args.grep and n == 0:
        print("    (no match)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
