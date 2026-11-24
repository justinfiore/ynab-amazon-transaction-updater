## MODIFIED Requirements

### Requirement: Email fetcher compatibility
IMAP fetching SHALL remain in `EmailAmazonOrderFetcher`, preserving regular purchase, Subscribe & Save, lookback, sign and non-fatal parsing behavior. Refund parsing SHALL retain distinct credit evidence and explicit multiple item rows/adjustments rather than merging all same-order refunds or treating one extracted title as a complete item allocation. Missing details SHALL preserve memo-only compatibility without becoming financial evidence.

#### Scenario: Existing email configuration is used
- **WHEN** `amazon.order_fetcher` is omitted and valid email credentials are configured
- **THEN** email remains the selected purchase fetcher, existing purchase/S&S behavior is preserved and no Python evidence source starts without explicit configuration

#### Scenario: Email parsing encounters an error
- **WHEN** the email implementation cannot connect or parse an individual message
- **THEN** it logs a bounded secret-safe diagnostic, does not terminate the updater and retains independently valid records according to the existing non-fatal behavior

#### Scenario: Two refunds share an original order
- **WHEN** separate notifications describe distinct refunds for the same original order
- **THEN** both credit occurrences are retained, while duplicate notices corroborating the same event do not create extra credits

## ADDED Requirements

### Requirement: Allowlisted Amazon financial evidence
The Python bridge SHALL expose existing pinned-library adjustment fields, nullable item quantities, total provenance and bounded completeness/parse indicators through a versioned allowlisted contract compatible with existing consumers. It SHALL distinguish merchandise value, charges, gift-card/reward funding and refunds. It SHALL NOT forward raw pages/recipient data or treat `full_details`, missing values or display quantity defaults as completeness evidence.

#### Scenario: Full details already contain tax and discounts
- **WHEN** the library returns recognized tax, shipping, promotion, coupon or subscription discount fields
- **THEN** the bridge retains their signed amounts and known/unknown state rather than dropping them or fetching another details page solely to expose those fields

#### Scenario: Item quantity is unknown
- **WHEN** the library reports a null quantity
- **THEN** financial evidence preserves that uncertainty even if a legacy display consumer continues using quantity 1

### Requirement: Bounded order-specific charge history
The Python bridge SHALL support same-session order-specific transaction history with all required pages, within existing process time/output limits. It SHALL retain completed dates, signed amounts, available payment identity and order references, preserving repeated occurrences. Missing pages, malformed rows, missing identity or multi-order ambiguity SHALL prohibit financial completeness claims without preventing independently valid memo-only records.

#### Scenario: A page contains multiple order references
- **WHEN** upstream transaction data contains more than one order reference before the library flattens it to its first reference
- **THEN** bridge validation detects ambiguity and does not assign the full charge solely to the first order

#### Scenario: Charge history exceeds process bounds
- **WHEN** required paging cannot finish within the configured timeout or output limit
- **THEN** existing bounded failure behavior is retained and no partially retrieved history is marked financially complete

### Requirement: Refund discovery independent of purchase lookback
Configured transaction evidence SHALL discover recent positive refund events by completed date and fetch needed original order details by real ID within process bounds, independently of purchase-order age or eligibility. Refund rows SHALL retain signed amounts, dates, available order/payment identity and occurrence provenance. Aggregate order refund totals and credit-note metadata SHALL NOT substitute for event item evidence.

#### Scenario: A recent refund belongs to an old purchase
- **WHEN** a credit is in the transaction lookback but its original purchase is older than the order lookback
- **THEN** refund matching still considers the credit and may retrieve the older order by ID without widening normal purchase matching

### Requirement: Explicit supplemental refund evidence
Supplemental refund email SHALL require opt-in and IMAP settings, defaulting off. `amazon.transaction_evidence.enabled` SHALL default true in Python mode, false otherwise; enabling it SHALL require Python runtime/session settings. Paths alone SHALL NOT authorize retrieval. Payment events SHALL support purchases/refunds without changing purchase sources or requiring IMAP for Python. Item amounts SHALL retain event/known-state provenance.

#### Scenario: Python mode has no email credentials
- **WHEN** supplemental refund email evidence is disabled or unavailable
- **THEN** Python purchase and refund event retrieval remain usable; missing refund-item evidence blocks item allocation but not independently eligible linked uncategorized fallback, without implicit mailbox access

#### Scenario: Email mode has dormant Python settings
- **WHEN** Python paths/session settings exist but supplemental transaction evidence is not explicitly enabled
- **THEN** no Python payment retrieval launches; email display evidence remains available but does not by itself authorize category clearing or allocation

#### Scenario: An email exposes only a subtotal or generic title
- **WHEN** a refund notification lacks a final credit total or identified refunded items
- **THEN** its legacy summary remains usable for memo evidence but its defaults do not establish a reconciled single-item refund
