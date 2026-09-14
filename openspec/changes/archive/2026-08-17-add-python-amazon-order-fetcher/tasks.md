## 1. Interface Extraction and Email Compatibility

- [x] 1.1 Add characterization tests for the current `AmazonOrderFetcher.fetchOrders()` regular-order, Subscribe & Save, refund, lookback, merge, sign, and error behavior before moving code
- [x] 1.2 Replace `AmazonOrderFetcher` with the minimal `List<AmazonOrder> fetchOrders()` interface
- [x] 1.3 Move the existing IMAP implementation into `EmailAmazonOrderFetcher` and update class-specific unit/integration test names without changing behavior
- [x] 1.4 Add constructor/factory injection to `AmazonService` so tests and runtime selection use the interface rather than directly constructing the email class
- [x] 1.5 Run focused email fetcher and Amazon service unit/integration tests and compare behavior with the characterization baseline

## 2. Configuration and Source Selection

- [x] 2.1 Add `email`/`python` fetcher constants and typed Python executable, bridge, upstream config, timeout, and output-limit fields with backward-compatible email defaults
- [x] 2.2 Load the new nested `amazon.python` YAML settings and normalize/resolve paths without reading account credentials into command arguments
- [x] 2.3 Implement mode-specific validation for unknown mode, required paths, positive bounds, email credentials, CSV-only operation, and automatic-source-plus-CSV operation
- [x] 2.4 Add table-driven `Configuration_UT` coverage for defaults and every valid/invalid configuration scenario with diagnostic assertions
- [x] 2.5 Update `AmazonService` construction to select exactly one automatic fetcher while preserving optional additive CSV loading
- [x] 2.6 Add service tests for email selection, Python selection, dependency injection, CSV-only operation, automatic-plus-CSV aggregation, and all-empty downstream behavior

## 3. Python Dependency and Bridge CLI

- [x] 3.1 Add a dedicated Python requirement file pinned to the verified `amazon-orders` release and record Python 3.9+ as the optional runtime baseline
- [x] 3.2 Implement the repository-owned bridge CLI using public `AmazonSession`/`AmazonOrders` APIs, configured upstream config/cookie paths, noninteractive authentication, and full-detail history
- [x] 3.3 Implement exact `look_back_days` filtering across the upstream time filters/years and deterministic order-number deduplication
- [x] 3.4 Emit only schema-version-1 JSON on stdout, send diagnostics to stderr, and omit recipient/address and other unnecessary sensitive fields
- [x] 3.5 Return a valid empty JSON envelope for a successful no-orders result and non-zero exit with no success payload for import/auth/upstream failures
- [x] 3.6 Add isolated Python bridge tests using upstream-style fixtures/mocks for full records, items, date boundaries, canceled/invalid orders, duplicates, empty history, dependency/auth/upstream failures, and stdout/stderr separation
- [x] 3.7 Add a bridge `--help`/preflight or equivalent non-live verification path that confirms interpreter, package version, paths, and schema support without fetching account data

## 4. Bounded Process Runner

- [x] 4.1 Add an injectable process-runner abstraction that launches an argument-list `ProcessBuilder` without a shell and without secret command arguments
- [x] 4.2 Drain stdout and stderr concurrently from process start with independently enforced byte limits
- [x] 4.3 Enforce the configured wall-clock timeout and terminate child plus descendants gracefully before forcible escalation
- [x] 4.4 Handle thread interruption by cleaning up the process tree, restoring interrupt status, and returning a typed failure result
- [x] 4.5 Sanitize and bound stderr/exception context before logging and ensure raw stdout/order payloads are never logged
- [x] 4.6 Add deterministic helper-process tests for success, heavy dual-stream output, launch failure, non-zero exit, timeout, forced descendant cleanup, interruption, and stdout/stderr overflow

## 5. Python Fetcher and Transformation

- [x] 5.1 Implement `PythonAmazonOrderFetcher` to build the configured bridge invocation, inherit supported upstream environment safely, and consume the process-runner result
- [x] 5.2 Add Jackson DTOs/parser validation for schema version 1 and the required orders array
- [x] 5.3 Map order number, ISO date, negative expense total, payment metadata, and item title/ASIN/price/quantity into existing `AmazonOrder`/`AmazonOrderItem` objects
- [x] 5.4 Exclude canceled or required-field-invalid records, default absent item quantity to 1, and deterministically collapse duplicate order numbers with bounded warnings
- [x] 5.5 Return `[]` for launch, timeout, output-limit, non-zero-exit, envelope, schema, malformed JSON, and transformation-level failures while distinguishing a successful empty result in logs
- [x] 5.6 Add committed JSON fixtures and unit tests for complete/minimal/empty output, signs, dates, items, payment fields, quantity default, invalid/canceled records, duplicates, unsupported schema, malformed JSON, and every process failure result
- [x] 5.7 Add an integration test that invokes a local fixture bridge as a real child process and proves successful mapping and failure-safe empty results without Amazon credentials

## 6. Documentation and Example Configuration

- [x] 6.1 Update `config.example.yml` with commented email, Python, and CSV-only configurations plus timeout/output defaults and secret-safe guidance
- [x] 6.2 Update `README.md` feature, prerequisite, setup, configuration, operation, file-structure, safety, limitation, and troubleshooting sections for selectable fetchers
- [x] 6.3 Update `QUICKSTART.md` with an end-to-end Python 3.9+ virtual-environment install, pinned requirements install, stock `amazon-orders login`/session setup, bridge preflight, YAML selection, and dry-run verification path
- [x] 6.4 Update `SETUP.md` with platform-specific Python guidance, upstream config/cookie permissions, supported environment variables, optional browser/challenge extras, timeout tuning, and rollback to email/CSV
- [x] 6.5 Update any other Amazon data-source or runtime guidance affected by the interface rename and Python asset paths
- [x] 6.6 Document that the upstream package is unofficial website parsing, officially supports English amazon.com, may require updates when Amazon changes, and does not reproduce all email-specific refund/Subscribe & Save notification semantics
- [x] 6.7 Verify every documented command and example path against a clean isolated environment, with no secrets committed or printed

## 7. Complete Verification

- [x] 7.1 Run focused configuration, email fetcher, Python fetcher, process runner, bridge, and Amazon service tests
- [x] 7.2 Run `.agents/scripts/run-unit-tests.sh` and inspect JUnit/HTML results for failures, errors, and unexpected skips
- [x] 7.3 Run `.agents/scripts/run-integration-tests.sh` without live credentials and verify credential-gated smoke tests remain skipped by default
- [x] 7.4 Run `./gradlew clean build installDist` and verify the Java distribution plus required Python bridge/requirement assets are packaged or located as documented
- [x] 7.5 Run bridge preflight with the documented minimum Python version and pinned `amazon-orders` package, then verify a fixture success and each bounded failure path
- [x] 7.6 Run a secret scan/diff inspection to confirm no credentials, cookies, raw order payloads, virtual environments, or generated upstream configuration files are tracked
- [x] 7.7 Run `openspec validate add-python-amazon-order-fetcher`, inspect final status/diff, and confirm all acceptance scenarios have automated or explicitly credential-gated coverage
