## Why

Amazon orders can currently be fetched automatically only by parsing notification emails, which makes the application dependent on message availability and formats. Adding an out-of-process integration with the maintained `amazon-orders` Python package provides an account-history-backed alternative while preserving the existing Groovy order model and downstream matching behavior.

## What Changes

- Introduce an `AmazonOrderFetcher` interface whose `fetchOrders()` contract returns `List<AmazonOrder>`.
- Move the current IMAP/email implementation into a separate `EmailAmazonOrderFetcher` without changing its supported regular-order, Subscribe & Save, or refund behavior.
- Add a selectable `PythonAmazonOrderFetcher` that runs a repository-owned Python bridge CLI backed by the published `amazon-orders` package, consumes versioned JSON, and transforms valid records into the existing `AmazonOrder`/`AmazonOrderItem` model.
- Add explicit Amazon fetcher selection and Python integration settings, including interpreter/bridge paths, upstream config path, process timeout, output limit, and lookback behavior; retain email as the compatibility default.
- Treat missing runtimes/scripts/packages, process launch failures, non-zero exits, timeouts, interruptions, oversized output, malformed JSON, and invalid records as non-fatal fetch failures: log actionable redacted warnings/errors and return no Python-fetched orders.
- Preserve optional CSV aggregation after the selected automatic fetcher runs.
- Add unit/integration coverage for interface wiring, both implementations, process lifecycle controls, JSON transformation, configuration validation, fallback-safe empty results, and downstream compatibility.
- Update `README.md`, `QUICKSTART.md`, `SETUP.md`, `config.example.yml`, and any affected operational guidance with selection/configuration examples, Python 3.9+ requirements, isolated package installation, authentication/session setup, security guidance, limitations, troubleshooting, and verification commands.

## Capabilities

### New Capabilities

- `amazon-order-fetching`: Defines the pluggable Amazon order-fetcher contract, email compatibility implementation, Python bridge implementation, transformation semantics, process-safety behavior, configuration, and operator documentation.

### Modified Capabilities

None. The existing `runtime-platform` capability remains unchanged; this change adds an optional Python 3.9+ runtime only when the Python fetcher is selected.

## Impact

- Core services: `AmazonOrderFetcher`, a renamed email implementation, new Python implementation/process abstraction, and `AmazonService` construction/orchestration.
- Models and transformation: existing `AmazonOrder` and `AmazonOrderItem` remain the downstream contract.
- Configuration: `Configuration.groovy` and `config.example.yml` gain fetcher selection and Python process settings while retaining current email and CSV settings.
- Runtime assets: a repository-owned Python bridge CLI and an installable dependency declaration for `amazon-orders` (current supported line is 4.4.x; exact apply-time pin is documented and tested).
- Tests: configuration, fetcher, process failure/timeout, JSON contract, and service integration suites.
- Documentation: `README.md`, `QUICKSTART.md`, `SETUP.md`, and any file-structure/troubleshooting guidance that describes Amazon data sources or runtime prerequisites.
- External systems: Amazon's consumer website, the unofficial `amazon-orders` package, local Python process execution, and the package's persisted configuration/cookie files.
