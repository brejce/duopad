#!/usr/bin/env python3
"""从绿幕照片里测出两块屏幕的四角坐标，写到 video/quads.json。

为什么要单独一个脚本：照片是手拍斜的，两块屏幕都是透视四边形。
每次换照片（换角度、换机型）都得重新测，硬编码会失效。

判据：绿幕是纯绿（G 明显高于 R/B）。机身米色也偏亮但 G-B 差值小，不会误判。
边缘用「内部 60% 行线性拟合 + 外推到上下边」求，避开圆角。

输出顺序 UL UR LR LL（左上、右上、右下、左下），像素坐标。
"""
import json
import os
import statistics
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PLATE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "assets", "greenscreen.png")
OUT = os.path.join(HERE, "quads.json")


def is_green(px, x, y):
    r, g, b = px[x, y][:3]
    return g > 100 and g - max(r, b) > 35


def fit_line(points):
    """最小二乘 y = kx + c，points 是 (x, y)。"""
    n = len(points)
    mx = sum(p[0] for p in points) / n
    my = sum(p[1] for p in points) / n
    num = sum((p[0] - mx) * (p[1] - my) for p in points)
    den = sum((p[0] - mx) ** 2 for p in points) or 1
    k = num / den
    return k, my - k * mx


def quad(px, W, ytop, ybot, name):
    rows = {}
    for y in range(ytop, ybot):
        xs = [x for x in range(W) if is_green(px, x, y)]
        if len(xs) > 40:
            rows[y] = (xs[0], xs[-1])
    if not rows:
        raise SystemExit(f"{name}: 没找到绿幕，检查照片判据")

    ys = sorted(rows)
    ya, yb = ys[0], ys[-1]
    # 只用内部 60% 的行拟合，两端圆角/遮挡会把斜率拉偏
    core = ys[len(ys) // 5: len(ys) * 4 // 5]
    kl, cl = fit_line([(y, rows[y][0]) for y in core])
    kr, cr = fit_line([(y, rows[y][1]) for y in core])

    def at(k, c, y):
        return max(0, min(W - 1, int(round(k * y + c))))

    q = [[at(kl, cl, ya), ya], [at(kr, cr, ya), ya],
         [at(kr, cr, yb), yb], [at(kl, cl, yb), yb]]
    print(f"{name}: y {ya}..{yb} -> {q}")
    return q


def main():
    im = Image.open(PLATE).convert("RGB")
    W, H = im.size
    px = im.load()
    # 上下两块的 y 分界：取两块绿区之间的空白（铰链）中点
    mid = H // 2
    quads = {
        "top": quad(px, W, 0, mid + 60, "上屏"),
        "bot": quad(px, W, mid - 60, H, "下屏"),
        "size": [W, H],
    }
    json.dump(quads, open(OUT, "w"), indent=1)
    print(f"已写 {OUT}")


if __name__ == "__main__":
    main()
