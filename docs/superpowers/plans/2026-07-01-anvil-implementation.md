# Anvil Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Anvil, a production-shaped Spring Boot starter for LLM/RAG applications, end-to-end through all 8 phases described in `readme.md` and `docs/superpowers/specs/2026-07-01-anvil-spring-boot-ai-starter-design.md`.

**Architecture:** Spring Boot 3.5 app on Java 21, using LangChain4j's Spring Boot starter (`@AiService` auto-configuration) for an Ollama-backed chat assistant, pgvector for RAG embeddings, and a hand-rolled `RedisChatMemoryStore` for persistent chat memory (the project's headline feature). Docker Compose ties together Postgres, Redis, Ollama, the app, and a Prometheus/Grafana observability stack.

**Tech Stack:** Spring Boot 3.5.16, Java 21, LangChain4j 1.17.1 (core) / 1.17.1-beta27 (Spring + pgvector + embeddings modules), Maven (via wrapper, no system Maven required), Testcontainers 1.21.4 (managed by Spring Boot's BOM) for integration tests, Docker Compose for local orchestration.

## Global Constraints

- Java 21, Spring Boot 3.5.16 (verified: matches what `langchain4j-spring-boot-starter:1.17.1-beta27` actually depends on — see design doc's resolved-decision note; the README's literal "3.3" is stale/EOL).
- LangChain4j BOM `dev.langchain4j:langchain4j-bom:1.17.1`; Spring/pgvector/embeddings integration modules pinned to `1.17.1-beta27` (verified via Maven Central metadata — these modules version independently from core and are still on the beta train).
- Default chat provider: Ollama, model `llama3.2:3b`, base URL configurable via `OLLAMA_BASE_URL` env var (default `http://localhost:11434`).
- groupId `com.anvil`, artifactId `anvil`, base package `com.anvil`.
- Package layout: `com.anvil.config`, `com.anvil.ai` (+ `com.anvil.ai.memory`), `com.anvil.ingestion`, `com.anvil.web` (+ `com.anvil.web.dto`) — per the approved design doc.
- No live Ollama dependency in automated tests (unit or integration) — Ollama is verified manually via curl against a dev-time container. Automated tests use `@MockitoBean`/mocks for the `Assistant` at the web layer, and Testcontainers (real Postgres/Redis, no mocks) for RAG and chat-memory integration tests.
- Git is intentionally not initialized yet (per explicit user instruction) — no task in this plan runs `git init`, `git add`, or `git commit`. Do not add commit steps.
- Local toolchain: Java 21 is installed; Maven and Ollama are not. Every build/test command in this plan uses `./mvnw` (the Maven Wrapper), never a bare `mvn`.

---

### Task 1: Bootstrap Maven wrapper and project skeleton (Phase 0)

**Files:**
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `pom.xml`
- Create: `src/main/java/com/anvil/AnvilApplication.java`
- Create: `src/main/java/com/anvil/config/.gitkeep`, `src/main/java/com/anvil/ai/.gitkeep`, `src/main/java/com/anvil/ingestion/.gitkeep`, `src/main/java/com/anvil/web/.gitkeep`, `src/main/java/com/anvil/web/dto/.gitkeep` (empty package placeholders so the structure exists before later tasks populate it)
- Test: `src/test/java/com/anvil/AnvilApplicationTests.java`

**Interfaces:**
- Produces: a Maven build invokable as `./mvnw <goal>` from the project root; a Spring Boot app entry point `com.anvil.AnvilApplication` with no custom beans.

- [ ] **Step 1: Fetch the canonical Maven Wrapper scripts**

These exact files were verified reachable on 2026-07-01:

```bash
mkdir -p .mvn/wrapper
curl -fsSL -o mvnw https://raw.githubusercontent.com/apache/maven-wrapper/master/maven-wrapper-distribution/src/resources/mvnw
curl -fsSL -o mvnw.cmd https://raw.githubusercontent.com/apache/maven-wrapper/master/maven-wrapper-distribution/src/resources/mvnw.cmd
chmod +x mvnw
```

- [ ] **Step 2: Write `.mvn/wrapper/maven-wrapper.properties`**

```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo1.maven.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar
```

Both URLs were verified to return HTTP 200 on 2026-07-01.

- [ ] **Step 3: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>com.anvil</groupId>
    <artifactId>anvil</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>anvil</name>
    <description>Production-shaped Spring Boot starter for LLM and RAG applications</description>

    <properties>
        <java.version>21</java.version>
        <langchain4j.version>1.17.1</langchain4j.version>
        <langchain4j-spring.version>1.17.1-beta27</langchain4j-spring.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-bom</artifactId>
                <version>${langchain4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 4: Create the main application class**

`src/main/java/com/anvil/AnvilApplication.java`:

```java
package com.anvil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnvilApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnvilApplication.class, args);
    }
}
```

- [ ] **Step 5: Create empty package placeholders**

Create empty files `src/main/java/com/anvil/config/.gitkeep`, `src/main/java/com/anvil/ai/.gitkeep`, `src/main/java/com/anvil/ingestion/.gitkeep`, `src/main/java/com/anvil/web/.gitkeep`, `src/main/java/com/anvil/web/dto/.gitkeep` so the package directories exist (they'll be populated with real classes in later tasks; empty Java packages with no files don't survive as directories without something in them).

- [ ] **Step 6: Write the context-loads test**

`src/test/java/com/anvil/AnvilApplicationTests.java`:

```java
package com.anvil;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AnvilApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 7: Build and verify**

Run: `./mvnw -B clean package`
Expected: `BUILD SUCCESS`, with `AnvilApplicationTests.contextLoads` passing and no beans beyond Spring Boot defaults (no custom `@Configuration`/`@Service`/`@Controller` classes exist yet).

---

### Task 2: Boot + Health (Phase 1)

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `com.anvil.AnvilApplication` (Task 1).
- Produces: `/actuator/health` endpoint returning 200.

- [ ] **Step 1: Add web + actuator dependencies to `pom.xml`**

Add inside `<dependencies>`, after `spring-boot-starter`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

- [ ] **Step 2: Write `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: anvil

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 3: Write a test that hits the real health endpoint**

`src/test/java/com/anvil/HealthEndpointTests.java`:

```java
package com.anvil;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void healthEndpoint_returns200() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 4: Run and verify**

Run: `./mvnw -B test -Dtest=HealthEndpointTests`
Expected: `BUILD SUCCESS`, `healthEndpoint_returns200` passes.

---

### Task 3: Raw LLM round trip via Ollama (Phase 2)

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/anvil/ai/Assistant.java`
- Create: `src/main/java/com/anvil/web/dto/ChatRequest.java`
- Create: `src/main/java/com/anvil/web/dto/ChatResponse.java`
- Create: `src/main/java/com/anvil/web/ChatController.java`
- Test: `src/test/java/com/anvil/web/ChatControllerTest.java`

**Interfaces:**
- Produces: `Assistant.chat(String message): String` (an `@AiService`), `POST /api/chat` accepting `{"message": "..."}` and returning `{"answer": "..."}`.

- [ ] **Step 1: Add LangChain4j + Ollama starter dependencies to `pom.xml`**

Add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-spring-boot-starter</artifactId>
            <version>${langchain4j-spring.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-ollama-spring-boot-starter</artifactId>
            <version>${langchain4j-spring.version}</version>
        </dependency>
```

- [ ] **Step 2: Add Ollama config to `application.yml`**

Append:

```yaml
langchain4j:
  ollama:
    chat-model:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      model-name: ${OLLAMA_MODEL:llama3.2:3b}
      temperature: 0.7
```

- [ ] **Step 3: Write the `Assistant` AI service**

`src/main/java/com/anvil/ai/Assistant.java`:

```java
package com.anvil.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {

    @SystemMessage("You are Anvil, a helpful assistant. Answer questions clearly and concisely. " +
            "If retrieved context is provided, ground your answer in it.")
    String chat(String message);
}
```

- [ ] **Step 4: Write the request/response DTOs**

`src/main/java/com/anvil/web/dto/ChatRequest.java`:

```java
package com.anvil.web.dto;

public record ChatRequest(String message) {
}
```

`src/main/java/com/anvil/web/dto/ChatResponse.java`:

```java
package com.anvil.web.dto;

public record ChatResponse(String answer) {
}
```

- [ ] **Step 5: Write the controller**

`src/main/java/com/anvil/web/ChatController.java`:

```java
package com.anvil.web;

import com.anvil.ai.Assistant;
import com.anvil.web.dto.ChatRequest;
import com.anvil.web.dto.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final Assistant assistant;

    public ChatController(Assistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = assistant.chat(request.message());
        return new ChatResponse(answer);
    }
}
```

- [ ] **Step 6: Write the failing test first**

`src/test/java/com/anvil/web/ChatControllerTest.java`:

```java
package com.anvil.web;

import com.anvil.ai.Assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvil.web.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Assistant assistant;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chat_returnsAssistantReply() throws Exception {
        when(assistant.chat("hello")).thenReturn("hi there");

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatRequest("hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("hi there"));
    }
}
```

Run: `./mvnw -B test -Dtest=ChatControllerTest`
Expected at this point: FAIL to compile if any prior step skipped, otherwise PASS immediately since all production code already exists — this test exists to lock in the contract, not to drive new code (the interface is simple enough to write directly).

- [ ] **Step 7: Run the full test suite**

Run: `./mvnw -B test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Manual verification against a real local Ollama**

Maven/Ollama are not installed on this machine, so Ollama runs as a standalone container for this phase (it gets folded into Compose in Task 9):

```bash
docker run -d --name anvil-ollama -p 11434:11434 -v anvil-ollama-data:/root/.ollama ollama/ollama
docker exec anvil-ollama ollama pull llama3.2:3b
./mvnw spring-boot:run
```

In another terminal:

```bash
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"message\":\"Say hello in one short sentence.\"}"
```

Expected: a JSON body like `{"answer":"Hello! How can I help you today?"}` — a real model response, not an error. Stop the app (Ctrl+C) once confirmed; leave the `anvil-ollama` container running for Task 5/9 reuse, or `docker rm -f anvil-ollama` if you'd rather start fresh later.

---

### Task 4: RAG — embedding model and pgvector store beans (Phase 3a)

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/anvil/config/AiConfig.java`

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: `EmbeddingModel` bean, `EmbeddingStore<TextSegment>` bean, `ContentRetriever` bean — all consumed by Task 5 (`DocumentIngestionService`) and auto-wired into `Assistant` by `AiServicesAutoConfig`.

- [ ] **Step 1: Add pgvector + embedding model dependencies to `pom.xml`**

```xml
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-pgvector</artifactId>
            <version>${langchain4j-spring.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-embeddings-all-minilm-l6-v2</artifactId>
            <version>${langchain4j-spring.version}</version>
        </dependency>
```

- [ ] **Step 2: Add pgvector connection config to `application.yml`**

```yaml
anvil:
  pgvector:
    host: ${PGVECTOR_HOST:localhost}
    port: ${PGVECTOR_PORT:5432}
    database: ${PGVECTOR_DATABASE:anvil}
    user: ${PGVECTOR_USER:postgres}
    password: ${PGVECTOR_PASSWORD:postgres}
    table: embeddings
```

- [ ] **Step 3: Write `AiConfig`**

`src/main/java/com/anvil/config/AiConfig.java`:

```java
package com.anvil.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    private static final int EMBEDDING_DIMENSION = 384;

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${anvil.pgvector.host}") String host,
            @Value("${anvil.pgvector.port}") int port,
            @Value("${anvil.pgvector.database}") String database,
            @Value("${anvil.pgvector.user}") String user,
            @Value("${anvil.pgvector.password}") String password,
            @Value("${anvil.pgvector.table}") String table) {
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(table)
                .dimension(EMBEDDING_DIMENSION)
                .createTable(true)
                .useIndex(true)
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.6)
                .build();
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./mvnw -B compile`
Expected: `BUILD SUCCESS`. (This task adds no new automated test on its own — `AiConfig`'s beans are exercised by Task 5's integration test, since constructing `PgVectorEmbeddingStore` requires a real Postgres connection and isn't meaningfully unit-testable in isolation.)

---

### Task 5: RAG — document ingestion service and endpoint (Phase 3b)

**Files:**
- Create: `src/main/java/com/anvil/ingestion/DocumentIngestionService.java`
- Create: `src/main/java/com/anvil/web/dto/IngestRequest.java`
- Create: `src/main/java/com/anvil/web/dto/IngestResponse.java`
- Create: `src/main/java/com/anvil/web/DocumentController.java`
- Test: `src/test/java/com/anvil/web/DocumentControllerTest.java`
- Test: `src/test/java/com/anvil/ingestion/DocumentIngestionServiceIntegrationTest.java`
- Modify: `pom.xml` (test-scope Testcontainers deps)

**Interfaces:**
- Consumes: `EmbeddingStore<TextSegment>` and `EmbeddingModel` beans (Task 4).
- Produces: `DocumentIngestionService.ingest(String text): void`; `POST /api/documents` accepting `{"text": "..."}`.

- [ ] **Step 1: Add Testcontainers test dependencies to `pom.xml`**

Spring Boot's parent POM already manages Testcontainers' version (1.21.4) and the Redis module's version (2.2.4), so no explicit `<version>` is needed:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write `DocumentIngestionService`**

`src/main/java/com/anvil/ingestion/DocumentIngestionService.java`:

```java
package com.anvil.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private final EmbeddingStoreIngestor ingestor;

    public DocumentIngestionService(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        this.ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    public void ingest(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Document text must not be blank");
        }
        ingestor.ingest(Document.from(text));
    }
}
```

- [ ] **Step 3: Write the request/response DTOs**

`src/main/java/com/anvil/web/dto/IngestRequest.java`:

```java
package com.anvil.web.dto;

public record IngestRequest(String text) {
}
```

`src/main/java/com/anvil/web/dto/IngestResponse.java`:

```java
package com.anvil.web.dto;

public record IngestResponse(String status) {
}
```

- [ ] **Step 4: Write `DocumentController`**

`src/main/java/com/anvil/web/DocumentController.java`:

```java
package com.anvil.web;

import com.anvil.ingestion.DocumentIngestionService;
import com.anvil.web.dto.IngestRequest;
import com.anvil.web.dto.IngestResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    public DocumentController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public IngestResponse ingest(@RequestBody IngestRequest request) {
        ingestionService.ingest(request.text());
        return new IngestResponse("ingested");
    }
}
```

- [ ] **Step 5: Write the controller's web-layer test**

`src/test/java/com/anvil/web/DocumentControllerTest.java`:

```java
package com.anvil.web;

import com.anvil.ingestion.DocumentIngestionService;
import com.anvil.web.dto.IngestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentIngestionService ingestionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ingest_callsServiceAndReturnsStatus() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new IngestRequest("some document text"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ingested"));

        verify(ingestionService).ingest("some document text");
    }
}
```

Run: `./mvnw -B test -Dtest=DocumentControllerTest`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Write the RAG integration test (this is the real "Done when" verification, automated)**

`src/test/java/com/anvil/ingestion/DocumentIngestionServiceIntegrationTest.java`:

```java
package com.anvil.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DocumentIngestionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("anvil")
            .withUsername("postgres")
            .withPassword("postgres");

    @Test
    void ingestedDocument_isRetrievableByEmbeddingSearch_provingRetrievalIsGrounded() {
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> embeddingStore = PgVectorEmbeddingStore.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(5432))
                .database(postgres.getDatabaseName())
                .user(postgres.getUsername())
                .password(postgres.getPassword())
                .table("embeddings")
                .dimension(384)
                .createTable(true)
                .build();

        DocumentIngestionService service = new DocumentIngestionService(embeddingStore, embeddingModel);
        service.ingest("Anvil's chat memory is backed by Redis via a custom RedisChatMemoryStore, " +
                "not the default in-memory store most LangChain4j tutorials use.");

        Response<Embedding> queryEmbedding = embeddingModel.embed("What does Anvil use to store chat memory?");

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding.content())
                        .maxResults(1)
                        .build());

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).embedded().text()).contains("Redis");
    }
}
```

This test requires Docker (already installed and confirmed working on this machine). The `AllMiniLmL6V2EmbeddingModel` downloads its ONNX model files (~90MB) on first use and caches them — the first run will be slower.

- [ ] **Step 7: Run and verify**

Run: `./mvnw -B test -Dtest=DocumentIngestionServiceIntegrationTest`
Expected: `BUILD SUCCESS` — proves a document ingested through the real pipeline is retrievable by a semantically related query, i.e. retrieval is actually grounding answers, without needing a live LLM call to prove it.

---

### Task 6: RedisChatMemoryStore — the differentiator, unit tested (Phase 4a)

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/anvil/ai/memory/RedisChatMemoryStore.java`
- Test: `src/test/java/com/anvil/ai/memory/RedisChatMemoryStoreTest.java`

**Interfaces:**
- Produces: `RedisChatMemoryStore implements ChatMemoryStore` — `getMessages(Object)`, `updateMessages(Object, List<ChatMessage>)`, `deleteMessages(Object)`. Consumed by Task 7's `ChatMemoryProvider` bean.

- [ ] **Step 1: Add Redis dependency to `pom.xml`**

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/anvil/ai/memory/RedisChatMemoryStoreTest.java`:

```java
package com.anvil.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisChatMemoryStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RedisChatMemoryStore(redisTemplate, 7);
    }

    @Test
    void getMessages_returnsEmptyList_whenNoDataStored() {
        when(valueOperations.get("chat:memory:session-1")).thenReturn(null);

        List<ChatMessage> messages = store.getMessages("session-1");

        assertThat(messages).isEmpty();
    }

    @Test
    void updateMessages_thenGetMessages_roundTripsViaJson() {
        ChatMessage message = UserMessage.from("hello");
        String[] storedJson = new String[1];
        doAnswer(invocation -> {
            storedJson[0] = invocation.getArgument(1);
            return null;
        }).when(valueOperations).set(eq("chat:memory:session-1"), any(), eq(Duration.ofDays(7)));
        when(valueOperations.get("chat:memory:session-1")).thenAnswer(invocation -> storedJson[0]);

        store.updateMessages("session-1", List.of(message));
        List<ChatMessage> result = store.getMessages("session-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(message.type());
    }

    @Test
    void deleteMessages_removesKey() {
        store.deleteMessages("session-1");

        verify(redisTemplate).delete("chat:memory:session-1");
    }
}
```

- [ ] **Step 3: Run test to verify it fails to compile (class doesn't exist yet)**

Run: `./mvnw -B test -Dtest=RedisChatMemoryStoreTest`
Expected: FAIL — compile error, `RedisChatMemoryStore` not found.

- [ ] **Step 4: Write `RedisChatMemoryStore`**

`src/main/java/com/anvil/ai/memory/RedisChatMemoryStore.java`:

```java
package com.anvil.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate,
                                 @Value("${anvil.chat-memory.ttl-days:7}") long ttlDays) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofDays(ttlDays);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        return json == null ? Collections.emptyList() : ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(key(memoryId), json, ttl);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
```

Note: Redis connection failures are allowed to surface as exceptions (no try/catch fallback to in-memory) — masking a persistence failure would defeat the entire point of this class, per the design doc's error-handling notes.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -B test -Dtest=RedisChatMemoryStoreTest`
Expected: `BUILD SUCCESS`, all 3 tests pass.

---

### Task 7: Persistent memory wiring + restart-survival integration test (Phase 4b)

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/anvil/ai/Assistant.java`
- Modify: `src/main/java/com/anvil/web/dto/ChatRequest.java`
- Modify: `src/main/java/com/anvil/web/ChatController.java`
- Modify: `src/test/java/com/anvil/web/ChatControllerTest.java`
- Create: `src/main/java/com/anvil/config/AiConfig.java` (add `ChatMemoryProvider` bean — modify existing file)
- Test: `src/test/java/com/anvil/ai/memory/RedisChatMemoryStoreIntegrationTest.java`
- Modify: `pom.xml` (add `com.redis:testcontainers-redis` test dependency)

**Interfaces:**
- Consumes: `RedisChatMemoryStore` (Task 6).
- Produces: `ChatMemoryProvider` bean; `Assistant.chat(String sessionId, String message): String`; `POST /api/chat` now requires `{"sessionId": "...", "message": "..."}`.

- [ ] **Step 1: Add Redis Testcontainers test dependency to `pom.xml`**

Version is managed by `spring-boot-starter-parent` (2.2.4 at the time this plan was written):

```xml
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Add Redis connection config to `application.yml`**

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

anvil:
  chat-memory:
    ttl-days: 7
```

- [ ] **Step 3: Add the `ChatMemoryProvider` bean to `AiConfig`**

Add this import and bean method to the existing `src/main/java/com/anvil/config/AiConfig.java` (from Task 4):

```java
import com.anvil.ai.memory.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
```

```java
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }
```

- [ ] **Step 4: Update `Assistant` to take a session ID**

Replace the contents of `src/main/java/com/anvil/ai/Assistant.java`:

```java
package com.anvil.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {

    @SystemMessage("You are Anvil, a helpful assistant. Answer questions clearly and concisely. " +
            "If retrieved context is provided, ground your answer in it.")
    String chat(@MemoryId String sessionId, @UserMessage String message);
}
```

- [ ] **Step 5: Update `ChatRequest` to carry a session ID**

Replace the contents of `src/main/java/com/anvil/web/dto/ChatRequest.java`:

```java
package com.anvil.web.dto;

public record ChatRequest(String sessionId, String message) {
}
```

- [ ] **Step 6: Update `ChatController`**

In `src/main/java/com/anvil/web/ChatController.java`, replace the `chat` method body:

```java
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = assistant.chat(request.sessionId(), request.message());
        return new ChatResponse(answer);
    }
```

- [ ] **Step 7: Update `ChatControllerTest` for the new method signature**

In `src/test/java/com/anvil/web/ChatControllerTest.java`, replace the test body:

```java
    @Test
    void chat_returnsAssistantReply() throws Exception {
        when(assistant.chat("session-1", "hello")).thenReturn("hi there");

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatRequest("session-1", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("hi there"));
    }
```

Run: `./mvnw -B test -Dtest=ChatControllerTest`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Write the restart-survival integration test**

This is the design doc's most important verification: a multi-turn conversation must survive a simulated app restart. It's deliberately scoped to just `RedisChatMemoryStore` + real Redis (via Testcontainers), not the full Spring context, so it isn't coupled to unrelated Postgres/Ollama bean wiring.

`src/test/java/com/anvil/ai/memory/RedisChatMemoryStoreIntegrationTest.java`:

```java
package com.anvil.ai.memory;

import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisChatMemoryStoreIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @Test
    void conversationSurvivesNewStoreInstance_simulatingAppRestart() {
        RedisStandaloneConfiguration redisConfig =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        String sessionId = "restart-test-session";

        RedisChatMemoryStore storeBeforeRestart = new RedisChatMemoryStore(redisTemplate, 7);
        ChatMemory memoryBeforeRestart = MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(20)
                .chatMemoryStore(storeBeforeRestart)
                .build();
        memoryBeforeRestart.add(UserMessage.from("My name is Arhan."));
        memoryBeforeRestart.add(AiMessage.from("Nice to meet you, Arhan."));

        // Fresh store + fresh ChatMemory instance, same Redis backend: simulates the app restarting.
        RedisChatMemoryStore storeAfterRestart = new RedisChatMemoryStore(redisTemplate, 7);
        ChatMemory memoryAfterRestart = MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(20)
                .chatMemoryStore(storeAfterRestart)
                .build();

        assertThat(memoryAfterRestart.messages()).hasSize(2);
        assertThat(((UserMessage) memoryAfterRestart.messages().get(0)).singleText()).contains("Arhan");

        connectionFactory.destroy();
    }
}
```

- [ ] **Step 9: Run and verify**

Run: `./mvnw -B test -Dtest=RedisChatMemoryStoreIntegrationTest`
Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Manual end-to-end verification (the literal "Done when" from the README)**

With Postgres and Redis running (standalone containers are fine for now — Compose lands in Task 9):

```bash
docker run -d --name anvil-postgres -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=anvil pgvector/pgvector:pg16
docker run -d --name anvil-redis -p 6379:6379 redis:7-alpine
./mvnw spring-boot:run
```

```bash
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"sessionId\":\"demo\",\"message\":\"My favorite color is teal. Remember that.\"}"
curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"sessionId\":\"demo\",\"message\":\"What is my favorite color?\"}"
```

Stop the app (Ctrl+C), restart it (`./mvnw spring-boot:run`), then repeat the second curl with the same `sessionId`. Expected: the model still knows the favorite color is teal, proving memory survived the restart.

---

### Task 8: Dockerfile (Phase 5a)

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Produces: a buildable image exposing port 8080, built via `docker build -t anvil .`.

- [ ] **Step 1: Write `.dockerignore`**

```
target/
.git/
*.md
docs/
```

- [ ] **Step 2: Write `Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /app/target/anvil-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Build and verify**

Run: `docker build -t anvil .`
Expected: image builds successfully (this will take a few minutes the first time while Maven downloads dependencies inside the build stage).

---

### Task 9: Docker Compose — full stack (Phase 5b)

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: `Dockerfile` (Task 8), all `application.yml` env var names already defined in Tasks 3/4/7 (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `PGVECTOR_*`, `REDIS_HOST`, `REDIS_PORT`).
- Produces: a one-command full stack via `docker compose up`.

- [ ] **Step 1: Write `docker-compose.yml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: anvil
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redisdata:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollamadata:/root/.ollama
    healthcheck:
      test: ["CMD-SHELL", "ollama list || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 10

  ollama-init:
    image: ollama/ollama:latest
    depends_on:
      ollama:
        condition: service_healthy
    entrypoint: ["sh", "-c", "OLLAMA_HOST=ollama:11434 ollama pull llama3.2:3b"]

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      OLLAMA_BASE_URL: http://ollama:11434
      OLLAMA_MODEL: llama3.2:3b
      PGVECTOR_HOST: postgres
      PGVECTOR_PORT: 5432
      PGVECTOR_DATABASE: anvil
      PGVECTOR_USER: postgres
      PGVECTOR_PASSWORD: postgres
      REDIS_HOST: redis
      REDIS_PORT: 6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      ollama-init:
        condition: service_completed_successfully

volumes:
  pgdata:
  redisdata:
  ollamadata:
```

- [ ] **Step 2: Bring up the stack and verify**

Run: `docker compose up -d --build`
Expected: all services start; `docker compose ps` shows `postgres`, `redis`, `ollama` healthy, `ollama-init` exited(0), `app` running.

Run: `curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"sessionId\":\"compose-test\",\"message\":\"hello\"}"`
Expected: a real model response. This is the README's Phase 5 "Done when" criterion — a clean-machine `docker compose up` with no manual setup beyond what's already in the compose file.

Run: `docker compose down` when done verifying (keep `-v` off so the named volumes — and the pulled model — persist for next time).

---

### Task 10: Observability — Prometheus metrics exposure (Phase 6a)

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `/actuator/prometheus` endpoint.

- [ ] **Step 1: Add the Micrometer Prometheus registry to `pom.xml`**

```xml
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

- [ ] **Step 2: Expose the prometheus endpoint in `application.yml`**

Change the existing `management.endpoints.web.exposure.include` line from `health` to:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```

- [ ] **Step 3: Verify**

Run: `./mvnw -B spring-boot:run` (with Postgres/Redis/Ollama still up from Task 9, or standalone containers from earlier tasks)
Run in another terminal: `curl -s http://localhost:8080/actuator/prometheus | head -20`
Expected: Prometheus-format metrics text output (lines like `jvm_memory_used_bytes{...}`). Stop the app once confirmed.

---

### Task 11: Observability — Prometheus + Grafana in Compose (Phase 6b)

**Files:**
- Create: `prometheus/prometheus.yml`
- Create: `grafana/provisioning/datasources/datasource.yml`
- Create: `grafana/provisioning/dashboards/dashboard.yml`
- Create: `grafana/dashboards/anvil-dashboard.json`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `/actuator/prometheus` (Task 10).
- Produces: Grafana reachable at `http://localhost:3000` with a pre-provisioned datasource and dashboard.

- [ ] **Step 1: Write the Prometheus scrape config**

`prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: anvil
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["app:8080"]
```

- [ ] **Step 2: Write the Grafana datasource provisioning file**

`grafana/provisioning/datasources/datasource.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

- [ ] **Step 3: Write the Grafana dashboard provisioning file**

`grafana/provisioning/dashboards/dashboard.yml`:

```yaml
apiVersion: 1

providers:
  - name: Anvil
    folder: ""
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 4: Write the starter dashboard JSON**

`grafana/dashboards/anvil-dashboard.json`:

```json
{
  "title": "Anvil",
  "uid": "anvil-starter",
  "timezone": "browser",
  "schemaVersion": 39,
  "panels": [
    {
      "id": 1,
      "title": "JVM Memory Used",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [
        {
          "expr": "sum(jvm_memory_used_bytes{application=\"anvil\"}) by (area)",
          "legendFormat": "{{area}}"
        }
      ]
    },
    {
      "id": 2,
      "title": "HTTP Request Rate",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"anvil\"}[1m])) by (uri)",
          "legendFormat": "{{uri}}"
        }
      ]
    }
  ]
}
```

- [ ] **Step 5: Add `prometheus` and `grafana` services to `docker-compose.yml`**

Add to the `services:` block:

```yaml
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    depends_on:
      - app

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
```

Add to the `volumes:` block: nothing new needed (Grafana state isn't persisted across restarts for this starter — acceptable for a demo/dev stack).

- [ ] **Step 6: Verify**

Run: `docker compose up -d --build`
Open `http://localhost:3000` in a browser (or `curl -s http://localhost:3000/api/health`).
Expected: Grafana loads with no login required (anonymous viewer), the "Anvil" dashboard is already present in the dashboard list, and its panels show live data once a few requests have hit the app (`curl` `/api/chat` a couple of times to generate traffic). Zero manual datasource/dashboard setup — this is the Phase 6 "Done when" criterion.

---

### Task 12: CI workflow (Phase 7a)

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: a GitHub Actions workflow running `./mvnw verify` on push/PR.

- [ ] **Step 1: Write `.github/workflows/ci.yml`**

Note on approach: the design doc called for "Postgres and Redis as GitHub Actions service containers." Tasks 5 and 7 ended up using Testcontainers instead (self-contained, identical behavior locally and in CI, no manual port/env wiring) — GitHub's `ubuntu-latest` runners have Docker preinstalled, so Testcontainers works there with zero extra configuration. This still satisfies the underlying goal (real Postgres/Redis in CI, not mocks) with less duplicated config than hand-wiring `services:` blocks that the tests wouldn't even use.

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Verify
        run: ./mvnw -B verify
```

- [ ] **Step 2: Verify locally (CI itself can't be tested without pushing, but the exact command it runs can be)**

Run: `./mvnw -B verify`
Expected: `BUILD SUCCESS`, full test suite passes including the Testcontainers-backed integration tests (Docker must be running locally, which it is).

---

### Task 13: `.env.example`, `.gitignore`, `LICENSE` (Phase 7b)

**Files:**
- Create: `.env.example`
- Create: `.gitignore`
- Create: `LICENSE`

**Interfaces:**
- Produces: documentation of every env var the app reads, a standard Java/Maven gitignore, and an MIT license file.

- [ ] **Step 1: Write `.env.example`**

```bash
# Ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2:3b

# Postgres / pgvector
PGVECTOR_HOST=localhost
PGVECTOR_PORT=5432
PGVECTOR_DATABASE=anvil
PGVECTOR_USER=postgres
PGVECTOR_PASSWORD=postgres

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
```

- [ ] **Step 2: Write `.gitignore`**

```gitignore
target/
.mvn/wrapper/maven-wrapper.jar
*.class
*.log
.idea/
*.iml
.vscode/
.DS_Store
.env
```

- [ ] **Step 3: Write `LICENSE`**

MIT License, copyright line using the current year and no named individual (standard for an open starter template):

```
MIT License

Copyright (c) 2026 Anvil contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 4: Verify**

Run: `git status --porcelain` (once git is initialized, later) would show these as untracked files; for now just confirm via `ls -la` that all three files exist at the project root.

---

### Task 14: Rewrite README as a five-minute quickstart (Phase 7c)

**Files:**
- Modify: `readme.md`

**Interfaces:**
- None — this is documentation only.

- [ ] **Step 1: Replace `readme.md` with a quickstart-first version**

Keep the "Stack" and "Differentiator" sections (still accurate), but lead with a copy-pasteable quickstart instead of the phase-by-phase build spec (the build spec did its job; once Anvil is built, a newcomer needs "how do I run this," not "how was this built"):

```markdown
# Anvil

A production-shaped Spring Boot starter for LLM and RAG applications — the "create-t3-app for Java AI."

## Quickstart

```bash
git clone <repo-url> anvil && cd anvil
cp .env.example .env
docker compose up -d --build
```

Wait for `ollama-init` to finish pulling the model (`docker compose logs -f ollama-init`), then:

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo","message":"hello"}'
```

Ingest a document and ask about it:

```bash
curl -s -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{"text":"Anvil uses Redis for persistent chat memory."}'

curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo","message":"What does Anvil use for chat memory?"}'
```

Metrics dashboard: `http://localhost:3000` (Grafana, no login).

No API keys required — the default chat model is Ollama (`llama3.2:3b`), pulled automatically on first `docker compose up`.

## Stack

- Spring Boot 3.5, Java 21
- LangChain4j via its official Spring Boot starter (`@AiService` auto-configuration)
- Postgres + pgvector for persistent embeddings
- A local, in-process embedding model (all-MiniLM-L6-v2 via ONNX) so RAG works without a second API key
- Redis, used specifically for persistent chat memory
- Actuator + Micrometer + Prometheus + Grafana, pre-wired with a starter dashboard
- Docker Compose for one-command local spin-up
- GitHub Actions CI with real Postgres/Redis via Testcontainers

## The Differentiator

Every LangChain4j/Spring tutorial out there uses the default in-memory `ChatMemoryStore`, so conversation history vanishes on restart. The one genuinely novel piece of this project is `RedisChatMemoryStore` — a custom implementation of LangChain4j's `ChatMemoryStore` interface backed by Spring Data Redis. That's the detail that makes this look like a real starter kit instead of a copy-pasted blog tutorial.

## Development

Requires Java 21 and Docker. No system Maven needed — use `./mvnw`.

```bash
./mvnw test          # unit + Testcontainers integration tests
./mvnw spring-boot:run   # run against standalone Postgres/Redis/Ollama containers, see docs/superpowers/plans/2026-07-01-anvil-implementation.md Task 3/7 for setup
```

## License

MIT
```

- [ ] **Step 2: Verify by following it literally**

Run through the Quickstart section's commands against the actual repo (this overlaps with Task 9's verification — if Task 9 passed, this section is already proven accurate; just confirm the README text matches the real command output/ports).
