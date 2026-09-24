#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复女仆皮肤手部缺失的面（用标准 Minecraft UV 展开规则，坐标已实证）。

玩家模型右手 texOffs(40,16) 尺寸 4x12x4，128x128 高清皮肤放大 2 倍：
  右手(起点80,32)：顶(88,32) 底(96,32) 前(88,40) 右(80,40) 后(96,40) 左(104,40)
  左手(镜像区,起点64,96)：顶(72,96) 底(80,96) 前(72,104) 右(64,104) 后(80,104) 左(88,104)

缺失：左右手"底面"半透明(32/64)、"左面"半透明(96/192)。
修复：底面 ← 顶面填充；左面 ← 右面水平镜像。
用法：python tools/fix_maid_skin_hands.py [源png] [输出png]
"""
import os
import sys

from PIL import Image

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)                      # SmartMaid/
_REPO = os.path.dirname(_ROOT)                      # 仓库根

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_REPO, "皮肤大肥鱼.png")
OUT = (sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    _ROOT, "src", "main", "resources", "assets", "smartmaid", "textures", "entity", "smart_maid.png"))

im = Image.open(SRC).convert("RGBA")
px = im.load()


def hflip_copy(sx0, sy0, sw, sh, dx0, dy0):
    piece = im.crop((sx0, sy0, sx0 + sw, sy0 + sh)).transpose(Image.FLIP_LEFT_RIGHT)
    im.paste(piece, (dx0, dy0))


def fill_region(x0, y0, w, h, sample):
    """用 sample 区域均值色填满目标区域（底面用）。"""
    r = g = b = a = 0
    sx, sy, sw, sh = sample
    n = 0
    for y in range(sy, sy + sh):
        for x in range(sx, sx + sw):
            p = px[x, y]
            if p[3] > 40:
                r += p[0]; g += p[1]; b += p[2]; a += p[3]; n += 1
    if n == 0:
        return
    color = (r // n, g // n, b // n, a // n)
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            px[x, y] = color


# 右手：左面(104,40) ← 右面(80,40) 镜像；底面(96,32) ← 顶面
hflip_copy(80, 40, 8, 24, 104, 40)
fill_region(96, 32, 8, 8, (88, 32, 8, 8))
# 左手：左面(88,104) ← 右面(64,104) 镜像；底面(80,96) ← 顶面
hflip_copy(64, 104, 8, 24, 88, 104)
fill_region(80, 96, 8, 8, (72, 96, 8, 8))

im.save(OUT)
print("saved:", OUT, im.size)

# 验证
img2 = Image.open(OUT).convert("RGBA")
p2 = img2.load()
ok = True
for name, (x0, y0, w, h) in {
    "右手顶": (88, 32, 8, 8), "右手底": (96, 32, 8, 8), "右手前": (88, 40, 8, 24),
    "右手右": (80, 40, 8, 24), "右手后": (96, 40, 8, 24), "右手左": (104, 40, 8, 24),
    "左手顶": (72, 96, 8, 8), "左手底": (80, 96, 8, 8), "左手前": (72, 104, 8, 24),
    "左手右": (64, 104, 8, 24), "左手后": (80, 104, 8, 24), "左手左": (88, 104, 8, 24),
}.items():
    op = sum(1 for y in range(y0, y0 + h) for x in range(x0, x0 + w) if p2[x, y][3] > 40)
    flag = "OK" if op == w * h else "!!"
    if op != w * h: ok = False
    print(f"  {name}: 不透明 {op}/{w*h} {flag}")
print("\n全部完整" if ok else "\n仍有缺失!")
