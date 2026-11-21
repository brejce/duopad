#!/usr/bin/env python3
"""为 DuoPad 介绍视频逐段配音（MOSS-TTS-Nano，本地 CPU）。

每段文案必须短到能塞进对应时长。语速约 5 字/秒（中文），这里留了余量。
改文案后重跑即可，输出 video/narration/<段号>.wav 和合并后的 narration.wav。
"""
import os
import subprocess
import sys

ROOT = "/home/brejce/Project/moss-tts-nano"
sys.path.insert(0, ROOT)
os.environ.setdefault("HF_HUB_OFFLINE", "1")

from moss_tts_nano_runtime import NanoTTSService  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "narration")
os.makedirs(OUT, exist_ok=True)

VOICE = os.environ.get("VOICE", "Junhao")

# 段号 -> (文案, 该段时长秒)。文案控制在 ~5 字/秒以内。
LINES = [
    ("01", "DuoPad，给双屏掌机补上缺失的那一半。", 7),
    ("02", "下屏是一块大触摸屏，安卓默认不把它当键盘，打字得外接。", 8),
    ("03", "键盘和触摸板是一个整体，一起出现，一起收起。触摸板走 uinput，是真鼠标。中文输入接 Rime。", 12),
    ("04", "浏览器里中文提问，候选浮在上屏光标下方。", 10),
    ("05", "上屏当桌面，下屏负责输入。", 7),
    ("06", "项目开源，地址是 github dot com slash brejce slash duopad。", 8),
    ("07", "DuoPad，为双屏掌机补上缺失的那一半。", 6),
]


def main():
    svc = NanoTTSService(device="cpu", output_dir=OUT)
    parts = []
    for seg, text, dur in LINES:
        r = svc.synthesize(text=text, voice=VOICE)
        src = r["audio_path"]
        dst = os.path.join(OUT, f"{seg}.wav")
        os.replace(src, dst)
        got = float(subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", dst], capture_output=True, text=True).stdout.strip())
        flag = "  ⚠ 超长" if got > dur else ""
        print(f"{seg}: {got:.2f}s / 段 {dur}s  {text}{flag}")
        parts.append(dst)

    # 每段配音补齐到该段时长（尾部静音），这样整条音轨和视频段落严格对齐
    # 直接从 0s 铺满整片，时间轴就不需要再算偏移
    padded = []
    for (seg, text, dur), src in zip(LINES, parts):
        dst = os.path.join(OUT, f"{seg}_pad.wav")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", src,
                        "-af", f"apad=whole_dur={dur}", "-ar", "48000", "-ac", "1", dst],
                       check=True)
        padded.append(dst)
    lst = os.path.join(OUT, "list.txt")
    with open(lst, "w") as f:
        for p in padded:
            f.write(f"file '{p}'\n")
    merged = os.path.join(HERE, "narration.wav")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0",
                    "-i", lst, "-ar", "48000", "-ac", "1", merged], check=True)
    print("合并 ->", merged)


if __name__ == "__main__":
    main()
