# amazon-transaction-enrichment Specification

## Purpose

Define Amazon memo enrichment and opt-in, no-default item categorization for purchases and refunds using independently qualified payment/item evidence, exact-cent allocation, guarded uncategorized fallback and journaled human-review-safe writes.

## Requirements

### Requirement: Opt-in no-default Amazon categorization
Amazon SHALL default to memo-only enrichment. Category writes SHALL require explicit `item_categories` and an independent eligible category shortlist. For eligible bank-targeted item allocation, each item SHALL receive an accepted shortlisted category or null, with no default, inherited Walmart policy or parent-category fallback. Orders without a bank target SHALL NOT require inference. Shared classification SHALL own decisions; Amazon SHALL own evidence and allocation.

#### Scenario: An item cannot be classified
- **WHEN** an otherwise eligible order contains an item with a valid abstention or conflicting approved mappings
- **THEN** that item's amount remains explicitly uncategorized on its item line rather than being absorbed by another category

#### Scenario: Provider transport fails
- **WHEN** necessary inference fails rather than returning a validated decision
- **THEN** no item category or split is applied; an eligible trustworthy match receives order metadata/link and explicit `category_id: null` under the fallback contract

### Requirement: Linked uncategorized fallback when allocation is unsafe
In `item_categories` mode, an eligible trustworthy match lacking allocation confidence SHALL receive its known order metadata and complete mobile link, explicit `category_id: null` and `approved: false`, with no splits. Omitted category fields or an Uncategorized category ID SHALL NOT substitute for null. This fallback SHALL require fresh ownership checks, memo bounds, durable intent and read-back, but not item allocation evidence. `memo_only` mode SHALL NOT clear categories.

#### Scenario: Purchase association is known but item amounts do not reconcile
- **WHEN** an eligible parent is trustworthily associated with a real order but item costs/adjustments do not explain the transaction
- **THEN** write the robot/date/known total/item-summary/mobile-link memo and explicit null category together, preserve the bank amount and report the failed allocation gate without creating splits or guessing missing metadata

#### Scenario: A matched refund lacks refunded-item evidence
- **WHEN** an eligible refund credit has a trustworthy original-order association but its item membership or net amounts are unknown
- **THEN** write refund metadata and the original order's mobile link with explicit null parent category, without inventing refunded items or applying a default income category

#### Scenario: An existing category is not owned by this workflow
- **WHEN** the parent has a non-null category without trustworthy app ownership, an existing split, approval or a human change since baseline
- **THEN** skip the fallback and report the conflict rather than clearing a human category or replacing reviewed work

#### Scenario: No trustworthy order association exists
- **WHEN** candidates cannot establish which real order belongs to the bank transaction
- **THEN** neither guessed metadata/link nor a category-clearing write occurs

#### Scenario: Null serialization and fallback recovery
- **WHEN** a fallback is submitted and its response is lost
- **THEN** the serialized request contains `category_id: null`, not an omitted field; restart verifies the memo and null category against saved intent before retry, and later item allocation remains incomplete

### Requirement: Explicit payment-account identity and fallback association
Amazon SHALL use independently confirmed `amazon.payment_accounts` mappings from normalized source payment identities to eligible plan account IDs. Fallback association SHALL require a unique completed event with real order ID, exact signed cents, mapped account and date within 14 days, or a trusted unchanged journal association without contradictions. Legacy scores, display memos, processed IDs and order totals alone SHALL NOT authorize category clearing.

#### Scenario: Two accounts could contain the charge
- **WHEN** the amount/date fit but source payment identity lacks a confirmed account mapping
- **THEN** neither allocation nor category-clearing fallback occurs, and preview identifies the missing mapping rather than matching by account name or payee

#### Scenario: Item evidence is incomplete but event association is established
- **WHEN** a completed event uniquely identifies the real order, exact bank amount and mapped account within the date bound, but adjustment details are missing
- **THEN** an otherwise eligible parent receives the linked uncategorized fallback without assuming that the full order equals that bank charge

#### Scenario: A bank amount has sub-cent precision
- **WHEN** a YNAB parent has −65001 milliunits and source evidence is a −$65.00 charge
- **THEN** matching fails without rounding the bank amount; neither item allocation nor event-based category-clearing fallback occurs

### Requirement: Evidence-based exact purchase eligibility
Item category/split allocation SHALL require a complete USD purchase with evidenced items, adjustments, currency and one external charge, optionally supplemented by known gift funding. Funding SHALL explain full order value; the charge SHALL uniquely match an eligible YNAB outflow in exact cents. Scores/defaulted getters SHALL NOT establish completeness. Incomplete evidence SHALL block allocation, not the separately guarded linked uncategorized fallback.

#### Scenario: A purchase falls outside initial scope
- **WHEN** purchase evidence includes conflicting duplicate records, multiple external charges, unexplained partial funding, unknown gift-card amounts, synthetic IDs, unsupported precision/currency or unexplained costs
- **THEN** purchase categorization is blocked with a specific reason; refund credits are evaluated separately under the refund contract

#### Scenario: One order has multiple external charges
- **WHEN** a $90 order has distinct $30 and $60 bank charges, each independently and uniquely matched to eligible YNAB parents
- **THEN** neither charge receives automatic item allocation; both receive known order metadata and the same order's mobile link with explicit null category under guarded fallback, preserving separate bank amounts and charge identities without treating order reuse as ambiguity

#### Scenario: Two orders compete for one parent
- **WHEN** two Amazon orders have indistinguishable payment/amount/date evidence for the same YNAB transaction
- **THEN** neither is selected for financial allocation merely by source ordering or best-score tie breaking

#### Scenario: Only merchandise and grand total are known
- **WHEN** merchandise totals $60 and grand total is $65 without evidence identifying the $5 difference
- **THEN** allocation skips with an unexplained-cost reason rather than assuming tax or adding a balancing line

### Requirement: Financial confidence requires all evidence gates
Item category/split allocation SHALL require complete purchase/refund evidence, verified USD, known item amounts, explained nonoverlapping adjustments, unique exact-cent payment/account association, exact sums and an unchanged eligible parent. Every applicable gate SHALL pass; scores SHALL NOT compensate for failure. Preview SHALL distinguish allocation failure from eligibility for linked uncategorized fallback; without a trustworthy association or write eligibility, no fallback SHALL occur.

#### Scenario: A high score accompanies incomplete data
- **WHEN** a legacy match scores highly but charge paging is incomplete, quantity is defaulted rather than evidenced, a relevant parse failed or duplicate records conflict
- **THEN** no item category/split allocation occurs; preview reports failed gates and separately evaluates the linked uncategorized fallback

#### Scenario: Payment matching is insufficient
- **WHEN** charge/order reference or compatible payment/account identity is missing, more than one assignment is possible, the completed date is over 14 days from the bank date or the amounts differ by one cent
- **THEN** the financial match fails without choosing a best-scoring candidate

#### Scenario: Gift-card funding changes payable total
- **WHEN** a $35.53 purchase is entirely gift-card-funded and details grand total is $0
- **THEN** funding is not a merchandise discount or proof of a zero-value order; with verified full gift-card funding and no external charge, no YNAB match, synthetic transaction or financial write is needed

### Requirement: Proportional bank allocation after gift-card funding
Partial gift-card purchases SHALL classify all order items and allocate only the external charge proportionally over adjusted full-order item costs, after discounts/tax/shipping/fees. Known gift funding plus that charge SHALL equal full order value. Allocation SHALL use stable largest-remainder cents, omit zero rows and preserve their item provenance. No gift-card offset line or transaction SHALL be created; fully gift-funded orders SHALL require no YNAB match or write.

#### Scenario: Gift cards cover part of a taxed order
- **WHEN** $20/$40 merchandise plus $5 tax gives $21.67/$43.33 adjusted costs and known gift funding is $15 with a uniquely matched $50 external charge
- **THEN** both items receive category decisions and bank lines are $16.67/$33.33, totaling −50000 YNAB milliunits, without a gift-card category line

#### Scenario: Item discounts affect gift-card allocation weights
- **WHEN** a cable-specific $6 discount produces $15.30/$43.70 adjusted costs after tax and $10 gift funding leaves a $49 external charge
- **THEN** bank lines are $12.71/$36.29 using adjusted costs, not raw $20/$40 merchandise weights

#### Scenario: Gift funding is inferred only from a gap
- **WHEN** full order value exceeds the external charge but no reliable funding amount explains the difference
- **THEN** item allocation stops rather than labeling the gap as a gift-card payment; a trustworthy eligible match still uses the linked uncategorized fallback

#### Scenario: A small charge rounds an item allocation to zero
- **WHEN** all order items are classified but proportional bank allocation gives an item zero cents
- **THEN** retain that item's decision/provenance, omit its zero row and use category-only application if only one nonzero row remains

### Requirement: Category confidence is separate from financial evidence
Category decisions SHALL use approved unambiguous shortlisted mappings or validated provider results meeting all configured gates. Initial gates SHALL be probability at least 0.80, confidence at least 0.80 and runner-up margin at least 0.20; scores SHALL NOT guarantee correctness. Valid rejected decisions SHALL yield null item categories when financial gates pass; operational failures SHALL block item allocation but permit the separately guarded linked uncategorized fallback.

#### Scenario: Financial evidence is complete but a category is uncertain
- **WHEN** all financial gates pass and a valid decision misses any category threshold or abstains
- **THEN** the known item amount remains on a null-category line for human review rather than preventing reconciliation or becoming default spending

### Requirement: Exact no-default item allocation
Allocation SHALL reconcile exact cents without zero/default/balancing lines or negative costs. Each item with nonzero bank allocation SHALL retain a row even when categories agree; one nonzero row SHALL use a category-only write with parent item text. Discounts SHALL precede proportional tax/shipping/fees over final net merchandise weights. Proportional amounts SHALL use largest-remainder cents and stable item-ID ties; nonzero adjustments with zero weight SHALL skip.

#### Scenario: Explicit tax is distributed
- **WHEN** $20 and $40 items have $5 explicit order-wide tax and no other adjustments
- **THEN** their lines are $21.67 and $43.33, summing to $65.00 with their respective decisions

#### Scenario: Discount precedes tax
- **WHEN** $20 and $40 items have a $6 order discount and $5 tax
- **THEN** their final allocated amounts are $19.67 and $39.33, summing to $59.00 without a default remainder

#### Scenario: Shipping is allocated proportionally
- **WHEN** $20 and $40 net merchandise items have a $3 shipping charge
- **THEN** shipping allocates $1 and $2, yielding $21 and $42 without a separate default line

#### Scenario: Categories coincide
- **WHEN** two distinct items receive the same accepted category
- **THEN** they retain separate item rows and provenance so a human can correct one without relabeling the other

### Requirement: Item-first discounts with proportional fallback
Discounts SHALL apply to the identified item when explicit source evidence establishes attribution; otherwise known discount amounts SHALL be proportional over applicable net merchandise costs, all items unless eligibility is evidenced. Item discounts SHALL precede remaining discounts in stable source order. Each adjustment SHALL count once; aggregate totals SHALL be reconciliation checks, not extra adjustments. Unknown attribution alone SHALL NOT block allocation.

#### Scenario: Discount identifies one item
- **WHEN** a $6 discount explicitly applies to the $20 cable, alongside $40 pet food and $5 tax
- **THEN** net merchandise is $14/$40 and proportional tax yields final lines of $15.30/$43.70, totaling $59.00

#### Scenario: Discount has no item association
- **WHEN** the same $6 discount amount is known but its item association is absent
- **THEN** proportional discount allocation yields $18/$36 merchandise and $19.67/$39.33 final lines after tax, without asking a model to infer attribution

#### Scenario: Promotion totals overlap
- **WHEN** available promotion and coupon fields may describe the same discount and their distinctness cannot be established
- **THEN** financial allocation skips rather than subtracting both or adding a balancing line to force agreement

### Requirement: Distinct refund-credit matching
Refund matching SHALL uniquely associate a positive YNAB inflow with one positive Amazon credit occurrence using exact cents, real order reference, compatible refund payment/account identity and completed date within 14 days of the bank date. Events SHALL be consumed once globally and retain multiplicity across same-order/equal-value credits. Cumulative refund totals, original purchase date or best-score ties SHALL NOT substitute for event evidence.

#### Scenario: Two refunds belong to one order
- **WHEN** one order has distinct $10 and $20 credits and uniquely matching bank inflows
- **THEN** each inflow matches its own event rather than using a merged $30 refund total or one `RETURN-<order ID>` record

#### Scenario: Equal-valued events cannot be distinguished
- **WHEN** available references, dates and payment identity cannot resolve competing refund assignments or preserve identity across restarts
- **THEN** the ambiguous candidates remain unchanged instead of being assigned by input ordering

#### Scenario: Original purchase is ineligible for automatic allocation
- **WHEN** a refund is independently evidenced but its original purchase is old, approved, split or multi-payment
- **THEN** refund eligibility is assessed independently and the original purchase is not rewritten

### Requirement: Event-specific refunded-item evidence
Refund item allocation SHALL require explicit association of items and known net amounts to the credit, with quantities when needed, known adjustments and exact reconciliation. Full reversal provenance MAY supply bases for explicitly identified quantities. Original order items, subset sums, generic titles, cumulative totals and inference SHALL NOT establish multi-item refund membership or amounts. Unsupported evidence SHALL use linked uncategorized fallback only when separately eligible.

#### Scenario: Credit amount is known but items are not
- **WHEN** a +$65 credit identifies an order containing several items but does not establish which were refunded
- **THEN** an eligible parent receives trustworthy refund metadata/link and explicit null category with `refund_items_unknown`, regardless of category confidence

#### Scenario: Refunded items lack reconciled amounts
- **WHEN** two refunded products are identified but their amounts and adjustments do not explain the bank credit exactly
- **THEN** item allocation stops with `unreconciled_refund`; an eligible trustworthy match receives metadata/link and explicit null category rather than guessed item amounts

### Requirement: Refunded-item category precedence
Refund categories SHALL first use unambiguous human-approved original-item evidence within the eligible Amazon shortlist, then shared approved product mappings/provider decisions when no such evidence exists. Conflicting or known out-of-list approved original categories SHALL remain unresolved, not be overridden by inference. Aggregate parent categories SHALL NOT serve as item defaults. Valid abstention SHALL permit null item categories when refund finances reconcile.

#### Scenario: Original item was reviewed by the human
- **WHEN** a refund identifies an item whose provenance-backed approved purchase row uses Electronics in the Amazon shortlist
- **THEN** its refund uses Electronics without new inference or editing the original purchase

#### Scenario: No trustworthy original category exists
- **WHEN** refund item identity and amounts are known but the original item category is unavailable
- **THEN** shared classification uses the actual refunded item and Amazon shortlist with existing acceptance gates, never a default income or Walmart category

### Requirement: Positive refund category and split updates
Refund allocation SHALL omit zero-net rows, use category-only for one nonzero item, or positive splits for multiple nonzero items summing exactly to the inflow. Use final item credits or reconciled bases, never both. Refunded tax/shipping/fees SHALL be proportional; discount deductions SHALL be item-attributed when known, otherwise proportional. Nonrefunded charges SHALL NOT be added. Negative-net or mixed-tender cases SHALL block allocation, not independently eligible fallback.

#### Scenario: Multi-item refund includes tax
- **WHEN** an eligible +$65 credit explicitly refunds $20 cable merchandise, $40 pet-food merchandise and $5 tax
- **THEN** its split amounts are +21670/+43330 YNAB milliunits with the respective item categories and memos, summing to +65000

#### Scenario: Refunded amount includes a specific promotion deduction
- **WHEN** the cable has an explicit $6 promotion deduction from those refunded merchandise amounts plus $5 refunded tax
- **THEN** positive item lines are +$15.30 and +$43.70, totaling +$59.00 without restoring original nonrefunded shipping

#### Scenario: One identified refund item has zero net credit
- **WHEN** explicit final item credits are $0 for one product and $10 for another, explaining a +$10 event
- **THEN** retain both items' evidence but write no zero row, use category-only for the $10 product and never learn the zero-credit product from that parent category

### Requirement: Refund memos and event-scoped recovery
Refund parents SHALL label the credit date/amount as Refund, summarize evidenced refunded items and use the real order's mobile URL. Rows SHALL identify refunded items. User text and memo bounds SHALL be preserved. Refund journals SHALL track distinct event/item intent and recovery, not only order IDs; new same-order credits SHALL NOT be treated as prior-plan drift. Shared eligibility, fresh reads, unapproved writes, read-back and learning safeguards SHALL apply.

#### Scenario: Refund parent and item memo are generated
- **WHEN** credit 2026-11-24 for +$21.67 refunds a cable from order 113-8849250-2919462
- **THEN** parent content identifies `Refund $21.67 USD` and uses `https://www.amazon.com/your-orders/order-details?orderID=113-8849250-2919462`, while any item row identifies `🤖 Refund: USB-C cable` without a `RETURN-` URL prefix

#### Scenario: A second credit arrives after a completed refund
- **WHEN** a later distinct refund for the same order is discovered after the first credit was applied
- **THEN** its event is processed independently without replaying or changing the first credit; changes to an already applied event instead stop for review

#### Scenario: A refund split response is lost
- **WHEN** YNAB accepts a positive refund split but the response is lost
- **THEN** restart verifies saved intent before retry, never edits an approved/human-changed credit and never records the refund twice as learning evidence

### Requirement: Compact parent memos and mobile order links
Purchase memos SHALL retain user text, robot, known ISO date/total/currency, up to three titles, and a locally built `https://www.amazon.com/your-orders/order-details?orderID=<order-id>` link for real IDs. Fallback SHALL omit unknown metadata. Known gift funding/charge SHALL be labeled. Only generated titles SHALL yield to 500 characters; insufficient space SHALL skip updates. Markers/URLs SHALL stay intact; legacy/source links or shorteners SHALL NOT be used.

#### Scenario: An ordinary two-item order is enriched
- **WHEN** order 113-8849250-2919462 dated 2026-11-20 totals USD $65.00 with USB-C cable and Dog food items and no user memo
- **THEN** the memo is `🤖 2026-11-20 | Total $65.00 USD | 2 items: USB-C cable, Dog food | https://www.amazon.com/your-orders/order-details?orderID=113-8849250-2919462`

#### Scenario: Parent distinguishes order value from bank spending
- **WHEN** the $65 order uses $15 gift funding and charges $50 externally
- **THEN** required parent metadata additionally labels `Gift card $15.00 | Charged $50.00`, preserving full order total and URL; generated titles yield first and insufficient required space blocks the update

#### Scenario: Fallback lacks the original purchase total or date
- **WHEN** a real order is trustworthily associated with an eligible bank parent but the original order date/total is unavailable
- **THEN** fallback retains known metadata, user text, robot marker and full order link; unknown fields are omitted rather than using zero, bank date or charge amount as invented original order metadata

#### Scenario: Long titles compete with a URL
- **WHEN** full generated titles would exceed 500 characters but user text and required metadata fit
- **THEN** only the generated summary is shortened or omitted, leaving the user text and URL complete

#### Scenario: A synthetic Subscribe and Save identifier is present
- **WHEN** a memo-only record has a `SUB-` identifier without a verified real Amazon order ID
- **THEN** its S&S context is retained without inventing an order-details URL or allowing financial allocation

### Requirement: Item-bearing split memos
Each Amazon split row SHALL include its generated robot marker and item title, with quantity when greater than one, within 500 characters. Nullable category decisions SHALL NOT hide item identity. Full item/row provenance SHALL be retained in protected state independently of shortened display text.

#### Scenario: An unresolved repeated-quantity item is split
- **WHEN** two units of Dog food produce a null-category item line
- **THEN** its memo includes `🤖 2 × Dog food` and its amount represents the extended, adjusted item cost

### Requirement: Guarded writes and journaled progression
All category writes SHALL require eligible unapproved, unsplit, nondeleted/nontransfer, unreconciled parents in open on-budget accounts, preserving non-owned categories. They SHALL fresh-check identity, amount, memo/category and state, persist intent, write `approved: false` and verify read-back. Unknown outcomes SHALL resolve before retry; applied item-allocation drift or human edits SHALL stop writes. Documentation SHALL disclose live budget effects before approval.

#### Scenario: Human edits after matching
- **WHEN** the fresh parent has a changed category, memo or approval relative to the saved baseline
- **THEN** the financial update stops without overwriting or unapproving that work

#### Scenario: A response is lost
- **WHEN** YNAB accepts a category-only or split write but its response is lost
- **THEN** the next read compares saved intent and recognizes the result before considering a retry

### Requirement: Explicit completion and safe legacy handling
Amazon SHALL journal memo-only, category and split completion separately rather than infer it from memo text/length. Trusted memo-only results SHALL allow later categorization after fresh validation. Legacy processed IDs or robot markers SHALL NOT authorize financial rewrites/backfill; legacy exclusions SHALL remain by default. Only unambiguous approved item rows with saved provenance SHALL train retailer/plan/product mappings.

#### Scenario: Categorization follows memo-only enrichment
- **WHEN** item_categories is enabled for an eligible parent with a matching trusted memo-only journal baseline
- **THEN** its existing summary/link does not prevent categorization and the generated metadata is not duplicated

#### Scenario: New evidence enables allocation after fallback
- **WHEN** linked uncategorized fallback is confirmed applied, the parent is unchanged and new noncontradictory evidence satisfies the allocation gates
- **THEN** save a new allocation intent under current policy and allow the first allocation without treating added evidence as applied-allocation drift, replacing only saved generated metadata

#### Scenario: New evidence contradicts prior association
- **WHEN** the bank amount/event/order identity or a previously known financial fact conflicts with saved fallback evidence
- **THEN** stop for review rather than using fallback progression to redirect the parent; completed item allocations remain frozen against later source/policy/mapping drift

#### Scenario: Only an old processed ID exists
- **WHEN** a previously processed transaction lacks trusted allocation ownership/provenance
- **THEN** it is not automatically recategorized and the old processed-ID file is not cleared to force backfill

### Requirement: Offline verification and independent operation
Default verification SHALL cover exact allocation, independent shortlists, compact links, abstention/failure, protected writes, progression and recovery through deterministic source/provider/YNAB fixtures without account access or paid requests. Amazon classification SHALL run without Walmart enabled or Go installed; failure in one retailer SHALL NOT prevent independent processing of the other.

#### Scenario: A base installation uses Amazon only
- **WHEN** the JVM distribution runs configured Amazon enrichment with Walmart disabled
- **THEN** shared classification and guarded YNAB operations require no Walmart helper, session, default or category configuration
