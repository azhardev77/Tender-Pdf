# Tender PDF Ingestion Engine

A Spring Boot application (Spring Boot 3.3.4, Java 21) that performs in-memory byte[] load + page-wise text extraction on tender PDF documents and extracts structured metadata using OpenAI-compatible LLM endpoints with json_object mode + Jakarta validation, backed by deterministic heuristic fallback parsing and PostgreSQL 17 persistence.

---

## Prerequisites

- **Java Development Kit (JDK)**: Version 21
- **Apache Maven**: Version 3.9+
- **Docker & Docker Compose**: For running PostgreSQL 17

---

## Environment Variables

The application can be configured using the following environment variables (defined in `src/main/resources/application.yml` and `docker-compose.yml`):

| Variable | Description | Default Value |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL user password | `postgres` |
| `LLM_BASE_URL` | Base URL for OpenAI-compatible LLM API | `https://api.openai.com/v1` |
| `LLM_API_KEY` | Bearer token for LLM API authentication | *(empty)* |
| `LLM_MODEL` | Model identifier for extraction completions | `gpt-4o-mini` |
| `LLM_TIMEOUT_MS` | HTTP connect and read timeout for LLM client (ms) | `30000` |
| `LLM_HEURISTIC_FALLBACK` | Allow fallback to regex parsing when LLM fails/unset | `false` |
| `LOCK_STALE_TIMEOUT_SECONDS` | Ingestion lock expiration threshold in seconds | `120` |

---

## Setup & Running Locally

### 1. Start the PostgreSQL 17 Database

Run the database container using Docker Compose:

```bash
docker compose up -d
```

This launches a PostgreSQL 17 container (`tender-engine-postgres`) on port `5432` with database `tender_engine_db` and user `postgres`.

To verify database readiness:

```bash
docker compose ps
```

### 2. Build and Run the Application

With PostgreSQL running, build and start the Spring Boot service:

```bash
mvn clean spring-boot:run
```

The application starts on port `8080`.

---

## API Endpoints

All endpoints are implemented in `src/main/java/com/azhardev/tenderengine/controller/TenderIngestionController.java`.

### Application Endpoints

| Method | Path | Content-Type | Description |
|---|---|---|---|
| `POST` | `/api/v1/tenders/ingest` | `multipart/form-data` | Ingests PDF file, extracts metadata, persists record |
| `GET` | `/api/v1/tenders/{documentHash}` | `application/json` | Retrieves completed extraction by SHA-256 hash |
| `GET` | `/api/v1/tenders/search` | `application/json` | Searches completed records by issuing authority substring |

### Actuator Endpoints (Configured via `application.yml`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/actuator/health` | Application health check |
| `GET` | `/actuator/info` | Application build and environment info |
| `GET` | `/actuator/metrics` | System and JVM metrics |

---

## cURL Examples

### 1. Ingest Tender PDF Document

```bash
curl -X POST http://localhost:8080/api/v1/tenders/ingest \
  -F "file=@sample_tender.pdf"
```

Expected response (`200 OK`):
```json
{
  "recordId": "42c7be5e-d747-4355-b8b3-2a9691ae4c1b",
  "documentHash": "c1fc279f975ab4934f2dea7c35d202ecaf3884c84cee59ab0362ccdaee3d6d87",
  "fileName": "sample_tender.pdf",
  "pageCount": 2,
  "status": "COMPLETED",
  "cacheHit": false,
  "executionTimeMs": 350,
  "metadata": {
    "tenderReferenceNumber": "NIT-2026-PWD-9001",
    "issuingAuthority": "Municipal Corporation of Delhi",
    "estimatedContractValue": 35000000.00,
    "emdAmount": 700000.00,
    "submissionDeadlineIso": "2026-11-30T17:00:00Z",
    "mandatoryEligibilityCriteria": [
      "Bidder must possess a valid Goods and Services Tax Identification Number (GSTIN) and Permanent Account Number (PAN)."
    ],
    "complianceChecklist": [
      {
        "clauseId": "CC-01",
        "category": "Financial",
        "requirementSummary": "Mandatory Earnest Money Deposit (EMD)",
        "mandatory": true,
        "riskLevel": "HIGH"
      }
    ]
  },
  "processedAt": "2026-10-16T12:00:00Z"
}
```

### 2. Fetch Tender by SHA-256 Hash

```bash
curl -s http://localhost:8080/api/v1/tenders/c1fc279f975ab4934f2dea7c35d202ecaf3884c84cee59ab0362ccdaee3d6d87
```

### 3. Search Tenders by Issuing Authority

```bash
curl -s "http://localhost:8080/api/v1/tenders/search?authority=Municipal"
```

### 4. Check Application Health

```bash
curl -s http://localhost:8080/actuator/health
```

---

## Running Tests

Execute the Maven test phase:

```bash
mvn clean test
```

Integration and unit tests run via `TenderEngineIntegrationTest.java` using an in-memory H2 database with PostgreSQL compatibility mode, verifying:
- In-memory PDF generation and end-to-end ingestion (`POST /api/v1/tenders/ingest`)
- SHA-256 idempotency cache hits (`cacheHit: true`)
- Document lookup by hash (`GET /api/v1/tenders/{documentHash}`)
- Authority search filtering (`GET /api/v1/tenders/search`)
- Stale-lock detection and recovery after 120s timeout
- Active lock conflict handling (`409 Conflict`)
- Request validation for empty file payloads (`400 Bad Request`)

---

## Design Decisions & Trade-offs

- **SHA-256 Idempotency Cache**:
  Uses a cryptographically deterministic SHA-256 hash of raw file bytes to prevent duplicate processing of identical documents.
  Avoids redundant LLM invocations and database duplicates, though identical contents with minor metadata differences produce distinct hashes.

- **PostgreSQL JSONB Storage**:
  Stores variable tender metadata and nested compliance checklists in a PostgreSQL `jsonb` column mapped via Hypersistence Utilities.
  Provides schema flexibility for diverse tender layouts and native queryability without requiring relational schema migrations.

- **Java 21 Virtual Threads**:
  Configures Spring Boot and embedded Tomcat to run on Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.
  Handles blocking I/O operations such as external LLM HTTP calls and JDBC queries with low per-thread memory overhead compared to platform thread pools.

---

## Known Limitations

- **Heuristic fallback behavior**: When `heuristic-fallback-enabled` is `false` (default), missing API keys or LLM call failures throw `DocumentProcessingException`. When enabled, unmatched fields fall back to synthetic values with explicit `HEURISTIC` prefixes, default values (₹25,000,000 estimated value, 2% EMD, +30 days deadline), 4 boilerplate eligibility criteria, and 5 standard compliance checklist items.
- **20k-char LLM truncation**: Text passed to the LLM completion endpoint is truncated to the first 20,000 characters (`rawText.substring(0, 20000)`), ignoring any tender terms, clauses, or values past that limit.
- **No OCR**: Text extraction relies solely on PDFBox `PDFTextStripper` to parse textual PDF streams; image-only and scanned PDFs produce empty text and fail extraction.
- **In-memory byte[] load**: Raw document bytes are loaded into heap memory during processing; memory consumption scales with concurrent upload file sizes.
- **Stale-lock timeout window**: Crashed worker locks are automatically recovered after `app.lock.stale-timeout-seconds` (default: 120s), but active locks killed inside this window block identical document submissions until the timeout elapses.
- **Multipart size limit**: File uploads are limited to 50MB per file and 50MB per request (`spring.servlet.multipart.max-file-size: 50MB`).
