## 1. Establish the post-Sheetz baseline

- [ ] 1.1 After Sheetz enrichment ships, inventory every YNAB request path and capture offline counts by endpoint and workflow.
- [ ] 1.2 Verify collection PATCH response status, per-ID confirmation, supported mutation shapes and safe chunk bounds; verify collection/delta freshness and field coverage. Keep unsupported cases on guarded individual requests.

## 2. Reduce requests without changing retailer behavior

- [ ] 2.1 Consolidate accounting and transport; share plan metadata and transaction snapshots where equivalent. Preserve onboarding revalidation and rollback/recovery checks.
- [ ] 2.2 Dispatch verified write shapes in bounded chunks with per-transaction durable intent and confirmation. Cover all retailer paths and existing memo batches; reconcile uncertain outcomes before retry.
- [ ] 2.3 Defer safely on known quota constraints or 429 and identify unfinished work without logging sensitive data.

## 3. Verify and document

- [ ] 3.1 Compare complete before/after HTTP counts and resulting states using post-Sheetz baseline fixtures for all retailers, dry runs, no-op, onboarding, approval races, explicit null, splits, partial responses, lost responses and 429 recovery.
- [ ] 3.2 Document reductions and retained individual requests with their reasons. Run the repository's unit and integration suites.
