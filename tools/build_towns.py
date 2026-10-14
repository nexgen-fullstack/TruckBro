"""
Builds the violent-crime level of every US city (FBI Crime Data Explorer) into
app/src/main/assets/towns.tsv. TruckBro uses it to color truck stops green / yellow / red.

Run (once every few months, before a new app version):
    python tools/build_towns.py

Rate = violent crimes in the last 12 months / population * 100 000 (US average is about 380).
"""
import json
import math
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://cde.ucr.cjis.gov/LATEST"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "towns.tsv")
STATES = ("AL AK AZ AR CA CO CT DE DC FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS MO MT NE NV NH NJ NM NY NC ND "
          "OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY").split()
SKIP_POP_KEYS = {"United States"}


def get(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "TruckBro-builder/1.0"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                print("  ! fail", url, e, file=sys.stderr)
                return None
            time.sleep(2 + i * 3)


def agencies(state):
    data = get(f"{BASE}/agency/byStateAbbr/{state}") or {}
    return [a for items in data.values() for a in items
            if a.get("agency_type_name") == "City" and a.get("latitude") is not None]


def month_range():
    meta = get(f"{BASE}/summarized/agency/IL0161000/violent-crime?from=01-2024&to=12-2024&type=counts")
    m, y = map(int, meta["cde_properties"]["max_data_date"]["UCR"].split("/"))
    # 12 full months ending one month before the last one (the last one is often incomplete).
    end_m, end_y = (m - 1, y) if m > 1 else (12, y - 1)
    start_m, start_y = end_m + 1, end_y - 1
    if start_m == 13:
        start_m, start_y = 1, end_y
    return f"{start_m:02d}-{start_y}", f"{end_m:02d}-{end_y}"


def row(a, frm, to):
    d = get(f"{BASE}/summarized/agency/{a['ori']}/violent-crime?from={frm}&to={to}&type=counts")
    if not d:
        return None
    actual = next((v for k, v in d.get("offenses", {}).get("actuals", {}).items() if k.endswith("Offenses")), None)
    pops = d.get("populations", {}).get("population", {})
    # Keys are the agency, its state and the US; the agency is the smallest one.
    sizes = [max((p for p in v.values() if p), default=0) for k, v in pops.items() if k not in SKIP_POP_KEYS]
    if not actual or not sizes:
        return None
    months = [v for v in actual.values() if v is not None]
    popv = min(s for s in sizes) if sizes else 0
    if len(months) < 6 or popv < 1000:
        return None
    rate = sum(months) / len(months) * 12 / popv * 100_000
    # Rough city radius: suburbs are about 1500 people per km².
    radius = min(15.0, max(1.5, math.sqrt(popv / (1500 * math.pi))))
    town = a["agency_name"].replace(" Police Department", "").replace(" Police Dept", "").strip()
    return (town, a["state_abbr"], round(a["latitude"], 5), round(a["longitude"], 5), popv, round(rate), round(radius, 2))


def main():
    frm, to = month_range()
    print("period", frm, "->", to, flush=True)
    rows = []
    for st in STATES:
        ags = agencies(st)
        with ThreadPoolExecutor(4) as ex:
            got = [r for r in ex.map(lambda a: row(a, frm, to), ags) if r]
        rows += got
        print(f"{st}: {len(got)}/{len(ags)} cities, total {len(rows)}", flush=True)
    rows.sort(key=lambda r: (r[1], r[0]))
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Violent crime per 100k, FBI CDE, {frm}..{to}\n")
        f.write("town\tstate\tlat\tlon\tpopulation\trate\tradius_km\n")
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")
    print("written", len(rows), "towns ->", OUT)


if __name__ == "__main__":
    main()
