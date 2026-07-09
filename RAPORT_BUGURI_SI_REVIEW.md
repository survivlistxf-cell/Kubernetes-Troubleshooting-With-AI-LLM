# Raport review cod — Kubexplain

Data: 2 iulie 2026. Scope: `backend/` (gateway Spring Boot), `Server/` (AI server Spring Boot),
`frontend/` (JS + Express), `docker-compose.yml`, `k8s/`, plus verificare de concordanță cu
`Documente/Licenta_Axinescu_FINAL_FARA_TESTE.docx`.

Am împărțit constatările în: **bug-uri funcționale**, **securitate**, **spaghetti / datorie tehnică**,
**neconcordanțe cu documentația**. Fiecare are severitate și recomandare concretă.

---

## 1. Bug-uri funcționale

### B1 — [CRITIC] Detecția erorilor de la AI server e moartă (string mismatch)
`backend/services/AiForwardingService.java`

Pe eroare HTTP/rețea, `forward()` întoarce textul `"ERR:404"` / `"ERR:UNREACHABLE"` / `"ERR:INTERNAL"`
(liniile 190–199). Dar `isAiHttpError()` (linia 204) verifică prefixul `"__AI_HTTP_ERROR__"`, iar
`extractHttpErrorCode()` taie tot `"__AI_HTTP_ERROR__".length()` caractere.

Consecință în `ChatController.chat()`:
- `isAiHttpError(aiResponse)` e **întotdeauna false** → ramura care ar trebui să întoarcă `502` nu se execută niciodată;
- textul `"ERR:404"` e tratat ca răspuns valid al modelului, **salvat în baza de date** ca `aiResponse` și afișat utilizatorului ca și cum ar fi diagnoza;
- fallback-ul `generateFallbackResponse()` (când AI e down) **nu se declanșează niciodată**, pentru că `aiResponse != null` (e `"ERR:UNREACHABLE"`).

Fix: aliniază cele două convenții. Ori întorci `"__AI_HTTP_ERROR__" + code`, ori — mai curat — introduci
un tip de rezultat cu stare explicită (ex. `ForwardResult(text, conversationId, ErrorKind kind)`) și
testezi `kind`, nu prefixe de string. Prefix-matching pe payload e fragil prin definiție.

### B2 — [MAJOR] `ephemeral`/`recordExchange` nu ajung pe calea de streaming
`backend/services/AiForwardingService.forwardStream()` construiește `kdiag` doar cu
`protocol_version`, `conversation_id`, `message`, `artifacts` — **fără** `recordExchange`/`ephemeral`.
Pe calea non-stream (`forward()`) le trimite corect.

Consecință: pe streaming, AI server-ul folosește default `recordExchange=true`. Generarea de titlu
prin stream (dacă e vreodată rutată aici) sau orice apel ephemeral pe stream **poluează `qa_feedback`**
și istoricul. În plus, în `ChatController.stream()` titlul se regenerează după persistare, deci mesajul
utilizatorului chiar se salvează — corect — dar controlul `recordExchange` din protocol e efectiv ignorat pe stream.

Fix: propagă `recordExchange`/`ephemeral` și în `forwardStream()`, la fel ca în `forward()`.

### B3 — [MAJOR] `chatId` și `attachments` se pot pierde silențios pe persist
`backend/services/ChatService.persistChat()` face toată salvarea în `userRepository.findById(userId).ifPresent(...)`.
Dacă `userId` nu există în DB (ex. sesiune veche în localStorage după reset DB, sau userId greșit),
lambda **nu rulează**, dar metoda tot întoarce un `Map` cu `chatId=null` și `attachments=[]`,
fără nicio eroare. Frontend-ul crede că s-a salvat.

`DefaultUserSeeder` atenuează asta pe DB proaspăt (creează id=1), dar dependența „frontend trimite
userId fix = 1” e fragilă (vezi S3). Fix: dacă user-ul lipsește, întoarce un rezultat de eroare
explicit sau loghează la nivel WARN, nu înghiți.

### B4 — [MINOR] `ContextController` și `ChatController.ingestContext` — cod duplicat, comportament divergent
Există două căi care fac aproape același lucru (`POST /api/context` în `ContextController` și
`POST /api/chat/context` în `ChatController`). Ambele parsează `user_id`/`userId` manual din `Object`.
E ușor să le modifici pe una și s-o uiți pe cealaltă. Vezi și SP2.

### B5 — [MINOR] `regenerateTitle` mută `Collections.reverse(chatsDesc)` in-place
`ChatService.regenerateTitle()` (linia 234) apelează `Collections.reverse` pe lista întoarsă de
repository în cadrul aceleiași metode. Momentan nu mai e folosită după, deci nu produce bug acum, dar
e o mutație a unei liste „împrumutate” — o extindere ulterioară a metodei poate introduce un bug subtil.
Lucrează pe o copie.

### B6 — [MINOR] Parsare fragilă a namespace-urilor
`ClusterController.getNamespaces()` face `output.trim().split("\\s+")` pe `jsonpath={.items[*].metadata.name}`.
Merge, dar dacă un namespace ar avea spații (nu se întâmplă în K8s, deci risc teoretic) s-ar sparge.
Acceptabil; îl notez pentru completitudine.

---

## 2. Securitate

### S1 — [CRITIC] Secret real (Brave API key) comis în git prin `target/`
`Server/target/classes/application-local.properties` conține
`BRAVE_API_KEY=BSAa4qQRS3X176ZGeQr281EIA-1JArZ` și **este urmărit în git** (113 fișiere din `target/`
sunt comise). Sursa `src/.../application-local.properties` e corect în `.gitignore`, dar copia compilată
din `target/` nu e ignorată și a ajuns în istoric.

Acțiuni:
1. **Revocă/rotește cheia Brave acum** — trebuie considerată compromisă.
2. `git rm -r --cached backend/target Server/target` și adaugă `target/` în `.gitignore` (global, pentru ambele module).
3. Cheia rămâne în istoricul git chiar și după ștergere — dacă repo-ul e/va fi public sau partajat, curăță istoricul (`git filter-repo`) sau consideră cheia pierdută definitiv.

### S2 — [CRITIC] IDOR: endpoint-urile de conversații nu leagă `userId` de JWT
Filtrul JWT (`JwtAuthenticationFilter`) autentifică pe baza token-ului, dar controllerele iau `userId`
**din request** (query param / body), nu din principalul autentificat. Nicăieri nu se verifică că
`userId`-ul cerut == user-ul din token.

Exemple:
- `DELETE /api/chat/conversation/{id}` (`ChatController.deleteConversation`) **nu are niciun `userId`** — orice utilizator autentificat poate șterge conversația oricui, dacă știe/ghicește ID-ul (UUID, deci greu de ghicit, dar tot IDOR).
- `GET /api/chat/conversations?userId=...`, `GET .../messages?userId=...`, `PATCH .../title?userId=...` — verifică doar că `conversationId` aparține `userId`-ului **trimis de client**, nu celui din token. Trimiți userId-ul altcuiva și îi citești/editezi conversațiile.

Fix: derivă user-ul din `SecurityContextHolder` (adaugă claim-ul `uid` în `Authentication` — deja îl pui în JWT ca `CLAIM_USER_ID`) și ignoră `userId` din request, sau validează egalitatea. Pentru lucrare, e o vulnerabilitate care merită menționată și rezolvată, mai ales că teza vinde „protejarea accesului... verificarea drepturilor la fiecare cerere” (secțiunea 4.1, obiectiv explicit).

### S3 — [MAJOR] `userId` de încredere zero + user seed fix
Frontend-ul ține `userId` în `localStorage` și îl trimite ca payload. Combinat cu S2, întreaga noțiune
de „proprietar al conversației” e controlată de client. `DefaultUserSeeder` presupune că id-ul auto-increment
va fi 1 ca să se potrivească cu frontend-ul — funcționează doar pe DB curat și e o cuplare ascunsă.

### S4 — [MAJOR] CORS `*` + `@CrossOrigin(origins="*")` peste tot, pe API cu JWT
Toate controllerele au `@CrossOrigin(origins = "*")` și mai există un `CorsConfigurationSource` global cu
`*`. `allowCredentials=false`, deci nu e catastrofal (token-ul e în header, nu cookie), dar orice site
poate lovi API-ul cu token-ul dacă îl obține. Pentru un sistem care își face un merit din „suveranitatea
datelor” (cap. 1), un allowlist de origini e mai coerent cu discursul.

### S5 — [MINOR] Default-uri periculoase dar documentate
`JWT_SECRET` are default `change-me-...` și `DEFAULT_USER_PASSWORD=admin`. Sunt marcate clar „override in
prod”, ceea ce e ok pentru o lucrare, dar merită menționat în capitolul de securitate ca „limitare asumată”,
altfel un evaluator îl va găsi. `enforce-readonly-kubeconfig` e default **false** — teza (secțiunea 5.11)
sugerează apărare pe niveluri; default-ul permisiv contrazice ușor tonul.

### S6 — [MINOR] Trace-ul de excepție se trimite la client
`ChatController.getConversationMessages()` (catch, liniile 282–288) întoarce stack trace-ul complet în
răspunsul HTTP 500. Util la debug, dar expune structura internă. Scoate-l sau pune-l doar în log.

---

## 3. Spaghetti / datorie tehnică

### SP1 — [MAJOR] `target/` comis în repo (ambele module)
Vezi S1. Peste 100 de `.class` + `build.log`, `run_output.txt`, loguri (`backend/logs/backend.log`,
arhive `.gz`) sunt în arbore. Poluează diff-urile și cresc repo-ul. `.gitignore` trebuie extins cu
`**/target/`, `**/logs/`, `*.log`, `build.log`, `run_output.txt`.

### SP2 — [MAJOR] Două aplicații Spring într-un singur repo cu convenții diferite
`backend` folosește `com.example` + `@Autowired` pe câmpuri; `Server` folosește `com.kdiag.server` +
injecție prin constructor. Package-ul `com.example` e generic (miros de „starter neredenumit”). Nu e bug,
dar e inconsecvent și îngreunează întreținerea. Cel puțin uniformizează stilul de injecție (constructor
peste tot — deja e majoritar).

### SP3 — [MEDIU] Amestec RestTemplate (blocant) + WebClient (reactiv) în același serviciu
`AiForwardingService` folosește `RestTemplate` pentru non-stream și `WebClient` pentru stream. Merge, dar
sunt două stack-uri HTTP cu timeouts și gestionare de erori diferite, întreținute în paralel. Pe termen
lung, un singur client (WebClient) simplifică.

### SP4 — [MEDIU] `renderMarkdown` scris de mână în `frontend/js/chat.js`
Un parser Markdown propriu (~120 linii, regex pe linii, placeholders `<CMD_PLACEHOLDER>`). Escaparea HTML
e prezentă (bine — nu e XSS evident), dar e clasă de cod predispusă la bug-uri de randare și greu de testat.
Dacă vrei să reduci riscul, `marked` + `DOMPurify` (deja ai `vendor/`) fac asta robust. Ca minim, notează
în teză că e intenționat minimalist.

### SP5 — [MEDIU] Comentarii-mock uriașe și cod „legacy” în producție
`ChatController.chat()` are ~30 de linii de JSON comentat; `AiForwardingService.forward()` la fel.
Ajută la citit, dar sunt multe. Există și `POST /api/chat/save` marcat „legacy” încă expus, și
`generateFallbackResponse()` cu răspunsuri hardcodate care (din cauza B1) nu se mai ating. Curăță ce e mort.

### SP6 — [MINOR] `System.out.println` / `System.err.println` amestecat cu SLF4J
~15 locuri folosesc `System.out/err` (ex. `DefaultUserSeeder`, `KubectlService`, `ClusterController`)
în loc de logger. Inconsecvent și nu respectă configurarea de logging/rotație din `application.properties`.

### SP7 — [MINOR] `catch (Exception ignored) {}` în puncte cheie
10+ locuri înghit excepții tăcut (`ChatService.ensureConversation`, `deleteConversation` flush,
`KubectlService` reader thread). Unele sunt ok (best-effort), dar `ensureConversation` care înghite totul
poate ascunde o conversație care nu s-a creat. Cel puțin loghează la DEBUG.

### SP8 — [MINOR] `HistoryService` ține tot în memorie
Istoricul, rezumatele, artifact bank sunt `ConcurrentHashMap` în RAM, fără expirare per-conversație
(doar cap pe nr. de tururi). La rulare lungă cu multe conversații, crește nelimitat. Pentru o lucrare e
acceptabil, dar merită o notă de „limitare” + un TTL/evict simplu.

---

## 4. Neconcordanțe cu documentația

Teza e, în general, **remarcabil de fidelă** codului (rar pentru o licență). Diferențele:

### D1 — Fereastra de context: 128000 în cod, dar comentarii vechi spun „llama3.1 8B / CPU”
`AiForwardingService` linia 27: comentariu `Inferenta pe CPU (llama3.1 8B) poate dura minute`. Teza (cap. 2,
5.4) spune clar gpt-oss-120b pe STS, 128000 tokeni, deloc CPU/llama. Comentariul e reziduu dintr-o versiune
anterioară și **contrazice** teza. Actualizează-l (altfel un evaluator care citește codul se încurcă).

### D2 — `generateFallbackResponse` (backend) vs `fallbackAnswer` (AI server)
Există **două** mecanisme de fallback, unul în gateway (`ChatService`, răspunsuri gen „Kubernetes is an
open-source...”) și unul mult mai elaborat în AI server (`AiEngine.fallbackAnswer`). Teza descrie doar
ideea de degradare grațioasă. Cel din gateway e naiv și oricum mort (B1). Recomand: șterge fallback-ul din
gateway și, dacă vrei, menționează în 5.3 doar pe cel din AI server.

### D3 — „chars-per-token = 3” documentat, dar teza spune buget ~326000 caractere
Secțiunea 5.6: „128000 tokeni × 3 ≈ 326000 caractere”. În cod, bugetul de intrare = `(numCtx − reserve) × 3`,
cu reserve 15%, deci `(128000 × 0.85) × 3 ≈ 326400`. **Se potrivește** — dar teza spune „din fereastră se
rezervă 15% și restul × 3”, iar 128000×3=384000, nu 326000. Cifra 326000 e corectă doar dacă rezervi întâi.
Formularea din teză e ok, dar verifică să nu apară undeva 384000. (Concordant — doar semnalez să fie coerent.)

### D4 — Titlu generat: teza spune „istoric rezumat automat”; codul limitează la 12 tururi
`ChatService.regenerateTitle` ia ultimele 12 perechi (`used >= 12`). E un detaliu care nu apare în teză —
ok, dar dacă descrii mecanismul de titlu în 5.x, menționează plafonul.

### D5 — Ceea ce teza NU acoperă și ar merita un rând
- **Bucla de streaming SSE cu re-împachetare JSON a tokenilor** (`{"text":"..."}`) ca să supraviețuiască
  stripping-ul de spațiu din specul SSE — e o decizie de inginerie reală și elegantă, descrisă în comentarii
  dar nu în teză. Ar fi un plus la 5.3/5.8.
- **`enrichArtifactContent`** (rescrie markerii `--- kubectl describe ---` în comenzi complete cu ns/nume) —
  contribuie la calitatea contextului trimis modelului; nu apare în teză.
- **Ablation config (`none`/`static`/`dynamic`)** — e coloana vertebrală a cap. 6 și e implementat curat în
  `AblationConfig` + `AiConfigController`. Asigură-te că descrii în 6.5 că e comutabil la runtime via API.

### D6 — Model de embedding
Teza: `nomic-embed-text`, 768 dim, Ollama pe OpenStack. Cod (`k8s/ai-server-deployment.yaml`) confirmă
`OLLAMA_EMBEDDING_MODEL=nomic-embed-text`. **Concordant.** ✔

---

## Prioritizare (ce aș rezolva, în ordine)

1. **S1** — rotește cheia Brave + scoate `target/` din git. (minute, risc real)
2. **B1** — repară detecția de eroare AI. (logică ruptă, afectează UX + date salvate)
3. **S2** — leagă `userId` de JWT pe toate rutele de conversații. (coerență cu teza + securitate)
4. **B2** — propagă `recordExchange`/`ephemeral` pe stream.
5. **D1** — corectează comentariul „llama3.1 8B / CPU”.
6. **SP1/SP6/SP7** — igienă: `.gitignore`, logging, catch-uri.
7. Restul, dacă mai ai timp înainte de predare.

Niciuna dintre acestea nu cere rescriere. Sunt fix-uri punctuale; arhitectura e sănătoasă și, repet,
concordanța cod–teză e peste medie.
