## 1. Qualify the source

- [ ] 1.1 Confirm permitted session access and expiry behavior, and document history depth and store scope using redacted evidence.
- [ ] 1.2 Verify how order items, combos and adjustments contribute to the total for exact split reconciliation.
- [x] 1.3 Verify authenticated retail fuel retrieval and confirm it returns a fuel purchase not present in online order history.

## 2. Integrate and verify

- [ ] 2.1 Implement the disabled-by-default mobile source in one request for the two verified types, with protected credentials and bounded standard-browser reauthentication. Verify valid-token reuse, one sign-in attempt, and no Sheetz writes after failed or challenged sign-in.
- [ ] 2.2 Implement exact-cent matching within three calendar days and preview, followed by opt-in Amazon-style memos with a purchase-history link fallback.
- [ ] 2.3 Implement opt-in category and reconciled split writes using the matched order, shared classifier and a separate Sheetz shortlist.
- [ ] 2.4 Include verified fuel records in the same match candidates as online orders. Add `sheetz.gasoline_default_category_id` to YAML configuration and the example config, and categorize matched fuel without classifier calls or splits.
- [ ] 2.5 Verify disabled, expired-session, date-boundary, ambiguous-match, memo link fallback and guarded-write behavior offline, including fuel versus online-order ambiguity and missing or invalid gasoline categories. Ensure Amazon and Walmart behavior is unchanged.
- [ ] 2.6 Add `sheetz.unmatched_default_category_id` to YAML and the example config. Implement the guarded unmatched memo, configured category assignment and explicit category clearing. Verify approved, split, failed-fetch and ambiguous cases receive no fallback writes.
