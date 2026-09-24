#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 AI 生成的 MC 皮肤：左臂/左腿缺失、右臂背面半空问题。

AI 皮肤生成器常见的坑：只绘制贴图右半边（头/身/右臂/右腿），不知道左臂在
y104-128 下半区、左腿在 y104-128。本脚本把右侧镜像补齐到左侧（MC 左右对称标准做法），
并把右臂背面未绘制的半宽用已绘制的半宽对称补齐。

用法：python tools/fix_ai_skin.py [AI皮肤.png] [输出.png]
"""
import os
import sys

from PIL import Image

SRC = (sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "皮肤大肥鱼.png"))
OUT = (sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src/main/resources/assets/smartmaid/textures/entity/smart_maid.png"))

im = Image.open(SRC).convert("RGBA")


def hflip_copy(sx0, sy0, sw, sh, dx0, dy0):
    """水平翻转并复制到目标区域。"""
    piece = im.crop((sx0, sy0, sx0 + sw, sy0 + sh)).transpose(Image.FLIP_LEFT_RIGHT)
    im.paste(piece, (dx0, dy0))


def fill_blank_half(bx0, by0, bw, bh):
    """把区域内已绘制的那一半水平对称补齐到另一半。"""
    px = im.load()
    # 判断哪一半内容更多
    left = sum(1 for y in range(by0, by0 + bh) for x in range(bx0, bx0 + bw // 2)
               if px[x, y][3] > 40)
    right = sum(1 for y in range(by0, by0 + bh) for x in range(bx0 + bw // 2, bx0 + bw)
                if px[x, y][3] > 40)
    if left >= right:
        # 左半 → 镜像到右半
        for y in range(by0, by0 + bh):
            for i in range(bw // 2):
                im.putpixel((bx0 + bw // 2 + i, y), im.getpixel((bx0 + bw // 2 - 1 - i, y)))
    else:
        # 右半 → 镜像到左半
        for y in range(by0, by0 + bh):
            for i in range(bw // 2):
                im.putpixel((bx0 + i, y), im.getpixel((bx0 + bw - 1 - i, y)))


# 1) 右臂背面半空 → 补齐（AI 只画了半宽）
fill_blank_half(104, 40, 8, 24)

# 2) 左侧 = 右侧镜像
hflip_copy(88, 40, 8, 24, 88, 104)     # 左臂正面 ← 右臂正面
hflip_copy(104, 40, 8, 24, 104, 104)   # 左臂背面 ← 右臂背面
hflip_copy(8, 40, 8, 24, 8, 104)       # 左腿正面 ← 右腿正面
hflip_copy(24, 40, 8, 24, 24, 104)     # 左腿背面 ← 右腿背面

im.save(OUT)
print("saved:", OUT, im.size)
