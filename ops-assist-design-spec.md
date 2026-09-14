# AI Ops Assist — Design Specification

| Field | Value |
|---|---|
| Version | 0.6 (draft) |
| Date | 2026-09-12 |
| Changes in 0.2 | Added permanent-fix backlog ticket generation (§9.6, §9.7) |
| Changes in 0.3 | KAs are now Markdown-in-Git (docs-as-code): new §5.6, §6.3, §8.5–8.7; AI drafts open PRs |
| Changes in 0.4 | KA template model: tool taxonomy (§6.6), full step object (§6.7), conclusions table (§6.8), fragments/families/`KA-0000` (§8A) |
| Changes in 0.5 | Added §8.4.1 worked runner trace and Appendix B complete KA example |
| Changes in 0.6 | Orchestrator-neutral: canonical workflow/unit vocabulary in `incident.v1` (§6.1), capability tools + adapter contract (§6.6, §6.6.1), fragments and `KA-0000` generalised |
| Status | For review — expect contradictions, mark them in §17 |
| Owner | _TBD_ |

> **How to use this doc.** Read §3 (Principles) and §5 (Components) first — everything else follows from those. When you disagree with something, add a row to §17 (Decision Log) rather than silently editing, so we can discuss the trade-off.

---

## 1. Purpose and Scope

### 1.1 Problem
Scheduled production workflows fail — on Airflow today, and on Control-M, Autosys, Step Functions, Databricks and others across the wider organisation. Today a support engineer manually reads the unit log, digs through CloudWatch, checks whether the input file landed in S3, queries Oracle, hunts for a Knowledge Article (KA), and then acts. This is slow, repetitive, and inconsistent between engineers.

### 1.2 Goal
An AI-assisted platform that, on failure:
1. Determines whether a **known fix (KA)** exists.
2. If yes — executes the KA's diagnostic steps automatically and reports the outcome.
3. If no — **investigates intelligently** using AI and read-only tools, and produces a root-cause analysis.
4. Where safe and permitted, **executes the remediation autonomously**; otherwise recommends it to a human.
5. **Learns** — every resolved incident can become a new KA, shrinking the unknown path over time.
6. **Drives permanent fixes** — where the root cause is a defect rather than a transient condition, raises a Jira backlog ticket so the failure is engineered away, not merely re-remediated.

> **Two different outputs, two different purposes.** The KA makes the *next occurrence* cheaper to handle. The Jira ticket aims to ensure there is no next occurrence. A platform that only produces KAs industrialises the toil instead of removing it.

### 1.3 Scope
**In scope (v1):** Scheduled workflow failures from **any orchestrator** (Airflow first, others via the adapter contract in §6.6.1); KA-driven diagnosis; AI investigation; read-only tools (S3, Oracle, CloudWatch Logs, orchestrator APIs); tiered autonomous remediation; ticket updates.

**In scope (v2+):** Non-workflow event sources (CloudWatch alarms, ticket webhooks); multi-team tenancy; third-party agents and MCP servers.

> **Orchestrator neutrality is a v1 requirement, not a v2 aspiration.** Airflow is the first implementation, not the model. Teams run Control-M, Autosys, Step Functions, Databricks Workflows, Dagster, Argo, ADF and others; a platform that only speaks Airflow is not sellable internally (§12).

**Out of scope:** Replacing the on-call engineer for P1 incidents; anything touching IAM, schemas, or data deletion autonomously.

### 1.4 Non-Goals
- We are **not** building a chatbot. The interface is the ticket and the chat channel.
- We are **not** letting the LLM freely decide what runs in production. See §3.1.
- We are **not** building a general agent framework. We build one narrow platform well.

---

## 2. Glossary

| Term | Meaning |
|---|---|
| **AI Dome** | The company's internal LLM gateway, reached by REST. Our only LLM access path. |
| **MCP** | Model Context Protocol. A standard for exposing tools to AI apps. |
| **MCP server** | A service that exposes tools (`s3_check`, `search_logs`, …) over HTTP. |
| **MCP client** | Library code **inside** our app that talks to MCP servers. Not a separate service. |
| **KA** | Knowledge Article. A known failure with a documented, now machine-readable, fix. |
| **Case file** | The full record of one incident: context, evidence, AI decisions, actions taken. |
| **Signature** | A normalized error string (IDs/timestamps stripped) used for matching. |
| **Fingerprint** | SHA-256 of `task_key + signature`. Used for exact KA lookup and dedupe. |
| **Context pack** | Deterministically gathered comparative facts about a failure (see §9.1). |
| **Policy gate** | Plain code that decides whether a proposed action may execute. |
| **Tenant** | A team onboarded to the platform, with its own config, KAs, and IAM role. |
| **Backlog issue** | A Jira ticket describing the *permanent engineering fix* for a recurring root cause. Distinct from the incident ticket, which tracks one occurrence. |
| **Toil cost** | Accumulated engineer minutes + AI cost spent re-remediating the same root cause. Used to justify backlog priority. |
| **Orchestrator** | Any scheduler that runs workflows: Airflow, Control-M, Autosys, Step Functions, Databricks Workflows, Dagster, Prefect, Argo, ADF, Tidal. |
| **Workflow** | Canonical term for the scheduled container. Airflow DAG, Control-M job stream, Step Functions state machine, Databricks job. |
| **Unit** | Canonical term for the failing element within a workflow. Airflow task, Control-M job, Step Functions state, Databricks task. |
| **Run** | One execution of a workflow. Airflow DAG run, Control-M order, Step Functions execution. |
| **Adapter** | The per-orchestrator implementation of the canonical capability set (§6.6.1). |

---

## 3. Design Principles (Non-Negotiable)

These are the rules that everything else must obey. If a later section contradicts one of these, this section wins.

### 3.1 The AI proposes; code disposes
The LLM may **suggest** an action. Only the **policy gate** (plain deterministic code) decides whether it executes. This holds regardless of the AI's confidence, and regardless of what a log line, KA, or third-party agent says. It is also our defence against prompt injection via log content.

### 3.2 Determinism where determinism is possible
If a KA already documents the steps, **run them with a loop, not an agent**. LLM planning is reserved for genuinely unknown situations. This reduces cost, latency, and variance.

### 3.3 Never send raw data to the LLM
Every tool output passes through the **Compactor** (§10). Full output goes to S3; a summary goes to AI Dome. Token budgets are enforced in code *before* the call, not discovered by an API error.

### 3.4 One contract, many implementations
Sources, tools, agents, and playbooks plug in through **versioned contracts** (§6). Onboarding a new team must require **zero code changes in the core repo**.

### 3.5 Read and write are separated
Read tools and action tools live on **different MCP servers with different IAM roles**. A compromised or buggy read path cannot mutate production.

### 3.6 Every run is idempotent and auditable
Keyed by fingerprint. A duplicate SQS delivery resumes, it does not re-investigate. Every AI prompt, response, tool call, and decision is persisted.

### 3.7 "I don't know" is a first-class output
A wrong confident diagnosis is worse than an honest partial one. The schema explicitly supports `root_cause: null` with `ruled_out` findings.

### 3.8 The safety tail is not configurable
Tenants customise the *investigation* stages. `policy gate → remediate → verify → comms → audit` always runs and cannot be removed or reordered by config.

---

## 4. Architecture Overview

### 4.1 Logical flow

```
Workflow failure (any orchestrator)
      │
      ▼
   SNS ──► SQS (incident-queue) ──► DLQ
      │
      ▼
┌─────────────────────────────────────────────┐
│  ops-assist  (ECS Fargate)                  │
│                                             │
│  Consumer → Dedupe → Context Pack           │
│                        │                    │
│                   KA Lookup                 │
│                   ╱        ╲                │
│            KA found        No KA            │
│                │              │             │
│          Step Runner    AI Investigation    │
│          (no LLM plan)   (hypothesis loop)  │
│                ╲             ╱              │
│                 Compactor                   │
│                     │                       │
│               Synthesis (1 AI call)         │
│                     │                       │
│               Policy Gate                   │
│            ╱        │        ╲              │
│      auto-exec  approval   suggest-only     │
│                     │                       │
│              Verify → Comms → Audit         │
└─────────────────────────────────────────────┘
      │              │              │
      ▼              ▼              ▼
 MCP servers    AI Dome      DynamoDB / S3
   (ECS)      (REST, LLM)     RDS pgvector
```

### 4.2 Deployment map (AWS)

| Component | Service | Network |
|---|---|---|
| `ops-assist` | ECS Fargate | Private subnet, egress via NAT/VPC endpoint |
| `ops-mcp-read` | ECS Fargate + internal ALB :443→:8080 | Private, SG-restricted to ops-assist |
| `ops-mcp-action` | ECS Fargate + internal ALB :443→:8080 | Private, SG-restricted to ops-assist |
| `incident-queue` | SQS + DLQ | — |
| `incident-table` | DynamoDB | — |
| `ka-docs` | Git repo (GitHub/Bitbucket) — **source of truth** | Outside AWS |
| KA CI/CD | GitHub Actions or CodeBuild + CodePipeline | Deploys to S3 on merge |
| `ka-indexer` | Lambda, triggered by S3 events | VPC-attached to reach RDS |
| `ka-db` | RDS PostgreSQL + pgvector — **derived index** | Private subnet |
| `ka-store`, `evidence-store` | S3 (versioning on for `ka-store`) | VPC endpoint |
| Approval callback | API Gateway + Lambda | Public endpoint, signature-verified |
| Secrets | Secrets Manager | — |
| AI Dome | External (company endpoint) | HTTPS egress |

---

## 5. Component Catalog

Each component below states what it **owns**, what it **must not do**, and its **interfaces**. Clean separation is the point — if two components need the same logic, it belongs in a shared library, not duplicated.

---

### 5.1 Event Producers

| | |
|---|---|
| **Responsibility** | Detect a failure and emit a well-formed `incident.v1` event. |
| **Owns** | Failure detection logic only. |
| **Must NOT** | Contain any diagnosis, KA, or AI logic. It is a dumb emitter. |
| **Deployed** | One emitter per orchestrator: Airflow `on_failure_callback`; Control-M post-processing action or SMART alert webhook; Step Functions failure rule on EventBridge; Databricks job webhook. Plus EventBridge rules for CloudWatch alarms and Lambda for ticket webhooks. |
| **Output** | `incident.v1` JSON → SNS → SQS. |
| **Scaling note** | SNS in front of SQS so additional consumers (e.g. a metrics sink) can subscribe later without changing producers. |

---

### 5.2 `ops-assist` — Orchestrator Application

The main Python app on ECS. Internally modular; deployed as one container in v1.

| | |
|---|---|
| **Responsibility** | Own the lifecycle of an incident from queue message to closed case. |
| **Owns** | Sequencing, state, budgets, retries, and the safety tail. |
| **Must NOT** | Hold AWS permissions to mutate production (that is `ops-mcp-action`'s task role). Must not contain tool implementations. |
| **Deployed** | ECS Fargate, min 2 tasks for availability, autoscale on queue depth. |

**Internal modules** (separate classes/packages, not services):

| Module | Responsibility | Must not |
|---|---|---|
| **Consumer** | Long-poll SQS, parse, extend visibility, ack only on success | Contain business logic |
| **Dedupe/State** | Conditional put in DynamoDB; resume on redelivery | Be bypassed for "urgent" cases |
| **Context Builder** | Gather the comparative context pack (§9.1) — no AI | Call AI Dome |
| **KA Lookup** | 3-tier match (§8.2) | Execute any KA steps |
| **Step Runner** | Execute KA steps deterministically (§8.4) | Let the LLM reorder or invent steps |
| **Investigator** | Hypothesis loop for the no-KA path (§9) | Exceed turn/token caps |
| **MCP Client** | Connect to MCP servers, cache tool list, call tools | Implement tool logic |
| **Compactor** | Shrink tool outputs; write full output to S3 | Be optional — every result passes through it |
| **AI Client** | Call AI Dome; retries, timeouts, token counting | Leak raw outputs; be the only place AI Dome format lives |
| **Policy Gate** | Decide auto / approval / suggest-only | Consult the LLM |
| **Executor** | Call action tools post-gate | Call an action tool the gate did not approve |
| **Verifier** | Poll until the fix is confirmed or failed | Retry indefinitely |
| **Comms** | Update ticket + chat, draft KA | Decide anything |

---

### 5.3 `ops-mcp-read` — Read Tool Server

| | |
|---|---|
| **Responsibility** | Expose **read-only** diagnostic tools over MCP. |
| **Owns** | Tool implementations, input validation, connection pooling, per-tool timeouts. |
| **Must NOT** | Expose any tool that mutates state. Must not accept free-text SQL or shell. |
| **Tools (v1)** | `get_task_log`, `search_logs`, `get_logs_by_arn`, `s3_check`, `s3_list`, `oracle_query` (named templates only), `get_recent_runs`, `check_upstream_dag`, `get_other_failures`, `search_ka` |
| **IAM** | Read-only role: `logs:FilterLogEvents`, `s3:GetObject`/`ListBucket` on named buckets, Oracle read-only DB user. Orchestrator credentials live with each adapter (§6.6.1), not here |
| **Deployed** | ECS Fargate, internal ALB, port 8080, path `/mcp`, health `/health` |
| **Config** | `stateless_http=True` so multiple tasks work without sticky sessions |

**Oracle safety rule.** Tools accept a `sql_id` referencing a server-side named template plus bound parameters. Free-text SQL is never accepted from the LLM or from a KA.

---

### 5.4 `ops-mcp-action` — Action Tool Server

| | |
|---|---|
| **Responsibility** | Expose the small set of **state-changing** tools. |
| **Owns** | Action execution, action-level rate limiting, its own audit log. |
| **Must NOT** | Be callable by anything except `ops-assist` post-gate. Must not expose read tools. |
| **Tools (v1)** | `clear_task`, `rerun_glue_job`, `restart_service` (tier 2), `update_ticket`, `create_backlog_issue`, `link_backlog_issue`, `search_backlog_issues` |
| **IAM** | Narrow role: `glue:StartJobRun` on named jobs, ticket API credentials, Jira service-account token scoped to the tenant's project. Orchestrator write credentials live with the corresponding `ops-mcp-orch-*` adapter |
| **Deployed** | ECS Fargate, separate service, separate task role, separate SG |

Separation exists so that a bug or injection in the read path can never mutate production.

---

### 5.5 AI Dome Gateway (external)

| | |
|---|---|
| **Responsibility** | LLM inference. |
| **Consumed via** | `AIClient` — the single place in our codebase that knows AI Dome's request/response format. |
| **Assumption to verify** | Does it support **tool/function calling**? If not, fall back to prompting for strict JSON (see §16 open questions). |
| **Constraints** | Context limit `TBD`; RPM/TPM limits `TBD`. Both must be captured before load testing. |
| **Our obligations** | Enforce token budget in code; retry with backoff; never send unmasked secrets or PII. |

---

### 5.6 KA Subsystem — docs-as-code

KAs are **Markdown files owned by the application teams in Git**, deployed to S3 on merge, and indexed automatically. Teams edit the docs they already know how to edit; the platform consumes them as executable artefacts.

**Three layers, one source of truth:**

| Layer | Role | Rebuildable? |
|---|---|---|
| **Git repo (`ka-docs`)** | **Source of truth.** Human-authored `.md` files, reviewed by PR | No — this is the master |
| **`ka-store` (S3)** | Deployed artefact. Immutable, versioned objects the runtime reads | Yes, redeploy from Git |
| **`ka-db` (RDS pgvector)** | Search index + parsed executable steps | Yes, full reindex from S3 |

If Postgres is lost, reindex from S3. If S3 is lost, redeploy from Git. Nothing is authored in a database.

#### 5.6.1 `ka-docs` repository

| | |
|---|---|
| **Responsibility** | Hold every KA as a reviewable Markdown file. |
| **Owns** | Authorship, review, history, ownership via `CODEOWNERS`. |
| **Must NOT** | Contain secrets, credentials, or environment-specific endpoints. |
| **Structure** | `ka/{tenant}/{dag_or_system}/KA-0042-settlement-missing-file.md` |
| **Governance** | `CODEOWNERS` per tenant directory; PR approval required; CI must pass |

#### 5.6.2 KA CI/CD pipeline

| | |
|---|---|
| **Responsibility** | Validate, publish, and index KA changes. |
| **Deployed** | GitHub Actions / CodeBuild for validation; CodePipeline or a deploy job for S3 sync. |
| **Stages** | see §8.6 |
| **Must NOT** | Publish a KA that fails validation. A broken KA is worse than a missing one — it runs the wrong steps in production. |

#### 5.6.3 `ka-indexer` (Lambda)

| | |
|---|---|
| **Responsibility** | React to an S3 object change; parse front matter; embed symptoms; upsert into `ka-db`. |
| **Trigger** | S3 `ObjectCreated` / `ObjectRemoved` event, or manual full-reindex invocation. |
| **Owns** | Parsing, embedding generation, index consistency, `source_sha` tracking. |
| **Must NOT** | Modify the Markdown, or accept writes from anywhere except S3 events. |
| **Idempotent** | Keyed on `ka_id` + `source_sha`; replaying an event is safe. |

#### 5.6.4 Fingerprint map (DynamoDB)

`fingerprint → {ka_id, backlog_issue_key, occurrence_count, …}` for tier-1 exact match. Written at runtime, not by the pipeline — it is learned data, not authored data.

**Decision:** **No graph DB in v1.** Graphs model system dependencies — a later problem, high ops cost now. Revisit at v3.

---

### 5.7 `incident-table` (DynamoDB)

| | |
|---|---|
| **Responsibility** | Incident state, dedupe, case file index, audit trail, fingerprint map. |
| **Keys** | PK `tenant_id#incident_id`; GSI on `fingerprint`; GSI on `status` |
| **Stores** | Status, timestamps, AI decisions, actions taken, S3 pointers to evidence and prompts |
| **Must NOT** | Store large blobs — those go to `evidence-store` |

---

### 5.8 `evidence-store` (S3)

| | |
|---|---|
| **Responsibility** | Full, uncompacted tool outputs; full AI prompts and responses. |
| **Layout** | `s3://evidence/{tenant}/{incident_id}/{step_id}.json` |
| **Lifecycle** | 90 days → Glacier or delete (TBD per compliance) |
| **Why** | Auditability and debugging without bloating DynamoDB or the LLM context |

---

### 5.9 Approval Service

| | |
|---|---|
| **Responsibility** | Get a human yes/no for tier-2 actions, and resume the paused case. |
| **Flow** | Case state saved → chat message with buttons → user clicks → API Gateway + Lambda verifies signature → puts `resume` message on SQS → `ops-assist` continues |
| **Must NOT** | Execute the action itself — it only authorises. |
| **Security** | Verify the chat platform's request signature; check the approver is in the tenant's `approval_group`; expire requests after N minutes. |

---

## 6. Data Contracts

Versioned. Additive changes only within a version; breaking changes bump the version and both are supported during migration.

### 6.1 `incident.v1` — the intake contract

```json
{
  "schema": "incident.v1",
  "incident_id": "uuid",
  "tenant": "payments-team",
  "occurred_at": "2026-09-11T02:14:55Z",

  "orchestrator": {
    "type": "airflow",                 // airflow|controlm|autosys|stepfunctions|
                                       // databricks|dagster|argo|adf|none
    "instance": "mwaa-prod",           // which deployment (adapter resolves endpoint)
    "native": {                        // opaque, orchestrator-specific passthrough
      "dag_id": "settlement_eod",
      "task_id": "load_settlements",
      "run_id": "scheduled__2026-09-11T02:00:00+00:00",
      "try_number": 2
    }
  },

  "workflow": {                        // CANONICAL — what KAs match and read
    "id": "settlement_eod",            // DAG / job stream / state machine
    "unit_id": "load_settlements",     // task / job / state
    "run_id": "scheduled__2026-09-11T02:00:00+00:00",
    "attempt": 2,
    "logical_date": "2026-09-11"       // ds / ODATE / execution date
  },

  "compute": {                         // where the work actually ran, if known
    "arn": "arn:aws:glue:us-east-1:222:job/settle-load",
    "type": "glue"                     // glue|ecs|emr|lambda|databricks|k8s|onprem
  },

  "error": "FileNotFoundError: settle_2026-09-11.csv",
  "context": { "ds": "2026-09-11", "env": "prod" },
  "links": { "ticket": "INC0012345", "log_url": "..." }
}
```

**Three deliberate separations:**

| Block | Purpose | Who reads it |
|---|---|---|
| `orchestrator.native` | Whatever that scheduler calls things | The adapter only |
| `workflow` | Canonical vocabulary | KAs, fragments, matching, AI prompts |
| `compute` | The underlying execution target | Service-specific fragments |

The `workflow` / `compute` split matters: **the orchestrator and the compute layer fail independently.** Control-M triggering a Glue job and Airflow triggering the same Glue job share every compute-side diagnostic step and differ only in the orchestrator-side ones. Separating them lets a fragment be reused across both.

`orchestrator.type: "none"` is valid — for alarm-sourced or ad-hoc incidents with no scheduler involved. KAs then use only `compute.*` steps.

**Rule:** producers send this and nothing more. All enrichment happens in `ops-assist`.

### 6.2 Tool result envelope

Every MCP tool returns this shape so the Compactor can handle it generically.

```json
{
  "ok": true,
  "tool": "s3_check",
  "summary": {"exists": false, "bucket": "eod-landing", "key": "..."},
  "detail_size_bytes": 1204,
  "detail": "...full payload, may be large...",
  "elapsed_ms": 340
}
```

`summary` is small and safe to send to the LLM. `detail` goes to S3.

### 6.3 KA format — Markdown with YAML front matter

A KA is **one `.md` file**, authored and reviewed by the application team in Git.

- **Front matter (YAML)** = the machine contract. Validated in CI, parsed by the indexer, executed by the Step Runner.
- **Markdown body** = the human contract. Rendered for engineers, and excerpted into the AI synthesis prompt.

Both live in the same file so they cannot drift apart. A team updating the explanation is looking directly at the steps that run.

`ka/payments-team/settlement_eod/KA-0042-missing-input-file.md`:

````markdown
---
ka_id: KA-0042
schema: ka.v1
status: approved            # draft | approved | deprecated
owner: payments-team
title: "Settlement EOD — input file missing"

applies_to:
  tenants: ["payments-team"]
  orchestrators: ["airflow"]        # omit = any orchestrator
  workflows: ["settlement_eod"]
  units: ["load_settlements"]
  compute_types: ["glue"]           # optional, matches incident.compute.type
  error_patterns: ["FileNotFoundError.*settle_.*csv"]

symptoms: |
  Task fails immediately at the read step. Log shows FileNotFoundError
  for the daily settlement CSV in the landing bucket.

steps:
  - id: s1
    description: "Check whether today's feed file landed"
    tool: s3_check
    args: {bucket: "eod-landing", key: "settle_{{ds}}.csv"}
    on_missing: stop
  - id: s2
    description: "Check whether rows already loaded (partial run)"
    tool: oracle_query
    args: {sql_id: "settle_count_by_date", params: {ds: "{{ds}}"}}
  - id: s3
    description: "Pull errors around the failure window"
    tool: aws.cloudwatch.search_logs
    args: {arn: "{{ incident.compute.arn }}", window_min: 15}

resolution:
  if_file_missing: "Upstream feed late. Notify feed team, then clear_task."
  else: "Escalate to data engineering."

remediation:
  action: action.orch.rerun_unit     # capability — resolves per orchestrator
  tier: 1

verification:
  tool: orch.get_unit_state
  expect: success
  timeout_min: 20
---

## What this failure means

The settlement load reads a single daily CSV dropped by the upstream
feed job. When the feed is late, the read task fails immediately…

## How to confirm

1. Check the landing bucket for today's file.
2. If absent, the feed is late — this is not a settlement-side bug.
3. If present but the task still failed, check for a partial load.

## Fix

Once the file lands, clear the task and let it re-run…

## Escalation

If the feed has not landed by 04:00 UTC, page the feed team
(`#feed-ops`). SLA is 02:30 UTC.

## History

- 2026-08-14 — added the partial-load check after INC0011902.
````

**Rules:**
- **Template variables** are drawn only from `incident.v1` fields (`{{ incident.workflow.id }}`, `{{ incident.compute.arn }}`, `{{ incident.context.ds }}`, …). Arbitrary expressions are not supported — deliberately.
- `version` is **not** a front-matter field. The Git commit SHA is the version; the pipeline stamps it. Hand-maintained version numbers always go stale.
- **The Markdown body never drives execution.** Prose is advisory only; the runtime executes `steps` and nothing else. This prevents a doc edit from silently changing production behaviour and closes the prompt-injection path through KA text.

### 6.4 `agent-result.v1` — what any agent returns

```json
{
  "schema": "agent-result.v1",
  "agent": "investigator",
  "root_cause": "Upstream feed did not land before the scheduled run",
  "category": "missing_input",
  "confidence": 0.87,
  "evidence": [{"step": "s1", "finding": "s3 key absent", "s3_ref": "..."}],
  "ruled_out": ["No recent deploy", "No sibling task failures"],
  "proposed_action": {"tool": "clear_task", "args": {...}},
  "permanent_fix": {
    "is_defect": true,
    "defect_type": "missing_dependency_check",
    "owning_system": "settlement-ingest",
    "recommendation": "Add a sensor on the feed landing before the read task",
    "confidence": 0.8
  },
  "human_summary": "…3–5 sentences for the support engineer…",
  "stop": false
}
```

`proposed_action` is the **immediate** remediation. `permanent_fix` is the **engineering** recommendation and feeds §9.7. Both are proposals only.

`proposed_action` is a **proposal**. It is meaningless until the policy gate approves it.

### 6.5 Tenant config contract

See §12.2.

---

### 6.6 Tool registry and naming taxonomy

Every tool has a **namespaced, versioned name**: `{domain}.{service}.{verb}`. Flat names like `search_logs` become ambiguous the moment a second team adds a second log source.

| Domain | Service | Example tools |
|---|---|---|
| `aws` | `cloudwatch` | `aws.cloudwatch.search_logs`, `aws.cloudwatch.get_log_stream`, `aws.cloudwatch.get_metric` |
| `aws` | `s3` | `aws.s3.object_exists`, `aws.s3.list_prefix`, `aws.s3.head_object`, `aws.s3.peek_csv` |
| `aws` | `glue` | `aws.glue.get_job_run`, `aws.glue.get_run_errors` |
| `aws` | `ecs` | `aws.ecs.get_task_status`, `aws.ecs.get_stopped_reason`, `aws.ecs.get_container_metrics` |
| `aws` | `rds` | `aws.rds.get_instance_status`, `aws.rds.get_connection_count` |
| `db` | `oracle` | `db.oracle.query` (named `sql_id` only), `db.oracle.check_locks` |
| `orch` | *(capability — see §6.6.1)* | `orch.get_unit_log`, `orch.get_unit_state`, `orch.get_recent_runs`, `orch.get_upstream_state`, `orch.get_sibling_states`, `orch.get_run_summary` |
| `ka` | — | `ka.search` |
| `ticket` | `jira` | `ticket.jira.create_issue`, `ticket.jira.search` |
| `action` | `orch` | `action.orch.rerun_unit`, `action.orch.rerun_workflow`, `action.orch.mark_success` |
| `action` | `glue` | `action.glue.rerun_job` |

**Rules that make this work:**

- **`action.*` is the only write prefix.** Anything else is read-only by definition, enforced by which MCP server hosts it. CI rejects a KA that puts an `action.*` tool in `steps` (§8.6) — actions belong only in `remediation`.
- **The registry is the contract.** Each tool publishes `{name, version, input_schema, output_schema, risk_tier, owning_team, timeout_default}` as a versioned JSON artefact. CI validates KAs against it; the AI receives the same definitions.
- **Names are stable.** Renaming requires a deprecation period where both names resolve, because KAs in Git reference them by name.
- **Capability tools resolve at runtime.** `orch.*` and `action.orch.*` are **capabilities, not implementations**. The runner resolves each to a concrete adapter using `incident.orchestrator.type`. A KA written against `orch.get_unit_log` works unchanged on Airflow, Control-M or Step Functions. This was deferred to v2 in an earlier draft; orchestrator neutrality makes it a v1 requirement.
- **Service tools stay concrete.** `aws.s3.*`, `db.oracle.*` name real implementations. Abstracting those buys nothing today, and a fake abstraction is worse than none.

---

### 6.6.1 Orchestrator adapter contract

Each orchestrator is supported by implementing one interface. Adding Control-M is **writing an adapter, not touching the core**.

**Required capabilities** — an adapter must implement all of these:

| Capability | Returns | Airflow | Control-M | Step Functions |
|---|---|---|---|---|
| `orch.get_unit_log` | Log text for the failing unit | Task log via REST | Job SYSOUT/output | Execution history + CW log |
| `orch.get_unit_state` | `success`/`failed`/`running`/`queued` | Task instance state | Job status | Execution status |
| `orch.get_recent_runs` | Last N runs with state + duration | DAG runs, filtered by task | Job order history | `ListExecutions` |
| `orch.get_upstream_state` | Immediate predecessors and their states | Upstream task instances | In-conditions / predecessors | Previous states |
| `orch.get_sibling_states` | Other units in the same run | Task instances in the DAG run | Jobs in the same stream | States in the execution |
| `orch.get_run_summary` | Run-level state, start, duration | DAG run | Order | Execution |

**Optional capabilities** — declared by the adapter; a KA step referencing an unsupported one is a CI error for tenants on that orchestrator:

| Capability | Notes |
|---|---|
| `orch.get_blast_radius` | Other workflows failing now. Trivial in Airflow, expensive in some schedulers |
| `orch.get_config_history` | Recent definition changes |
| `action.orch.rerun_unit` | Airflow `clear_task`, Control-M rerun, SFN redrive |
| `action.orch.rerun_workflow` | Whole-run restart |
| `action.orch.mark_success` | Tier 3 — dangerous, usually disabled |

**Adapter responsibilities:**

- **Translate vocabulary.** Map `workflow.id` / `unit_id` / `run_id` onto whatever the scheduler calls them, using `orchestrator.native` for anything that does not map cleanly.
- **Normalise state.** Return the canonical state set, never the native strings (`ENDED_NOT_OK`, `ABEND`, `up_for_retry`).
- **Declare capabilities.** Publish a manifest so CI can validate KAs per orchestrator.
- **Own authentication.** Token, mTLS, gateway — the core never learns how a given scheduler authenticates.

**Deployment.** Each adapter is an MCP server (§5.3) — `ops-mcp-orch-airflow`, `ops-mcp-orch-controlm` — registered per tenant. A tenant on two orchestrators registers two, and the runner picks per incident. An on-prem scheduler runs its adapter on-prem and is reached over the existing network path; no other component changes.

**Capability gap handling.** If a KA needs a capability the tenant's adapter lacks, the step is `skipped` with `status: unsupported`, and the AI is told which signal was unavailable. Degraded is better than failed.

---

### 6.7 The step object — full schema

This is the heart of the design. It must be expressive enough that the **deterministic runner** handles the common cases without AI, and simple enough that CI can fully validate it.

```yaml
- id: s2                                  # unique within the KA
  description: "Check whether the feed file landed"   # shown to humans
  tool: aws.s3.object_exists
  args:
    bucket: "eod-landing"
    key: "settle_{{ ds }}.csv"

  # ---- optional fields below ----

  capture:                                # bind outputs for later steps
    file_size: "$.summary.size_bytes"     # JSONPath into the tool result
    landed_at: "$.summary.last_modified"

  expect:                                 # deterministic pass/fail test
    field: "$.summary.exists"
    op: eq                                # eq|ne|gt|lt|gte|lte|contains|matches|exists
    value: true

  on_pass: continue                       # continue | stop | goto:<step_id>
  on_fail:
    verdict: "upstream_feed_missing"      # feeds the conclusion table
    action: stop

  when: "{{ steps.s1.verdict != 'partial_load' }}"   # conditional execution
  optional: false                         # true = failure does not fail the KA
  timeout_sec: 30
  retry: {attempts: 2, backoff_sec: 5}
  redact: ["$.detail.rows[*].account_no"] # mask before it leaves the tool
```

**Why each field earns its place:**

| Field | Without it |
|---|---|
| `capture` | Steps can't chain — you couldn't take a Glue `run_id` from the orchestrator log and use it to fetch Glue errors |
| `expect` | The runner can't tell pass from fail, so every step needs an AI call to interpret |
| `on_fail.verdict` | No deterministic path from findings to a conclusion |
| `when` | No branching; every KA becomes a straight line and you need one KA per variation |
| `optional` | A flaky non-essential check aborts the whole diagnosis |
| `redact` | Sensitive columns reach the LLM |

**`{{ }}` resolution scope** is closed and validated at CI time: `{{ incident.* }}` (from `incident.v1`), `{{ steps.<id>.<captured_var> }}`, `{{ tenant.<config_key> }}`. Nothing else. No arithmetic, no function calls, no arbitrary expressions — deliberately, because the template engine is a code-execution surface reachable from a document.

### 6.8 Conclusion mapping — deterministic where possible

The KA ends with a table from verdicts to outcomes, so the common cases need no AI at all:

```yaml
conclusions:
  - when_verdict: upstream_feed_missing
    root_cause: "Upstream settlement feed has not landed"
    severity: medium
    remediation: {action: action.orch.rerun_unit, tier: 1,
                  precondition: "file present"}
    notify: ["#feed-ops"]
    human_summary: |
      The daily settlement CSV for {{ incident.context.ds }} is absent from
      s3://eod-landing. This is an upstream feed delay, not a settlement bug.
  - when_verdict: partial_load
    root_cause: "Run failed mid-load; rows already present"
    severity: high
    remediation: {action: none}
    escalate_to: "data-engineering"
  - default:
      escalate_to_ai: true      # unmatched → hand the evidence to AI Dome
```

**This is the biggest cost and latency saver.** A matched verdict produces a full answer with **zero LLM calls**. AI Dome is invoked only for the `default` branch, where the KA's authors did not anticipate the shape of the failure.

---

## 7. End-to-End Flow

### 7.1 Startup (once per container)
1. Load secrets from Secrets Manager.
2. Open MCP sessions to `ops-mcp-read` and `ops-mcp-action`.
3. Call `tools/list`; cache tool definitions converted to AI Dome's format.
4. Refresh cache every N minutes, and on any "tool not found" error.
5. Load tenant configs.

### 7.2 Intake
6. Producer publishes `incident.v1` → SNS → SQS.
7. Consumer long-polls, receives, validates against schema. Invalid → DLQ.
8. Compute fingerprint. Conditional put to DynamoDB.
   - Already `INVESTIGATING` or `CLOSED` → link and ack (dedupe).
   - Same fingerprint seen ≥3× in the last hour → attach to the existing case (investigation caching).
9. Status → `INVESTIGATING`. Start visibility-extension heartbeat.

### 7.3 Context (no AI)
10. Context Builder gathers the context pack (§9.1) via read tools.
11. Rule-based short circuits run first — e.g. ≥N other DAGs failing in the same window → classify as `platform_incident`, notify, skip AI investigation.

### 7.4 KA lookup
12. Tier 1 — exact fingerprint → KA.
13. Tier 2 — hybrid keyword + vector search in `ka-db`, filtered by tenant, `orchestrator.type` and `workflow.id`.
14. Tier 3 — one small AI Dome call to verify a candidate genuinely matches the **root cause**, not just the symptom.
15. Branch: KA found (§7.5) or not — in which case **`KA-0000` (§8A.3) runs first**, and only its `default` conclusion escalates to the AI path (§7.6).

### 7.5 KA path — deterministic
16. Step Runner executes `steps` in order. No LLM planning.
17. Each result → Compactor → evidence list; full detail → `evidence-store`.
18. `on_missing: stop` short-circuits when a step is already conclusive.
19. Match verdicts against the `conclusions` table (§6.8). A matched verdict yields the full result with **zero AI calls**. Only the `default` branch makes one AI Dome synthesis call: KA text + compacted evidence → `agent-result.v1`.

### 7.6 No-KA path — AI investigation
20. Classify into a category (one cheap AI call).
21. Select the category's starting toolset.
22. Hypothesis loop (§9.3), capped at ~6 turns and a token budget.
23. Conclude — or return an honest partial result with `ruled_out`.

### 7.7 Decision and action (always runs)
24. Policy Gate evaluates `proposed_action` (§11).
25. **auto_execute** → Executor calls `ops-mcp-action`.
    **needs_approval** → save state, post approval request, ack SQS, exit. Resume on callback.
    **suggest_only** → skip execution.
26. Verifier polls until success/failure/timeout. Repeat failure → escalate, do not loop.
27. Comms updates the ticket and chat with the summary, evidence links, and action taken.
28. **Learn — KA.** If resolved with no KA → generate a Markdown KA and **open a pull request** against `ka-docs` for the owning team (§9.6).
29. **Learn — permanent fix.** Evaluate the backlog trigger rules (§9.7). If they fire, create or update a Jira backlog issue and link it to the incident and the KA.
30. Write the full audit record. **Then** delete the SQS message.

---

## 8. KA Subsystem Detail

### 8.1 Signature normalisation
Strip timestamps, UUIDs, S3 URIs, and numbers from the error text. Produces a stable string across runs; hashed with the task key into a fingerprint.

### 8.2 Three-tier matching

| Tier | Method | Store | Cost | Trust |
|---|---|---|---|---|
| 1 | Exact fingerprint | DynamoDB | ~0 | Highest — tier-1 actions may auto-run |
| 2 | Hybrid keyword + vector | RDS pgvector | Low | Candidate only |
| 3 | AI verification of top-3 | AI Dome | 1 small call | Auto-run only after shadow-mode proves reliability |

No match → `weak`; proceed to investigation but **pass the near-miss candidates as hints**.

### 8.3 Storage schema

`ka-db` is a **derived index**, never authored directly. Every row traces back to a Git commit.

```sql
CREATE TABLE ka (
  ka_id          text PRIMARY KEY,
  tenant_id      text NOT NULL,
  status         text NOT NULL,          -- draft|approved|deprecated
  title          text,
  -- provenance: where this row came from
  git_path       text NOT NULL,          -- ka/payments-team/.../KA-0042-*.md
  git_sha        text NOT NULL,          -- commit that produced it
  s3_uri         text NOT NULL,          -- deployed artefact
  source_sha     text NOT NULL,          -- sha256 of file content
  indexed_at     timestamptz NOT NULL,
  -- parsed front matter
  orchestrators  text[],                 -- empty = any
  workflow_ids   text[],
  unit_ids       text[],
  compute_types  text[],
  error_patterns text[],
  symptoms       text,                   -- embedded & searched
  steps          jsonb,                  -- executable
  remediation    jsonb,
  verification   jsonb,
  body_excerpt   text,                   -- trimmed MD body for AI synthesis
  embedding      vector(1024),
  -- learned at runtime (NOT from git)
  hit_count      int DEFAULT 0,
  success_count  int DEFAULT 0
);
CREATE INDEX ON ka USING gin (to_tsvector('english', symptoms));
CREATE INDEX ON ka USING hnsw (embedding vector_cosine_ops);
```

Embeddings: AI Dome embeddings endpoint if available, else Bedrock Titan Text Embeddings v2. Re-embed only when `source_sha` changes, so a docs-only reword does not trigger a full re-embed of the corpus.

`hit_count` and `success_count` are **runtime counters** and must survive reindexing — the indexer upserts and preserves them rather than replacing the row wholesale.

### 8.4 Step Runner rules

The runner is **plain code, no LLM**. It is the component that must be boringly correct.

**Execution:**
- Steps run **in declared order**; `goto` may only jump **forward**, never backward (guarantees termination — no loops, by construction).
- `when` is evaluated before the call; a skipped step records `status: skipped`, not a failure.
- `capture` binds outputs into the step context for later `args` and `when` expressions.
- `expect` produces `pass` / `fail`; absence of `expect` means the step is informational (`gather`) and always passes if the call succeeded.
- A tool **error** (timeout, 5xx) is distinct from an `expect` **failure**. Errors honour `retry`, then `optional`; failures go straight to `on_fail`.

**Guards:**
- Only tools in the tenant's allowlist, and only non-`action.*` tools, may appear in `steps`.
- Per-step `timeout_sec` (registry default if unset); total KA-path wall-clock budget enforced.
- Max steps per KA (default 15) — a longer diagnosis belongs in the AI path.
- Every result passes through the Compactor before it is retained or sent anywhere.

**Outcome:**
- Collected verdicts are matched against `conclusions` in order; first match wins.
- No match → `default` → escalate to AI with the gathered evidence, which is still far richer than a cold start.

#### 8.4.1 Worked trace — how the runner decides without AI

> The most commonly misread part of this design. The decision is made **at authoring time**, not at runtime. The author already knows what "conclusive" looks like and writes it down as a comparison; the runner only evaluates that comparison.

Given this step:

```yaml
- id: s1
  tool: aws.s3.object_exists
  args: {bucket: "eod-landing", key: "settle_{{ incident.context.ds }}.csv"}
  expect:
    field: "$.summary.exists"     # where to look in the result
    op: eq                        # how to compare
    value: true                   # what "good" means
  on_pass: continue               # file present → keep digging
  on_fail:                        # file missing → we are done
    verdict: input_missing
    action: stop
```

The tool returns:

```json
{"ok": true, "tool": "aws.s3.object_exists",
 "summary": {"exists": false, "bucket": "eod-landing",
             "key": "settle_2026-09-11.csv"}}
```

The runner then:

1. Reads `$.summary.exists` → `false`
2. Evaluates `false eq true` → **fail**
3. Reads `on_fail` → records `verdict: input_missing`, `action: stop`
4. Stops — steps 2 and 3 never execute
5. Matches `input_missing` against `conclusions` → root cause, remediation, human summary, all pre-written

**Total AI calls: zero.** Three literal values — a JSONPath, an operator, an expected value — are all the runner needs.

**Reference implementation:**

```python
def run_ka(ka, incident):
    ctx, verdicts = {"incident": incident, "steps": {}}, []

    for step in ka.steps:
        if step.when and not evaluate(step.when, ctx):
            ctx["steps"][step.id] = {"status": "skipped"}
            continue

        result = mcp.call(step.tool, render(step.args, ctx))
        ctx["steps"][step.id] = extract(step.capture, result)

        if not step.expect:                    # informational step
            continue

        actual = jsonpath(result, step.expect.field)
        passed = COMPARE[step.expect.op](actual, step.expect.value)
        branch = step.on_pass if passed else step.on_fail

        if branch.verdict:
            verdicts.append(branch.verdict)
        if branch.action == "stop":
            break
        if branch.action.startswith("goto:"):
            jump_to(branch.action.split(":")[1])   # forward only

    return match_conclusion(ka.conclusions, verdicts)   # first match wins

COMPARE = {
    "eq":       lambda a, b: a == b,
    "ne":       lambda a, b: a != b,
    "gte":      lambda a, b: a >= b,
    "lte":      lambda a, b: a <= b,
    "contains": lambda a, b: b in (a or ""),
    "matches":  lambda a, b: re.search(b, a or "") is not None,
    "exists":   lambda a, b: (a is not None) == b,
}
```

`COMPARE` is the entire decision engine — a dictionary of one-line lambdas.

**The three ways to skip a step:**

| Situation | Mechanism |
|---|---|
| Step 1 is conclusive; nothing else matters | `on_fail: {action: stop}` |
| Step 3 only makes sense given a certain step 1 result | `when: "{{ steps.s1.verdict == 'ok' }}"` on step 3 |
| Step 1 means jump ahead to step 5 | `on_fail: {action: "goto:s5"}` |

**The honest limit.** This works because the author *anticipated* the outcome. Anything unanticipated falls to the `default` conclusion and goes to AI with the evidence already gathered. That split is the whole design: the KA handles known shapes cheaply and deterministically; the AI handles shapes nobody predicted.

Put another way — **the intelligence was spent once, when the KA was written.** Running it afterwards is executing a decision already made. That is why the KA path costs near-zero and completes in seconds.

### 8.5 KA lifecycle — everything is a pull request

```
Author (human or AI-drafted PR)
      │
      ▼
  PR in ka-docs  ──► CI validation (§8.6)  ──► fail = blocked
      │
      ▼  CODEOWNERS approval + merge
  Deploy job ──► S3 (ka-store), versioned
      │
      ▼  S3 event
  ka-indexer (Lambda) ──► parse, embed, upsert ka-db
      │
      ▼
  ops-assist cache refresh ──► live
```

- **AI-drafted KAs open a pull request**, they do not write to the database. The team reviews a Markdown diff in the tool they already use. This replaces the earlier "`status: draft` row in Postgres" design.
- `status: draft` in front matter means indexed and searchable but **never auto-executes actions** — useful for a KA that is still being proven.
- **Deprecation** is a front-matter change plus a PR, so the reason lives in the commit message.
- Track `success_count / hit_count`; a KA whose fix keeps failing is auto-flagged, and the platform **opens a PR** adding a `⚠️ needs review` marker rather than silently disabling it.

### 8.6 CI validation — the safety net

A malformed KA is more dangerous than a missing one: it runs the wrong steps against production. CI must block on all of these.

| Check | Why |
|---|---|
| Front matter parses; `schema: ka.v1` present | Basic integrity |
| `ka_id` unique across the repo; matches the filename | Prevents collisions and confusion |
| Every `tool` name exists in the live tool registry | Catches renamed/removed tools at PR time, not 03:00 |
| Every `args` payload validates against that tool's JSON Schema | Wrong argument shapes fail fast |
| All tools referenced are **read-only**, except `remediation.action` | A KA must not sneak an action into the diagnostic steps |
| `remediation.action` is in the tenant's `auto_actions` allowlist, and its declared `tier` matches the **platform registry** | Prevents a doc edit from escalating its own privileges |
| Template variables resolve against `incident.v1` fields | No `{{typo}}` at runtime |
| `oracle_query` uses only registered `sql_id` values | No free-text SQL, ever |
| `error_patterns` are valid regex, and compile within a time bound | Prevents catastrophic backtracking |
| No secrets, credentials, or hardcoded environment endpoints | Repo hygiene |
| Dry-run against a recorded fixture incident | Proves the steps actually execute |

**Tool-registry sync.** The registry is published by the MCP servers as a versioned JSON artefact that CI fetches. Nightly, the pipeline re-validates the whole corpus against the current registry and opens an issue for any KA that has drifted — so a tool removal surfaces as a ticket, not an outage.

### 8.7 Deployment and consistency

- **S3 layout:** `s3://ka-store/{tenant}/{ka_id}.md`, bucket versioning on, plus `s3://ka-store/_manifest.json` listing every `ka_id` with its `source_sha`.
- **Deletion:** removing a file from Git tombstones the S3 object and marks the row `deprecated`. The indexer must never hard-delete, so a mistaken removal is recoverable.
- **Cache in `ops-assist`:** KA content is fetched from `ka-db`, not S3, at runtime. TTL of a few minutes, with an invalidation ping on index update.
- **Drift reconciliation:** a scheduled job compares `_manifest.json` against `ka-db`; any mismatch triggers a targeted reindex and an alarm. Silent index drift is the failure mode that makes people stop trusting the system.
- **Environments:** `ka-docs` deploys to a non-prod S3 prefix first. Promotion to prod is the merge to `main`. Same artefact, no re-authoring.
- **Rollback:** revert the commit. The pipeline redeploys the previous content, and the indexer restores the previous parsed steps.

---

## 8A. KA Template Model

The goal: an application team should assemble a KA from **known building blocks**, not invent a diagnostic sequence from scratch. Three mechanisms.

### 8A.1 Fragments — reusable step blocks

Standard diagnostic sequences live once, in `ka/_fragments/`, and are included by reference. Each fragment is a Markdown file with the same front-matter shape, exposing `params` and `verdicts`.

`ka/_fragments/aws-s3-input-check.md`:

```yaml
fragment_id: frag.aws.s3.input_check
params: [bucket, key_template, min_size_bytes]
steps:
  - id: exists
    description: "Does the expected object exist?"
    tool: aws.s3.object_exists
    args: {bucket: "{{ params.bucket }}", key: "{{ params.key_template }}"}
    capture: {size: "$.summary.size_bytes"}
    expect: {field: "$.summary.exists", op: eq, value: true}
    on_fail: {verdict: input_missing, action: stop}
  - id: size_sane
    description: "Is it plausibly complete, not a zero-byte stub?"
    tool: aws.s3.head_object
    args: {bucket: "{{ params.bucket }}", key: "{{ params.key_template }}"}
    expect: {field: "$.summary.size_bytes", op: gte,
             value: "{{ params.min_size_bytes }}"}
    on_fail: {verdict: input_truncated, action: stop}
verdicts: [input_missing, input_truncated, ok]
```

Used in a KA:

```yaml
steps:
  - include: frag.aws.s3.input_check
    as: input
    params: {bucket: "eod-landing", key_template: "settle_{{ incident.context.ds }}.csv",
             min_size_bytes: 1024}
  - id: s2
    when: "{{ steps.input.verdict == 'ok' }}"
    tool: db.oracle.query
    ...
```

**Starter fragment library** (v1):

| Fragment | Answers |
|---|---|
| `frag.orch.triage` | Unit state, duration vs baseline, attempt number, last success |
| `frag.orch.upstream` | Did an upstream unit or workflow fail first? |
| `frag.orch.blast_radius` | Are sibling units or other workflows failing too? |
| `frag.aws.s3.input_check` | Did the expected input land, and is it complete? |
| `frag.aws.cloudwatch.error_scan` | Deduped errors in the failure window for an ARN |
| `frag.aws.glue.run_detail` | Glue run state, error string, worker/DPU limits |
| `frag.aws.ecs.stop_reason` | OOM kill, exit code, task placement failure |
| `frag.db.oracle.load_state` | Row counts, partial load, watermark position |
| `frag.db.oracle.contention` | Blocking sessions and locks in the run window |
| `frag.aws.rds.health` | Instance status, connection saturation, failover events |

Fragments are versioned and CI-validated like any KA, and changing one is a PR that is reviewed by the platform team because it affects every KA that includes it.

### 8A.2 Families — templates by failure shape

Most failures fall into a handful of shapes. `ka/_templates/` holds a skeleton per family, so authoring starts from a filled-in example rather than an empty file.

| Family | Typical sequence | Usual remediation |
|---|---|---|
| `missing_input` | orch triage → s3 input check → upstream | Wait + `rerun_unit` |
| `data_quality` | orch triage → oracle load state → error scan | Escalate to data eng |
| `infra_timeout` | orch triage → error scan → rds health → contention | Retry once, else escalate |
| `permissions` | error scan → recent IAM/config change | Escalate, never auto-fix |
| `resource_limit` | ecs stop reason → container metrics → error scan | Resize (tier 2) |
| `upstream_outage` | upstream → blast radius | Notify, wait |
| `code_error` | orch triage → unit log → recent deploys | Escalate to owning team |
| `transient` | recent runs → error scan | `rerun_unit`, max 1 |

A new KA typically = pick a family template, adjust fragment params, write the conclusions table, write the prose. That is the "standard template model" — the sequence is not reinvented, only parameterised.

### 8A.3 `KA-0000` — the default playbook

> Your point about having a defined KA even when no KA matches is important enough to be a first-class artefact.

`ka/_default/KA-0000-generic-triage.md` runs **before** the AI investigation whenever tier-1/2/3 matching returns nothing. It is a KA in every respect — same format, same validation, same review process — but it is selected by fallback rather than by match.

```yaml
ka_id: KA-0000
schema: ka.v1
status: approved
title: "Generic triage — no specific KA matched"
kind: default_playbook
applies_to: {always: true}    # any tenant, any orchestrator, any compute

steps:
  # Phase 1 — orchestrator-agnostic, runs via whichever adapter is registered
  - include: frag.orch.triage
    as: triage
  - include: frag.orch.upstream
    as: upstream
  - include: frag.orch.blast_radius
    as: blast

  # Phase 2 — compute-type dispatch (independent of orchestrator)
  - include: frag.aws.glue.run_detail
    as: svc
    when: "{{ incident.compute.type == 'glue' }}"
  - include: frag.aws.ecs.stop_reason
    as: svc
    when: "{{ incident.compute.type == 'ecs' }}"
  - include: frag.aws.cloudwatch.error_scan
    as: errors
    when: "{{ incident.compute.arn != null }}"
    params: {arn: "{{ incident.compute.arn }}", window_min: 15}

conclusions:
  - when_verdict: upstream_failed
    root_cause: "Upstream dependency failed first"
    remediation: {action: none}
    notify: ["{{ tenant.notify }}"]
  - when_verdict: widespread_failure
    root_cause: "Platform-level incident — multiple unrelated DAGs failing"
    severity: high
    escalate_to: "platform-oncall"
  - default:
      escalate_to_ai: true        # hand the full context pack to §9
```

**Why this matters:**
- Two of the most common causes — an upstream failure and a platform-wide incident — are resolved **deterministically, with no AI call at all**.
- When it does reach AI, the AI starts with a complete context pack rather than a raw error string, which is the single biggest driver of diagnosis quality (§9.1).
- The context pack in §9.1 is *implemented as* this playbook, rather than as hand-written Python. One mechanism, one validator, one thing to maintain.
- Teams can override it: a tenant may set `ka.default_playbook: KA-0000-payments` to add their own standard checks.

### 8A.4 Authoring template

`ka/_templates/TEMPLATE.md` — what a team copies to start:

```markdown
---
ka_id: KA-XXXX                    # allocate next free; CI enforces uniqueness
schema: ka.v1
status: draft                     # flip to approved once proven
owner: <team>
title: "<system> — <one-line symptom>"
family: missing_input             # see §8A.2

applies_to:
  tenants: ["<team>"]
  orchestrators: ["<airflow|controlm|...>"]   # omit = any orchestrator
  workflows: ["<workflow_id>"]
  units: ["<unit_id>"]            # omit to match the whole workflow
  compute_types: ["<glue|ecs|...>"]           # optional
  error_patterns:
    - "<regex against the normalised error signature>"

symptoms: |
  <2–4 lines. This text is embedded for semantic search — describe the
  observable symptom, not the fix.>

steps:
  - include: frag.orch.triage
    as: triage
  # add fragments / custom steps here

conclusions:
  - when_verdict: <verdict>
    root_cause: "<plain statement>"
    severity: low|medium|high
    remediation: {action: <action.* or none>, tier: <0-3>}
    human_summary: |
      <what the on-call engineer needs to know, 3–5 sentences>
  - default:
      escalate_to_ai: true
---

## What this failure means
## How to confirm
## Fix
## Escalation
## History
```

### 8A.5 Design rules for KA authors

1. **One KA per root cause, not per symptom.** Two causes sharing a symptom need two KAs and a discriminating step.
2. **Prefer fragments to bespoke steps.** Bespoke steps are unreviewed diagnostic logic.
3. **Order steps cheapest-and-most-decisive first.** An `s3.object_exists` that ends the diagnosis should never sit behind a 30-second log scan.
4. **Every step must be able to change the outcome.** If a step's result never affects a verdict or a `when`, delete it — it is cost without value.
5. **Write the `default` conclusion as if the author was wrong,** because eventually they will be.
6. **Never put an `action.*` tool in `steps`.** Remediation belongs in `conclusions`, behind the policy gate. CI enforces this.

### 9.1 Context pack — gathered deterministically, before any AI

**Implemented as `KA-0000`, the default playbook (§8A.3)** — not as hand-written Python. Same format, same CI validation, same review process as any other KA, which means teams can extend it by PR.

| Signal | Question answered |
|---|---|
| Last successful run of this task | Newly broken, or never worked? |
| Last 7 runs | Flaky vs hard break |
| Sibling task status in the DAG | One task or whole DAG? |
| Upstream DAG status | Dependency failed first? |
| Other DAGs failing in last 30 min | Platform-wide vs isolated |
| Duration vs baseline | Timeout vs instant failure |
| Recent deploys / config changes | Did something change today? |
| Compacted error + stack trace | The actual exception |

This is the highest-value part of the design. Much of the diagnosis is in the comparison, not the log.

### 9.2 Category-guided planning

One cheap AI call assigns a category; the category selects the starting toolset. The AI may still request other tools during the loop.

```python
CATEGORY_PLANS = {
  "missing_input":   ["s3_check", "check_upstream_dag"],
  "data_quality":    ["oracle_query", "search_logs"],
  "infra_timeout":   ["search_logs", "check_service_health"],
  "permissions":     ["search_logs", "check_iam_recent_changes"],
  "code_error":      ["get_task_log", "check_recent_deploys"],
  "resource_limit":  ["get_container_metrics", "search_logs"],
  "upstream_outage": ["check_upstream_dag", "get_other_failures"],
  "unknown":         ["search_logs", "get_task_log"],
}
```

### 9.3 Hypothesis loop

The AI must state a hypothesis and a prediction **before** seeing the result:

```json
{
  "hypothesis": "Upstream feed did not land",
  "confidence": 0.6,
  "next": {"tool": "s3_check",
           "args": {"bucket": "eod-landing", "key": "settle_{{ds}}.csv"},
           "expect_if_true": "exists=false"}
}
```

Logging whether `expect_if_true` held gives a free quality signal.

### 9.4 Loop guards
- Max ~6 turns.
- Per-incident token budget.
- **No repeated calls** — identical tool+args returns the cached result with a note. (Most common failure mode.)
- Wall-clock cap (~5 min) → force conclusion and escalate.

### 9.5 Honest uncertainty
`root_cause: null` plus `ruled_out` and `narrowed_to` is a valid, useful result. Routed to a human; never auto-executed; ticket marked "partial analysis".

### 9.6 Closing the loop — two outputs

> Applies to **both** the KA path and the no-KA path. Runs as part of the fixed safety tail (§3.8).

Every resolved incident produces up to two artefacts:

| Output | Purpose | Destination | Trigger |
|---|---|---|---|
| **KA pull request** | Make the next occurrence cheap | `ka-docs` Git repo | Resolved with no existing KA |
| **Backlog issue** | Make the next occurrence *not happen* | Jira | Root cause is a defect **and** trigger rules fire (§9.7) |

**KA flow.** Resolved-with-no-KA → AI generates a Markdown file in the §6.3 format → the platform opens a **pull request** on `ka-docs` in the owning team's directory → CI validates it (§8.6) → `CODEOWNERS` review and merge → deploy → index → fingerprint written to DynamoDB → next occurrence is tier 1.

Why a PR rather than a database draft:
- The team reviews a **Markdown diff**, in the workflow they already use. Adoption is the hard part, and this removes the "log into another tool" barrier.
- CI validates the generated steps **before** anyone can approve them, so a plausible-but-wrong AI draft is caught mechanically.
- Authorship, review, and history are recorded by Git for free.

Generated PRs are labelled `ops-assist-generated`, capped per tenant per day, and the PR body links the incident, the evidence in S3, and the case file. Track "no-KA investigations per DAG" to prioritise KA authoring.

### 9.7 Permanent fix — backlog issue generation

#### 9.7.1 The problem this solves
Without this step, a highly effective agent is a *hazard*: it silently absorbs a recurring defect, the pain disappears from the engineers' view, and nobody ever fixes the cause. The backlog ticket is how the toil stays visible.

#### 9.7.2 Not every incident earns a ticket
Raising a Jira per failure produces backlog spam and the feature gets switched off. Classify first:

| Root-cause class | Backlog ticket? |
|---|---|
| **Systemic defect** — missing sensor, no retry, unhandled edge case, hardcoded value, missing validation | **Yes** |
| **Chronic external** — upstream feed habitually late | **Yes**, against the contract/SLA owner |
| **Capacity / config drift** — undersized memory, expiring credential | **Yes** |
| **Transient infra** — one-off network blip, spot reclaim | **No**, unless recurring |
| **Genuine data anomaly** — real bad record from source | **No**, route to data stewardship |
| **Human/operational** — manual step missed | Only if automatable |

#### 9.7.3 Trigger rules (deterministic — the AI advises, code decides)

Create a backlog issue when **all** hold:
1. `permanent_fix.is_defect == true` and `confidence >= 0.7`, **or** a human marked it so via feedback.
2. Occurrence count for this fingerprint `>= N` in the trailing window (default **3 in 30 days**; `1` for classes marked always-actionable, such as missing validation).
3. No **open** backlog issue already linked to this fingerprint.
4. The tenant has `backlog.enabled: true` and a target project configured.

If an open issue **already exists**: do not create a duplicate. Increment its occurrence counter, append the new incident link and evidence, update the toil cost, and re-evaluate priority.

#### 9.7.4 Deduplication and linking
The fingerprint is the join key across all three systems:

```
fingerprint ──┬──► KA            (how to handle it)
              ├──► incidents[]   (each occurrence)
              └──► backlog issue (how to end it)
```

Stored in DynamoDB as `fingerprint → {ka_id, backlog_issue_key, occurrence_count, first_seen, last_seen, toil_minutes, ai_cost}`. `search_backlog_issues` is used as a secondary check against tickets a human raised manually.

#### 9.7.5 Ticket content
Drafted by AI Dome from the case file, then rendered by a **code template** — the AI supplies fields, not free-form markup.

```yaml
project: "{{tenant.backlog.project}}"
issue_type: Bug            # or Task for capacity/config
summary: "[ops-assist] settlement_eod.load_settlements fails when
          upstream feed lands late (12 occurrences, 30d)"
labels: [ops-assist-generated, auto-triage, needs-triage]
priority: derived-from-toil-and-severity
fields:
  occurrences_30d: 12
  first_seen / last_seen
  toil_cost: "≈4.5 engineer-hours; $18 AI spend"
  root_cause: "…from agent-result…"
  evidence: [links to S3 + incident tickets]
  current_workaround: "KA-0042 — clear_task after feed lands"
  recommended_fix: "Add ExternalTaskSensor / S3KeySensor before read"
  owning_system: settlement-ingest
  confidence: 0.8
```

Rules:
- **Always labelled as machine-generated** and created in a triage state, never assigned directly to a person.
- **Never auto-closed** by the platform. Humans close backlog issues.
- Links to the KA, the incident tickets, and the S3 evidence.
- `recommended_fix` is explicitly advisory — engineers decide the real design.

#### 9.7.6 Autonomy and safety
- `create_backlog_issue` is **tier 1** (low blast radius, reversible) but rate-limited: default **max 5 per tenant per day**.
- Restricted to the tenant's configured project; cannot write to arbitrary projects.
- Priority is computed by **code** from occurrence count, toil cost, and incident severity — not chosen by the LLM.
- Tenants may set `backlog.mode: suggest_only`, where the platform posts a draft to chat and a human clicks to create it. Recommended default for the first month.

#### 9.7.7 Feedback into prioritisation
Every subsequent occurrence updates the issue's counters, so the backlog is **self-ranking**: the defects costing the most toil rise naturally. When the issue is closed as fixed, mark the KA `deprecated` after a configurable quiet period with no recurrence — and if it recurs, reopen and flag the fix as ineffective.

---

## 10. Context and Token Management

### 10.1 Compaction rules

| Tool | Raw | Sent to LLM |
|---|---|---|
| `search_logs` | 50k lines | ~15 deduped groups, ~1k tokens |
| `oracle_query` | 10k rows | Count, first 3 rows, aggregates |
| `s3_check` / `s3_list` | Full listing | exists, size, last-modified |
| `get_task_log` | Full log | Exception line + deepest app frame + tail |

Techniques: **deduplicate by signature** (400 identical traces → `[x400] …`); **keep head and tail of stack traces**, drop the middle.

### 10.2 Budget enforcement
Count tokens before every call. On overflow, drop in this order: oldest tool results → older hypotheses → context-pack detail. **Never drop** the error, the KA, or the latest tool result. Log every truncation as a metric.

### 10.3 Stateless API reality
AI Dome remembers nothing between calls. The full message history plus the tool list is resent each turn. This is the main driver of cost and of the turn cap.

---

## 11. Policy Gate and Autonomy Tiers

### 11.1 Tiers

| Tier | Examples | Default mode |
|---|---|---|
| 0 | Read logs, query status, check S3 | Always automatic |
| 1 | `clear_task`, `rerun_glue_job` | Auto if KA tier-1 match or high confidence; max 2 attempts |
| 1 | `update_ticket`, `create_backlog_issue` | Auto, subject to rate limits (§9.7.6); scoped to the tenant's project |
| 2 | Restart service, backfill, scale | Human approval required |
| 3 | Delete data, IAM, schema change | Suggest only — never automated |

### 11.2 Gate inputs
Proposed action, its tier, AI confidence, KA match tier, prior auto-attempts on this incident, tenant allowlist, per-hour rate limit, global and per-tenant kill switch, business-hours/blackout windows.

### 11.3 Gate rules
- Unknown action → `suggest_only`.
- Tier assignment comes from **our registry**, never from the tool's self-declaration or a third-party agent.
- Repeat failure after an auto-action → escalate, never retry blindly.
- The gate is pure, deterministic, unit-tested code. It never calls an LLM.

---

## 12. Extensibility and Multi-Tenancy

### 12.1 Four plug-in slots

| Slot | How a team plugs in |
|---|---|
| **Sources** | Emit `incident.v1` to the intake API / EventBridge. Prebuilt emitters for Airflow, Control-M, Step Functions and CloudWatch; others via the schema. |
| **Tools** | Register their own MCP server URL; platform discovers tools. **We** assign risk tiers after review. |
| **Agents** | *Config agent* (prompt + tool list + schema) or *remote agent* (their service implementing `/run`). |
| **Playbooks** | YAML: stage order, allowed auto-actions, approval group, limits. |

### 12.2 Tenant config

```yaml
tenant: payments-team
sources:
  - {type: airflow, instance: mwaa-prod, workflow_prefix: payments_}
  - {type: controlm, instance: ctm-prod, workflow_prefix: PAY_}
  - {type: cloudwatch_alarm, alarm_prefix: payments-}
orchestrators:                 # adapter registration (§6.6.1)
  - {type: airflow, adapter: https://mcp-orch-airflow.internal/mcp}
  - {type: controlm, adapter: https://mcp-orch-controlm.internal/mcp}
mcp_servers:
  - {name: payments-tools, url: https://payments-mcp.internal/mcp}
agents:
  settlement-checker: {type: remote, url: https://settle-agent.internal/run}
workflow: [triage, ka_search, settlement-checker, investigate]
policy:
  auto_actions: [action.orch.rerun_unit]
  approval_group: payments-oncall
  max_auto_per_hour: 5
backlog:
  enabled: true
  mode: auto_create           # auto_create | suggest_only
  project: PAYENG
  issue_type: Bug
  min_occurrences: 3
  window_days: 30
  max_per_day: 5
  labels: [ops-assist-generated]
ka:
  repo: git@github.com:org/ka-docs.git
  path: ka/payments-team/
  codeowners: "@payments-team/ops"
  auto_pr: true                 # AI-drafted KAs open a PR
  max_pr_per_day: 3
notify: teams://payments-alerts
aws_role: arn:aws:iam::222222222222:role/opsagent-read
```

Note `workflow` contains only investigation stages. The safety tail (§3.8) is appended by the engine and is not configurable.

### 12.3 Engine pattern — registry, not `if/else`

```python
STAGES = {}

def stage(name):
    def wrap(cls):
        STAGES[name] = cls
        return cls
    return wrap

def build(step, tenant):
    spec = tenant.agents.get(step)
    if spec and spec["type"] == "remote":
        return RemoteAgent(spec)
    return STAGES[step](tenant)

async def handle(case, tenant):
    for step in tenant.workflow:
        result = await build(step, tenant).run(case, tenant)
        case.add(step, result)
        if result.stop:
            break
    await gate_remediate_verify_comms(case, tenant)   # fixed tail
```

New built-in stage = one class. New team agent = one YAML line. Core loop unchanged.

### 12.4 Tenant isolation
- `tenant_id` is a partition key and a tag on every log line, metric, and AI call.
- Cross-account IAM: the team creates a role in **their** account; we assume it. They control our blast radius.
- Per-tenant token budget, action rate limit, and kill switch.
- Circuit breakers and timeouts on every plugin — one broken MCP server cannot stall other tenants.

### 12.5 Making it sellable
- **SDK + template repo** — `@tool` decorator, agent spec helpers, local test runner. Zero to working plugin in a day.
- **Replay harness** — run a team's last 30 days of failures in shadow mode; show what would have been caught. Strongest demo we have.
- **Tenant dashboard** — auto-resolved %, MTTR before/after, engineer-hours saved, AI cost per incident.
- **Plug out too** — expose the platform as an API and MCP server so chatbots/IDE assistants can ask "investigate INC-1234".

### 12.6 Scaling levers
Horizontal: ECS autoscale on SQS depth (`ApproximateAgeOfOldestMessage` is the better signal). MCP servers scale independently and run stateless. Concurrency capped per tenant so one noisy team cannot starve others. AI Dome is the likeliest bottleneck — queue and backpressure rather than retry-storm.

---

## 13. Security

- **Instruction boundary.** Log content, KA text, ticket text, and third-party agent output are **data, never instructions**. The gate is the enforcement point (§3.1).
- **Least privilege.** Three distinct roles: `ops-assist` (queue/state only), `ops-mcp-read` (read), `ops-mcp-action` (narrow write).
- **No free-form execution.** Oracle via named SQL templates; no shell tool; no arbitrary HTTP tool.
- **Secret masking** in the Compactor before anything reaches AI Dome or S3.
- **Network.** All internal traffic in private subnets, SG-to-SG. Only AI Dome egress leaves the VPC.
- **Approval integrity.** Signature verification, group membership check, expiry.
- **Audit.** Every prompt, response, tool call, gate decision, and action persisted with the actor and reason.

---

## 14. Reliability and Failure Modes

| Failure | Handling |
|---|---|
| SQS redelivery mid-investigation | Idempotent by fingerprint; resume from saved stage. Visibility timeout > max processing time, with heartbeat extension. |
| Poison message | 3 receives → DLQ → alarm |
| MCP server down | Circuit breaker; degrade to fewer tools; note the gap in the summary |
| AI Dome timeout / 429 | Exponential backoff; on exhaustion, fall back to context-pack-only summary for a human |
| AI returns invalid JSON | One re-ask with the schema; then fail to `suggest_only` |
| Tool loop repetition | Cached-result short circuit (§9.4) |
| Action succeeds, verification fails | Escalate; never auto-retry the same action a third time |
| KA fix repeatedly fails | Auto-flag the KA for review; stop trusting it for auto-execution |
| Runaway cost | Per-tenant token budget + global kill switch |

---

## 15. Observability

**Operational:** queue depth and age, processing latency p50/p95, tool latency and error rate by tool, AI Dome latency/tokens/cost per incident, turn counts, truncation events.

**Quality (the ones that matter for adoption):**
- KA hit rate by tier (1 / 2 / 3 / none)
- Auto-resolution rate, and its success rate
- Diagnosis accuracy from the feedback button ("right" / "wrong — actual cause was ___")
- MTTR before vs after
- No-KA investigations per DAG (drives KA authoring)
- Hypothesis prediction accuracy (`expect_if_true` held?)

**Permanent-fix metrics (the ones that prove the platform reduces work, not just absorbs it):**
- Backlog issues created, and **accepted vs rejected as noise** — the signal-quality measure
- Recurring fingerprints with **no** open backlog issue (the "silently absorbed toil" gap — should trend to zero)
- Toil cost per open backlog issue, and total toil eliminated by closed ones
- Recurrence rate after a backlog issue is closed as fixed (measures whether fixes actually worked)
- Ratio of remediations to permanent fixes over time — should fall

**Feedback button is mandatory in v1.** Without the correction feed we have no quality metric and no KA source, and no way to prove value to other teams.

---

## 16. Rollout Plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Spike** | Confirm AI Dome tool-calling, context limit, rate limits | Documented in §17 |
| **0b. KA migration** | Stand up `ka-docs`, CI validation, deploy pipeline, indexer. Build the fragment library (§8A.1) and `KA-0000`. Convert the top ~20 most frequent failures to Markdown KAs by hand | 20 validated KAs live in the index; a team has merged a PR unaided; `KA-0000` resolves upstream and platform-wide cases with no AI call |
| **1. Shadow** | Full pipeline, **zero execution**. Posts diagnosis to ticket. Our team only. | 4 weeks; ≥70% diagnosis accuracy on feedback |
| **2. Tier 1 auto** | Auto-execute tier-1 actions only for KA tier-1 matches | 4 weeks; no incident caused by the agent |
| **3. Approval flow** | Tier-2 actions with human approval in chat | Approvals used and trusted |
| **3b. Backlog** | Start in `suggest_only` — draft posted to chat, human clicks to create. Switch to `auto_create` once acceptance rate is high | ≥70% of drafted issues accepted rather than dismissed as noise |
| **4. Multi-tenant** | Extract contracts; onboard **our own team as tenant #1** via the same YAML, no special paths | Our team runs entirely through tenant config |
| **4b. Second orchestrator** | Implement one non-Airflow adapter end to end. This is the real test of §6.6.1 — if the core needs edits, the contract is wrong | An existing KA runs unchanged against the new orchestrator |
| **5. Second team** | One friendly team; replay harness demo | Onboarded with zero core code changes |

**Build order note.** Write the KA YAML schema and Step Runner first — every other contract hangs off it.

---

## 17. Decision Log and Open Questions

### 17.1 Decisions made

| # | Decision | Rationale | Revisit when |
|---|---|---|---|
| D1 | LLM proposes, code disposes | Safety, injection defence | Never |
| D2 | KA path is deterministic, not agentic | Cost, latency, reliability | Never |
| D3 | No LangChain; hand-written loop | ~200 LOC; AI Dome's custom format fits awkwardly into LC; avoids version treadmill | If we need many prebuilt integrations |
| D4 | pgvector + S3 for KA; **no graph DB** | Graph solves dependency modelling, a later problem, high ops cost | v3, if cross-system dependency reasoning is needed |
| D5 | Read and action MCP servers split | Blast radius | Never |
| D6 | Single `ops-assist` container in v1 | Simpler ops; modules split later if ownership diverges | When a team needs to own a stage |
| D7 | Compaction mandatory on every tool result | Token limits | Never |
| D8 | Safety tail not tenant-configurable | Lets us accept third-party agents safely | Never |
| D9 | Backlog issues are recurrence-gated, not per-incident | Per-incident creation produces spam and the feature gets disabled | If a class proves always-actionable at N=1 |
| D10 | Backlog issue priority computed by code, not the LLM | Priority is a business decision; LLM confidence is not a proxy for it | Never |
| D11 | Platform never closes a backlog issue | Closing implies verified engineering work; only humans can attest to that | Never |
| D12 | KAs authored as Markdown in Git; S3 is the deployed artefact; Postgres is a rebuildable index | Teams own their docs in the tooling they already use; review, history, and rollback come free | Never |
| D13 | AI-drafted KAs open a **pull request**, not a DB row | Team reviews a diff; CI validates before approval is even possible | Never |
| D14 | The Markdown **body never drives execution** — only front-matter `steps` run | A doc edit must not be able to change production behaviour; closes injection via KA prose | Never |
| D15 | No hand-maintained `version` field; Git SHA is the version | Hand-kept version numbers always drift | Never |
| D16 | CI blocks on tool-name, arg-schema, and tier validation | A malformed KA is more dangerous than a missing one | Never |
| D17 | Tools are namespaced `{domain}.{service}.{verb}`; `action.*` is the only write prefix | Flat names collide; the prefix makes read/write separation visible and CI-checkable | Never |
| D18 | KAs are built from **fragments**, not bespoke steps | Standard sequences reviewed once; authoring becomes parameterisation | Never |
| D19 | `conclusions` table resolves the common cases with **zero AI calls** | Cost, latency, determinism. AI only for unanticipated shapes | Never |
| D20 | `KA-0000` default playbook runs before AI investigation, and **is** the context pack | One mechanism, extensible by PR, not hard-coded Python | Never |
| D21 | `goto` may only jump forward | Guarantees termination by construction | Never |
| D22 | `incident.v1` uses canonical `workflow`/`unit`/`run` vocabulary; native scheduler fields are opaque passthrough | KAs, matching and prompts stay orchestrator-neutral; adding a scheduler touches no core code | Never |
| D23 | `orch.*` and `action.orch.*` are **capabilities resolved at runtime**, not implementations | A KA survives a workflow migrating between orchestrators | Never |
| D24 | `workflow` and `compute` are separate blocks | They fail independently; compute fragments are reused across every orchestrator | Never |
| D25 | Adapters are MCP servers, one per orchestrator, holding their own credentials | On-prem schedulers supported without changing anything else | Never |
| D26 | Missing optional capability → step `skipped: unsupported`, not failure | Degraded diagnosis beats no diagnosis | Never |

### 17.2 Open questions — **must be answered before build**

| # | Question | Owner | Blocks |
|---|---|---|---|
| Q1 | Does AI Dome support tool/function calling? Exact request/response format? | | Everything in §7.6 |
| Q2 | AI Dome context window and rate limits? | | §10 budgets |
| Q3 | Does AI Dome offer embeddings? If not, confirm Bedrock Titan is permitted | | §8.3 |
| Q4 | Where do KAs live today (ServiceNow / Confluence / other)? **One-time migration to Markdown**, or a sync bridge during transition? | | §5.6 |
| Q18 | One central `ka-docs` repo with per-tenant directories, or a repo per team? Central is easier to validate; per-team gives clearer ownership | | §5.6.1 |
| Q19 | Can the platform's service account open PRs on the team repos? Which Git host and auth model? | | §9.6 |
| Q20 | Who publishes the tool-registry artefact that CI validates against, and how is it versioned? | | §8.6 |
| Q21 | Do non-engineering support staff need to author KAs? If so, Markdown-in-Git may need a lightweight editor front end | | §5.6.1 adoption |
| Q22 | Which AWS services must the v1 fragment library cover? Confirm the actual service mix behind the DAGs (Glue / EMR / ECS / Lambda / Step Functions?) | | §8A.1 |
| Q23 | Is JSONPath the right expression language for `capture` / `expect`, or should we use a narrower custom accessor? | | §6.7 |
| Q24 | Who owns `ka/_fragments/` — platform team only, or can app teams contribute fragments by PR? | | §8A.1 |
| Q25 | Does `KA-0000` need per-tenant variants from day one, or is one global default enough for v1? | | §8A.3 |
| Q5 | Which orchestrators are actually in use across the teams we intend to onboard, and which is the second one to implement after Airflow? | | §6.6.1 adapter order |
| Q5b | MWAA or self-managed Airflow? Affects log access and API auth | | Airflow adapter |
| Q5c | Are any orchestrators on-prem? Network path and auth for the adapter | | §6.6.1 deployment |
| Q6 | Ticket system and its API — is `update_ticket` write-capable for us? | | §5.4 |
| Q7 | Chat platform for approvals — Slack or Teams? | | §5.9 |
| Q8 | Oracle access path — direct from VPC, or via an existing service? | | `oracle_query` |
| Q9 | Evidence retention period (compliance)? | | §5.8 |
| Q10 | Is any log/DB content PII- or secret-bearing? Masking rules needed | | Compactor |
| Q11 | Who owns KA approval? | | §8.5 |
| Q12 | Cost ceiling per incident / per month? | | Budgets, kill switch |
| Q13 | Jira instance, API access, and a service account that can create issues in the target project? | | §9.7 |
| Q14 | Which Jira project(s) receive backlog issues — one per tenant, or one central ops-debt project? | | §12.2 config |
| Q15 | Who triages machine-generated backlog issues, and on what cadence? Without a named owner they rot | | §9.7 value |
| Q16 | Is the incident ticket system the same as Jira, or separate (e.g. ServiceNow for incidents, Jira for engineering)? | | §5.4 tools |
| Q17 | Do we need occurrence counters on the Jira issue itself, or only in DynamoDB with a link? | | §9.7.4 |

### 17.3 Known gaps / deliberately deferred
- Multi-region / DR strategy.
- Cost attribution and chargeback per tenant.
- Formal versioning and deprecation policy for contracts.
- A UI beyond ticket + chat.
- Graph-based system dependency modelling (see D4).

---

## Appendix A — Reference: MCP roles in this architecture

| Role | Who plays it here |
|---|---|
| LLM | AI Dome (never talks to MCP servers directly) |
| MCP client | Library **inside** `ops-assist` |
| MCP server | `ops-mcp-read`, `ops-mcp-action`, plus tenant-supplied servers |

An MCP server is essentially an HTTP microservice with a single `POST /mcp` endpoint using JSON-RPC, self-describing via `tools/list`, and typically wrapping existing REST APIs or databases. It is not a replacement for existing microservices — it is an adapter in front of them.

---

## Appendix B — Complete worked KA

A realistic KA with branching, showing how one task's three distinct failure modes resolve to three different answers with no AI involvement.

`ka/payments-team/settlement_eod/KA-0042-load-settlements-failure.md`

````markdown
---
ka_id: KA-0042
schema: ka.v1
status: approved
owner: payments-team
family: missing_input
title: "Settlement EOD — load_settlements fails"

applies_to:
  tenants: ["payments-team"]
  orchestrators: []                 # empty = any; this KA is orchestrator-neutral
  workflows: ["settlement_eod"]
  units: ["load_settlements"]
  error_patterns:
    - "FileNotFoundError.*settle_.*csv"
    - "ORA-00001.*SETTLE_STG"
    - "ORA-12170.*TNS"

symptoms: |
  The daily settlement load fails. Usually the upstream feed file has not
  landed, but the same task also fails on partial re-loads and on database
  connectivity problems, which look different in the log.

steps:
  # ---- Step 1: is the input there at all? Cheapest, most decisive. ----
  - include: frag.aws.s3.input_check
    as: input
    params:
      bucket: "eod-landing"
      key_template: "settle_{{ incident.workflow.logical_date }}.csv"
      min_size_bytes: 1024
    # fragment stops with verdict input_missing or input_truncated

  # ---- Step 2: file exists — did a previous run already load rows? ----
  - id: s2
    description: "Check whether rows for this date already loaded"
    tool: db.oracle.query
    args:
      sql_id: settle_count_by_date
      params: {ds: "{{ incident.context.ds }}"}
    capture:
      row_count: "$.summary.rows[0].cnt"
    expect:
      field: "$.summary.rows[0].cnt"
      op: eq
      value: 0
    on_pass: continue          # 0 rows = clean slate, keep investigating
    on_fail:                   # rows present = partial load, stop here
      verdict: partial_load
      action: stop

  # ---- Step 3: clean slate but still failed — DB reachable? ----
  - id: s3
    description: "Confirm the Oracle instance is healthy"
    tool: aws.rds.get_instance_status
    args: {identifier: "{{ tenant.oracle_instance }}"}
    expect: {field: "$.summary.status", op: eq, value: "available"}
    on_fail: {verdict: db_unavailable, action: stop}

  # ---- Step 4: DB is up — is something blocking the load? ----
  - id: s4
    description: "Look for blocking sessions during the run window"
    tool: db.oracle.check_locks
    args:
      schema: SETTLE
      from: "{{ incident.occurred_at | minus_minutes(20) }}"
      to: "{{ incident.occurred_at }}"
    capture: {blockers: "$.summary.blocking_session_count"}
    expect: {field: "$.summary.blocking_session_count", op: eq, value: 0}
    on_fail: {verdict: db_contention, action: stop}

  # ---- Step 5: nothing obvious — gather errors for the AI ----
  - include: frag.aws.cloudwatch.error_scan
    as: errors
    params: {arn: "{{ incident.compute.arn }}", window_min: 15}
    # informational only, no expect — always falls through to default

conclusions:
  - when_verdict: input_missing
    root_cause: "Upstream settlement feed has not landed"
    severity: medium
    remediation:
      action: none                    # nothing to fix until the file arrives
    notify: ["#feed-ops"]
    human_summary: |
      settle_{{ incident.context.ds }}.csv is absent from s3://eod-landing.
      This is an upstream feed delay, not a settlement-side defect.
      Feed SLA is 02:30 UTC. Once the file lands, re-run the unit.
      If it has not landed by 04:00 UTC, page the feed team.

  - when_verdict: input_truncated
    root_cause: "Feed file landed but is truncated or a zero-byte stub"
    severity: high
    remediation: {action: none}
    notify: ["#feed-ops"]
    escalate_to: "feed-engineering"
    human_summary: |
      The file exists but is under 1 KB, which means the upstream writer
      failed mid-write. Do NOT re-run the unit — it will load bad data.
      Ask the feed team to re-drop the file.

  - when_verdict: partial_load
    root_cause: "Previous run failed mid-load; rows already present for this date"
    severity: high
    remediation: {action: none}       # needs a human, never auto-retry
    escalate_to: "data-engineering"
    human_summary: |
      {{ steps.s2.row_count }} rows already exist for
      {{ incident.workflow.logical_date }}. Re-running would double-load.
      Purge the partial load first, then re-run.

  - when_verdict: db_unavailable
    root_cause: "Oracle instance not available during the run"
    severity: high
    remediation: {action: none}
    escalate_to: "platform-oncall"

  - when_verdict: db_contention
    root_cause: "Blocking sessions prevented the load from acquiring locks"
    severity: medium
    remediation:
      action: action.orch.rerun_unit        # capability: resolves per orchestrator
      tier: 1
      max_attempts: 1
    human_summary: |
      {{ steps.s4.blockers }} blocking sessions were active during the run.
      Contention is usually transient — retrying once is appropriate.

  - default:
      escalate_to_ai: true
      hint: |
        Input file present and correct size, no rows loaded, DB healthy,
        no lock contention. Failure is likely inside the transform logic.
---

## What this failure means

`load_settlements` reads one daily CSV dropped by the upstream feed job and
loads it into `SETTLE_STG`. It has three distinct failure modes that look
similar in the ticket but need different responses.

## How to confirm

1. Check the landing bucket for today's file.
2. If present, check whether rows already exist for the date — a partial
   load is the dangerous case, because clearing the task double-loads.
3. If clean, check database reachability and lock contention.

## Fix

See the conclusions table above. The short version: missing file means wait,
partial load means purge first, contention means retry once.

## Escalation

- Feed not landed by 04:00 UTC → `#feed-ops`, then page the feed team.
- Partial load → data engineering. Never re-run the unit yourself.

## History

- 2026-08-14 — added the partial-load check after INC0011902, where a
  re-run double-loaded 40k rows.
- 2026-06-02 — added lock contention check after a month of false escalations.
````

### B.1 What to notice in this example

**Step order is deliberate.** The S3 check runs first because it is a sub-second call that resolves the most common cause outright. The 15-second log scan is last, and only runs if nothing else concluded. See §8A.5 rule 3.

**Five verdicts, five different answers, zero AI calls.** Each `on_fail` names a verdict; the conclusions table turns that verdict into a root cause, a remediation and a human summary. The runner compares values — it does not reason.

**Not every conclusion is an action.** Three of the five say `action: none`. The `partial_load` case is why this KA exists at all: the naive fix — clearing the task — is actively harmful there. That knowledge lives in the KA, and the History section records the incident that taught it.

**The default branch admits ignorance usefully.** By the time it fires, four possibilities are ruled out, and the `hint` tells the AI where to look. This is §3.7 in practice.

**It is orchestrator-neutral.** `applies_to.orchestrators` is empty and the only scheduler-facing element is `action.orch.rerun_unit`, a capability. The same file works if this workflow is migrated from Airflow to Control-M — only the registered adapter changes (§6.6.1).

**The prose body carries the "why".** The front matter tells the machine what to do; the Markdown tells the next engineer why it is done that way. Because both live in one file and one PR, they cannot drift apart (D14).
