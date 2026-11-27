## Why

YNAB allows 200 requests per access token per rolling hour. The app already shares some reads and batches Amazon login notices, but guarded enrichment still reads, writes and verifies individual transactions. Adding Sheetz makes the total request budget more important.

## What Changes

- Implement only after Sheetz enrichment ships. Re-inventory the actual post-Sheetz endpoints and baseline counts before changing them. Cover every YNAB call in normal runs, dry runs, recovery, learning and interactive onboarding across Amazon, Walmart and Sheetz, including any reachable legacy path.
- Share equivalent reads, reduce duplicate freshness checks, and use bounded plan-level batch updates where the API preserves the existing operation's semantics. Retain narrower reads and individual writes where needed for correctness or when batching would cost more.
- Preserve approval, split reconciliation, retailer eligibility, durable intents, per-transaction confirmation and recovery. Stop safely on rate-limit exhaustion or an uncertain write; do not replay whole batches blindly.
- Measure total HTTP attempts and resulting YNAB state against deterministic post-Sheetz baseline fixtures. Do not count delta pagination or payload savings as request savings unless the number of calls actually falls.

## Capabilities

### New Capabilities

- `ynab-request-efficiency`: App-wide request accounting, shared reads, safe batch dispatch and rate-limit-aware deferral.

### Modified Capabilities

None. Retailer-facing behavior and write eligibility are unchanged.

## Impact

Touches YNAB transport, sync orchestration, retailer write/recovery paths and onboarding. No runtime changes in this proposal. The proposal PR can be reviewed independently; implementation depends on Sheetz enrichment, not merely on merging its proposal.
