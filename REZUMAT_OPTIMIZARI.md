# Rezumat optimizări — sesiunea de fine-tuning Kubexplain

> Document de transfer de context pentru o conversație nouă. Pe scurt: punctul de plecare, ce s-a implementat, în ce ordine și ce a rămas.

## Punctul de plecare

**Proiectul Kubexplain** — asistent de diagnostic Kubernetes powered de LLM local (Ollama, llama3.1).

Trei module:
- `Server/` — AI Server, Spring Boot, orchestrează retrieval + LLM, pachetul `com.kdiag.server`
- `backend/` — gateway Spring Boot între frontend și AI Server, pachetul `com.example`
- `frontend/` — vanilla JS + Express

**Problemele identificate în sesiunea inițială:**
- Limită de 20.000 caractere per document salvat în DB → 50.000 cuvinte de pe `kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/` erau efectiv ignorate
- Trunchiere oarbă cu `substring(0, 20000)` care tăia fix la mijlocul secțiunilor relevante
- Retrieval primitiv prin `contains()` pe text plain la nivel de pagină întreagă
- Răspunsuri blocking 30-60 secunde fără niciun feedback vizual
- Bug ascuns: Ollama folosea `num_ctx` default (2048 tokens) și trunca tăcut prompturi de până la 32K caractere
- Feedback (like/dislike) era înregistrat în DB dar nu folosit la retrieval

## Cele 8 optimizări implementate, în ordine

### 1. Readability4j — extracție inteligentă a articolelor

Readability4j este portul Java al algoritmului Mozilla Readability — același algoritm folosit de Firefox în modul „Reader View" care îți curăță un articol web de toate distragerile (meniuri, sidebar, bannere, footer, „Edit this page", „Last modified", reclame) și îți lasă doar corpul articolului. Practic, primește HTML brut și returnează doar conținutul semnificativ al paginii, plus titlul.

**Înainte:** selectori JSoup manuali (`h1, h2, h3, p, pre, code, li`) care prindeau și boilerplate (nav, sidebar, footer, „Edit this page", „Last modified").

**După:** portul Java al algoritmului Mozilla Readability extrage doar corpul articolului. Fallback automat la JSoup dacă Readability eșuează.

**Fișiere noi/modificate:**
- `Server/src/main/java/com/kdiag/server/docs/ReadabilityExtractor.java` (nou, utilitar package-private)
- `KubernetesDocsScraper.java`, `KubernetesDynamicSearcher.java` (apelează extractorul + păstrează `legacyExtract` ca fallback)

**Dependență:** `net.dankito.readability4j:readability4j:1.0.8`

### 2. Lucene BM25 + chunking + scoaterea limitei de 20K

**Înainte:** pagini trunchiate la 20.000 chars + ranking `contains()` la nivel de pagină → scor pe blob-uri de 20K, ratând conținut relevant tăiat la jumătate.

BM25 (Best Matching 25) e un algoritm de scoring pentru text retrieval, dezvoltat la Cambridge prin anii '90. Pentru fiecare termen din query, calculează un scor care ține cont de:

    Câte ori apare termenul în document (term frequency, TF) — dar cu saturație, nu liniar. A 10-a apariție contează mai puțin decât a 2-a.
    Cât de rar e termenul în corpus (inverse document frequency, IDF) — „pod" apare în multe pagini Kubernetes deci e penalizat, „CrashLoopBackOff" e rar deci primește scor mare.
    Lungimea documentului față de media — un document scurt cu termenul de 3 ori e mai relevant decât un document foarte lung cu termenul de 3 ori.

**Chunking** înseamnă spargerea documentelor lungi în bucăți mai mici (chunks) care se indexează separat. Motivul: dacă indexezi o pagină de 50.000 chars ca un singur „document Lucene", scoring-ul BM25 e diluat — un chunk dens despre tema ta de query e amestecat cu 49.000 chars de conținut irelevant, iar scorul mediei nu reflectă densitatea locală. Cu chunking, fiecare bucată de ~1200 chars e scorată independent, deci poți să întorci „chunk-ul #4 din pagina X, chunk-ul #7 din pagina Y" — exact secțiunile potrivite.

**După:**
- Coloana `text_content` în Postgres este `TEXT` (nelimitat). Constanta `MAX_SCRAPED_DOC_CHARS = 20000` a fost eliminată din ambele scrapers.
- S-a adăugat hard cap defensiv `ABSOLUTE_PERSIST_CHAR_CAP = 500_000` pentru protecție.
- Paginile sunt sparte în chunks de ~1200 chars (max 1800) pe boundaries de paragraf, cu fallback la propoziții pentru paragrafe imense. Overlap de 100 chars între chunks consecutive pentru continuitate.
- Lucene indexează chunk-urile cu scoring BM25 (default similarity din v6+).
- `SearcherManager` pentru read thread-safety; `MMapDirectory` pentru viteză.

**Fișiere noi:**
- `Server/src/main/java/com/kdiag/server/docs/index/ChunkSplitter.java`
- `Server/src/main/java/com/kdiag/server/docs/index/LuceneChunkIndex.java`
- `Server/src/main/java/com/kdiag/server/docs/index/DocChunk.java` (record)
- `Server/src/main/java/com/kdiag/server/api/IndexController.java`
- `Server/src/test/java/com/kdiag/server/docs/index/ChunkSplitterTest.java` (10 teste unitare)

**Endpoint-uri noi:**
- `POST /v1/index/rebuild` — rebuild complet async
- `GET /v1/index/stats` — statistici (pages, chunks, bytes, lastRebuild)
- `POST /v1/index/refresh-stale` — refetch pagini cu length exact 20000 (heuristică pentru date vechi)

**Dependențe:** `org.apache.lucene:lucene-core`, `lucene-analysis-common`, `lucene-queryparser` toate la `9.11.1`

**Configurare:** `kdiag.lucene.dir=./lucene_index`, `kdiag.retrieval.topk=12` (vezi punctul 12 — proprietate unică, partajată de ambele motoare)

### 3. Streaming SSE end-to-end + num_ctx + limite mărite

**Înainte:** Frontend → backend `RestTemplate` (blocking) → AI Server `WebClient` blocking → Ollama OpenAI-compat endpoint cu `stream:false`. User vede totul deodată după 30-60s.

**După:** Server-Sent Events token-by-token pe toate cele 3 hop-uri.

**Schimbări tehnice:**
- `OllamaClient` switchuit de la `/v1/chat/completions` la endpoint-ul nativ Ollama `/api/chat` care acceptă `options.num_ctx` (cel OpenAI-compat îl ignora tăcut).
  > ⚠️ **Migrat ulterior** pe gpt-oss (OpenAI-compatible): clasa e acum `GptChatClient`, vorbește din nou `/v1/chat/completions` cu streaming SSE, iar `num_ctx` rămâne doar buget local de prompt. Vezi `PLAN_MIGRARE_GPTOSS.md`.
- `num_ctx=8192` setat explicit (era default 2048).
- Metodă `GptChatClient.chatStream(messages)` returnând `Flux<String>` (inițial NDJSON pe Ollama; acum SSE pe gpt-oss).
- Metodă nouă `AiEngine.solveStream(...)` care construiește sincron promptul și emite chunks.
- Endpoint nou `POST /v1/chat/stream` cu `produces = TEXT_EVENT_STREAM_VALUE`.
- Backend: metodă nouă `AiForwardingService.forwardStream(...)` cu WebClient (RestTemplate-ul vechi păstrat pentru backward compat).
- Frontend: `sendMessageStreaming()` cu `fetch + ReadableStream.getReader()`, flag `STREAMING_ENABLED = true` în `chat.js`.

**Limite mărite:**
- `MAX_USER_MESSAGE_CHARS`: 4000 → 16000 (în `Chat.java`)
- `MAX_ARTIFACT_PROMPT_CHARS`: 3000 → 6000
- `MAX_TOTAL_PROMPT_CHARS`: 32000 → 28000 (rezervăm spațiu pentru răspuns)
- `MAX_RETRIEVAL_SNIPPET_CHARS`: 200 → 400
- Migration SQL: `db_migrations/2026-05-XX_chat_text_extend.sql` cu `ALTER TABLE chats ALTER COLUMN text TYPE VARCHAR(16000)`

### 4. Infrastructură feedback — partea de scriere

**Concept:** capturăm fiecare schimb (user_question, ai_response) ca un caz care poate fi „validat" prin like. La like, generăm embedding-ul întrebării și îl salvăm în pgvector.

**Tabelă nouă:**
```sql
qa_feedback (id, conversation_id, user_question, ai_response,
             embedding vector(768), feedback int, source_urls, created_at)
```
cu index HNSW pe embedding pentru cosine similarity search.

**Fișiere noi:**
- `db_migrations/2026-05-12_qa_feedback_pgvector.sql`
- `Server/src/main/java/com/kdiag/server/entities/QaFeedback.java`
- `Server/src/main/java/com/kdiag/server/repositories/QaFeedbackRepository.java`
- `Server/src/main/java/com/kdiag/server/ollama/OllamaEmbeddingClient.java`
- `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java`
- `Server/src/main/java/com/kdiag/server/api/FeedbackController.java`

**Comportament:** la fiecare `AiEngine.solve` se cheamă `recordExchange(...)` cu feedback=0. La `setFeedback(+1)`, `FeedbackRetrievalService.onPositiveFeedback` generează embedding-ul prin `OllamaEmbeddingClient` (model `nomic-embed-text`) și îl salvează în coloana vector.

**Endpoint nou:** `GET /v1/feedback/stats` — totalRecorded, positive/negative/neutral, withEmbedding, model, dimension.

**Prerequisite manuale:**
- `CREATE EXTENSION vector;` în Postgres
- `ollama pull nomic-embed-text`
- Rulare migration SQL în pgAdmin

### 5. Infrastructură feedback — partea de citire (Mod 1 + Mod 2)

**Mod 2 (case-based reasoning prin similaritate semantică):**
- Pe fiecare întrebare nouă, embedding cu `nomic-embed-text` → cosine search în `qa_feedback` (filtrat la `feedback >= 1`) → top 3 cu similarity ≥ 0.75.
- Cazurile găsite sunt injectate în system prompt într-o secțiune `PREVIOUSLY SUCCESSFUL ANSWERS TO SIMILAR USER QUESTIONS`.
- Truncare per caz: 300 chars întrebare + 1200 chars răspuns, total max 4000 chars pentru tot block-ul.

**Mod 1 (BM25 boost prin URL-uri validate):**
- `ProblemResolution.feedback >= 1` → URL-urile devin „boosted".
- La căutare Lucene, retrieve 2×topK, multiply score cu 1.5 pentru chunks cu URL în set, re-sort, trim la topK.
- Cache 60s pentru lista de URL-uri boosted (read din DB rar).

**Fișiere modificate:**
- `FeedbackRetrievalService` extins cu `findSimilarCases` + `getBoostedUrls` + cache
- `LuceneChunkIndex.search` overload cu `Set<String> boostedUrls`
- `KubernetesDocsScraper.getRelevantDocsByBm25Boosted` (variantă nouă)
- `AiEngine.solve` apelează `findSimilarCases` + `getBoostedUrls` înainte de retrieval, pasează amândouă mai jos
- `AiEngine.buildSystemPrompt` include secțiunea cu cazuri similare
- `ProblemResolutionRepository.findAllUsefulUrlsWithPositiveFeedback`

**Endpoint nou:** `GET /v1/feedback/boosted-urls` — count, ttlSecondsRemaining, sample de URL-uri.

**Praguri tunabile (constante în `FeedbackRetrievalService`):**
- `SIMILARITY_THRESHOLD = 0.75` (cosine)
- `MAX_SIMILAR_CASES = 3`
- `BOOSTED_URLS_TTL_MS = 60_000`
- Boost factor în Lucene: `1.5`

### 6. Metrics + actualizare METRICI_EFICIENTA.md (Bloc A)

**MetricsCollector** ca `@Component` cu contoare `AtomicLong` thread-safe pe tot hot path-ul:
- Throughput: `totalChatRequests`, `totalStreamingRequests`, `totalFallbackResponses`, `totalNeedsSearchTriggers`
- Latency: `totalResponseTimeMs`, `totalChatLatencyMs`, `totalEmbeddingLatencyMs`
- Mărime prompt/response: `totalPromptChars`, `totalResponseChars`
- BM25: `bm25Searches`, `bm25EmptyResults`, `bm25BoostedSearches`
- Feedback: `similarCasesQueries`, `similarCasesHits`, `embeddingFailures`
- Ollama: `numCtxOverflowsApprox`

În `snapshot()` se calculează derivate: `avgResponseTimeMs`, `bm25HitRate`, `similarCasesHitRate`, `streamingRatio`, `avgPromptChars` etc.

**Endpoint nou:**
- `GET /v1/metrics` — snapshot complet ca JSON
- `POST /v1/metrics/reset` — pentru demo curat

**Hook-uri:** `AiEngine`, `GptChatClient`, `OllamaEmbeddingClient`, `LuceneChunkIndex`, `FeedbackRetrievalService` toate primesc `MetricsCollector` injectat și raportează non-invaziv.

**Document:** `METRICI_EFICIENTA.md` rescris de la zero, citind constantele din cod actual (nu mai e desincronizat).

### 7. Polish — orphan chunks GC + keywords + Ollama sanity check (Bloc B)

**Bug fixat: orphan chunks după TRUNCATE.** După `TRUNCATE TABLE kubernetes_doc_pages` rămâneau chunks în Lucene cu pageId-uri inexistente în DB (am văzut 66 orfani + 43 valizi = 109 total). Adăugat `garbageCollectOrphans()` apelat în `LuceneChunkIndex.init()` care:
1. Citește toate pageId-urile din DB
2. Enumeră toate documentele din Lucene
3. Șterge chunks cu pageId nealocat
- Endpoint nou: `POST /v1/index/gc` pentru rulare manuală.

**Fix `extractKeywords`:** filtrul `length() > 3` arunca termeni K8s esențiali (pod, dns, oom, cni, api, rbac, tls). Adăugat whitelist:
```
"pod","dns","oom","cni","api","rbac","tls","ssl","tcp","udp","ip",
"etcd","cri","csi","crd","cmd","env","ctx","gpu","cpu","mem","nfs","cwd","pvc","pv"
```

**Sanity check num_ctx la startup:** `GptStartupCheck implements ApplicationRunner` compară `llm.chat.num-ctx` configurat cu fereastra maximă raportată de model. Pe gpt-oss (OpenAI-compatible) lungimea contextului nu e expusă, deci `queryModelMaxContext()` întoarce `Optional.empty()` și check-ul e un no-op grațios (un singur INFO). `num-ctx` rămâne doar un buget local de prompt.

### 8. [NEEDS_SEARCH:] în streaming path (Bloc D)

`AiEngine.solve` (blocking) detecta deja `[NEEDS_SEARCH: <query>]` și făcea un al doilea apel Ollama cu docs adiționale. `solveStream` sărea peste asta (TODO).

**Soluție:** wrapper reactive în jurul `chatStream` care:
1. Buffer-uiește primele 256 chars fără să emite downstream
2. Dacă găsește marker complet `[NEEDS_SEARCH: ... ]` → cancel stream original → run `dynamicSearcher.searchAndSave` synchronous → start NEW `chatStream` cu mesaje augmentate → emite chunks-urile noi downstream
3. Dacă threshold-ul trece fără marker → flush buffer + continuă streaming normal

**Trade-off UX:** worst case +2-5s la primul token când se triggerează search-ul dinamic. Best case +50-200ms (doar buffer fill). Acceptabil.

### 9. Budget dinamic artefacte vs RAG + artifact bank persistent + system prompt tiered

**Problema identificată:** până la această optimizare, bugetele erau statice — `MAX_RAG_CONTEXT_CHARS = 12000`, `MAX_ARTIFACT_PROMPT_CHARS = 6000` per artefact, până la `MAX_ARTIFACTS_PER_REQUEST = 5`. Asta însemna trei probleme concrete:
1. **Risipă teoretică de buget** — 5 artefacte × 6000 chars = 30.000 chars, mai mult decât `MAX_TOTAL_PROMPT_CHARS = 28000` per total. Bugetul actual scădea prin `truncateToBudget` aplicat in-flight pe mesaje, tăind agresiv din istoric și uneori chiar din artefactele turnului curent.
2. **Artefacte pierdute peste turns** — un `kubectl describe pod` atașat la turn 1 dispărea complet din context la turn 5, când mesajul lui era trimmat din istoric. LLM-ul „uita" evidence-ul concret.
3. **Repetare inutilă a preambulului** — system prompt-ul cu identitate + reguli de format era re-trimis verbatim la fiecare turn, deși LLM-ul îl văzuse deja.

**Soluție în trei piese conectate:**

**Piesa 1 — Alocare bazată pe dimensiune (size-based), nu pe count.** Argumentul: un singur `kubectl describe pod` cu Events + Logs poate avea 12.000 chars, identic cu 5 YAML-uri mici de 2.400 chars fiecare. Count-ul nu reflectă consum efectiv.

Constante noi în `AiEngine.java`:
```java
MAX_TOTAL_ARTIFACT_CHARS = 15000   // plafon dur pe suma artefactelor
MIN_RAG_CHARS            = 6000    // RAG nu coboară sub asta
MAX_RAG_CHARS            = 14000   // RAG când nu sunt artefacte
ARTIFACT_TO_RAG_RATIO    = 0.5     // raport schimb: 1 char artefact „costă" 0.5 chars RAG
```

Eliminate: `MAX_RAG_CONTEXT_CHARS`, `MAX_ARTIFACT_PROMPT_CHARS`, `MAX_ARTIFACTS_PER_REQUEST`, `limitArtifacts()`.

Record nou + algoritm:
```java
record ArtifactBudget(int[] perArtifactChars, int totalArtifactChars, int ragChars) {}

ArtifactBudget computeArtifactBudget(List<Artifact> artifacts) {
    int[] alloc = new int[artifacts.size()];
    int used = 0;
    for (int i = 0; i < artifacts.size(); i++) {
        int rawLen = artifacts.get(i).getContent().length();
        int remaining = MAX_TOTAL_ARTIFACT_CHARS - used;
        if (remaining <= 0) { alloc[i] = 0; continue; }
        alloc[i] = Math.min(rawLen, remaining);
        used += alloc[i];
    }
    int ragChars = Math.max(MIN_RAG_CHARS,
            MAX_RAG_CHARS - (int) Math.round(used * ARTIFACT_TO_RAG_RATIO));
    return new ArtifactBudget(alloc, used, ragChars);
}
```

**FIFO truncation:** artefactele primesc alocare în ordinea în care au fost atașate; când plafonul total se epuizează, restul primesc 0 și sunt omise complet din prompt (`if (alloc <= 0) continue` în `buildUserPrompt`).

**Tabel de alocare verificabil:**

| Raw artefacte (suma) | Alocat efectiv | RAG buget | Total combinat |
|---|---|---|---|
| 0 | 0 | 14.000 | 14.000 |
| 2.000 | 2.000 | 13.000 | 15.000 |
| 6.000 | 6.000 | 11.000 | 17.000 |
| 10.000 | 10.000 | 9.000 | 19.000 |
| 14.000 | 14.000 | 7.000 | 21.000 |
| 20.000 | 15.000 (capat) | 6.500 | 21.500 |
| 40.000 | 15.000 (capat) | 6.500 | 21.500 |

Garanție matematică: `totalArtifactChars + ragChars ≤ 21.500` pentru orice input, lăsând ~6.500 chars rezervați pentru similar cases + summary + istoric + întrebare curentă în bugetul total de 28.000.

Logul per request:
```
Artifact budget: rawTotal=8000, allocated=8000, ragChars=10000, perArtifact=[3000, 5000]
```
Diferența `rawTotal - allocated > 0` semnalează când se atinge plafonul.

**Piesa 2 — Artifact bank persistent peste turnuri.** Structuri noi în `HistoryService.java`:
```java
BANK_MAX_ENTRIES        = 5
BANK_SUMMARY_CHARS_EACH = 1500    // worst case bank footprint: 7.500 chars

Map<String, Deque<BankedArtifact>> artifactBank  // per conversationId
Map<String, Long> turnCounters                    // monoton crescător

record BankedArtifact(String type, String filename, String summary,
                      long turnNumber, Instant addedAt) {}
```

Metode noi:
- `addArtifacts(convId, artifacts)` — incrementează `turnCounter` atomic (prin `merge(..., 1L, Long::sum)`), banchează fiecare artefact cu summary trunchiat la 1500 chars (suffix `"...[truncated, full version was in turn N]"`), eviction FIFO peste 5 entries.
- `getBankedArtifactsBefore(convId, turn)` — filtrează `< turn`, exclude turnul curent (anti-duplicare cu mesajul user curent care deja conține artefactele).
- `getBankedArtifacts(convId)` — fetch întreg (folosit când turnul curent n-are artefacte noi).
- `clearArtifacts(convId)` — pentru reset.

Thread-safety: `ConcurrentHashMap` pe mapele exterioare + `synchronized (deque)` pe operațiile compuse de eviction.

Wire-up în `AiEngine.solve` și `solveStream`:
```java
if (!processedArtifacts.isEmpty()) {
    long currentTurn = historyService.addArtifacts(conversationId, processedArtifacts);
    bank = historyService.getBankedArtifactsBefore(conversationId, currentTurn);
} else {
    bank = historyService.getBankedArtifacts(conversationId);
}
```

Inserție în `buildSystemPrompt`, plasare deliberată **după** cazurile similare și **înainte** de RAG docs (artefactele user-ului = evidence concret, mai aproape de partea „user-side" a contextului decât docs generice):
```markdown
## Reference artifacts attached earlier in this conversation
(These were uploaded by the user previously. Use them as context only if relevant to the current question.)

[turn 1 - kubectl describe - kubectl describe-1]
<1500 chars summary>...[truncated, full version was in turn 1]

[turn 2 - logs - logs-2]
<...>
```

**Piesa 3 — System prompt tiered (full / compact).** Parametru nou `boolean isFirstTurn` în `buildSystemPrompt`:
- `isFirstTurn = true` (~700 chars preambul): identitate + reguli (`IMPORTANT ACTIONS`, `stop commands` handling, `error messages` interpretation, convenția `[NEEDS_SEARCH:]`).
- `isFirstTurn = false` (~200 chars preambul): reminder de identitate + 3 convenții esențiale (cite sources, NEEDS_SEARCH marker, markdown format).

Detecție: `isFirstTurn = conversationId == null || historyService.getHistory(conversationId).size() <= 1` (verificare: `<= 1` pentru că mesajul user curent e deja adăugat la history înainte de buildSystemPrompt).

**Important:** secțiunile dinamice (`relevantDocs`, `similarCases`, `bank`, `conversationSummary`) **nu** sunt afectate de tier — sunt mereu emise full. Doar preambulul static repetitiv e scurtat.

Economia: ~500 chars per turn după primul. Pentru o conversație de 10 schimburi → ~4500 chars cumulativ eliberați pentru artefacte/istoric/răspuns.

**Fișiere modificate:**
- `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` (net ~−30 linii: eliminări de constante + `limitArtifacts` vs adăugări de `ArtifactBudget` + `computeArtifactBudget`; signature changes pe `fetchRelevantDocs`, `buildUserPrompt`, `buildSystemPrompt`)
- `Server/src/main/java/com/kdiag/server/ai/history/HistoryService.java` (+72 linii: bank + turn counter)
- `Server/src/test/java/com/kdiag/server/ai/AiEngineBudgetTest.java` (nou, 8 teste — empty input, single small, single huge capped, three-artifact FIFO, invariant test pentru random input 1-20 artefacte, bank FIFO eviction, getBankedArtifactsBefore filtering, summary truncation cu suffix)

**Datorie tehnică conștientă:** testele folosesc `null` în constructorul `AiEngine` în loc de mock-uri Mockito, pentru că Byte Buddy 1.14 (bundle-uit cu Spring Boot 3.2.2) nu suportă Java 25 pe care rulează machine-ul de development. `computeArtifactBudget` nu folosește field-uri injectate, deci `null` e semantic echivalent cu mock pentru aceste teste. La adăugarea de teste viitoare care exercită `solve()`/`solveStream()`, se vor folosi mock-uri reale (Mockito update sau rulare pe JDK 21 LTS).

### 10. Scheduled cleanup pentru paginile dynamic (Opțiunea B)

**Problema:** paginile descoperite prin `[NEEDS_SEARCH:]` se acumulează indefinit în `kubernetes_doc_pages`. Calitatea lor variază (uneori sunt blog posts, draft-uri, pagini deprecated găsite de DuckDuckGo), iar conținutul devine stale pe măsură ce Kubernetes evoluează. În același timp, paginile validate prin like (URL apare în `problem_resolutions` cu `feedback >= 1`) sunt valoroase și trebuie păstrate.

**Soluție — job programat cu `@Scheduled` + endpoint manual:**

Activare prin `@EnableScheduling` pe clasa `@SpringBootApplication`. Job-ul rulează **duminica la 3 AM** (cron configurabil), cu protecție explicită pentru URL-urile validate.

**Configurare nouă în `application.properties`:**
```properties
kdiag.cleanup.dynamic.enabled=${KDIAG_CLEANUP_ENABLED:true}
kdiag.cleanup.dynamic.age-days=${KDIAG_CLEANUP_AGE_DAYS:30}
kdiag.cleanup.dynamic.cron=${KDIAG_CLEANUP_CRON:0 0 3 * * SUN}
kdiag.cleanup.dynamic.dry-run=${KDIAG_CLEANUP_DRY_RUN:false}
```

**Query nativ Postgres pentru selecție:**
```sql
SELECT id, url FROM kubernetes_doc_pages
WHERE is_dynamic = true
  AND last_scraped < :cutoff
  AND url NOT IN (
    SELECT DISTINCT trim(u) FROM problem_resolutions,
      unnest(string_to_array(useful_urls, E'\n')) AS u
    WHERE feedback >= 1 AND useful_urls IS NOT NULL
  );
```

`unnest(string_to_array(...))` convertește string-ul newline-joined din coloana `useful_urls` într-un set de rânduri pentru subquery-ul `NOT IN`. Sintaxă Postgres-specifică, dar nativă și performantă.

**Comportament al job-ului:**
1. Selectează candidați (`SELECT id, url` pentru audit log).
2. Loghează fiecare URL candidat la nivel INFO (auditabilitate).
3. Dacă **nu** e dry-run: `DELETE FROM kubernetes_doc_pages WHERE id IN :ids` într-o tranzacție.
4. După DELETE: `LuceneChunkIndex.forceGarbageCollect()` curăță chunks-urile orphan din indexul Lucene.
5. Înregistrează metrici (`cleanupRunsTotal`, `cleanupPagesDeleted`, `cleanupLastDurationMs`, `cleanupLastRunAt`, `cleanupLastRunDryRun`).

**Dry-run mode:** flag în config; metoda `runCleanup(boolean dryRunOverride)` acceptă override-ul, permițând endpoint-ului manual să forțeze dry-run independent de configul global. Util la primul deploy pentru a vedea ce **ar fi** șters fără risc de pierdere date.

**Endpoint nou:** `POST /v1/index/cleanup-dynamic?dryRun=true` — trigger manual cu opțiunea de dry-run. Returnează JSON cu `candidates`, `deleted`, `dryRun`, `ageDays`. Util pentru ops și demo la prezentare.

**Fișiere modificate / noi:**
- `Server/.../KubexplainApplication.java` (sau echivalent — adăugare `@EnableScheduling`)
- `Server/.../repositories/KubernetesDocPageRepository.java` (queries noi: `findStaleDynamicPages`, `deleteByIds`)
- `Server/.../maintenance/DynamicPageCleanupService.java` (nou)
- `Server/.../api/IndexController.java` (endpoint nou)
- `Server/.../metrics/MetricsCollector.java` (5 contoare noi)
- `Server/src/main/resources/application.properties` (4 properties noi)
- `Server/src/test/java/.../DynamicPageCleanupServiceTest.java` (nou, 4 teste — dry-run, no candidates, real run, disabled mode)

**Garanție de siguranță:** paginile statice (`isDynamic = false`) nu sunt atinse vreodată. Paginile cu feedback pozitiv în `problem_resolutions` nu sunt atinse vreodată. Doar paginile dynamic, vechi de >30 zile, fără feedback pozitiv ajung la `DELETE`.

### 11. Status events în streaming SSE pentru `[NEEDS_SEARCH:]`

**Problema:** wrapper-ul `wrapWithDynamicSearchLoop` din punctul 8 detectează marker-ul în primii 256 chars, anulează stream-ul original, rulează `KubernetesDynamicSearcher.searchAndSave` (2-5s blocking sync), apoi pornește un stream nou cu mesaje augmentate. Pe durata acestor 2-5s, frontend-ul vede **blackout total** — niciun semnal că ceva se întâmplă. Userul presupune că aplicația s-a blocat.

**Soluție — meta-events pe canalul SSE existent:**

Două tipuri de evenimente SSE distincte sunt emise acum de `POST /v1/chat/stream`:
- `event: chunk` → tokens LLM (existent)
- `event: status` → meta-mesaje cu `code` machine-readable + `label` în română (nou)

**Tip nou — `StreamChunk`:**
```java
public record StreamChunk(Type type, String text, String code, String label) {
    public enum Type { TOKEN, STATUS }
    public static StreamChunk token(String text) { ... }
    public static StreamChunk status(String code, String label) { ... }
}
```

`AiEngine.solveStream` returnează acum `Flux<StreamChunk>` în loc de `Flux<String>`. `wrapWithDynamicSearchLoop` mapează tokens Ollama la `StreamChunk.token(text)` și injectează `StreamChunk.status(...)` la momente cheie.

**Cele patru status codes emise:**
| Code | Label (român) | Când |
|---|---|---|
| `needs_search_detected` | Informație insuficientă în documentația locală. Caut surse suplimentare... | Imediat după detectarea marker-ului |
| `searching` | Caut: `<query extras>` | Înainte de apel DuckDuckGo |
| `search_completed` | Am găsit surse noi. Reanalizez întrebarea cu context actualizat... | Înainte de pornirea stream-ului nou Ollama |
| `search_empty` | Căutarea nu a returnat rezultate noi. Continui cu informațiile existente. | Când dynamic search returnează blank |

**Separare `code` vs `label`:** `code` rămâne stabil în engleză ca API contract; `label` e text user-facing în română. Frontend-ul poate folosi `label` direct sau `code` ca cheie i18n pentru traducere viitoare.

**Status events NU intră în history:** `doFinally`-ul care acumulează răspunsul AI filtrează doar `chunk.type() == TOKEN`. Status events sunt UI hints, nu output al LLM-ului — nu trebuie să apară în istoricul conversației sau în `qa_feedback.ai_response`.

**Rendering frontend (separate „system bubbles"):**

```
[bubble user: "de ce kubelet-ul restartează pod-urile?"]
[system: Informație insuficientă în documentația locală. Caut surse suplimentare...]
[system: Caut: kubelet pod restart policy]
[system: Am găsit surse noi. Reanalizez întrebarea cu context actualizat...]
[bubble assistant: Kubelet repornește pod-urile când... (streaming tokens)]
```

CSS: `.system-status-message` — italic, gri-700, font mai mic, fără avatar, fără butoane copy/feedback. Sunt **breadcrumbs vizibile** care rămân în chat history ca log de etape.

**Fișiere modificate / noi:**
- `Server/.../ai/stream/StreamChunk.java` (nou, record + enum)
- `Server/.../ai/AiEngine.java` (refactor `wrapWithDynamicSearchLoop` + signature `solveStream`)
- `Server/.../api/ChatController.java` (dispatcher pe `chunk.type()` → SSE event name diferit)
- `Server/src/test/java/.../StreamChunkFlowTest.java` (nou, 4 teste cu StepVerifier — no-marker, marker + content, marker + empty search, sub-threshold)
- `frontend/js/chat.js` (handler nou pentru `event: status`)
- `frontend/css/chat.css` (regula `.system-status-message`)

**Verificare manuală end-to-end:**
1. Întrebare clară → fără status bubbles, doar tokens.
2. Întrebare obscură care declanșează `[NEEDS_SEARCH:]` → 3 status bubbles apar înainte de răspunsul AI streamed.

**Trade-off UX:** worst case +2-5s la primul token *real* al răspunsului (după status events). Best case +50-200ms. Față de versiunea fără status events: aceeași latență totală, dar **percepție mult mai bună** — userul vede progres concret, nu blackout.

### 12. Unificarea top-K într-o singură proprietate partajată

**Problema:** top-K-ul (numărul de chunks returnate per căutare) era hardcodat ca literalul `12` în `KubernetesDocsScraper`, la ambele apeluri de retrieval (`getRelevantDocsByBm25` și `getRelevantDocsByBm25Boosted`). Proprietatea `kdiag.lucene.topk=12` exista în `application.properties`, dar nu era injectată nicăieri, deci modificarea ei nu avea niciun efect. În paralel, ElasticSearch avea propria proprietate `kdiag.elastic.topk` (citită în câmpul `defaultTopK`), iar `KubernetesDocsScraper` e agnostic față de motor — pasează același top-K oricărui `ChunkRetriever` activ prin parametru. Rezultau două proprietăți pentru același concept, una moartă și una nefolosită la calea reală de căutare.

**Soluție:** o singură proprietate `kdiag.retrieval.topk`, partajată de ambele motoare.

```properties
# inlocuieste kdiag.lucene.topk si kdiag.elastic.topk
kdiag.retrieval.topk=${KDIAG_RETRIEVAL_TOPK:12}
```

**Modificări:**
- `KubernetesDocsScraper.java` — câmp nou `@Value("${kdiag.retrieval.topk:12}") private int retrievalTopK;`. Cele două apeluri `chunkRetriever.search(userMessage, 12 ...)` folosesc acum `retrievalTopK`. Top-K-ul ajunge la motorul activ (Lucene sau ES) prin parametrul de căutare, deci o singură valoare controlează ambele.
- `ElasticChunkRetriever.java` — `@Value` pe câmpul `defaultTopK` repointat de la `kdiag.elastic.topk` la `kdiag.retrieval.topk` (numele câmpului rămâne neschimbat, deci `ElasticChunkRetrieverTest` care îl setează prin `ReflectionTestUtils.setField(..., "defaultTopK", 12)` nu e afectat).
- `application.properties` — eliminate `kdiag.lucene.topk` și `kdiag.elastic.topk`; adăugat `kdiag.retrieval.topk`, override-abil din mediu prin `KDIAG_RETRIEVAL_TOPK`.

**De ce nu hardcodat:** acum schimbarea valorii în config (sau prin env la deploy) chiar reconfigurează câte chunks intră în context, indiferent de motorul activ. Înainte, valoarea era fixă în cod și proprietatea era decorativă.

## Sumar arhitectural — înainte vs după

| Aspect | Înainte | După |
|---|---|---|
| Extracție HTML | JSoup selectori manuali cu boilerplate | Readability4j cu fallback |
| Limită doc salvat | 20K chars (trunchiat brut) | TEXT nelimitat (cap defensiv 500K) |
| Retrieval | `contains()` pe pagini întregi | BM25 pe chunks de 1200 chars cu overlap |
| Top-K | 3 pagini | 12 chunks |
| LLM context | num_ctx=2048 (default, trunchiat tăcut) | num_ctx=8192 explicit + verificat la startup |
| Ollama endpoint | `/v1/chat/completions` (OpenAI-compat) | `/api/chat` (nativ, suportă options) |
| Response | Blocking, 30-60s blackout | SSE token-by-token, primul token <2s |
| Limită mesaj user | 4000 chars | 16000 chars |
| Feedback | Înregistrat, nefolosit | Mod 1 (URL boost BM25) + Mod 2 (semantic case retrieval) |
| Embedding model | — | `nomic-embed-text` (768 dim) prin Ollama |
| Vector DB | — | pgvector cu index HNSW |
| Observabilitate | Log-uri ad-hoc | `/v1/metrics`, `/v1/index/stats`, `/v1/feedback/stats`, `/v1/feedback/boosted-urls` |
| Dynamic RAG în streaming | Sărit | Wrapper cu buffer + replace stream |
| Buget artefacte | Static: 5 × 6000 chars (teoretic 30K) | Dinamic, plafon dur 15K total cu FIFO truncation |
| Buget RAG | Static: 12.000 chars | Dinamic 6.000-14.000 chars, raport 2:1 cu artefacte |
| Persistența artefactelor | Pierdute când mesajul lor era trimmat din istoric | Bank persistent 5 entries × 1500 chars summary per conv |
| System prompt | Re-trimis verbatim full la fiecare turn | Tiered: full la turn 1 (~700 chars), compact la 2+ (~200 chars) |
| Cleanup pagini dynamic | Niciunul — acumulare indefinită | `@Scheduled` weekly cu protecție pe feedback + endpoint manual |
| Feedback UX la NEEDS_SEARCH | Blackout 2-5s fără semnal vizual | 3 status bubbles cu progres real-time (detected → searching → completed) |

## Endpoint-uri noi adăugate

```
POST /v1/chat/stream               — SSE end-to-end
POST /v1/index/rebuild             — async, return 202
GET  /v1/index/stats               — pages/chunks/bytes
POST /v1/index/refresh-stale       — async, rate-limit 1/s
POST /v1/index/gc                  — orphan chunks cleanup
POST /v1/index/cleanup-dynamic     — șterge pagini dynamic vechi (cu ?dryRun=true opțional)
GET  /v1/feedback/stats            — counters
GET  /v1/feedback/boosted-urls     — debug + demo
GET  /v1/metrics                   — toate counter-ele + derivate
POST /v1/metrics/reset             — zeroes (pentru demo curat)
```

În backend (gateway):
```
POST /api/chat/stream              — proxy SSE către AI Server
```

## Dependențe nou adăugate

**Maven (`Server/pom.xml`):**
- `net.dankito.readability4j:readability4j:1.0.8`
- `org.apache.lucene:lucene-core:9.11.1`
- `org.apache.lucene:lucene-analysis-common:9.11.1`
- `org.apache.lucene:lucene-queryparser:9.11.1`

**Maven (`backend/pom.xml`):**
- `spring-boot-starter-webflux` (pentru WebClient + reactive)

**Postgres:**
- Extensia `vector` (pgvector)

**Ollama:**
- Modelul `nomic-embed-text`

## Configurare nouă în `application.properties`

```properties
llm.chat.num-ctx=${LLM_CHAT_NUM_CTX:8192}
ollama.embedding-model=${OLLAMA_EMBEDDING_MODEL:nomic-embed-text}
ollama.embedding-timeout-seconds=${OLLAMA_EMBEDDING_TIMEOUT:30}
kdiag.lucene.dir=./lucene_index
kdiag.retrieval.topk=${KDIAG_RETRIEVAL_TOPK:12}
kdiag.cleanup.dynamic.enabled=${KDIAG_CLEANUP_ENABLED:true}
kdiag.cleanup.dynamic.age-days=${KDIAG_CLEANUP_AGE_DAYS:30}
kdiag.cleanup.dynamic.cron=${KDIAG_CLEANUP_CRON:0 0 3 * * SUN}
kdiag.cleanup.dynamic.dry-run=${KDIAG_CLEANUP_DRY_RUN:false}
```

## Ce a rămas neimplementat (intenționat)

- **Mod 3** — embedding search peste chunks Lucene. BM25 e suficient pentru demo, complexitatea în plus nu se justifică.
- **Cache hash(question) → response** — recomandat Caffeine LRU cu TTL. Util pentru demo cu întrebări repetate, dar nu critic.
- **Unit tests pentru `FeedbackRetrievalService` și `OllamaEmbeddingClient`** — credibilitate tehnică în raport, dar nu blocant pentru funcționalitate.

## Direcții viitoare considerate (out of scope pentru teză)

- **Integrare Prometheus + kube-state-metrics.** Ar transforma asistentul dintr-un diagnostic tool pe artefacte furnizate de user într-un monitoring agent proactiv cu acces la metrici live ale clusterului (CPU trends, OOM kills, restart loops). Tehnic, ar implica un client Java pentru Prometheus HTTP API + un marker LLM nou (`[QUERY_METRICS: ...]`) similar cu `[NEEDS_SEARCH:]`. Excluse din scope: necesită cluster live + sub-proiect dedicat de 2-3 săptămâni.
- **Ingestion logs prin Elasticsearch.** Pentru retrieve istoric pe săptămâni de logs cluster. Trade-off real: dublează stack-ul de retrieval (Lucene face deja BM25, ES e construit peste Lucene oricum). Mai relevant pentru clustere mari cu volume mari, nu pentru demo.
- **Memorie per-user.** Tabela `qa_feedback` păstrează feedback global. O extensie ar permite prioritizarea cazurilor similare pe baza istoriei propriilor sesiuni ale unui user.

## Cifre relevante pentru raport / prezentare

- **Chunks per pagină în Lucene:** ~8-10 (vs. 1 „blob" întreg înainte)
- **Mărime index pe disc:** ~135-300 KB pentru 5 pagini statice (după Readability4j)
- **`num_ctx` honorat:** 8192 tokens (≈32 KB) — verificabil cu `/api/show` la startup
- **Embedding dimension:** 768 (nomic-embed-text)
- **Similarity threshold pentru case retrieval:** 0.75 cosine (= 0.25 distance în pgvector)
- **BM25 boost factor pentru URL-uri validate:** 1.5×
- **TTL cache URL-uri boosted:** 60 secunde
- **Limită absolută persistare doc:** 500.000 chars (de la 20.000 înainte = **25× mai mult**)
- **Plafon total artefacte per request:** 15.000 chars (FIFO truncation peste)
- **Plaja RAG buget dinamic:** 6.000-14.000 chars în funcție de consum de artefacte (raport 2:1)
- **Garanție matematică buget combinat (RAG + artefacte):** ≤ 21.500 chars worst case
- **Bank artefacte per conversație:** 5 entries × 1500 chars summary = 7.500 chars worst case în system prompt
- **Economie preambul system prompt:** ~500 chars per turn după primul (turn 1 full, turn 2+ compact)
- **Cleanup pagini dynamic:** vârsta minimă 30 zile + protejare URL-uri validate, rulat duminica 3 AM
- **Status events SSE pentru NEEDS_SEARCH:** 4 coduri (`needs_search_detected`, `searching`, `search_completed`, `search_empty`), latență totală neschimbată dar blackout 2-5s eliminat perceptual

## Cum se demonstrează la licență

> Userul întreabă „de ce e podul meu în CrashLoopBackOff?". Sistemul:
> 1. Generează embedding întrebării prin nomic-embed-text.
> 2. Caută în pgvector cazuri trecute cu like-uri și similarity ≥ 0.75 → dacă găsește, le injectează ca hint.
> 3. Face BM25 search peste 109 chunks Lucene din docs Kubernetes, cu boost 1.5× pe URL-uri validate.
> 4. Asamblează prompt (max 28.000 chars) și apelează Ollama `/api/chat` cu `num_ctx=8192`, `stream:true`.
> 5. Token-urile sunt streamed prin SSE: Ollama → AI Server → backend → frontend.
> 6. Dacă LLM-ul cere `[NEEDS_SEARCH:]`, wrapper-ul detectează în primii 256 chars, oprește streamul, face dynamic search, pornește un stream nou.
> 7. La final, schimbul se salvează în `qa_feedback`. Dacă userul dă like, se generează embedding și răspunsul devine sursă pentru cazuri viitoare similare.
> 8. Tot ce s-a întâmplat e contorizat în `MetricsCollector` și expus la `/v1/metrics` — avem numere pentru toate.

Este un sistem RAG cu feedback loop adevărat (case-based reasoning) plus optimizări de UX (streaming) și de retrieval (BM25 chunking). E maturat în mod natural, nu artificial complex.



                            User pune întrebare
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
   findSimilarCases             getBoostedUrls          Lucene BM25 search
   (Mod 2 — pgvector)           (Mod 1 — cache)         (cu boost aplicat)
        │                           │                           │
        │                           └────────►──────────────────┤
        ▼                                                       ▼
   PREVIOUSLY                                            RAG context
   SUCCESSFUL                                            (12 chunks)
   ANSWERS                                                      │
        │                                                       │
        └───────────────────┬───────────────────────────────────┘
                            ▼
                     buildSystemPrompt
                            │
                            ▼
                       Ollama call 1
                            │
                            ▼
                    [NEEDS_SEARCH:]?
                            │
              ┌─────────────┴─────────────┐
              │ YES                       │ NO
              ▼                           ▼
   dynamicSearcher.searchAndSave    return assistantText
   (DuckDuckGo + scrape +
    index în Lucene NEW pages)
              │
              ▼
   nou user message cu noile docs
              │
              ▼
        Ollama call 2
        (vede TOT: boosted chunks +
         similar cases + dynamic docs)
              │
              ▼
       return assistantText final
