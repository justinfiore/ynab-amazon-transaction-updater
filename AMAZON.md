# Amazon enrichment: setup and safety

Amazon defaults to `amazon.enrichment: memo_only`. This updates summaries and
links, not categories. The optional `item_categories` mode writes categories or
item splits into the **live budget**, affecting available balances immediately
even when a transaction is approved. Existing approval is preserved. Keep `app.dry_run: true` until
you have reviewed the exact preview. No live account or paid inference is needed
to run the repository's offline acceptance tests.

Dry runs print each Amazon preview as JSON, including exact proposed changes,
explicit null categories and skip reasons. The summary counts transactions with
proposed changes, not completed writes. Payload amounts are signed YNAB milliunits
(1000 = 1 currency unit). Previews contain local transaction and purchase details;
review logs before sharing them.

## Independent setup

Build the ordinary JVM distribution with `./gradlew installDist` (Java 25).
Use the generated launcher under `build/install/<project-name>/bin/` with:

- `--amazon-categories [config.yml]`: select numbered existing categories. There
  is no Amazon default. This does not change Walmart, fetching or enrichment mode.
- `--amazon-setup [config.yml]`: select OpenAI Decisions or direct TypeSafe Jev,
  read the Amazon-specific disclosure, give explicit consent and enter a hidden
  provider-issued key. Setup makes no paid probe and does not enable writes.
- `--amazon-payment-accounts [config.yml]`: confirm an exact normalized payment
  identity such as `python:visa:1234` against an eligible account ID in the
  configured plan. Repeat for each instrument. Confirm it from your own payment
  records; account names/payees and last four alone are not identity evidence.
- `--amazon-disconnect [config.yml]`: remove Amazon's provider reference after
  confirmation. A protected key still referenced by Walmart in the **same config**
  is retained. This is not remote revocation. Other config files are not scanned:
  use separate setup-created credential records for independent configs, and
  revoke a compromised key in the provider console.

Provider credentials are protected local records under `.amazon-credentials`,
not values in YAML or arguments. Retailer consent is independent even when a
credential record is intentionally shared. Only product titles and shortlisted
labels/descriptions leave the machine. Purchase titles can be sensitive. Model
thresholds are not a quality guarantee; no live Amazon model profile is qualified.
Approved local item mappings are used before inference. No provider is required
for visible uncategorized itemization. Dry runs use mappings/cache only and mark
necessary uncached inference as `inference_required`.

After setup, opt in explicitly to `amazon.enrichment: item_categories`. Maintain
the independent `amazon.categorization.categories`, `amazon.payment_accounts`
and `amazon.journal_file` settings. Do not copy Walmart's default or mappings.
Mappings are plan-specific: changing `ynab.budget_id` requires reconfirmation.

## Evidence and source limits

The selected purchase source remains email or Python, with additive CSV as
before. Payment evidence defaults on in Python mode, off in email/CSV mode.
`amazon.transaction_evidence.enabled: true` elsewhere is an explicit opt-in to
launch the configured Python bridge/session; dormant paths alone launch nothing.
The Python runtime/session settings and reviewed hash-locked dependencies are
documented in [README](README.md). Email/CSV prices and order totals alone cannot
authorize allocation or category-clearing fallback. Supplemental transaction
events do not replace the selected purchase source or bless its defaulted amounts.

Python is usable without IMAP. Optional
`amazon.refund_email_evidence.enabled: true` explicitly opts Python mode into
the configured mailbox for refund-item evidence, default false. Email mode uses
its existing mailbox parser without requiring Python unless payment evidence is
explicitly enabled. An unavailable optional source fails closed with a bounded
diagnostic; it never guesses missing item amounts.

The bridge qualifies a bounded US-English detail-totals layout and JSON or
HTML-form payment history using the real pinned `amazon-orders==6.0.0` parser.
It retains nullable quantities, exact-cent costs, adjustment/funding provenance
and parse/completeness flags, never recipients or raw pages. The observed blank
quantity component may infer one only for a single, non-weighted item whose
explicit USD price exactly equals the subtotal, with no conflicting count,
quantity badge or parse failure. Inferred quantities carry `quantity_source:
single_item_subtotal`; all total, tax, funding and payment checks still apply.
The detail label `Your Coupon Savings` is accepted as the existing coupon discount,
not a second adjustment. Unknown labels,
currency, other missing quantities, overlapping promotions, count mismatches,
incomplete pages or ambiguous multi-order
references block financial authority. Qualified forms require complete row coverage,
one signed-dollar amount and masked payment identity per row, one matching order
link/reference, known status text, and valid pagination controls. Pending form
rows remain incomplete payment competitors, not completed-payment authority.
Unknown or empty forms remain unqualified. Amazon's exact HTTP-success error page
asking to refresh gets at most one refresh per history retrieval, preserving any
pagination input. Persistent errors still block payment completeness; an error
page never establishes an empty ledger. History totals may be payable rather
than full order value; a residual is never assumed to be tax or gift funding. Pages are capped at
20 per history retrieval and detail orders at 200, additionally bounded by the
existing subprocess timeout/output limits. Global payment history stops on a
page wholly older than the lookback plus 14-day margin; equal-boundary dates
remain included. Rows may be reordered within pages, but page date ranges must
advance newest-first or the retrieval fails closed.
Order-specific payment ledgers are never truncated by that date window.

Recent purchase events remain payment competitors even when the order is outside
purchase lookback or its charge ledger is unavailable. Out-of-window purchase
events are competition-only: they do not fetch old purchase details or authorize
historical writes. Corroborating global/order histories count an occurrence once;
when each history has exactly one charge for the same order, amount, card and
currency, differing payment dates are retained as alternatives for that occurrence.
Repeated charges within either history are not collapsed. Global payment evidence
retains a 14-day margin before lookback to cover the bank matching window. Credits
in that margin are competition-only too; they do not trigger old-order retrieval
or historical credit writes.

Recent credit events are discovered independently of purchase lookback; an older
original order can be fetched by real ID. The pinned library has cumulative
refund totals and document metadata, **not structured per-refund item amounts**.
The email parser retains explicit product links, quantities, final `Item refund:` /
`Refund amount:` amounts, or extended `Refund merchandise:` bases with explicit
`Promo discount`, `Refund discount`, `Withheld fee`, `Refunded tax`,
`Refunded shipping` and `Refunded fees` components. Final-net amounts cannot be
combined with components. Only independently reconciled, uniquely event-associated
notices qualify. Subtotal-only, generic-title, mixed-tender or incomplete notices
remain nonallocating. Synthetic fixtures qualify these layouts; this is not a
promise that every real notification contains them.

## Accounting and fallback

Purchases first match qualified Python order details: an explicit mapped card,
exact reconciled bank amount after known gift funding, and a uniquely closest
order date within 14 days. The order must come from purchase history within the
lookback, not an older original fetched for a refund. One category or item splits
can be assigned without payment history or a complete order-specific ledger.
The parent memo starts with `🤖❓` when the match lacks a completed, unambiguous
payment-history event; `🤖` indicates payment corroboration. Split item memos keep
their ordinary robot prefix. Missing-item summaries start with `❌`.

Payment history can corroborate order matches or resolve orders that cannot be
uniquely matched from their order data. Payment-based matches require a unique
completed exact-cent event, mapped payment/account identity and a bank date within
14 days of either payment-history date (purchases may also use the order date).
Refund credits never use the original purchase date. The plan/source must be
USD. Equal-distance ties and associations claimed by multiple parents remain
blocked; excluded and human-edited parents continue to compete. Known multiple
charges or conflicting payment amounts, cards or currencies cannot be bypassed
by the order-only path. Refunds still require completed payment evidence.
Unavailable optional payment history alone does not block qualified order matches.
An order-only purchase association continues to reserve that order when its bank
parent leaves the lookback window; a later payment event cannot allocate it again.
Existing trusted payment associations are retained when order details become
qualified, so metadata fallbacks can progress without changing association keys.
Already completed financial allocations are not rewritten just to change the marker.
All item costs
and nonoverlapping adjustments must explain full order value exactly. Discounts
apply to explicitly identified items first, otherwise proportionally. Known tax,
shipping and fees are proportional over discounted merchandise, rounded by stable
largest remainder. There is no default, residual plug or one-cent tolerance.

One external charge plus known gift funding is supported. Adjust full-order item
costs first, then proportionally scale to the bank charge. For $20/$40 merchandise
plus $5 tax, item costs are $21.67/$43.33; $15 gift funding leaves bank allocations
$16.67/$33.33. There is no gift offset row. Fully gift-funded purchases need no
YNAB target, write or inference. Multiple external purchase charges are deliberately
nonallocating; each independently trustworthy eligible charge may get fallback.

Refunds require event-specific identified products and reconciled net credits,
not guessed percentages, original full order items or subset sums. Explicitly
refunded tax/shipping/fees follow the same allocation convention; withheld fees
are deductions, not negative rows. Approved provenance-backed original item
categories take precedence, with out-of-list/conflicting evidence unresolved.
Refunds use positive amounts; original purchases are never edited.

Keep one nonzero row per item, even for equal categories. One nonzero item uses
a parent category-only write; zero items never learn that parent's category.
All nonzero items must have accepted categories before creating a category or
split allocation. Otherwise the parent remains a retryable metadata fallback,
not a completed categorization. Provider operational errors block item allocation,
not an independently eligible fallback. Older completed records with blank
categories can be reconsidered only while the current parent and every split
still match the saved app result. Existing partial splits are left intact until
all categories resolve. Manual category, split, memo, or approval edits block
automatic rewriting. New journal entries retain the allocation/fallback reason.

**Fallback is a category write:** in `item_categories`, a trustworthy eligible
match lacking item allocation confidence gets known robot/date/total metadata,
the full mobile order URL and explicit `category_id: null`, no splits, unchanged
amount and approval. Missing/ambiguous event identity or a human-owned
category or split blocks it. `memo_only` never clears categories.
Unknown fallback totals/dates are omitted, not invented. Parent memos preserve
user text and required fields; generated titles yield first to the 500-character
limit. If required content cannot fit, the whole update skips. The mobile URL is
constructed locally; actual phone-app handoff is not qualified.

## Review and recovery

Keep the protected Amazon journal across runs (never commit it). It stores
ownership, distinct event/item provenance, separate memo/fallback/category/split
completion, classifier scope and durable intent. A fresh baseline check precedes
each PUT; uncertain outcomes are read back before any retry. Human changes since
a saved result stop further updates. Financial writes additionally require
unsplit, unreconciled parents in eligible accounts; approved parents must be
uncategorized. Default memo-only
enrichment may add metadata to approved, split or reconciled parents, preserving
their approval, categories, split rows and all other financial fields. Neither
mode updates deleted or transfer parents. There is no distributed compare-and-swap
in YNAB: avoid editing the same transaction during a run.

Switching to `memo_only` suspends unapplied financial intents, including explicit
null-category fallback. Already accepted writes can still be confirmed by
read-back; they are not rolled back. Re-enabling `item_categories` permits replay
of the saved intent only after the normal unchanged-policy and baseline checks.

Confirmed fallback can advance once added noncontradictory evidence explains a
first allocation. Source/bank identity contradictions stop it. Completed item
allocations are frozen against source/policy/mapping drift. Lost-response and
restart recovery do not authorize replay against changed policy. Restore the
journal from backup rather than deleting it to force a retry. There is no
automatic historical backfill: legacy processed IDs and unowned robot markers
remain excluded. Later distinct credits are separate events, not prior-credit
drift. Approved stable item rows can teach retailer/plan/product mappings;
ambiguous edits, missing product IDs and zero-value rows cannot.

## Offline checks

Run `.agents/scripts/build.sh`, `.agents/scripts/run-unit-tests.sh`,
`.agents/scripts/run-integration-tests.sh`, and
`npx @fission-ai/openspec validate --specs --strict`.
For real-library parser fixtures (no network/account access), install
`requirements-amazon-orders.txt` with hash checking and run
`python -m unittest discover -s scripts -p 'test_amazon_orders_bridge.py'`.
Optional Walmart helper checks use `-Pwalmart walmartUnitTest walmartIntegrationTest`.
The ordinary JVM suites include shared-classifier and fake-YNAB recovery tests
without Go or Walmart enablement.

The implemented `amazon-item-categorization` change is archived under
[`openspec/changes/archive/2026-11-24-amazon-item-categorization/`](openspec/changes/archive/2026-11-24-amazon-item-categorization/).
Its requirements are synced into the canonical
[Amazon enrichment](openspec/specs/amazon-transaction-enrichment/spec.md),
[Amazon fetching](openspec/specs/amazon-order-fetching/spec.md),
[shared classification](openspec/specs/item-classification/spec.md) and
[Walmart enrichment](openspec/specs/walmart-transaction-enrichment/spec.md) specs.
Archiving records implementation completion, not additional live-source or model
qualification; the operational limits above still apply.
