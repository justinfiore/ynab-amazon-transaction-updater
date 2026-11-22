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
Exception-first mode SHALL require a valid Default Walmart Category ID and an explicit nonempty shortlist of existing eligible category IDs in that YNAB plan, including the default. The application SHALL reject duplicates/invalid targets, restrict all automatic decisions and mapping reuse to the shortlist, and never create, rename or suggest categories. Classification SHALL remain local.

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

### Requirement: Qualified Laya classic classification
The integration SHALL provide only Laya classic (322M/421M) CPU classification initially. Kev SHALL be deferred to a future proposal, not installed, selectable or used as a fallback. Support SHALL require reviewed loading, locked dependencies, licensing, CPU correctness, measured memory/latency and held-out Walmart quality tests per checkpoint. Unqualified profiles SHALL fail closed without model/GPU/cloud substitution. Hosted providers and fine-tuning SHALL be outside initial scope.

#### Scenario: A selected provider exceeds the resource budget
- **WHEN** local inference exceeds configured positive memory, time or CPU-thread bounds
- **THEN** it stops safely and retains unresolved items for review or link-only handling without loading another model or accepting a partial malformed result

### Requirement: Guided model provisioning
Onboarding SHALL check Docker prerequisites, offer numbered qualified Laya profiles and confirm pinned image/model downloads with hash verification and offline container readiness checks. Token input SHALL be hidden and used only for provisioning, without persistence, command arguments, logging, unrelated-host forwarding or global Hub login. Provisioning SHALL omit purchase/budget data and remain separate from inference.

#### Scenario: Model download succeeds or fails
- **WHEN** the operator confirms provisioning with a token able to read required repositories
- **THEN** the complete pinned artifacts are verified before becoming ready; interrupted, denied or corrupt downloads leave configuration unchanged and give non-secret retry/cancel guidance without requesting write/admin access

#### Scenario: Tokenizer compatibility needs normalization
- **WHEN** a reviewed Laya tokenizer requires a compatibility rewrite
- **THEN** setup verifies both original and transformed artifact hashes before readiness, without inference-time model writes

### Requirement: Numbered existing-category onboarding
Onboarding SHALL read eligible categories from the configured YNAB plan, display numbered group/name choices and accept comma-separated numbers for the shortlist. It SHALL resolve choices to the displayed IDs, reject invalid/empty/duplicate selections and ask for the default from the selected list. No manual ID copying, category creation or live transaction writes SHALL be required or performed.

#### Scenario: Category names are duplicated
- **WHEN** two eligible categories have the same name in different groups and the operator selects their displayed numbers
- **THEN** the group labels distinguish them and the configuration uses the corresponding stable IDs, not names or a newly fetched list's positions

### Requirement: Platform-qualified onboarding and documentation
Initial host targets SHALL be Linux x86_64, macOS arm64 and Windows x86_64 using native onboarding and qualified CPU-only Linux containers. A documented host/engine/image/checkpoint matrix SHALL distinguish intended from verified support. Onboarding SHALL check prerequisites before token entry/download, without automatic Docker installation/reconfiguration, silent emulation, dependency downgrades or GPU/cloud substitution.

#### Scenario: Windows uses Docker Desktop
- **WHEN** onboarding runs natively on Windows
- **THEN** it explains Docker Desktop's possible WSL2/Hyper-V backend prerequisite without requiring application/onboarding commands in WSL

#### Scenario: A Laya profile qualifies on the detected platform
- **WHEN** onboarding detects a host/engine/image target with a qualified pinned Laya profile
- **THEN** only qualified fitting Laya profiles are selectable, without offering Kev or treating helper binary availability as proof of model support

#### Scenario: Intel Mac or another target has no qualified models
- **WHEN** the detected platform has no qualified local provider, including initially unsupported Intel Macs
- **THEN** onboarding explains the platform/dependency limitation, leaves config unchanged, requests no token/download and describes link-only operation without model provisioning

### Requirement: Confirmed safe onboarding configuration
Onboarding SHALL preview and confirm model/shortlist/default settings, revalidate categories, and atomically save protected configuration preserving unrelated settings and detecting concurrent edits. Cancellation/failure SHALL preserve the original config. Reruns SHALL preselect valid saved choices. Setup SHALL NOT automatically enable fetching or allocation, change existing enablement/mode, or weaken disabled/link-only initial defaults.

#### Scenario: Selection is cancelled or becomes stale
- **WHEN** the operator cancels, a selected category becomes ineligible or another process edits the config before save
- **THEN** the original config is not overwritten, no partial settings are applied, and selection/retry guidance is given without silently replacing categories

### Requirement: Resource-aware model recommendations
Onboarding SHALL ask for model RAM before selection: default 1 GB, explicitly adjustable up to 2 GB, with decimal-byte units explained. It SHALL recommend only platform/quality-qualified profiles whose measured worker-tree loading/inference peaks plus documented headroom fit the budget, prioritizing lower memory then measured latency. It SHALL show download/disk size separately, require confirmation and persist/enforce `categorization.memory_limit_bytes`.

#### Scenario: No model fits the preferred budget
- **WHEN** no qualified profile fits 1 GB but one fits within 2 GB
- **THEN** onboarding explains the measured requirement and offers an explicit budget increase or cancellation/link-only handling without silently raising the limit or downloading an over-budget model

#### Scenario: No fitting model or actual memory overrun
- **WHEN** measurements are missing, no qualified profile fits within 2 GB or a worker exceeds its confirmed memory ceiling
- **THEN** inference is not treated as supported/successful, no model/cloud substitution occurs and safe unresolved/link-only handling applies; documentation explains that Docker engine/VM and JVM/helper/OS require RAM beyond the model budget

### Requirement: Bounded choices and explicit abstention
Providers SHALL score the entire shortlist plus an internal abstain choice using names and optional boundary descriptions. Results SHALL use stable choice IDs, validated membership/completeness/finite scores and qualified acceptance/margin thresholds. Token/choice limits SHALL be checked without silent omission/truncation. Uncertainty/failure SHALL NOT become default spending. Models SHALL NOT choose amounts or perform writes.

#### Scenario: Similar product names cross category boundaries
- **WHEN** representative fixtures include human food, pet food, household supplies and ambiguous product names
- **THEN** each provider is evaluated against independently labeled expected categories and abstentions, including exception recall and default precision, before thresholds are accepted

#### Scenario: The model returns an out-of-list choice or invalid score
- **WHEN** output names an unknown category, has non-finite scores or is incomplete for the request
- **THEN** the application rejects that result without creating categories, accepting partial malformed batch output or defaulting unresolved products to Groceries

#### Scenario: All configured choices do not fit
- **WHEN** product text and category descriptions exceed the selected checkpoint's token or choice budget
- **THEN** classification abstains/reports the limit rather than dropping allowed categories or silently truncating potentially decisive input

### Requirement: Private offline inference and reproducible artifacts
Laya SHALL use a digest-pinned image, a locked runtime separate from Amazon and hash-verified immutable checkpoint/encoder/tokenizer artifacts. Loading SHALL use safetensors and explicit classic CPU/eager execution, not Vega/router dispatch. Review SHALL cover licenses/advisories and the full dependency closure including installed VegaML. Inference SHALL NOT request networks, pull images, download files, load remote code, use unsafe pickle fallback or emit telemetry.

#### Scenario: Offline inference sees only classification inputs
- **WHEN** a provisioned provider classifies fixture products with external networking blocked and unrelated credentials present in the parent
- **THEN** it succeeds locally without network attempts or leaked credentials, and its input/output/logs omit order/account secrets and raw purchase logs

#### Scenario: A checkpoint is missing or modified
- **WHEN** the image or a Laya classic checkpoint, encoder configuration or tokenizer artifact is absent or fails verification
- **THEN** loading fails without image pull, Hub download, Vega/router dispatch, remote code or unsafe deserialization fallback

### Requirement: Isolated minimal model inputs
The worker SHALL receive only bounded necessary product attributes and shortlisted category labels/descriptions, not sessions, YNAB credentials, addresses, order links or payments. Execution SHALL enforce network/filesystem isolation, an environment allowlist, bounded local IPC and protected caches/journals without raw purchase logging or LAN/public service exposure. Offline flags SHALL NOT be represented as a sandbox.

#### Scenario: Parent credentials are available on the host
- **WHEN** the JVM starts classification while holding Walmart/YNAB credentials and protected journals
- **THEN** only the minimal classification payload reaches the worker and host secrets/journals are neither mounted nor inherited

### Requirement: Network-disabled Docker worker and streamed IPC
The JVM SHALL directly launch one non-root Laya container per batch with structured arguments and image pull disabled. It SHALL disable networking, ports and persistent logs, enforce read-only root/model filesystems, drop capabilities and apply no-new-privileges plus memory/swap/CPU/thread/PID/temp limits. Only the verified model directory SHALL be mounted, never host home, secrets, journals or Docker socket.

#### Scenario: Docker launch is inspected
- **WHEN** a fixture batch starts
- **THEN** its image digest, isolation flags, limits and sole read-only model mount are verified, with no host/application/provisioning credentials exposed

### Requirement: Bounded batch worker protocol and cleanup
Versioned bounded newline-delimited JSON with correlation IDs SHALL use stdin/stdout, not HTTP, shell interpolation or per-item exec. Startup/request/batch deadlines, result validation and bounded redacted diagnostics SHALL apply. Laya SHALL load once per batch. Completion/failure/cancellation SHALL stop and remove the identified container, including interruption recovery. Disabled/link-only operation SHALL NOT require or probe Docker or download images/models.

#### Scenario: A classification batch completes or fails
- **WHEN** several uncached items are classified, or a worker times out, exceeds memory or emits malformed/partial results
- **THEN** a single isolated batch worker handles valid requests without reloading per item, is removed after completion/failure, and invalid/incomplete results do not authorize writes

#### Scenario: Docker is unavailable during link-only use
- **WHEN** Walmart is disabled or operates link-only without model classification
- **THEN** no Docker probe/start, image/model download or model credential request occurs

#### Scenario: Hostile product text is classified
- **WHEN** product text contains instructions requesting credentials, network access or category creation
- **THEN** it remains untrusted classification input without access to those capabilities, and results remain subject to application-side shortlist and score validation

### Requirement: Reproducible classification provenance
The protected plan SHALL record mapping/model provenance, selected/abstained category, scores and model/artifact/threshold/shortlist fingerprints. Inferred caches SHALL be scoped to plan/product/relevant description and those fingerprints, invalidating on change. Corrections SHALL update mappings only through trustworthy YNAB provenance, not retrain weights or expand the shortlist. Model/shortlist drift after an allocation write SHALL stop further application.

#### Scenario: The category list or model changes
- **WHEN** the operator changes a category description, shortlist, checkpoint or threshold
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
