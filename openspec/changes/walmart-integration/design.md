# Walmart integration design

Use the reviewed Go client to link each YNAB purchase to a distinct Walmart final charge, then optionally spread the order's reconciled item costs proportionally across all charges. Proportional allocation is a bookkeeping convention, not evidence that a particular charge paid for particular items.

This is a proposal only. No Walmart implementation, login or YNAB write is authorized by this document. See `proposal.md` for motivation and the capability specs for normative requirements.

## Context

- `WalmartService` currently selects browser-backed guest/login fetching. `TransactionMatcher` compares final charge amounts but does not reserve unique charge occurrences and also permits an order-total fallback. Preserve those legacy source modes; apply the new contracts to opt-in ledger mode.
- `TransactionProcessor` already adds Walmart links. Transaction DTOs and `YNABService` need explicit split support; the existing processed-ID/memo heuristic cannot represent link-only versus split completion.
- Reviewed client: [`github.com/eshaffer321/walmart-client-go/v2`, v2.2.1](https://github.com/eshaffer321/walmart-client-go/tree/d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244). `ledger.go` exposes payment identity and final charge amounts/dates, but no charge-to-item mapping. `models.go` exposes item quantities and prices. Neither grouping by shipment nor repeating all items per transaction is justified by this evidence.
- [YNAB OpenAPI](https://api.ynab.com/papi/open_api_spec.yaml), inspected version 1.91.0, supports converting an unsplit transaction through `PUT /budgets/{budget_id}/transactions/{transaction_id}` with `subtransactions` and parent `category_id: null`. It rejects updates to existing split subtransactions. Amounts use integer milliunits; omitted `approved` defaults to false. The implementation must preserve parent state explicitly.

## Goals / Non-Goals

**Goals:** Match individual charges without reuse; preserve order access; create auditable item/component splits with exact totals; retain user edits and safe retry behavior; keep session and purchase data local/minimal.

**Non-goals:** Reverse-engineer an actual item-to-charge mapping; automatically choose new budget categories; split refunds or foreign-currency transactions; edit existing splits; replace imported bank transactions; build a new GUI; guarantee atomic updates across multiple transactions; change Amazon behavior.

## Decisions

### 1. Put the Go client behind a small repository-owned helper

Add a dedicated Go module/helper using the reviewed revision with checked-in `go.mod`/`go.sum`, a documented toolchain and `go mod verify`. Expose a versioned JSON output contract to a new Groovy fetcher selected by `WalmartService`. Standard Java distribution must still run without Go/helper installation when ledger mode is unused.

Input consists of a protected local session-file path and non-secret query bounds. Session capture import must parse data without executing pasted cURL/shell. Owner-only permissions or equivalent ACLs protect bearer material. Fetching never automates login, requests a password or reads email. Ship explicit setup/expiration/disposal instructions; credentials and capture contents never enter command arguments or logs.

Apply positive HTTP/process timeouts, pagination bounds, output-size limits and version/schema checks. Enforce verified TLS, exact required Walmart hosts and no off-site redirects, including session/configurable URL inputs; constrain or replace unsafe client defaults rather than trusting upstream validation. Use a source-specific child-environment allowlist and minimal session/output fields. Do not relax the Amazon runner's protections to accommodate Walmart.

Process/environment bounds are not an OS sandbox. Document dedicated unprivileged execution and narrow filesystem/network access for deployments needing isolation. Do not add the client's optional browser installation or debug/raw-response persistence to the normal path.

**Alternative rejected:** Embedding the entire upstream CLI/browser workflow exposes more state and behavior than the application needs. Copying its implementation into Groovy loses the reviewed dependency boundary.

### 2. Normalize a complete ledger before matching

The helper emits order ID/link, fulfillment state, item IDs/descriptions/decimal quantities, extended costs and known discounts/tax/fees/tips, plus every final purchase-charge occurrence with currency, date and payment identity (type/card type/last four when available). Emit completeness and failure state explicitly; omit addresses, cookies and unused response fields.

Normalize raw decimals directly where possible. The client uses `float64` for some ledger values: convert only finite values demonstrably representing exact cent amounts within bounded machine-representation error; reject materially fractional-cent values, never use a broad matching tolerance. Use decimal/integer cents after this boundary. Reject unsupported currency or unknown currency rather than assume USD from a numeric value alone.

Deduplicate repeated presentation groups by stable item identity without collapsing genuinely separate lines. Use extended costs as supplied, not extended cost times quantity. Resolve explicit item discounts into net item rows; distribute a known order-wide discount over its eligible item base using deterministic cent rounding. Unsupported discount semantics or negative residual bases prohibit splitting. Keep explicit tax, fees and tips as separately named rows; do not manufacture an adjustment row to hide discrepancies.

Canonical charge keys use order ID, payment identity, date, amount and a duplicate ordinal for identical occurrences; do not invent upstream transaction IDs. Treat indistinguishable duplicate occurrences as a multiset and retain multiplicity. Unknown/misaligned dates or incomplete charge lists cannot produce a split-ready order. Holds/refunds are not purchase columns. Pending fulfillment, unresolved cancellations, refunds or uncertain pricing make the order link-only at most; a split-ready snapshot must account for all fulfilled billable components and complete final purchase charges.

### 3. Match globally before reserving charge occurrences

Construct candidates using exact outflow magnitude, configured charge-date window and compatible account/payment evidence. Account mapping is optional only when the remaining evidence gives a unique assignment. Resolve the candidate graph across orders and transactions, consuming each occurrence once; skip ambiguous connected assignments rather than greedily taking the first amount match. Interchangeable identical occurrences within one order/payment are equivalent only when the set of assigned YNAB transactions is itself unambiguous.

Do not use a whole-order amount fallback in ledger mode. Retain the stable charge key in `TransactionMatch`, not merely an order pointer. A trustworthy match may support linking even when allocation is unsafe. Reserve space for the validated HTTPS Walmart link and order number in the 500-character parent memo, preserve existing user text, trim only optional summaries, and skip/report if required content cannot fit.

### 4. Allocate over all final charges, including unmatched payments

For component cost \(r_i\), charge amount \(c_j\), and reconciled total \(T = \sum_i r_i = \sum_j c_j\), the ideal allocation is \(a_{ij} = r_i c_j / T\). The denominator includes every final purchase charge, including gift cards and charges absent from YNAB. Only matched eligible columns are posted; other columns remain represented in the allocation plan.

**Illustrative example, not real purchase data:** A $100 order has $60 groceries, $30 household goods and $10 tax. Walmart posts $70 and $30 final charges, so each component is allocated 70% and 30%:

| Component | Order cost | $70 transaction | $30 transaction |
|---|---:|---:|---:|
| Groceries item | $60.00 | $42.00 | $18.00 |
| Household item | $30.00 | $21.00 | $9.00 |
| Tax | $10.00 | $7.00 | $3.00 |
| Total | $100.00 | $70.00 | $30.00 |

Real output has one line per normalized item, not automatic category aggregates. YNAB records these as negative outflows: the first column becomes -42000, -21000 and -7000 milliunits. If the second charge is a gift card missing from YNAB, do not write its $30 share anywhere else. Across both charges the item costs are counted once, rather than putting the entire $100 order in each transaction.

Use deterministic controlled rounding in integer cents. Floor each ideal cell; compute remaining row/column deficits; select fractional cells to increment through a feasible integral bipartite flow, using stable row/charge ordering and a documented deterministic tie-break. This preserves both margins with each cell at floor or ceiling. Independent per-charge largest-remainder rounding is rejected because it can preserve charge totals while distorting total item costs. Omit zero-value split lines from API output but retain their allocations in the plan.

Require positive total and nonnegative reconciled rows/columns; use checked/exact arithmetic for products. Test invariants and adversarial penny cases rather than trusting one algorithm's self-derived expected values.

### 5. Make proportional splitting opt-in and preserve categories/state

Expose explicit `link_only` (default) and `proportional` modes alongside opt-in ledger source settings. Dry run previews exact signed amounts, descriptions, order links, proportional labeling and skip reasons, with no remote writes or completion advancement. Unsupported or uncertain data must surface a reason, not silently turn into a balancing split.

For eligible unsplit budget-account purchases, retain each item's identity/description in its child memo. Inherit the parent's ordinary eligible category, or leave child categories null if already uncategorized; automatic category inference is out of scope. Skip special/unsupported category cases rather than translate them silently. Set parent category null and preserve approval, cleared state, date, account, amount, payee, flags, memo and import identity. Do not delete/recreate the transaction or send unrelated defaults. Reject deleted/reconciled transactions, transfers, tracking accounts and all preexisting splits for automatic split writes.

Use the documented PUT endpoint for this path with mock-HTTP assertions against YNAB's schema, not the legacy service's PATCH assumption. Existing source behavior is not an excuse to send an invalid split payload. A fresh read before writing detects already-present user edits; because there is no documented compare-and-swap guarantee, concurrent human edits remain a risk rather than a claimed atomic protection.

### 6. Freeze the allocation and journal partial progress

Persist a protected, atomically replaced per-order plan containing the canonical component/charge fingerprint, deterministic allocations and per-charge link/split status scoped to YNAB budget/account/transaction IDs. Store no session material. Serialize local plan application to prevent two local workers claiming the same charge; different machines must not operate the same scope concurrently.

Save intent before the request and completion after confirmed success. A link-only marker does not block a later explicitly enabled split. Retry only incomplete charge operations with the same plan; re-fetch YNAB first. A lost/timeout response is an unknown outcome: compare current remote state to the saved intended parent/splits. Matching state completes the journal; unchanged original state permits a retry; anything else requires review. Never assume a failed response means no write.

Before any split has been applied, an order plan can be recomputed from a fresh validated snapshot. After a split is applied, changed components/ledger or conflicting remote edits freeze further automatic splits for manual review, because YNAB cannot repair existing split subtransactions. A partial success is not rolled back by deleting imported transactions.

## Risks / Trade-offs

- **Unofficial endpoints can change or sessions can expire.** Fail closed, separate source failure from empty results and keep legacy modes available. A live compatibility smoke test requires later explicit operator participation; static source review is not runtime assurance.
- **Ledger pricing may not fully reconcile real orders, especially substitutions, tips and refunds.** Start with supported USD completed purchases, retain sanitized fixtures of actual supported shapes, and link without splitting when the basis is uncertain. Do not quietly broaden monetary tolerance.
- **Proportions do not describe actual item shipment charges.** Mark preview and split descriptions as proportional. This policy is the user's selected accounting convention.
- **Category work remains manual.** Item splits make later categorization possible, but category discovery/mapping is a separate feature.
- **Session files and local journals contain sensitive data.** Minimize fields and retention, restrict access and redact diagnostics; host-level compromise is outside the protections of a helper process alone.
- **YNAB has no cross-transaction atomicity or existing-split repair.** Journal partial outcomes, verify uncertain writes and stop on drift. Manual undo/review may be necessary.

## Migration Plan

1. Implement and verify the pinned helper and offline contract without changing source defaults or Amazon isolation.
2. Add ledger configuration, normalization and matching, then link-only previews. Existing Walmart/Amazon runs must pass unchanged tests.
3. Add allocation and YNAB split payloads with local fake-server verification and per-charge recovery tests. Document session setup, payment mapping, penny behavior and category limitations.
4. Operator explicitly provisions a session and reviews a dry-run plan before separately enabling live proportional writes. No proposal/test task logs in or writes to a real account.
5. Roll back by selecting the prior source or `link_only` and stopping further split application. Preserve the journal for review; reverting code cannot automatically undo already-written splits.
