#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_dep_compat.py —— 静态核验「第三方 jar 能不能跑在目标 MC 版本上」。

为什么需要它：
  多版本兼容时，最不确定的不是自己的代码（编译器能查），而是**内嵌/依赖的第三方二进制**。
  第三方 jar 的 fabric.mod.json 常常写**开放式**版本范围（例如 `"minecraft": ">=26.2"`），
  于是 Loader 会**照单放行**，装上去才发现运行期 `NoSuchMethodError` / `NoClassDefFoundError`。
  在把 jar 放进 mods 跑之前，先用本工具静态量一遍，能提前把风险钉死。

它做什么：
  1. 解析第三方 jar 里每个 class 的**常量池**，抽出它引用的**外部类型与成员**
     （默认只关心 `net/minecraft/**`、`com/mojang/**`、`net/fabricmc/**`）。
  2. 逐个对目标版本核对：
     - 类型**是否还存在**（在 MC jar / 额外 classpath 里找 `<owner>.class`）
     - 成员的 **name + descriptor 是否还匹配**（在目标版本上 `javap -p -s` 取签名表，
       并**沿父类/接口链向上解析** —— 这是 JVM 的解析规则，不能只看本类声明）
  3. 汇总「不存在的类型」「找不到的成员」——这两类基本等于运行期必崩。

用法：
  python tools/check_dep_compat.py --dep <pal.jar> --mc-jar <26.3.jar> \
      --extra-classpath <fabric-api解包目录> --extra-classpath <mc>/libraries

  加 --list-ok 也会打印核对通过的条目（默认只打印问题）。

踩过的坑（写这个工具时实测出来的，改代码前先看一眼）：
  - **必须走父类链**：`avatar.getHealth()` 在常量池里的 owner 是 `Avatar`，
    但 `getHealth` 实际声明在 `LivingEntity` —— 只看本类会把所有继承成员误判为"找不到"。
  - **构造器在字节码里叫 `<init>`**，而 javap 打印的是类名 —— 不做映射会全部误判。
  - **`<mc>/libraries` 里是一堆 jar**，不是 class 目录 —— 只 walk `.class` 会漏掉
    brigadier / fabric-loader 等，产生大量假的"类型不存在"。

已知局限（如实标注）：
  - 只做**签名级**核对，不做字节码语义检查（跨版本行为漂移检测不到）。
  - 反射调用的成员、字符串拼出来的类名，看不见。
  - access widener / class tweaker 的"能不能访问"不在核对范围（只问"成不成员在"）。
"""
from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# ---------- JVM class 文件：只解析常量池 ----------
_CP_UTF8, _CP_INT, _CP_FLOAT, _CP_LONG, _CP_DOUBLE = 1, 3, 4, 5, 6
_CP_CLASS, _CP_STRING = 7, 8
_CP_FIELDREF, _CP_METHODREF, _CP_IFACEREF = 9, 10, 11
_CP_NAMEANDTYPE = 12
_CP_MH, _CP_MT, _CP_DYN, _CP_INVOKEDYN = 15, 16, 17, 18
_CP_MODULE, _CP_PACKAGE = 19, 20


def parse_constant_pool(data: bytes):
    """返回 (classes, members)。classes=set(内部名)；members=set((owner, name, desc))。"""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return set(), set()
    off = 8
    (count,) = struct.unpack_from(">H", data, off)
    off += 2
    utf8: dict[int, str] = {}
    cls_idx: dict[int, int] = {}
    nat: dict[int, tuple[int, int]] = {}
    refs: list[tuple[int, int]] = []
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == _CP_UTF8:
            (ln,) = struct.unpack_from(">H", data, off)
            off += 2
            utf8[i] = data[off:off + ln].decode("utf-8", "replace")
            off += ln
        elif tag in (_CP_INT, _CP_FLOAT):
            off += 4
        elif tag in (_CP_LONG, _CP_DOUBLE):
            off += 8
            i += 1
        elif tag == _CP_CLASS:
            (idx,) = struct.unpack_from(">H", data, off)
            off += 2
            cls_idx[i] = idx
        elif tag == _CP_STRING:
            off += 2
        elif tag in (_CP_FIELDREF, _CP_METHODREF, _CP_IFACEREF):
            c, nt = struct.unpack_from(">HH", data, off)
            off += 4
            refs.append((c, nt))
        elif tag == _CP_NAMEANDTYPE:
            n, d = struct.unpack_from(">HH", data, off)
            off += 4
            nat[i] = (n, d)
        elif tag == _CP_MH:
            off += 3
        elif tag in (_CP_MT, _CP_MODULE, _CP_PACKAGE):
            off += 2
        elif tag in (_CP_DYN, _CP_INVOKEDYN):
            off += 4
        else:
            return set(), set()
        i += 1

    classes = {utf8.get(n, "") for n in cls_idx.values()}
    members = set()
    for c, nt in refs:
        owner = utf8.get(cls_idx.get(c, -1), "")
        n, d = nat.get(nt, (-1, -1))
        members.add((owner, utf8.get(n, ""), utf8.get(d, "")))
    return {c for c in classes if c}, members


_CLS_HDR = re.compile(r"\b(class|interface|enum|record)\s+([\w.$]+)(?:\s*<[^>]*>)?"
                      r"(?:\s+extends\s+([\w.$]+)(?:\s*<[^>]*>)?)?"
                      r"(?:\s+implements\s+([^{]+))?")


class Target:
    """目标版本的「类型存在性 + 成员签名表（含父类链解析）」。"""

    def __init__(self, mc_jar: str, extras: list[str], javap: str):
        self.javap = javap
        self._mc = mc_jar
        self._names: set[str] | None = None
        self._home: dict[str, str] = {}      # class 内部名 → 装着它的容器（jar 或 class 根目录）
        self._cache: dict[str, tuple[set[str], list[str]] | None] = {}

        # 收集 class 名，并记住每个类「住在哪个 jar / 目录里」
        names: set[str] = set()

        def scan_jar(p: str) -> None:
            try:
                with zipfile.ZipFile(p) as z:
                    for e in z.namelist():
                        if e.endswith(".class"):
                            n = e[:-6]
                            names.add(n)
                            self._home.setdefault(n, p)
            except Exception:
                pass

        scan_jar(mc_jar)
        for ex in extras:
            if os.path.isdir(ex):
                for r, _, fs in os.walk(ex):
                    for f in fs:
                        p = os.path.join(r, f)
                        if f.endswith(".class"):
                            n = os.path.relpath(p, ex)[:-6].replace(os.sep, "/")
                            names.add(n)
                            self._home.setdefault(n, ex)
                        elif f.endswith(".jar"):
                            scan_jar(p)          # ⚠️ libraries 里是一堆 jar
            elif zipfile.is_zipfile(ex):
                scan_jar(ex)
        self._names = names

    def has_class(self, internal: str) -> bool:
        return internal in self._names

    def info(self, internal: str) -> tuple[set[str], list[str]] | None:
        """返回 (成员签名集, 父类/接口内部名列表)；类不存在返回 None。"""
        if internal in self._cache:
            return self._cache[internal]
        # ⚠️ 只把「装着这个类的那个容器」加进 classpath：
        #    ① 只给"装着 jar 的目录"javap 是找不到类的（它把目录当 class 根目录）；
        #    ② 把 libraries 下几百个 jar 全塞进 -classpath 会超过 Windows 命令行长度上限 → 起不来。
        home = self._home.get(internal)
        cp = self._mc if (home is None or home == self._mc) else (self._mc + os.pathsep + home)
        r = subprocess.run([self.javap, "-p", "-s", "-classpath", cp,
                            internal.replace("/", ".")],
                           capture_output=True, text=True, errors="replace")
        out = r.stdout or ""
        if not out.strip():
            self._cache[internal] = None
            return None
        simple = internal.split("/")[-1].split("$")[-1]
        supers: list[str] = []
        members: set[str] = set()
        pending = None
        for ln in out.splitlines():
            s = ln.strip()
            if s.startswith("descriptor:"):
                if pending:
                    members.add(pending + " " + s.split("descriptor:", 1)[1].strip())
                pending = None
                continue
            if not s or s.startswith("Compiled from") or s.startswith("}") or s.startswith("static {"):
                continue
            if s.endswith("{"):
                m = _CLS_HDR.search(s)
                if m:
                    for g in (m.group(3), m.group(4)):
                        if g:
                            supers += [x.strip().split("<")[0].replace(".", "/")
                                       for x in g.split(",") if x.strip()]
                continue
            mm = re.search(r"([A-Za-z0-9_$<>]+)\s*\(", s)
            if mm:
                nm = mm.group(1)
                pending = "<init>" if nm == simple else nm      # ⚠️ 构造器映射
            else:
                mf = re.search(r"([A-Za-z0-9_$]+)\s*;", s)
                if mf:
                    pending = mf.group(1)
        res = (members, supers)
        self._cache[internal] = res
        return res

    def resolve_member(self, owner: str, name: str, desc: str, _depth: int = 0) -> bool:
        """沿父类/接口链解析成员（对应 JVM 的解析规则）。"""
        if _depth > 12:
            return False
        inf = self.info(owner)
        if inf is None:
            return False
        members, supers = inf
        if (name + " " + desc) in members:
            return True
        return any(self.resolve_member(s, name, desc, _depth + 1) for s in supers)


def main() -> int:
    ap = argparse.ArgumentParser(description="静态核验第三方 jar 对目标 MC 版本的可用性")
    ap.add_argument("--dep", required=True, help="要核验的第三方 jar（如 PAL）")
    ap.add_argument("--mc-jar", required=True, help="目标版本的 MC jar")
    ap.add_argument("--extra-classpath", action="append", default=[],
                    help="额外 classpath（Fabric API 解包目录 / MC libraries 等），可重复")
    ap.add_argument("--javap", help="指定 javap")
    ap.add_argument("--pkg", action="append",
                    default=["net/minecraft/", "com/mojang/", "net/fabricmc/"],
                    help="只核对这些包前缀，可重复（默认游戏/加载器侧）")
    ap.add_argument("--list-ok", action="store_true", help="也列出核对通过的成员")
    args = ap.parse_args()

    try:
        from probe_api import JAVAP_CANDIDATES, find_first
        javap = args.javap or find_first(JAVAP_CANDIDATES)
    except Exception:
        javap = args.javap
    if not javap or not os.path.isfile(javap):
        print("!! 找不到 javap", file=sys.stderr)
        return 2

    dep = args.dep.replace("\\", "/")
    mc = args.mc_jar.replace("\\", "/")
    extras = [e.replace("\\", "/") for e in args.extra_classpath]
    for p, n in [(dep, "--dep"), (mc, "--mc-jar")]:
        if not os.path.isfile(p):
            print("!! %s 不存在：%s" % (n, p), file=sys.stderr)
            return 2

    all_classes: set[str] = set()
    all_members: set[tuple[str, str, str]] = set()
    ncls = 0
    with zipfile.ZipFile(dep) as z:
        for e in z.namelist():
            if not e.endswith(".class") or e.startswith("META-INF/versions/"):
                continue
            ncls += 1
            c, m = parse_constant_pool(z.read(e))
            all_classes |= c
            all_members |= m

    pref = tuple(args.pkg)
    ext_classes = sorted(c for c in all_classes if c.startswith(pref))
    ext_members = sorted({(o, n, d) for (o, n, d) in all_members
                          if o.startswith(pref) and n and d})

    tgt = Target(mc, extras, javap)
    print("# dep    : %s（%d 个 class）" % (dep, ncls))
    print("# 目标   : %s" % mc)
    print("# 额外 cp: %s" % (", ".join(extras) if extras else "(无)"))
    print("# 引用到游戏/加载器侧：类型 %d 个，成员 %d 个" % (len(ext_classes), len(ext_members)))
    print("=" * 72)

    missing_cls = [c for c in ext_classes if not tgt.has_class(c)]
    print("\n==== 类型核对 ====")
    if missing_cls:
        print("❌ 目标版本**不存在**的类型（%d）：运行期必崩" % len(missing_cls))
        for c in missing_cls:
            print("   ✗", c)
    else:
        print("✅ 引用的类型全部存在。")

    owners = defaultdict(list)
    for o, n, d in ext_members:
        owners[o].append((n, d))
    missing_mem, ok_mem = [], []
    for o, items in sorted(owners.items()):
        if not tgt.has_class(o):
            missing_mem += [(o, n, d, "所属类不存在") for n, d in items]
            continue
        for n, d in items:
            if tgt.resolve_member(o, n, d):
                ok_mem.append((o, n, d))
            else:
                missing_mem.append((o, n, d, "签名不匹配"))
    print("\n==== 成员核对 ====")
    if missing_mem:
        print("❌ 目标版本**找不到**的成员（%d）：" % len(missing_mem))
        for o, n, d, why in missing_mem:
            print("   ✗ %s.%s %s   [%s]" % (o.replace("/", "."), n, d, why))
    else:
        print("✅ 引用的成员全部匹配（%d 个）。" % len(ok_mem))
    if args.list_ok:
        print("\n---- 通过明细 ----")
        for o, n, d in ok_mem:
            print("   ✓ %s.%s %s" % (o.replace("/", "."), n, d))

    print("\n" + "=" * 72)
    bad = len(missing_cls) + len(missing_mem)
    if bad == 0:
        print("✅ 结论：静态核对通过 —— 该 jar 引用的游戏侧 API 在目标版本上都在。")
        print("   （仅签名级；运行时行为漂移仍需真机确认。）")
        return 0
    print("❌ 结论：发现 %d 处不兼容（类型 %d / 成员 %d）。"
          % (bad, len(missing_cls), len(missing_mem)))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
