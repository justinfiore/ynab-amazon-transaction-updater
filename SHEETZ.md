# Sheetz purchase enrichment

Sheetz is disabled by default. It requests only the verified `ANYWHERE` and `RETAIL_FUEL` purchase types from the mobile receipt feed. History depth, store scope, and coverage of in-store purchases are **not established**. An unmatched notice is an operational assumption, not evidence of an in-store purchase. The fallback can change or clear a prefilled YNAB category and affects budget balances, even while the transaction remains unapproved.

Configure `sheetz` in `config.yml` using the example. Set `accounts` to existing on-budget YNAB account IDs. Install the browser requirements in a Python environment, install Playwright's full Chromium build (not just the headless shell), and set `python_executable` to that environment's Python. The bridge runs Chromium headlessly with a Chrome-form user agent derived from its installed browser version and an English locale; this does not guarantee that Sheetz will allow access. The credentials file must be an owner-only, regular JSON file with `username` and `password` fields; do not put credentials or tokens in YAML or command arguments. The token file's parent directory must already exist. The bridge creates the token file with owner-only permissions and reuses an unexpired token. It performs at most one website sign-in per run and stops on access blocks or challenges. A failed fetch or invalid response envelope stops all Sheetz writes without treating the history as empty.

Start with `app.dry_run: true` and inspect the Sheetz preview. For writes, set `app.dry_run: false` and independently enable `sheetz.memo_writes` and/or `sheetz.category_writes`. `gasoline_default_category_id` must name an eligible existing category; otherwise fuel category writes are skipped. For unmatched transactions, `unmatched_default_category_id` selects an eligible existing category; omitting it clears a prefilled category. Approved, split, changed, and ambiguous transactions are not updated. Malformed items are omitted from the memo summary while valid receipt fields remain usable; logs identify the record position, field, and reason without exposing raw values. A receipt missing matching-critical data is skipped, but other valid receipts remain available for preview. Since the skipped receipt might compete with a valid match, that run makes no Sheetz writes and does not propose unmatched fallbacks.

For online categorization, configure `sheetz.categorization.categories` with eligible existing category IDs. Copy the provider, model, limits, acceptance thresholds, and protected provider credential path from a configured Amazon classifier, use `policy_version: amazon-choice-v1`, and explicitly set `sheetz.categorization.consent: true` to allow sending Sheetz item titles to the provider. Dry runs do not make inference requests; an uncached classification is reported as requiring inference. Missing prices, unresolved classifications, and ineligible categories prevent category or split writes without blocking memo updates. The classifier's inferred decisions are kept only for the current run.

For example, after using `--amazon-setup` to provision a protected OpenAI provider record, add the following under `sheetz` and replace the category IDs and absolute provider-record path. Enabling `category_writes` and disabling dry-run can change live budget balances before approval; the classifier may make paid requests.

```yaml
categorization:
  categories:
    - id: "existing-food-category-id"
    - id: "existing-drink-category-id"
  consent: true
  provider: openai
  model: gpt-6-luna
  policy_version: amazon-choice-v1
  credentials_file: "/absolute/path/to/protected-provider-record.json"
  connect_ms: 5000
  read_ms: 15000
  total_ms: 20000
  max_input_bytes: 16384
  max_response_bytes: 262144
  batch_size: 8
  concurrency: 1
  max_requests: 50
  max_retries: 1
  acceptance: { probability: 0.8, confidence: 0.8, margin: 0.2 }
```

For an eligible online match, item `totalPrice` values are weights: the matched order total, including tax and discounts, is distributed proportionally with deterministic cent rounding. These are approximate category amounts, not a reconstruction of each item's actual tax or discount. A combo represented by the feed as one item stays one item. The order total and date are sufficient matching evidence; no secondary payment source is required. No receipt-specific online URL has been verified; online memos use the order number and the purchase-history page, while fuel memos do not claim a web receipt link.
