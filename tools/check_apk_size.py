# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Fails if an APK is bigger than the size budget (40 MB, without star databases or caches).

Usage: python3 tools/check_apk_size.py app.apk [more.apk ...]
"""

import os
import sys

BUDGET_BYTES = 40 * 1000 * 1000


def main(paths):
    failed = False
    for path in paths:
        size = os.path.getsize(path)
        verdict = "ok" if size <= BUDGET_BYTES else "OVER BUDGET"
        print(f"{path}: {size / 1e6:.1f} MB of {BUDGET_BYTES / 1e6:.0f} MB ({verdict})")
        failed |= size > BUDGET_BYTES
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
