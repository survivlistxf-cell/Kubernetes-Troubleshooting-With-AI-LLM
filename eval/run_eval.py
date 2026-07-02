#!/usr/bin/env python3
"""Kubexplain — evaluare pe erori injectate (Capitolul 6).

Rulează cele 13 scenarii în cele 3 configurații RAG (none | static | dynamic),
cu N rulări per celulă, și salvează răspunsurile + metricile pentru notare manuală.

Flux per configurație:
  1. POST /v1/config/rag-mode?value=<mode>     (comutare fără restart)
  2. POST /v1/metrics/reset
  3. pentru fiecare scenariu: kubectl apply → așteaptă simptomul → colectează dovezi
     (describe/logs/events) → N× POST /v1/chat (conversation_id nou, recordExchange=false)
     → kubectl delete
  4. GET /v1/metrics → snapshot salvat (include needsSearchTriggers)

Utilizare:
  python run_eval.py                        # tot: 13 scenarii × 3 moduri × 3 rulări
  python run_eval.py --modes static,dynamic --runs 1 --scenarios s01,s03
  python run_eval.py --ai-url http://localhost:8090

Rulare pe clusterul OpenStack (AI serverul e ClusterIP, deci întâi port-forward):
  kubectl --kubeconfig $HOME/.kube/licenta-cluster.yaml -n kubexplain port-forward svc/ai-server 8090:8090
  python run_eval.py --kubeconfig $HOME/.kube/licenta-cluster.yaml --ai-url http://localhost:8090

Scenarii manuale (studiu de caz, ex. testele pe aplicația e-learning):
  1. provoci defectul manual (patch/set env/etc.);
  2. python run_eval.py --adhoc cazuri/case-db-password.json --modes dynamic
     (pune întrebarea din JSON cu dovezile colectate prin kubectl, fără apply/delete);
  3. repari defectul manual.

Cerințe: python3 (stdlib), kubectl configurat pe clusterul de test, AI serverul pornit.
"""

import argparse
import csv
import json
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCEN_DIR = HERE / "scenarios"

CHAT_TIMEOUT_S = 300          # inferența poate dura minute
EVIDENCE_MAX_CHARS = 15000    # sub limita @Size(20000) a artifact.content

# ---------------------------------------------------------------------------
# Catalogul scenariilor: manifest, cum așteptăm simptomul, ce dovezi atașăm,
# întrebarea standard (EN) și cuvintele-cheie așteptate (ajutor la notare).
# ---------------------------------------------------------------------------
SCENARIOS = [
    dict(id="s01", manifest="s01-crashloopbackoff.yaml",
         wait=("describe", "pod/s01-crashloop", "kubexplain-eval", "CrashLoopBackOff|Back-off restarting", 120),
         evidence=[("pod_describe", "pod/kubexplain-eval/s01-crashloop",
                    ["describe", "pod", "s01-crashloop", "-n", "kubexplain-eval"]),
                   ("logs", "pod/kubexplain-eval/s01-crashloop",
                    ["logs", "s01-crashloop", "-n", "kubexplain-eval", "--previous", "--tail=100"])],
         question="My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. "
                  "What is the root cause and how do I fix it?",
         expected="app exits at startup (exit code 1 / bad command); fix command or image",
         keywords=["CrashLoopBackOff", "exit", "command"]),

    dict(id="s02", manifest="s02-imagepullbackoff.yaml",
         wait=("describe", "pod/s02-imagepull", "kubexplain-eval", "ImagePullBackOff|ErrImagePull", 120),
         evidence=[("pod_describe", "pod/kubexplain-eval/s02-imagepull",
                    ["describe", "pod", "s02-imagepull", "-n", "kubexplain-eval"])],
         question="My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. "
                  "What is the root cause and how do I fix it?",
         expected="image cannot be pulled (bad name/tag or missing credentials); fix image reference",
         keywords=["ImagePullBackOff", "image", "pull"]),

    dict(id="s03", manifest="s03-oomkilled.yaml",
         wait=("describe", "pod/s03-oomkilled", "kubexplain-eval", "OOMKilled", 180),
         evidence=[("pod_describe", "pod/kubexplain-eval/s03-oomkilled",
                    ["describe", "pod", "s03-oomkilled", "-n", "kubexplain-eval"])],
         question="My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and "
                  "restarted. What is the root cause and how do I fix it?",
         expected="container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage",
         keywords=["OOMKilled", "memory", "limit"]),

    dict(id="s04", manifest="s04-cpu-throttling.yaml",
         wait=("sleep", 45),
         evidence=[("pod_describe", "pod/kubexplain-eval/s04-cpu-throttle",
                    ["describe", "pod", "s04-cpu-throttle", "-n", "kubexplain-eval"])],
         question="My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the "
                  "application inside is extremely slow and unresponsive. The manifest and describe "
                  "output are attached. What is the root cause and how do I fix it?",
         expected="cpu limit far too low (25m) causes heavy throttling; raise limits.cpu",
         keywords=["CPU", "throttl", "limit"]),

    dict(id="s05", manifest="s05-pending-unschedulable.yaml",
         wait=("describe", "pod/s05-pending", "kubexplain-eval", "FailedScheduling|Insufficient", 120),
         evidence=[("pod_describe", "pod/kubexplain-eval/s05-pending",
                    ["describe", "pod", "s05-pending", "-n", "kubexplain-eval"])],
         question="My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. "
                  "What is the root cause and how do I fix it?",
         expected="no node satisfies the resource requests; lower requests or add capacity",
         keywords=["Pending", "Insufficient", "requests", "schedul"]),

    dict(id="s06", manifest="s06-rbac-forbidden.yaml",
         wait=("sleep", 60),
         evidence=[("logs", "pod/kubexplain-eval/s06-rbac",
                    ["logs", "s06-rbac", "-n", "kubexplain-eval", "--tail=50"]),
                   ("pod_describe", "pod/kubexplain-eval/s06-rbac",
                    ["describe", "pod", "s06-rbac", "-n", "kubexplain-eval"])],
         question="An application running as service account 's06-limited-sa' in namespace "
                  "'kubexplain-eval' gets an error when listing secrets. The logs are attached. "
                  "What is the root cause and how do I fix it?",
         expected="RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule",
         keywords=["Forbidden", "RBAC", "Role", "permission"]),

    dict(id="s07", manifest="s07-missing-configmap.yaml",
         wait=("describe", "pod/s07-missing-cm", "kubexplain-eval", "CreateContainerConfigError|configmap .* not found", 120),
         evidence=[("pod_describe", "pod/kubexplain-eval/s07-missing-cm",
                    ["describe", "pod", "s07-missing-cm", "-n", "kubexplain-eval"])],
         question="My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its "
                  "container. What is the root cause and how do I fix it?",
         expected="referenced ConfigMap does not exist; create it or fix the reference",
         keywords=["ConfigMap", "not found", "CreateContainerConfigError"]),

    dict(id="s08", manifest="s08-bad-liveness.yaml",
         wait=("describe", "pod/s08-bad-liveness", "kubexplain-eval", "Liveness probe failed|Killing", 180),
         evidence=[("pod_describe", "pod/kubexplain-eval/s08-bad-liveness",
                    ["describe", "pod", "s08-bad-liveness", "-n", "kubexplain-eval"])],
         question="My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being "
                  "restarted even though nginx itself works fine. What is the root cause and how "
                  "do I fix it?",
         expected="liveness probe checks the wrong port (8081 vs 80); fix the probe",
         keywords=["liveness", "probe", "port"]),

    dict(id="s09", manifest="s09-bad-readiness.yaml",
         wait=("describe", "pod/s09-bad-readiness", "kubexplain-eval", "Readiness probe failed", 120),
         evidence=[("pod_describe", "pod/kubexplain-eval/s09-bad-readiness",
                    ["describe", "pod", "s09-bad-readiness", "-n", "kubexplain-eval"]),
                   ("events", "service/kubexplain-eval/s09-web",
                    ["get", "endpoints", "s09-web", "-n", "kubexplain-eval", "-o", "yaml"])],
         question="My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but "
                  "receives no traffic from service 's09-web' (its endpoints are empty). "
                  "What is the root cause and how do I fix it?",
         expected="readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe",
         keywords=["readiness", "probe", "endpoint"]),

    dict(id="s10", manifest="s10-networkpolicy-deny.yaml",
         wait=("sleep", 60),
         evidence=[("logs", "pod/kubexplain-eval/s10-client",
                    ["logs", "s10-client", "-n", "kubexplain-eval", "--tail=30"]),
                   ("pod_describe", "pod/kubexplain-eval/s10-server",
                    ["describe", "pod", "s10-server", "-n", "kubexplain-eval"]),
                   ("events", "networkpolicy/kubexplain-eval",
                    ["get", "networkpolicy", "-n", "kubexplain-eval", "-o", "yaml"])],
         question="Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service "
                  "'s10-server' in the same namespace (requests time out), although the server pod "
                  "is healthy. The network policies of the namespace are attached. What is the "
                  "root cause and how do I fix it?",
         expected="deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy",
         keywords=["NetworkPolicy", "ingress", "deny"]),

    # --- Scenarii pentru regăsirea DINAMICĂ (documentație exclusă din baza statică) ---
    dict(id="s11", manifest="s11-podsecurity-restricted.yaml", expect_apply_error=True,
         wait=("sleep", 5),
         evidence=[("events", "namespace/kubexplain-eval-psa",
                    ["get", "events", "-n", "kubexplain-eval-psa",
                     "--sort-by=.lastTimestamp"])],
         question="I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. "
                  "The API server rejects it with the attached error message. What is the root "
                  "cause and how do I fix it?",
         expected="Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext",
         keywords=["Pod Security", "restricted", "runAsNonRoot", "admission"]),

    dict(id="s12", manifest="s12-hpa-misconfigured.yaml",
         wait=("describe", "hpa/s12-hpa", "kubexplain-eval", "FailedGetScale|not found", 120),
         evidence=[("events", "hpa/kubexplain-eval/s12-hpa",
                    ["describe", "hpa", "s12-hpa", "-n", "kubexplain-eval"])],
         question="My HorizontalPodAutoscaler 's12-hpa' in namespace 'kubexplain-eval' does not "
                  "scale anything. Its describe output is attached. What is the root cause and how "
                  "do I fix it?",
         expected="HPA targets a Deployment that does not exist (FailedGetScale); fix scaleTargetRef",
         keywords=["HorizontalPodAutoscaler", "scaleTargetRef", "not found"]),

    dict(id="s13", manifest="s13-job-backofflimit.yaml",
         wait=("describe", "job/s13-failing-job", "kubexplain-eval", "BackoffLimitExceeded|Failed", 180),
         evidence=[("events", "job/kubexplain-eval/s13-failing-job",
                    ["describe", "job", "s13-failing-job", "-n", "kubexplain-eval"]),
                   ("logs", "job/kubexplain-eval/s13-failing-job",
                    ["logs", "job/s13-failing-job", "-n", "kubexplain-eval", "--tail=50"])],
         question="My Job 's13-failing-job' in namespace 'kubexplain-eval' ended in Failed state "
                  "and no pods are retrying anymore. What is the root cause and how do I fix it?",
         expected="job hit backoffLimit after repeated failures (BackoffLimitExceeded); fix the task, raise limit",
         keywords=["backoffLimit", "BackoffLimitExceeded", "Job"]),
]

# ---------------------------------------------------------------------------


KUBECONFIG = None  # setat din --kubeconfig; adăugat automat la fiecare apel kubectl


def kubectl(kubectl_bin, args, check=False):
    """Rulează kubectl și întoarce (rc, stdout+stderr)."""
    cmd = [kubectl_bin] + (["--kubeconfig", KUBECONFIG] if KUBECONFIG else []) + args
    p = subprocess.run(cmd, capture_output=True, text=True)
    out = (p.stdout or "") + (("\n" + p.stderr) if p.stderr else "")
    if check and p.returncode != 0:
        raise RuntimeError(f"kubectl {' '.join(args)} failed:\n{out}")
    return p.returncode, out.strip()


def http_json(method, url, payload=None, timeout=30):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode()
    return json.loads(body) if body else {}


def wait_for_symptom(kubectl_bin, scen):
    w = scen["wait"]
    if w[0] == "sleep":
        print(f"    waiting {w[1]}s for the symptom to develop...")
        time.sleep(w[1])
        return
    _, kind_name, ns, pattern, timeout = w
    import re
    rx = re.compile(pattern, re.IGNORECASE)
    kind, name = kind_name.split("/", 1)
    deadline = time.time() + timeout
    while time.time() < deadline:
        _, out = kubectl(kubectl_bin, ["describe", kind, name, "-n", ns])
        if rx.search(out):
            print(f"    symptom detected: /{pattern}/")
            time.sleep(5)  # lasă evenimentele să se așeze
            return
        time.sleep(5)
    print(f"    WARNING: symptom /{pattern}/ not seen within {timeout}s — continuing anyway")


def gather_evidence(kubectl_bin, scen, apply_output):
    artifacts = []
    if scen.get("expect_apply_error"):
        artifacts.append({"type": "events", "target": "kubectl apply", "level": 1,
                          "content": apply_output[:EVIDENCE_MAX_CHARS]})
    for a_type, target, args in scen["evidence"]:
        rc, out = kubectl(kubectl_bin, args)
        if out:
            artifacts.append({"type": a_type, "target": target, "level": 1,
                              "content": out[:EVIDENCE_MAX_CHARS]})
    return artifacts


def ask(ai_url, question, artifacts):
    payload = {
        "protocol_version": "kdiag/1.0",
        "conversation_id": f"eval-{uuid.uuid4()}",
        "message": {"role": "user", "text": question},
        "artifacts": artifacts,
        "recordExchange": False,   # nu poluăm qa_feedback / CBR în timpul evaluării
    }
    t0 = time.time()
    resp = http_json("POST", f"{ai_url}/v1/chat", payload, timeout=CHAT_TIMEOUT_S)
    elapsed = time.time() - t0
    text = (resp.get("assistant_message") or {}).get("text", "") if isinstance(resp, dict) else ""
    return text, elapsed


def main():
    ap = argparse.ArgumentParser(description="Kubexplain injected-fault evaluation")
    ap.add_argument("--ai-url", default="http://localhost:8090")
    ap.add_argument("--modes", default="none,static,dynamic")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--scenarios", default="all", help="ex: s01,s03,s11")
    ap.add_argument("--kubectl", default="kubectl")
    ap.add_argument("--kubeconfig", default=None,
                    help="kubeconfig pentru clusterul de test (ex: ~/.kube/licenta-cluster.yaml)")
    ap.add_argument("--adhoc", default=None,
                    help="fișier JSON cu un scenariu manual: {id, question, expected, evidence:[[args kubectl]...]}; "
                         "fără apply/wait/delete — defectul îl provoci și îl repari tu")
    ap.add_argument("--no-evidence", action="store_true",
                    help="NU atașează dovezile (describe/logs) — doar întrebarea. Condiția "
                         "'dovezi incomplete': aici cunoștințele (RAG) contează, nu citirea erorii")
    ap.add_argument("--skip-cluster", action="store_true",
                    help="nu aplică manifeste; refolosește dovezile din ultimul run (debug)")
    args = ap.parse_args()

    global KUBECONFIG
    if args.kubeconfig:
        KUBECONFIG = str(Path(args.kubeconfig).expanduser())

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    if args.adhoc:
        # Format JSON: {"id": "...", "question": "...", "expected": "...", "keywords": [...],
        #   "evidence": [{"type": "pod_describe", "target": "pod/ns/nume", "args": ["describe", ...]}]}
        spec = json.loads(Path(args.adhoc).read_text(encoding="utf-8"))
        scenarios = [dict(id=spec["id"], manifest=None, wait=("sleep", 0),
                          evidence=[(e.get("type", "pod_describe"),
                                     e.get("target", "manual"),
                                     e["args"]) for e in spec.get("evidence", [])],
                          question=spec["question"],
                          expected=spec.get("expected", ""),
                          keywords=spec.get("keywords", []))]
    else:
        wanted = None if args.scenarios == "all" else {s.strip() for s in args.scenarios.split(",")}
        scenarios = [s for s in SCENARIOS if wanted is None or s["id"] in wanted]

    out_dir = HERE / "results" / datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir.mkdir(parents=True)
    print(f"Results -> {out_dir}")

    # sanity: AI server accesibil?
    try:
        current = http_json("GET", f"{args.ai_url}/v1/config/rag-mode")
        print(f"AI server OK, current rag-mode: {current.get('mode')}")
    except Exception as e:
        sys.exit(f"AI server unreachable at {args.ai_url}: {e}")

    if not args.skip_cluster and not args.adhoc:
        kubectl(args.kubectl, ["apply", "-f", str(SCEN_DIR / "namespace.yaml")], check=True)

    results_f = open(out_dir / "results.jsonl", "a", encoding="utf-8")
    summary_rows = []
    evidence_cache = {}

    for mode in modes:
        print(f"\n=== MODE: {mode} ===")
        http_json("POST", f"{args.ai_url}/v1/config/rag-mode?value={mode}")
        http_json("POST", f"{args.ai_url}/v1/metrics/reset")

        for scen in scenarios:
            sid = scen["id"]
            print(f"  [{sid}] {scen['manifest'] or '(scenariu manual — defectul e deja provocat de tine)'}")
            apply_out = ""
            if scen["manifest"] is None:
                # ad-hoc: doar colectăm dovezile din starea curentă a clusterului
                evidence = gather_evidence(args.kubectl, scen, apply_out)
            elif not args.skip_cluster:
                rc, apply_out = kubectl(args.kubectl,
                                        ["apply", "-f", str(SCEN_DIR / scen["manifest"])])
                if rc != 0 and not scen.get("expect_apply_error"):
                    print(f"    APPLY FAILED, skipping scenario:\n{apply_out}")
                    continue
                wait_for_symptom(args.kubectl, scen)
                evidence = gather_evidence(args.kubectl, scen, apply_out)
                evidence_cache[sid] = evidence
            else:
                evidence = evidence_cache.get(sid, [])

            evidence_used = [] if args.no_evidence else evidence
            question = scen["question"]
            if args.no_evidence:
                # Fără dovezi, întrebarea nu are voie să pretindă că ele sunt atașate —
                # altfel modelul (corect) le cere înapoi în loc să diagnosticheze.
                question = re.sub(r'[^.?!]*\battached\b[^.?!]*[.?!]\s*', ' ', question)
                question = re.sub(r'\s+', ' ', question).strip()
                question += (" I cannot access the cluster right now, so list the most likely "
                             "causes in order of probability and explain how to verify and fix "
                             "each one.")
            for run in range(1, args.runs + 1):
                m_before = http_json("GET", f"{args.ai_url}/v1/metrics")
                try:
                    answer, elapsed = ask(args.ai_url, question, evidence_used)
                except Exception as e:
                    answer, elapsed = f"__ERROR__: {e}", -1
                m_after = http_json("GET", f"{args.ai_url}/v1/metrics")
                ns_delta = (m_after.get("totalNeedsSearchTriggers", 0) or 0) - \
                           (m_before.get("totalNeedsSearchTriggers", 0) or 0)

                rec = dict(scenario=sid, mode=mode, run=run,
                           latency_s=round(elapsed, 1), needs_search=ns_delta,
                           evidence_attached=bool(evidence_used),
                           question=question, expected=scen["expected"],
                           keywords=scen["keywords"], answer=answer)
                results_f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                results_f.flush()

                ans_file = out_dir / f"{sid}_{mode}_run{run}.md"
                ans_file.write_text(
                    f"# {sid} | mode={mode} | run={run} | {rec['latency_s']}s | "
                    f"needs_search={ns_delta}\n\n**Q:** {question}\n\n"
                    f"**Expected:** {scen['expected']}\n\n---\n\n{answer}\n",
                    encoding="utf-8")
                summary_rows.append([sid, mode, run, rec["latency_s"], ns_delta,
                                     "", "", ""])  # coloane goale: notare manuală
                print(f"    run {run}: {rec['latency_s']}s, needs_search={ns_delta}")

            if not args.skip_cluster and scen["manifest"] is not None:
                kubectl(args.kubectl, ["delete", "-f", str(SCEN_DIR / scen["manifest"]),
                                       "--ignore-not-found", "--wait=false"])
                time.sleep(3)

        snap = http_json("GET", f"{args.ai_url}/v1/metrics")
        (out_dir / f"metrics_{mode}.json").write_text(
            json.dumps(snap, indent=2), encoding="utf-8")

    results_f.close()
    with open(out_dir / "grading_sheet.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scenario", "mode", "run", "latency_s", "needs_search",
                    "cauza_corecta(0/1)", "remediere(0-2)", "halucinatie(0/1)"])
        w.writerows(summary_rows)

    print(f"\nDone. Grade the answers in {out_dir}/grading_sheet.csv "
          f"(read the {len(summary_rows)} .md files).")


if __name__ == "__main__":
    main()
