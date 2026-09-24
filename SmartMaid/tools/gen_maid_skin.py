#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成女仆皮肤（桌宠形象精细版）：头部 = 立绘 front/side/back 像素映射（还原脸/发型），
身体/四肢 = 精细色块重绘（渐变+裙褶+描边，颜色从立绘采样）。

立绘来源：<桌宠项目>/assets/.base_frames/<front|side|back>/frame_0000.png
路径解析见 tools/_paths.py（环境变量 DESKPET_ASSETS_DIR / DESKPET_DIR 或 tools/local_paths.json）
用法：python tools/gen_maid_skin.py [立绘根目录] [输出png]
"""
import os
import sys

from PIL import Image

import _paths

PET_ASSETS = (sys.argv[1] if len(sys.argv) > 1 else _paths.deskpet_assets())
OUT = (sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src/main/resources/assets/smartmaid/textures/entity/smart_maid.png"))


def load(view):
    p = os.path.join(PET_ASSETS, ".base_frames", view, "frame_0000.png")
    return Image.open(p).convert("RGBA")


# 立绘头区域（由分析标定）：(x, y, w, h)
HEAD_REGIONS = {
    "front": (43, 12, 97, 103),
    "side":  (60, 12, 74, 103),
    "back":  (65, 12, 90, 108),
}

# ---------- 从立绘采样得到的配色 ----------
HAIR = (60, 84, 144)
HAIR_HI = (92, 120, 185)
SKIN = (237, 209, 198)
SKIN_DK = (202, 173, 163)
WHITE = (238, 234, 240)
WHITE_DK = (188, 188, 202)
BOW = (36, 48, 72)
SKIRT = (45, 69, 124)
SKIRT_HI = (74, 108, 162)
SOCK = (240, 216, 216)
SHOE = (30, 40, 72)
SHOE_HI = (62, 78, 124)
OUTLINE = (34, 34, 58)        # 深色描边（Q 版立绘感）

# ---------- 128x128 玩家皮肤 UV 布局（= 64x64 经典布局 x2） ----------
BOXES = {
    "head_top":    (16, 0, 16, 16),
    "head_bottom": (32, 0, 16, 16),
    "head_left":   (0, 16, 16, 16),
    "head_front":  (16, 16, 16, 16),
    "head_right":  (32, 16, 16, 16),
    "head_back":   (48, 16, 16, 16),
    "body_top":    (40, 32, 16, 8),
    "body_right":  (32, 40, 8, 24),
    "body_front":  (40, 40, 16, 24),
    "body_left":   (56, 40, 8, 24),
    "body_back":   (64, 40, 16, 24),
    "body_bottom": (56, 32, 8, 8),
    "rarm_top":    (88, 32, 8, 8),
    "rarm_right":  (96, 40, 8, 24),
    "rarm_front":  (88, 40, 8, 24),
    "rarm_left":   (80, 40, 8, 24),
    "rarm_back":   (104, 40, 8, 24),
    "rarm_bottom": (96, 64, 8, 8),
    "larm_top":    (88, 96, 8, 8),
    "larm_right":  (96, 104, 8, 24),
    "larm_front":  (88, 104, 8, 24),
    "larm_left":   (80, 104, 8, 24),
    "larm_back":   (104, 104, 8, 24),
    "larm_bottom": (96, 88, 8, 8),
    "rleg_top":    (8, 32, 8, 8),
    "rleg_right":  (16, 40, 8, 24),
    "rleg_front":  (8, 40, 8, 24),
    "rleg_left":   (0, 40, 8, 24),
    "rleg_back":   (24, 40, 8, 24),
    "rleg_bottom": (16, 64, 8, 8),
    "lleg_top":    (8, 96, 8, 8),
    "lleg_right":  (16, 104, 8, 24),
    "lleg_front":  (8, 104, 8, 24),
    "lleg_left":   (0, 104, 8, 24),
    "lleg_back":   (24, 104, 8, 24),
    "lleg_bottom": (16, 88, 8, 8),
}

img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
px = img.load()


def paste_region(view_img, box, crop, flip=False):
    x0, y0, w, h = box
    cx, cy, cw, ch = crop
    piece = view_img.crop((cx, cy, cx + cw, cy + ch))
    if flip:
        piece = piece.transpose(Image.FLIP_LEFT_RIGHT)
    piece = piece.resize((w, h), Image.LANCZOS)
    img.paste(piece, (x0, y0), piece)


def fill(x0, y0, w, h, color):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            px[x, y] = color


def vgrad(x0, y0, w, h, top, bottom):
    for i in range(h):
        t = i / max(h - 1, 1)
        c = tuple(int(top[j] + (bottom[j] - top[j]) * t) for j in range(3))
        fill(x0, y0 + i, w, 1, c)


def outline(box, color=OUTLINE):
    """沿 UV 方块四周描 1px 边（外部），模拟立绘描边。"""
    x0, y0, w, h = box
    fill(x0, y0, w, 1, color)
    fill(x0, y0 + h - 1, w, 1, color)
    fill(x0, y0, 1, h, color)
    fill(x0 + w - 1, y0, 1, h, color)


# ---------- 加载立绘 ----------
FRONT, SIDE, BACK = load("front"), load("side"), load("back")

# ---------- 头部：立绘头直接映射（还原脸/发型） ----------
paste_region(FRONT, BOXES["head_front"], HEAD_REGIONS["front"])
paste_region(SIDE, BOXES["head_left"], HEAD_REGIONS["side"])
paste_region(SIDE, BOXES["head_right"], HEAD_REGIONS["side"], flip=True)
paste_region(BACK, BOXES["head_back"], HEAD_REGIONS["back"])
vgrad(*BOXES["head_top"], HAIR, HAIR_HI)
fill(*BOXES["head_bottom"], SKIN)

# ---------- 身体：精细色块（渐变 + 裙褶 + 领结 + 描边） ----------
def torso(box, front):
    x0, y0, w, h = box
    if front:
        fill(x0, y0, w, 4, WHITE)                     # 白上衣
        fill(x0, y0 + 3, w, 1, WHITE_DK)
        # 领结
        fill(x0 + 3, y0 + 4, 2, 2, BOW)
        fill(x0 + w - 5, y0 + 4, 2, 2, BOW)
        fill(x0 + w // 2 - 1, y0 + 4, 2, 3, BOW)
        fill(x0 + w // 2 - 1, y0 + 6, 1, 2, OUTLINE)
        fill(x0 + w // 2, y0 + 6, 1, 2, OUTLINE)
        # 裙
        for i in range(h - 9):
            t = i / max(h - 9 - 1, 1)
            c = tuple(int(SKIRT[j] + (SKIRT_HI[j] - SKIRT[j]) * t) for j in range(3))
            fill(x0, y0 + 9 + i, w, 1, c)
        # 裙摆花边
        fill(x0, y0 + h - 3, w, 1, SOCK)
        fill(x0, y0 + h - 2, w, 1, SOCK)
        fill(x0, y0 + h - 1, w, 1, OUTLINE)
        # 裙褶竖线
        for gx in range(x0 + 3, x0 + w, 4):
            for gy in range(y0 + 10, y0 + h - 3):
                px[gx, gy] = tuple(int(c * 0.82) for c in px[gx, gy][:3]) + (255,)
    else:
        fill(x0, y0, w, 4, WHITE)
        fill(x0, y0 + 3, w, 1, WHITE_DK)
        fill(x0, y0 + 4, w, h - 5, SKIRT)
        vgrad(x0, y0 + 4, w, h - 5, SKIRT, SKIRT_HI)
        fill(x0, y0 + h - 2, w, 1, SOCK)
        fill(x0, y0 + h - 1, w, 1, OUTLINE)

torso(BOXES["body_front"], True)
torso(BOXES["body_back"], False)
for n in ("body_left", "body_right"):
    torso(BOXES[n], False)
for n in ("body_top", "body_bottom"):
    fill(*BOXES[n], SKIRT)
for n in ("body_front", "body_back", "body_left", "body_right"):
    outline(BOXES[n])

# ---------- 手臂：白短袖 + 肤色手（渐变 + 描边） ----------
def arm(box):
    x0, y0, w, h = box
    vgrad(x0, y0, w, 8, WHITE, WHITE_DK)
    fill(x0, y0 + 8, w, 1, OUTLINE)                   # 袖口
    vgrad(x0, y0 + 9, w, h - 9, SKIN, SKIN_DK)
    fill(x0, y0 + h - 1, w, 1, OUTLINE)

for n in ("rarm_front", "rarm_back", "rarm_left", "rarm_right",
          "larm_front", "larm_back", "larm_left", "larm_right"):
    arm(BOXES[n])
    outline(BOXES[n])
for n in ("rarm_top", "rarm_bottom", "larm_top", "larm_bottom"):
    fill(*BOXES[n], WHITE)

# ---------- 腿：蓝裙 + 粉白袜 + 深蓝鞋（渐变 + 描边） ----------
def leg(box):
    x0, y0, w, h = box
    vgrad(x0, y0, w, 11, SKIRT, SKIRT_HI)
    fill(x0, y0 + 11, w, 1, OUTLINE)                  # 裙摆/袜口
    fill(x0, y0 + 12, w, 9, SOCK)
    fill(x0, y0 + 20, w, 1, OUTLINE)                  # 袜口/鞋口
    fill(x0, y0 + 21, w, 2, SHOE)
    fill(x0, y0 + 21, w, 1, SHOE_HI)
    fill(x0, y0 + h - 1, w, 1, OUTLINE)

for n in ("rleg_front", "rleg_back", "rleg_left", "rleg_right",
          "lleg_front", "lleg_back", "lleg_left", "lleg_right"):
    leg(BOXES[n])
    outline(BOXES[n])
for n in ("rleg_top", "rleg_bottom", "lleg_top", "lleg_bottom"):
    fill(*BOXES[n], SKIRT)

img.save(OUT)
print("saved:", OUT, img.size)
