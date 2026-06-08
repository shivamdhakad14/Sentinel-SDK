# 🛡️ Sentinel-SDK — Autonomous QA & Self-Healing Agent

> **The QA engineer that never sleeps.** Sentinel autonomously explores your API, writes tests, detects schema drift, and submits self-healing PRs — all without a human in the loop.

[![CI/CD](https://github.com/yourorg/sentinel-sdk/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/yourorg/sentinel-sdk/actions)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-0.8.1-blue)](https://spring.io/projects/spring-ai)

---

## What is Sentinel?

Sentinel is a **standalone Spring Boot service** that acts as an autonomous background employee for your API quality assurance. Point it at any Spring Boot (or any OpenAPI-compliant) application and it will:

1. **Discover** all endpoints via `/v3/api-docs`
2. **Reason** about dependencies (e.g., "to test POST /orders, I need a valid productId first")
3. **Autonomously call** prerequisite endpoints to gather real data
4. **Execute** the target endpoint with a constructed valid payload
5. **Self-correct** on 4xx errors, reading error messages and adjusting the payload
6. **Generate** JUnit 5 tests and Postman collections from successful runs
7. **Detect** schema drift by comparing current spec to previous snapshot
8. **Submit a GitHub PR** with auto-healed tests when drift is detected

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        SENTINEL-SDK                             │
│                                                                 │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────────────┐  │
│  │  Discovery  │───▶│  Agent Loop  │───▶│  Report Generator  │  │
│  │  (OpenAPI)  │    │  (Spring AI) │    │  (JUnit / Postman) │  │
│  └─────────────┘    └──────┬───────┘    └────────────────────┘  │
│                            │                      │             │
│                     ┌──────▼───────┐   ┌──────────▼──────────┐  │
│                     │ Schema Drift │   │   GitHub PR Service  │  │
│                     │  Detector   │──▶│  (Self-Healing PRs)  │  │
│                     └─────────────┘   └─────────────────────┘  │
│                                                                 │
│  REST API (/api/v1/*)  ·  WebSocket (/ws)  ·  Actuator         │
└─────────────────────────────────────────────────────────────────┘
           │ HTTP                            │ HTTP
           ▼                                ▼
   ┌───────────────┐                 ┌─────────────┐
   │  Your Spring  │                 │   GitHub    │
   │  Boot App     │                 │   API       │
   └───────────────┘                 └─────────────┘
```

### The Think-Act-Observe (TAO) Loop

Each endpoint goes through this loop (up to `sentinel.agent.max-retries` times):

```
THINK  → LLM analyzes the endpoint schema and plans prerequisites
  │
ACT    → Calls GET endpoints to collect real IDs (products, users, etc.)
  │
ACT    → Constructs and executes the target endpoint
  │
OBSERVE→ 2xx?  ──YES──▶  Generate test  ──▶  DONE ✅
          │
          NO
          │
         4xx?  ──▶  Read error body, self-correct payload, retry
          │
         Same payload + 400 = SCHEMA_DRIFT detected ⚠️
```

---

## Quick Start

### Prerequisites

- Java 21+
- MySQL 8.0+ (or Docker)
- OpenAI API key (GPT-4o recommended)

### 1. Clone & Configure

```bash
git clone https://github.com/yourorg/sentinel-sdk.git
cd sentinel-sdk

cp src/main/resources/application.properties src/main/resources/application-local.properties
# Edit application-local.properties:
#   spring.datasource.password=your_mysql_password
#   spring.ai.openai.api-key=sk-...
#   sentinel.target.base-url=http://localhost:8080
```

### 2. Start with Docker Compose

```bash
export OPENAI_API_KEY=sk-your-key
docker-compose up -d
```

Sentinel dashboard: http://localhost:8090  
Swagger UI: http://localhost:8090/swagger-ui.html

### 3. Run Your First Scan

```bash
curl -X POST http://localhost:8090/api/v1/scans \
  -H "Content-Type: application/json" \
  -d '{"targetBaseUrl": "http://localhost:8080", "targetName": "My API"}'
```

Watch the agent work in real-time via WebSocket at `ws://localhost:8090/ws`.

---

## API Reference

### Scans

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/scans` | Start a new autonomous scan |
| `GET`  | `/api/v1/scans` | List all scan sessions |
| `GET`  | `/api/v1/scans/{id}` | Get session details |
| `GET`  | `/api/v1/scans/{id}/attempts` | Full agent attempt log |
| `GET`  | `/api/v1/scans/stats` | Aggregate statistics |

### Tests

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/v1/tests` | List generated tests |
| `GET`  | `/api/v1/tests/{id}/download` | Download test file |
| `GET`  | `/api/v1/tests/self-healed` | Tests that were auto-healed |

### Schema Drift

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/v1/drift` | All detected drift events |
| `GET`  | `/api/v1/drift/unhealed` | High/critical drift needing attention |
| `GET`  | `/api/v1/drift/stats` | Drift summary |

### Scheduler

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/scheduler/targets` | Register a target for periodic scanning |
| `GET`  | `/api/v1/scheduler/targets` | List registered targets |
| `DELETE`| `/api/v1/scheduler/targets?baseUrl=...` | Unregister a target |

---

## Enterprise: Embed as SDK (.jar)

Add Sentinel to your existing Spring Boot project:

```xml
<dependency>
    <groupId>com.sentinel</groupId>
    <artifactId>sentinel-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Configure in `application.properties`:

```properties
sentinel.sdk.enabled=true
sentinel.sdk.target-url=http://localhost:8080
sentinel.sdk.target-name=My API
sentinel.sdk.auto-scan-on-startup=true
sentinel.sdk.fail-on-critical-drift=true   # Breaks CI build on critical drift
```

Use programmatically:

```java
@Autowired SentinelSdk sentinel;

// One-off scan
ScanSession result = sentinel.scan();

// Async
sentinel.scanAsync().thenAccept(s -> 
    log.info("Scan complete: {}/{} passed", 
        s.getEndpointsPassed(), s.getTotalEndpointsDiscovered())
);

// Discovery only
DiscoveryResult discovery = sentinel.discover();
log.info("Found {} endpoints", discovery.endpoints().size());
```

---

## Self-Healing Regression

When a developer renames a field (e.g., `user_id` → `customer_uuid`):

1. Sentinel detects the schema change via OpenAPI diff
2. AI analysis identifies which existing tests reference the old field name
3. GPT-4 rewrites the affected test code
4. A GitHub PR is automatically submitted with the healed tests
5. PR description includes full drift report and review checklist

Configure GitHub integration:

```properties
sentinel.github.token=ghp_...
sentinel.github.owner=your-org
sentinel.github.repo=your-repo
sentinel.github.base-branch=main
```

---

## Deployment

### Render.com (one-click)

1. Fork this repo
2. Create a new Web Service on Render, point to your fork
3. Set environment variables: `OPENAI_API_KEY`, `GITHUB_TOKEN`, `GITHUB_OWNER`, `GITHUB_REPO`
4. Render uses `render.yaml` for automatic configuration

### Docker

```bash
docker build -t sentinel-sdk .
docker run -p 8090:8090 \
  -e OPENAI_API_KEY=sk-... \
  -e DATABASE_URL=jdbc:mysql://host:3306/sentinel_db \
  -e DATABASE_USERNAME=sentinel \
  -e DATABASE_PASSWORD=secret \
  sentinel-sdk
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `sentinel.agent.max-retries` | `5` | Max self-correction attempts per endpoint |
| `sentinel.agent.concurrent-agents` | `4` | Parallel endpoint testing threads |
| `sentinel.agent.think-loop-max-iterations` | `15` | Max TAO iterations per endpoint |
| `sentinel.agent.request-timeout-seconds` | `30` | HTTP timeout for target API calls |
| `sentinel.scheduler.enabled` | `true` | Enable periodic background scans |
| `sentinel.scheduler.interval-ms` | `3600000` | Scan interval (1 hour default) |
| `sentinel.report.output-dir` | `./generated-tests` | Where to write generated test files |
| `sentinel.github.base-branch` | `main` | Base branch for self-healing PRs |

---

## Project Structure

```
sentinel-sdk/
├── src/main/java/com/sentinel/
│   ├── SentinelSdkApplication.java       # Entry point
│   ├── agent/
│   │   ├── SentinelAgent.java            # Think-Act-Observe loop
│   │   └── SchemaDriftDetector.java      # OpenAPI diff engine
│   ├── config/
│   │   ├── SentinelConfig.java           # RestClient, async pool, CORS
│   │   └── AiFunctionConfig.java         # Spring AI function registration
│   ├── controller/
│   │   ├── Controllers.java              # Scan, Tests, Drift, Dashboard
│   │   └── SchedulerController.java      # Target registration API
│   ├── exception/
│   │   └── GlobalExceptionHandler.java   # Unified error handling
│   ├── model/                            # JPA entities
│   ├── repository/                       # Spring Data repositories
│   ├── report/
│   │   └── TestReportGenerator.java      # JUnit 5 + Postman generation
│   ├── scheduler/
│   │   └── ScheduledRegressionRunner.java # Periodic scan scheduler
│   ├── sdk/
│   │   ├── SentinelAutoConfiguration.java # Spring Boot auto-config
│   │   └── SentinelSdk.java              # Programmatic API facade
│   ├── service/
│   │   ├── OpenApiDiscoveryService.java  # Spec crawling
│   │   ├── ScanOrchestrationService.java # Full scan lifecycle
│   │   └── GitHubPrService.java          # PR submission
│   └── tools/
│       └── AgentTools.java               # Spring AI function tools
├── src/test/java/com/sentinel/
│   ├── agent/                            # Agent unit tests
│   ├── service/                          # Service unit tests
│   ├── controller/                       # MockMvc slice tests
│   └── integration/                      # Full context tests
├── .github/workflows/ci-cd.yml          # GitHub Actions pipeline
├── docker-compose.yml
├── Dockerfile
├── render.yaml
└── pom.xml
```

---

## License

MIT License — see [LICENSE](LICENSE) for details.

Enterprise licensing available for embedded SDK usage. Contact: sentinel@yourorg.com
