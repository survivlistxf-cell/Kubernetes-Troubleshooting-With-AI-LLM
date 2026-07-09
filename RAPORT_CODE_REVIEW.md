# Raport code review — Kubexplain (2 iulie 2026)

Acoperire: `backend/` (gateway Spring), `Server/` (serviciu AI), `frontend/`, `docker-compose.yml`, `k8s/`, comparație cu `Licenta_Axinescu_FINAL_FARA_TESTE.docx`.

---

## 1. Bug-uri CRITICE (funcționalitate stricată sau securitate)

### C1. Protocolul de eroare AI e incoerent → utilizatorul vede și DB-ul salvează „ERR:UNREACHABLE" ca răspuns AI
`AiForwardingService.forward()` returnează la eșec text de forma `"ERR:" + cod` (liniile 190–199), dar `isAiHttpError()` caută prefixul `"__AI_HTTP_ERROR__"` (linia 204), care nu mai e produs nicăieri.

Consecințe în lanț:
- `ChatController.chat()`: verificarea de eroare nu se declanșează niciodată → `"ERR:UNREACHABLE"` trece drept răspuns valid, e **persistat în DB** și afișat utilizatorului.
- Fallback-ul „legacy" (`generateFallbackResponse`) e cod mort — se activa doar pe `aiResponse == null`, care nu se mai întâmplă.
- `ChatService.regenerateTitle()` folosește același check spart → titlul conversației poate deveni literal `ERR:UNREACHABLE`.
- `extractHttpErrorCode()` ar arunca `StringIndexOutOfBoundsException` dacă ar fi apelat vreodată cu noul format.

**Fix:** un singur contract. Recomand: `ForwardResult` cu câmp `error` explicit (enum/boolean), nu semnalizare prin string magic. Minim: aliniază prefixul în toate cele 3 locuri și tratează `ERR:*` înainte de persistare.

### C2. Autorizare lipsă (IDOR) — JWT-ul verifică doar „ești logat", nu „e resursa ta"
Toate controller-ele iau `userId` din body/query (trimis de frontend din `localStorage`), nu din JWT (`SecurityContext`), deși token-ul conține deja `uid`:
- `GET /api/chat/conversations?userId=X` — oricine autentificat poate lista conversațiile oricui, schimbând X.
- `DELETE /api/chat/conversation/{id}` — **nu cere niciun userId și nu verifică proprietarul**; oricine poate șterge orice conversație.
- `GET /api/chat/attachments/{id}` și `/{id}/content` — fără verificare de proprietar; orice atașament e citibil după id numeric (enumerabil).
- `ChatService.ensureConversation()` — dacă `conversationId` există deja la alt user, mesajele noi se atașează conversației aceluia (injecție cross-user).

Documentația (4.1) promite „verificarea drepturilor la fiecare cerere" — în cod nu există. Ori repari codul, ori reformulezi teza; acum e o neconcordanță pe care o comisie o poate specula.

**Fix:** extrage `uid` din `SecurityContextHolder` (filtrul deja populează authentication) și ignoră complet `userId` din request. Adaugă verificare de proprietar la delete/attachments.

### C3. Healthcheck-ul din docker-compose e permanent „unhealthy"
Compose face `curl -f http://localhost:8080/api/hello`, dar `/api/hello` **nu e în lista permitAll** din `SecurityConfig` (doar `/api/auth/**` și `/api/health`) → 401 → `curl -f` eșuează → containerul e mereu unhealthy.

**Fix:** schimbă healthcheck-ul la `/api/health` (există deja și e public).

### C4. docker-compose nu pornește serverul AI — dar teza afirmă contrariul
Documentația (5.1): „un fișier docker-compose.yml care pornește local întreaga stivă (PostgreSQL, ElasticSearch, gateway-ul, serverul AI și frontend-ul)". În realitate compose are doar postgres, backend, frontend, elasticsearch — **fără `ai-server` și fără Ollama**. În plus backend-ul nu primește `AI_SERVER_BASE_URL`, deci în compose chatul cade pe `localhost:8090` → unreachable → combinat cu C1, se persistă „ERR:UNREACHABLE".

**Fix:** ori adaugi serviciile în compose, ori corectezi paragraful din teză (varianta k8s rămâne cea completă).

### C5. Cheie API Brave comisă în repo
`Server/src/main/resources/application-local.properties` conține `BRAVE_API_KEY=BSAa4q...` în clar, în git. Manifestele k8s folosesc corect `secretKeyRef`, dar cheia din istoric rămâne compromisă.

**Fix:** revocă/rotește cheia la Brave, scoate fișierul din repo (`.gitignore`), curăță istoricul dacă repo-ul devine public.

### C6. Allowlist-ul kubectl permite `auth`, dar `kubectl auth reconcile` SCRIE în cluster
`READ_ONLY_VERBS` conține verbul generic `auth` (pentru `auth can-i`), însă `kubectl auth reconcile -f rbac.yaml` **creează/actualizează obiecte RBAC**. Contrazice direct afirmația centrală „strict read-only" din teză (2 straturi de apărare din 3 descrise în 5.2 devin ocolibile dacă kubeconfig-ul e supra-privil