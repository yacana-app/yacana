# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Measures Yacana's cold start on a connected phone or emulator (2 s at most).

Each run stops the app and starts it with `am start -W`, which reports when the first frame
was drawn. Install the build to measure first; release builds start faster than debug ones.

Usage: python3 tools/cold_start.py [runs] [adb serial]; set ADB to the adb program if it is not found.
"""

import os
import re
import shutil
import statistics
import subprocess
import sys

BUDGET_MS = 2000
ACTIVITY = "app.yacana/.MainActivity"


def adb_path():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    home = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    candidate = os.path.join(home, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    return candidate if os.path.exists(candidate) else shutil.which("adb") or "adb"


def main(runs, serial):
    adb = [adb_path()] + (["-s", serial] if serial else [])
    times = []
    for _ in range(runs):
        subprocess.run(adb + ["shell", "am", "force-stop", "app.yacana"], check=True)
        out = subprocess.run(adb + ["shell", "am", "start", "-W", "-n", ACTIVITY], check=True, capture_output=True, text=True).stdout
        match = re.search(r"TotalTime:\s*(\d+)", out)
        if not match:
            sys.exit(f"am start did not report a time:\n{out}")
        times.append(int(match.group(1)))
    median = statistics.median(times)
    print(f"cold start over {runs} runs: median {median:.0f} ms, worst {max(times)} ms, budget {BUDGET_MS} ms")
    print("runs (ms):", " ".join(map(str, times)))
    return 0 if median <= BUDGET_MS else 1


if __name__ == "__main__":
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    serial = sys.argv[2] if len(sys.argv) > 2 else None
    sys.exit(main(runs, serial))
