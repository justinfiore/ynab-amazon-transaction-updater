# amazon-order-fetching Specification

## Purpose
Define selectable and testable Amazon order ingestion through backward-compatible email parsing, an optional bounded Python `amazon-orders` bridge, and additive CSV loading while preserving downstream order models and secret-safe failure behavior.
## Requirements
### Requirement: Pluggable Amazon order fetcher contract
The application SHALL define an `AmazonOrderFetcher` interface whose automatic-source contract returns a `List<AmazonOrder>`, and `AmazonService` SHALL consume that abstraction rather than a concrete protocol implementation.

#### Scenario: Service uses the selected implementation
- **WHEN** the application constructs `AmazonService` with a valid automatic Amazon fetcher selection
- **THEN** the service invokes exactly the corresponding `AmazonOrderFetcher` implementation and passes its returned `AmazonOrder` values to the existing downstream workflow

#### Scenario: Fetcher can be injected for testing
- **WHEN** a caller constructs the service with an injected `AmazonOrderFetcher`
- **THEN** the service uses that instance without creating an email connection or Python process

### Requirement: Email fetcher compatibility
The current IMAP-based Amazon fetching and parsing behavior SHALL reside in a separate `EmailAmazonOrderFetcher` implementation and SHALL preserve regular order, Subscribe & Save, refund, lookback, merge, sign, and non-fatal parsing behavior.

#### Scenario: Existing email configuration is used
- **WHEN** `amazon.order_fetcher` is omitted and valid email credentials are configured
- **THEN** configuration defaults to the email implementation and order fetching behaves as it did before the interface extraction

#### Scenario: Email parsing encounters an error
- **WHEN** the email implementation cannot connect or parse an individual message
- **THEN** it logs the existing appropriate diagnostic, does not terminate the updater, and returns the successfully parsed orders or an empty list according to the existing behavior

### Requirement: Explicit fetcher configuration
The application SHALL support `email` and `python` automatic Amazon fetcher modes, SHALL default to `email` for backward compatibility, and SHALL reject unknown modes or incomplete mode-specific settings during configuration validation.

#### Scenario: Valid Python mode is configured
- **WHEN** `amazon.order_fetcher` is `python` and interpreter path, bridge path, upstream config path, positive timeout, and positive output limit are configured
- **THEN** configuration is valid without requiring IMAP email credentials

#### Scenario: Python mode is incomplete
- **WHEN** Python mode omits a required path or supplies a non-positive timeout or output limit
- **THEN** validation fails with a message that identifies the invalid configuration key without printing credentials

#### Scenario: CSV is configured with either mode
- **WHEN** a valid automatic fetcher and `amazon.csv_file_path` are both configured
- **THEN** the service appends valid CSV orders after the selected automatic fetcher's orders

#### Scenario: CSV is the only source
- **WHEN** no automatic source has usable mode-specific configuration but a CSV path is configured
- **THEN** the application remains valid and loads CSV orders without launching an automatic fetcher

### Requirement: Machine-readable Python bridge
The repository SHALL provide a Python CLI bridge backed by the published `amazon-orders` package that writes exactly one versioned JSON envelope to stdout on success and writes human diagnostics only to stderr.

#### Scenario: Bridge fetch succeeds
- **WHEN** the bridge runs with a valid persisted Amazon session or supported noninteractive upstream environment and a lookback window
- **THEN** it fetches sufficient order history with full details, filters orders to the exact lookback window, and emits a schema-versioned JSON orders array

#### Scenario: No upstream orders exist
- **WHEN** the upstream account has no orders in the requested lookback window
- **THEN** the bridge exits successfully and emits a valid envelope with an empty `orders` array

#### Scenario: Bridge dependency or authentication fails
- **WHEN** Python cannot import `amazonorders`, the session cannot authenticate noninteractively, or the upstream request/parsing fails
- **THEN** the bridge emits no successful order payload, writes a concise diagnostic to stderr, and exits non-zero

#### Scenario: Sensitive address data is available upstream
- **WHEN** upstream order entities contain recipient or shipping-address fields
- **THEN** the bridge omits those fields from its JSON contract

### Requirement: Python order transformation
The Python fetcher SHALL validate bridge schema version 1 and SHALL transform valid bridge records into the existing `AmazonOrder` and `AmazonOrderItem` models without changing downstream model contracts.

#### Scenario: Complete expense order is transformed
- **WHEN** a record contains an order number, ISO order date, positive grand total, payment metadata, and item details
- **THEN** the fetcher maps identifiers/dates/items/payment fields, converts the grand total to the existing negative expense sign convention, defaults `isReturn` to false, and returns the resulting `AmazonOrder`

#### Scenario: Item quantity is absent
- **WHEN** an otherwise valid item omits quantity
- **THEN** the transformed `AmazonOrderItem` uses quantity 1

#### Scenario: Required order fields are absent
- **WHEN** an individual record lacks order number, order date, or grand total, or represents a cancelled order
- **THEN** the fetcher excludes that record, logs a bounded non-secret warning, and continues transforming independently valid records

#### Scenario: Duplicate order numbers are emitted
- **WHEN** the bridge output contains the same order number more than once
- **THEN** the fetcher deterministically returns one order for that number and logs a warning

#### Scenario: Envelope is malformed or unsupported
- **WHEN** stdout is not valid JSON, lacks an orders array, or declares an unsupported schema version
- **THEN** the fetcher logs an actionable error and returns no Amazon orders

### Requirement: Bounded and safe child-process execution
The Python fetcher SHALL execute the configured interpreter and bridge through a non-shell argument list, SHALL concurrently drain stdout and stderr under configured byte limits, SHALL enforce a configured wall-clock timeout, and SHALL clean up the process and descendants on timeout or interruption.

#### Scenario: Process succeeds within bounds
- **WHEN** the child exits zero before the timeout with valid output under both limits
- **THEN** the fetcher parses the output and returns transformed orders

#### Scenario: Process cannot start
- **WHEN** the interpreter or bridge is missing, inaccessible, or cannot be launched
- **THEN** the fetcher logs the failing non-secret path/context and returns no Amazon orders without terminating the updater

#### Scenario: Process exits non-zero
- **WHEN** the child exits with a non-zero status
- **THEN** the fetcher logs the exit status plus bounded sanitized stderr context and returns no Amazon orders

#### Scenario: Process hangs
- **WHEN** the child does not finish before the configured timeout
- **THEN** the fetcher terminates the child and its descendants, escalates to forcible termination after a grace period, logs a timeout warning, and returns no Amazon orders

#### Scenario: Waiting thread is interrupted
- **WHEN** the fetching thread is interrupted while the child is running
- **THEN** the fetcher cleans up the process tree, restores the thread interrupt status, logs the interruption, and returns no Amazon orders

#### Scenario: Output exceeds a configured limit
- **WHEN** stdout or stderr exceeds its configured byte limit
- **THEN** the fetcher terminates the process tree, does not parse or log the full output, and returns no Amazon orders

#### Scenario: Child writes both streams heavily
- **WHEN** the child writes enough stdout and stderr to fill sequential pipe buffers
- **THEN** concurrent stream drainage prevents deadlock while retaining the configured limits

### Requirement: Secret-safe Python authentication
The Python integration SHALL use the upstream persisted configuration/cookie workflow or supported environment variables and SHALL NOT place Amazon account passwords, OTP secrets, cookies, or raw order payloads in process arguments or application logs.

#### Scenario: Persisted session is configured
- **WHEN** an operator has run the stock `amazon-orders login` command against the configured upstream config path
- **THEN** the bridge reuses that configuration and cookie location without requiring credentials in this application's YAML

#### Scenario: Environment credentials are used
- **WHEN** supported `AMAZON_USERNAME`, `AMAZON_PASSWORD`, or `AMAZON_OTP_SECRET_KEY` variables are available to the updater process
- **THEN** the bridge can inherit them without the fetcher printing their names and values as command arguments or log content

#### Scenario: Failure diagnostics contain sensitive text
- **WHEN** child stderr or an exception contains credential-like or cookie-like content
- **THEN** logged context is bounded and sanitized before emission

### Requirement: Graceful empty-result behavior
A fetch-level Python integration failure SHALL be contained within the Python fetcher and SHALL produce an empty order list so the updater can safely continue without creating Amazon matches from partial or untrusted process output.

#### Scenario: Python fetching fails during a normal update
- **WHEN** any launch, timeout, output-limit, non-zero-exit, envelope, schema, or transformation-level failure prevents trustworthy processing
- **THEN** `PythonAmazonOrderFetcher.fetchOrders()` returns an empty list and `AmazonService` reports no Python-fetched orders rather than propagating the failure

#### Scenario: No orders reach matching
- **WHEN** the selected fetcher and optional CSV source both return empty lists
- **THEN** downstream matching receives an empty Amazon order list and no Amazon transaction update is attempted

### Requirement: Python runtime packaging and documentation
The repository SHALL pin the tested `amazon-orders` dependency in a dedicated Python requirement, and operator documentation SHALL describe Python 3.9+, isolated installation, session setup, application configuration, operation, optional challenge extras, security, limitations, troubleshooting, and verification.

#### Scenario: New operator follows Python setup
- **WHEN** an operator follows `README.md`, `QUICKSTART.md`, or `SETUP.md`
- **THEN** they can create a virtual environment, install the pinned package, run the stock login/session command, configure Python mode with documented paths and process bounds, and run a non-destructive verification before enabling normal updates

#### Scenario: Operator uses only email or CSV
- **WHEN** an operator does not select Python mode
- **THEN** the documentation makes clear that Python and `amazon-orders` are optional and Java 25 remains sufficient for existing modes

#### Scenario: Operator troubleshoots a Python failure
- **WHEN** logs report a missing runtime/package, authentication challenge, stale session, timeout, non-zero exit, unsupported schema, or malformed output
- **THEN** documentation provides a corresponding diagnostic command and recovery path without instructing the operator to expose secrets

#### Scenario: Documentation describes upstream limitations
- **WHEN** an operator evaluates Python mode
- **THEN** the documentation states that `amazon-orders` is unofficial website parsing, may break when Amazon changes, officially supports English amazon.com, and does not reproduce all email-specific refund or Subscribe & Save notification semantics

### Requirement: Automated verification without live credentials
The implementation SHALL include deterministic tests for the interface split, email compatibility, configuration, bridge schema/filtering, transformation, process lifecycle/failures, service orchestration, and documentation/config examples, while keeping live Amazon authentication tests optional and disabled by default.

#### Scenario: Standard verification runs without Amazon credentials
- **WHEN** the repository's documented unit, integration, build, and distribution commands run in CI or a clean developer environment
- **THEN** all Python integration behavior is exercised through fixtures/mocks/helper processes and no live Amazon login or order fetch is attempted

#### Scenario: Optional live smoke test is enabled
- **WHEN** an operator explicitly provides the required secure environment/session and opts into the live smoke test
- **THEN** the test verifies bridge invocation and schema without updating YNAB and remains excluded from the default suite
