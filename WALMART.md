# Optional Walmart integration

Walmart is disabled by default; `link_only` is the default enrichment mode. The
Go source replaces the removed browser fetchers, with no Playwright, guest or
login fallback. Amazon installation and base Gradle builds require no Go,
inference key or Walmart session. Walmart inference uses paid API keys only;
there is no local model, Docker requirement, OAuth or subscription route.

## Build and session provisioning

Java 25 and the Gradle wrapper are required. `./gradlew -Pwalmart installDist`
provisions the hash-pinned Go 1.27.4 SDK for the build host, compiles the
repository-owned helper with read-only modules and packages it in `bin/`.
`distZip`/`distTar` use `walmart-<os>-<arch>` classifiers. The compiler, module
cache, weights and sessions are not shipped. An ordinary `installDist` omits the
helper, including after an enabled build. Cached enabled builds support
`--offline`; uncached SDK/modules fail rather than implicitly downloading.
SDK archives are pinned in `gradle/go-sdk-sha256.json`, from the official Go
download manifest. The module pins reviewed `walmart-client-go/v2` v2.2.1
(`d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`), with checked-in `go.sum`.

Native Linux amd64 is exercised here. SDK hashes also cover Linux arm64,
macOS arm64 and Windows amd64; availability is not platform qualification.
Windows session/journal/provider-key ACL support is **not implemented**: protected operations
fail closed rather than writing insecure files. No Intel Mac SDK is offered.

Log into Walmart manually in your own browser. In browser developer tools,
capture a successful order-details request as cURL text and save it in an
owner-readable file outside the repository. Do not paste it into chat or
execute it. It contains account-session credentials. Send the helper one JSON
request on stdin (absolute paths):

```json
{"version":1,"action":"import","capture_file":"/absolute/private/capture.txt","session_file":"/absolute/private/walmart-session.json"}
```

The importer tokenizes the capture using the reviewed client; it never executes
shell text. POSIX session writes use an owner-only temporary file and rename.
Delete the capture after import. Protect the session and its parent directory;
refresh it manually when requests fail/expire. Delete the session to dispose of
local credentials; logging out/revoking the browser session controls server-side
validity. Do not commit sessions. The helper has Walmart-only HTTPS GET egress,
no proxies/redirects, 30-second requests, bounded pagination/bodies and fixed
non-secret failure output. It is **not** an OS sandbox: a compromised native
helper runs with your OS user's privileges.

## Configuration

See `config.example.yml`. `helper_executable` may be omitted in an installed
Walmart distribution. `session_file` is required. Time/output/page limits must
be positive; `max_pages` is at most 20. Removed browser configuration has no
aliases. Start with `app.dry_run: true`. The helper returns version-1 allowlisted
source evidence (no addresses, cookies or raw HTTP payloads), not upstream diagnostics.
It preserves decimal amounts and repeated ledger entries; Groovy owns currency,
final-charge filtering, stable occurrence keys, item deduplication, adjustments,
classification, matching and all financial/YNAB processing. No inference or YNAB
key is passed to Go. Successful empty history is an explicit `orders: []`.

Walmart requires a verified USD YNAB plan before fetching/matching and rechecks
that currency before enrichment. Non-USD, missing or unavailable plan currency
skips all Walmart links and allocation writes without affecting Amazon; no
currency conversion or assumed USD fallback is performed.

Charge amounts match exactly in cents within 14 days of the charge date, not
the order date. Distinct equal charges are a multiset. Competing global
assignments are skipped, not selected by list order; order totals are never a
fallback. Optional `payment_accounts` maps a normalized payment identifier
such as `CREDITCARD:VISA:1234` to an existing YNAB account ID. Disambiguate
cross-order/account collisions instead of forcing a match.

Parent metadata preserves user text, contains no generated product names and
never calls a shortener. Example for a $70 charge in a $100 USD order:

```text
User note | 2026-11-20 | Total $100.00 | #123 | https://www.walmart.com/orders/details?orderId=123
```

The whole memo must fit 500 characters; overflow skips the write. Matching and
allocation previews are returned by the processing API; normal logs include
counts only, not raw purchase details. A dry run reports the planned category
lines or a source-specific skip reason such as an ambiguous charge, an
unqualified provider, or a changed parent. It does not PUT, mark the order
complete, or save a journal. Compact memos keep the existing user text, the
order date, the full order total, the order ID, and the direct Walmart URL.
They do not list item names.

## Categories, allocation and YNAB review

Run the installed application with `--walmart-categories [config.yml]` to read
existing YNAB categories, select numbered group/name choices and choose the
default from that shortlist. It performs GETs only. Confirmation revalidates
stable IDs and atomically writes protected YAML, detects edits made during
setup and preserves unrelated values, `enabled` and `enrichment`. YAML comments
and formatting can be normalized. Cancel or invalid input leaves the original
file unchanged. This separate category setup needs no inference key or remote model call.

Explicitly choose `exception_first` only after reviewing its immediate budget
effects. **Unapproved transactions are real YNAB changes, not drafts.** Only
existing eligible shortlist IDs may be used. No category creation, suggestions,
hosted fallback or model training is provided. Trustworthy approved product
mappings take precedence. Unknown/conflicting products remain uncategorized
exceptions, never implicitly become default spending.

The entire order must have supported USD final payments matched to unapproved,
unsplit, non-transfer, non-reconciled transactions in open on-budget accounts.
Incomplete payments, refunds, unknown currency/gross-discount semantics,
unexplained residuals and negative default remainders prevent allocation; unique
safe links may still be added. The Groovy normalizer deliberately marks
nonzero savings with unproven gross/net semantics incomplete, not guessed gross.

All separately proven tax/fee/tip costs increase the default; discounts decrease
it. Exception prices are proven pre-discount extended costs. For groceries $80,
Household $20, tax $5, fee $3, tip $2 and discount $10: Household is $20, default
$80. With $70/$30 payments, place $20 Household + $50 default on $70 and simply
categorize $30 as default. Default groceries are not itemized. Larger exceptions
fragment exactly in stable item order across descending payment capacities.
This is bookkeeping placement, **not evidence of Walmart item-to-charge routing**.
Single-line results remain unsplit; zero default lines are omitted.

The Walmart-only API path uses `/plans/{id}` GET/read-back and PUT with explicit
`approved: false` for allocation. It does not change identity, amount, account,
date, payee, import ID, flags or cleared status. Fresh reads stop on human edits.
Review, correct and approve in YNAB itself. Future reads learn only identifiable
approved item rows; all fragments must retain provenance. Aggregate default
lines do not teach products, and conflicts do not authorize a model override.

## Retry and manual recovery

The POSIX owner-only journal stores frozen plans, pending intents, per-parent
link/allocation/review state and item provenance. A lock serializes local runs.
The intent is saved before PUT; uncertain outcomes are read back, not blindly
retried. Earlier link-only writes do not block allocation. Previously journaled
splits are distinguished from human splits. Ledger/settings/mapping drift after
an intent stops remaining application. Multi-parent updates are **not atomic**
across YNAB; a successful first write can remain when a later one fails.

On a conflict, compare the journal's baseline/intent/result with YNAB and the
linked order manually. Preserve the journal for investigation; do not delete it
to force replanning over existing splits. Correct/review in YNAB. The program
does not rewrite existing splits or auto-approve anything. Back up protected
journals/configs as sensitive financial data, outside source control.

## Paid Groovy inference and protected onboarding

Run the distributed launcher with `--walmart-setup [config.yml]` in an interactive
terminal. It asks for OpenAI Decisions or Jev **before** hidden key input. No
hidden console means no key prompt/save: do not use an echoed stdin workaround.
Setup explains data transfer and billing, asks consent, lists existing numbered
categories/default, revalidates IDs and confirms a protected atomic config save.
It never enables fetching or exception-first mode. Setup checks key-record shape
locally only; there is no silent paid probe or claim of verified remote access.

Routes are fixed, independent JVM HTTP contexts:

- OpenAI: `https://api.openai.com/v1/decisions`, `gpt-6-luna`, paid OpenAI API key.
- Jev: `https://api.typesafe.ai/v1/systemone`, `jev-1.14.0`, TypeSafe-issued key.

No Responses, Codex/ChatGPT OAuth, OpenRouter, key refresh, floating aliases or
provider/billing fallback. Only uncached product titles plus selected category
labels/descriptions and fixed instructions are sent. Titles themselves can be
sensitive. Local product/category/account/order IDs, payment data and memos are
not sent. Duplicate/ambiguous category labels fail closed rather than silently
collapsing options. Money remains deterministic Groovy arithmetic.

TypeSafe commits to no input training, **not default zero retention**; its policy
allows necessary retention and describes U.S. processing. Enterprise ZDR requires
account-specific arrangements. Read https://typesafe.ai/legal/privacy-policy and
https://typesafe.ai/legal/data-processing. For OpenAI read
https://platform.openai.com/docs/guides/your-data; API access alone is not ZDR.

Keys are separate provider-tagged 0600 atomic records under a 0700
`.walmart-credentials` directory beside the selected config. YAML holds only
their paths. A random connection ID scopes caches, never a key hash. Source
control ignores this directory. Preserve owner-only permissions and do not back
it up to source control. A replacement stages a new immutable record, then
activates it with config; cancellation/save failure cleans staged keys. Older
confirmed records remain available for separately configuring comparison; dispose
of unused records and revoke old keys in the provider console. Never copy a key
to YAML, shell arguments, Go input, environment exports or bug reports.
`--walmart-disconnect [config.yml]` confirms removal of the selected local key
and route settings, preserving categories, mode and other provider records.
This does not revoke the credential remotely: do that in the provider console.
Windows ACL protection and macOS onboarding remain unqualified; protected key
operations fail closed without the verified POSIX security contract.

Setup writes explicit connect/read/total deadlines, input/response/batch limits,
sequential concurrency `1`, per-run request/token reservations and at most one
transient retry (configurable 0–2). Each retry consumes a fresh request/token
reservation. Only HTTP 429/500/502/503/504 may retry, with bounded backoff and
Retry-After; long Retry-After stops instead of being shortened. Redirects,
auth failures and uncertain transport outcomes never replay. Automatic HTTP
retries/cookies/redirects are disabled. Missing usage remains unknown, not free;
failed attempts retain their full reserved token allowance. Local token bounds
are conservative reservations, not a guarantee of the provider's eventual bill.

Validated decisions require complete correlations and distributions, finite
probabilities and separate confidence, bounded distribution sum and agreement
between choice and unique argmax. A bad batch returns no partial decisions.
Abstentions remain uncategorized exceptions, never Groceries. Inference failure
prevents category/split allocation; safe metadata-only links can still proceed.
Approved mappings take precedence; conflicting approved evidence vetoes inference.
Dry runs never upload. Cache entries include provider/model/policy, scores and
plan/product/title/shortlist/descriptions/acceptance/connection fingerprints.
Changes invalidate inferred reuse and stop already-frozen application plans.

**Neither paid provider is quality-qualified by this implementation.** Setup sets
`acceptance.qualified: false`; production makes no inference requests under an
unqualified policy. Example probability/confidence/margin gates are test values,
not calibrated recommendations. Separately authorize synthetic evaluation,
calibrate provider-specific gates without held-out tuning, review the evidence
and record the acceptance `evidence_id` and exact `qualified_for` fingerprint
before deliberately enabling the matching provider/model/policy. The fingerprint
binds the actual labels/descriptions, prompt, route/model and gates; changing any
of those removes qualification. A boolean flag alone is insufficient. Comparison
prints this fingerprint for its frozen shortlist, not for arbitrary private
shortlists. Contract tests cannot establish accuracy, latency or cost.

## Separate synthetic provider comparison

`--walmart-compare comparison.yml` loads only a dedicated provider configuration,
not the application config, Walmart session, YNAB service or production journal.
Copy each explicitly configured provider's `categorization` block to
`providers.openai` / `providers.jev` in that dedicated file, keeping only paths to
their separate protected records. Include the model/policy, acceptance and all
positive limits from setup. Both must be configured. The command prints each
request/token budget and requires typing `PAY BOTH PROVIDERS`; builds/tests
never invoke paid calls. For an explicitly authorized single-provider run use
`--walmart-compare comparison.yml --provider jev` (or `openai`); only the selected
provider record is needed and consent becomes `PAY JEV ONLY` (or `PAY OPENAI ONLY`).
Omitting `--provider` retains the two-provider mode and its two-provider consent.

Both receive the same frozen synthetic/public titles, shortlist/descriptions and
abstain instructions. The earlier 60-title fixture and reference policy are
preserved byte-for-byte under `src/main/resources/walmart-comparison`; a separate
12-title calibration split does not reuse held-out rows. The prompt has a new
version, so earlier local-model numbers are historical, not directly comparable
measurements of this prompt. Configure gates using calibration evidence only;
the command reports configured and common fixed gates separately and never tunes
thresholds against held-out answers or activates production acceptance.

JSON output records dataset/policy fingerprints, concrete models, calibration and
held-out raw/accepted accuracy, coverage, abstention, default precision, exact
non-default recall, failures, item-completion latency (batch completion, not
amortized latency), throughput, elapsed time, requests/retries and token usage.
Undefined precision is null, not perfect accuracy. Missing/failed predictions
cannot count as correct abstentions. Actual billed cost is unknown. USD per 1,000
items is labeled a base-rate estimate using dated 2026-11-22 input rates of
$0.10/M (OpenAI) and $0.042/M (Jev), free output, excluding cache discounts,
regional/long-context premiums and account pricing; incomplete/failed usage
produces unknown cost, not a cheaper-provider claim.

## Account-free verification

Run `.agents/scripts/build.sh -Pwalmart`,
`.agents/scripts/run-unit-tests.sh -Pwalmart`, and
`.agents/scripts/run-integration-tests.sh -Pwalmart`. Enabled tests include Go
unit/contract tests and JUnit XML under `build/test-results/walmart*`; defaults
remain model-download/account-free. See `openspec/changes/walmart-integration/tasks.md`
for tasks still incomplete, especially paid quality qualification and
native cross-platform/ACL verification. Passing app fixtures are not live-account
or model readiness claims. Go has no inference dependency; both paid wire
contracts and comparison use synthetic fake transports in the default tests.

A separately authorized one-time Jev-only synthetic test on 2026-11-22 used the
unchanged 12 calibration and 60 held-out titles, batch 8, concurrency 1, at most
10 requests and no retries. Calibration raw accuracy was 12/12; held-out raw
accuracy was 58/60 and accepted accuracy 48/48 at 80% coverage. Fixed gates were
not tuned. It consumed 33,686 input and 10,199 output tokens; the listed-base-rate
estimate was $0.001414812, with actual billed cost unavailable. No OpenAI calls,
private purchases or live YNAB writes occurred. Neither provider is production
qualified, and that one-time authorization does not permit further paid runs.
