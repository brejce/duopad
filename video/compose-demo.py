#!/usr/bin/env python3
"""把双屏录屏内容按透视贴进绿幕掌机照片，输出视频。

为什么用 Python 而不是 ffmpeg 滤镜：
  · ffmpeg 的 perspective 只做正向映射，且 sense 语义易踩坑
  · 我们要的是「照片上这块四边形 = 录屏内容」，属于反向映射
  · PIL 的 Image.transform(..., PERSPECTIVE) 正好是反解系数后的采样，
     coeficients 由 4 组目标点算出来，逐帧跑一次，逻辑一目了然

流程：
  1. 读 quads.json（四角坐标）
  2. 录屏拆两块：上半 = 上屏(1920x1080)，下半 = 下屏(1280x960 -> 拉伸)
  3. 每帧：先算两块的反向透视系数，transform 到照片尺寸，贴回底图
"""
import argparse
import json
import os
import subprocess
import sys
from fractions import Fraction

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(HERE, "assets")


def pj(a, b):
    """求 8 个透视系数，把源矩形 (0,0,w,h) 映射到目标四边形 quad。"""
    m = []
    for (x, y), (u, v) in zip(a, b):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    # 高斯消元解 m·c = rhs
    rhs = [c for p in b for c in (p[0], p[1])]
    n = 8
    A_ = [row[:] + [rhs[i]] for i, row in enumerate(m)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(A_[r][col]))
        if abs(A_[piv][col]) < 1e-12:
            raise ValueError("四边形退化，无法求透视系数")
        A_[col], A_[piv] = A_[piv], A_[col]
        pv = A_[col][col]
        A_[col] = [x / pv for x in A_[col]]
        for r in range(n):
            if r != col and A_[r][col]:
                f = A_[r][col]
                A_[r] = [x - f * y for x, y in zip(A_[r], A_[col])]
    return [A_[i][n] for i in range(n)]


def coeffs_for(src_size, quad):
    """src_size=(w,h) 源矩形 -> 目标四边形；返回 PIL transform 的 8 系数（反向）。"""
    w, h = src_size
    src = [(0, 0), (w, 0), (w, h), (0, h)]          # 左上 右上 右下 左下
    # 正解：源 -> 目标；PIL 需要目标 -> 源，取逆
    fwd = pj(src, quad)
    # 逆变换：直接交换映射方向求解
    return pj(quad, src)


def detect_bot_bounds(rec):
    """量下屏内容在录屏帧里的左右边界（两侧是黑边）。"""
    from PIL import Image as I
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", "1", "-i", rec, "-frames:v", "1",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
    if not out:
        return 320, 1600
    im = I.frombytes("RGB", (1920, 2040), out)
    bot = im.crop((0, 1080, 1920, 2040))
    px = bot.load()
    xs = [x for x in range(1920)
          if sum(1 for y in range(0, 960, 8) if sum(px[x, y]) > 30) > 3]
    return (xs[0], xs[-1] + 1) if xs else (320, 1600)


def ffprobe(path, *args):
    out = subprocess.run(["ffprobe", "-v", "error", *args, "-of", "csv=p=0", path],
                         capture_output=True, text=True).stdout.strip()
    return out.splitlines()[0] if out else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec", default=os.path.join(A, "screen-dual.mp4"))
    ap.add_argument("--plate", default=os.path.join(A, "greenscreen.png"))
    ap.add_argument("--quads", default=os.path.join(HERE, "quads.json"))
    ap.add_argument("--out", default=os.path.join(A, "demo.mp4"))
    ap.add_argument("--start", type=float, default=0.0, help="录屏起始秒（截掉前面没用的）")
    ap.add_argument("--duration", type=float, default=20.0)
    ap.add_argument("--fps", type=int, default=0, help="输出帧率，0=跟随录屏（否则时长会变）")
    ap.add_argument("--crf", type=int, default=20)
    args = ap.parse_args()

    q = json.load(open(args.quads))
    plate = Image.open(args.plate).convert("RGB")
    W, H = plate.size
    print(f"底图 {W}x{H}")

    # 拆录屏：上 1920x1080，下 1920x960（宽度相同，录屏本身就是上下拼接）
    rec_w = 1920
    top_h, bot_h = 1080, 960
    rw = int(ffprobe(args.rec, "-select_streams", "v:0", "-show_entries", "stream=width").split(".")[0])
    rh = int(ffprobe(args.rec, "-select_streams", "v:0", "-show_entries", "stream=height").split(".")[0])
    in_fps = ffprobe(args.rec, "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate")
    num, _, den = in_fps.partition("/")
    in_fps = float(Fraction(num) / Fraction(den)) if den else float(num or 60)
    out_fps = args.fps or round(in_fps)
    print(f"录屏 {rw}x{rh} @ {in_fps:.2f}fps -> 输出 {out_fps}fps")
    if rh < top_h + bot_h:
        raise SystemExit(f"录屏高度不足，期望 ≥ {top_h + bot_h}")

    # 下屏是被缩到 1280 宽再拼上去的？还是原始 1920 宽？
    # 拍摄时下屏 1280x960，若录屏直接拼原始分辨率则下半是 1920x960（被拉伸）
    # 下屏真实内容边界：录屏帧 1920 宽，下屏内容居中只占 1280 宽
    bot_x0, bot_x1 = detect_bot_bounds(args.rec)
    print(f"下屏内容 x {bot_x0}..{bot_x1} (宽 {bot_x1 - bot_x0})")
    bot_src_size = (bot_x1 - bot_x0, bot_h)

    c_top = coeffs_for((rec_w, top_h), q["top"])
    c_bot = coeffs_for(bot_src_size, q["bot"])

    # 用 ffmpeg 解码成 rgb24 裸流，逐帧处理，再编码回去
    dec = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-ss", str(args.start), "-t", str(args.duration), "-i", args.rec,
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE)
    enc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{W}x{H}", "-r", str(out_fps), "-i", "-",
         "-c:v", "libx264", "-preset", "medium", "-crf", str(args.crf),
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", args.out],
        stdin=subprocess.PIPE)

    fsz = rw * rh * 3
    n = 0
    try:
        while True:
            buf = dec.stdout.read(fsz)
            if len(buf) < fsz:
                break
            frame = Image.frombytes("RGB", (rw, rh), buf)
            top = frame.crop((0, 0, rw, top_h))
            # 下屏在录屏帧里是居中放置的（1920 宽里有黑边），先裁掉黑边再贴，
            # 否则照片上会看到两侧黑条。
            bot = frame.crop((bot_x0, top_h, bot_x1, top_h + bot_h))
            out = plate.copy()
            for img, co, quad in ((top, c_top, q["top"]), (bot, c_bot, q["bot"])):
                warped = img.transform((W, H), Image.PERSPECTIVE, co, Image.BICUBIC)
                # 只把四边形内的像素贴回去：用四边形掩膜
                mask = Image.new("L", (W, H), 0)
                from PIL import ImageDraw
                ImageDraw.Draw(mask).polygon([tuple(p) for p in quad], fill=255)
                out.paste(warped, (0, 0), mask)
            enc.stdin.write(out.tobytes())
            n += 1
            if n % 30 == 0:
                print(f"\r{n} 帧", end="", flush=True)
    finally:
        if dec.stdout:
            dec.stdout.close()
        if enc.stdin:
            enc.stdin.close()
        dec.wait()
        enc.wait()
    print(f"\r共 {n} 帧 -> {args.out}")


if __name__ == "__main__":
    main()
