# Ghid — teste reale pe aplicația e-learning (secțiunea 6.7)

13 incidente provocate manual pe aplicația reală din namespace-ul `elearning`
(11 din Teste.docx + două din sugestii.docx: 504 și 404), diagnosticate prin
Kubexplain cu **protocolul de escaladare în max 5 prompturi**. Configurația: `dynamic`.

## Ce măsurăm (și de ce e diferit de matricea sintetică)

Matricea sintetică a măsurat *dacă* sistemul găsește cauza cu dovezi complete atașate
(rezultat: plafon 13/13). Aici măsurăm *cât de repede* ajunge la diagnostic un operator
care escaladează treptat, ca în realitate:

| Prompt | Ce primește AI-ul |
|---|---|
| P1 | doar simptomul, în cuvintele operatorului, fără dovezi |
| P2 | + prima verificare a unui operator real: `get pods` **+ logurile podului afectat** (unde există) / `get pvc` / răspunsul HTTP |
| P3 | + `describe`/YAML-ul obiectului afectat |
| P4 | + events / configurație detaliată (ConfigMap, StorageClass, loguri anterioare) |
| P5 | + toate dovezile, cu cerere explicită de cauză exactă |

Logurile intră deci de la P2 (t01, t07, t08, t09, t10 — cazurile unde podul produce
loguri; la ImagePull/Pending/ContainerCreating/ingress nu există loguri utile, acolo
P2 conține dovada primară relevantă). P1 rămâne intenționat fără dovezi: răspunsul
generic de acolo nu e un defect, e linia de bază a metricii — primește `n` și se
escaladează. Abia de la P2 răspunsul devine țintit pe cazul concret și verdictul
`d`/`n` se dă ușor.

Metrici: **numărul de prompturi până la diagnostic corect** (EȘEC dacă >5),
**timpul AI cumulat**, plus remedierea (0/1/2) și halucinația (0/1) după
criteriile deja folosite la Tabelul 6.2. Tu ești evaluatorul: după fiecare
răspuns decizi `d`/`n` comparând cu „Cauza așteptată" afișată de script.

Important pentru consecvență: `d` = răspunsul identifică **cauza reală specifică**
(ex. „parola DB greșită"), nu o listă generică de cauze posibile în care se
nimerește și cea reală. Lista generică la P1 e normală și primește `n`.

## Pregătire (o singură dată)

```powershell
# terminal 1 — port-forward la AI server
kubectl --kubeconfig $HOME\.kube\licenta-cluster.yaml -n kubexplain port-forward svc/ai-server 8090:8090

# terminal 2 — rularea testelor
cd C:\Users\axine\Desktop\Licenta_v2\Proiect\eval
python run_real.py --kubeconfig $HOME\.kube\licenta-cluster.yaml
```

Scriptul comută singur modul RAG pe `dynamic`, afișează comenzile de injectare
pentru fiecare test (le rulezi tu, în terminal separat, din `Proiect` ca să
existe folderul `k8s/`), trimite prompturile, cronometrează și salvează totul în
`results_real/<timestamp>/` (transcrieri `.md`, `results.jsonl`, `summary.csv`).

Poți rula și bucăți: `python run_real.py --cases t01,t06 --kubeconfig ...` —
rezultatele se pot uni ulterior (fiecare rulare are directorul ei; păstrează-le).

## Ordinea testelor (respect-o!)

`t01 → t02 → t03 → t05 → t06 → t07 → t08 → t09 → t11 → t12 → t13 → t04 → t10`

De ce: **t04 e distructiv** (șterge StatefulSet + PVC → pierzi datele din baza
e-learning) — de aceea e penultimul. **t10 cere baza de date goală** (init.sql
rulează doar la prima inițializare) — de aceea e ultimul, imediat după t04, care
lasă DB-ul proaspăt. Restul testelor sunt reversibile.

## Avertismente punctuale

- **t04 / t10**: pierdere de date în `elearning`. Dacă ai date de demo la care ții,
  fă înainte un dump: `kubectl ... exec postgres-0 -n elearning -- pg_dump -U <user> ELearning_db > backup.sql`.
- **t09 / t10**: folosesc `kubectl edit` — editorul se deschide în terminalul tău;
  modifică exact ce scrie în comentariul comenzii.
- **t12 (504)**: are două componente (CPU 50m pe backend + `proxy-read-timeout=1`
  pe ingress). După injectare, verifică întâi tu cu `curl.exe -i http://203.25.143.55/api/`
  că primești 504; dacă backend-ul răspunde totuși sub 1s, scade și mai mult CPU (25m).
- **t13 (404)**: verifică cu `curl.exe -i http://203.25.143.55/` că primești 404
  înainte să confirmi scriptului.
- Între teste, confirmă baseline-ul: `kubectl ... get pods -n elearning` — totul
  Running/Ready. Dacă ceva rămâne stricat, folosește „Revenire Baseline" din Teste.docx
  (delete ns + create ns + apply -f k8s/) — dar atunci refaci și datele.

## După rulare

Trimite-mi în conversație conținutul `summary.csv` (sau output-ul
`python run_real.py --report results_real/<timestamp>`) și spune-mi dacă au fost
cazuri cu observații speciale (EȘEC, halucinații, răspunsuri ciudate la P1).
Scriu eu secțiunea 6.7 în teză cu tabelul și concluziile.

## Restul sugestiilor din sugestii.docx (nu le implementăm acum)

500 (backend crash — acoperit implicit de t01/t07/t08), 502 (headere malformate),
429 (rate limit), 301/302 (bucle de redirect), Evicted, migrări eșuate la deploy,
RunContainerError — cer configurări/cod suplimentar și nu schimbă concluziile.
Le menționăm în teză la limitări/lucru viitor ca extinderi ale suitei de teste.
