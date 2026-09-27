# Entity Recognition Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python FastAPI service that recognizes only catalog-backed financial entities, returns their categories, and explicitly ranks cross-category ambiguity.

**Architecture:** Use an in-memory exact alias matcher for deterministic recall, PostgreSQL/pgvector for fuzzy candidate retrieval, and an OpenAI-compatible LLM solely to rerank supplied catalog candidates. A resolver combines these signals and emits a discriminated `resolved`/`ambiguous` response that validates against a published JSON Schema.

**Tech Stack:** Python 3.11+, FastAPI, Pydantic 2, SQLAlchemy 2 async, asyncpg, pgvector, pyahocorasick, httpx, pytest, pytest-asyncio, PostgreSQL 16 + pgvector, Ollama `qwen3-embedding:0.6b`, DeepSeek-compatible chat API.

**Spec:** `docs/superpowers/specs/2026-09-27-entity-recognition-service-design.md`

## Global Constraints

- Final entity IDs must exist in the active imported catalog version.
- Preserve all eleven category values exactly as defined in the spec.
- Preserve original-text `[start, end)` character offsets after normalization.
- A shared alias may retain candidates from multiple categories.
- LLM output may reorder only supplied candidate IDs and must never create entities.
- Embedding dimension is exactly 1024.
- Unit and API tests must not require live embedding or LLM services.
- Production code follows test-first RED → GREEN → REFACTOR cycles.
- Do not copy the 35 MB source catalog into Git; use a small representative fixture in tests.

---

## File Structure

```text
entity-service/
├── .env.example                         # Document service environment variables
├── Dockerfile                           # Production service image
├── README.md                            # Local setup, indexing, API examples
├── pyproject.toml                       # Runtime/dev dependencies and pytest config
├── app/
│   ├── __init__.py
│   ├── main.py                          # FastAPI application and lifespan wiring
│   ├── config.py                        # Typed environment configuration
│   ├── api/
│   │   ├── __init__.py
│   │   ├── dependencies.py              # Application service accessors
│   │   ├── entities.py                  # Recognition and entity lookup routes
│   │   ├── index.py                     # Rebuild and job-status routes
│   │   └── schema.py                    # Published response schema route
│   ├── catalog/
│   │   ├── __init__.py
│   │   ├── loader.py                    # JSON catalog parsing and validation
│   │   └── models.py                    # Catalog entity/category models
│   ├── core/
│   │   ├── __init__.py
│   │   ├── embedding.py                 # OpenAI-compatible embedding adapter
│   │   ├── exact_matcher.py             # Aho-Corasick alias matching
│   │   ├── mention_generator.py         # Candidate spans for fuzzy recall
│   │   ├── normalization.py              # Normalized text and offset mapping
│   │   ├── pipeline.py                  # Recognition orchestration
│   │   ├── reranker.py                  # Restricted structured LLM reranker
│   │   └── resolver.py                  # Score fusion and ambiguity policy
│   ├── db/
│   │   ├── __init__.py
│   │   ├── models.py                    # SQLAlchemy catalog tables
│   │   ├── repository.py                # Active-version and vector queries
│   │   └── session.py                   # Async engine/session factory
│   ├── indexing/
│   │   ├── __init__.py
│   │   └── service.py                   # Atomic catalog rebuild jobs
│   └── models/
│       ├── __init__.py
│       └── api.py                       # Pydantic API contracts
└── tests/
    ├── conftest.py                      # Fixtures and dependency overrides
    ├── fixtures/entity-small.json       # All categories + ambiguity examples
    ├── unit/test_catalog_loader.py
    ├── unit/test_normalization.py
    ├── unit/test_exact_matcher.py
    ├── unit/test_mention_generator.py
    ├── unit/test_embedding.py
    ├── unit/test_resolver.py
    ├── unit/test_reranker.py
    ├── unit/test_pipeline.py
    ├── integration/test_repository.py
    ├── integration/test_indexing.py
    └── api/test_api.py
docker/postgres/initdb/05_entity_schema.sql # pgvector entity tables/indexes
docker/postgres/docker-compose.yml          # entity-service container
```

---

### Task 1: Service Skeleton and API Contracts

**Files:**
- Create: `entity-service/pyproject.toml`
- Create: `entity-service/app/__init__.py`
- Create: `entity-service/app/config.py`
- Create: `entity-service/app/models/__init__.py`
- Create: `entity-service/app/models/api.py`
- Create: `entity-service/tests/unit/test_api_models.py`

**Interfaces:**
- Produces: `Category`, `Span`, `CatalogEntity`, `MatchEvidence`, `ResolvedMention`, `AmbiguousMention`, `UnresolvedMention`, `RecognitionRequest`, `RecognitionResponse`, `Settings`.
- Consumes: no earlier task interfaces.

- [ ] **Step 1: Create project metadata and the failing API-model tests**

Create `pyproject.toml` with package name `entity-service`, Python `>=3.11`, and these dependencies: `fastapi>=0.115`, `uvicorn[standard]>=0.30`, `pydantic>=2.9`, `pydantic-settings>=2.5`, `sqlalchemy[asyncio]>=2.0`, `asyncpg>=0.29`, `pgvector>=0.3`, `pyahocorasick>=2.1`, `httpx>=0.27`, `orjson>=3.10`, and `loguru>=0.7`. Add dev dependencies `pytest>=8`, `pytest-asyncio>=0.24`, `jsonschema>=4.23`, and `testcontainers[postgres]>=4.8`.

Write tests proving:

```python
def test_resolved_and_ambiguous_mentions_are_discriminated():
    resolved = ResolvedMention.model_validate({
        "mention": "华夏成长", "span": {"start": 0, "end": 4},
        "resolution": "resolved",
        "entity": {"id": "fund_name:000001.OF", "category": "fund_name",
                   "canonical_value": "华夏成长", "metadata": {"fund_code": "000001.OF"}},
        "match": {"method": "exact_alias", "confidence": 0.99},
    })
    assert resolved.entity.category == Category.FUND_NAME

    ambiguous = AmbiguousMention.model_validate({
        "mention": "000001", "span": {"start": 0, "end": 6},
        "resolution": "ambiguous",
        "ambiguity": {"reason": "cross-category", "context_used": "000001",
                      "context_needed": ["资产类型"]},
        "candidates": [candidate("fund_code:000001.OF", "fund_code", 1, 0.90),
                       candidate("stock_code:000001.SZ", "stock_code", 2, 0.88)],
    })
    assert len(ambiguous.candidates) == 2
```

Also assert invalid spans, unknown categories, confidence outside `[0, 1]`, one-candidate ambiguity, and extra properties are rejected.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_api_models.py -v`

Expected: collection fails because `app.models.api` does not exist.

- [ ] **Step 3: Implement the minimal Pydantic contracts and settings**

Use `ConfigDict(extra="forbid")`, a `Span` model validator enforcing `end > start`, string enums for categories/methods, and a discriminated union:

```python
EntityMention = Annotated[
    ResolvedMention | AmbiguousMention,
    Field(discriminator="resolution"),
]

class RecognitionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str
    text: str
    status: Literal["completed", "degraded"]
    entities: list[EntityMention]
    unresolved_mentions: list[UnresolvedMention]
    diagnostics: Diagnostics
```

Model every field from section 8 of the spec. Configure settings with the exact environment variables and defaults from section 10.

- [ ] **Step 4: Verify GREEN and validate generated schema**

Run: `cd entity-service && python -m pytest tests/unit/test_api_models.py -v`

Expected: all tests pass, and `jsonschema.Draft202012Validator.check_schema(RecognitionResponse.model_json_schema())` succeeds.

- [ ] **Step 5: Commit**

```bash
git add entity-service/pyproject.toml entity-service/app entity-service/tests/unit/test_api_models.py
git commit -m "feat(entity): define service contracts and configuration"
```

---

### Task 2: Catalog Loading and Offset-Safe Normalization

**Files:**
- Create: `entity-service/app/catalog/__init__.py`
- Create: `entity-service/app/catalog/models.py`
- Create: `entity-service/app/catalog/loader.py`
- Create: `entity-service/app/core/__init__.py`
- Create: `entity-service/app/core/normalization.py`
- Create: `entity-service/tests/fixtures/entity-small.json`
- Create: `entity-service/tests/unit/test_catalog_loader.py`
- Create: `entity-service/tests/unit/test_normalization.py`

**Interfaces:**
- Consumes: `Category` from Task 1.
- Produces: `CatalogRecord`, `CatalogDocument`, `CatalogLoader.load(path)`, `NormalizedText`, `normalize_with_offsets(text)`.

- [ ] **Step 1: Write failing catalog-loader tests**

The fixture must contain at least one record from every source collection and include alias `000001` in fund, stock, and index code records. Test:

```python
def test_loader_maps_all_source_collections(tmp_path, fixture_catalog_path):
    document = CatalogLoader().load(fixture_catalog_path)
    assert {item.category for item in document.entities} == set(Category)
    assert document.by_id("stock_code:000001.SZ").metadata["stock_name"] == "平安银行"

def test_loader_preserves_cross_category_aliases(fixture_catalog_path):
    document = CatalogLoader().load(fixture_catalog_path)
    matches = [e for e in document.entities if "000001" in e.normalized_aliases]
    assert {e.category for e in matches} == {
        Category.FUND_CODE, Category.STOCK_CODE, Category.TRACK_INDEX_CODE
    }
```

Also test that conflicting duplicate IDs fail, missing canonical fields fail, empty catalogs fail, and the SHA-256 is stable.

- [ ] **Step 2: Run catalog tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_catalog_loader.py -v`

Expected: failure because the loader is missing.

- [ ] **Step 3: Implement streaming catalog parsing and normalized records**

Use `orjson.loads` for the initial implementation. Map each source collection through one immutable mapping, derive canonical value from `code` or `name`, include it in aliases, normalize aliases, preserve metadata, and compute a file SHA-256 before parsing. Reject conflicting IDs instead of silently overwriting.

- [ ] **Step 4: Verify catalog tests GREEN**

Run: `cd entity-service && python -m pytest tests/unit/test_catalog_loader.py -v`

Expected: all catalog tests pass.

- [ ] **Step 5: Write failing normalization and offset tests**

```python
@pytest.mark.parametrize(("source", "normalized", "fragment"), [
    ("请看０００００１．ＳＺ", "请看000001.sz", "０００００１．ＳＺ"),
    ("比较  华夏成长  和指数", "比较 华夏成长 和指数", "华夏成长"),
    ("*ST三六五怎么样", "*st三六五怎么样", "*ST三六五"),
])
def test_normalization_recovers_original_spans(source, normalized, fragment):
    result = normalize_with_offsets(source)
    assert result.text == normalized
    start = normalized.index(fragment.casefold().replace("０００００１．ＳＺ", "000001.sz"))
    span = result.original_span(start, start + len(result.text[start:start + len(fragment)]))
    assert source[span.start:span.end] == fragment
```

Add focused cases for collapsed whitespace, combining Unicode, Chinese punctuation, and empty input.

- [ ] **Step 6: Run normalization tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_normalization.py -v`

Expected: failure because `normalize_with_offsets` is missing.

- [ ] **Step 7: Implement normalization with a per-output-character source range**

Normalize each source segment with NFKC and case folding, collapse whitespace while retaining a source range for the collapsed character, and expose `original_span(normalized_start, normalized_end) -> Span`.

- [ ] **Step 8: Verify Task 2 GREEN and commit**

Run: `cd entity-service && python -m pytest tests/unit/test_catalog_loader.py tests/unit/test_normalization.py -v`

```bash
git add entity-service/app/catalog entity-service/app/core entity-service/tests/fixtures entity-service/tests/unit/test_catalog_loader.py entity-service/tests/unit/test_normalization.py
git commit -m "feat(entity): load catalog and preserve normalized offsets"
```

---

### Task 3: Exact Matcher and Fuzzy Mention Generation

**Files:**
- Create: `entity-service/app/core/exact_matcher.py`
- Create: `entity-service/app/core/mention_generator.py`
- Create: `entity-service/tests/unit/test_exact_matcher.py`
- Create: `entity-service/tests/unit/test_mention_generator.py`

**Interfaces:**
- Consumes: `CatalogRecord`, `NormalizedText`, `Span`.
- Produces: `RawCandidate`, `ExactMatcher.build(records)`, `ExactMatcher.find(normalized)`, `MentionGenerator.generate(normalized, covered_spans)`.

- [ ] **Step 1: Write failing exact-matcher tests**

Test one-pass matching, original offsets, duplicate aliases across categories, longer numeric boundaries, nested names, and duplicate suppression:

```python
def test_shared_alias_keeps_every_category(catalog_document):
    matcher = ExactMatcher.build(catalog_document.entities)
    hits = matcher.find(normalize_with_offsets("比较000001最近表现"))
    group = [hit for hit in hits if hit.mention == "000001"]
    assert {hit.entity.category for hit in group} == {
        Category.FUND_CODE, Category.STOCK_CODE, Category.TRACK_INDEX_CODE
    }

def test_code_does_not_match_inside_longer_number(catalog_document):
    hits = ExactMatcher.build(catalog_document.entities).find(
        normalize_with_offsets("订单号10000012不是证券代码")
    )
    assert not any(hit.mention == "000001" for hit in hits)
```

- [ ] **Step 2: Run exact matcher tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_exact_matcher.py -v`

Expected: import failure for `ExactMatcher`.

- [ ] **Step 3: Implement Aho-Corasick exact matching**

Store an alias-to-entity-list payload in the automaton. Apply digit/Latin boundary checks, map normalized offsets back to original offsets, retain cross-category hits, and deduplicate only identical `(start, end, entity_id)` tuples.

- [ ] **Step 4: Verify exact matcher GREEN**

Run: `cd entity-service && python -m pytest tests/unit/test_exact_matcher.py -v`

- [ ] **Step 5: Write failing mention-generator tests**

Assert that the generator emits bounded spans for `华夏沪深三百`, `平银`, and `000001SZ`, excludes punctuation-only spans, respects already covered exact spans, and never emits more than the configured limit.

- [ ] **Step 6: Run mention-generator tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_mention_generator.py -v`

Expected: import failure for `MentionGenerator`.

- [ ] **Step 7: Implement bounded span generation**

Split on sentence punctuation, recognize finance-code patterns, and generate Chinese/Latin token windows of 2–24 normalized characters. Prefer spans adjacent to finance category words, skip stopword-only spans, subtract spans fully covered by strong exact matches, and enforce `MAX_MENTION_SPANS`.

- [ ] **Step 8: Verify Task 3 GREEN and commit**

Run: `cd entity-service && python -m pytest tests/unit/test_exact_matcher.py tests/unit/test_mention_generator.py -v`

```bash
git add entity-service/app/core/exact_matcher.py entity-service/app/core/mention_generator.py entity-service/tests/unit/test_exact_matcher.py entity-service/tests/unit/test_mention_generator.py
git commit -m "feat(entity): add exact matching and mention generation"
```

---

### Task 4: PostgreSQL/pgvector Repository

**Files:**
- Create: `docker/postgres/initdb/05_entity_schema.sql`
- Create: `entity-service/app/db/__init__.py`
- Create: `entity-service/app/db/models.py`
- Create: `entity-service/app/db/session.py`
- Create: `entity-service/app/db/repository.py`
- Create: `entity-service/tests/integration/test_repository.py`

**Interfaces:**
- Consumes: `CatalogRecord`, 1024-dimensional embeddings.
- Produces: `EntityRepository.create_version`, `insert_batch`, `activate_version`, `fail_version`, `get_active_version`, `get_entity`, `list_active_entities`, `vector_search`.

- [ ] **Step 1: Write failing repository integration tests**

Use a `pgvector/pgvector:pg16` testcontainer marked `integration`. Verify:

```python
async def test_activation_is_atomic(repository, version_a, version_b):
    await repository.activate_version(version_a)
    assert (await repository.get_active_version()).version_id == version_a
    await repository.activate_version(version_b)
    assert (await repository.get_active_version()).version_id == version_b
    assert await repository.version_status(version_a) == "retired"

async def test_vector_search_isolated_to_active_version(repository, seeded_versions):
    hits = await repository.vector_search([1.0] + [0.0] * 1023, top_k=5)
    assert hits
    assert {hit.version_id for hit in hits} == {seeded_versions.active}
```

Also test conflicting IDs, category filters, entity lookup, failed-version invisibility, and cosine ordering.

- [ ] **Step 2: Run repository tests and verify RED**

Run: `cd entity-service && python -m pytest tests/integration/test_repository.py -v -m integration`

Expected: repository import failure. If Docker is unavailable, record that environmental blocker and still verify test collection with `--collect-only`.

- [ ] **Step 3: Add schema SQL**

Create the two tables and indexes from spec section 5. Use `CREATE EXTENSION IF NOT EXISTS vector`, category/status check constraints, `VECTOR(1024)`, JSONB GIN alias index, and HNSW cosine index. Make initialization idempotent.

- [ ] **Step 4: Implement async SQLAlchemy models and repository**

Use explicit transactions. `activate_version` must lock version rows, validate the target is `building`, retire the current active version, then activate the target in the same transaction. `vector_search` joins the active version and uses cosine distance, returning similarity `1 - distance`.

- [ ] **Step 5: Verify repository GREEN and commit**

Run: `cd entity-service && python -m pytest tests/integration/test_repository.py -v -m integration`

```bash
git add docker/postgres/initdb/05_entity_schema.sql entity-service/app/db entity-service/tests/integration/test_repository.py
git commit -m "feat(entity): persist versioned catalog in pgvector"
```

---

### Task 5: Embedding Adapter and Atomic Index Rebuild

**Files:**
- Create: `entity-service/app/core/embedding.py`
- Create: `entity-service/app/indexing/__init__.py`
- Create: `entity-service/app/indexing/service.py`
- Create: `entity-service/tests/unit/test_embedding.py`
- Create: `entity-service/tests/integration/test_indexing.py`

**Interfaces:**
- Consumes: `CatalogLoader`, `EntityRepository`, `Settings`.
- Produces: `EmbeddingClient.embed(texts)`, `IndexJob`, `IndexingService.start_rebuild`, `IndexingService.get_job`.

- [ ] **Step 1: Write failing embedding-adapter tests**

Use `httpx.MockTransport` to assert batching, input order, timeout/error mapping, and strict dimension validation:

```python
async def test_rejects_wrong_embedding_dimension():
    client = embedding_client(returning=[[0.1, 0.2]])
    with pytest.raises(EmbeddingDimensionError):
        await client.embed(["华夏成长"])
```

- [ ] **Step 2: Verify embedding tests RED**

Run: `cd entity-service && python -m pytest tests/unit/test_embedding.py -v`

Expected: missing adapter import.

- [ ] **Step 3: Implement the OpenAI-compatible embedding adapter**

POST batches to `/embeddings` using `model` and `input`. Sort response items by `index`, require one 1024-float vector per input, reject non-finite values, use bounded retries only for 429/502/503/504, and map terminal failures to typed dependency errors.

- [ ] **Step 4: Verify embedding tests GREEN**

Run: `cd entity-service && python -m pytest tests/unit/test_embedding.py -v`

- [ ] **Step 5: Write failing atomic-indexing tests**

Test successful activation, repeated SHA idempotency, forced rebuild, batch progress, concurrent rebuild rejection, catalog failure, embedding failure, and preservation of the prior active version.

- [ ] **Step 6: Verify indexing tests RED**

Run: `cd entity-service && python -m pytest tests/integration/test_indexing.py -v -m integration`

Expected: missing `IndexingService`.

- [ ] **Step 7: Implement rebuild jobs**

Allow exactly one in-process job under an `asyncio.Lock`. Build `search_text` as `类别: {category}; 标准值: {canonical}; 别名: {aliases}; 元数据: {selected metadata}`, embed in configured batches, insert through the repository, verify counts and categories, activate only after complete success, and mark failed versions without touching the active one.

- [ ] **Step 8: Verify Task 5 GREEN and commit**

Run: `cd entity-service && python -m pytest tests/unit/test_embedding.py tests/integration/test_indexing.py -v`

```bash
git add entity-service/app/core/embedding.py entity-service/app/indexing entity-service/tests/unit/test_embedding.py entity-service/tests/integration/test_indexing.py
git commit -m "feat(entity): rebuild catalog embeddings atomically"
```

---

### Task 6: Candidate Fusion, Restricted Reranking, and Resolution

**Files:**
- Create: `entity-service/app/core/resolver.py`
- Create: `entity-service/app/core/reranker.py`
- Create: `entity-service/tests/unit/test_resolver.py`
- Create: `entity-service/tests/unit/test_reranker.py`

**Interfaces:**
- Consumes: exact/vector candidates, source context, thresholds from `Settings`.
- Produces: `CandidateFusion.score`, `Reranker.rerank`, `Resolver.resolve`.

- [ ] **Step 1: Write failing resolver tests**

Cover dominant resolution, cross-category ambiguity, confidence/margin thresholds, ordered candidate limits, overlaps, and identical tuple deduplication:

```python
def test_close_cross_category_candidates_are_ambiguous(resolver):
    result = resolver.resolve("000001", span(0, 6), [
        scored("fund_code:000001.OF", "fund_code", 0.91),
        scored("stock_code:000001.SZ", "stock_code", 0.88),
        scored("track_index_code:000001.SH", "track_index_code", 0.86),
    ], context="比较000001最近表现")
    assert result.resolution == "ambiguous"
    assert [c.entity.category for c in result.candidates] == [
        Category.FUND_CODE, Category.STOCK_CODE, Category.TRACK_INDEX_CODE
    ]
```

- [ ] **Step 2: Verify resolver tests RED**

Run: `cd entity-service && python -m pytest tests/unit/test_resolver.py -v`

- [ ] **Step 3: Implement deterministic fusion and resolution policy**

Represent signal weights explicitly and clamp the final score to `[0, 1]`. Apply the three thresholds and ambiguity candidate limit from settings. Set `context_needed` from remaining category distinctions, such as `需要说明这是基金代码、股票代码还是指数代码`.

- [ ] **Step 4: Verify resolver tests GREEN**

Run: `cd entity-service && python -m pytest tests/unit/test_resolver.py -v`

- [ ] **Step 5: Write failing reranker tests**

Use `httpx.MockTransport`. Verify supplied candidates are reordered, unknown IDs are discarded, duplicate IDs are rejected, malformed JSON triggers deterministic fallback, and unavailable LLM marks the result degraded without inventing entities.

- [ ] **Step 6: Verify reranker tests RED**

Run: `cd entity-service && python -m pytest tests/unit/test_reranker.py -v`

- [ ] **Step 7: Implement restricted structured reranking**

Send a system instruction that the model may only rank supplied IDs. Request JSON containing `ranked_candidates: [{entity_id, relevance, reason}]`. Parse with a Pydantic model, intersect IDs with the supplied set, preserve omitted candidates after valid returned candidates, and fall back to deterministic order on any dependency or validation failure.

- [ ] **Step 8: Verify Task 6 GREEN and commit**

Run: `cd entity-service && python -m pytest tests/unit/test_resolver.py tests/unit/test_reranker.py -v`

```bash
git add entity-service/app/core/resolver.py entity-service/app/core/reranker.py entity-service/tests/unit/test_resolver.py entity-service/tests/unit/test_reranker.py
git commit -m "feat(entity): resolve and rerank ambiguous entities"
```

---

### Task 7: End-to-End Recognition Pipeline

**Files:**
- Create: `entity-service/app/core/pipeline.py`
- Create: `entity-service/tests/unit/test_pipeline.py`

**Interfaces:**
- Consumes: matcher, mention generator, embedding client, repository, reranker, resolver.
- Produces: `RecognitionPipeline.recognize(request, request_id) -> RecognitionResponse`.

- [ ] **Step 1: Write failing pipeline behavior tests**

Use real matcher/resolver objects and deterministic fake boundary adapters for HTTP/database dependencies. Test:

- `华夏成长` returns one resolved fund-name result with exact original span.
- `000001` returns a cross-category ambiguity group when context is insufficient.
- `比较000001基金表现` resolves the fund-code candidate after context reranking.
- `华夏沪深三百` uses vector recall and returns only a catalog entity.
- duplicate exact/vector candidates collapse by `(span, entity_id)`.
- embedding/reranker outages retain exact results and set `status=degraded`.
- a request with no validated candidates returns no entities and a permitted unresolved mention.

- [ ] **Step 2: Run pipeline tests and verify RED**

Run: `cd entity-service && python -m pytest tests/unit/test_pipeline.py -v`

Expected: missing `RecognitionPipeline`.

- [ ] **Step 3: Implement pipeline orchestration**

Normalize once, exact-match once, generate only bounded uncovered spans, batch-embed spans, vector-search with optional category filters, fuse candidates by span, rerank only uncertain groups, resolve all groups, sort by `(span.start, span.end, resolution)`, and calculate diagnostics with `time.perf_counter_ns()`.

Before serializing, assert every returned ID belongs to candidates supplied by the active repository/matcher snapshot.

- [ ] **Step 4: Verify pipeline GREEN and commit**

Run: `cd entity-service && python -m pytest tests/unit/test_pipeline.py -v`

```bash
git add entity-service/app/core/pipeline.py entity-service/tests/unit/test_pipeline.py
git commit -m "feat(entity): orchestrate hybrid entity recognition"
```

---

### Task 8: FastAPI Endpoints and Error Contract

**Files:**
- Create: `entity-service/app/api/__init__.py`
- Create: `entity-service/app/api/dependencies.py`
- Create: `entity-service/app/api/entities.py`
- Create: `entity-service/app/api/index.py`
- Create: `entity-service/app/api/schema.py`
- Create: `entity-service/app/main.py`
- Create: `entity-service/tests/conftest.py`
- Create: `entity-service/tests/api/test_api.py`

**Interfaces:**
- Consumes: all service interfaces from Tasks 1–7.
- Produces: FastAPI `app` and all endpoints from spec section 7.

- [ ] **Step 1: Write failing API tests**

With dependency overrides, test:

```python
def test_recognize_contract(client):
    response = client.post("/v1/entities/recognize", json={"text": "000001"})
    assert response.status_code == 200
    RecognitionResponse.model_validate(response.json())

def test_schema_endpoint_publishes_valid_schema(client):
    schema = client.get("/v1/schema/recognition-response").json()
    Draft202012Validator.check_schema(schema)

def test_rebuild_requires_admin_token(client):
    response = client.post("/v1/entities/index/rebuild", json={"force": False})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"
```

Also cover validation 422, missing entity 404, index unavailable 503, rebuild accepted 202, concurrent rebuild 409, job status, health states, and root endpoint discovery.

- [ ] **Step 2: Run API tests and verify RED**

Run: `cd entity-service && python -m pytest tests/api/test_api.py -v`

Expected: `app.main` import failure.

- [ ] **Step 3: Implement dependency wiring, routes, and error handlers**

Use lifespan to initialize the database pool, load active entities into the exact matcher, and close HTTP/database clients. Create a request ID when `X-Request-ID` is absent. Register typed exception handlers producing the spec error envelope. Compare the admin token with `secrets.compare_digest`.

- [ ] **Step 4: Verify API tests and full unit suite GREEN**

Run: `cd entity-service && python -m pytest tests/unit tests/api -v`

- [ ] **Step 5: Commit**

```bash
git add entity-service/app/api entity-service/app/main.py entity-service/tests/conftest.py entity-service/tests/api
git commit -m "feat(entity): expose recognition and indexing APIs"
```

---

### Task 9: Docker, Documentation, and Final Verification

**Files:**
- Create: `entity-service/.env.example`
- Create: `entity-service/Dockerfile`
- Create: `entity-service/README.md`
- Modify: `docker/postgres/docker-compose.yml`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: FastAPI `app`, PostgreSQL, Ollama embedding service, chat API.
- Produces: reproducible local/container deployment and operator documentation.

- [ ] **Step 1: Verify the completed API baseline before deployment work**

Run: `cd entity-service && python -m pytest tests/api/test_api.py -v`

Expected: all Task 8 API tests pass. This task changes deployment configuration and documentation, not runtime behavior, so it reuses the already test-driven API suite.

- [ ] **Step 2: Add container and environment configuration**

Use a `python:3.11-slim` image, install the package without dev dependencies, run as a non-root user, expose 8800, and start `uvicorn app.main:app --host 0.0.0.0 --port 8800`.

Add `entity-service` to `docker/postgres/docker-compose.yml` with:

```yaml
entity-service:
  build: ../../entity-service
  environment:
    DATABASE_URL: postgresql+asyncpg://postgres:${POSTGRES_PASSWORD:-123456}@postgres:5432/financial_copilot
    ENTITY_CATALOG_PATH: /data/entity.json
    ENTITY_ADMIN_TOKEN: ${ENTITY_ADMIN_TOKEN:-change-me}
    EMBEDDING_BASE_URL: http://host.docker.internal:11434/v1
    EMBEDDING_MODEL: qwen3-embedding:0.6b
    EMBEDDING_DIM: "1024"
    LLM_API_KEY: ${DEEPSEEK_API_KEY:-sk-placeholder}
    LLM_BASE_URL: https://api.deepseek.com/v1
    LLM_MODEL: deepseek-chat
    PORT: "8800"
  ports:
    - "8800:8800"
  volumes:
    - ${ENTITY_CATALOG_PATH_HOST:?set ENTITY_CATALOG_PATH_HOST}:/data/entity.json:ro
  depends_on:
    - postgres
```

Ignore `.env`, Python caches, build metadata, and local benchmark artifacts under `entity-service` without weakening existing repository ignore rules.

- [ ] **Step 3: Document operation and contract examples**

README sections must cover prerequisites, local install, environment variables, PostgreSQL schema initialization, initial rebuild with admin token, job polling, recognition examples for resolved and ambiguous responses, dependency degradation, Docker startup, tests, and the rule that only catalog entities are returned.

- [ ] **Step 4: Run formatting-independent static checks**

Run:

```bash
cd entity-service
python -m compileall -q app tests
python -m pytest tests/unit tests/api -v
python -m pytest tests/integration -v -m integration
```

Expected: compilation succeeds and all available tests pass. If Docker/PostgreSQL is unavailable, integration tests must skip with one explicit reason rather than fail collection.

- [ ] **Step 5: Validate the real catalog without importing it into Git**

Run a read-only validation command against:

```text
D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/entity.json
```

Expected: all eleven categories are present, conflicting ID count is zero, and the valid entity count is reported. Do not print or commit the full catalog.

- [ ] **Step 6: Build and inspect the container definition**

Run:

```bash
docker compose -f docker/postgres/docker-compose.yml config
docker build -t financial-copilot-entity-service:local entity-service
```

Expected: Compose resolves with `ENTITY_CATALOG_PATH_HOST` set to the absolute source file path, and the image builds successfully.

- [ ] **Step 7: Commit deployment and documentation**

```bash
git add entity-service/.env.example entity-service/Dockerfile entity-service/README.md docker/postgres/docker-compose.yml .gitignore
git commit -m "docs(entity): add deployment and operations guide"
```

- [ ] **Step 8: Final repository review**

Run:

```bash
git status --short
git diff --check HEAD~9..HEAD
git log --oneline -9
```

Confirm no pre-existing user changes were included in task commits, no catalog dump or secret was committed, and each task commit is independently understandable.
