"""Pick the visual-regression frames from the shared capture log.

The CI capture loop screenshots the device every second while the
ScreenshotRegressionTest runs, appending
    COMPRESSLY-FRAME <path>
to pass1-console.log (O_APPEND, same file gradle writes to), and the test
prints
    COMPRESSLY-MARK <screen> START
    COMPRESSLY-MARK <screen> END
around a fixed hold on each screen. Because both writers append to the
same file, line order == wall-clock order, so for every screen we take
the frame captured mid-window - no polling, no clocks to sync, no
handshake that can die silently.

Usage: python3 scripts/pick_frames.py <log> <caps_dir> <out_dir>
"""
import os
import re
import shutil
import sys

log_path, caps_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
screens = ["01_onboarding", "02_home", "03_history"]
FRAME = re.compile(r"COMPRESSLY-FRAME (.+)$")
MARK = re.compile(r"COMPRESSLY-MARK (\S+) (START|END)")

frames = []
marks = []
with open(log_path, encoding="utf-8", errors="replace") as f:
    for i, line in enumerate(f):
        m = FRAME.search(line)
        if m:
            frames.append((i, m.group(1).strip()))
            continue
        m = MARK.search(line)
        if m:
            marks.append((i, m.group(1), m.group(2)))

os.makedirs(out_dir, exist_ok=True)
ok = True
for s in screens:
    starts = [i for i, sc, w in marks if sc == s and w == "START"]
    ends = [i for i, sc, w in marks if sc == s and w == "END"]
    if not starts or not ends:
        print(f"FAIL {s}: no complete START/END window in log")
        ok = False
        continue
    lo = max(i for i in starts if any(e > i for e in ends))
    hi = min(e for e in ends if e > lo)
    in_win = [p for i, p in frames if lo < i < hi]
    if not in_win:
        print(f"FAIL {s}: window found but no frames inside it")
        ok = False
        continue
    mid = in_win[len(in_win) // 2]
    src = os.path.join(caps_dir, os.path.basename(mid))
    shutil.copyfile(src, os.path.join(out_dir, s + ".png"))
    print(f"OK {s}: {len(in_win)} frames in window, picked {os.path.basename(mid)}")

sys.exit(0 if ok else 1)
