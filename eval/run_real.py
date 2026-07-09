#!/usr/bin/env python3
"""Kubexplain — teste reale pe aplicația e-learning (Capitolul 6, secțiunea 6.7).

Protocol de escaladare conversațională (max 5 prompturi per incident):

  P1  întrebare vagă, la nivel de simptom, FĂRĂ dovezi (operatorul abia a observat problema)
  P2  + starea resurselor (get pods / get pvc / răspuns HTTP)
  P3  + describe / YAML al obiectului afectat
  P4  + logs / events / configurație detaliată
  P5  + TOATE dovezile împreună, cu cerere explicită de cauză exactă

După fiecare răspuns, operatorul (evaluator uman) decide: diagnostic corect? [d/n].
La [d] se opresc prompturile și se notează remedierea (0/1/2) și halucinația (0/1),
după aceleași criterii ca în evaluarea sintetică (Tabelul 6.2).

Metrici per incident: numărul de prompturi până la diagnostic corect (sau EȘEC după 5),
latența AI per prompt, latența AI cumulată. Toate rulările folosesc ACEEAȘI conversație
(conversation_id constant per incident) — istoricul e menținut de server.

Flux per test:
  1. scriptul afișează comenzile de injectare → operatorul le rulează manual în alt terminal
  2. operatorul confirmă că simptomul e vizibil → începe bucla de escaladare
  3. la final scriptul afișează comenzile de fix → operatorul readuce aplicația la baseline

Utilizare (după port-forward la AI server):
  kubectl --kubeconfig $HOME/.kube/licenta-cluster.yaml -n kubexplain port-forward svc/ai-server 8090:8090
  python run_real.py --kubeconfig $HOME/.kube/licenta-cluster.yaml                 # toate cazurile, în ordinea recomandată
  python run_real.py --kubeconfig $HOME/.kube/licenta-cluster.yaml --cases t01,t06 # doar unele
  python run_real.py --report results_real/<timestamp>                             # re-afișează sumarul

Cerințe: python3 (stdlib), kubectl, AI serverul accesibil, aplicația e-learning deployată.
"""

import argparse
import csv
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
CATALOG = HERE / "cazuri" / "reale.json"

CHAT_TIMEOUT_S = 300
EVIDENCE_MAX_CHARS = 15000
HTTP_BODY_MAX = 2500
MAX_TURNS = 5

# Ordinea recomandată: testele distructive (t04 șterge datele DB, t10 cere DB gol) la final.
DEFAULT_ORDER = ["t01", "t02", "t03", "t05", "t06", "t07", "t08", "t09",
                 "t11", "t12", "t13", "t04", "t10"]

FOLLOWUPS = {
    2: "I looked into your suggestion but the problem persists. I collected this from the "
       "cluster (attached: {arts}). What is the root cause in MY case, specifically?",
    3: "Still not resolved. I have attached more detail ({arts}). What is the root cause "
       "in my case, specifically?",
    4: "The problem is still there. I have attached {arts}. What is the exact root cause?",
    5: "This is everything I have — attached: {arts}. Based strictly on the attached "
       "evidence, state the exact root cause and the exact fix for my case.",
}

KUBECONFIG = None


def kubectl(kubectl_bin, args):
    cmd = [kubectl_bin] + (["--kubeconfig", KUBECONFIG] if KUBECONFIG else []) + args
    p = subprocess.run(cmd, capture_output=True, text=True)
    return ((p.stdout or "") + (("\n" + p.stderr) if p.stderr else "")).strip()


def http_json(method, url, payload=None, timeout=30):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode()
    return json.loads(body) if body else {}


def http_probe(app_url, path):
    """GET către aplicația e-learning; întoarce status + început de corp (ca un curl -i)."""
    url = app_url.rstrip("/") + path
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read(HTTP_BODY_MAX).decode(errors="replace")
            return f"GET {url}\nHTTP {r.status}\n\n{body}"
    except urllib.error.HTTPError as e:
        body = e.read(HTTP_BODY_MAX).decode(errors="replace")
        return f"GET {url}\nHTTP {e.code} {e.reason}\n\n{body}"
    except Exception as e:
        return f"GET {url}\nEROARE DE CONEXIUNE: {e}"


def gather_level(case, level, kubectl_bin, app_url):
    """Colectează dovezile PROASPETE pentru un nivel de escaladare."""
    artifacts = []
    for ev in case["evidence"].get(str(level), []):
        if "http_path" in ev:
            out = http_probe(app_url, ev["http_path"])
        else:
            out = kubectl(kubectl_bin, ev["kubectl"])
        if out:
            artifacts.append({"type": ev["type"], "target": ev["target"], "level": 1,
                              "content": out[:EVIDENCE_MAX_CHARS]})
    return artifacts


def ask(ai_url, conv_id, text, artifacts):
    payload = {
        "protocol_version": "kdiag/1.0",
        "conversation_id": conv_id,
        "message": {"role": "user", "text": text},
        "artifacts": artifacts,
        "recordExchange": False,
    }
    t0 = time.time()
    resp = http_json("POST", f"{ai_url}/v1/chat", payload, timeout=CHAT_TIMEOUT_S)
    elapsed = time.time() - t0
    answer = (resp.get("assistant_message") or {}).get("text", "") if isinstance(resp, dict) else ""
    fetched = (resp.get("source_urls") if isinstance(resp, dict) else None) or []
    return answer, elapsed, fetched


def ask_verdict(prompt, valid):
    while True:
        v = input(prompt).strip().lower()
        if v in valid:
            return v
        print(f"  (răspunde cu una din: {'/'.join(sorted(valid))})")


def run_case(case, ai_url, app_url, kubectl_bin, outdir):
    cid = case["id"]
    print("\n" + "=" * 78)
    print(f"  {cid.upper()} — {case['title']}")
    print("=" * 78)
    if case.get("warning"):
        print(f"\n  !!! ATENȚIE: {case['warning']}")
    print("\n  Comenzi de INJECTARE (rulează-le manual în alt terminal PowerShell):\n")
    for c in case["break"]:
        print(f"    {c}")
    print()
    if ask_verdict("  Defect injectat și simptom vizibil? [d = da, continuăm / s = sar peste acest test] ", {"d", "s"}) == "s":
        print(f"  {cid} sărit.")
        return None

    conv_id = f"real-{cid}-{uuid.uuid4()}"
    transcript = [f"# {cid.upper()} — {case['title']}",
                  f"- Data: {datetime.now().isoformat(timespec='seconds')}",
                  f"- conversation_id: `{conv_id}`",
                  f"- Cauza așteptată: {case['expected']}", ""]
    turns = []
    correct_at = None
    all_fetched = []

    for turn in range(1, MAX_TURNS + 1):
        if turn == 1:
            text, artifacts = case["question"], []
        elif turn < MAX_TURNS:
            artifacts = gather_level(case, turn, kubectl_bin, app_url)
        else:  # P5: tot ce avem, colectat proaspăt
            artifacts = []
            for lvl in (2, 3, 4):
                artifacts += gather_level(case, lvl, kubectl_bin, app_url)

        art_desc = ", ".join(a["type"] for a in artifacts) if artifacts else "fără dovezi"
        if turn > 1:
            text = FOLLOWUPS[turn].format(arts=art_desc if artifacts else "nothing new")
        print(f"\n  --- Prompt {turn}/{MAX_TURNS} ({art_desc}) — aștept răspunsul AI...")
        try:
            answer, elapsed, fetched = ask(ai_url, conv_id, text, artifacts)
        except Exception as e:
            print(f"  EROARE la apelul AI: {e}")
            answer, elapsed, fetched = f"[EROARE: {e}]", 0.0, []
        all_fetched += [u for u in fetched if u not in all_fetched]

        print(f"\n  Răspuns AI (după {elapsed:.1f}s):\n")
        print("  " + "\n  ".join(answer.splitlines() or ["<gol>"]))
        print(f"\n  [Cauza așteptată: {case['expected']}]")

        transcript += [f"## Prompt {turn} ({art_desc}) — {elapsed:.1f}s",
                       f"**User:** {text}", "", f"**AI:** {answer}", ""]
        turns.append({"turn": turn, "latency_s": round(elapsed, 1),
                      "artifacts": [a["type"] for a in artifacts]})

        if ask_verdict("  Diagnostic corect (cauza reală identificată)? [d/n] ", {"d", "n"}) == "d":
            correct_at = turn
            break

    result = {"id": cid, "title": case["title"], "conversation_id": conv_id,
              "correct_at_turn": correct_at, "turns": turns,
              "ai_time_total_s": round(sum(t["latency_s"] for t in turns), 1),
              "fetched_urls": all_fetched}

    if correct_at:
        rem = ask_verdict("  Nota remedierii (criteriile din 6.2)? [0/1/2] ", {"0", "1", "2"})
        hal = ask_verdict("  Halucinație în răspunsuri (link mort/comandă inventată)? [0/1] ", {"0", "1"})
        result["remediere"], result["halucinatie"] = int(rem), int(hal)
        print(f"\n  ✔ {cid}: diagnostic corect la promptul {correct_at}, "
              f"timp AI cumulat {result['ai_time_total_s']}s")
    else:
        result["remediere"], result["halucinatie"] = 0, None
        hal = ask_verdict("  Halucinație în răspunsuri? [0/1] ", {"0", "1"})
        result["halucinatie"] = int(hal)
        print(f"\n  ✘ {cid}: EȘEC — diagnostic incorect după {MAX_TURNS} prompturi")

    transcript += ["## Rezultat",
                   f"- Diagnostic corect la promptul: {correct_at or f'EȘEC (>{MAX_TURNS})'}",
                   f"- Timp AI cumulat: {result['ai_time_total_s']}s",
                   f"- Remediere: {result['remediere']} | Halucinație: {result['halucinatie']}",
                   f"- Surse aduse de căutarea dinamică: {', '.join(all_fetched) or 'niciuna'}", ""]

    (outdir / f"{cid}.md").write_text("\n".join(transcript), encoding="utf-8")
    with (outdir / "results.jsonl").open("a", encoding="utf-8") as f:
        f.write(json.dumps(result, ensure_ascii=False) + "\n")

    print("\n  Comenzi de FIX (readu aplicația la baseline înainte de testul următor):\n")
    for c in case["fix"]:
        print(f"    {c}")
    input("\n  Apasă Enter când aplicația e la baseline (toate podurile Running/Ready)... ")
    return result


def print_summary(results, outdir=None):
    print("\n" + "=" * 78)
    print("  SUMAR — teste reale pe aplicația e-learning (config dynamic)")
    print("=" * 78)
    print(f"  {'test':6} | {'prompturi':>9} | {'timp AI (s)':>11} | {'remediere':>9} | {'halucinatie':>11}")
    print("  " + "-" * 60)
    rows = []
    for r in results:
        p = str(r["correct_at_turn"]) if r["correct_at_turn"] else f"EȘEC"
        print(f"  {r['id']:6} | {p:>9} | {r['ai_time_total_s']:>11} | "
              f"{r['remediere']:>9} | {str(r['halucinatie']):>11}")
        rows.append([r["id"], r["title"], p, r["ai_time_total_s"],
                     r["remediere"], r["halucinatie"]])
    ok = [r for r in results if r["correct_at_turn"]]
    if results:
        prompts = sorted(r["correct_at_turn"] for r in ok)
        med_p = prompts[len(prompts) // 2] if prompts else "-"
        times = sorted(r["ai_time_total_s"] for r in ok)
        med_t = times[len(times) // 2] if times else "-"
        print("  " + "-" * 60)
        print(f"  GLOBAL: {len(ok)}/{len(results)} diagnosticate corect în ≤{MAX_TURNS} prompturi | "
              f"mediana prompturi {med_p} | mediana timp AI {med_t}s")
    if outdir:
        with (outdir / "summary.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["test", "titlu", "prompturi_pana_la_diagnostic", "timp_ai_total_s",
                        "remediere", "halucinatie"])
            w.writerows(rows)
        print(f"\n  Salvat: {outdir / 'summary.csv'}")


def main():
    global KUBECONFIG
    ap = argparse.ArgumentParser(description="Kubexplain — teste reale e-learning (escaladare max 5 prompturi)")
    ap.add_argument("--ai-url", default="http://localhost:8090")
    ap.add_argument("--app-url", default=None, help="URL-ul aplicației e-learning (implicit din catalog)")
    ap.add_argument("--cases", default="all", help="ex: t01,t06,t13 sau 'all' (ordinea recomandată)")
    ap.add_argument("--kubectl", default="kubectl")
    ap.add_argument("--kubeconfig", default=None)
    ap.add_argument("--catalog", default=str(CATALOG))
    ap.add_argument("--skip-ragmode", action="store_true", help="nu comuta modul RAG la pornire")
    ap.add_argument("--report", default=None, help="doar re-afișează sumarul dintr-un director de rezultate")
    args = ap.parse_args()
    KUBECONFIG = args.kubeconfig

    if args.report:
        d = Path(args.report)
        results = [json.loads(l) for l in (d / "results.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        print_summary(results, d)
        return

    catalog = json.loads(Path(args.catalog).read_text(encoding="utf-8"))
    app_url = args.app_url or catalog.get("app_url", "http://203.25.143.55")
    by_id = {c["id"]: c for c in catalog["cases"]}
    ids = DEFAULT_ORDER if args.cases == "all" else [s.strip() for s in args.cases.split(",")]
    unknown = [i for i in ids if i not in by_id]
    if unknown:
        sys.exit(f"Cazuri necunoscute: {unknown}. Disponibile: {sorted(by_id)}")

    # sănătate: AI server accesibil?
    try:
        http_json("GET", f"{args.ai_url}/v1/metrics", timeout=10)
    except Exception as e:
        sys.exit(f"AI serverul nu răspunde la {args.ai_url} ({e}). Ai făcut port-forward?")

    if not args.skip_ragmode:
        try:
            http_json("POST", f"{args.ai_url}/v1/config/rag-mode?value=dynamic", timeout=10)
            print("Mod RAG comutat pe: dynamic (configurația de producție)")
        except Exception as e:
            print(f"AVERTISMENT: nu am putut comuta modul RAG ({e}) — verifică manual că e 'dynamic'.")

    outdir = HERE / "results_real" / datetime.now().strftime("%Y%m%d-%H%M%S")
    outdir.mkdir(parents=True, exist_ok=True)
    print(f"Rezultatele se salvează în: {outdir}")
    print(f"Cazuri de rulat, în ordine: {', '.join(ids)}")

    results = []
    for i in ids:
        r = run_case(by_id[i], args.ai_url, app_url, args.kubectl, outdir)
        if r:
            results.append(r)
    if results:
        print_summary(results, outdir)
    print("\nGata. Transcrierile complete sunt în fișierele .md din directorul de rezultate.")


if __name__ == "__main__":
    main()
