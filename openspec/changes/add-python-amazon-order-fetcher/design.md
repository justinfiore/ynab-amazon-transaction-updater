## Context

`AmazonService` currently constructs the concrete `AmazonOrderFetcher`, whose `fetchOrders()` method performs all IMAP search and email parsing. It then optionally appends CSV orders. Downstream matching consumes only `List<AmazonOrder>`, so the automatic source can be made pluggable without changing transaction matching.

The selected upstream dependency, `amazon-orders`, is an unofficial Python library/CLI that scrapes Amazon's consumer website. Its current PyPI release is 4.4.7, it requires Python 3.9 or newer, supports persisted cookies/configuration, and can fetch history with full details. Its stock `history` command prints presentation text rather than a stable JSON format and does not print complete item fields. Parsing that output directly would couple this application to human-oriented formatting.

The integration crosses Java/Groovy and Python process boundaries and handles credentials, cookies, network operations, potentially long full-detail fetches, untrusted output, and a child process that can fail or hang. The design must fail closed for order import while allowing the rest of the updater to continue safely.

## Goals / Non-Goals

**Goals:**

- Define a small source-agnostic `AmazonOrderFetcher` contract and preserve the current email behavior in its own implementation.
- Add an explicitly selected Python implementation backed by `amazon-orders` without adding Python to the Java process.
- Produce deterministic, versioned, machine-readable output and map it to the existing Groovy models/sign conventions.
- Bound child-process execution time and output, prevent stream deadlocks, terminate hung descendants, and return an empty list on fetch-level failure.
- Keep credentials out of command arguments/logs and support the upstream persisted-session/configuration workflow.
- Keep CSV aggregation independent of the selected automatic fetcher.
- Document and test installation, authentication, configuration, operation, limitations, and recovery.

**Non-Goals:**

- Embed CPython or reimplement Amazon scraping in Groovy.
- Treat `amazon-orders` as an official/stable Amazon API.
- Parse the stock CLI's human-readable `history` output.
- Make the application install Python, create a virtual environment, or authenticate interactively during a normal updater run.
- Guarantee that Python mode reproduces email-only synthetic Subscribe & Save IDs or email refund-event dates. Email mode remains available when those notification semantics are required.
- Change CSV parsing, YNAB matching, memo generation, or Walmart behavior.
- Automatically fall back from a failed selected fetcher to another credential source; an empty result and actionable diagnostic is safer than silently changing source semantics.

## Decisions

### Use an interface and explicit implementations

Change `AmazonOrderFetcher` into an interface with `List<AmazonOrder> fetchOrders()`. Rename/move the current concrete logic to `EmailAmazonOrderFetcher implements AmazonOrderFetcher`. Add `PythonAmazonOrderFetcher implements AmazonOrderFetcher`. `AmazonService` will receive or construct exactly one automatic fetcher based on validated configuration, then append CSV orders as it does today.

Constructor injection will be supported so service tests can use a mock fetcher without reflection or real processes. Email remains the default selection to preserve existing configurations.

Alternative considered: keep the concrete class and add branching inside it. Rejected because it conflates unrelated protocols, makes process behavior hard to test, and does not provide the requested interface boundary.

### Add a repository-owned bridge CLI instead of parsing stock presentation output

Add a small Python entry point (for example `scripts/amazon_orders_bridge.py`) that imports the installed `amazonorders` package, uses `AmazonSession` and `AmazonOrders`, and writes one versioned JSON document to stdout. Human diagnostics go to stderr. `PythonAmazonOrderFetcher` invokes the configured Python interpreter with this script and explicit non-secret options.

The bridge invocation will accept the upstream config path and exact lookback days. It will request sufficient upstream history, enable full details so items are available, filter to the exact configured window by order date, and emit a bounded schema containing only fields needed for transformation. It will share the config/cookie paths used by `amazon-orders login`.

The bridge schema is versioned from its first release:

```json
{
  "schema_version": 1,
  "orders": [
    {
      "order_number": "123-4567890-1234567",
      "order_placed_date": "2026-08-01",
      "grand_total": 42.17,
      "payment_method": "Visa",
      "payment_method_last_4": 1234,
      "cancelled": false,
      "items": [
        {"title": "Example", "asin": "B000000000", "price": 42.17, "quantity": 1}
      ]
    }
  ]
}
```

Alternative considered: invoke `amazon-orders history --full-details` and parse stdout. Rejected because upstream 4.4.7 emits banners and human-oriented labels, does not expose a JSON mode, and its formatted output omits a stable complete item schema.

Alternative considered: call Python in-process. Rejected because it adds native embedding complexity and weakens timeout/termination isolation.

### Select the source and process controls in YAML, but keep account secrets upstream

Extend `amazon` configuration with this supported shape (final names may be adjusted only if existing naming conventions require it during apply):

```yaml
amazon:
  order_fetcher: "email" # email or python
  email: "user@example.com"
  email_password: "app-password"
  csv_file_path: "amazon_orders.csv"

  python:
    executable: ".venv-amazon-orders/bin/python"
    bridge_script: "scripts/amazon_orders_bridge.py"
    config_path: "~/.config/amazonorders/config.yml"
    timeout_seconds: 300
    max_output_bytes: 10485760
```

`order_fetcher` defaults to `email` for backward compatibility. Python mode requires nonblank executable, bridge, and config paths plus positive timeout/output bounds. Email mode retains its current credential requirements. CSV remains optional and additive in either mode. At least one valid automatic source or CSV source remains required.

Amazon account credentials will not be added to this application's YAML. Operators establish a persisted session with the virtual environment's stock CLI (`amazon-orders --config-path … login`) or use the upstream `AMAZON_USERNAME`, `AMAZON_PASSWORD`, and optional `AMAZON_OTP_SECRET_KEY` environment variables. Environment is inherited without echoing values. Documentation will call out file permissions, process environment exposure, and Amazon's authentication/challenge behavior.

Alternative considered: pass username/password as CLI arguments. Rejected because arguments can be exposed by process listings and error logs.

### Pin and isolate the Python dependency

Add a dedicated requirement file that pins the verified 4.4.x package release used by tests (4.4.7 at proposal time), and document an isolated virtual environment:

```sh
python3 -m venv .venv-amazon-orders
.venv-amazon-orders/bin/python -m pip install --upgrade pip
.venv-amazon-orders/bin/python -m pip install -r requirements-amazon-orders.txt
.venv-amazon-orders/bin/amazon-orders --config-path ~/.config/amazonorders/config.yml login
```

Python 3.9+ is the upstream minimum. Browser/challenge extras are optional and must be documented separately, including `amazon-orders[browser]` plus `playwright install chromium` when selected. Java 25 remains the application's baseline.

Alternative considered: unpinned `pip install --upgrade amazon-orders`. Rejected for reproducibility; operators can deliberately update the pin when Amazon markup changes and rerun contract tests.

### Map only validated bridge records into the existing model

The Groovy implementation will parse JSON with Jackson and require `schema_version == 1` and an `orders` array. Mapping is:

- `order_number` → `AmazonOrder.orderId`
- ISO `order_placed_date` → `AmazonOrder.orderDate`
- positive `grand_total` → negative `AmazonOrder.totalAmount`, preserving the current expense convention
- payment brand and optional last four → `AmazonOrder.paymentMethod`
- each item title/ASIN/price/quantity → `AmazonOrderItem`; missing quantity defaults to 1
- cancelled orders and records missing order number/date/total are excluded with non-secret warnings
- `isReturn` is false for emitted history orders

The fetcher validates the complete envelope before returning data. Envelope/schema/JSON failures return an empty list. A malformed individual record is skipped while other independently valid records remain usable; the warning reports its index/order ID when available but not raw payload. Duplicate order numbers are deterministically collapsed with a warning.

The bridge will not emit recipient/address data because it is unnecessary for matching and increases privacy exposure. Refund-only event synthesis is excluded because upstream order history does not provide the same notification date semantics as email mode.

### Treat process execution as a bounded, testable dependency

Wrap process creation/execution behind an injectable runner so unit tests can simulate output, exit codes, launch errors, hangs, interrupts, and stream behavior. Use `ProcessBuilder` with an argument list—never a shell command string. Resolve configured paths predictably and log the executable/bridge/config paths without environment secrets.

Capture stdout and stderr concurrently from process start to prevent pipe-buffer deadlocks. Enforce separate configured byte caps; exceeding either cap is a fetch-level failure. Wait at most `timeout_seconds`. On timeout, terminate the process and descendants, wait a short grace period, then forcibly terminate remaining processes. On thread interruption, perform the same cleanup, restore the interrupt flag, and return an empty list.

A launch error, missing executable/script/package, timeout, output overflow, non-zero exit, unsupported schema, malformed JSON, or transformation-level exception logs one concise error/warning plus bounded sanitized stderr context and returns `[]`. Successful empty upstream history returns `[]` at info level rather than as an error. Temporary files, if any, are created with restrictive permissions and removed in `finally`; the preferred protocol uses pipes and needs none.

### Verify behavior at contract, component, and service levels

- Unit tests for the interface wiring and unchanged email parser behavior.
- Table-driven configuration tests for default email mode, valid Python mode, invalid mode, missing paths, and non-positive bounds.
- Bridge tests with upstream entity fixtures/mocks that verify JSON schema, exact lookback filtering, canceled/invalid order handling, item fields, and stderr/stdout separation without live credentials.
- Groovy transformation tests using committed JSON fixtures for sign, dates, items, defaults, duplicate handling, malformed records, and schema rejection.
- Process-runner tests using deterministic helper processes for success, stderr, non-zero exit, timeout/forced cleanup, interruption, and oversized output.
- `AmazonService` tests proving selected fetcher invocation, empty-list failure behavior, CSV aggregation, and no downstream API updates when no orders are returned.
- Full Java/Groovy verification via repository scripts; optional live Python smoke tests remain credential-gated and skipped by default.

## Risks / Trade-offs

- [Amazon changes consumer HTML and breaks `amazon-orders`] → Pin a verified release, log the upstream failure clearly, return no Python orders, document upgrade/troubleshooting, and keep email/CSV modes available.
- [Full-detail history is slow and may trigger a hang] → Exact lookback filtering, configurable timeout, concurrent stream drainage, output caps, descendant termination, and no partial return on fetch-level failure.
- [Authentication prompts block unattended runs] → Require a pre-established persisted session or noninteractive upstream environment configuration; never attach stdin for interactive normal runs; timeout remains the final guard.
- [Credentials or personal order data leak into logs] → No secrets in arguments/application YAML, redacted bounded diagnostics, no raw JSON logging, no recipient/address output, restrictive upstream config/cookie permissions in documentation.
- [The bridge duplicates part of the upstream CLI] → Keep it thin and limited to serialization/filtering; use public upstream Python APIs rather than scraping or copying selectors.
- [Python mode differs from email-specific refund/Subscribe & Save semantics] → Document the distinction and retain explicit email selection; do not fabricate refund dates or synthetic subscription records.
- [A malformed order causes a partial set] → Skip only independently invalid records with warnings; treat envelope/process/schema failures as all-or-nothing and return no orders.
- [A timeout leaves descendants running] → Track `ProcessHandle` descendants, terminate gracefully, then forcibly, and test cleanup deterministically.
- [Current checkout is on an unrelated runtime-upgrade branch] → This proposal changes only OpenSpec artifacts; implementation branch/worktree selection occurs before `/opsx:apply`.

## Migration Plan

1. Add the interface and move the existing email class with compatibility tests before changing construction.
2. Add configuration selection/validation and inject the chosen fetcher into `AmazonService` while preserving CSV aggregation.
3. Add the pinned Python requirement and bridge CLI with isolated tests/fixtures.
4. Add the bounded process runner and Python fetcher transformation tests.
5. Update all operator/configuration documentation and examples together.
6. Run unit tests, integration tests, build/distribution verification, bridge tests, and OpenSpec verification without live credentials.
7. Existing users require no configuration change because `email` remains the default. Python users create the virtual environment, authenticate/persist a session, validate the bridge, then set `amazon.order_fetcher: python`.
8. Roll back by selecting `email` or `csv` and reverting the implementation; no YNAB or processed-transaction data migration is required.

## Open Questions

None. The proposal deliberately uses a repository-owned machine-readable bridge because the upstream stock CLI does not provide stable JSON/item output; email remains the default and Python failures return an empty order list.
