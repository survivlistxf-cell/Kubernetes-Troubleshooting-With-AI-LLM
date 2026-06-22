# Recomandari de eficientizare

Acest fisier este bazat pe inventarul din `METRICI_EFICIENTA.md` si propune optimizari concrete.

## 1) Prioritate mare (impact mare, risc mic)

1. Limiteaza history in AI server (in-memory)
- Problema: `HistoryService` poate creste mult daca nu comprimi conversatia.
- Efect: crestere prompt + memorie + latenta.
- Unde: `Server/src/main/java/com/kdiag/server/ai/history/HistoryService.java`
- Recomandare:
  - pastrezi ultimele `12` mesaje brute.
  - cand conversatia ajunge la `10` mesaje brute, declansezi sumarizarea in fundal.
  - rezumatul devine contextul compact pentru mesajele vechi, iar mesajele noi raman brute.

2. Fereastra de context in `AiEngine`
- Problema: istoricul poate creste mult si trebuie lasat sub controlul unui buget global, nu al unui prag fix pe numar de mesaje.
- Unde: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- Recomandare:
  - trimite istoricul complet, dar lasa `MAX_TOTAL_PROMPT_CHARS` sa taie automat ce nu mai incape.
  - pastreaza prioritar mesajul user curent si sistem promptul; restul se reduc doar daca depaseste bugetul global.

3. [x] Timeout explicit pentru backend -> AI server (solved)
- Problema: `RestTemplate` fara connect/read timeout.
- Unde: `backend/src/main/java/com/example/services/AiForwardingService.java`
- Recomandare:
  - `connectTimeout=3s`
  - `readTimeout=65s` (usor peste `OLLAMA_TIMEOUT_SECONDS=60`).

4. [x] Limita client-side la attach text (solved)
- Problema: frontend citeste fisierele integral, fara limita.
- Unde: `frontend/js/attachments.js`
- Recomandare:
  - limita client-side per fisier: `<= 2MB` (aliniat cu backend).
  - limita numar fisiere in draft: `<= 12`.

## 2) Prioritate medie (impact mare, risc mediu)

1. [x] Buget total de caractere per request catre LLM (solved)
- Problema: exista limita per artifact (10k), dar nu buget global clar.
- Unde: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- Recomandare:
  - adauga `MAX_TOTAL_PROMPT_CHARS = 32000`.
  - limiteaza la maximum `5` artefacte per request.
  - cand depasesti, aplici truncare pe artifacts/history in ordinea de prioritate.

2. [x] Ajusteaza `MAX_CONTEXT_CHARS` docs (setat la 12000) (solved)
- Unde: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- Acum: `12000` (was 15000)
- Recomandare:
  - test A/B: `12000` vs `15000`.
  - daca raspunsurile raman corecte, pastreaza `12000` pentru latenta mai buna.

3. [x] Limita pentru dynamic RAG  (solved)
- Unde: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- Acum: top `2` rezultate, fiecare pana la `15000` chars.
- Recomandare:
  - pastreaza top 2, dar limiteaza fiecare la `10000` chars.
  - adauga deduplicare simpla de paragrafe repetitive.

4. [x] Validari protocol (`@Size`) (solved)
- Problema: `KdiagModels` nu limiteaza campuri text.
- Unde: `Server/src/main/java/com/kdiag/server/protocol/KdiagModels.java`
- Recomandare:
  - `message.text`: max `4000`
  - `artifact.content`: max `10000`
  - `artifact.target`: max `255`
  - `artifacts`: max `5`

## 3) Prioritate mica (fine tuning)

1. `kubectl` timeouts adaptive
- Unde: `PodsController`, `NodesController`, `ClusterController`
- Recomandare:
  - `scan`: ramane 10s
  - `details json/events`: 20s e OK
  - `describe node` 25s ramane OK
  - adauga retry 1x doar pe timeout pentru comenzi non-destructive.

2. Log noise reduction
- Unde: `AiForwardingService`, `GptChatClient`, `HistoryService`
- Recomandare:
  - reduce log level pentru payload preview in productie.
  - evita log pe fiecare insert history la INFO (muta pe DEBUG).

## 4) Parametri tinta recomandati (set initial)

- `MAX_MESSAGES_PER_CONVERSATION`: `20`
- `MAX_TOTAL_PROMPT_CHARS`: `32000`
- `MAX_CONTEXT_CHARS` docs: `12000` (daca testele raman bune)
- dynamic doc snippet max: `10000`
- `message.text` max: `4000`
- `artifact.content` max: `10000`
- `artifacts` max: `5`
- `RestTemplate connect/read timeout`: `3s / 65s`

## 5) KPI de urmarit dupa schimbari

- P95 latency `/api/chat`
- rata timeout catre AI server
- dimensiune medie payload trimis la Ollama (chars)
- numar mediu mesaje in history per conversatie
- memory RSS pentru container `ai-server`
- procent raspunsuri cu fallback vs LLM normal

## 6) Plan de implementare recomandat

Faza 1 (rapid, 1 zi)
- cap history + fereastra context
- timeout `RestTemplate`
- limita client-side atasamente

Faza 2 (1-2 zile)
- buget total prompt chars
- `@Size` pe protocol model
- tuning `MAX_CONTEXT_CHARS`

Faza 3 (optional)
- telemetrie detaliata pe prompt size/latency
- heuristica avansata de selectie context
