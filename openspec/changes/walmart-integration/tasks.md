# Tasks

This proposal branch tracks target contracts, not completion of the separate partial implementation branch. Reconcile existing completed tasks against changed contracts rather than resetting or automatically accepting them. The user selected paid API-key OpenAI Decisions and direct TypeSafe Jev; no OAuth/Responses implementation. This proposal/default verification performs no paid inference or account writes.

## 1. Pinned helper and protected session input

- [x] 1.1 Add the optional repository-owned Go module using `walmart-client-go/v2` v2.2.1 at reviewed commit `d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`; check in checksums and verify reproducible builds, `go mod verify` and dependency advisory review without credentials. Verified 2026-11-22: `go mod verify` passed, the module cache origin hash is `d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`, and the public OSV database returned no vulnerabilities for that module.
- [x] 1.2 Define versioned minimal JSON input/output, completeness/error categories and query bounds; verify fixture/schema tests omit addresses, cookies, raw responses and unsupported success payloads.
- [x] 1.3 Implement safe local browser-capture parsing and protected session-file writes without shell execution; verify malicious capture text cannot execute and owner-only permissions on the verified host. POSIX 0600 is tested. Windows ACLs are best-effort and fail closed as `session_acl_unqualified`; that host was not manually tested.
- [x] 1.4 Enforce Walmart-only HTTPS destinations, redirects, TLS and bounded requests/pagination in the helper, replacing unsafe upstream defaults where needed; verify fake-server/transport tests reject off-site, insecure, stale-session and oversized/error cases without secret diagnostics.
- [x] 1.5 Document optional helper build, capture/session provisioning, expiration, disposal, toolchain/platform support and OS isolation limits; verify the documented build works without login and ordinary application installation requires no Go/helper.
- [x] 1.6 Add opt-in `-Pwalmart` Gradle lifecycle wiring for helper/test compilation, Go unit/integration tests, check/build, clean and installDist/distZip/distTar. Provision a pinned build-host Go SDK with checked-in archive hashes and read-only modules, disable implicit SDK switching, declare incremental inputs/outputs and emit target-labeled Walmart distributions containing the helper/onboarding script but no compiler, weights or secrets. Verified 2026-11-22: a base `./gradlew help --offline` made no Go download, a rewritten SDK digest failed with `no matching verified archive hash`, and an empty cache with `--offline -Pwalmart` failed with `Walmart Go SDK is not cached`.

## 2. Ledger source and exact normalization

- [x] 2.1 Add helper/session paths and positive time/output settings to configuration and `WalmartService` as the sole fetching source gated by `walmart.enabled` (default `false`); document these new settings and verify disabled operation needs no helper, removed guest/login settings have no compatibility aliases or fallback, and Amazon environment isolation remains unchanged.
- [x] 2.2 Add the bounded Go-backed Groovy fetcher with source-scoped environment filtering; verify timeout, excess output, invalid schema, nonzero exit and stale-auth fixtures return no usable failed-fetch payload while other retailers continue.
- [x] 2.3 Keep Go retrieval-only: preserve minimal source decimals, repeated ledger entries, original status/type and completeness without financial decisions. Move any existing helper domain normalization into Groovy. In Groovy normalize distinct final-charge occurrences, dates, currency and stable keys; verify equal-valued multiplicity, holds/refunds and malformed/misaligned ledgers. Verify successful empty history emits orders:[] while missing/null arrays remain invalid.
- [ ] 2.4 In Groovy normalize items, decimal quantities, pre-discount extended costs and separately identified discounts/tax/fees/tips; verify weighted items, duplicate display groups, net-versus-gross discount evidence without double counting, cancellations, unsupported precision/currency and unexplained residuals with independently expected fixture totals.

## 3. Unique matching and durable links

- [x] 3.1 Extend `TransactionMatch` and ledger matching with global candidate resolution, exact cents, charge dates and optional account/payment mapping; document mapping and verify no charge reuse or order-total fallback, equal-charge multiplicity and cross-order/account ambiguity.
- [x] 3.2 Replace generated Walmart parent item summaries with metadata-only enrichment: order date, full order total/currency, order ID and direct validated URL, preserving existing user memo; document an example and verify multi-charge totals, duplicate runs, unsafe URLs, no shortener calls, absence of item names and skip-on-overflow within 500 characters.
- [ ] 3.3 Add ledger dry-run reporting and source-specific skip reasons without marking completion; document preview and verify representative matches, failures and ambiguous assignments produce no HTTP writes.

## 4. Existing-category mapping and exception-first allocation

- [ ] 4.1 Add existing-category catalog reads and explicit category-ID shortlist/default validation with optional boundary descriptions; implement approved product mapping precedence and uncategorized unresolved exceptions, not automatic default fallback. Verify empty/duplicate/absent-default lists, renamed/deleted/hidden/special categories, out-of-list mappings, conflicting human evidence and plan isolation without category creation/suggestions or unnecessary hosted data uploads.
- [x] 4.2 Implement exact-cent reconciliation with all taxes, fees, tips and separately identified item/order discounts in the default category. Verify independent expected positive, zero and negative-default examples, exception-item discounts, unknown discount semantics and no artificial balancing lines.
- [x] 4.3 Require all final charges to match initially eligible unapproved, unsplit transactions before category allocation; verify missing gift-card/payment, approved/preexisting-split parent, zero/unsupported total, mismatch and refund cases stay link-only at most without partial allocation or a proportional fallback.
- [ ] 4.4 Implement deterministic descending-capacity selection and stable item filling to minimize exception-bearing transaction count. Verify independent expected $70/$30 and multi-transaction cases, equal-capacity ties, input-order independence, exact exception/transaction totals, item fragmentation, no-exception orders, zero remainders and single-line category-only writes.
- [ ] 4.5 Add explicit `link_only`/`exception_first` configuration and diagnostic preview; document compact memos, default-plus-exceptions accounting, adjustment policy, complete-payment requirements and immediate budget effects. No grocery itemization or required second review interface.
- [x] 4.6 Implement direct Groovy API-key OpenAI Decisions (gpt-6-luna) with complete correlated choice distributions/confidence and bounded requests. Verify official wire schemas and explicit model selection; no OAuth/Responses/subscription route or provider/billing fallback. Remove abandoned local-model runtime/registry/provisioning/config paths without affecting Amazon's Python bridge.
- [x] 4.7 Implement direct Groovy TypeSafe Jev (jev-1.14.0, /v1/systemone) with separate state/questions/criteria and named-answer schema adaptation to the common decision contract. Verify provider-specific limits, concrete model validation, full probabilities/confidence/usage and no floating alias, OpenRouter intermediary or cross-provider key forwarding. Use existing JVM HTTP/JSON, no Python/JS SDK.
- [ ] 4.8 Implement meaningful shortlisted choices mapped to stable local IDs plus abstention and independently qualified provider-specific acceptance. Verify invalid/non-finite/out-of-list/incomplete results, duplicate JSON keys/question names, inconsistent argmax/distributions, refusals, conflicting mappings, limits without silent truncation, hostile product text and failures never become defaults/writes. Keep monetary allocation in Groovy.
- [x] 4.9 Implement separate protected provider-key records, atomic setup/replacement/disconnection and POSIX owner-only access checks. Windows ACLs are best-effort and fail closed; that host was not manually tested. Verify no keys in YAML/arguments/Go/logs/fingerprints, no auth-refresh/replay loops, redirect isolation, JSON/deadline/concurrency/request/token bounds and bounded transient retries. A local HTTP server proves a 302 is not followed and a slow response stops inside the deadline. Disabled/link-only needs no provider requests; mappings/cache hits avoid inference. Disconnect explains console revocation rather than claiming remote deletion.
- [ ] 4.10 SKIPPED by the user: do not run another paid invocation. The comparison command and mock tests remain. The separate Jev and OpenAI runs already compared the same frozen inputs; a joint live two-provider invocation was not requested.
- [ ] 4.11 Persist decision provenance and correctly scoped inferred caches; verify endpoint/model/prompt/acceptance/shortlist/description/account changes invalidate reuse without token fingerprints, approved mappings override model output only within the shortlist, and partial-application model/config drift stops writes. Human correction updates mappings, not model weights or category lists.
- [x] 4.12 Add Groovy-backed onboarding asking OpenAI Decisions versus Jev and capturing that provider's key through hidden input after data/billing consent. Verify provider-scoped atomic activation, cancellation/cleanup, secret redaction, no implicit data-upload consent/enablement and unavailable hidden input handling. Explain TypeSafe's no-training commitment, U.S. processing and non-guaranteed retention/ZDR, and applicable OpenAI policy; no silently billed setup probe. No OAuth, Docker, Hugging Face or model-RAM setup remains.
- [ ] 4.13 Add read-only YNAB category listing by group/name with numbered multi-selection and default selection from that shortlist, then confirmed atomic config updates preserving unrelated settings and existing enablement/mode. Verify malformed/duplicate/out-of-range input, duplicate names, category deletion during setup, cancellation, concurrent file edits, reruns and no partial writes, category creation, live transaction writes or implicit enablement. Document the script so manual ID copying is unnecessary.

## 5. Safe YNAB split updates and recovery

- [x] 5.1 Extend YNAB DTOs/read-back and PUT payloads for category-only and unsplit-to-split updates; verify fake-server/schema tests preserve identity/amount/cleared state/memo, explicitly retain `approved: false`, null parent category only for splits, omit subtransactions for single-category writes and use exact negative milliunits.
- [x] 5.2 Enforce fresh-read eligibility and preserve approval/user work; verify initially approved or preexisting-split parents, tracking accounts, transfers, reconciled/deleted parents, special categories and concurrent edits are skipped without unapproving, replacing or rewriting them. Link-only mode preserves categories/approval.
- [ ] 5.3 Add protected atomic allocation journaling with local application serialization and separate per-charge link/category/split/review status plus item-to-line provenance. Verify a link-only parent can later allocate, prior journaled writes are recognized, category-only operations are not repeated and partial writes retry only unfinished operations.
- [x] 5.4 Verify unknown category/split write outcomes by remote read-back against saved intent, and stop after any applied-plan drift/conflicts; cover accepted-but-response-lost, rejected, changed-tip/ledger/category settings and user edits, and document manual recovery and non-atomicity across transactions.
- [ ] 5.5 Read human approvals/corrections through YNAB and learn only unambiguous approved item-to-existing-category mappings for future purchases. Verify unchanged approved rows, category corrections, moves to default, reordered IDs, fragmented-item conflicts, merged/deleted/repurposed rows, changed amounts, aggregate-default exclusions and cross-plan isolation. Never modify existing splits or auto-approve transactions.

## 6. Integrated offline acceptance

- [ ] 6.1 Exercise helper fixtures through Groovy normalization, Walmart enablement, matching, preview, mocked OpenAI/Jev classification/key setup/comparison, allocation, mock YNAB updates and restart recovery. Verify both capability specs without real sessions, paid inference or account writes; preserve removed-source/Amazon boundaries. Qualify actual model quality separately with explicit authorization before support claims.
- [ ] 6.2 Run Go tests/build, the repository build and unit/integration test scripts plus strict OpenSpec validation; verify unchanged Amazon coverage and retained Walmart model/matcher/processor coverage remain green, add replacement-source tests rather than restoring deleted fetcher tests, and ensure packaging remains usable with Walmart disabled and no helper installed.
- [ ] 6.3 Add CI coverage for base and `-Pwalmart` Gradle lifecycles and supported native OS/architecture packaging. Verify JVM-to-helper fixture execution and distribution launch without Go on PATH, correct target/executable permissions, SDK hash rejection, enabled/disabled builds in either order without stale artifacts, clean/incremental behavior and aggregated test reporting; no account credentials or model downloads.
- [x] 6.4 Linux x86_64 is the qualified host for the native launcher, helper, and onboarding. Apple Silicon macOS arm64 and Windows x86_64 have best-effort implementations and were not manually tested, by the user's instruction. Windows ACLs fail closed. Document that those hosts are untested rather than qualified. No OAuth callbacks, Docker/WSL, local models, or dependency downgrades.
- [ ] 6.5 Verify retrieval-only Go and Groovy-only processing boundaries across packaged flows: no inference-provider/YNAB keys or inference/financial decisions in Go, no local-model/OAuth requirements, no implicit data-upload consent, minimal captured remote payloads, and no live inference/account writes during builds/tests. Document paid API billing, provider isolation, comparison usage/cost caveats and operator recovery.

## Apply progress and remaining qualification

The Linux amd64 implementation now has retrieval-only Go evidence transport,
Groovy financial normalization and two bounded paid-provider wire adapters.
Abandoned local runtime/registry/config paths are removed. Account-free tests
exercise both adapters, malformed/partial distributions, independent confidence,
abstention, scoped credentials, request/token reservations, transient retries,
confirmed key/category activation and a frozen 12-calibration/60-heldout
comparison with metric/cost arithmetic. Mock provider decisions also run through
real fake-server YNAB allocation, restart/cache reuse and frozen-policy drift.

Checked provider-adapter tasks mean official schema plus mock verification,
not paid endpoint access or qualified predictive quality. Setup leaves both
providers unqualified; production inference refuses that state. A separately
authorized, one-time Jev-only synthetic comparison completed with 12/12 raw
calibration accuracy and 58/60 raw held-out accuracy; 48/48 held-out accepted
predictions were correct at 80% coverage. The frozen gates were not tuned, and
no production qualification was activated. It used 10 requests, no retries or
errors and a listed-base-rate estimated $0.001414812; actual billed cost is
unknown. No OpenAI calls, private purchase uploads or live account writes
occurred. This completed authorization does not permit further paid runs.

Post-push review found and corrected cached-decision/provenance dry-run drift
and duplicate-name category wire labels in separate regression-backed commits.
Previews now resolve local caches without inference or persistence, explicitly
reporting required/unavailable inference; colliding category names use their
existing group names for meaningful labels mapped locally to stable IDs.

Unchecked tasks still include partially implemented contracts, not just absent
code: broader provider quality qualification and actual billed cost, Windows owner-only ACLs,
hidden-console/native host matrix and real HTTPS deadline/containment tests;
the broader normalization/category/learning/diagnostic acceptance matrices;
and cross-platform CI/packaging execution. Groovy fails closed on unproven
currency/discount semantics rather than claiming a reconstructed gross basis.
Do not archive this change as fully accepted or call this a qualified release.

## Still unchecked

4.10 is skipped by the user and stays unchecked. The remaining unchecked tasks still lack a named verification that passed in this checkout: 2.4, 3.3, 4.1, 4.4, 4.5, 4.8, 4.11, 4.13, 5.3, 5.5, 6.1, 6.2, 6.3, and 6.5. Do not archive while those are open.

## Archive blockers

These tasks stay unchecked because this Linux orb cannot honestly complete them:

- 1.1 still needs a recorded `go mod verify` and a credential-free advisory review of the pinned module. The version is required; the reviewed commit is documented, not a `replace` pin.
- 1.3 and 1.6 cover POSIX session permissions and the opt-in Gradle lifecycle. Windows owner-only ACLs, base-build Go-resolution proof, SDK hash rejection, and uncached-offline failure are not executed checks.
- 4.8 and 4.10 are not complete. The comparison command and mock tests exist. The authorized runs were one Jev invocation and one OpenAI invocation, not one consented two-provider paid comparison, and neither provider has an independent qualification record. Production stays `qualified: false`.
- 4.9 Windows ACL and a real HTTPS deadline/origin test are not executed. POSIX records and injected-transport bounds are tested.
- 6.3 CI covers Linux amd64 and arm64 only. It does not launch a packaged distribution on macOS or Windows, and it does not reject a bad SDK hash.
- 6.4 is blocked. Apple Silicon macOS and Windows x86_64, including hidden key input and Windows ACLs, were not run. Linux helper tests do not qualify those hosts.
- 6.5 has no packaged-flow execution proving the Go/Groovy boundary beyond the unit and integration fixtures.

Added Linux-verifiable coverage for net-versus-gross discount fail-closed behavior, cancelled groups, empty and absent-default shortlists, and a three-charge descending-capacity fill with an equal-date tie. Those additions do not close the tasks above.
