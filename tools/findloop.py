"""Find the best seamless-loop points in a clip and build a looping version.

Usage: python3 -I findloop.py <in.mp4> <out.mp4> [blend_seconds]

Picks a start S (early) and end E (late) where a short run of frames at E
looks most like the run at S (picture *and* motion), then renders
[S+X, E+X] cross-faded into [S, S+X] so the last frame flows into the first.
"""
import json
import subprocess
import sys

import numpy as np

W, H, WIN = 96, 54, 6


def probe(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=r_frame_rate:format=duration", "-of", "json", path])
    j = json.loads(out)
    num, den = j["streams"][0]["r_frame_rate"].split("/")
    return float(num) / float(den), float(j["format"]["duration"])


def frames(path):
    raw = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={W}:{H}",
        "-pix_fmt", "gray", "-f", "rawvideo", "-"])
    return np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(np.float32)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    blend = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
    fps, dur = probe(src)
    v = frames(src)
    n = len(v)
    xf = int(round(blend * fps))

    # Typical change between consecutive frames, to judge match quality.
    step = float(np.mean(np.abs(v[1:] - v[:-1])))

    s_range = range(0, min(int(1.5 * fps), n // 4))
    e_lo = max(int(0.5 * n), int(6 * fps))
    e_hi = n - xf - WIN - 2
    best = (1e9, 0, 0)
    for s in s_range:
        a = v[s:s + WIN]
        for e in range(e_lo, e_hi):
            d = float(np.mean(np.abs(v[e:e + WIN] - a)))
            if d < best[0]:
                best = (d, s, e)
    d, s, e = best
    S, E, X = s / fps, e / fps, xf / fps
    L = E - S
    print(f"{src.split('/')[-1]}: loop {S:.2f}s -> {E:.2f}s  ({L:.1f}s)  "
          f"seam diff {d:.2f} vs frame-to-frame {step:.2f}  ({d / step:.1f}x)")

    # part1 = [S+X, E+X], part2 = [S, S+X]; fade part1's tail into part2.
    fc = (f"[0:v]trim=start={S + X:.4f}:end={E + X:.4f},setpts=PTS-STARTPTS[a];"
          f"[0:v]trim=start={S:.4f}:end={S + X:.4f},setpts=PTS-STARTPTS[b];"
          f"[a][b]xfade=transition=fade:duration={X:.4f}:offset={L - X:.4f},format=yuv420p[v]")
    subprocess.check_call([
        "ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex", fc,
        "-map", "[v]", "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "21",
        "-profile:v", "high", "-level", "4.1", "-movflags", "+faststart", dst])


if __name__ == "__main__":
    main()
