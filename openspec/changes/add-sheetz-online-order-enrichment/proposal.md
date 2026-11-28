## Why

Sheetz charges in YNAB do not identify what was purchased. Recent Sheetz online order history can supply order and item metadata for enrichment, but the verified source is not equivalent to the mobile app's broader purchase history.

## Verified source

On 2026-11-27, the signed-in [Sheetz order history page](https://orders.sheetz.com/order/history) made a successful `GET /anybff/api/orders/favoritesAndHistory` with a `storeNumber` query parameter. With the same URL in the same browser, cookies alone returned 401; the existing Bearer token with browser cookies omitted returned 200 without the `sessionid` or `client-version` headers. This is a private web endpoint, not a documented public API. The observed web tokens advertised a lifetime of about 30 minutes; no refresh flow was observed. See [design.md](design.md) for the browser evidence and open questions.

The response included an online pickup order from the mobile history, with a matching total, an order number, pickup timestamp, payment mode and item descriptions, quantities and prices. It did not include the fuel purchase from the same day. History depth and the effect of changing stores are unknown.

Clicking an order in the signed-in website opened `https://orders.sheetz.com/order/history/detail` with no order ID or query parameter. The browser navigation state held `order` and `storeNumber`; opening the same URL in a fresh signed-in tab did not load the order. Use the purchase-history link with the order number in the memo unless a reusable order-specific link is verified.

## What Changes

- Use the mobile filtered-receipts endpoint as the sole purchase source for online orders (`ANYWHERE`) and retail fuel (`RETAIL_FUEL`). Reuse an unexpired web token or sign in again once through the normal website using protected credentials. Stop for MFA, CAPTCHA or access blocks. No passwords or tokens in YAML, logs, fixtures or process arguments.
- Reuse existing YNAB write safeguards and the shared item classifier, with a Sheetz-specific category shortlist. Start with a preview; enable memo writes separately from category and split writes.
- Match eligible Sheetz outflows one-to-one to an online order by exact cents and an inclusive three-calendar-day window in either direction. The order record is sufficient matching evidence; no separate payment API or payment-account evidence is required. Leave ambiguous matches unchanged, and approximate item splits proportionally while ensuring their amounts sum exactly to the matched total.
- Use Amazon-style memos with the date, order total, a compact item summary and a verified order link when available. Otherwise include the order number and link to the purchase-history page.
- Match fuel and online purchases together under the same amount/date rules. For a unique fuel match, set the whole transaction to the existing YNAB category configured as `sheetz.gasoline_default_category_id` (select `Gasoline` for this plan). Do not call Jev or create splits for fuel.
- After a successful fetch, treat a zero-candidate Sheetz outflow as presumed non-fuel/non-online, not as a verified in-store receipt. Only update unapproved transactions. With memo writes enabled, record the missing match; with category writes enabled, use `sheetz.unmatched_default_category_id` or explicitly clear the category if unset, even if YNAB populated one. No hard-coded default and no classification call. Defer itemized coverage for other point-of-sale purchases, refunds and mixed tender.

## Capabilities

### New Capabilities

- `sheetz-online-order-enrichment`: Bounded Sheetz purchase retrieval, conservative matching, opt-in online-order enrichment and deterministic fuel categorization.

### Modified Capabilities

None. Existing Amazon and Walmart behavior remains unchanged.

## Impact

Touches updater orchestration, configuration, and the existing category and YNAB write interfaces. Verification uses redacted or synthetic fixtures and fake YNAB responses. This proposal itself changes no runtime code or live configuration.
