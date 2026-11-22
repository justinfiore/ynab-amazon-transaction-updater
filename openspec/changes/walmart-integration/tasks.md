# Tasks

All tasks are implementation work for a later apply phase. This proposal does not perform live authentication or account writes.

## 1. Pinned helper and protected session input

- [ ] 1.1 Add the optional repository-owned Go module using `walmart-client-go/v2` v2.2.1 at reviewed commit `d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`; check in checksums and verify reproducible builds, `go mod verify` and dependency advisory review without credentials.
- [ ] 1.2 Define versioned minimal JSON input/output, completeness/error categories and query bounds; verify fixture/schema tests omit addresses, cookies, raw responses and unsupported success payloads.
- [ ] 1.3 Implement safe local browser-capture parsing and protected session-file writes without shell execution; verify malicious capture text cannot execute and owner-only permissions/ACL behavior is covered on supported platforms.
- [ ] 1.4 Enforce Walmart-only HTTPS destinations, redirects, TLS and bounded requests/pagination in the helper, replacing unsafe upstream defaults where needed; verify fake-server/transport tests reject off-site, insecure, stale-session and oversized/error cases without secret diagnostics.
- [ ] 1.5 Document optional helper build, capture/session provisioning, expiration, disposal, toolchain/platform support and OS isolation limits; verify the documented build works without login and ordinary application installation requires no Go/helper.

## 2. Ledger source and exact normalization

- [ ] 2.1 Add explicit ledger source, helper/session paths and positive time/output settings to configuration and `WalmartService`; document examples and verify legacy guest/login selection and Amazon environment isolation remain unchanged.
- [ ] 2.2 Add the bounded Go-backed Groovy fetcher with source-scoped environment filtering; verify timeout, excess output, invalid schema, nonzero exit and stale-auth fixtures return no usable failed-fetch payload while other retailers continue.
- [ ] 2.3 Normalize distinct payment-charge occurrences, dates, currency, completeness and stable keys; verify repeated equal-valued charges remain distinct, holds/refunds are excluded and malformed/misaligned ledgers fail split eligibility.
- [ ] 2.4 Normalize items, decimal quantities, extended costs and supported discounts/tax/fees/tips; verify weighted items, duplicate display groups, cancellations, unsupported precision/currency and unexplained residuals with independently expected fixture totals.

## 3. Unique matching and durable links

- [ ] 3.1 Extend `TransactionMatch` and ledger matching with global candidate resolution, exact cents, charge dates and optional account/payment mapping; document mapping and verify no charge reuse or order-total fallback, equal-charge multiplicity and cross-order/account ambiguity.
- [ ] 3.2 Add link-only enrichment preserving existing memo content and reserving space for the validated order number/link; verify long memos, duplicate runs, unsafe URLs and optional summary truncation obey the 500-character limit.
- [ ] 3.3 Add ledger dry-run reporting and source-specific skip reasons without marking completion; document preview and verify representative matches, failures and ambiguous assignments produce no HTTP writes.

## 4. Exact proportional allocation

- [ ] 4.1 Implement reconciled component/complete-charge plans using exact cents and deterministic controlled rounding; verify independent expected examples and generated allocation invariants for both row/column totals, floor/ceiling bounds, stable ordering, zero cells and asymmetric fractional-penny cases.
- [ ] 4.2 Include unmatched/gift-card charges in the full plan while posting only matched columns; verify missing-payment, changed matching subsets, supported discounts and explicit fee/tax/tip examples do not duplicate or redistribute spending.
- [ ] 4.3 Add explicit `link_only`/`proportional` configuration and item-level preview; document the worked $70/$30 example, rounding, category inheritance and accounting-versus-actual-charge distinction, and verify mismatch/refund/unsupported inputs stay unsplit with a clear reason.

## 5. Safe YNAB split updates and recovery

- [ ] 5.1 Extend YNAB DTOs and the new update path for unsplit-to-split PUT payloads; verify fake-server/schema tests preserve parent identity/amount/approval/cleared state/memo, null the parent category and use negative integer milliunits with valid child categories/memos.
- [ ] 5.2 Enforce fresh-read split eligibility and preserve all preexisting splits; document restrictions and verify tracking accounts, transfers, reconciled/deleted parents, special categories and concurrent edits are skipped without replacement transactions.
- [ ] 5.3 Add protected atomic allocation journaling with local application serialization and separate per-charge link/split completion; verify a linked parent can later split, partial writes retry only unfinished charges and repeated runs do not duplicate spending.
- [ ] 5.4 Verify unknown write outcomes by remote read-back against saved intent, and stop after applied-plan drift/conflicts; cover accepted-but-response-lost, rejected, changed-tip/ledger and user-edited fixtures, and document manual recovery and non-atomicity across transactions.

## 6. Integrated offline acceptance

- [ ] 6.1 Exercise helper fixtures through source selection, matching, preview, allocation, mock YNAB updates and restart recovery; verify both capability specs' scenarios without real sessions, browser installation or live account writes.
- [ ] 6.2 Run Go tests/build, the repository build and unit/integration test scripts plus strict OpenSpec validation; verify existing Amazon/legacy Walmart tests remain green and packaging remains usable without the optional helper.
