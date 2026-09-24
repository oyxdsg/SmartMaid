#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键端到端联调测试（SmartMaid ↔ 桌宠）。

做四件事：
  1. 确保桌宠在跑（端口没监听就拉起 desktop-pet）
  2. 启动游戏并自动打开指定单机存档（--quickPlaySingleplayer，否则会停在主菜单）
  3. 等 AutoTest 跑完 / 遥测窗口落盘 / 桌宠收到数据
  4. 收集证据（遥测文件、模组日志关键行、桌宠日志增量）并关掉游戏

**必须在一个前台进程里跑完**：游戏是当前进程的子进程，父进程一退出它就会被回收。

用法::

    python tools/run_e2e_test.py --quick-play "新的世界 (11)"
    python tools/run_e2e_test.py --quick-play "新的世界 (11)" --pet-dir <桌宠目录>
"""

import argparse
import glob
import json
import os
import re
import socket
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from launch_game import (DEFAULT_GAME_DIR, find_launch_command, load_access_token,  # noqa: E402
                         parse_command, check_token)
import _paths  # noqa: E402

# 桌宠目录不写死在仓库里：环境变量 DESKPET_DIR / tools/local_paths.json / 桌面默认位置
DEFAULT_PET_DIR = _paths.deskpet_dir(required=False) or ""
PET_PORT = 21420


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        # Windows 中文控制台是 GBK，emoji 打不出时降级为 ASCII
        print(line.encode("ascii", "replace").decode("ascii"), flush=True)


def read_text(path, encodings=("gbk", "utf-8")):
    """MC 日志在中文 Windows 下是 GBK，必须先按字节读再解码。"""
    try:
        raw = open(path, "rb").read()
    except OSError:
        return ""
    for enc in encodings:
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def port_open(port, host="127.0.0.1"):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex((host, port)) == 0


def ensure_pet(pet_dir, port):
    """桌宠没在监听就拉起来（GUI 程序，独立进程组）。"""
    if port_open(port):
        log("桌宠已在监听 %d，跳过启动" % port)
        return None
    main_py = os.path.join(pet_dir, "main.py")
    if not os.path.isfile(main_py):
        log("找不到桌宠入口: %s" % main_py)
        return None
    log("启动桌宠: %s" % main_py)
    out = open(os.path.join(pet_dir, "logs", "pet_console.log"), "ab")
    kwargs = {"cwd": pet_dir, "stdout": out, "stderr": out}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    proc = subprocess.Popen([sys.executable, "main.py"], **kwargs)
    for _ in range(20):
        time.sleep(1)
        if port_open(port):
            log("桌宠已就绪（pid=%d）" % proc.pid)
            return proc
    log("桌宠启动后端口仍未监听，继续（游戏侧会自行重连）")
    return proc


def launch_game(game_dir, quick_play):
    log_dir = os.path.join(os.path.dirname(game_dir), ".hmcl", "logs")
    src, command = find_launch_command(log_dir)
    if not command:
        raise SystemExit("[x] 没找到 HMCL 启动命令，请先用启动器进过一次游戏")
    argv = parse_command(command, load_access_token(game_dir))
    if quick_play:
        argv += ["--quickPlaySingleplayer", quick_play]
    log("启动游戏（命令来自 %s，自动进存档 %s）" % (os.path.basename(src), quick_play))
    kwargs = {"cwd": game_dir}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(argv, **kwargs)


def wait_for(game_dir, t0, timeout, pet_log, pet_log_start):
    """轮询直到 AutoTest 跑完且遥测落盘，或超时。返回收集到的状态。"""
    log_path = os.path.join(game_dir, "logs", "latest.log")
    maid_dir = os.path.join(game_dir, "deskpet", "maid")
    deadline = time.time() + timeout
    state = {"autotest_done": False, "windows": 0, "perception": False, "pet_lines": []}
    while time.time() < deadline:
        time.sleep(5)
        try:
            if os.path.getmtime(log_path) < t0:
                continue  # 日志还没被这次运行覆盖
        except OSError:
            continue
        text = read_text(log_path)
        state["autotest_done"] = "AutoTest: 执行完成" in text
        state["windows"] = text.count("遥测窗口写入")
        state["perception"] = "开始上报感知" in text
        files = glob.glob(os.path.join(maid_dir, "*.jsonl"))
        state["windows_files"] = len(files)
        pet_lines = read_pet_log(pet_log, pet_log_start)
        state["pet_lines"] = pet_lines
        log("进度: AutoTest=%s 遥测窗口=%d 感知上报=%s 桌宠日志新增=%d 行"
            % (state["autotest_done"], state["windows"], state["perception"], len(pet_lines)))
        if state["autotest_done"] and state["windows"] >= 2 and state["perception"]:
            break
    return state


def read_pet_log(path, start_offset):
    try:
        size = os.path.getsize(path)
        if size < start_offset:
            start_offset = 0
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            f.seek(start_offset)
            return [l.rstrip() for l in f if l.strip()]
    except OSError:
        return []


def report(game_dir, state, pet_lines):
    log_path = os.path.join(game_dir, "logs", "latest.log")
    text = read_text(log_path)
    print("\n" + "=" * 72)
    print("联调报告")
    print("=" * 72)

    print("\n--- 模组侧关键日志 ---")
    keys = ["遥测窗口写入", "开始上报感知", "心跳往返", "握手成功", "女仆 WebSocket",
            "桌宠气泡", "桌宠动画", "桌宠指令", "AutoTest: 执行完成", "未知消息类型"]
    for k in keys:
        hits = [l for l in text.splitlines() if k in l]
        print("\n  ● %s（%d 条）" % (k, len(hits)))
        for l in hits[:6]:
            print("     ", l.split("]: ", 1)[-1][:190])
    errs = [l for l in text.splitlines() if re.search(r"/ERROR\]|/WARN\]", l)]
    print("\n  ● ERROR/WARN：%d 条" % len(errs))
    for l in errs[:5]:
        print("     ", l[:190])

    print("\n--- 女仆遥测窗口 ---")
    maid_dir = os.path.join(game_dir, "deskpet", "maid")
    for f in sorted(glob.glob(os.path.join(maid_dir, "*.jsonl"))):
        for line in read_text(f, ("utf-8",)).splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                w = json.loads(line)
            except ValueError:
                continue
            print("  seq=%-3s %-8s 事件 %-3d %s" % (
                w.get("seq"), w.get("importance"), len(w.get("highlights") or []),
                json.dumps(w.get("maid"), ensure_ascii=False)))
            for h in (w.get("highlights") or [])[:4]:
                print("        -", json.dumps(h, ensure_ascii=False))

    print("\n--- 桌宠侧新增日志 ---")
    for l in pet_lines[-40:]:
        print("  ", l)
    ok = state["autotest_done"] and state["windows"] >= 1 and state["perception"]
    print("\n" + ("结论: 链路正常 OK" if ok else "结论: 证据不足 WARN（见上面日志）"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir", default=DEFAULT_GAME_DIR)
    ap.add_argument("--pet-dir", default=DEFAULT_PET_DIR)
    ap.add_argument("--quick-play", default=None)
    ap.add_argument("--timeout", type=int, default=240, help="等待秒数")
    ap.add_argument("--no-pet", action="store_true", help="不启动桌宠")
    ap.add_argument("--keep-game", action="store_true", help="结束后不关游戏")
    ap.add_argument("--skip-token-check", action="store_true",
                    help="跳过 accessToken 校验（HMCL 实际用别处缓存的 token，check_token 读 hmcl.json 会误报）")
    args = ap.parse_args()

    if not args.game_dir:
        log("[x] 没有指定游戏目录，且自动探测未命中。请用 --game-dir 指定，"
            "或设置环境变量 SMARTMAID_MC_DIR，或写 tools/local_paths.json")
        return 2
    if not args.no_pet and not args.pet_dir:
        log("[x] 没有指定桌宠目录，且自动探测未命中。请用 --pet-dir 指定，"
            "或设置环境变量 DESKPET_DIR，或写 tools/local_paths.json（或用 --no-pet 跳过）")
        return 2

    pet_proc = None
    if not args.no_pet:
        ensure_pet(args.pet_dir, PET_PORT)

    # 启动游戏前先验证 token（过期就早退，避免最新日志被无效凭证塞爆）。
    # 注意：check_token 只读 hmcl.json 的 accessToken 字段，HMCL 实际可能用别处缓存的
    # 有效 token（历史多次误报），故提供 --skip-token-check 跳过。
    if not args.skip_token_check:
        ok, info = check_token(args.game_dir)
        if not ok:
            log("[x] access token 不可用: %s" % info)
            log("   请先用『我.exe』启动游戏登录一次（HMCL 会自动刷新 token），再跑这个脚本")
            return 3

    pet_log = os.path.join(args.pet_dir, "logs", "maid_link.log")
    pet_log_start = os.path.getsize(pet_log) if os.path.isfile(pet_log) else 0

    t0 = time.time()
    game = launch_game(args.game_dir, args.quick_play)
    try:
        state = wait_for(args.game_dir, t0, args.timeout, pet_log, pet_log_start)
    finally:
        if not args.keep_game:
            log("关闭游戏（pid=%d）..." % game.pid)
            subprocess.run(["taskkill", "/PID", str(game.pid)],
                           capture_output=True) if os.name == "nt" else game.terminate()
            time.sleep(8)
            if game.poll() is None:
                subprocess.run(["taskkill", "/PID", str(game.pid), "/F"], capture_output=True)
    return report(args.game_dir, state, state.get("pet_lines") or [])


if __name__ == "__main__":
    sys.exit(main())
