#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Check that 64-bit native libraries are aligned for 16 KB memory pages.

Android 15+ devices with 16 KB pages only load libraries whose LOAD segments
are aligned to at least 16 KB (ARCHITECTURE.md). Pass .so files,
folders or APKs; every 64-bit ELF found is checked.

Usage: check_16kb.py PATH [PATH ...]
Exit code 0 when everything is aligned, 1 otherwise.
"""

import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(data: bytes) -> list[int] | None:
    """Return the alignment of each LOAD segment, or None if not a 64-bit ELF."""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    aligns = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(endian + "Q", data, offset + 0x30)[0])
    return aligns


def libraries(path: Path):
    """Yield (name, bytes) for every .so in a file, folder or APK."""
    if path.is_dir():
        for so in sorted(path.rglob("*.so")):
            yield str(so), so.read_bytes()
    elif path.suffix in (".apk", ".aab", ".aar", ".zip"):
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith(".so"):
                    yield f"{path}!{name}", archive.read(name)
    else:
        yield str(path), path.read_bytes()


def main(args: list[str]) -> int:
    if not args:
        print(__doc__)
        return 1
    checked = failed = 0
    for arg in args:
        for name, data in libraries(Path(arg)):
            aligns = load_alignments(data)
            if aligns is None:
                continue
            checked += 1
            smallest = min(aligns) if aligns else 0
            ok = smallest >= PAGE
            failed += not ok
            print(f"{'OK  ' if ok else 'FAIL'} {smallest:>6} {name}")
    if checked == 0:
        print("No 64-bit ELF libraries found.")
        return 1
    print(f"{checked} checked, {failed} not aligned to {PAGE} bytes.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
