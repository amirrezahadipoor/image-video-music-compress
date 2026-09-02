"""Visual regression: pixel-diff fresh CI captures against the committed
baseline.

Usage: python3 scripts/visual_diff.py [baseline_dir] [captures_dir]

The first CI capture creates the baseline (committed by the workflow);
later runs diff every baseline PNG against the fresh capture and fail if
any frame drifts by more than 3% of pixels (or changes size).
"""
import os
import sys

from PIL import Image, ImageChops

BASE = sys.argv[1] if len(sys.argv) > 1 else "docs/screenshots-baseline"
SHOTS = sys.argv[2] if len(sys.argv) > 2 else "shots-run/CompresslyScreenshots"
THRESHOLD_PCT = 3.0

if not os.path.isdir(SHOTS):
    print(f"captures dir missing: {SHOTS}")
    sys.exit(1)

names = [n for n in os.listdir(BASE) if n.endswith(".png")]
fresh = [n for n in os.listdir(SHOTS) if n.endswith(".png")]
missing = sorted(set(names) - set(fresh))
if missing:
    print(f"missing captures: {missing} (got: {sorted(fresh)})")
    sys.exit(1)

worst = 0.0
for name in sorted(names):
    a = Image.open(os.path.join(BASE, name)).convert("RGB")
    b = Image.open(os.path.join(SHOTS, name)).convert("RGB")
    if a.size != b.size:
        print(f"{name}: SIZE MISMATCH {a.size} vs {b.size}")
        sys.exit(1)
    hist = ImageChops.difference(a, b).convert("L").histogram()
    pct = 100.0 * sum(hist[16:]) / (a.size[0] * a.size[1])
    worst = max(worst, pct)
    print(f"{name}: {pct:.2f}% changed")

print(f"worst: {worst:.2f}% (threshold {THRESHOLD_PCT}%)")
sys.exit(0 if worst <= THRESHOLD_PCT else 2)
