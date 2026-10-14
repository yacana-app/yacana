#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""JPL Horizons' own numbers for the ephemerides' tests: for each night of CASES, the
times of sunset, the twilights, sunrise and the Moon's rise and set, from the Sun's and the Moon's
airless topocentric altitude minute by minute, interpolated where they cross; and the Sun's and the
Moon's apparent topocentric place and the Moon's illuminated fraction at midnight. Prints them as
the Kotlin of `HorizonsReference.kt`. Needs the internet.

    python tools/horizons/reference.py > shared/domain/src/commonTest/kotlin/app/yacana/domain/sky/HorizonsReference.kt

The rise and set use the same horizon as Astronomy Engine: the Sun's or the Moon's upper limb at
34' of refraction below the horizon, so its centre at -(34' + its radius).
"""

import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

API = "https://ssd.jpl.nasa.gov/api/horizons.api"

# Public observatories: (name, latitude, east longitude, metres).
SITES = {
    "roque": ("Roque de los Muchachos", 28.7606, -17.8816, 2396.0),
    "siding": ("Siding Spring", -31.2733, 149.0611, 1165.0),
    "metsahovi": ("Metsähovi", 60.2172, 24.3933, 60.0),
}
# (site, the evening's date): the night runs from local noon to the next local noon.
CASES = [
    ("roque", "2026-10-02"),
    ("roque", "2026-06-21"),
    ("siding", "2026-06-21"),
    ("siding", "2026-12-21"),
    ("metsahovi", "2026-06-21"),
    ("metsahovi", "2026-12-21"),
]
REFRACTION = 34.0 / 60.0
SUN_RADIUS_KM = 695700.0
AU_KM = 149597870.7


def horizons(body, site, start, stop, step, quantities):
    _, lat, lon, metres = SITES[site]
    params = {
        "format": "json",
        "COMMAND": f"'{body}'",
        "OBJ_DATA": "'NO'",
        "MAKE_EPHEM": "'YES'",
        "EPHEM_TYPE": "'OBSERVER'",
        "CENTER": "'coord@399'",
        "COORD_TYPE": "'GEODETIC'",
        "SITE_COORD": f"'{lon % 360:.6f},{lat:.6f},{metres / 1000:.4f}'",
        "START_TIME": f"'{start:%Y-%m-%d %H:%M}'",
        "STOP_TIME": f"'{stop:%Y-%m-%d %H:%M}'",
        "STEP_SIZE": f"'{step}'",
        "QUANTITIES": f"'{quantities}'",
        "APPARENT": "'AIRLESS'",
        "ANG_FORMAT": "'DEG'",
        "CSV_FORMAT": "'YES'",
        "TIME_DIGITS": "'SECONDS'",
        "EXTRA_PREC": "'YES'",
    }
    with urllib.request.urlopen(API + "?" + urllib.parse.urlencode(params, safe="'@,:"), timeout=120) as reply:
        text = json.load(reply)["result"]
    table = text[text.index("$$SOE") + 5:text.index("$$EOE")].strip().splitlines()
    header = [h.strip() for h in text[:text.index("$$SOE")].strip().splitlines()[-2].split(",")]
    rows = []
    for line in table:
        cells = [c.strip() for c in line.split(",")]
        row = dict(zip(header, cells))
        row["time"] = parse_time(cells[0])
        rows.append(row)
    return rows


def parse_time(text):
    """Horizons' "2026-Oct-02 13:11", with seconds or not."""
    for pattern in ("%Y-%b-%d %H:%M:%S.%f", "%Y-%b-%d %H:%M:%S", "%Y-%b-%d %H:%M"):
        try:
            return datetime.strptime(text, pattern).replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    raise ValueError(text)


def crossings(rows, altitude):
    """Each time the altitude crosses `altitude(row)`, interpolated: ('rise' or 'set', time)."""
    found = []
    for a, b in zip(rows, rows[1:]):
        da = float(a["Elevation_(a-app)"]) - altitude(a)
        db = float(b["Elevation_(a-app)"]) - altitude(b)
        if da < 0 <= db or da >= 0 > db:
            t = a["time"] + (b["time"] - a["time"]) * (da / (da - db))
            found.append(("rise" if db > da else "set", t))
    return found


def first(found, direction):
    return next((t for d, t in found if d == direction), None)


def iso(t):
    return None if t is None else t.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def kotlin_time(t):
    return "null" if t is None else f'Instant.parse("{iso(t)}")'


def night(site, date):
    _, lat, lon, _ = SITES[site]
    evening = datetime.fromisoformat(date).replace(tzinfo=timezone.utc)
    start = evening + timedelta(hours=12 - lon / 15)
    start = start.replace(second=0, microsecond=0)
    stop = start + timedelta(days=1)
    sun = horizons("10", site, start, stop, "1 m", "4,20")
    moon = horizons("301", site, start, stop, "1 m", "4,13")

    def sun_limb(row):
        radius = SUN_RADIUS_KM / (float(row["delta"]) * AU_KM)
        return -(REFRACTION + radius * 180 / 3.141592653589793)

    def moon_limb(row):
        return -(REFRACTION + float(row["Ang-diam"]) / 2 / 3600)

    sun_events = crossings(sun, sun_limb)
    events = {
        "sunset": first(sun_events, "set"),
        "sunrise": first(sun_events, "rise"),
    }
    for name, depth in (("civil", -6.0), ("nautical", -12.0), ("astronomical", -18.0)):
        found = crossings(sun, lambda _row, d=depth: d)
        events[f"{name}Dusk"] = first(found, "set")
        events[f"{name}Dawn"] = first(found, "rise")
    moon_events = crossings(moon, moon_limb)
    events["moonrise"] = first(moon_events, "rise")
    events["moonset"] = first(moon_events, "set")

    # The places at local midnight, a whole minute.
    midnight = (start + timedelta(hours=12)).replace(second=0, microsecond=0)
    places = {}
    for body, name in (("10", "sun"), ("301", "moon")):
        rows = horizons(body, site, midnight, midnight + timedelta(minutes=1), "1 m", "2,10" if body == "301" else "2")
        row = rows[0]
        places[name] = (float(row["R.A.__(a-app)"]), float(row["DEC___(a-app)"]), float(row.get("Illu%", "nan")))
    return start, events, midnight, places


def main():
    # LF on every system, as the repository keeps its files.
    sys.stdout.reconfigure(newline="
", encoding="utf-8")
    print("// This Source Code Form is subject to the terms of the Mozilla Public")
    print("// License, v. 2.0. If a copy of the MPL was not distributed with this")
    print("// file, You can obtain one at https://mozilla.org/MPL/2.0/.")
    print()
    print("package app.yacana.domain.sky")
    print()
    print("import app.yacana.domain.profile.Location")
    print("import kotlinx.datetime.LocalDate")
    print("import kotlin.time.Instant")
    print()
    print("/** JPL Horizons' numbers for the ephemerides' tests, as `tools/horizons/reference.py` printed them. */")
    print("internal val HORIZONS =")
    print("    listOf(")
    for site, date in CASES:
        name, lat, lon, metres = SITES[site]
        _, events, midnight, places = night(site, date)
        sun, moon = places["sun"], places["moon"]
        print("        HorizonsNight(")
        print(f'            "{name}",')
        print(f"            Location({lat}, {lon}, {metres}),")
        print(f'            LocalDate.parse("{date}"),')
        for key in ("sunset", "civilDusk", "nauticalDusk", "astronomicalDusk", "astronomicalDawn", "nauticalDawn", "civilDawn",
                    "sunrise", "moonrise", "moonset"):
            print(f"            {key} = {kotlin_time(events[key])},")
        print(f"            midnight = {kotlin_time(midnight)},")
        print(f"            sun = Place({sun[0]}, {sun[1]}),")
        print(f"            moon = Place({moon[0]}, {moon[1]}),")
        print(f"            moonIlluminated = {moon[2] / 100:.5f},")
        print("        ),")
    print("    )")


if __name__ == "__main__":
    main()
