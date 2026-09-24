#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 HMCL 日志中提取启动命令并重放，用于自动化联调测试。

为什么要这样做：MC 的启动命令行由启动器拼装（classpath / natives / 资源索引 / 账号参数），
自己拼容易出错；而 HMCL 会把它真正执行的命令行写进自己的日志
（``.hmcl/logs/<时间>.log.xz`` 里的 ``Launched process: ...``）。
本脚本解压最新一条含该行的日志、解析命令行、原样重放。

注意：命令行里含账号 accessToken，**只从日志实时提取，不落盘、不入库**。

用法::

    python tools/launch_game.py                 # 启动最新一次记录的版本
    python tools/launch_game.py --dry-run       # 只打印参数骨架，不启动
    python tools/launch_game.py --game-dir <路径>
"""

import argparse
import glob
import lzma
import os
import re
import subprocess
import sys

import _paths

# 游戏目录不写死在仓库里：环境变量 SMARTMAID_MC_DIR / tools/local_paths.json
# / %APPDATA%\.minecraft 依次解析（见 tools/_paths.py）
DEFAULT_GAME_DIR = _paths.minecraft_dir(required=False) or ""

# HMCL 出于隐私，把日志里的账号 token 替换成了字面量占位符（含空格，会被切词器拆开）
TOKEN_PLACEHOLDER = "<access token>"
TOKEN_SENTINEL = "\x00TOKEN\x00"

# HMCL 日志里的命令行形如：
#   java.exe -Xmx4526m ... "-DFabricMcEmu= net.minecraft.client.main.Main " -cp <很长> ... KnotClient ...
# 含空格/引号的参数被双引号包住，因此按引号感知方式切分，不能简单 split(" ")
QUOTED = re.compile(r'"[^"]*"|\S+')

# 值仍是 ${...} 占位符时一并丢弃的可选参数（缺了不影响单机）
DROPPABLE_WITH_PLACEHOLDER = ("--clientId", "--xuid")


def load_access_token(game_dir):
    """从启动器的 hmcl.json 里取第一个微软账号的 accessToken（仅本机内存使用）。"""
    path = os.path.join(os.path.dirname(game_dir), "hmcl.json")
    if not os.path.isfile(path):
        return None
    try:
        import json
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for acc in data.get("accounts") or []:
            token = acc.get("accessToken")
            if token:
                return token
    except Exception:
        pass
    return None


def check_token(game_dir):
    """检查 hmcl.json 里 access token 是否还有效（JWT.exp），给 false 表示不可用。

    返回 (ok: bool, info: str)。expired 时即便能启动游戏也进不了 quickPlay。
    """
    import json, base64, datetime
    path = os.path.join(os.path.dirname(game_dir), "hmcl.json")
    if not os.path.isfile(path):
        return False, "hmcl.json 不存在"
    try:
        data = json.load(open(path, encoding="utf-8"))
    except Exception as e:
        return False, "hmcl.json 解析失败: %s" % e
    for acc in (data.get("accounts") or []):
        tok = acc.get("accessToken")
        if not tok or tok == TOKEN_PLACEHOLDER:
            continue
        try:
            parts = tok.split(".")
            pad = lambda s: s + "=" * (4 - len(s) % 4)
            payload = json.loads(base64.urlsafe_b64decode(pad(parts[1])))
            exp = int(payload.get("exp", 0))
            expires_at = datetime.datetime.fromtimestamp(exp)
            now = datetime.datetime.now()
            if expires_at <= now:
                return False, "accessToken 已过期（JWT.exp=%s）" % expires_at.isoformat(timespec="seconds")
            return True, "有效到 %s（JWT.exp）" % expires_at.isoformat(timespec="seconds")
        except Exception as e:
            return False, "JWT 解析失败: %s" % e
    return False, "hmcl.json 里没有 accessToken（从没在启动器登录过）"


def find_launch_command(log_dir):
    """在日志目录里从新到旧找第一条 'Launched process:' 命令行。"""
    files = sorted(glob.glob(os.path.join(log_dir, "*.log.xz")), reverse=True)
    for path in files:
        try:
            text = lzma.open(path).read().decode("utf-8", errors="replace")
        except Exception:
            continue
        m = re.search(r"Launched process: (.+)$", text, re.M)
        if m:
            return path, m.group(1).strip()
    return None, None


def parse_command(command, token=None):
    """按引号感知切分成参数列表；补回 token，丢弃值仍为 ${...} 的可选参数。"""
    # 先把占位符替换成无空格哨兵，避免被切词器拆开
    command = command.replace(TOKEN_PLACEHOLDER, TOKEN_SENTINEL)
    args = []
    for token_item in QUOTED.findall(command):
        if token_item == TOKEN_SENTINEL:
            args.append(token or "0")
            continue
        if len(token_item) >= 2 and token_item[0] == '"' and token_item[-1] == '"':
            args.append(token_item[1:-1])
        else:
            args.append(token_item)
    # 丢弃值仍是 ${clientid} / ${auth_xuid} 之类的可选参数
    cleaned, i = [], 0
    while i < len(args):
        if args[i] in DROPPABLE_WITH_PLACEHOLDER and i + 1 < len(args) \
                and args[i + 1].startswith("${"):
            i += 2
            continue
        cleaned.append(args[i])
        i += 1
    return cleaned


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir", default=DEFAULT_GAME_DIR,
                    help="游戏目录（.minecraft）。默认自动探测（%s）" %
                         (DEFAULT_GAME_DIR or "未找到，需显式指定"))
    ap.add_argument("--hmcl-log-dir", default=None,
                    help="HMCL 日志目录，默认 <游戏目录同级>/.hmcl/logs")
    ap.add_argument("--quick-play", default=None,
                    help="存档文件夹名，进入后自动打开该单机世界（无人值守测试用）")
    ap.add_argument("--dry-run", action="store_true", help="只打印参数，不启动")
    ap.add_argument("--check-token", action="store_true",
                    help="只检查 hmcl.json 里 access token 是否过期，不启动游戏")
    args = ap.parse_args()

    if not args.game_dir:
        print("[x] 没有指定游戏目录，且自动探测未命中。请用 --game-dir 指定，"
              "或设置环境变量 SMARTMAID_MC_DIR，或写 tools/local_paths.json")
        return 2

    if args.check_token:
        ok, info = check_token(args.game_dir)
        if ok:
            print("[✓] access token 状态: %s" % info)
            return 0
        else:
            print("[x] access token 状态: %s" % info)
            print("    解决: 先用启动器(我.exe)开游戏登录一次，HMCL 会自动刷新 token 到 hmcl.json")
        return 0 if ok else 3

    game_dir = args.game_dir
    log_dir = args.hmcl_log_dir or os.path.join(os.path.dirname(game_dir), ".hmcl", "logs")
    if not os.path.isdir(log_dir):
        print("[x] HMCL 日志目录不存在: %s" % log_dir)
        return 2

    src, command = find_launch_command(log_dir)
    if not command:
        print("[x] 在 %s 里没找到 'Launched process:'（请先用启动器进过一次游戏）" % log_dir)
        return 2
    print("[1] 命令来源: %s" % os.path.basename(src))

    token = load_access_token(game_dir)
    used_placeholder = TOKEN_PLACEHOLDER in command
    argv = parse_command(command, token)
    if not argv:
        print("[x] 命令行解析为空")
        return 2
    print("[2] 共 %d 个参数" % len(argv))
    if used_placeholder:
        print("    日志里 token 是占位符，已从 hmcl.json 补回真实 token: %s"
              % ("成功" if token else "失败（用 '0' 兜底，单机可正常进入）"))

    def mask(t):
        low = t.lower()
        if "accesstoken" in low or re.fullmatch(r"[0-9a-f]{32}", t or "") or len(t) > 200:
            return "<已隐藏 %d 字符>" % len(t)
        return t if len(t) <= 90 else "<长参数 %d 字符> ...%s" % (len(t), t[-45:])

    for i, t in enumerate(argv):
        if i in (43, 42, 44):  # classpath 一带太长，单独提示
            print("     [%d] %s" % (i, mask(t)))
        elif args.dry_run or i < 60:
            print("     [%d] %s" % (i, mask(t)))
    if not args.dry_run:
        print("     ...（其余 %d 个参数省略）" % max(0, len(argv) - 60))

    if args.dry_run:
        print("[3] dry-run，不启动")
        return 0

    if args.quick_play:
        # 无人值守：进游戏后直接打开指定单机存档（否则会停在主菜单，AutoTest 不会触发）
        argv += ["--quickPlaySingleplayer", args.quick_play]
        print("[3] 将自动打开存档: %s" % args.quick_play)

    exe = argv[0]
    if not os.path.isfile(exe):
        print("[x] java 不存在: %s" % exe)
        return 2
    print("[4] 启动游戏（工作目录 %s）..." % game_dir)
    kwargs = {"cwd": game_dir}
    if os.name == "nt":
        # 独立进程组，父进程退出不影响游戏
        kwargs["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    proc = subprocess.Popen(argv, **kwargs)
    print("[5] 已启动，pid=%d" % proc.pid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
