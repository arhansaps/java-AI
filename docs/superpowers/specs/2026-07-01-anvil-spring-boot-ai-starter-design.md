# Anvil — Spring Boot AI Starter — Design

## Summary

Build "Anvil," a production-shaped Spring Boot starter for LLM/RAG applications, per `readme.md`. The README is itself an 8-phase build spec (Phase 0–7); this document resolves the open decisions it flags and pins down the concrete technical choices needed before writing an implementation plan. Scope: all phases (0–7), implemented as a single plan with a verification gate at each phase's "Done when" checkpoint.

## Decisions Resolved

- **Chat model provider:** Ollama (not OpenAI). Chosen for a zero-API-key quickstart, matching the README's stated priority on adoption over peak model quality.
- **Default Ollama model:** `llama3.2:3b` — middle ground between the 1b (faster, weaker) and 8b (slower, better) options; reasonable quality on CPU-only dev machines.
- **Local toolchain:** Java 21 is installed; Maven and Ollama are not. The project will use the Maven wrapper (`mvnw`/`mvnw.cmd`) so the build never depends on a system Maven install. Ollama will be run via standalone `docker run` during early-phase dev (before Compose exists in Phase 5), then folded into `docker-compose.yml`.
- **Project is not yet a git repository.** `git init` happens at Phase 0 so there's a repo for the CI workflow (Phase 7) and this spec to live in.

## Package Layout

Per the README's stated structure (`config`, `ai`, `ingestion`, `web` with a `dto` subpackage):

```
src/main/java/.../anvil/
  config/
    AiConfig.java            — ChatLanguageModel, EmbeddingModel, EmbeddingStore, ChatMemoryProvider beans
  ai/
    Assistant.java           — @AiService interface (system prompt + chat method)
    memory/
      RedisChatMemoryStore.java  — implements LangChain4j's ChatMemoryStore (the differentiator)
  ingestion/
    DocumentIngestionService.java — chunks + embeds documents into pgvector
  web/
    ChatController.java      — POST /api/chat
    DocumentController.java  — POST /api/documents
    dto/
      ChatRequest, ChatResponse, IngestRequest, IngestResponse, ...
```

## Phase-by-Phase Technical Plan

**Phase 0 — Setup**
`git init`. Scaffold via Maven wrapper, Spring Boot 3.3, Java 21. LangChain4j BOM pinned. Package skeleton per above. `mvn clean package` (via `./mvnw`) succeeds with no custom beans.

**Phase 1 — Boot + Health**
`spring-boot-starter-web` + `spring-boot-starter-actuator`. `/actuator/health` → 200.

**Phase 2 — Raw LLM Round Trip (Ollama)**
`langchain4j-spring-boot-starter` + `langchain4j-ollama-spring-boot-starter`. `Assistant` interface: `@SystemMessage` + `String chat(String message)`. `ChatController` exposes `POST /api/chat`. `ChatLanguageModel` base URL configurable via `application.yml` (`ollama.base-url`, defaulting to `http://localhost:11434`), model name `llama3.2:3b`. Dev-time: run Ollama via standalone `docker run -p 11434:11434 ollama/ollama` and `docker exec ... ollama pull llama3.2:3b`.

**Phase 3 — RAG**
Add `langchain4j-pgvector` + `langchain4j-embeddings-all-minilm-l6-v2`. `AiConfig` gains `EmbeddingModel` (in-process ONNX, no API key) and `PgVectorEmbeddingStore` beans. `DocumentIngestionService` uses `DocumentSplitters.recursive(...)` + `EmbeddingStoreIngestor` to chunk/embed into pgvector. `POST /api/documents` triggers ingestion. Dev-time Postgres via standalone `docker run pgvector/pgvector:pg16`. Verification: ingest a doc with a fact not in the model's training data, confirm `/api/chat` can answer it.

**Phase 4 — Persistent Memory (the differentiator)**
`RedisChatMemoryStore implements ChatMemoryStore`: `getMessages`/`updateMessages`/`deleteMessages`, backed by `StringRedisTemplate`, key pattern `chat:memory:{sessionId}`, JSON via LangChain4j's `ChatMessageSerializer`/`ChatMessageDeserializer`. `ChatMemoryProvider` bean wires one `MessageWindowChatMemory` per session ID, using the Redis store. `Assistant.chat` gains `@MemoryId String sessionId` parameter; `ChatController` accepts a session ID in the request. This class gets the most deliberate test coverage of the project. Verification: multi-turn conversation survives an app restart.

**Phase 5 — Docker Compose**
`docker-compose.yml`: `postgres` (pgvector image), `redis`, `ollama` (with a one-shot init step that pulls `llama3.2:3b` on first boot), `app` (multi-stage `Dockerfile` — build stage runs `./mvnw package`, run stage on a slim JRE base image). `docker compose up` brings up the full stack.

**Phase 6 — Observability**
`micrometer-registry-prometheus`, `/actuator/prometheus` exposed. `prometheus.yml` scrape config + `prometheus` and `grafana` services added to Compose. Grafana provisioned with a datasource and a starter dashboard (JVM memory, HTTP request rate) with zero manual setup.

**Phase 7 — CI and Polish**
`.github/workflows/ci.yml`: GitHub Actions with Postgres and Redis as service containers, so `mvn verify` runs real integration tests against them. **Ollama is not run in CI** (too slow/heavy to pull a model on every run) — RAG ingestion and Redis memory persistence are tested directly against real Postgres/Redis; the `@AiService` wiring itself is covered separately with a stub/mock `ChatLanguageModel`, not a live model call. Add `.env.example`, `.gitignore`, MIT `LICENSE`. Rewrite `readme.md` as a five-minute quickstart.

## Testing Strategy

- **Unit tests:** `RedisChatMemoryStore` serialization round-trip (mocked `StringRedisTemplate`), `DocumentIngestionService` chunking logic.
- **Integration tests (Phase 7, CI-backed):** real Postgres for embedding storage/retrieval, real Redis for chat memory persistence across simulated "restarts" (new `ChatMemoryProvider` instance, same Redis data).
- **Manual/dev verification per phase:** each phase's README "Done when" criterion is checked manually against the locally running stack (e.g., curl `/api/chat`, restart the app and verify memory, `docker compose up` from clean).

## Error Handling Notes

- `RedisChatMemoryStore` should let Redis connection failures surface as exceptions rather than silently falling back to in-memory — masking persistence failures would defeat the project's stated differentiator.
- `DocumentIngestionService` validates non-empty input before chunking; embedding/store failures propagate as 5xx from `/api/documents` rather than being swallowed.
- No retry/circuit-breaker logic — out of scope for a starter template; YAGNI per the project's "production-shaped, not production-hardened" framing.
