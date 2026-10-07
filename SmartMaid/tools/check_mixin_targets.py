#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mixin 注入目标核验 —— 静态检查某个 mod 的 Mixin 能不能在目标 MC 版本上应用。

为什么需要它（真实教训，2026-10-06）
------------------------------------
`check_dep_compat.py` 只看**常量池里的类型/成员引用**，而 Mixin 的注入信息
（`@Mixin 目标类`、`@Inject(method=…)`、`@At(target="Lowner;name(desc)ret")`）都是
**注解里的字符串**。于是那一轮静态核验给出「PAL 在 26.3 只差 2 个成员」的结论，
**漏掉了致命的一项**：PAL 1.2.6 的 `ItemInHandLayerMixin` 在 26.3 上 `Scanned 0 target(s)`，
真机表现是**启动即崩**（`required=true` + `injectors.defaultRequire=1`）。

本脚本做两件事，并**区分严重级别**：
  1. `@Mixin` 的目标类是否存在
       → 缺失时 Mixin **只打 WARN 并跳过该 mixin**，不致命（实测日志确认）。
  2. `@At` / `@Redirect` 里的 `Lowner;name(desc)ret` 成员是否能在目标类（含父类链）找到
       → 缺失时注入失败；若该 mixin 配置 `required=true` 或 `injectors.defaultRequire>=1`，
         则**致命**（启动即崩）—— 这就是 1.2.6 的崩溃形态。

⚠️ 局限（必须如实说）
  * 只做**静态**核对：不能证明注入点在方法体内真的命中
    （`@At` 的 ordinal、指令类型、方法体是否被改写过，都要运行期由 Mixin 判定）；
  * 结论是「**没发现已知断点**」，不是「一定能跑」。真机仍需验证。

用法
----
  python tools/check_mixin_targets.py \\
      --mod-jar libs/mc26.3/player_animation_library-1.2.7.jar \\
      --mc-jar "<.minecraft>/versions/26.3/26.3.jar" \\
      --libs-dir "<.minecraft>/libraries"
"""
from __future__ import annotations

import argparse
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _paths import find_jdk_tool  # noqa: E402


# ==========================================================================
# 1. 最小 class 文件解析（常量池 + 类级注解）
# ==========================================================================
class ClassFile:
    """只解析本工具需要的部分：常量池、类级注解、类属性区起点。

    ⚠️ 别用「扫一遍 Utf8 串」的偷懒办法抽 `@Mixin` 目标 —— 那样会把方法描述符等
    无关的 `L…;` 串也当成目标（本工具第一版就是这么误报的：把指向已删类的
    `ItemInHandRendererMixin` 判成「可解析」）。注解有确定的结构，按结构读才准。
    """

    def __init__(self, data: bytes):
        self.data = data
        self.cp: list[tuple[str, object] | None] = [None]
        self.valid = False
        self.class_annotations: list[tuple[str, dict]] = []
        if data[:4] != b"\xca\xfe\xba\xbe":
            return
        self.valid = True
        self._read_cp()
        self._read_class_annotations()

    # -- 常量池 --
    def _read_cp(self) -> None:
        d = self.data
        (cnt,) = struct.unpack_from(">H", d, 8)
        off = 10
        i = 1
        while i < cnt:
            (tag,) = struct.unpack_from(">B", d, off)
            off += 1
            if tag == 1:
                (ln,) = struct.unpack_from(">H", d, off)
                self.cp.append(("utf8", d[off + 2:off + 2 + ln].decode("utf-8", "replace")))
                off += 2 + ln
            elif tag in (7, 8, 16, 19, 20):
                (v,) = struct.unpack_from(">H", d, off)
                self.cp.append(({7: "class", 8: "string", 16: "mtype",
                                 19: "module", 20: "package"}[tag], v))
                off += 2
            elif tag == 15:
                (k, r) = struct.unpack_from(">BH", d, off)
                self.cp.append(("mhandle", (k, r)))
                off += 3
            elif tag == 12:
                (a, b) = struct.unpack_from(">HH", d, off)
                self.cp.append(("nat", (a, b)))
                off += 4
            elif tag in (9, 10, 11, 17, 18):
                (a, b) = struct.unpack_from(">HH", d, off)
                self.cp.append(({9: "fieldref", 10: "methodref", 11: "imethodref",
                                 17: "dynamic", 18: "indy"}[tag], (a, b)))
                off += 4
            elif tag in (3, 4):
                (v,) = struct.unpack_from(">I", d, off)
                self.cp.append(("int" if tag == 3 else "float", v))
                off += 4
            elif tag in (5, 6):
                (v,) = struct.unpack_from(">Q", d, off)
                self.cp.append(("long" if tag == 5 else "double", v))
                off += 8
                self.cp.append(None)
                i += 1
            else:
                self.valid = False
                return
            i += 1
        self.cp_end = off

    def utf8(self, idx: int) -> str | None:
        return self._payload(idx, "utf8")

    def class_name(self, idx: int) -> str | None:
        """CONSTANT_Class → 内部名（已去掉 `L…;` 包裹）。"""
        v = self._payload(idx, "class")
        if not isinstance(v, int):
            return None
        name = self.utf8(v)
        if name and name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        return name

    def _payload(self, idx, tag):
        if not (0 < idx < len(self.cp)):
            return None
        e = self.cp[idx]
        return e[1] if e and e[0] == tag else None

    # -- 属性区 --
    def _skip_attrs(self, off: int) -> int:
        (n,) = struct.unpack_from(">H", self.data, off)
        off += 2
        for _ in range(n):
            (_, ln) = struct.unpack_from(">HI", self.data, off)
            off += 6 + ln
        return off

    def _read_class_annotations(self) -> None:
        d = self.data
        off = self.cp_end
        off += 6                                   # access_flags, this_class, super_class
        (n_if,) = struct.unpack_from(">H", d, off)
        off += 2 + 2 * n_if
        for _ in range(2):                         # fields, methods
            (n,) = struct.unpack_from(">H", d, off)
            off += 2
            for _ in range(n):
                off += 6
                off = self._skip_attrs(off)
        (n_attr,) = struct.unpack_from(">H", d, off)
        off += 2
        for _ in range(n_attr):
            (name_i, ln) = struct.unpack_from(">HI", d, off)
            body = off + 6
            nm = self.utf8(name_i)
            if nm in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                self.class_annotations += self._parse_annotations(body)
            off = body + ln

    def _parse_annotations(self, off: int) -> list[tuple[str, dict]]:
        (n,) = struct.unpack_from(">H", self.data, off)
        off += 2
        out = []
        for _ in range(n):
            off, a = self._parse_annotation(off)
            if a:
                out.append(a)
        return out

    def _parse_annotation(self, off: int, depth: int = 0):
        d = self.data
        (ti,) = struct.unpack_from(">H", d, off)
        (npair,) = struct.unpack_from(">H", d, off + 2)
        off += 4
        elems: dict[str, object] = {}
        for _ in range(npair):
            (ni,) = struct.unpack_from(">H", d, off)
            off += 2
            off, val = self._parse_element_value(off, depth)
            nm = self.utf8(ni)
            if nm:
                elems[nm] = val
        return off, (self.utf8(ti) or "", elems)

    def _parse_element_value(self, off: int, depth: int = 0):
        d = self.data
        (tag,) = struct.unpack_from(">B", d, off)
        off += 1
        c = chr(tag)
        if c == "c":                                        # Class
            (ci,) = struct.unpack_from(">H", d, off)
            return off + 2, ("class", self.class_name_any(ci))
        if c == "s":                                        # String
            (si,) = struct.unpack_from(">H", d, off)
            return off + 2, ("str", self.utf8(si))
        if c == "[":
            (n,) = struct.unpack_from(">H", d, off)
            off += 2
            vals = []
            for _ in range(n):
                off, v = self._parse_element_value(off, depth + 1)
                vals.append(v)
            return off, ("array", vals)
        if c == "@":
            if depth > 6:
                return off, None
            return self._parse_annotation(off, depth + 1)
        if c == "e":                                        # enum
            (t, v) = struct.unpack_from(">HH", d, off)
            return off + 4, ("enum", self.utf8(v))
        if c in "BCDFIJSZ":
            (v,) = struct.unpack_from(">H", d, off)
            return off + 2, ("const", v)
        return off, None

    def class_name_any(self, idx: int) -> str | None:
        """注解里的 class 常量可能落在 Utf8 上（`Lx/y/Z;`）而非 CONSTANT_Class。"""
        u = self.utf8(idx)
        if u:
            return u[1:-1] if u.startswith("L") and u.endswith(";") else u
        return self.class_name(idx)


# ==========================================================================
# 2. 目标版本索引（类名 + 成员签名，按需 javap）
# ==========================================================================
def parse_signatures(text: str) -> set[str]:
    """`javap -p -s` 输出 → {'名字(参数)返回', '字段:描述符'}。"""
    out: set[str] = set()
    lines = text.splitlines()
    for idx, ln in enumerate(lines):
        desc = None
        for j in range(idx + 1, min(idx + 3, len(lines))):
            m = re.match(r"\s*descriptor:\s*(\S+)", lines[j])
            if m:
                desc = m.group(1)
                break
        if not desc:
            continue
        m = re.match(r"\s*[\w$.<>,\[\] ]*?([\w$<>]+)\s*\([^)]*\)\s*;", ln)
        if m:
            out.add(m.group(1) + desc)
            continue
        m = re.match(r"\s*[\w$.<>,\[\]]+\s+([\w$]+)\s*;", ln)
        if m:
            out.add(m.group(1) + ":" + desc)
    return out


class McIndex:
    """类存在性 + 成员签名索引。

    ⚠️ 存在性判定**只允许来自目标 MC jar**。实测踩到的坑：`.minecraft/libraries`
    里可能躺着**别的 MC 版本**的客户端 jar（本例是 HMCL 装的 NeoForge 21.1.51 里的
    `client-1.21.1-srg.jar`，含 `net/minecraft/client/renderer/ItemInHandRenderer`）。
    若把 libraries 整目录当索引，就会把「26.3 已删除的类」判成「存在」——本工具第一版
    正是这样把真正会崩的 `ItemInHandRendererMixin` 误报成「可解析」。
    `--libs-dir` 只用于**给 javap 解析第三方类**（如 org.joml），不参与存在性判定。
    """

    def __init__(self, mc_jar: str, extras: list[str], javap: str):
        self.javap = javap
        self.classes: set[str] = set()          # 仅目标 MC jar → 存在性判定
        self.owner: dict[str, str] = {}         # javap 用，MC jar 优先
        self._members: dict[str, set[str] | None] = {}
        self._parents: dict[str, tuple[str | None, list[str]]] = {}
        self._index_jar(os.path.join(mc_jar), authoritative=True)
        for j in extras:
            if os.path.isfile(j):
                self._index_jar(j)
            elif os.path.isdir(j):
                for r, _, fs in os.walk(j):
                    for f in fs:
                        if f.endswith(".jar"):
                            self._index_jar(os.path.join(r, f))

    def _index_jar(self, path: str, authoritative: bool = False) -> None:
        try:
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if not n.endswith(".class"):
                        continue
                    self.owner.setdefault(n[:-6], path)
                    if authoritative:
                        self.classes.add(n[:-6])
        except Exception:
            pass

    def has(self, internal: str) -> bool:
        return internal in self.classes

    def members(self, internal: str) -> set[str] | None:
        if internal not in self._members:
            jar = self.owner.get(internal)
            res: set[str] | None = None
            if jar:
                r = subprocess.run([self.javap, "-p", "-s", "-classpath", jar,
                                    internal.replace("/", ".")],
                                   capture_output=True, text=True, errors="replace")
                res = parse_signatures((r.stdout or "") + (r.stderr or ""))
            self._members[internal] = res
        return self._members[internal]

    def parents(self, internal: str) -> list[str]:
        if internal not in self._parents:
            jar = self.owner.get(internal)
            sup, ifs = None, []
            if jar:
                r = subprocess.run([self.javap, "-p", "-classpath", jar,
                                    internal.replace("/", ".")],
                                   capture_output=True, text=True, errors="replace")
                txt = (r.stdout or "") + (r.stderr or "")
                m = re.search(r"\bextends\s+([\w.$]+)", txt)
                if m:
                    sup = m.group(1).replace(".", "/")
                m = re.search(r"\bimplements\s+([\w.$,\s]+?)\s*\{", txt)
                if m:
                    ifs = [x.strip().replace(".", "/")
                           for x in m.group(1).split(",") if x.strip()]
            self._parents[internal] = (sup, ifs)
        sup, ifs = self._parents[internal]
        return ([sup] if sup else []) + ifs

    def find_member(self, internal: str, name: str, desc: str, depth: int = 0) -> bool:
        ms = self.members(internal)
        if ms is not None and (name + desc) in ms:
            return True
        if depth > 8:
            return False
        return any(self.find_member(p, name, desc, depth + 1)
                   for p in self.parents(internal) if p and p != internal)


# ==========================================================================
# 3. 从 mod jar 抽取 mixin 配置与注入目标
# ==========================================================================
RUNTIME_MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"


def load_mixin_configs(mod_jar: str) -> dict[str, dict]:
    """{配置文件名: {mixin 类内部名: (required, defaultRequire)}}。"""
    res: dict[str, dict] = {}
    with zipfile.ZipFile(mod_jar) as z:
        for n in z.namelist():
            if not (n.endswith(".mixins.json") or n.endswith(".mixin.json")
                    or n == "mixins.json"):
                continue
            try:
                cfg = json.loads(z.read(n).decode("utf-8"))
            except Exception:
                continue
            pkg = (cfg.get("package") or "").replace(".", "/")
            req = bool(cfg.get("required", False))
            dreq = int((cfg.get("injectors") or {}).get("defaultRequire", 0) or 0)
            entries: dict[str, tuple[bool, int]] = {}
            for key in ("mixins", "client", "server"):
                for m in (cfg.get(key) or []):
                    cls = str(m).replace(".", "/")
                    entries[(pkg + "/" + cls) if pkg else cls] = (req, dreq)
            if entries:
                res[n] = entries
    return res


def mixin_declared_targets(cf: ClassFile) -> tuple[set[str], set[str]]:
    """返回 (@Mixin 的 value 类, targets 字符串)。"""
    values: set[str] = set()
    targets: set[str] = set()
    for desc, elems in cf.class_annotations:
        if desc != RUNTIME_MIXIN:
            continue
        v = elems.get("value")
        if isinstance(v, tuple) and v[0] == "array":
            for item in v[1]:
                if isinstance(item, tuple) and item[0] == "class" and item[1]:
                    values.add(str(item[1]))
        elif isinstance(v, tuple) and v[0] == "class" and v[1]:
            values.add(str(v[1]))
        t = elems.get("targets")
        if isinstance(t, tuple) and t[0] == "array":
            for item in t[1]:
                if isinstance(item, tuple) and item[0] == "str" and item[1]:
                    targets.add(str(item[1]).replace(".", "/"))
    # value 里的内部名可能带 `L…;` 残留，统一清理
    values = {v[1:-1] if v.startswith("L") and v.endswith(";") else v for v in values}
    return values, targets


def injected_members(cf: ClassFile) -> set[tuple[str, str, str]]:
    """抽 `Lowner;name(desc)ret` 形式的注入目标 → {(owner, name, '(desc)ret')}。"""
    out: set[tuple[str, str, str]] = set()
    for e in cf.cp:
        if not e or e[0] != "utf8":
            continue
        v = str(e[1])
        if not (v.startswith("Lnet/minecraft/") or v.startswith("Lcom/mojang/")):
            continue
        m = re.fullmatch(r"L([\w/$]+);([\w$<>]+)(\([^)]*\)\S+)", v)
        if m:
            out.add((m.group(1), m.group(2), m.group(3)))
    return out


# ==========================================================================
def main() -> int:
    ap = argparse.ArgumentParser(description="Mixin 注入目标对目标 MC 版本的静态核验")
    ap.add_argument("--mod-jar", required=True, help="含 mixins.json 的模组 jar（如 PAL）")
    ap.add_argument("--mc-jar", required=True, help="目标版本原版 jar")
    ap.add_argument("--libs-dir", action="append", default=[], help="额外 classpath（目录）")
    ap.add_argument("--javap", help="指定 javap")
    args = ap.parse_args()

    javap = find_jdk_tool("javap", args.javap)
    mod_jar = os.path.abspath(args.mod_jar)
    mc_jar = os.path.abspath(args.mc_jar)
    for p, n in ((mod_jar, "--mod-jar"), (mc_jar, "--mc-jar")):
        if not os.path.isfile(p):
            print("!! %s 不存在：%s" % (n, p), file=sys.stderr)
            return 2

    print("mod : %s" % mod_jar)
    print("mc  : %s" % mc_jar)
    idx = McIndex(mc_jar, args.libs_dir, javap)
    print("目标版本类: %d 个\n" % len(idx.classes))

    fatal: list[str] = []
    warn: list[str] = []
    ok = 0
    with zipfile.ZipFile(mod_jar) as z:
        names = set(z.namelist())
        configs = load_mixin_configs(mod_jar)
        for cfg_name, mixins in sorted(configs.items()):
            print("=== %s（%d 个 mixin）===" % (cfg_name.split("/")[-1], len(mixins)))
            for cls, (req, dreq) in sorted(mixins.items()):
                short = cls.split("/")[-1]
                entry = cls + ".class"
                if entry not in names:
                    print("   ⚠️  %-44s 类不在 jar 里（跳过）" % short)
                    continue
                cf = ClassFile(z.read(entry))
                if not cf.valid:
                    print("   ·  %-44s class 文件解析失败（跳过）" % short)
                    continue
                values, targets = mixin_declared_targets(cf)
                declared = values | targets
                if not declared:
                    print("   ·  %-44s 未解析出 @Mixin 目标（跳过）" % short)
                    continue

                # (1) 目标类是否存在 —— Mixin 对缺失目标只打 WARN 并跳过
                miss_t = sorted(t for t in declared if not idx.has(t))
                for t in miss_t:
                    warn.append("%s → 目标类不存在：%s" % (short, t))

                # (2) 注入点成员是否存在 —— 缺失且 require>=1 时致命
                bad_m = []
                for owner, name, desc in sorted(injected_members(cf)):
                    # 只在「注入目标类自身或其成员所属类」上判断；owner 可能是
                    # 目标方法体内的调用接收者（如 PoseStack），同样要存在
                    if owner not in idx.classes:
                        bad_m.append("%s.%s%s（类缺失）" % (owner, name, desc))
                    elif not idx.find_member(owner, name, desc):
                        bad_m.append("%s.%s%s" % (owner, name, desc))
                is_fatal = req or dreq >= 1
                if bad_m:
                    for x in bad_m:
                        (fatal if is_fatal else warn).append("%s → %s" % (short, x))

                if not miss_t and not bad_m:
                    ok += 1
                    print("   ✅ %-44s 目标类 + 注入点均可解析" % short)
                else:
                    if miss_t:
                        print("   ⚠️  %-44s 目标类缺失 %d 个（Mixin 会跳过，不致命）"
                              % (short, len(miss_t)))
                        for t in miss_t[:4]:
                            print("        ∟ %s" % t)
                    if bad_m:
                        tag = "❌ 致命" if is_fatal else "⚠️  警告"
                        print("   %s %-40s 注入点不匹配 %d 处（required=%s defaultRequire=%d）"
                              % (tag, short, len(bad_m), req, dreq))
                        for x in bad_m[:6]:
                            print("        ∟ %s" % x)
            print()

    print("=" * 76)
    print("❌ 致命（会导致 Mixin 应用失败／启动崩溃）: %d 处" % len(fatal))
    for x in fatal[:20]:
        print("   ", x)
    print("⚠️  警告（Mixin 跳过该处，游戏仍可启动）      : %d 处" % len(warn))
    for x in warn[:20]:
        print("   ", x)
    print("✅ 静态完全可解析的 mixin: %d 个" % ok)
    print("\n注意：这是**静态**核对 —— 只能说明「没发现已知断点」，"
          "不能证明注入点在方法体内真的命中（ordinal／指令类型需运行期判定）。")
    return 1 if fatal else 0


if __name__ == "__main__":
    sys.exit(main())
