## Purpose

Associate individual YNAB purchases with Walmart final charges, concentrate non-default items into few transactions, and use existing categories with YNAB-only review and feedback without duplicating spending or overwriting user work.

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
- **WHEN** an exception-first split is written
- **THEN** the parent memo remains metadata-only and exception item descriptions appear only on their individual split lines without itemizing default-category purchases

### Requirement: Existing categories and explicit default policy
Exception-first mode SHALL require a valid Default Walmart Category ID and an explicit nonempty shortlist of existing eligible category IDs in that YNAB plan, including the default. The application SHALL reject duplicates/invalid targets, restrict all automatic decisions and mapping reuse to the shortlist, and never create, rename or suggest categories. Remote classification SHALL require explicit consent to minimal OpenAI data disclosure and the selected authentication/billing route.

#### Scenario: An approved product mapping exists
- **WHEN** a product has unambiguous approved evidence for an allowed existing category
- **THEN** that mapping takes precedence over model inference; conflicting approved evidence suspends automatic classification instead of letting the model choose

#### Scenario: A product is confidently default spending
- **WHEN** a trustworthy mapping or accepted model result selects the default
- **THEN** its amount collapses into the default remainder without itemization; unresolved products do not receive this treatment

#### Scenario: Existing category is renamed or removed
- **WHEN** a mapped category is renamed without changing its ID, or is deleted, hidden or becomes ineligible
- **THEN** a rename preserves the valid ID mapping, while an ineligible default prevents category allocation and an ineligible exception target becomes unresolved without creating a replacement category

#### Scenario: A product has an uncertain non-default category
- **WHEN** a product lacks an unambiguous approved mapping and the model cannot choose a trustworthy allowed category
- **THEN** its cost is retained as an uncategorized exception item for human review, not silently absorbed into the default or placed in a newly created review category

#### Scenario: The operator narrows the category shortlist
- **WHEN** the configured shortlist excludes a previously learned category or omits the default
- **THEN** the excluded mapping is not reused and an absent default prevents allocation, without expanding the list or suggesting a new category

### Requirement: Groovy-owned OpenAI classification
Groovy SHALL own inference, OAuth refresh, normalization, categorization, splitting and YNAB processing; Go SHALL only retrieve Walmart evidence and manage its retrieval session/transport. Local Laya/Kev runtimes SHALL be removed. API keys SHALL use public Decisions. OAuth Responses SHALL remain implementation-gated until the proposal's endpoint choice is resolved; OAuth Decisions entitlement SHALL NOT be assumed.

#### Scenario: A classification request fails
- **WHEN** OpenAI returns an authentication, quota, network or model error
- **THEN** Groovy retains unresolved items for review or link-only handling without changing endpoint/auth mode, silently billing another connection or accepting malformed results

### Requirement: Guided OpenAI authentication onboarding
The onboarding script SHALL launch Groovy setup, offer API key versus app-owned ChatGPT OAuth, explain the actual endpoint/model, remote data and mode-specific billing, and obtain consent before authentication or inference. API-key input SHALL be hidden. OAuth SHALL use the documented open-source registration, loopback callback and PKCE flow, not borrowed Codex credentials/client IDs or private ChatGPT backends. Credentials SHALL be stored separately from YAML in protected atomic records.

#### Scenario: An operator chooses API-key authentication
- **WHEN** hidden console input is available and the operator confirms the paid Decisions route
- **THEN** setup captures the key without command arguments, echo or logs, stores only a credential-file reference in YAML and does not imply ChatGPT subscription coverage

#### Scenario: An operator chooses OAuth
- **WHEN** the OAuth endpoint choice has been approved and browser authorization starts
- **THEN** Groovy uses fresh state/nonce/PKCE-S256, validates the loopback callback and ID-token signature/issuer/audience/expiry/nonce/scopes, and saves the issued app/account client registration without reusing another application's identity

#### Scenario: Authentication is cancelled or unavailable
- **WHEN** the operator cancels or hidden input/browser/loopback requirements cannot be satisfied
- **THEN** the existing connection/configuration remains active, staged credentials are cleaned up and safe retry/API-key/cancel guidance is given without insecure credential capture

### Requirement: Groovy-owned OAuth refresh and credential isolation
Groovy SHALL refresh using the saved issued client ID and documented endpoint/resource. Rotation SHALL be serialized across threads/processes and atomically saved with scopes/expiry. Unknown outcomes SHALL require recovery, not blind replay. Tokens SHALL NOT reach Go, YAML, arguments, logs or purchase journals. Owner-only POSIX permissions or equivalent verified Windows ACLs SHALL protect credentials and locks.

#### Scenario: Several runs observe an expiring token
- **WHEN** concurrent processes need the same OAuth connection refreshed
- **THEN** a protected lock and reread permit one coordinated refresh, preserving the rotated credential record without stale-token overwrite

#### Scenario: Authorization or quota fails
- **WHEN** inference explicitly rejects authentication or reports exhausted quota
- **THEN** at most one coordinated authentication refresh/replay occurs within budgets, quota is not treated as expiry, and no API-key fallback or retry loop occurs

#### Scenario: The operator disconnects
- **WHEN** logout is confirmed
- **THEN** inference stops, documented revocation is attempted and local tokens are removed with any unconfirmed remote revocation disclosed

### Requirement: Numbered existing-category onboarding
Onboarding SHALL read eligible categories from the configured YNAB plan, display numbered group/name choices and accept comma-separated numbers for the shortlist. It SHALL resolve choices to the displayed IDs, reject invalid/empty/duplicate selections and ask for the default from the selected list. No manual ID copying, category creation or live transaction writes SHALL be required or performed.

#### Scenario: Category names are duplicated
- **WHEN** two eligible categories have the same name in different groups and the operator selects their displayed numbers
- **THEN** the group labels distinguish them and the configuration uses the corresponding stable IDs, not names or a newly fetched list's positions

### Requirement: Platform-qualified onboarding and documentation
Initial host targets SHALL be Linux x86_64, macOS arm64 and Windows x86_64 using the native Groovy/JVM launcher and packaged retrieval helper. A documented JVM/browser/credential-permission matrix SHALL distinguish intended from verified support. Docker, Python inference, Hugging Face credentials and model downloads SHALL NOT be prerequisites.

#### Scenario: Windows credentials are saved
- **WHEN** onboarding runs natively on Windows
- **THEN** protected credential ACLs are verified without requiring Docker Desktop or WSL commands

#### Scenario: A host has not been verified
- **WHEN** the platform lacks qualified launcher/helper/authentication support
- **THEN** documentation identifies the unverified boundary rather than claiming support from helper binary availability alone

### Requirement: Confirmed safe onboarding configuration
Onboarding SHALL confirm auth-mode/endpoint/model/credential-location/shortlist/default without revealing secrets, revalidate categories and atomically save protected config preserving unrelated settings and detecting concurrent edits. Cancellation/failure SHALL preserve config; reruns SHALL preselect valid choices. Setup SHALL NOT change enablement/mode or migrate local settings into remote-data consent.

#### Scenario: Selection is cancelled or becomes stale
- **WHEN** the operator cancels, a selected category becomes ineligible or another process edits the config before save
- **THEN** the original config is not overwritten, no partial settings are applied, and selection/retry guidance is given without silently replacing categories

### Requirement: Bounded remote requests and explicit billing
Groovy SHALL use bounded TLS requests to exact public OpenAI inference/authentication endpoints with isolated credentials and no off-origin redirects. Positive connect/read/total deadlines, response/event limits, concurrency and per-run request/token budgets SHALL apply. Transient retries SHALL be bounded and account for potentially duplicate cost. Documentation SHALL distinguish paid Platform billing from subscription OAuth limits; no automatic billing/model/endpoint fallback SHALL occur.

#### Scenario: A request exceeds its budget
- **WHEN** a response is oversized, a deadline expires or the run exhausts its request/token allowance
- **THEN** inference stops safely without accepting incomplete output, exceeding configured retry budgets or substituting another billing route

#### Scenario: Local processing needs no new inference
- **WHEN** Walmart is disabled/link-only or trustworthy mappings/cache entries cover the items
- **THEN** no unnecessary OpenAI inference occurs and disabled/link-only operation requires no OpenAI authentication/refresh

### Requirement: Bounded choices and explicit abstention
Groovy SHALL submit the entire shortlist plus abstain with meaningful labels/descriptions mapped to stable local IDs. Decisions scores SHALL be validated and qualified. Approved OAuth Responses SHALL have a separately qualified choice/abstention contract, not equivalent generated confidence. Limits SHALL be checked without silent omission/truncation. Uncertainty SHALL NOT become default spending. Models SHALL NOT choose amounts or write.

#### Scenario: Similar product names cross category boundaries
- **WHEN** representative fixtures include human food, pet food, household supplies and ambiguous product names
- **THEN** each provider is evaluated against independently labeled expected categories and abstentions, including exception recall and default precision, before thresholds are accepted

#### Scenario: The model returns an out-of-list choice or invalid score
- **WHEN** output names an unknown category, has non-finite scores or is incomplete for the request
- **THEN** the application rejects that result without creating categories, accepting partial malformed batch output or defaulting unresolved products to Groceries

#### Scenario: All configured choices do not fit
- **WHEN** product text and category descriptions exceed the selected endpoint/model's token or choice budget
- **THEN** classification abstains/reports the limit rather than dropping allowed categories or silently truncating potentially decisive input

### Requirement: Minimal consented remote classification inputs
Only bounded necessary product attributes and shortlisted category labels/descriptions SHALL reach OpenAI after explicit consent. Requests SHALL omit sessions, YNAB credentials/IDs, addresses, order IDs/links, payments, bank memos and the full budget. Models SHALL receive no tools or write authority. Raw prompts/responses SHALL NOT be logged by default. Documentation SHALL disclose applicable mode-specific service data policies without promising local-only processing or zero retention.

#### Scenario: Remote requests are inspected
- **WHEN** mock OpenAI endpoints capture classification requests while Walmart/YNAB credentials are configured
- **THEN** only the consented minimal classification payload and the selected OpenAI authentication reach their intended origin without unrelated account data

#### Scenario: Hostile product text is classified
- **WHEN** product text requests credentials, tools or category creation
- **THEN** it remains untrusted data with no such capabilities and all output still requires application-side membership/schema/acceptance validation

### Requirement: Complete route-specific inference validation
Groovy SHALL reject unknown/duplicate/missing entries, malformed output, refusals and invalid scores. Decisions SHALL validate complete distributions/confidence. Approved OAuth Responses SHALL use the public endpoint, `store:false`, `stream:true`, supported fields and a schema-qualified model, accepting output only after valid terminal `response.completed`. HTTP 200 alone SHALL NOT authorize classification/writes.

#### Scenario: An OAuth response stream fails after partial output
- **WHEN** a stream ends early or emits failed/incomplete/error/quota events
- **THEN** partial choices are rejected and no category assignment or monetary write is authorized by that response

### Requirement: Independently qualified remote categorization
Each endpoint/model/acceptance policy SHALL be evaluated on independently labeled held-out Walmart-relevant examples before support is claimed. Report raw and accepted accuracy/coverage, exception recall, default precision, abstention, latency and usage/cost. Default tests SHALL use mock endpoints; live calibration SHALL require explicit permission and synthetic/public inputs, not production purchases. Passing HTTP integration tests SHALL NOT be represented as model-quality qualification.

#### Scenario: An endpoint succeeds but quality is insufficient
- **WHEN** a model returns valid responses but fails the agreed categorization quality criteria
- **THEN** it remains unqualified and uncertain items remain unresolved rather than silently enabling exception-first inference

### Requirement: Reproducible classification provenance
Protected plans SHALL record mapping/model provenance, choices/abstentions, available scores and route/model/prompt/acceptance/shortlist fingerprints. Caches SHALL include plan/product/description/account identity without tokens and these fingerprints, invalidating on change. Only trustworthy YNAB corrections SHALL update mappings, not weights or category lists. Drift after an allocation write SHALL stop application.

#### Scenario: The category list or model changes
- **WHEN** the operator changes a category description, shortlist, endpoint/model, account or acceptance policy
- **THEN** incompatible inferred decisions are not reused and any partially applied order stops for review rather than being silently reclassified

### Requirement: Default-category adjustment accounting
All identified taxes, fees and tips SHALL increase the default-category amount; all separately identified item-specific and order-wide discounts SHALL decrease it. Exception item costs SHALL use proven pre-discount extended amounts without adjustment allocation or double counting. Unknown discount semantics, unexplained differences or a negative default remainder SHALL prohibit category allocation rather than produce artificial balancing lines.

#### Scenario: All adjustments belong to the default
- **WHEN** groceries $80, a household item $20, tax $5, fees $3, tip $2 and discounts $10 reconcile to a $100 order
- **THEN** Household receives $20 and the default receives $80, with no separate tax, fee, tip or discount lines

#### Scenario: An exception item has its own discount
- **WHEN** groceries cost $80 and a household item's proven $20 pre-discount price is shown as net $15 with an explicit $5 discount, totaling $95
- **THEN** Household receives $20 and the default receives $75; the $5 discount is neither lost nor applied twice, and an already-gross $20 source price is not increased to $25

#### Scenario: Discounts would make the default negative
- **WHEN** groceries $10 plus household $50 minus discount $20 reconcile to $40
- **THEN** no category allocation occurs because the default would be negative $10; the discount is not reassigned to Household and no artificial credit line is created

### Requirement: Complete eligible order before allocation
Category allocation SHALL require supported USD purchase outflows, exact reconciled item/adjustment/charge totals and every final charge matched to an initially eligible unapproved, unsplit YNAB transaction. Cancelled/unfulfilled costs, refunds, invalid precision and unsupported accounts SHALL NOT enter allocation. Missing or ineligible payments SHALL leave the order link-only at most, without partial allocation or proportional fallback.

#### Scenario: A payment is missing or already reviewed
- **WHEN** an order has an unmatched gift-card charge, an initially approved transaction or a preexisting user split among its final payments
- **THEN** category allocation for the whole order is skipped and existing categories, splits and approval are preserved

#### Scenario: Charges and known costs disagree
- **WHEN** known item/adjustment costs total $98 but complete charges total $100 without an explanation
- **THEN** no category allocation occurs and a trustworthy charge match remains eligible only for link-only enrichment

### Requirement: Minimal exception-bearing charge selection
Exception-first allocation SHALL choose the fewest transactions whose capacities cover exception costs by descending charge amount, with stable date/charge-key/transaction-ID ties. It SHALL fill those capacities using stable item order, allowing item fragments when needed. Each exception cost and transaction total SHALL be preserved exactly in cents. This SHALL be described as bookkeeping placement, not actual Walmart item-to-charge evidence.

#### Scenario: One charge contains all exceptions
- **WHEN** a $100 order has $20 Household exceptions and $80 default spending, charged as $70 and $30
- **THEN** the $70 transaction contains $20 Household plus $50 default and the $30 transaction is entirely default without subtransactions

#### Scenario: An exception exceeds every individual charge
- **WHEN** one $80 exception and $20 default spending belong to charges of $70 and $30
- **THEN** $70 of the exception categorizes the first transaction without a split, and the second splits into $10 exception plus $20 default without duplicating the item

#### Scenario: Multiple transactions are needed
- **WHEN** a $100 order has a $60 exception and charges of $40, $35 and $25
- **THEN** only the $40 and $35 transactions carry exceptions: $40 and $20 respectively, leaving $15 and $25 default; permuting input order preserves the allocation

### Requirement: Avoid unnecessary splits and default itemization
Transactions SHALL remain unsplit when the plan produces only one line. Transactions with multiple lines SHALL contain exception item fragments and at most one positive default-category remainder. Zero lines SHALL be omitted. Grocery/default items and their adjustments SHALL NOT be separately itemized. An order with no exceptions SHALL receive category-only default assignments.

#### Scenario: All items use the default policy
- **WHEN** an eligible order has no non-default or unresolved exception items
- **THEN** every transaction is categorized directly to the default without subtransactions or product lists in parent memos

#### Scenario: Default remainder is zero
- **WHEN** an exception exactly fills a transaction
- **THEN** no zero default line is emitted and a single exception line produces a category-only write

### Requirement: Unapproved YNAB-only review
Category/split writes SHALL modify existing parents, preserve identity, amount, account, date, payee, import identity, cleared state, flags and user memo, and explicitly retain `approved: false`. The application SHALL NOT unapprove reviewed transactions, overwrite existing splits/human edits or approve automatically. YNAB SHALL be the human review interface. Documentation SHALL state that unapproved changes affect live budget balances and are not drafts.

#### Scenario: The human reviews the proposed allocation
- **WHEN** the program writes exception splits and default-category transactions
- **THEN** all remain unapproved for the human to compare with the linked Walmart order, adjust and approve in YNAB, without a separate application review UI

#### Scenario: Human edits or approves before a write
- **WHEN** a fresh read detects changed categories, memo, splits or approval relative to the saved baseline
- **THEN** the affected automatic write stops rather than replacing or unapproving the human's work

### Requirement: Learn only unambiguous approved item decisions
Later reads SHALL learn mappings only from approved item rows with trustworthy saved provenance, scoped to plan/product. Approved unchanged rows or clear corrections MAY inform future purchases. Aggregate default remainders, uncategorized/ambiguous rows and conflicting decisions SHALL NOT train mappings. Learning SHALL NOT rewrite transactions or model weights; targets SHALL be revalidated against eligibility and the shortlist before reuse.

#### Scenario: Human selects a category outside the shortlist
- **WHEN** a reviewed item is assigned an existing category outside the configured shortlist
- **THEN** the human edit is preserved without automatic list expansion or reuse of that category for future writes

#### Scenario: An approved category correction is observed
- **WHEN** the human changes an identifiable exception item from Household to an existing Pet category and approves the transaction
- **THEN** the program remembers Pet for that product in that plan for future orders without editing the reviewed transaction

#### Scenario: Approval does not establish item provenance
- **WHEN** an approved transaction has merged, deleted or repurposed rows, changed amounts breaking provenance, conflicting fragments, or only an aggregate default remainder
- **THEN** ambiguous product mappings are not learned; row position or aggregate approval does not substitute for item evidence

### Requirement: Safe preview and retry handling
Dry runs SHALL show links, exception placement, categories, default remainders and skip reasons without remote writes or completion changes. Live writes SHALL recheck state, journal link/category/split/review status and verify uncertain outcomes by rereading YNAB. After an allocation write, source/mapping drift or human conflicts SHALL stop further application rather than replan. Journaled prior writes SHALL be distinguished from preexisting user splits.

#### Scenario: Link-only enrichment precedes exception-first activation
- **WHEN** exception-first mode is enabled after link-only enrichment and all order transactions remain eligible
- **THEN** link-only markers do not prevent allocation, and link-only itself has not changed categories or approval

#### Scenario: A response is lost after YNAB accepted a category-only write
- **WHEN** the program cannot determine whether the unsplit default-category write succeeded
- **THEN** it compares current YNAB state with saved intent before retrying and does not repeatedly recategorize it merely because it has no subtransactions

#### Scenario: A response is lost after YNAB accepted a split
- **WHEN** the client cannot determine whether a split update succeeded
- **THEN** it rereads and compares the transaction with the saved plan before recording success or attempting another write, recognizing confirmed journaled writes on restart

#### Scenario: Tip changes after a partial application
- **WHEN** one category/split write succeeded and a later snapshot changes the order's tip, ledger or configured default/mappings
- **THEN** automatic allocation stops for review and existing categories/splits remain untouched

### Requirement: Offline verification and documented limitations
Default verification SHALL exercise matching, exact exception allocation, default adjustments, category validation, approval/learning, links, eligibility, payloads and retry safety without live accounts. Documentation SHALL explain the accounting convention, complete-payment requirement, existing-category-only policy, optional diagnostics, immediate budget effects and YNAB's existing-split limitations. Link-only SHALL remain the default; proportional mode SHALL NOT be retained.

#### Scenario: Operator verifies the change without credentials
- **WHEN** the standard test suite runs
- **THEN** deterministic fixtures cover the enrichment contracts without signing into Walmart or modifying a real YNAB plan
