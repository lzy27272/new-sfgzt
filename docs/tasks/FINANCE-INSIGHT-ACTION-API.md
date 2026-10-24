# Financial analysis recommendation closure (V105)

This is an explicitly assigned human follow-up, not a grant of `task.create`, an execution task, a payment, posting, tax decision, or KPI adjustment.

## Assignment and real access

- `GET /api/v1/finance/insights/actions?page=0`: 50 actual scoped rows and a separately counted total; owner, designated verifier, or scoped configuration reader only. Not limited to today. `overdue` follows the tenant business timezone.
- `GET /actions/assignees?hotelId=`: real active accounts whose current effective identity can act/review in that hotel. Does not assume a role title or grant permissions. A 1,000-candidate window is explicit.
- `POST /actions`: `{requestId,reportId,reportRevision,recommendationIndex,hotelId,ownerId,reviewerId,title,dueDate,note}`. Requires finance.review, live scope, current published analysis revision, an explicit adoption record with that same owner, and a unique matching company in the frozen report's real books. Owner and verifier are different actual accounts; eligibility is resolved against current IAM.
- `GET /actions/{id}`: returns only this action's recommendation text, citations, source report version, scoped assignment, immutable handling history and proof metadata. It does not disclose the full group analysis dataset or stored proof payloads to an owner.

The finance UI uses real scoped account selectors, not typed UUIDs. Adoption and explicit assignment are distinct user actions. The old `/reports/{id}/followups` endpoint cannot accept self-reported `DONE`.

## Actual proof and independent verification

- `POST /actions/{id}/start`: `{requestId,expectedVersion,note}`. Only the actual owner with an existing finance.submit/review/configure permission and live hotel scope can work. Broad finance.review/configure does not allow doing another owner's action.
- `GET /actions/{id}/sources?sourceType=VOUCHER&page=0`: candidate source pages, actual source authorization and validation applied to each record before revealing metadata. An empty filtered page is not an assertion of no later candidates. Payroll and tax retain their independent permissions.
- `POST /actions/{id}/proof-preview`: `{sourceType,sourceId,expectedSourceVersion:"PREVIEW"}` reads the trusted original source adapter, not client-supplied facts. Returns its real version/hash and linkage.
- `POST /actions/{id}/submit`: `{requestId,expectedVersion,note,evidence:[{sourceType,sourceId,expectedSourceVersion}]}`. Requires 1–20 distinct, actual verified sources, all strictly matching the action's one hotel and frozen company. Accepted: approved/paid claim, approved operation, posted unreversed voucher, confirmed revenue/payroll, or a tax filing with a real original attachment and external filing receipt. No self-reported JSON, model analysis, unconfirmed draft or migration stub substitutes for completion.
- `POST /actions/{id}/review`: `{requestId,expectedVersion,approve,note}`. Only the designated independent verifier, with finance.review and current original-source permissions, can decide. Approval re-resolves every submitted source and linked permission, compares exact source versions and canonical hashes, and validates verified source status. Changed/reversed/unauthorized evidence cannot be rubber-stamped. Return for rework preserves all previous submissions and notes.
- `POST /actions/{id}/cancel`: `{requestId,expectedVersion,note}`. Only the original assigning reviewer may cancel OPEN/IN_PROGRESS/REWORK; cannot cancel an independently submitted or completed item.

State: OPEN → IN_PROGRESS (optional) → SUBMITTED → DONE or REWORK → SUBMITTED. Explicit cancellation is separate from completion. All decisions are optimistic and request-id/content/actor-bound. Original assignment/recommendation and append-only proof/history cannot be silently changed or deleted.

## Workbench and notification contract

`fi_action_task`: id,tenant_id,report_id,report_revision,recommendation_index,hotel_id,company_id,owner_id,reviewer_id,title,recommendation_text,citations,source_report_version,due_date,status,version,submission_version,created_by,created_at,updated_at,submitted_by,reviewed_by,reviewed_at,request_hash.

Actual owner pending states: OPEN/IN_PROGRESS/REWORK and existing finance.submit/review/configure. Actual designated verifier pending state: SUBMITTED and finance.review; never owner/submitted_by. Current hotel scope remains mandatory, not inferred from the creator's grant.

Pending domain `INSIGHT_ACTION`; notification source `FINANCE_INSIGHT_ACTION`; route `finance?tab=insight-actions&actionId={id}`. Assignment, submission, return and conclusion notifications contain no financial details. The shared current-node reminder scheduler supplies scheduled, idempotent reminders and overdue wording from the same authorized queue. Scheduler must be explicitly enabled and have its tenant whitelist configured; code presence is not proof of production delivery.

## Reporting extension

F23 now retains revenue/total expense/book net profit/net cash movement YoY/MoM, absolute changes, both source report versions, comparison month and source completeness. Closed periods read real frozen accounting snapshots; open periods are explicitly live preliminary results. Zero historical denominator is not a fabricated growth percentage. Missing/incomplete history has null comparisons.

The insight snapshot, page and same-version XLSX/PDF reuse the same income statement, balance-sheet and cash-flow aggregates; exporting does not query a different revision. Group trends first aggregate the same complete hotel scope in each period, never average hotel percentages. Strict operating profit is explicitly unavailable until operating/nonoperating/investing/finance attribution is configured. Actual work hours may be explicitly confirmed from a real manual source, versioned and frozen; revenue per work-hour requires complete financial revenue and a positive confirmed hours source. Electricity/water per room-night requires both confirmed measurements and a positive room-night source. These manual sources do not claim an automatic WeCom attendance or meter connection.

V103 budget business sources are aggregated separately by company/hotel/month/category/department/economic basis/source type. Confirmed inventory purchase funding minus returns remains separate from expense actuals; current procurement commitments are not presented as historical month-end balances. Open classification issues are displayed as incomplete, not zero cost. The model's declared/detected forecasts need explicit assumptions and uncertainty, not only a prompt requesting them.

Verification suites: FinanceInsightActionIntegrationTest (8 real app-role PG cases, actual source accounting gates, identity eligibility stub only); FinanceInsightTrendIntegrationTest (3 real app-role PG cases reading persisted historical accounting snapshots); FinanceInsightContentTest (3 strict content cases); FinancePendingV2IntegrationTest (10 scoped current-node cases through V106); FinanceReminderIntegrationTest (4 real IAM/assignment/current-queue app-role PG cases, including 61-row pagination and idempotent reminders). Execution/results are recorded by the centralized parent regression run, not asserted by this document.
