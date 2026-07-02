# Evaluarea Kubexplain — erori injectate (Capitolul 6)

13 scenarii cu cauză cunoscută, rulate în 3 configurații RAG, cu 3 rulări per celulă
(13 × 3 × 3 = 117 cereri). Răspunsurile se notează manual după criteriile din teză.

## Configurațiile

| Mod | RAG static | Căutare dinamică | Corespondent în teză |
|---|---|---|---|
| `none` | ✗ | ✗ | fără RAG (doar starea clusterului) |
| `static` | ✓ | ✗ | RAG static, regim izolat (air-gapped) |
| `dynamic` | ✓ | ✓ | RAG static + regăsire dinamică [NEEDS_SEARCH] |

Comutarea se face live prin `POST /v1/config/rag-mode?value=...` — scriptul o face singur.

## Scenariile

- **S01–S10**: incidentele clasice din Tabelul 6.1 (CrashLoopBackOff, ImagePullBackOff,
  OOMKilled, CPU throttling, Pending, RBAC, ConfigMap lipsă, liveness/readiness greșite,
  NetworkPolicy deny-all).
- **S11–S13** (regăsire dinamică): Pod Security Admission `restricted`, HPA cu țintă
  inexistentă, Job cu `backoffLimit` depășit. **Condiție de validitate:** paginile de
  documentație pentru aceste 3 subiecte trebuie să LIPSEASCĂ din baza statică pre-populată
  (altfel nu măsori nimic). Verifică lista URL-urilor indexate înainte de rulare, de ex.:

  ```
  curl -s 'http://localhost:9200/kdiag-chunks/_search?q=url:pod-security*&size=0' | jq .hits.total
  ```

  Dacă apar hituri, re-populează baza statică fără paginile: `concepts/security/pod-security-*`,
  `tasks/run-application/horizontal-pod-autoscale*`, `concepts/workloads/controllers/job*`.

## Cerințe înainte de rulare

1. AI serverul (8090) pornit, cu ElasticSearch + Ollama funcționale.
2. `kubectl` configurat pe clusterul de test (kubeconfig-ul read-only NU e suficient —
   scriptul creează/șterge resurse; folosește un context cu drepturi pe namespace-urile
   `kubexplain-eval` și `kubexplain-eval-psa`).
3. Imagini accesibile din cluster: `busybox:1.36`, `nginx:1.25`, `bitnami/kubectl` (S06).
4. S10 presupune un CNI care aplică NetworkPolicy (altfel exclude-l: `--scenarios` fără s10).
5. S11 presupune Kubernetes ≥ 1.25 (Pod Security Admission activ implicit).

## Rulare

```bash
python run_eval.py                                  # tot (durează: 117 × latența LLM)
python run_eval.py --scenarios s01,s02 --runs 1     # test rapid de fum
python run_eval.py --modes dynamic --scenarios s11,s12,s13
```

## Rezultate și notare

`results/<timestamp>/` conține:
- `results.jsonl` + câte un `.md` per răspuns (pentru citit/notat)
- `grading_sheet.csv` — completezi manual: `cauza_corecta(0/1)`, `remediere(0-2)`,
  `halucinatie(0/1)`, după criteriile din secțiunea 6.4 a tezei
- `metrics_<mode>.json` — snapshot MetricsCollector per configurație (latențe, tokeni,
  declanșări NEEDS_SEARCH)

Agregarea pentru Tabelele 6.2/6.3: cauza corectă per celulă = vot majoritar (2 din 3
rulări); timpul = mediana celor 3 rulări.

Condiția `--no-evidence`: întrebarea e reformulată automat (fără „is attached", plus
cerința de a enumera cauzele probabile). Notare: cauza corectă = cauza reală apare
printre cauzele enumerate (nu neapărat prima); remedierea = cea pentru cauza reală.

## Pragul de relevanță (declanșarea NEEDS_SEARCH)

Retrieval-ul întoarce ACUM context doar dacă cel mai bun hit kNN depășește pragul
`kdiag.retrieval.min-relevance` (implicit **0.85**, scor ES normalizat `(1+cos)/2`,
adică ~cos 0.70). Sub prag, blocul de documentație e gol → modelul emite
`[NEEDS_SEARCH:]` (în modul dynamic) sau răspunde din cunoștințe generale (static).

Calibrare (fără restart):

```bash
# vezi pragul curent / schimbă-l
curl http://localhost:8090/v1/config/min-relevance
curl -X POST 'http://localhost:8090/v1/config/min-relevance?value=0.88'
```

Serverul loghează la fiecare cerere `Relevance gate: kNN top score X (gate Y)` —
rulează un scenariu acoperit de baza statică (ex. s01) și unul exclus (ex. s12),
citește scorurile din log și așază pragul ÎNTRE ele. Dacă s01 dă ~0.90 și s12 dă
~0.83, pragul 0.85–0.87 e corect. Dacă cele două scoruri sunt apropiate, crește
pragul spre scorul lui s01, dar notează în teză valoarea aleasă și cum ai ales-o.

Atenție: după prima declanșare reușită pe un subiect, pagina descărcată dinamic se
indexează (is_dynamic=true) — rulările 2–3 vor găsi context peste prag și NU vor mai
căuta. Asta e comportamentul proiectat (baza învață); în teză raportează-l ca atare
(rularea 1: needs_search=1, rulările 2–3: cache hit). Pentru rulări strict
independente, șterge paginile dinamice între rulări (DELETE FROM kubernetes_doc_pages
WHERE is_dynamic=true; apoi POST /v1/index/rebuild dacă e cazul).

## Curățenie

```bash
kubectl delete namespace kubexplain-eval kubexplain-eval-psa --ignore-not-found
```
