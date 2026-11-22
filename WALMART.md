# Optional Walmart integration

Walmart is disabled by default; `link_only` is the default enrichment mode. The
Go source replaces the removed browser fetchers, with no Playwright, guest or
login fallback. Amazon installation and base Gradle builds require no Go,
Docker, model or Walmart session.

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
Windows session/journal ACL support is **not implemented**: protected operations
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
aliases. Start with `app.dry_run: true`. The helper returns a version-1 minimal
ledger (no addresses, cookies or raw HTTP payloads), not upstream diagnostics.

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
counts only, not raw purchase details. Dry runs never PUT or save completion.

## Categories, allocation and YNAB review

Run the installed application with `--walmart-categories [config.yml]` to read
existing YNAB categories, select numbered group/name choices and choose the
default from that shortlist. It performs GETs only. Confirmation revalidates
stable IDs and atomically writes protected YAML, detects edits made during
setup and preserves unrelated values, `enabled` and `enrichment`. YAML comments
and formatting can be normalized. Cancel or invalid input leaves the original
file unchanged. This separate category setup needs no Docker or model token.

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
safe links may still be added. The current Go normalizer deliberately marks
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

## Laya qualification: unavailable, not ready

`--walmart-setup` explains decimal model RAM (default 1,000,000,000 bytes;
explicit maximum 2,000,000,000) and returns unchanged when no qualified profile
exists. Docker engine/VM, JVM, helper and OS RAM are additional; disk/download
size is separate. Public artifacts do not need a Hugging Face token. Hidden,
read-scoped credentials may only be requested by a future verified provisioner
on an actual access/rate-limit failure; no global Hub login or token persistence.

The checked-in qualified registry is intentionally empty. The host-side Docker
launcher is guarded by that registry; it is not an operational model release.
No pinned worker image/full-runtime release or automatic provisioning is shipped
yet. Do not edit a manifest to claim readiness. Kev, GPU/cloud fallback and
model substitution are outside this change. Disabled/link-only paths never
probe Docker or fetch models.

Independent synthetic evaluation reports unchanged English and multilingual FP32
exceed both 1 GB and 2 GB during load. Multilingual BF16 fits below 2 GB in one
Linux Docker test (~1.769 GB peak), but quality fails: 29/60 raw correct and
15/17 accepted correct at the frozen probability >=0.8 / margin >=0.2 gate.
These measurements do **not** qualify either profile or other platforms. Laya's
state truncation flag also misses silently shortened question/options, requiring
independent full-token preservation checks before any future activation.

Linux amd64/arm64 images, Linux x86_64/macOS arm64/Windows x86_64 onboarding and
Docker Desktop backends remain an **unqualified intended matrix**. Native helper
tests do not prove container isolation, image safety, latency or whole-host RAM.
Readiness requires immutable artifact/runtime locks, license/advisory review,
local-only safetensors/config/tokenizer loading, full-token/choice checks,
held-out quality, measured loading/inference/headroom and actual containment.

## Account-free verification

Run `.agents/scripts/build.sh -Pwalmart`,
`.agents/scripts/run-unit-tests.sh -Pwalmart`, and
`.agents/scripts/run-integration-tests.sh -Pwalmart`. Enabled tests include Go
unit/contract tests and JUnit XML under `build/test-results/walmart*`; defaults
remain model-download/account-free. See `openspec/changes/walmart-integration/tasks.md`
for tasks still incomplete, especially model provisioning/qualification and
native cross-platform/ACL verification. Passing app fixtures are not live-account
or model readiness claims.
