"""Pick the visual-regression frames from the test report + capture dir.

Single-clock design (no cross-process line order, no polling handshake):

* The CI capture loop screenshots the device every second and names each
  file frame-<host-epoch-ms>.png, then writes `frame <host-epoch-ms>` to
  the device logcat (tag CompresslyCap) so the test process can read the
  host clock.
* ScreenshotRegressionTest.bracket() holds each screen for 20s between two
  `latest frame timestamp` reads and prints
      COMPRESSLY-WINDOW <screen> <t0> <t1>
  which lands in the connected-test XML report (instrumentation stdout is
  NOT streamed to the gradle console, so the report is the channel).
* After the run, this script reads the windows from the XML and picks the
  frame whose file-name timestamp lies closest to the middle of the
  window. All comparisons happen in host milliseconds - one clock only.

Usage: python3 scripts/pick_frames.py <test_xml> <caps_dir> <out_dir>
"""
import glob
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

xml_path, caps_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
screens = ["01_onboarding", "02_home", "03_history"]

text = open(xml_path, encoding="utf-8", errors="replace").read()
windows = re.findall(r"COMPRESSLY-WINDOW (\S+) (\d+) (\d+)", text)

frames = {}
for p in glob.glob(os.path.join(caps_dir, "frame-*.png")):
    m = re.search(r"frame-(\d+)\.png$", p)
    if m:
        frames[int(m.group(1))] = p

os.makedirs(out_dir, exist_ok=True)
ok = True
for s in screens:
    wins = [(int(a), int(b)) for sc, a, b in windows if sc == s and int(b) > int(a)]
    if not wins:
        print(f"FAIL {s}: no valid COMPRESSLY-WINDOW in report (windows seen: {windows})")
        ok = False
        continue
    lo, hi = max(wins)
    in_win = sorted(t for t in frames if lo < t < hi)
    if not in_win:
        print(f"FAIL {s}: window {lo}..{hi} but no frames inside (have {len(frames)})")
        ok = False
        continue
    mid = in_win[len(in_win) // 2]
    shutil.copyfile(frames[mid], os.path.join(out_dir, s + ".png"))
    print(f"OK {s}: {len(in_win)} frames in window {lo}..{hi}, picked {mid}")

sys.exit(0 if ok else 1)
