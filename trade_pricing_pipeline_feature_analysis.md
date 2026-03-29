# Trade Pricing Pipeline — Feature Analysis for Business Pitch

| Field | Value |
|---|---|
| Source Document | trade_pricing_pipeline_spec_v4_6.md |
| Analysis Date | March 2026 |
| Total Features Identified | 65 |
| Purpose | Feature inventory for stakeholder pitch — pick what to lead with |

---

## Category 1 — Support & Operations (fastest to show ROI)

| # | Feature | Detail |
|---|---|---|
| 1 | **Full trade audit trail in one JSON** | Every trade carries a complete lifecycle record: raw inputs, classification decision, derivation trace, every pricer's inputs/outputs/confidence, DQ score breakdown, processing metadata. One API call gives you the whole story. |
| 2 | **Plain-English trade investigation** | That audit record is structured so anyone (support, analyst, or an LLM) can answer "why was this trade priced at 98.42?" without calling a developer. |
| 3 | **Per-step query endpoints** | Population, classification, context, derivation, pricing, result — each step is independently queryable via REST. Support doesn't dig through logs, they hit an endpoint. |
| 4 | **Failed trade visibility** | `GET /pipeline/{jobDetailId}/failures` shows exactly which trades failed, at which step, with the error. Paginated. No log hunting. |
| 5 | **Degraded trade visibility** | `GET /pipeline/{jobDetailId}/degraded` shows trades that were priced with incomplete data. Support knows which results to double-check. |
| 6 | **Two-tier failure model** | One bad trade never kills the batch. 29,999 trades keep pricing. The failed one is marked, queryable, and reprocessable. |
| 7 | **DAG-level downstream blocking** | If any trade fails, Airflow blocks all downstream jobs automatically. Bad data never flows forward silently. |
| 8 | **Force-reset for stuck jobs** | JVM crash leaves `JOB_DETAIL` in RUNNING? One API call or Airflow parameter resets it. Recovery matrix documented: PARTIAL, FAILED, stale RUNNING — each has a clear path. |
| 9 | **No orphaned locks, ever** | Oracle releases all locks on commit, rollback, or dead connection. Spring Batch chunk transactions guarantee this. Documented, proven, four scenarios covered. |
| 10 | **External service health dashboard** | `GET /health/external-services` shows circuit breaker state per Feign service. Support sees instantly whether the issuer service is down. |

---

## Category 2 — Resilience & Risk Reduction

| # | Feature | Detail |
|---|---|---|
| 11 | **Observability isolation principle** | Lineage, audit, OTEL tracing, DQ scoring can all fail completely and pricing still completes. `ObservabilitySafeExecutor` wraps every non-pricing call. Hard architectural rule. |
| 12 | **Graceful degradation (CONTEXT_DEGRADED)** | External service goes down? Trades still price with available data. DQ score reflects the gap. Not an error — an expected, handled state. |
| 13 | **Resilience4j on external calls only** | Retry (3 attempts) → CircuitBreaker (50% threshold) → Bulkhead (20 concurrent max). All tunable via Config Server, no redeployment. |
| 14 | **No Resilience4j on internal JPA** | EDI/TDS/ODS are internal DB calls. Adding circuit breakers there would mask real database problems. Deliberate, documented design decision. |
| 15 | **Circuit breaker state management** | CLOSED → OPEN → HALF_OPEN → CLOSED lifecycle. Automatic recovery. Bulkhead prevents slow service from exhausting thread pools. |
| 16 | **Optimistic locking on job pick-up** | `@Version` column on `JOB_DETAIL` prevents two Airflow runs from claiming the same job. Cheap insurance, no performance cost. |
| 17 | **Transaction design fully specified** | Every operation has a documented transaction boundary, isolation level, and propagation. Anti-patterns table lists six things that must never appear in code. |

---

## Category 3 — Performance & Scalability

| # | Feature | Detail |
|---|---|---|
| 18 | **7 DB operations per 1,000-trade chunk** | 3 bulk SELECTs, 1 batch HTTP POST, 1 batch INSERT (results), 1 batch INSERT (details), 2 batch UPDATEs. N+1 queries literally cannot exist. |
| 19 | **Parallel JPA fetch** | EDI, TDS, ODS loaded simultaneously via `CompletableFuture.allOf()`. Three queries fire at once, join when all complete. |
| 20 | **Config-only parallelism scaling** | `chunk-throttle-limit: 1` for sequential (99% of runs). Change to N for parallel chunks. No code change. Thread pool formula documented: `jpa-fetch-pool-core = throttleLimit × 4`. |
| 21 | **Reference data preload before partition threads** | Step 3a loads all reference data for all effective dates once, sequentially, into cache. Partition threads never compete on loading. All cache hits. |
| 22 | **Stateless writer** | `TradePricingChunkWriter` has zero instance variables. Every `write()` call uses only local variables. Safe at any `throttleLimit`. No synchronization needed. |
| 23 | **Reader→Writer pattern, no ItemProcessor** | Spring Batch ItemProcessor forces per-item processing. This pipeline skips it — writer receives the full chunk and does everything in bulk. Standards-compliant, not a hack. |
| 24 | **Caffeine caching with sync=true** | One thread loads per cache key. All others wait and share the result. No thundering herd on cache miss. |
| 25 | **30,000 trades per pricing run** | Documented throughput target. ~30 chunks of 1,000 trades each. |

---

## Category 4 — Config-Driven Flexibility

| # | Feature | Detail |
|---|---|---|
| 26 | **Pricing waterfall is YAML config** | `MBS: [PRICER_A, PRICER_C]`, `ARM: [PRICER_B, PRICER_A]`. Add, remove, reorder pricers per classification type without code changes. |
| 27 | **Price selection strategy is config** | `BEST_CONFIDENCE`, `FIRST_VALID`, `CONFIGURED_PRIMARY`. Switch between strategies in YAML. |
| 28 | **DQ scoring thresholds are config** | Minimum acceptable score, degraded source penalties (full: -25, partial: -10). Tunable without redeployment. |
| 29 | **Resilience4j parameters are Config Server keys** | Retry attempts, wait time, circuit breaker thresholds, bulkhead limits. All tunable live via Spring Cloud Config. |
| 30 | **Population query filter is config** | `book_code IN ('BOOK_A','BOOK_B')` — change which books are in scope without touching code. |
| 31 | **RERUN preserved fields are config** | `preserve-fields: [originalTradeDate, bookingSystem, legalEntityId]`. Which fields carry forward on rerun is configurable. |
| 32 | **Upstream override fields whitelisted in Config Server** | Special treatment overrides validated against a configurable whitelist. Upstream can't override arbitrary fields. |
| 33 | **Audit destination is config** | S3 bucket or file. Switch without code. |

---

## Category 5 — Multiple Entry Points & Run Modes

| # | Feature | Detail |
|---|---|---|
| 34 | **3 entry points, 1 pipeline** | UI by date, UI by CSV upload, upstream API push. All three converge to `JOB_DETAIL` + `JOB_REQUEST`. The batch pipeline is identical regardless of entry. |
| 35 | **4 run modes** | SUBMIT (new), RERUN (reprice with preserved fields), RECLASS (fresh classification), UPSTREAM SUBMIT (price new + apply specials on existing). Plus future AGENT mode. |
| 36 | **RECLASS is pre-processing + SUBMIT** | The batch job never sees "RECLASS." It's a delete + run-as-SUBMIT. Clean separation of concerns. |
| 37 | **Upstream special treatment** | Upstream systems can post trade IDs with `SpecialTreatmentConfig` (SLA type + override fields). Smart split: unpriced trades get full pipeline, already-priced trades get overrides only. |
| 38 | **CSV file upload** | Support/testers can upload a CSV of specific trade IDs for targeted reprocessing. File pre-processor reads rows, creates DB records, pipeline runs normally. |
| 39 | **Airflow as sole scheduler** | No internal polling scheduler. Airflow DAG runs every ~2 minutes. Support can manually trigger with `job_detail_id` or `file_path` parameters. |

---

## Category 6 — Data Quality & Pricing Transparency

| # | Feature | Detail |
|---|---|---|
| 40 | **Data Quality Score per trade** | Weighted scoring: price found (+40), pricers agree (+20), all required attrs present (+20), data completeness (+20). Degradation penalties applied per failed source. |
| 41 | **Quality flags** | Machine-readable tags: `ISSUER_DATA_UNAVAILABLE`, `PRICER_B_SKIPPED`, etc. Filterable, alertable, reportable. |
| 42 | **Minimum threshold enforcement** | DQ score below configurable minimum (default 60) is flagged. Future: auto-hold for review. |
| 43 | **Pricing waterfall trace** | Every pricer in the chain: pre-condition check with actual attribute values at time of check, pricing inputs, pricing outputs, confidence score, skip reason if skipped. Full path string: `PRICER_A:OK:0.94 → PRICER_B:SKIPPED:missing_issuerSpread → SELECTED:PRICER_A:0.94`. |
| 44 | **Pre-condition validator per pricer** | Before a pricer runs, it checks required attributes exist. Missing attrs → skip with documented reason, try next pricer. No silent failures. |
| 45 | **Selection decision with reasoning** | `BEST_CONFIDENCE` strategy picks PRICER_A (0.94) over PRICER_C (0.81). The reason string is in the audit record. Reviewable by human or machine. |

---

## Category 7 — Extensibility

| # | Feature | Detail |
|---|---|---|
| 46 | **SETTLES pipeline placeholder** | Architecture already has `PipelineType` enum (OPENS/SETTLES), `AbstractPricingJobFactory`, discriminator on `JOB_DETAIL` and output tables. Steps 3a-6 are shared. Only steps 0-2 and partition key differ. |
| 47 | **Expression evaluator** | Custom recursive-descent parser for classification and derivation rules. No external library. `classification == "MBS" and couponRate > 5.0`. Add new rules without touching service code. |
| 48 | **Derivation rule chain** | Ordered `@Order` rules with `appliesTo()` via ExpressionEvaluator. Add new computed attributes by adding a rule class. No existing code modified. |
| 49 | **Pricer strategy pattern** | `PricerStrategy` interface. Add PRICER_D by implementing the interface and adding it to the YAML waterfall config. Zero changes to existing pricers. |
| 50 | **Shadow pricing API** | `POST /api/v1/pipeline/shadow-price` runs the full pipeline for one trade, returns complete result + audit record, writes nothing to DB. Pre-validation, analyst queries, AI agent inference. Same code path as batch — no parallel implementation. |

---

## Category 8 — AI-Ready Architecture

| # | Feature | Detail |
|---|---|---|
| 51 | **LLM-ready audit record** | Explicitly designed for AI consumption. Every field is labeled with its source and the step that produced it. Nothing is implicit. |
| 52 | **Agent manifest endpoint** | `GET /api/v1/agent/manifest` returns all callable tools with schemas. AI agent can discover and use pipeline endpoints without hardcoding. |
| 53 | **Future AGENT run mode (jobType TBD)** | Placeholder for LLM-driven automated reprocessing based on audit record analysis. |
| 54 | **Auto-approval path designed in** | Future approval agent can read DQ score, quality flags, selection reason, and pricing path to determine approval eligibility without human review. |
| 55 | **Per-step endpoints as agent tools** | Population, classification, context, derivation, pricing, result — each queryable independently. An agent can inspect any step without pulling the full record. |

---

## Category 9 — Observability & Monitoring

| # | Feature | Detail |
|---|---|---|
| 56 | **OpenTelemetry, not Micrometer** | Manual spans on every public service method and Feign call. Span attributes: runId, tradeId, partitionKey, stepName. |
| 57 | **13 custom metrics** | trades.priced, trades.failed, trades.degraded, pricer.invocations, pricer.skips, step.duration.ms, enrichment.feign.calls/retries/circuit.open, cache hits/misses/evictions. |
| 58 | **MDC injection** | runId, tradeId, partitionKey in every log line via `StepExecutionLineageListener`. Grep by trade ID across all log lines instantly. |
| 59 | **Pipeline lineage table** | `PIPELINE_LINEAGE` records step start/end, enrichment status, degraded sources, partition key. Full trace queryable via `GET /lineage/{runId}/trade/{tradeId}`. |

---

## Category 10 — Developer Experience & Code Quality

| # | Feature | Detail |
|---|---|---|
| 60 | **Spec is the LLM prompt** | Document header says "Upload this file at the start of a Claude Code session and say which phase to build." The spec IS the development context. |
| 61 | **5-phase build order** | Foundation → Orchestration → Pricing services → Spring Batch wiring → REST API. Each phase has a checkpoint: `./gradlew build` succeeds before proceeding. |
| 62 | **Coding standards enforced** | Naming conventions, architecture rules (no business logic in controllers, constructor injection only, no `@Autowired` on fields), 8-scenario WireMock test suite per Feign client. |
| 63 | **Anti-patterns documented** | Six things that must never appear in code, with explanations of why they break things and what to do instead. |
| 64 | **Observability isolation tests mandatory** | For every observability component, write a test that mocks it to throw and verify pricing still completes. Explicitly called "as important as happy-path tests." |
| 65 | **Flyway migrations** | V1-V5, Oracle syntax, version-controlled schema evolution. Spring Batch meta-tables in separate H2 — completely out of Oracle. |

---

## Pitch Framing Notes

**Lead with Category 1** (Support & Operations) — this is measurable cost savings. Every hour a support person spends debugging a trade failure is money. Start here, get the nod, then walk through the rest.

**Don't lead with Category 8** (AI-Ready) — put it fifth, not first. If you lead with AI, the conversation becomes about AI. You want the conversation to be about operational excellence, and AI is one benefit among many.

**Category 4** (Config-Driven) is the "faster feature delivery" story — product says "add a new pricer" and it's a YAML change, not a sprint.

**Category 2** (Resilience) is the "sleep at night" story — the pipeline doesn't go down, it degrades gracefully.

**Category 3** (Performance) is the "we built it right" story — 7 DB operations per 1,000 trades, not 7,000.

**Category 7** (Extensibility) is the "we won't rebuild this next year" story — SETTLES pipeline, new pricers, new rules, all additive.

---

*This document is a working inventory. Select features per audience and build the pitch deck from the selected subset.*
