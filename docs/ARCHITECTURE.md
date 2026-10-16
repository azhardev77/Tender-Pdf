# Architecture Documentation: Tender PDF Ingestion Engine

## Overview

The Tender PDF Ingestion Engine is a Spring Boot application built with Java 21 that ingests tender documents, performs in-memory byte[] load + page-wise text extraction, and extracts structured metadata using OpenAI-compatible LLM endpoints with json_object mode + Jakarta validation, supported by a deterministic heuristic fallback parser.

Data persistence and idempotency are handled through PostgreSQL 17 using a combination of SHA-256 hashing, unique database constraints, and transactional row locking with automated stale-lock recovery.

```
+-----------------------------------------------------------------------------------+
|                        TenderIngestionController                                  |
|  POST /api/v1/tenders/ingest (multipart/form-data)                                |
|  GET  /api/v1/tenders/{documentHash}                                              |
|  GET  /api/v1/tenders/search?authority={keyword}                                  |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                           TenderOrchestratorService                               |
+-----------------------------------------------------------------------------------+
         |                                                 |
         v                                                 v
+-------------------------------+             +-------------------------------------+
|  PdfStreamExtractorService    |             |      DatabaseCacheLockService       |
|  - SHA-256 computation        |             |  - Fast cache check (COMPLETED)     |
|  - In-memory byte[] load +    |             |  - Advisory lock + row lock         |
|    page-wise text extraction  |             |  - 120s Stale-lock recovery        |
|  - Noise & whitespace strip   |             |  - Status: LOCKED_PROCESSING        |
+-------------------------------+             +-------------------------------------+
         |                                                         |
         | (extracted text)                                        v
         +------------------------------------------->+-------------------------+
                                                      | PostgreSQL 17           |
                                                      | tender_document_records |
                                                      +-------------------------+
                                                                   |
                                                                   v
+-----------------------------------------------------------------------------------+
|                         StructuredLlmPipelineService                              |
|  - Truncates raw text at 20,000 characters                                        |
|  - Primary path: RestClient -> /chat/completions (json_object mode)               |
|  - Validation: Jakarta Validator checks TenderMetadataSchema                      |
|  - Fallback path: Regex pattern matching with explicit HEURISTIC prefix           |
+-----------------------------------------------------------------------------------+
                                   |
                                   v
+-----------------------------------------------------------------------------------+
|                   DatabaseCacheLockService.completeRecord()                       |
|  - Updates record status to COMPLETED                                             |
|  - Persists extracted JSONB metadata & duration                                   |
+-----------------------------------------------------------------------------------+
```

---

## Component Architecture

### 1. Ingestion & Text Extraction (`PdfStreamExtractorService`)
- Computes a deterministic SHA-256 checksum across the raw file byte array via `java.security.MessageDigest`.
- Performs in-memory byte[] load + page-wise text extraction using Apache PDFBox 3.0.2 (`Loader.loadPDF(byte[])` and `PDFTextStripper`).
- Applies regular expressions to remove repeating header/footer artifacts (`PAGE_NOISE_PATTERN`) and condenses multi-line whitespace.
- Assembles extracted page text into a unified string separated by page delineation markers (`--- PAGE <N> ---`).

### 2. Cache & Concurrency Management (`DatabaseCacheLockService`)
- **Fast Cache Path**: Queries `TenderDocumentRepository` for existing records matching the document hash in `COMPLETED` status. If present, returns immediately without re-extracting text or invoking the LLM.
- **Idempotency via SHA-256 + Unique Index**: The database constraint `idx_tender_doc_hash` prevents duplicate document ingestion. Concurrent insert races are caught via `DataIntegrityViolationException` and mapped cleanly to `ConcurrentProcessingException`.
- **Locking & Stale-Lock Recovery**:
  1. Requests a PostgreSQL transaction-level advisory lock using `pg_try_advisory_xact_lock(hashtext(:lockKey))`.
  2. Queries the record using pessimistic write locking (`LockModeType.PESSIMISTIC_WRITE`).
  3. If status is `LOCKED_PROCESSING` and `updatedAt` is older than `app.lock.stale-timeout-seconds` (default: 120s), recovers the lock, resets timestamps, and allows processing to proceed.
  4. If status is `LOCKED_PROCESSING` and active (elapsed < 120s), throws `ConcurrentProcessingException`.
  5. If status is `FAILED`, resets the existing record to `LOCKED_PROCESSING`.
  6. If no record exists, inserts a new record with status `LOCKED_PROCESSING`.
- Executes within an isolated transaction boundary (`Propagation.REQUIRES_NEW`).

### 3. Structured Metadata Pipeline (`StructuredLlmPipelineService`)
- **Primary LLM Pipeline**:
  - Activated when `app.llm.api-key` is non-empty.
  - Sends a POST request via Spring `RestClient` to `${app.llm.base-url}/chat/completions`.
  - Configures `response_format: {"type": "json_object"}` with model `${app.llm.model}` (default: `gpt-4o-mini`) and temperature `0.1`.
  - Enforces schema validity through Jakarta Bean Validation (`Validator.validate()`) against `TenderMetadataSchema`.
- **Heuristic Fallback Pipeline**:
  - `heuristic-fallback-enabled` defaults to `false` in `application.yml`. If disabled and `LLM_API_KEY` is missing or fails, throws `DocumentProcessingException`.
  - When explicitly enabled (`true`), regex patterns extract tender reference numbers, authorities, contract values, EMD amounts, and deadlines.
  - Unmatched attributes receive explicit attribution prefixes (e.g. `HEURISTIC-TND-...` and `HEURISTIC: National Procurement Authority`).

### 4. REST Controller & Exception Handling
- `TenderIngestionController` exposes `/api/v1/tenders` for document ingestion, retrieval by hash, and search by authority.
- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to standardized `ApiErrorResponse` payloads:
  - `ConcurrentProcessingException` -> `409 Conflict`
  - `DocumentProcessingException` & `IllegalArgumentException` -> `400 Bad Request`
  - `MaxUploadSizeExceededException` -> `413 Payload Too Large`
  - `Exception` -> `500 Internal Server Error`

### 5. Concurrency Model (`AsyncVirtualThreadConfig`)
- Configured for Java 21 virtual threads via `spring.threads.virtual.enabled: true`.
- Embedded Tomcat protocol handler uses `Executors.newVirtualThreadPerTaskExecutor()`.
- Application task executor (`applicationTaskExecutor`) uses virtual threads to handle blocking I/O (LLM HTTP calls, database JDBC operations).

---

## API Specification

### Implemented Endpoints (`src/main/java`)

#### 1. Ingest Tender Document
- **Method**: `POST`
- **Path**: `/api/v1/tenders/ingest`
- **Content-Type**: `multipart/form-data`
- **Parameters**: `file` (MultipartFile, max 50MB, required)
- **Responses**:
  - `200 OK`: Returns `TenderExtractionResponse` with record ID, document hash, metadata, and cache status.
  - `400 Bad Request`: Empty file, non-PDF file, or schema validation failure.
  - `409 Conflict`: Document is actively locked and being processed by another thread/worker.
  - `413 Payload Too Large`: Upload exceeds configured 50MB limit.

#### 2. Get Tender by Hash
- **Method**: `GET`
- **Path**: `/api/v1/tenders/{documentHash}`
- **Parameters**: `documentHash` (path variable, SHA-256 hexadecimal string)
- **Responses**:
  - `200 OK`: Returns existing `TenderExtractionResponse`.
  - `404 Not Found`: No record found for the provided hash.

#### 3. Search Tenders by Issuing Authority
- **Method**: `GET`
- **Path**: `/api/v1/tenders/search`
- **Parameters**: `authority` (query parameter, optional)
- **Responses**:
  - `200 OK`: Returns JSON array of completed `TenderDocumentRecord` matching authority substring.

#### 4. Actuator Endpoints (Configured via `application.yml`)
- `GET /actuator/health` — Application health check.
- `GET /actuator/info` — Application build and environment info.
- `GET /actuator/metrics` — JVM and request metrics.

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
- **20k-char LLM truncation**: Text supplied to the LLM is truncated to the first 20,000 characters (`rawText.substring(0, 20000)`). Clauses and values beyond the first 20,000 characters are not parsed by the LLM.
- **No OCR**: Text extraction relies entirely on PDFBox `PDFTextStripper`. Scanned PDFs or image-only pages contain no extractable text stream and produce empty extraction results.
- **In-memory byte[] load**: Raw document bytes are held entirely in heap memory during extraction; large concurrent document uploads scale memory consumption linearly with file size.
- **Stale-lock timeout**: Stale locks held by crashed workers are recovered after `app.lock.stale-timeout-seconds` (default: 120s) based on `updated_at`, but active workers killed within the 120s window will block re-ingestion of the identical document hash until the timeout elapses.
- **Multipart size limit**: File uploads are capped at 50MB per file and 50MB per request (`spring.servlet.multipart.max-file-size: 50MB`).

---

## Database Schema

Database engine: **PostgreSQL 17** (configured via `docker-compose.yml` with `postgres:17-alpine`).

### Table: `tender_document_records`

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | PRIMARY KEY | Unique document record identifier |
| `document_hash` | `VARCHAR(64)` | NOT NULL, UNIQUE | SHA-256 hexadecimal hash of document bytes |
| `file_name` | `VARCHAR(255)` | | Original upload file name |
| `page_count` | `INTEGER` | | Total number of pages reported by PDFBox |
| `status` | `VARCHAR(32)` | NOT NULL | Processing state: `LOCKED_PROCESSING`, `COMPLETED`, `FAILED` |
| `extracted_metadata` | `JSONB` | | Serialized `TenderMetadataSchema` JSON object |
| `processing_duration_ms` | `BIGINT` | | Total pipeline execution duration in milliseconds |
| `version` | `BIGINT` | | Optimistic locking version field |
| `created_at` | `TIMESTAMP` | NOT NULL | Creation timestamp (set once on insert) |
| `updated_at` | `TIMESTAMP` | NOT NULL | Last update timestamp |

### Indexes
- `idx_tender_doc_hash` ON `document_hash` (UNIQUE)
- `idx_tender_doc_status` ON `status`
