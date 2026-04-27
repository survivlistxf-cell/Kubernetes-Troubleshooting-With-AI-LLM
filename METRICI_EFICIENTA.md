# Metrici de eficienta extrase din cod

Acest fisier inventariaza parametrii care influenteaza performanta/costul in proiect: timp, dimensiuni, context, RAG, memorie.

## 0) Limite DOAR in numar de caractere (cu sursa exacta din cod)

Nota: sectiunea aceasta include doar limite exprimate in caractere (`substring`, `truncate`, `VARCHAR(n)`), nu limite in MB/secunde.

- `10,000` caractere per artifact in prompt-ul catre LLM
  - cod exact: `truncate(a.getContent(), 10000)`
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- `200` caractere din fiecare artifact folosite pentru query-ul de retrieval docs
  - cod exact: `a.getContent().substring(0, 200)`
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- `15,000` caractere maxim pentru contextul total injectat din docs
  - cod exact: `private static final int MAX_CONTEXT_CHARS = 15000;`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- `15,000` caractere maxim returnate dintr-un document in dynamic search
  - cod exact: `return truncate(content, 15000);`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- `20,000` caractere maxim salvate per document docs in DB (static/dynamic)
  - cod exact: `truncate(text, 20000)` si `truncate(content, 20000)`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- `500` caractere pentru preview/truncare string in log-ul payload backend -> AI
  - cod exact: `trimLargeStrings(kdiag, 500)`
  - sursa: `backend/src/main/java/com/example/services/AiForwardingService.java`
- `100` caractere pentru preview/truncare content mesaj in log-ul Ollama
  - cod exact: `truncate(m.getOrDefault("content", ""), 100)`
  - sursa: `Server/src/main/java/com/kdiag/server/ollama/OllamaClient.java`

### Limite de tip coloana (caractere) din schema

- `conversation_id`: `VARCHAR(100)`
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
  - sursa: `init.sql`
- `file_name`: `VARCHAR(255)`
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
- `mime_type`: `VARCHAR(120)`
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
- `sha256`: `VARCHAR(64)`
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
- `content_encoding`: `VARCHAR(20)`
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
- `source`: `VARCHAR(100)`
  - sursa: `init.sql`
- `cluster_configs.name`: `VARCHAR(100)`
  - sursa: `init.sql`
- `cluster_configs.display_name`: `VARCHAR(255)`
  - sursa: `init.sql`
- `cluster_configs.kubeconfig_path`: `VARCHAR(500)`
  - sursa: `init.sql`
- `cluster_configs.context_name`: `VARCHAR(255)`
  - sursa: `init.sql`
- `cluster_configs.default_namespace`: `VARCHAR(100)`
  - sursa: `init.sql`
- `problem_resolutions.searchQuery`: `length = 1000`
  - cod exact: `@Column(nullable = false, length = 1000)`
  - sursa: `Server/src/main/java/com/kdiag/server/entities/ProblemResolution.java`
- `kubernetes_doc_pages.url`: `length = 1024`
  - cod exact: `@Column(nullable = false, unique = true, length = 1024)`
  - sursa: `Server/src/main/java/com/kdiag/server/entities/KubernetesDocPage.java`

## 1) Timp (timeouts, scheduling, cache TTL)

- `kubectl quick check`: `3s`
  - sursa: `backend/src/main/java/com/example/services/KubectlService.java`
- `scan-pods`: timeout comanda `10s`
  - sursa: `backend/src/main/java/com/example/controllers/PodsController.java`
- `pod-details`:
  - `describe`: `15s`
  - `json`: `15s`
  - `events`: `15s`
  - `logs`: `15s`
  - sursa: `backend/src/main/java/com/example/controllers/PodsController.java`
- `scan-nodes`: timeout comanda `10s`
  - sursa: `backend/src/main/java/com/example/controllers/NodesController.java`
- `node-details`:
  - `describe`: `25s`
  - `json`: `20s`
  - `events`: `20s`
  - sursa: `backend/src/main/java/com/example/controllers/NodesController.java`
- `cluster test` (`/api/clusters/{id}/test`): `10s`
  - sursa: `backend/src/main/java/com/example/controllers/ClusterController.java`
- `cluster namespaces` (`/api/clusters/{id}/namespaces`): `10s`
  - sursa: `backend/src/main/java/com/example/controllers/ClusterController.java`
- `Ollama call timeout`: `60s` (configurabil din `OLLAMA_TIMEOUT_SECONDS`)
  - sursa: `Server/src/main/resources/application.properties`
  - folosit in: `Server/src/main/java/com/kdiag/server/ollama/OllamaClient.java`
- `RAG scraping (DuckDuckGo HTML)`: `10s`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- `RAG scraping (kubernetes pages)`: `8s`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- `stopwords cache TTL`: `24h`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- cleanup retention job: ruleaza zilnic la `03:30` (`cron = 0 30 3 * * *`)
  - sursa: `backend/src/main/java/com/example/services/RetentionCleanupJob.java`
- CORS max-age: `3600s`
  - sursa: `backend/src/main/java/com/example/Application.java`

## 2) Dimensiune (payload, fisiere, text, memorie)

- Backend multipart upload:
  - `spring.servlet.multipart.max-file-size=5MB`
  - `spring.servlet.multipart.max-request-size=10MB`
  - sursa: `backend/src/main/resources/application.properties`
- AI Server multipart upload:
  - `spring.servlet.multipart.max-file-size=10MB`
  - `spring.servlet.multipart.max-request-size=10MB`
  - sursa: `Server/src/main/resources/application.properties`
- Limita atasament individual salvat in DB: `2MB`
  - `MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024`
  - sursa: `backend/src/main/java/com/example/services/AttachmentService.java`
- Numar maxim atasamente per mesaj: `12`
  - `MAX_ATTACHMENTS_PER_MESSAGE = 12`
  - sursa: `backend/src/main/java/com/example/services/AttachmentService.java`
- `kubectl logs` preluat in detalii: `--tail=200`
  - sursa: `backend/src/main/java/com/example/controllers/PodsController.java`
- limita text artifact in prompt (per artifact): `10,000` caractere
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- max artifacts incluse in prompt per request: `10`
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- context docs injectat in prompt: `MAX_CONTEXT_CHARS = 15,000`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- text doc salvat in DB la scrape static/dinamic: trunchiat la `20,000` caractere
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- text doc returnat din dynamic search catre LLM: trunchiat la `15,000` caractere
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- snippet extras din artifact pentru query semantic docs: max `200` caractere/artifact
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- truncare log payload backend->AI (doar pentru logging): `500` caractere/string
  - sursa: `backend/src/main/java/com/example/services/AiForwardingService.java`
- truncare log mesaje Ollama (doar pentru logging): `100` caractere/mesaj
  - sursa: `Server/src/main/java/com/kdiag/server/ollama/OllamaClient.java`

### Dimensiuni de infrastructura (container/runtime)

- backend container:
  - `mem_limit: 2g`
  - `JAVA_OPTS: -Xmx1536m -Xms768m`
  - sursa: `docker-compose.yml`
- frontend container:
  - `mem_limit: 1g`
  - `NODE_OPTIONS=--max-old-space-size=512`
  - sursa: `docker-compose.yml`
- AI server container: nu are `mem_limit` explicit in `docker-compose-ai.yml`

## 3) Context conversațional (istoric/memorie)

- AI history este in-memory (`ConcurrentHashMap<String, List<HistoryEntry>>`)
  - **nu exista limita explicita** pe:
    - numar conversatii active
    - numar mesaje per conversatie
    - lungime mesaj
  - sursa: `Server/src/main/java/com/kdiag/server/ai/history/HistoryService.java`
- In `AiEngine`, pentru conversatiile cu `conversationId`, se trimit in LLM toate entry-urile din history + mesajul de sistem
  - **fara clipping/fereastra maxima**
  - sursa: `Server/src/main/java/com/kdiag/server/ai/AiEngine.java`
- In backend, contextele persistate (`conversation_context`) se curata dupa `30 zile`
  - sursa: `backend/src/main/java/com/example/services/RetentionCleanupJob.java`

## 4) Parametri RAG (retrieval + context assembly)

- surse statice indexate la init: `5` URL-uri Kubernetes docs
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- ranking pagini:
  - daca nu exista keywords -> returneaza primele `min(3, pages.size())`
  - cu keywords -> top `3`
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDocsScraper.java`
- dynamic search:
  - ia top `2` rezultate DDG (filtrate pe `kubernetes.io/docs`)
  - sursa: `Server/src/main/java/com/kdiag/server/docs/KubernetesDynamicSearcher.java`
- temperatura modelului: `0.2` (default/configurabil)
  - sursa: `Server/src/main/resources/application.properties`
  - folosire: `Server/src/main/java/com/kdiag/server/ollama/OllamaClient.java`
- model default: `llama3.1` (configurabil)
  - sursa: `Server/src/main/resources/application.properties`

## 5) Limite de schema DB (campuri cu maxima explicita)

- `chat_attachments`:
  - `conversation_id VARCHAR(100)`
  - `file_name VARCHAR(255)`
  - `mime_type VARCHAR(120)`
  - `sha256 VARCHAR(64)`
  - `content_encoding VARCHAR(20)`
  - `content BYTEA` (fara limita SQL, limitat aplicativ la 2MB/atasament)
  - sursa: `db_migrations/2026-03-03_add_chat_attachments.sql`
- `conversation_context`:
  - `conversation_id VARCHAR(100)`
  - `source VARCHAR(100)`
  - `payload_json TEXT`
  - sursa: `init.sql`
- `cluster_configs`:
  - `name VARCHAR(100)`
  - `display_name VARCHAR(255)`
  - `kubeconfig_path VARCHAR(500)`
  - `context_name VARCHAR(255)`
  - `default_namespace VARCHAR(100)`
  - sursa: `init.sql`
- `problem_resolutions.searchQuery`: `length = 1000`
  - sursa: `Server/src/main/java/com/kdiag/server/entities/ProblemResolution.java`
- `kubernetes_doc_pages.url`: `length = 1024`
  - sursa: `Server/src/main/java/com/kdiag/server/entities/KubernetesDocPage.java`

## 6) Parametri fara maxima explicita (zone de risc)

- `fetch()` din frontend fara timeout custom
  - sursa: `frontend/js/api.js`
- `RestTemplate` backend->AI fara connect/read timeout setat explicit
  - sursa: `backend/src/main/java/com/example/services/AiForwardingService.java`
- `HistoryService` in-memory fara cap de dimensiune
  - sursa: `Server/src/main/java/com/kdiag/server/ai/history/HistoryService.java`
- protocol model (`KdiagModels`) fara `@Size` pe campuri text/artifacts
  - sursa: `Server/src/main/java/com/kdiag/server/protocol/KdiagModels.java`
- frontend permite atasare fisiere text fara limita client-side (se citeste complet in memorie)
  - sursa: `frontend/js/attachments.js`

## 7) Rezumat rapid (valorile maxime principale)

- Max upload backend: `5MB`/fisier, `10MB`/request
- Max upload AI server: `10MB`/fisier, `10MB`/request
- Max atasament persistat: `2MB`
- Max atasamente/mesaj: `12`
- Max artifacte incluse in prompt: `10`
- Max text per artifact in prompt: `10,000` chars
- Max context docs injectat: `15,000` chars
- Max pagini statice in context: `3`
- Max rezultate dynamic search: `2`
- Ollama timeout: `60s`
- kubectl timeouts: `10s` scan, `15-25s` detalii
- Retention date context/attachments: `30 zile`

## 8) Observatie

Interpretare importanta: exista limite bune pe upload/attachments si pe context RAG, dar exista inca 3 zone ne-limiate care pot degrada performanta la volum mare:
- history in-memory ne-bounded,
- lipsa timeout explicit pe `RestTemplate`,
- lipsa limitelor de marime la nivel protocol (`@Size`) si frontend attach.
