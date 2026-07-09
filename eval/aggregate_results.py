#!/usr/bin/env python3
"""Agregă foile de notare completate în cifrele pentru Tabelele 6.2 / 6.3.

Utilizare:
  python aggregate_results.py results/20260702-154819            # matricea CU dovezi
  python aggregate_results.py results/20260702-162713            # matricea FĂRĂ dovezi

Citește grading_sheet_prefilled.csv (după ce ai verificat/completat notele) și scoate:
  - Tabelul 6.2 (config completă = modul dynamic): cauză (vot majoritar), remediere
    (mediană), halucinație (oricare din rulări), timp (mediană) — per scenariu + global
  - Tabelul 6.3: cauza corectă per scenariu × configurație (vot majoritar) + totaluri
  - declanșările NEEDS_SEARCH per configurație

Reguli: celulă = 3 rulări; cauza = majoritar (>=2/3); remediere = mediana notelor
completate; halucinație = 1 dacă apare în oricare rulare; timp = mediana.
"""

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

MODES = ["none", "static", "dynamic"]
SCEN = [f"s{i:02d}" for i in range(1, 14)]


def to_int(v):
    v = str(v).strip()
    return int(float(v)) if v not in ("", "None") else None


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    folder = Path(sys.argv[1])
    path = folder / "grading_sheet_prefilled.csv"
    rows = list(csv.DictReader(open(path, encoding="utf-8")))

    cells = defaultdict(list)
    for r in rows:
        cells[(r["scenario"], r["mode"])].append(r)

    def cell(sid, mode):
        rs = cells.get((sid, mode), [])
        cauza = [to_int(r["cauza_corecta(0/1)"]) for r in rs]
        cauza = [c for c in cauza if c is not None]
        rem = [to_int(r["remediere(0-2)"]) for r in rs]
        rem = [x for x in rem if x is not None]
        hal = [to_int(r["halucinatie(0/1)"]) for r in rs]
        hal = [x for x in hal if x is not None]
        lat = [float(r["latency_s"]) for r in rs if float(r["latency_s"]) > 0]
        ns = sum(to_int(r["needs_search"]) or 0 for r in rs)
        return dict(
            cauza=(sum(cauza) * 2 > len(cauza)) if cauza else None,
            remediere=statistics.median(rem) if rem else None,
            halucinatie=(max(hal) if hal else None),
            timp=round(statistics.median(lat), 1) if lat else None,
            needs_search=ns,
            n=len(rs),
        )

    print(f"=== {folder.name} ===\n")
    print("--- Tabelul 6.2 (config completa = dynamic): scenariu | cauza | remediere | halucinatie | timp(s) ---")
    g_cauza, g_rem, g_hal, g_timp = [], [], [], []
    for sid in SCEN:
        c = cell(sid, "dynamic")
        if not c["n"]:
            continue
        print(f"{sid} | {'DA' if c['cauza'] else 'NU' if c['cauza'] is not None else '?'} | "
              f"{c['remediere'] if c['remediere'] is not None else '?'} | "
              f"{c['halucinatie'] if c['halucinatie'] is not None else '?'} | {c['timp']}")
        if c["cauza"] is not None: g_cauza.append(c["cauza"])
        if c["remediere"] is not None: g_rem.append(c["remediere"])
        if c["halucinatie"] is not None: g_hal.append(c["halucinatie"])
        if c["timp"] is not None: g_timp.append(c["timp"])
    if g_cauza:
        print(f"GLOBAL | {sum(g_cauza)}/{len(g_cauza)} cauze corecte | "
              f"remediere mediana {statistics.median(g_rem) if g_rem else '?'} | "
              f"halucinatii {sum(g_hal)}/{len(g_hal)} scenarii | "
              f"timp median {statistics.median(g_timp):.1f}s")

    print("\n--- Tabelul 6.3: cauza corecta per configuratie (vot majoritar) ---")
    print("scenariu | " + " | ".join(MODES))
    tot = {m: 0 for m in MODES}
    for sid in SCEN:
        line = [sid]
        for m in MODES:
            c = cell(sid, m)
            v = "DA" if c["cauza"] else "NU" if c["cauza"] is not None else "?"
            if c["cauza"]: tot[m] += 1
            line.append(v)
        print(" | ".join(line))
    print("TOTAL | " + " | ".join(f"{tot[m]}/13" for m in MODES))

    print("\n--- Declansari NEEDS_SEARCH per configuratie ---")
    for m in MODES:
        ns = sum(cell(sid, m)["needs_search"] for sid in SCEN)
        lats = [float(r["latency_s"]) for r in rows if r["mode"] == m and float(r["latency_s"]) > 0]
        print(f"{m}: {ns} declansari | timp median {statistics.median(lats):.1f}s | max {max(lats):.1f}s")


if __name__ == "__main__":
    main()
