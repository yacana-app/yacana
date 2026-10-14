#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Builds Yacana's catalogue of deep-sky objects from OpenNGC, with the
common names of names.tsv in English and Spanish, as the tab-separated file the app reads. Needs
the internet unless OpenNGC's two files are given.

    python catalog/build.py [folder with NGC.csv and addendum.csv]

Each object keeps its designations, Messier first, then NGC or IC, then those of other catalogues
that OpenNGC lists (Caldwell, Sharpless, LBN, Melotte, Collinder, Barnard, UGC and PGC); its type,
J2000 place, constellation, size, angle, magnitude (V, else B), surface brightness and Hubble type;
and its common names, those of names.tsv first, and without OpenNGC's leading "the". Nonexistent
objects are left out, and duplicates become another designation of the object they repeat.
"""

import csv
import io
import os
import re
import sys
import urllib.request

RELEASE = "v20260501"
URL = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/" + RELEASE + "/database_files/"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "shared", "ui", "src", "commonMain", "composeResources", "files", "catalogue.tsv")

# OpenNGC's identifiers kept as designations, and how the app writes them.
IDENTIFIERS = [
    (re.compile(r"^C 0*(\d+)$"), "C {}"),
    (re.compile(r"^SH 2-0*(\d+)$"), "Sh2-{}"),
    (re.compile(r"^LBN 0*(\d+)$"), "LBN {}"),
    (re.compile(r"^Mel 0*(\d+)$"), "Mel {}"),
    (re.compile(r"^Cl 0*(\d+)$"), "Cr {}"),
    (re.compile(r"^B 0*(\d+)$"), "B {}"),
    (re.compile(r"^UGC 0*(\d+)$"), "UGC {}"),
    (re.compile(r"^PGC 0*(\d+)$"), "PGC {}"),
]
# OpenNGC's own names, as the app writes them: NGC0224 is NGC 224, Mel022 is Mel 22, Cl399 is Cr 399…
NAMES = [
    (re.compile(r"^NGC0*(\d+)(.*)$"), "NGC {}{}"),
    (re.compile(r"^IC0*(\d+)(.*)$"), "IC {}{}"),
    (re.compile(r"^Mel0*(\d+)$"), "Mel {}"),
    (re.compile(r"^Cl0*(\d+)$"), "Cr {}"),
    (re.compile(r"^B0*(\d+)$"), "B {}"),
    (re.compile(r"^C0*(\d+)$"), "C {}"),
    (re.compile(r"^H0*(\d+)$"), "H {}"),
    (re.compile(r"^HCG0*(\d+)$"), "HCG {}"),
    (re.compile(r"^UGC0*(\d+)$"), "UGC {}"),
    (re.compile(r"^PGC0*(\d+)$"), "PGC {}"),
    (re.compile(r"^MWSC0*(\d+)$"), "MWSC {}"),
    (re.compile(r"^ESO0*(\d+)-0*(\d+)$"), "ESO {}-{}"),
    (re.compile(r"^M0*(\d+)$"), "M {}"),
]


def written(name):
    for pattern, form in NAMES:
        match = pattern.match(name)
        if match:
            return form.format(*match.groups())
    raise ValueError("No form for " + name)


def rows(folder, name):
    if folder:
        text = open(os.path.join(folder, name), encoding="utf-8").read()
    else:
        with urllib.request.urlopen(URL + name, timeout=120) as reply:
            text = reply.read().decode("utf-8")
    return list(csv.DictReader(io.StringIO(text), delimiter=";"))


def hours(ra):
    h, m, s = (float(x) for x in ra.split(":"))
    return h + m / 60 + s / 3600


def degrees(dec):
    sign = -1 if dec.startswith("-") else 1
    d, m, s = (float(x) for x in dec.lstrip("+-").split(":"))
    return sign * (d + m / 60 + s / 3600)


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else None
    entries = rows(folder, "NGC.csv") + rows(folder, "addendum.csv")
    objects = {}
    duplicates = []
    for row in entries:
        kind = row["Type"]
        if kind == "NonEx":
            continue
        if kind == "Dup":
            duplicates.append(row)
            continue
        if not row["RA"] or not row["Dec"]:
            continue
        designations = []
        if row["M"]:
            designations.append("M {}".format(int(row["M"])))
        designations.append(written(row["Name"]))
        for other, prefix in ((row["NGC"], "NGC"), (row["IC"], "IC")):
            for number in filter(None, other.split(",")):
                designations.append(written(prefix + number.strip()))
        for identifier in filter(None, (i.strip() for i in row["Identifiers"].split(","))):
            for pattern, form in IDENTIFIERS:
                match = pattern.match(identifier)
                if match:
                    designations.append(form.format(*match.groups()))
        objects[row["Name"]] = {
            "designations": list(dict.fromkeys(designations)),
            "type": kind,
            "ra": hours(row["RA"]),
            "dec": degrees(row["Dec"]),
            "const": row["Const"],
            "major": row["MajAx"],
            "minor": row["MinAx"],
            "angle": row["PosAng"],
            "mag": row["V-Mag"] or row["B-Mag"],
            "surface": row["SurfBr"],
            "hubble": row["Hubble"],
            "names": [re.sub(r"^the ", "", n.strip()) for n in row["Common names"].split(",") if n.strip()],
            "spanish": "",
        }
    # A duplicate is another designation of the object in its NGC, IC or Messier column.
    for row in duplicates:
        target = None
        if row["NGC"]:
            target = "NGC" + row["NGC"].split(",")[0].strip().zfill(4)
        elif row["IC"]:
            target = "IC" + row["IC"].split(",")[0].strip().zfill(4)
        elif row["M"]:
            target = next((k for k, o in objects.items() if o["designations"][0] == "M {}".format(int(row["M"]))), None)
        if target in objects:
            alias = written(row["Name"])
            if alias not in objects[target]["designations"]:
                objects[target]["designations"].append(alias)
    by_designation = {d: o for o in objects.values() for d in o["designations"]}
    for line in open(os.path.join(HERE, "names.tsv"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        key, english, spanish = line.rstrip("\n").split("\t")
        target = by_designation.get(written(key)) if not key.startswith("ESO") else objects.get(key)
        if target is None:
            raise SystemExit("names.tsv: no object " + key)
        first = [n.strip() for n in english.split(",") if n.strip()]
        target["names"] = first + [n for n in target["names"] if n not in first]
        if spanish:
            target["spanish"] = spanish

    def order(o):
        first = o["designations"][0]
        number = re.search(r"\d+", first)
        rank = {"M": 0, "NGC": 1, "IC": 2}.get(first.split(" ")[0], 3)
        return rank, first.split(" ")[0], int(number.group()) if number else 0, first

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        out.write("# Yacana's catalogue of deep-sky objects, made by catalog/build.py from OpenNGC " + RELEASE +
                  " (https://github.com/mattiaverga/OpenNGC) by Mattia Verga and its contributors, CC BY-SA 4.0,"
                  " with the common names of catalog/names.tsv, many from Wikipedia's lists of Messier, NGC and Caldwell"
                  " objects, CC BY-SA 4.0. This file is shared under CC BY-SA 4.0.\n")
        out.write("# designations\ttype\tra (h, J2000)\tdec (deg, J2000)\tconstellation\tmajor (arcmin)\tminor (arcmin)"
                  "\tangle (deg)\tmagnitude\tsurface brightness\tHubble\tcommon names\tSpanish name\n")
        for o in sorted(objects.values(), key=order):
            out.write("\t".join([
                ",".join(o["designations"]), o["type"], "{:.6f}".format(o["ra"]), "{:.5f}".format(o["dec"]), o["const"],
                o["major"], o["minor"], o["angle"], o["mag"], o["surface"], o["hubble"], ",".join(o["names"]), o["spanish"],
            ]) + "\n")
    print("{} objects, {} with a Spanish name".format(len(objects), sum(1 for o in objects.values() if o["spanish"])), file=sys.stderr)


if __name__ == "__main__":
    main()
