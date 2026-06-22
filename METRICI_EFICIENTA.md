# Metrici de eficiență — Kubexplain AI Server

Acest fișier inventariază **toți** parametrii care influențează performanța, costul și calitatea
răspunsurilor în componenta AI Server a proiectului.  
Valorile sunt extrase direct din cod (constantele și proprietățile de configurare existente la
momentul redactării); fiecare linie indică sursa exactă cu număr de linie.

---

## 1. Limite explicite în caractere

### 1.1 Pipeline AiEngine (prompt assembly)

| Constantă | Valoare | Fișier | Linie |
|-----------|--------:|-------|------:|
| `MAX_TOTAL_PROMPT_CHARS` | 28 000 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 43 |
| `MAX_ARTIFACT_PROMPT_CHARS` | 6 000 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 42 |
| `MAX_RETRIEVAL_SNIPPET_CHARS` | 400 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 40 |
| `MAX_RAG_CONTEXT_CHARS` | 12 000 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 378 |
| `MAX_ARTIFACTS_PER_REQUEST` | 5 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 41 |
| `MAX_RECENT_HISTORY_MESSAGES` | 12 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 38 |
| `SUMMARY_TRIGGER_HISTORY_MESSAGES` | 10 | `Server/src/main/java/com/kdiag/server/ai/AiEngine.java` | 39 |

> **MAX_TOTAL_PROMPT_CHARS** este plafonul total al mesajelor trimise la gpt-oss (sistem + istoric +
> mesaj curent). Mesajele istorice sunt truniate la `remainingBudget` pentru a rămâne în buget.  
> **MAX_RAG_CONTEXT_CHARS** este bugetul de caractere pentru blocul de documentație injectată din
> indexul Lucene sau baza de date.

---

### 1.2 Validare protocol (KdiagModels)

| Câmp | Limită | Fișier | Linie |
|------|-------:|-------|------:|
| `conversationId` | 100 chars (`@Size`) | `Server/src/main/java/com/kdiag/server/protocol/KdiagModels.java` | 31 |
| `message.text` | 16 000 chars (`@Size`) | `Server/src/main/java/com/kdiag/server/protocol/KdiagModels.java` | 159 |
| `artifact.content` | 20 000 chars (`@Size`) | `Server/src/main/java/com/kdiag/server/protocol/KdiagModels.java` | 284 |

---

### 1.3 Mesaj utilizator în backend

| Constantă | Valoare | Fișier | Linie |
|-----------|--------:|-------|------:|
| `MAX_USER_MESSAGE_CHARS` | 16 000 | `backend/src/main/java/com/example/entities/Chat.java` | 17 |

Aceeași limită este enforced client-side:
```js
const MAX_MESSAGE_LENGTH = 16000;   // frontend/js/chat.js:11
```

---

### 1.4 RAG — scraping și persistență

| Constantă | Valoare | Fișier | Linie |
|-----------|--------:|-------|------:|
| `MAX_CONTEXT_CHARS` _(fallback legacy)_ | 12 000 | `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java` | 35 |
| `ABSOLUTE_PERSIST_CHAR_CAP` _(static)_ | 500 000 | `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java` | 37 |
| `MAX_DYNAMIC_DOC_CHARS` | 10 000 | `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java` | 30 |
| `ABSOLUTE_PERSIST_CHAR_CAP` _(dynamic)_ | 500 000 | `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java` | 32 |

> `ABSOLUTE_PERSIST_CHAR_CAP = 500 000` înlocuiește vechiul plafon de 20 000 caractere.
> Textul extras cu Readability4j este stocat nearbitronic în coloana TEXT din PostgreSQL;
> limita de 500 k caractere apare doar la pagini-oglidă patologice.

---

### 1.5 Lucene BM25 — chunk splitting

| Constantă | Valoare | Fișier | Linie |
|-----------|--------:|-------|------:|
| `TARGET_CHUNK_CHARS` | 1 200 | `Server/src/main/java/com/kdiag/server/docs/index/ChunkSplitter.java` | 9 |
| `MAX_CHUNK_CHARS` | 1 800 | `Server/src/main/java/com/kdiag/server/docs/index/ChunkSplitter.java` | 10 |
| `OVERLAP_CHARS` | 100 | `Server/src/main/java/com/kdiag/server/docs/index/ChunkSplitter.java` | 11 |

Algoritmul în 3 pași: împărțire pe paragrafe → despicare la nivel de propoziție dacă depășim
`MAX_CHUNK_CHARS` → prefix de suprapunere `OVERLAP_CHARS` pentru a menține continuitatea
semantică între chunk-uri consecutive.

---

### 1.6 Case-Based Retrieval (pgvector)

| Constantă | Valoare | Fișier | Linie |
|-----------|--------:|-------|------:|
| `SIMILARITY_THRESHOLD` | 0.75 | `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java` | 47 |
| `MAX_SIMILAR_CASES` | 3 | `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java` | 48 |
| `BOOSTED_URLS_TTL_MS` | 60 000 ms (60 s) | `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java` | 49 |
| `MAX_CASE_RESPONSE_CHARS` | 1 200 | `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java` | 50 |
| `MAX_CASE_QUESTION_CHARS` | 300 | `Server/src/main/java/com/kdiag/server/ai/feedback/FeedbackRetrievalService.java` | 51 |

> `SIMILARITY_THRESHOLD = 0.75` — distanța cosinus pgvector este convertită în similaritate
> `sim = 1 − dist`; cazurile sub prag sunt excluse din sistemul de prompting.  
> `BOOSTED_URLS_TTL_MS = 60 s` — cache-ul URL-urilor boost se invalidează automat la 60 de
> secunde. Apăsarea 👍 resetează `cachedBoostedUrlsAt = 0` imediat.

---

## 2. Limite de timp (timeout-uri, scheduling, cache TTL)

| Operație | Valoare | Sursă |
|----------|--------:|-------|
| gpt-oss chat timeout | `300s` (env `LLM_CHAT_TIMEOUT_SECONDS`) | `Server/src/main/resources/application.properties` → `GptChatClient.java` |
| Ollama embedding timeout | `30s` (env `OLLAMA_EMBEDDING_TIMEOUT`) | `Server/src/main/resources/application.properties` → `OllamaEmbeddingClient.java` |
| RAG scraping — pagini statice | `8s` | `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java` |
| RAG scraping — DuckDuckGo HTML | `10s` | `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java` |
| RAG scraping — pagini dinamice | `8s` | `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java` |
| SSE streaming WebClient timeout (backend) | `120s` | `backend/src/main/java/com/example/services/AiForwardingService.java` |
| Boosted-URLs cache TTL | `60s` | `FeedbackRetrievalService.java:49` |
| Stopwords cache TTL | `24h` | `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java` |
| Retention cleanup job | zilnic `03:30` (`cron = 0 30 3 * * *`) | `backend/src/main/java/com/example/services/RetentionCleanupJob.java` |
| CORS max-age | `3600s` | `backend/src/main/java/com/example/Application.java` |
| kubectl scan-pods | `10s` | `backend/src/main/java/com/example/controllers/PodsController.java` |
| kubectl pod describe/logs/events | `15s` fiecare | `backend/src/main/java/com/example/controllers/PodsController.java` |
| kubectl scan-nodes | `10s` | `backend/src/main/java/com/example/controllers/NodesController.java` |
| kubectl node describe | `25s` | `backend/src/main/java/com/example/controllers/NodesController.java` |
| cluster test / namespaces | `10s` | `backend/src/main/java/com/example/controllers/ClusterController.java` |

---

## 3. Parametri LLM (chat gpt-oss + embeddings Ollama)

Valori implicite din `Server/src/main/resources/application.properties`, suprascrise prin variabile
de mediu. Chat-ul rulează pe gpt-oss (OpenAI-compatible) sub `llm.chat.*`; embeddings rămân pe Ollama sub `ollama.*`:

| Proprietate | Valoare implicită | Variabilă de mediu |
|-------------|:-----------------:|-------------------|
| `llm.chat.base-url` | `http://localhost:11434/v1` | `LLM_CHAT_BASE_URL` |
| `llm.chat.model` | `openai/gpt-oss-120b` | `LLM_CHAT_MODEL` |
| `llm.chat.api-key` | _(gol)_ | `LLM_CHAT_API_KEY` |
| `llm.chat.temperature` | `0.2` | `LLM_CHAT_TEMPERATURE` |
| `llm.chat.timeout-seconds` | `300` | `LLM_CHAT_TIMEOUT_SECONDS` |
| `llm.chat.max-output-tokens` | `0` (fără limită) | `LLM_CHAT_MAX_OUTPUT_TOKENS` |
| `llm.chat.num-ctx` | `32768` | `LLM_CHAT_NUM_CTX` |
| `llm.chat.output-reserve-fraction` | `0.15` | `LLM_CHAT_OUTPUT_RESERVE_FRACTION` |
| `llm.chat.chars-per-token` | `3.0` | `LLM_CHAT_CHARS_PER_TOKEN` |
| `ollama.base-url` | `http://localhost:11434` | `OLLAMA_BASE_URL` |
| `ollama.embedding-model` | `nomic-embed-text` | `OLLAMA_EMBEDDING_MODEL` |
| `ollama.embedding-timeout-seconds` | `30` | `OLLAMA_EMBEDDING_TIMEOUT` |

> `num_ctx = 8192` tokens ≈ 32 768 caractere disponibil. Promptul asamblat este plafonate la
> `MAX_TOTAL_PROMPT_CHARS = 28 000` caractere, asigurând că contextul LLM nu este depășit în
> condiții normale.  
> `MetricsCollector.recordNumCtxOverflowIfApplicable` semnalează când `promptChars > numCtx × 4`.

---

## 4. Arhitectura RAG (Retrieval-Augmented Generation)

```
Cerere utilizator
      │
      ▼
[AiEngine.fetchRelevantDocs]
      │
      ├─ 1. Construct query: userText + artifact snippets (≤ 400 chars/artifact)
      │
      ├─ 2. getRelevantDocsByBm25Boosted(query, 12 chunks, boostedUrls)
      │       │
      │       ├─ LuceneChunkIndex.search(query, 12, boostedUrls)
      │       │       │
      │       │       ├─ BM25 Lucene (Apache Lucene 9.11.1, MMapDirectory, BM25Similarity)
      │       │       ├─ Dacă boostedUrls ≠ ∅: extrage 2×12 candidați, înmulțește scorul
      │       │       │   cu 1.5× pentru URL-urile care au primit 👍, re-sortează, trimite topK
      │       │       └─ ChunkSplitter: TARGET=1200 / MAX=1800 / OVERLAP=100 chars
      │       │
      │       └─ assembleContext: grupare pe URL, cap la MAX_RAG_CONTEXT_CHARS = 12 000
      │
      ├─ 3. Fallback: getRelevantDocs (keyword scoring legacy)
      │
      └─ 4. (Opțional) [NEEDS_SEARCH:] → KubernetesDynamicSearcher
                 │
                 ├─ DuckDuckGo HTML scraping → top 2 rezultate kubernetes.io/docs
                 ├─ Readability4j extraction → text curat
                 └─ Persistat în DB (kubernetes_doc_pages, dynamic=true)
                    + indexat în Lucene (indexPage)

Extragere text din pagini:
  HTML → Readability4j Article.textContent()
       → fallback JSoup selector (main, article, .td-content)
  Stocat în PostgreSQL TEXT (unbounded, cap aplicativ 500 000 chars)
  Indexat în Lucene: ChunkSplitter → DocChunk → StringField(pageId/url) + TextField(title/text)
```

---

## 5. Streaming SSE end-to-end

Trei salturi din frontend până la gpt-oss:

```
Browser (EventSource / fetch + ReadableStream)
  │  POST /api/chat/stream   (backend port 8080)
  │
Backend Spring MVC — AiForwardingService.forwardStream()
  │  Flux<ServerSentEvent<String>>  (WebClient, timeout 120s)
  │  POST /v1/chat/stream   (AI Server port 8090)
  │
AI Server Spring WebFlux — ChatController.chatStream()
  │  Flux<String> → map to SSE events: meta / chunk / done / error
  │  AiEngine.solveStream() → GptChatClient.chatStream()
  │  POST /v1/chat/completions  (gpt-oss, stream=true, SSE)
  │
gpt-oss
```

Tipuri de evenimente SSE:
- `meta`  — primul eveniment; payload JSON `{conversationId, protocolVersion}`
- `chunk` — un token/fragment din răspunsul asistentului
- `done`  — semnalează sfârșitul streamului
- `error` — emis în loc de `done` la eșec

Persistența pe streaming: `doFinally` în `solveStream()` scrie răspunsul complet în
`HistoryService` indiferent dacă stream-ul s-a terminat normal sau a fost anulat.

---

## 6. Bucla de feedback

```
Utilizator apasă 👍 pe un răspuns
      │
      ▼
POST /v1/history/{convId}/feedback  {"score": 1}
      │
      ├─ ProblemResolutionRepository: setFeedback(convId, 1)   (dynamic search path)
      │
      └─ FeedbackRetrievalService.onPositiveFeedback(convId)
              │
              ├─ Citeste QaFeedback (ultima înregistrare pentru conversație)
              ├─ OllamaEmbeddingClient.embedAsPgVector(userQuestion)
              │       └─ POST /api/embeddings (nomic-embed-text, 768 dims)
              │          → "[d0,...,d767]"  (Locale.ROOT, 6 zecimale)
              ├─ qaRepo.updateEmbeddingAndFeedback(id, vec, 1)
              │       └─ CAST(:vec AS vector)  (pgvector HNSW index)
              └─ Invalidează cache boostedUrls (cachedBoostedUrlsAt = 0)

La următorul mesaj similar:
      FeedbackRetrievalService.findSimilarCases(userQuestion)
        └─ ANN cosine search în qa_feedback WHERE feedback >= 1
           → până la 3 cazuri cu similaritate ≥ 0.75
           → injectate în system prompt sub "PREVIOUSLY SUCCESSFUL ANSWERS"
           → budget 4 000 chars, truncare per caz
```

---

## 7. Observabilitate — endpoint-uri de diagnosticare

Toate endpoint-urile sunt pe AI Server (port 8090 implicit):

| Endpoint | Metodă | Descriere |
|----------|--------|-----------|
| `GET /v1/metrics` | GET | Snapshot complet al tuturor contoarelor + medii derivate (`MetricsController`) |
| `POST /v1/metrics/reset` | POST | Zeroizare contoare între demo-uri |
| `GET /v1/feedback/stats` | GET | Statistici tabel `qa_feedback`: total/pozitiv/negativ/neutru/cu-embedding |
| `GET /v1/feedback/boosted-urls` | GET | Starea cache-ului URL-urilor boost + TTL rămas |
| `GET /v1/index/stats` | GET | Număr chunk-uri Lucene, bytes index, data ultimului rebuild |
| `GET /actuator/health` | GET | Health probe Spring Boot (configurat în `application.properties`) |

### Contoarele `GET /v1/metrics`

```json
{
  "totalChatRequests":        "<n>",
  "totalStreamingRequests":   "<n>",
  "totalFallbackResponses":   "<n>",
  "totalNeedsSearchTriggers": "<n>",
  "totalResponseTimeMs":      "<ms>",
  "totalChatLatencyMs":       "<ms>",
  "totalEmbeddingLatencyMs":  "<ms>",
  "totalPromptChars":         "<n>",
  "totalResponseChars":       "<n>",
  "bm25Searches":             "<n>",
  "bm25EmptyResults":         "<n>",
  "bm25BoostedSearches":      "<n>",
  "similarCasesQueries":      "<n>",
  "similarCasesHits":         "<n>",
  "embeddingFailures":        "<n>",
  "numCtxOverflowsApprox":    "<n>",
  "avgResponseTimeMs":        "<ms>",
  "avgChatLatencyMs":         "<ms>",
  "avgEmbeddingLatencyMs":    "<ms>",
  "avgPromptChars":           "<n>",
  "avgResponseChars":         "<n>",
  "bm25HitRate":              "<0.0–1.0>",
  "similarCasesHitRate":      "<0.0–1.0>",
  "streamingRatio":           "<0.0–1.0>"
}
```

Toate contoarele sunt `AtomicLong`; nu există `synchronized` pe calea normală de execuție.

---

## 8. Zone de risc rămase

| Risc | Detalii | Locație |
|------|---------|---------|
| **HistoryService fără cap global** | `ConcurrentHashMap<String, List<HistoryEntry>>` poate crește nelimitat în număr de conversații; nu există evicție de tip LRU sau limită totală de memorie | `Server/src/main/java/com/kdiag/server/ai/history/HistoryService.java` |
| **NEEDS_SEARCH incompatibil cu streaming** | Bucla dinamică RAG necesită răspunsul complet al modelului înainte de re-interogare; pe calea de streaming se sare deliberat (TODO în cod) | `AiEngine.solveStream()` |
| **Embedding sincron în thread HTTP** | `onPositiveFeedback` rulează `embedAsPgVector` sincron; dacă modelul Ollama este lent (> 30s), feedback-ul 👍 blochează thread-ul HTTP al backend-ului | `FeedbackRetrievalService.onPositiveFeedback()` |
| **Contoare metrici non-atomice cross-field** | `snapshot()` citește câmpuri atomice individual; nu există snapshot transacțional, deci perechea `(totalChatRequests, totalResponseTimeMs)` poate fi inconsistentă sub concurență mare | `MetricsCollector.snapshot()` |
| **fetch() frontend fără timeout** | Cererile SSE din browser nu au un timeout client-side explicit; dacă server-ul tace, browser-ul așteaptă indefinit | `frontend/js/chat.js` |
| **Lucene index pe disc fără rotație** | Indexul `lucene_index/` crește monoton; nu există compactare automată sau limită de dimensiune | `LuceneChunkIndex`, `kdiag.lucene.dir=./lucene_index` |
| **pgvector HNSW neindexat pentru feedback < 1** | Căutarea ANN este eficientă; totuși, tabelul `qa_feedback` cu `feedback=0` (neutru) nu este niciodată curățat, putând crește continuu | `db_migrations/2026-05-12_qa_feedback_pgvector.sql` |

---

## 9. Rezumat rapid

| Categorie | Parametru | Valoare |
|-----------|-----------|--------:|
| Prompt total max | `MAX_TOTAL_PROMPT_CHARS` | 28 000 chars |
| Artifact per prompt | `MAX_ARTIFACT_PROMPT_CHARS` | 6 000 chars |
| Artefacte per request | `MAX_ARTIFACTS_PER_REQUEST` | 5 |
| Context RAG injectat | `MAX_RAG_CONTEXT_CHARS` | 12 000 chars |
| Mesaj utilizator max | `MAX_USER_MESSAGE_CHARS` | 16 000 chars |
| Chunk Lucene țintă | `TARGET_CHUNK_CHARS` | 1 200 chars |
| Chunk Lucene maxim | `MAX_CHUNK_CHARS` | 1 800 chars |
| Suprapunere chunk | `OVERLAP_CHARS` | 100 chars |
| Cazuri CBR returnate | `MAX_SIMILAR_CASES` | 3 |
| Prag similaritate CBR | `SIMILARITY_THRESHOLD` | 0.75 |
| Persist doc max | `ABSOLUTE_PERSIST_CHAR_CAP` | 500 000 chars |
| Context chat (buget local) | `llm.chat.num-ctx` | 32 768 tokens |
| Embedding model | `ollama.embedding-model` | nomic-embed-text (768 dims) |
| Timeout chat (gpt-oss) | `llm.chat.timeout-seconds` | 300 s |
| Timeout embedding | `ollama.embedding-timeout-seconds` | 30 s |
| Cache boosted URLs | `BOOSTED_URLS_TTL_MS` | 60 s |
| Istoric brut max | `MAX_RECENT_HISTORY_MESSAGES` | 12 mesaje |
| Prag sumarizare | `SUMMARY_TRIGGER_HISTORY_MESSAGES` | 10 mesaje |
