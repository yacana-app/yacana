# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Checks a night's frames against the session log (the log exported from Settings).

- Every frame the log says was saved must be in the folder: none lost.
- Every FITS file must open and hold a whole image: none corrupt.
- Leftover `.part` files are listed: a frame cut short by a crash, never under its final name.
- Crashes and restarts come from the log's `process_exit` and `session_restarted` events.
- The sky night's results: polar alignment, plate solves, centring, sync and autofocus.

Usage: python3 tools/check_night.py <night folder> <exported log ZIP or .jsonl file>
FITS checks need astropy (`pip install astropy`).
"""

import json
import os
import sys
import zipfile


def log_lines(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            for name in sorted(n for n in archive.namelist() if n.endswith(".jsonl")):
                yield from archive.read(name).decode("utf-8").splitlines()
    else:
        with open(path, encoding="utf-8") as f:
            yield from f


def check_fits(path):
    from astropy.io import fits

    with fits.open(path, memmap=False) as hdul:
        hdul.verify("exception")
        data = hdul[0].data
        if data is None or data.ndim != 2:
            raise ValueError("no 2D image")
        return f"{data.shape[1]}x{data.shape[0]}"


def main(folder, log):
    events = [json.loads(line) for line in log_lines(log) if line.strip()]
    saved = [e for e in events if e.get("event") == "frame_saved"]
    exits = [e for e in events if e.get("event") == "process_exit"]
    restarts = [e for e in events if e.get("event") == "session_restarted"]
    errors = [e for e in events if e.get("event") == "capture_error"]

    files = {}
    parts = []
    for root, _, names in os.walk(folder):
        for name in names:
            path = os.path.join(root, name)
            if name.endswith(".part"):
                parts.append(path)
            elif name.lower().endswith((".fits", ".fit")):
                files[name] = path

    corrupt = []
    for name, path in sorted(files.items()):
        try:
            check_fits(path)
        except Exception as e:  # noqa: BLE001 - every failure is a corrupt file
            corrupt.append((name, str(e)))

    in_folder = set(files)
    lost = [e["file"] for e in saved if os.path.basename(e["file"].replace("\\/", "/")) not in in_folder]

    print(f"frames in the folder: {len(files)}")
    print(f"frames the log saved: {len(saved)}")
    print(f"lost: {len(lost)}")
    print(f"corrupt: {len(corrupt)}")
    print(f"leftover .part files: {len(parts)}")
    print(f"capture errors: {len(errors)}")
    print(f"restarts after the process ended: {len(restarts)} (process exits logged: {len(exits)})")
    downloads = sorted(e["downloadMs"] for e in saved if isinstance(e.get("downloadMs"), int))
    if downloads:
        median = downloads[len(downloads) // 2]
        print(f"download (3,000 ms at most): median {median} ms, worst {downloads[-1]} ms")
    # Only frames shown while the screen was on and Imaging was open.
    shown = sorted(e["shownMs"] for e in events if e.get("event") == "frame_shown" and isinstance(e.get("shownMs"), int))
    if shown:
        median = shown[len(shown) // 2]
        print(f"on screen after the download (1,000 ms at most): median {median} ms, worst {shown[-1]} ms, {len(shown)} frames")
    sky(events)
    for path in lost[:10]:
        print("  LOST", path)
    for name, why in corrupt[:10]:
        print("  CORRUPT", name, why)
    for e in errors[:10]:
        print("  ERROR", e.get("t"), e.get("reason"))
    return 1 if lost or corrupt else 0


def sky(events):
    """What the sky night measures, as the log keeps it: polar alignment, solving, centring, sync and autofocus."""

    def of(name):
        return [e for e in events if e.get("event") == name]

    for e in of("polar_alignment_measured"):
        print(f"polar alignment measured: altitude {e['altitudeArcsec']:.0f}\", azimuth {e['azimuthArcsec']:.0f}\"")
    for e in of("polar_alignment_finished"):
        print(f"polar alignment left: {e['totalArcsec']:.0f}\" (altitude {e['altitudeArcsec']:.0f}\", "
              f"azimuth {e['azimuthArcsec']:.0f}\"), from {e['initialTotalArcsec']:.0f}\"")
    solves = of("plate_solve")
    if solves:
        solved = [e for e in solves if e.get("solved")]
        times = sorted(e["ms"] for e in solved if isinstance(e.get("ms"), int))
        median = f", median {times[len(times) // 2]} ms" if times else ""
        print(f"plate solves: {len(solved)} of {len(solves)} solved{median}")
        rotations = [e["rotationDeg"] for e in solved if isinstance(e.get("rotationDeg"), (int, float))]
        if rotations:
            print(f"  camera rotation: {min(rotations):.1f}° to {max(rotations):.1f}°")
    for e in of("centring_done"):
        verdict = "centred" if e.get("centred") else "NOT centred"
        print(f"centring (3 attempts at most): {verdict} in {e.get('attempts')} attempts, {e.get('offArcsec', 0):.0f}\" off")
    for e in of("mount_sync"):
        print(f"sync (30\" at most): {e.get('offArcsec', 0):.0f}\" off")
    focused = of("autofocus_done")
    for e in focused:
        r2 = e.get("rSquared")
        fit = f", R² {r2:.3f}" if isinstance(r2, (int, float)) else ""
        print(f"autofocus: position {e['position']}, HFR {e['hfr']:.2f}{fit}")
    if len(focused) >= 3:
        positions = [e["position"] for e in focused[-3:]]
        print(f"  last three positions within {max(positions) - min(positions)} steps")
    for e in of("autofocus_failed"):
        print("  AUTOFOCUS FAILED", e.get("t"), e.get("reason"))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
