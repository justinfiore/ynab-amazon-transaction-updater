## ADDED Requirements

### Requirement: Optional authenticated mobile purchase source

The updater SHALL default Sheetz to disabled. It SHALL fetch `ANYWHERE` and `RETAIL_FUEL` in one bounded, read-only request to the mobile filtered-receipts endpoint using the authorized web token. It SHALL retain only matching and enrichment fields and SHALL NOT log credentials, raw payloads or unrelated account data.

#### Scenario: Sheetz is enabled with a current session

- **WHEN** enabled Sheetz retrieval begins with a valid Bearer token
- **THEN** it makes one request with both order types and does not fetch web order history separately

#### Scenario: The Sheetz session cannot be restored

- **WHEN** the source returns 403 or the bounded sign-in and retry do not restore access
- **THEN** the updater skips Sheetz writes, reports that the session needs attention without exposing credentials, and continues with other retailers independently

### Requirement: Bounded web session reauthentication

The updater SHALL reuse a valid web token and attempt normal website sign-in at most once per run when the token is missing, expired or rejected with 401. It SHALL obtain credentials from a protected source and use standard browser behavior. It SHALL NOT bypass MFA, CAPTCHA or access blocks, or expose secrets in browser traces.

#### Scenario: Reauthentication succeeds

- **WHEN** the web token expires and normal sign-in succeeds
- **THEN** the updater uses the new Bearer token for a bounded read-only retrieval retry

#### Scenario: Sign-in requires user interaction

- **WHEN** Sheetz requires MFA or CAPTCHA
- **THEN** the updater stops Sheetz processing and reports that operator action is needed

### Requirement: Match purchases by amount and date

The updater SHALL match eligible Sheetz outflows in configured accounts one-to-one by exact cents and within three calendar days in either direction. Online orders (dated by local pickup date) and fuel purchases SHALL share one candidate set. No separate payment API or account evidence SHALL be required. Changes SHALL default to preview.

#### Scenario: An exact amount falls at the date boundary

- **WHEN** an eligible Sheetz outflow has exactly one matching order with the same amount and a date three calendar days away, and that order has no other eligible candidate
- **THEN** the updater accepts the match; a four-day difference or a one-cent difference does not qualify

#### Scenario: Purchases cannot be distinguished

- **WHEN** more than one eligible charge or order fits the same match evidence
- **THEN** the updater reports the ambiguity and leaves the transactions unchanged

### Requirement: Amazon-style order memos

When explicitly enabled, memo enrichment SHALL preserve user memo text, transaction amounts, existing categories and splits. Generated text SHALL contain the robot marker, ISO order date, total, a compact list of up to three item titles, and a verified order-specific HTTPS link when available. To fit YNAB's 500-character limit, the updater SHALL shorten only the generated item summary or skip the write if the remaining fields and user text still do not fit.

#### Scenario: The detail page requires browser navigation state

- **WHEN** there is no verified URL that opens the specific order in a fresh signed-in tab
- **THEN** the updater includes the order number and `https://orders.sheetz.com/order/history` instead of the generic detail URL

### Requirement: Guarded category and split enrichment

When enabled, the updater SHALL classify matched online items using the shared classifier and an independent Sheetz category shortlist. It SHALL preserve abstentions, use guarded writes and read-back, and leave user-owned categories, approved transactions and existing splits unchanged. It SHALL proportionally distribute the matched total over item prices, including tax and discounts, rounding deterministically so splits sum exactly to the transaction amount.

#### Scenario: The order total differs from the item subtotal

- **WHEN** an order matches by amount and date and all item prices and categories are available
- **THEN** the updater distributes the order total across the items proportionally, without a separate payment source or balancing line

#### Scenario: Item prices or categories are unavailable

- **WHEN** an item price is invalid or an item classification is unresolved
- **THEN** the updater does not write item categories or splits for that order

### Requirement: Categorize verified fuel without a classifier

With category writes enabled, the updater SHALL assign the full outflow for a uniquely matched fuel-only purchase to the existing YNAB category configured in YAML as `sheetz.gasoline_default_category_id`, with the same write safeguards as online orders and no splits or classifier calls. A fuel match SHALL require an explicit source fuel type, stable purchase ID, date and total.

#### Scenario: No source identifies the outflow as fuel

- **WHEN** a Sheetz outflow has no matching verified fuel record
- **THEN** the updater does not infer fuel from the payee or from the absence of a matching online order

#### Scenario: A verified fuel purchase uniquely matches

- **WHEN** a source explicitly identifies a fuel purchase that uniquely matches an eligible YNAB outflow and the configured category ID resolves to the plan's Gasoline category
- **THEN** the updater assigns the full outflow to Gasoline without a split or classifier call

#### Scenario: The gasoline category is unset or ineligible

- **WHEN** the fuel match is unique but the configured category ID is absent or does not resolve to an eligible existing category in the target plan
- **THEN** the updater reports the configuration issue and makes no category write for the fuel purchase

### Requirement: Unapproved unmatched transaction fallback

After a successful, validated fetch, the updater SHALL treat eligible unapproved Sheetz outflows with zero candidates as presumed non-fuel/non-online. With memo writes enabled it SHALL add `🤖❓ No matching Sheetz online or fuel purchase found` once, preserving user text and the memo limit. With category writes enabled it SHALL use the existing category ID configured as `sheetz.unmatched_default_category_id`, or explicitly send `category_id: null` if unset. It SHALL NOT use a code default.

#### Scenario: A recent outflow has no matching receipt

- **WHEN** a successful, validated fetch yields zero candidates for an eligible unapproved outflow whose three-day matching window is still open
- **THEN** the updater applies the configured fallback in the same run without waiting for the window to close

#### Scenario: YNAB prefilled a category and no default is configured

- **WHEN** an unchanged, unsplit transaction qualifies for the fallback and category writes are enabled
- **THEN** the updater clears the prefilled category and leaves the transaction unapproved

#### Scenario: A valid unmatched default is configured

- **WHEN** an unchanged, unsplit transaction qualifies for the fallback and category writes are enabled
- **THEN** the updater assigns the configured category without classification or a split; an invalid ID instead produces a configuration error and no category write

#### Scenario: The fallback cannot safely be applied

- **WHEN** retrieval failed, matching is ambiguous, or a fresh read finds the transaction approved, split or changed since planning
- **THEN** the updater makes neither unmatched memo nor category writes
