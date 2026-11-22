## Purpose

Associate individual YNAB purchases with Walmart final charges and provide order links and optional balanced proportional item splits without duplicating spending or overwriting user work.

## ADDED Requirements

### Requirement: Distinct final-charge matching
The application SHALL match each eligible YNAB outflow to at most one final purchase-charge occurrence from the sole Go-backed Walmart source, using exact integer amount, charge-date proximity and compatible payment/account evidence. It SHALL consume each occurrence at most once across transactions and orders, skip unresolved ambiguity and never substitute an order-total match for a missing final charge.

#### Scenario: Several transactions belong to one order
- **WHEN** two eligible YNAB transactions uniquely match two final charges of one order
- **THEN** both transactions are associated with that order and each references its own charge occurrence

#### Scenario: Equal amounts exceed available charges
- **WHEN** three transactions compete for two equal-valued final charges
- **THEN** no more than two occurrences are consumed and an unresolved transaction assignment is not chosen merely by input ordering

#### Scenario: Candidate payment methods conflict
- **WHEN** equal amounts and dates cannot distinguish payment methods or orders using available account evidence
- **THEN** the application leaves the ambiguous candidates unchanged and reports the need for disambiguation

### Requirement: Metadata-only parent memos and direct order links
Each linked parent transaction SHALL preserve its existing memo and add only the order date, full order total with currency, order ID and validated direct HTTPS Walmart order-details URL. Generated parent metadata SHALL NOT contain items or product summaries. The total SHALL be labeled as the order total, not the matched charge amount. The entire memo SHALL fit 500 characters; otherwise the write SHALL be skipped without discarding user text. No third-party shortener SHALL be used.

#### Scenario: A charge belongs to a larger order
- **WHEN** a $70 transaction matches one charge of a USD order dated 2026-11-20 with a full total of $100.00
- **THEN** its generated metadata starts `2026-11-20 | Total $100.00 | #`, followed by the order ID and direct URL last, without item names or a `Walmart | ` prefix, and the transaction amount remains $70

#### Scenario: A narrow phone preview truncates the metadata
- **WHEN** the client shows only the beginning of the generated metadata
- **THEN** the ISO order date and full order total precede the order ID and URL, without verbose date or ID labels or an assumed fixed preview length

#### Scenario: Existing memo leaves insufficient space
- **WHEN** the existing memo and required order metadata would exceed 500 characters
- **THEN** no write occurs and the preview reports insufficient memo space without truncating user text or the order URL

#### Scenario: Item splits are enabled
- **WHEN** a proportional split is written
- **THEN** the parent memo remains metadata-only and item descriptions appear only on their individual split lines

### Requirement: Explicit proportional allocation
When proportional mode is enabled and pricing reconciles, the application SHALL allocate each item and explicit non-item component across the complete order's final purchase charges by charge amount. This SHALL be labeled an accounting allocation, not an item-to-shipment or item-to-charge fact. Link-only mode SHALL remain the default.

#### Scenario: One hundred dollar order has seventy and thirty dollar charges
- **WHEN** components are groceries $60, household goods $30 and tax $10 and charges are $70 and $30
- **THEN** the first charge's splits are $42, $21 and $7 and the second charge's splits are $18, $9 and $3, with YNAB expense signs applied

#### Scenario: One payment is not imported into YNAB
- **WHEN** a $100 order has a matched $80 card charge and an unmatched $20 gift-card charge
- **THEN** the matched charge receives only 80% of each component and the gift-card share remains unposted rather than reassigned

### Requirement: Exact deterministic allocation balances
Allocation SHALL use exact decimal/integer arithmetic and deterministic controlled rounding. Each charge's splits SHALL equal that charge, each component's allocations across all charges SHALL equal its original cost, and each rounded cell SHALL differ from its exact proportional value by less than one cent. Results SHALL NOT depend on which charges are currently matched or iteration order.

#### Scenario: Fractional pennies occur
- **WHEN** three one-cent components are allocated to charges of one cent and two cents
- **THEN** exactly one component-cent belongs to the first charge and two belong to the second, with every component counted once and stable tie-breaking

### Requirement: Reconciliation before splitting
The application SHALL split only supported USD purchase outflows with complete final-charge evidence and a reconciled nonnegative component basis. It SHALL distinguish explicit tax, fees, tips and supported discounts, exclude cancelled/unfulfilled item costs, and refuse unexplained residuals, refunds, foreign exchange or invalid precision rather than fabricate balancing items.

#### Scenario: Charges and known components disagree
- **WHEN** known item/component costs total $98 but complete charges total $100 without an explicit explanation
- **THEN** no proportional split is written and a trustworthy charge match remains eligible only for link-only enrichment

### Requirement: Preserve YNAB transaction identity and existing work
Splitting SHALL modify the matched existing parent, not create replacement bank transactions. It SHALL preserve amount, account, date, payee, import identity, cleared/approved state and existing memo. Existing splits, transfers, tracking accounts, deleted transactions, unsupported categories and already reconciled transactions SHALL NOT receive automatic split writes.

#### Scenario: Existing user split is encountered
- **WHEN** a matched transaction already has subtransactions
- **THEN** existing subtransactions and categories remain unchanged and no replace/delete-and-recreate workaround is attempted

#### Scenario: No item category mapping exists
- **WHEN** an eligible unsplit transaction is converted into item splits
- **THEN** each line retains its item/component description and inherits the original eligible category, or remains uncategorized if the parent was uncategorized, without inferring a new category

### Requirement: Safe preview and retry handling
Dry runs SHALL show parent links, component amounts, proportional labeling and skip reasons without remote writes or completion changes. Live writes SHALL recheck parent state, record successful links and splits separately, and recover uncertain responses by rereading YNAB before retrying. Source drift after an applied split SHALL require review rather than automatic reallocation.

#### Scenario: Link-only enrichment precedes split activation
- **WHEN** proportional mode is later enabled for a previously linked but still unsplit transaction
- **THEN** its link-only completion marker alone does not prevent an otherwise eligible split

#### Scenario: A response is lost after YNAB accepted a split
- **WHEN** the client cannot determine whether a split update succeeded
- **THEN** it rereads and compares the transaction with the saved plan before recording success or attempting another write

#### Scenario: Tip changes after a partial application
- **WHEN** one charge has already been split and a later snapshot changes the order's components or ledger
- **THEN** automatic allocation for that order stops for review and existing splits remain untouched

### Requirement: Offline verification and documented limitations
Default verification SHALL exercise matching, exact allocation, link preservation, split eligibility, API payloads and retry safety without live accounts. Documentation SHALL explain proportional meaning, missing-charge treatment, category behavior, preview/opt-in and YNAB's existing-split limitations.

#### Scenario: Operator verifies the change without credentials
- **WHEN** the standard test suite runs
- **THEN** deterministic fixtures cover the enrichment contracts without signing into Walmart or modifying a real YNAB plan
