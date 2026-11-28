## 1. Qualify the source

- [ ] 1.1 Confirm permitted session access and expiry behavior, and document history depth and store scope using redacted evidence.
- [x] 1.2 Adopt proportional approximation for tax and discounts; retain exact-cent total reconciliation without requiring combo or adjustment semantics.
- [x] 1.3 Verify authenticated retail fuel retrieval and confirm it returns a fuel purchase not present in online order history.

## 2. Integrate and verify

- [x] 2.1 Implement the disabled-by-default mobile source in one request for the two verified types, with protected credentials and bounded standard-browser reauthentication. Verify valid-token reuse, one sign-in attempt, and no Sheetz writes after failed or challenged sign-in.
- [x] 2.2 Implement exact-cent matching within three calendar days and preview, followed by opt-in Amazon-style memos with a purchase-history link fallback.
- [x] 2.3 Implement opt-in category and proportional split writes using the matched order, shared classifier and a separate Sheetz shortlist.
- [x] 2.4 Include verified fuel records in the same match candidates as online orders. Add `sheetz.gasoline_default_category_id` to YAML configuration and the example config, and categorize matched fuel without classifier calls or splits.
- [x] 2.5 Verify disabled, expired-session, date-boundary, ambiguous-match, memo link fallback and guarded-write behavior offline, including fuel versus online-order ambiguity and missing or invalid gasoline categories. Ensure Amazon and Walmart behavior is unchanged.
- [x] 2.6 Add `sheetz.unmatched_default_category_id` to YAML and the example config. Implement the guarded unmatched memo, configured category assignment and explicit category clearing. Verify approved, split, failed-fetch and ambiguous cases receive no fallback writes.

## Implementation gate

History depth and store scope remain unknown. Malformed items are logged and omitted while usable receipts are retained; if a receipt cannot be matched due to invalid identity, date or amount, the partial feed is preview-only and no unmatched fallback is proposed. Headless sign-in and receipt retrieval have succeeded live, but intermittent sign-in failures and source coverage require further qualification before closing task 1.1. Offline tests cover disabled operation, token reuse and expiry, one bounded request, challenges and 403 failures, matching, fallback safeguards, and independent retailer processing. The user chose proportional approximation of the matched total instead of requiring exact tax, combo or discount semantics; the resulting split amounts sum exactly to the transaction.

On 2026-11-28, a fresh read-only authenticated feed request returned eight online receipts. All eight had numeric item `totalPrice` values and summary lines, but none of the sums of item `totalPrice` equaled the corresponding order total. One receipt had a non-unit quantity. A subsequent read-only check found a numeric tax-labeled summary line on all eight receipts; the sum of item `totalPrice` plus tax equaled the order total for all eight. Two receipts had at least one summary line marked as a discount. These aggregate checks do not establish how to allocate tax, or qualify the treatment of combos and other adjustments for split writes. No receipt contents, account identifiers or credentials were recorded.
