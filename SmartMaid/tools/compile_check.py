#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""compile_check.py —— 不用 Loom / Gradle，直接用 javac 编译整个工程（多版本兼容专用）。

为什么需要它：
  Minecraft **26.1 起已完全去混淆**，源码里的类名就是官方名，因此**不需要 mappings 重映射**。
  于是可以用普通 javac 对着「目标版本的原版 jar + Fabric API jar + libs」直接编译。
  好处：**绕开 Gradle/Loom 的依赖下载**（国内常卡在 Maven Central 的 netty POM 上，
  见 DESIGN_MULTIVERSION.md §10.1），几秒内拿到**完整、权威的编译报错清单** ——
  这比"人工挑类做 diff"更彻底，也避免遗漏。

它解决什么问题：
  多版本兼容的第一步是量出"真实需要改哪些地方"。让**编译器**枚举，而不是靠文档或人工猜。

用法：
  # 用某版本的原版 jar + 对应 Fabric API 编译
  python tools/compile_check.py --mc-jar <26.3.jar> --fapi-jar <fabric-api-0.161.0+26.3.jar>

  # 指定额外依赖目录（MC 的 libraries：slf4j / gson / brigadier 等）
  python tools/compile_check.py --mc-jar ... --fapi-jar ... --libs-dir <minecraft>/libraries

  # 只看前 N 条错误
  python tools/compile_check.py ... --max-errors 40

Fabric API 的聚合 jar 内部是嵌套 jar（META-INF/jars/*.jar），javac 读不到，
本脚本会自动解压到临时目录后再加入 classpath。

⚠️ **已知误报来源（务必先读）**
Fabric API 用 **access widener / class tweaker** 开放了一批 vanilla 成员给模组使用
（例：`MenuType.<init>(MenuSupplier, FeatureFlagSet)`、`MenuScreens.register(...)`、
`BlockPlaceContext.<init>(Level, Player, InteractionHand, ItemStack, BlockHitResult)`）。
这些成员在 vanilla jar 里是 `private` / `protected`，**javac 不会应用 classtweaker**，
于是会报成 `has private access` / `has protected access` —— 这是**假错误**，Loom 真实构建时不会报。

判定方法：报错若形如 `X has private access` / `has protected access`，
去 Fabric API 的 `*.classtweaker` 里搜该成员（例：`fabric-menu-api-v1.classtweaker`、
`fabric-transitive-access-wideners-v1.classtweaker`）——搜到就是误报。
其余错误（`cannot find symbol` / `no suitable method found for` / `cannot be applied to given types`）
基本都是**真错误**。
"""
from __future__ import annotations

import argparse
import glob
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, HERE)

from _paths import find_jdk_tool as _find_jdk_tool  # noqa: E402


def _win(p: str) -> str:
    r"""MSYS 风格路径 /c/Users/x → C:/Users/x（Windows 的 javac 不认前者）。"""
    m = re.match(r"^/([a-zA-Z])/(.*)$", p)
    return "%s:/%s" % (m.group(1).upper(), m.group(2)) if m else p


def find_jdk_tool(name: str, override: str | None = None) -> str | None:
    """定位 JDK 工具（javac）。实现已挪到 _paths.py 与其它脚本共用。"""
    return _find_jdk_tool(name, override)


def extract_nested_jars(agg_jar: str) -> str | None:
    """把 fabric-api 聚合 jar 里的嵌套模块解压到临时目录，返回目录。"""
    out = os.path.join(tempfile.gettempdir(), "cc_fapi_" + os.path.basename(agg_jar)[:-4].replace("+", "_"))
    if os.path.isdir(out) and any(f.endswith(".class") for _, _, fs in os.walk(out) for f in fs):
        return out
    os.makedirs(out, exist_ok=True)
    n = 0
    with zipfile.ZipFile(agg_jar) as z:
        for name in z.namelist():
            if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(z.read(name))) as inner:
                    inner.extractall(out)
                n += 1
    if not n:                      # 不是聚合 jar（普通 jar）→ 直接用原文件
        shutil.rmtree(out, ignore_errors=True)
        return None
    return out


def package_jar(out_path: str, classes_dir: str, resources_dir: str,
                mod_version: str, mc_range: str, fapi_range: str, target_mc: str,
                pal_range: str, include_jars: list[str], manifest_from: str | None = None) -> str:
    """把 javac 的产物 + 资源打成一个**可直接放进 mods/ 的 Fabric jar**。

    26.1+ 去混淆 → 不需要 remap，所以「javac 编译 + 照结构装包」就等价于 Loom 的产出。
    这让我们在 Gradle 依赖不通时也能做出真机测试包。

    jar 结构与 Loom 产物保持一致：
      fabric.mod.json（占位符已替换）/ 类 / assets / data
      META-INF/{MANIFEST.MF, LICENSE, NOTICE.md}
      META-INF/jars/<jar-in-jar 依赖>
    """
    out_path = _win(out_path)
    classes_dir = _win(classes_dir)
    resources_dir = _win(resources_dir)
    parent = os.path.dirname(out_path)
    if parent:
        os.makedirs(parent, exist_ok=True)

    manifest = None
    if manifest_from and os.path.isfile(_win(manifest_from)):
        try:
            with zipfile.ZipFile(_win(manifest_from)) as z:
                manifest = z.read("META-INF/MANIFEST.MF")
        except Exception:
            pass

    repo_root = os.path.abspath(os.path.join(REPO, ".."))
    subs = {"${version}": mod_version, "${minecraft_range}": mc_range,
            "${fabric_api_range}": fapi_range, "${target_mc}": target_mc,
            "${pal_range}": pal_range}

    n_cls = n_res = 0
    jars = [(j, "META-INF/jars/" + os.path.basename(_win(j)))
            for j in include_jars if os.path.isfile(_win(j))]

    def _sub(v):
        if isinstance(v, str):
            for k, s in subs.items():
                v = v.replace(k, s)
            return v
        if isinstance(v, dict):
            return {k: _sub(x) for k, x in v.items()}
        if isinstance(v, list):
            return [_sub(x) for x in v]
        return v

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF",
                   manifest if manifest else b"Manifest-Version: 1.0\nCreated-By: SmartMaid compile_check.py\n")
        if os.path.isdir(resources_dir):
            for r, _, fs in os.walk(resources_dir):
                for f in fs:
                    p = os.path.join(r, f)
                    arc = os.path.relpath(p, resources_dir).replace(os.sep, "/")
                    data = open(p, "rb").read()
                    if arc == "fabric.mod.json":
                        # ⚠️ 关键：源码里的 fabric.mod.json **不含 `jars` 数组** ——
                        #    Loom 打包时才会把 include 的 jar-in-jar 写进去。
                        #    漏了它 → 嵌套 jar 躺在包里但 Fabric 不加载 →
                        #    加载器报「需要 player_animation_library 但没有安装它！」（实测踩到）
                        obj = _sub(json.loads(data.decode("utf-8")))
                        if jars:
                            obj["jars"] = [{"file": arc2} for _p, arc2 in jars]
                        data = json.dumps(obj, ensure_ascii=False, indent=2).encode("utf-8")
                    z.writestr(arc, data)
                    n_res += 1
        for r, _, fs in os.walk(classes_dir):
            for f in fs:
                if f.endswith(".class"):
                    p = os.path.join(r, f)
                    z.write(p, os.path.relpath(p, classes_dir).replace(os.sep, "/"))
                    n_cls += 1
        for src, arc in ((os.path.join(repo_root, "LICENSE"), "META-INF/LICENSE"),
                         (os.path.join(repo_root, "NOTICE.md"), "META-INF/NOTICE.md")):
            if os.path.isfile(src):
                z.write(src, arc)
        for jp, arc in jars:
            z.write(jp, arc)

    print("\n📦 已打包: %s（class %d / 资源 %d / jar-in-jar %d）"
          % (out_path, n_cls, n_res, len(jars)))
    if jars:
        print("   ↳ fabric.mod.json 的 jars: %s" % ", ".join(a for _p, a in jars))
    return out_path


def load_ct_entries(agg_jar: str) -> list[tuple[str, str, str | None, str | None]]:
    """读出 Fabric API 的 classtweaker / access widener 条目（完整形式）。

    返回 [(kind, owner 内部名, 成员名或 None, 描述符或 None)]，kind ∈ {class, method, field}。
    """
    out: list[tuple[str, str, str | None, str | None]] = []

    def scan(blob: bytes) -> None:
        for ln in blob.decode("utf-8", "replace").splitlines():
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            parts = ln.split()
            if len(parts) < 3:
                continue
            kind, owner = parts[1], parts[2]
            if kind not in ("class", "method", "field"):
                continue
            name = parts[3] if len(parts) > 3 else None
            desc = parts[4] if len(parts) > 4 else None
            out.append((kind, owner, name, desc))

    if agg_jar and os.path.isfile(agg_jar):
        with zipfile.ZipFile(agg_jar) as z:
            for n in z.namelist():
                if n.endswith(".classtweaker") or n.endswith(".accesswidener"):
                    scan(z.read(n))
                elif n.startswith("META-INF/jars/") and n.endswith(".jar"):
                    with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                        for m in inner.namelist():
                            if m.endswith(".classtweaker") or m.endswith(".accesswidener"):
                                scan(inner.read(m))
    return out


# ---------- 极简 class 文件「访问标志」改写（等价于 Loom 应用 classtweaker）----------
_ACC_PUBLIC = 0x0001
_ACC_MASK = 0x0007


def _read_cp(data: bytes):
    """解析常量池，返回 (utf8, cls_idx, nat, cp_end)。"""
    off = 8
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    utf8: dict[int, str] = {}
    cls_idx: dict[int, int] = {}
    nat: dict[int, tuple[int, int]] = {}
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, off)
            off += 2
            utf8[i] = data[off:off + ln].decode("utf-8", "replace")
            off += ln
        elif tag in (3, 4):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        elif tag == 7:
            cls_idx[i] = struct.unpack_from(">H", data, off)[0]
            off += 2
        elif tag == 8:
            off += 2
        elif tag in (9, 10, 11, 12):
            nat[i] = struct.unpack_from(">HH", data, off)
            off += 4
        elif tag == 15:
            off += 3
        elif tag in (16, 19, 20):
            off += 2
        elif tag in (17, 18):
            off += 4
        else:
            return utf8, cls_idx, nat, None
        i += 1
    return utf8, cls_idx, nat, off


def _skip_attrs(data: bytes, off: int, extra_u2: tuple[int, ...] = ()):
    n = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(n):
        off += 2
        ln = struct.unpack_from(">I", data, off)[0]
        off += 4 + ln
    return off


def _acc_table(data: bytes, cp_end: int, utf8: dict) -> tuple[dict, int]:
    """解析 fields + methods，返回 ({(名字, 描述符): access_flags 偏移}, 类属性区起点)。"""
    off = cp_end + 2 + 4                       # access_flags, this_class, super_class
    n_if = struct.unpack_from(">H", data, off)[0]
    off += 2 + 2 * n_if
    table = {}
    for _section in range(2):                  # fields, methods
        n = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(n):
            acc = off
            ni, di = struct.unpack_from(">HH", data, off + 2)
            table[(utf8.get(ni, ""), utf8.get(di, ""))] = acc
            # ⚠️ attributes_count 在 acc+6；_skip_attrs 自己会读它。
            #    不能先 off += 8 再调 _skip_attrs —— 那会让它把下一个成员的
            #    access_flags 当成 attributes_count 读，偏移直接跑飞。
            off = _skip_attrs(data, acc + 6)
    return table, off


def _inner_class_offsets(data: bytes, off: int, inner_classes_attr_name: int):
    """从类属性区解析 InnerClasses 属性，返回 {内部名索引: access_flags 偏移}。"""
    res = {}
    n = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(n):
        ni = struct.unpack_from(">H", data, off)[0]
        ln = struct.unpack_from(">I", data, off + 2)[0]
        dstart = off + 6
        if ni == inner_classes_attr_name:
            k = struct.unpack_from(">H", data, dstart)[0]
            q = dstart + 2
            for _ in range(k):
                inner_i = struct.unpack_from(">H", data, q)[0]
                res[inner_i] = q + 6          # inner_class_access_flags 的偏移
                q += 8
        off = dstart + ln
    return res


def build_ct_delta(mc_jar: str, entries, owners: set[str], out_jar: str):
    """按 classtweaker 条目把「开放了访问权限的类 / 成员」改成 public，产出**编译用补丁 jar**。

    把它放在 classpath **最前面**，即可覆盖原版 jar 里的同名类（javac 取第一个匹配）。
    **不要**在运行时用它 —— Fabric 自己会应用 classtweaker；这里只是复刻 Loom 准备
    编译 classpath 时做的事。返回 (补丁 jar 路径 或 None, 改动处数)。
    """
    want_class = set()
    want_member = {}

    def _matches(owner: str) -> bool:
        """owner 是否命中「javac 报访问错的那个类」。

        ⚠️ 嵌套类型要按**外层类名**匹配：javac 对 `MenuScreens$ScreenConstructor`
        报的是 `ScreenConstructor has private access in MenuScreens`，
        我们从消息里只能抽出 `MenuScreens`；若按简单名严格匹配就会漏掉嵌套类型。
        """
        base = owner.split("/")[-1]
        if "$" in base:
            return base.split("$")[0] in owners
        return base in owners

    for kind, owner, name, desc in entries:
        if not _matches(owner):
            continue
        if kind == "class":
            want_class.add(owner)
        elif name:
            want_member.setdefault(owner, set()).add((name, desc or ""))

    targets = sorted(want_class | set(want_member))
    if not targets:
        return None, 0

    parent = os.path.dirname(out_jar)
    if parent:
        os.makedirs(parent, exist_ok=True)
    patched = 0
    with zipfile.ZipFile(mc_jar) as zin, zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        names = set(zin.namelist())
        for owner in targets:
            entry = owner + ".class"
            if entry not in names:
                continue
            data = bytearray(zin.read(entry))
            utf8, cls_idx, _nat, cp_end = _read_cp(data)
            if cp_end is None:
                continue
            changed = False
            if owner in want_class:
                f = struct.unpack_from(">H", data, cp_end)[0]
                struct.pack_into(">H", data, cp_end, (f & ~_ACC_MASK) | _ACC_PUBLIC)
                changed = True
                patched += 1
            table, attr_off = _acc_table(data, cp_end, utf8)
            for key in want_member.get(owner, ()):
                if key in table:
                    a = table[key]
                    f = struct.unpack_from(">H", data, a)[0]
                    struct.pack_into(">H", data, a, (f & ~_ACC_MASK) | _ACC_PUBLIC)
                    changed = True
                    patched += 1
            # 嵌套类型的可访问性由**外层类**的 InnerClasses 属性决定，必须一并改
            inner_name_i = next((i for i, u in utf8.items() if u == "InnerClasses"), None)
            if inner_name_i is not None and want_class:
                for ii, acc_off in _inner_class_offsets(data, attr_off, inner_name_i).items():
                    if utf8.get(cls_idx.get(ii, -1), "") in want_class:
                        f = struct.unpack_from(">H", data, acc_off)[0]
                        struct.pack_into(">H", data, acc_off, (f & ~_ACC_MASK) | _ACC_PUBLIC)
                        changed = True
                        patched += 1
            if changed:
                zout.writestr(entry, bytes(data))
    return out_jar, patched


def load_classtweakers(agg_jar: str) -> set:
    """{(所有者简单名, 成员名或 '*')}：供「javac 访问报错是不是 CT 误报」判定用。"""
    out = set()
    for kind, owner, name, _desc in load_ct_entries(agg_jar):
        simple = owner.split("/")[-1].split("$")[-1]
        if kind == "class" or not name:
            out.add((simple, "*"))
        elif name == "<init>":
            out.add((simple, simple))       # javac 的消息里构造器写作类名
        else:
            out.add((simple, name))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="不用 Loom，直接用 javac 编译工程")
    ap.add_argument("--mc-jar", required=True, help="目标版本的原版 jar（如 <.minecraft>/versions/26.3/26.3.jar）")
    ap.add_argument("--fapi-jar", required=True, help="对应版本的 Fabric API 聚合 jar")
    ap.add_argument("--src", action="append",
                    help="源码根目录，可重复（多版本时传 src/main/java 与 src/mc<series>/java）")
    ap.add_argument("--libs", default=os.path.join(REPO, "libs"), help="本地依赖目录（PAL / mocha 等）")
    ap.add_argument("--libs-dir", action="append", default=[],
                    help="额外依赖目录（如 <minecraft>/libraries），可重复；会递归收集 *.jar")
    ap.add_argument("--out", default=None, help="class 输出目录（默认临时目录）")
    ap.add_argument("--javac", help="指定 javac")
    ap.add_argument("--max-errors", type=int, default=200, help="最多打印多少条错误")
    # ---- 可选：编译通过后打包成可放进 mods/ 的 jar ----
    ap.add_argument("--package", metavar="OUT.jar", help="编译通过后打包（26.1+ 去混淆，无需 remap）")
    ap.add_argument("--resources", default=os.path.join(REPO, "src", "main", "resources"),
                    help="资源目录（assets/ data/ fabric.mod.json）")
    ap.add_argument("--include-jar", action="append", default=None,
                    help="jar-in-jar 依赖，可重复（默认 libs/ 下全部 jar）")
    ap.add_argument("--mod-version", default=None, help="版本号（默认读 gradle.properties 的 mod_version）")
    ap.add_argument("--mc-version", default=None, help="目标 MC 版本（默认从 --mc-jar 文件名推断）")
    ap.add_argument("--mc-range", default=None, help="fabric.mod.json 的 minecraft 范围（默认 ~<mc-version>）")
    ap.add_argument("--fapi-range", default="*", help="fabric.mod.json 的 fabric-api 范围（测试包默认 *）")
    ap.add_argument("--pal-range", default="*",
                    help="fabric.mod.json 的 player_animation_library 范围（测试包默认 *）")
    ap.add_argument("--manifest-from", default=None, help="从该 jar 复制 MANIFEST.MF（默认取 build/libs 里最新的）")
    args = ap.parse_args()

    javac = find_jdk_tool("javac", args.javac)
    if not javac or not os.path.isfile(javac):
        print("!! 找不到 javac", file=sys.stderr)
        return 2

    mc = _win(args.mc_jar)
    fapi = _win(args.fapi_jar)
    for p, n in ((mc, "--mc-jar"), (fapi, "--fapi-jar")):
        if not os.path.isfile(p):
            print("!! %s 不存在：%s" % (n, p), file=sys.stderr)
            return 2

    # ---- classpath ----
    cp = [mc]
    ex = extract_nested_jars(fapi)
    cp.append(ex if ex else fapi)
    libs_dir = _win(args.libs)
    if os.path.isdir(libs_dir):
        cp += sorted(glob.glob(os.path.join(libs_dir, "*.jar")))
    for d in args.libs_dir:
        d = _win(d)
        cp += sorted(glob.glob(os.path.join(d, "**", "*.jar"), recursive=True))
    cp = [c for c in cp if os.path.exists(c)]

    # ---- 源码 ----
    src_roots = [_win(s) for s in (args.src or [os.path.join(REPO, "src", "main", "java")])]
    for r in src_roots:
        if not os.path.isdir(r):
            print("!! 源码目录不存在：%s" % r, file=sys.stderr)
            return 2
    # 按「相对路径」去重，**靠后的源码目录覆盖前面的同名类**
    # （与 Gradle sourceSet 的 srcDirs 顺序语义一致：版本专属目录要排在共享目录之后）
    by_rel: dict[str, str] = {}
    for root in src_roots:
        for r, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(".java"):
                    p = os.path.join(r, f)
                    by_rel[os.path.relpath(p, root)] = p
    sources = sorted(by_rel.values())
    if not sources:
        print("!! 没找到 .java 源文件（%s）" % ", ".join(src_roots), file=sys.stderr)
        return 2

    out_dir = _win(args.out) if args.out else os.path.join(tempfile.gettempdir(), "cc_out")
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir, ignore_errors=True)   # 清掉上一轮残留，免把陈旧 .class 打进包
    os.makedirs(out_dir, exist_ok=True)

    # ---- 打包参数（仅 --package 时使用；先算好便于回显）----
    _vm = re.search(r"(\d+\.\d+(?:\.\d+)?)", os.path.basename(mc))
    mc_version = args.mc_version or (_vm.group(1) if _vm else "26.2")
    mc_range = args.mc_range or ("~" + mc_version)
    mod_version = args.mod_version
    if not mod_version:
        try:
            with open(os.path.join(REPO, "gradle.properties"), encoding="utf-8") as gp:
                for ln in gp:
                    if ln.strip().startswith("mod_version="):
                        mod_version = ln.split("=", 1)[1].strip()
                        break
        except Exception:
            pass
    mod_version = mod_version or "0.1.2-dev"
    include_jars = args.include_jar or sorted(glob.glob(os.path.join(_win(args.libs), "*.jar")))
    manifest_from = args.manifest_from
    if not manifest_from:
        cands = [p for p in sorted(glob.glob(os.path.join(REPO, "build", "libs", "smartmaid-*.jar")))
                 if not p.endswith("-sources.jar")]
        manifest_from = cands[-1] if cands else None

    # ---- 编译 ----
    # 两趟：先直接编；若报出「classtweaker 打开的成员」的访问错，就按 CT 生成补丁 jar
    # 放到 classpath 最前面再编一次 —— 复刻 Loom 准备编译 classpath 时做的事。
    # ⚠️ 为什么必须消掉这些错：**javac 遇到错误会中止**，class 文件只产出中止前已生成的
    #    那几个（实测 126 个源文件只出 1 个 class）→ 拿不到可打包的完整产物。
    print("# javac : %s" % javac)
    print("# mc    : %s" % mc)
    print("# fapi  : %s" % fapi)
    print("# libs  : %d 个 jar（含 --libs-dir 递归）" % len(cp))
    print("# 源码  : %d 个 .java（%d 个源码目录）" % (len(sources), len(src_roots)))
    print("=" * 72)

    ct_entries = load_ct_entries(fapi)
    ct = load_classtweakers(fapi)
    ct_owners = {o for o, _ in ct}

    _FILELINE = re.compile(r"\.java:\d+:")
    _MSG = re.compile(r"(error|错误)[:：]")
    _PATH = re.compile(r"^(.*?\.java):\d+:")

    def _fs(x: str) -> str:
        return x.replace("\\", "/")

    def run_javac(extra_first=None):
        cp2 = ([extra_first] if extra_first else []) + cp
        argfile = os.path.join(tempfile.gettempdir(), "cc_args.txt")
        with open(argfile, "w", encoding="utf-8") as f:
            f.write('-d "%s"\n' % _fs(out_dir))
            f.write('-encoding UTF-8\n-nowarn\n-proc:none\n')
            f.write('-cp "%s"\n' % ";".join(_fs(c) for c in cp2))
            for s in sources:
                f.write('"%s"\n' % _fs(s))
        # ⚠️ -J（传给 JVM 的参数，用于强制英文诊断）**只能写在真实命令行上**，
        #    写进 @argfile 会被判为「无效的标记」。中文 locale 下输出「错误: …」既难解析也乱码。
        rr = subprocess.run([javac, "-J-Duser.language=en", "-J-Duser.country=US", "@" + argfile],
                            capture_output=True, text=True, errors="replace")
        return [ln for ln in ((rr.stdout or "") + (rr.stderr or "")).splitlines() if ln.strip()]

    def to_blocks(ls):
        bs = []
        for ln in ls:
            if _FILELINE.search(ln) and _MSG.search(ln):
                bs.append({"head": ln, "detail": []})
            elif bs:
                bs[-1]["detail"].append(ln)
        return bs

    def access_owners(bs):
        """从错误块里抽出「哪个类的成员访问不到」。"""
        own = set()
        for b in bs:
            joined = " ".join([b["head"]] + b["detail"])
            if "private access" not in joined and "protected access" not in joined:
                continue
            for m in re.finditer(r"in (?:class )?([A-Za-z0-9_$.]+)", joined):
                own.add(m.group(1).split(".")[-1].split("$")[-1])
        return own

    lines = run_javac(None)
    blocks = to_blocks(lines)
    _need = {o for o in access_owners(blocks) if o in ct_owners}
    if _need:
        _delta = os.path.join(tempfile.gettempdir(), "cc_ct_delta.jar")
        _djar, _np = build_ct_delta(mc, ct_entries, _need, _delta)
        if _djar:
            print("# classtweaker: 生成编译用补丁 jar（%s，改动 %d 处）" % ("、".join(sorted(_need)), _np))
            lines = run_javac(_djar)
            blocks = to_blocks(lines)

    def is_ct_false_positive(b: dict) -> bool:
        """该错误块是否只是「Fabric classtweaker 打开的 vanilla 成员」被 javac 误判。

        注意要看**整块**：`cannot be applied to given types` 这类主错误行本身不提到访问权限，
        真正的原因写在紧跟的 `reason: ... has protected access in X` 里。
        """
        joined = " ".join([b["head"]] + b["detail"])
        if "private access" not in joined and "protected access" not in joined:
            return False
        m = re.search(r"in (?:class )?([A-Za-z0-9_$.]+)", joined)
        if not m:
            return False
        owner = m.group(1).split(".")[-1].split("$")[-1]
        return owner in ct_owners

    real = [b for b in blocks if not is_ct_false_positive(b)]
    fp = [b for b in blocks if is_ct_false_positive(b)]

    def summarize(bs: list[dict]) -> dict[str, int]:
        k: dict[str, int] = {}
        for b in bs:
            parts = re.split(r"(?:error|错误)[:：]", b["head"], maxsplit=1)
            if len(parts) == 2:
                msg = parts[1].strip()
                k[msg] = k.get(msg, 0) + 1
        return k

    print("# classtweaker 开放项: %d 条（来自 fabric-api 的 *.classtweaker/*.accesswidener）" % len(ct))
    if fp:
        print("ℹ️ 已识别 %d 个 classtweaker 误报（javac 不应用 AW/CT，Loom 真实构建不会报），不计入失败：" % len(fp))
        for msg, c in sorted(summarize(fp).items(), key=lambda kv: -kv[1]):
            print("  %4d × %s" % (c, msg))

    if not real:
        print("\n✅ 编译通过：**0 个真实错误**。")
        if fp:
            print("   （另有 %d 个 classtweaker 误报已按上文列出并忽略）" % len(fp))
        if args.package:
            package_jar(args.package, out_dir, _win(args.resources), mod_version,
                        mc_range, args.fapi_range, mc_version, args.pal_range, include_jars, manifest_from)
        return 0

    real_files = sorted({m.group(1) for b in real if (m := _PATH.match(b["head"]))})
    print("\n❌ %d 个**真实**错误，涉及 %d 个文件\n" % (len(real), len(real_files)))
    print("---- 按错误类型汇总 ----")
    for msg, c in sorted(summarize(real).items(), key=lambda kv: -kv[1]):
        print("  %4d × %s" % (c, msg))
    print("\n---- 涉及文件 ----")
    for f in real_files:
        print("   ", f.replace("\\", "/").split("/SmartMaid/")[-1])
    print("\n---- 明细（最多 %d 行）----" % (args.max_errors * 3))
    shown = 0
    for b in real:
        for ln in [b["head"]] + b["detail"]:
            if shown >= args.max_errors * 3:
                break
            print("   ", ln.replace("\\", "/").split("/SmartMaid/")[-1])
            shown += 1
        if shown >= args.max_errors * 3:
            break
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
