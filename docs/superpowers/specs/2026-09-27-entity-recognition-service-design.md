# Entity Recognition Service Design

## 1. Goal

Build a standalone Python `entity-service` under the repository root. The service recognizes financial entities mentioned in natural-language text, assigns each entity a catalog category, and returns only entities present in the supplied `entity.json` catalog.

The service must maximize recall while preserving a strict no-hallucination boundary. A mention may resolve to one catalog entity, or remain explicitly ambiguous with ranked candidates from different categories.

## 2. Scope

### Included

- Import and validate the existing `entity.json` catalog.
- Normalize the eleven catalog collections into one entity model.
- Exact matching of canonical values, codes, names, and aliases.
- Fuzzy candidate recall using embeddings and PostgreSQL with pgvector.
- Context-aware reranking of candidates with an OpenAI-compatible chat model.
- Explicit resolved and ambiguous response structures.
- Source-text character offsets for every detected mention.
- Index rebuild, entity lookup, health, and recognition APIs.
- Docker integration beside the existing `memory-service`.
- Unit, integration, API-contract, and catalog-import tests.

### Excluded

- Returning entities not present in the catalog.
- Creating or modifying catalog entities through the recognition API.
- Merging code and name records that represent the same financial instrument.
- Making downstream business decisions from recognized entities.
- Training a custom NER or reranking model.

## 3. Source Catalog

The initial source is:

`D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/entity.json`

The production service receives its catalog path through `ENTITY_CATALOG_PATH`; the source file is not copied implicitly across worktrees. Deployment must mount or copy a catalog file into the service container explicitly.

The catalog contains these collections and normalized categories:

| Source collection | Category |
|---|---|
| `fund_codes` | `fund_code` |
| `fund_names` | `fund_name` |
| `exchange_fund_names` | `exchange_fund_name` |
| `company_names` | `company_name` |
| `manager_names` | `manager_name` |
| `track_index_names` | `track_index_name` |
| `track_index_codes` | `track_index_code` |
| `stock_codes` | `stock_code` |
| `stock_names` | `stock_name` |
| `bond_codes` | `bond_code` |
| `bond_names` | `bond_name` |

Every normalized entity has:

- `id`: stable catalog identifier.
- `category`: one of the eleven values above.
- `canonical_value`: source `name` or `code`.
- `aliases`: normalized, non-empty aliases including the canonical value.
- `metadata`: catalog metadata preserved without reinterpretation.
- `search_text`: category-aware text used to generate the embedding.

Malformed entries are rejected during import with a counted error and a structured log. A rebuild fails atomically when any duplicate `id` has conflicting content, when the catalog cannot be parsed, or when no valid entities remain.

## 4. Architecture

`entity-service` is a FastAPI application following the repository's existing Python-service conventions.

```text
HTTP request
  -> request validation
  -> text normalization with offset mapping
  -> exact alias matcher
  -> possible-mention generator for unmatched spans
  -> embedding + pgvector Top-K recall
  -> deterministic score fusion
  -> context-aware LLM reranking for uncertain candidate groups
  -> confidence and ambiguity policy
  -> JSON response validation
```

Primary components:

- `catalog`: streaming catalog reader, validator, and category mapping.
- `normalization`: Unicode/case/whitespace normalization while retaining original character offsets.
- `exact_matcher`: in-memory Aho-Corasick alias index, built from the active database catalog.
- `mention_generator`: produces bounded candidate spans not already covered by exact matches. It uses punctuation and sentence boundaries, finance-oriented code patterns, and configurable Chinese/Latin n-gram limits.
- `embedding_client`: batches OpenAI-compatible embedding requests to the existing Ollama `qwen3-embedding:0.6b` endpoint.
- `repository`: PostgreSQL/pgvector persistence, atomic index versions, exact lookup, and vector search.
- `candidate_fusion`: combines exact-match, lexical, vector, category, boundary, and overlap signals.
- `reranker`: sends only uncertain mention/candidate groups plus local context to the configured chat model. It must choose only candidate IDs supplied by the service.
- `resolver`: applies thresholds and produces `resolved` or `ambiguous` results.
- `api`: FastAPI endpoints and Pydantic request/response models.

The exact matcher supplies deterministic high-recall results for known aliases. Vector retrieval extends recall to abbreviations, minor typos, and semantically close expressions. LLM reranking is not an entity generator; it may only reorder or score catalog candidates.

## 5. Persistence and Indexing

PostgreSQL with pgvector is reused because it is already part of the repository deployment. The embedding dimension is 1024, matching `qwen3-embedding:0.6b`.

### Tables

`entity_catalog_version` records an import:

- `version_id UUID PRIMARY KEY`
- `catalog_version TEXT NOT NULL`
- `source TEXT`
- `source_sha256 TEXT NOT NULL UNIQUE`
- `entity_count INTEGER NOT NULL`
- `status TEXT NOT NULL` constrained to `building`, `active`, `failed`, or `retired`
- `created_at TIMESTAMPTZ NOT NULL`
- `activated_at TIMESTAMPTZ`

`entity_catalog_item` stores normalized entities:

- `version_id UUID NOT NULL`
- `entity_id TEXT NOT NULL`
- `category TEXT NOT NULL`
- `canonical_value TEXT NOT NULL`
- `normalized_value TEXT NOT NULL`
- `aliases JSONB NOT NULL`
- `normalized_aliases JSONB NOT NULL`
- `metadata JSONB NOT NULL`
- `search_text TEXT NOT NULL`
- `embedding VECTOR(1024) NOT NULL`
- primary key `(version_id, entity_id)`

Indexes cover `(version_id, category)`, normalized-value equality, JSONB aliases, and HNSW cosine similarity on `embedding`.

### Atomic rebuild

1. Hash and validate the catalog.
2. Create a `building` version.
3. Normalize records and batch embeddings.
4. Insert items in bounded batches.
5. Verify row count and required categories.
6. Build the in-memory exact index from persisted rows.
7. Activate the new version in one transaction and retire the previous version.
8. Keep the previous version available until the process completes so recognition remains online.

Rebuilding the same SHA-256 is idempotent and returns the existing active version unless `force=true`.

## 6. Recognition Pipeline

### 6.1 Normalization

- Apply Unicode NFKC.
- Fold Latin case for matching.
- Normalize internal whitespace.
- Preserve financial punctuation such as `.`, `-`, `*`, parentheses, and exchange suffixes.
- Maintain a map from normalized characters back to original `[start, end)` offsets.

### 6.2 Exact recognition

The matcher scans all aliases in one pass. It retains overlapping results initially. Boundary rules prevent a numeric code from matching inside a longer code. Longest matches receive a scoring advantage but do not automatically erase a valid nested entity.

All entities associated with the same alias are retained, including entities from different categories.

### 6.3 Fuzzy recall

For uncovered or weakly covered spans, the mention generator creates candidate spans. Each span is embedded and queried against only the active catalog version. Retrieval returns configurable `top_k` candidates, with a default of 20.

A fuzzy candidate is discarded when its vector similarity is below `VECTOR_MIN_SIMILARITY`, default `0.68`. This is a recall gate, not the final confidence threshold.

### 6.4 Fusion and reranking

Deterministic fusion produces a preliminary score from:

- exact canonical match;
- exact alias match;
- normalized edit similarity;
- embedding cosine similarity;
- mention boundary quality;
- code-pattern compatibility;
- contextual category terms such as “基金”, “股票”, “指数”, and “债券”.

Groups with a single dominant candidate may resolve without an LLM call. Candidate groups are sent to the reranker when multiple candidates remain close, when categories differ, or when only fuzzy candidates exist.

The reranker receives the full input, local context, mention span, and a numbered list of candidates. Structured output may contain only supplied entity IDs, ordering, and relevance scores. Unknown IDs or malformed output are ignored, and deterministic ordering remains available as fallback.

### 6.5 Resolution policy

A mention is `resolved` when:

- the top candidate confidence is at least `RESOLVED_MIN_CONFIDENCE`, default `0.86`; and
- the margin over the second candidate is at least `RESOLVED_MIN_MARGIN`, default `0.10`, unless the second candidate represents the same exact entity ID.

Otherwise it is `ambiguous`. Ambiguous candidates are ordered by final confidence and limited by `AMBIGUOUS_MAX_CANDIDATES`, default 5.

No candidate below `RETURN_MIN_CONFIDENCE`, default `0.55`, appears in the response. A suspected span with no validated candidate is placed in `unresolved_mentions` and is never represented as an entity.

Overlapping mentions are both returned when they refer to materially different catalog entities or categories. Exact duplicates of the same `(span, entity_id)` are removed.

## 7. API

### `POST /v1/entities/recognize`

Request:

```json
{
  "text": "帮我比较华夏成长和000001最近的表现",
  "options": {
    "categories": null,
    "top_k": 20,
    "include_unresolved": true
  }
}
```

`text` must contain 1 to 20,000 Unicode characters. `categories` optionally limits retrieval to known category enum values without changing the catalog. `top_k` is constrained to 1 through 100.

### `POST /v1/entities/index/rebuild`

Request:

```json
{
  "catalog_path": null,
  "force": false
}
```

The endpoint is protected by `ENTITY_ADMIN_TOKEN`. A null path uses `ENTITY_CATALOG_PATH`. The API starts a single in-process rebuild job and returns HTTP 202 with a job ID; concurrent rebuild requests return HTTP 409.

### `GET /v1/entities/index/jobs/{job_id}`

Returns rebuild progress, counts, active version, and any terminal error.

### `GET /v1/entities/{entity_id}`

Returns an entity from the active catalog version or HTTP 404.

### `GET /v1/schema/recognition-response`

Returns the generated JSON Schema for `RecognitionResponse`.

### `GET /health`

Returns `ok`, `degraded`, or `unavailable`, along with database, embedding, reranker, and active-index status. Recognition requires the database and an active index. Exact recognition remains available when embedding or reranking is temporarily unavailable; the response diagnostics indicate degraded operation.

## 8. Recognition Response Contract

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://financial-copilot.local/schemas/entity-recognition-response.json",
  "title": "RecognitionResponse",
  "type": "object",
  "additionalProperties": false,
  "required": ["request_id", "text", "status", "entities", "unresolved_mentions", "diagnostics"],
  "properties": {
    "request_id": { "type": "string", "minLength": 1 },
    "text": { "type": "string", "minLength": 1 },
    "status": { "enum": ["completed", "degraded"] },
    "entities": {
      "type": "array",
      "items": { "$ref": "#/$defs/entityMention" }
    },
    "unresolved_mentions": {
      "type": "array",
      "items": { "$ref": "#/$defs/unresolvedMention" }
    },
    "diagnostics": { "$ref": "#/$defs/diagnostics" }
  },
  "$defs": {
    "span": {
      "type": "object",
      "additionalProperties": false,
      "required": ["start", "end"],
      "properties": {
        "start": { "type": "integer", "minimum": 0 },
        "end": { "type": "integer", "minimum": 1 }
      }
    },
    "category": {
      "enum": [
        "fund_code", "fund_name", "exchange_fund_name", "company_name",
        "manager_name", "track_index_name", "track_index_code",
        "stock_code", "stock_name", "bond_code", "bond_name"
      ]
    },
    "catalogEntity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "category", "canonical_value", "metadata"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "category": { "$ref": "#/$defs/category" },
        "canonical_value": { "type": "string", "minLength": 1 },
        "metadata": { "type": "object" }
      }
    },
    "match": {
      "type": "object",
      "additionalProperties": false,
      "required": ["method", "confidence"],
      "properties": {
        "method": {
          "enum": ["exact_canonical", "exact_alias", "vector", "hybrid", "context_rerank"]
        },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    },
    "candidate": {
      "type": "object",
      "additionalProperties": false,
      "required": ["rank", "entity", "match"],
      "properties": {
        "rank": { "type": "integer", "minimum": 1 },
        "entity": { "$ref": "#/$defs/catalogEntity" },
        "match": { "$ref": "#/$defs/match" }
      }
    },
    "ambiguity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["reason", "context_used", "context_needed"],
      "properties": {
        "reason": { "type": "string", "minLength": 1 },
        "context_used": { "type": "string" },
        "context_needed": {
          "type": "array",
          "items": { "type": "string", "minLength": 1 }
        }
      }
    },
    "resolvedMention": {
      "type": "object",
      "additionalProperties": false,
      "required": ["mention", "span", "resolution", "entity", "match"],
      "properties": {
        "mention": { "type": "string", "minLength": 1 },
        "span": { "$ref": "#/$defs/span" },
        "resolution": { "const": "resolved" },
        "entity": { "$ref": "#/$defs/catalogEntity" },
        "match": { "$ref": "#/$defs/match" }
      }
    },
    "ambiguousMention": {
      "type": "object",
      "additionalProperties": false,
      "required": ["mention", "span", "resolution", "ambiguity", "candidates"],
      "properties": {
        "mention": { "type": "string", "minLength": 1 },
        "span": { "$ref": "#/$defs/span" },
        "resolution": { "const": "ambiguous" },
        "ambiguity": { "$ref": "#/$defs/ambiguity" },
        "candidates": {
          "type": "array",
          "minItems": 2,
          "items": { "$ref": "#/$defs/candidate" }
        }
      }
    },
    "entityMention": {
      "oneOf": [
        { "$ref": "#/$defs/resolvedMention" },
        { "$ref": "#/$defs/ambiguousMention" }
      ]
    },
    "unresolvedMention": {
      "type": "object",
      "additionalProperties": false,
      "required": ["mention", "span", "reason"],
      "properties": {
        "mention": { "type": "string", "minLength": 1 },
        "span": { "$ref": "#/$defs/span" },
        "reason": { "enum": ["no_catalog_candidate", "below_confidence_threshold"] }
      }
    },
    "diagnostics": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "catalog_version", "entity_count", "resolved_count",
        "ambiguous_count", "processing_ms", "degraded_dependencies"
      ],
      "properties": {
        "catalog_version": { "type": "string", "minLength": 1 },
        "entity_count": { "type": "integer", "minimum": 0 },
        "resolved_count": { "type": "integer", "minimum": 0 },
        "ambiguous_count": { "type": "integer", "minimum": 0 },
        "processing_ms": { "type": "integer", "minimum": 0 },
        "degraded_dependencies": {
          "type": "array",
          "items": { "enum": ["embedding", "reranker"] },
          "uniqueItems": true
        }
      }
    }
  }
}
```

The service enforces `span.end > span.start` in Pydantic because this cross-field constraint is clearer in application validation than in the portable schema.

## 9. Error Handling

Errors use one envelope:

```json
{
  "error": {
    "code": "INDEX_NOT_READY",
    "message": "No active entity catalog index is available",
    "request_id": "req_01J...",
    "details": {}
  }
}
```

Expected codes include `VALIDATION_ERROR` (422), `ENTITY_NOT_FOUND` (404), `INDEX_NOT_READY` (503), `REBUILD_ALREADY_RUNNING` (409), `CATALOG_INVALID` (422), `DEPENDENCY_UNAVAILABLE` (503), and `INTERNAL_ERROR` (500).

The service never substitutes an unverified LLM-generated entity when dependencies fail. Embedding or reranker failure degrades to exact matching when an active exact index is available.

## 10. Configuration

Environment variables:

- `ENTITY_CATALOG_PATH`
- `ENTITY_ADMIN_TOKEN`
- `DATABASE_URL`
- `EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`
- `EMBEDDING_MODEL=qwen3-embedding:0.6b`
- `EMBEDDING_DIM=1024`
- `LLM_API_KEY`
- `LLM_BASE_URL=https://api.deepseek.com/v1`
- `LLM_MODEL=deepseek-chat`
- `HOST=0.0.0.0`
- `PORT=8800`
- `VECTOR_TOP_K=20`
- `VECTOR_MIN_SIMILARITY=0.68`
- `RETURN_MIN_CONFIDENCE=0.55`
- `RESOLVED_MIN_CONFIDENCE=0.86`
- `RESOLVED_MIN_MARGIN=0.10`
- `AMBIGUOUS_MAX_CANDIDATES=5`

Secrets are never returned by configuration or health endpoints.

## 11. Performance and Reliability

- Exact matching must be linear in input length plus matches.
- Embedding calls are batched during import and bounded by concurrency limits.
- Recognition embeds only generated candidate spans, not every possible substring.
- The service caches normalized span embeddings with an LRU/TTL cache.
- Database pools, HTTP timeouts, maximum request size, and candidate limits are explicit configuration.
- Rebuild state is observable and failed versions never become active.
- Recognition logs request IDs, timing, counts, dependency degradation, and index version without logging secrets.

Target performance for a warm local deployment is p95 below 500 ms for exact-only recognition and p95 below 3 seconds when LLM reranking is required. These are operational targets, not API guarantees.

## 12. Testing Strategy

- Catalog tests validate all category mappings, malformed records, conflicting IDs, aliases, metadata preservation, and idempotent hashes.
- Normalization tests cover Chinese text, Latin case, full-width characters, punctuation, and original offset recovery.
- Exact matcher tests cover duplicate aliases, multiple categories, overlaps, nested names, and numeric-code boundaries.
- Recall tests use a deterministic fake embedding adapter against real repository behavior.
- Fusion and resolver tests cover thresholds, cross-category ambiguity, ordering, overlap retention, and duplicate removal.
- Reranker tests verify that unknown returned IDs are rejected and malformed output falls back safely.
- API contract tests validate both branches of the JSON Schema and all error envelopes.
- PostgreSQL integration tests exercise pgvector lookup, active-version isolation, and atomic rebuild behavior.
- A small checked-in fixture mirrors the real catalog shape; the 35 MB source file is used only in an opt-in import smoke test.

## 13. Acceptance Criteria

1. Given an exact catalog name or code, the service returns its original span, category, stable ID, metadata, and confidence.
2. Given one alias shared by multiple categories, the service either resolves it using context or returns an ordered ambiguous result containing every candidate above the return threshold.
3. Given a minor typo, abbreviation, or semantically close mention, vector recall can return catalog candidates for contextual reranking.
4. No successful response contains an entity ID absent from the active catalog version.
5. An embedding or reranking outage still permits exact recognition and marks the response `degraded`.
6. A failed rebuild does not replace the active index.
7. The response validates against the published JSON Schema.
8. Unit and API tests run without live LLM or embedding services; integration tests clearly declare their PostgreSQL requirement.
