## Context and verified inventory

This is checked-in source evidence, not a live deployment audit. No production config or host was accessed.

| Surface | Observed source | Required treatment |
| --- | --- | --- |
| Product text | README/setup/quick-start, retailer guides, config example, main class log messages, `.kiro/steering/product.md` | Budget Assistant for YNAB; explain current three-retailer categorization scope and planned retailers without implying official endorsement or automatic writes everywhere. |
| Build identity | `build.gradle` uses group `com.ynab.amazon` and main class `com.ynab.amazon.YNABAmazonTransactionUpdater`; no settings file pins root name | Pin project/distribution name `ynab-budget-assistant`; change group/package and main class as specified below. |
| Launchers | Background script hard-codes old install path and sets CWD to its directory; `run.sh` chooses the first executable; Windows/docs refer to an old JAR name | Use deterministic installed launchers, not an unverified executable fat-JAR contract. Update all wrappers atomically with class/package moves. |
| Source responsibilities | Shared YNAB/config/classifier code and all retailers live under `com.ynab.amazon`; `TransactionMatcher` is Amazon-only; `TransactionMatch` supports both Amazon and Walmart; `TransactionProcessor` combines legacy tracking and both enrichment entrypoints | Neutral root namespace; explicit Amazon matcher name; retain truly multi-retailer names. Consolidate empty Walmart compatibility subclasses onto shared implementations. |
| Config consumers | `Configuration` loads CWD `config.yml`; setup commands accept file arguments; `CategoryOnboarding` directly edits YAML for selection/disconnect/payment mappings; `WalmartOnboarding` is an empty subclass | New schema only, with a shared schema gate for every application-config reader/writer. No old-property aliases. |
| Paths/state | Selected paths expand `~`/resolve against CWD; CSV/processed-state paths have separate consumers; journal defaults are `amazon-journal.json` and `walmart-journal.json` | Migration uses the actual old effective CWD and materializes effective resource paths/defaults before optional checkout remapping. Preserve state bytes and pending outcomes. |
| Secrets | YAML supports YNAB/IMAP secrets; provider records live in `.amazon-credentials`/`.walmart-credentials` beside config, with strict ownership/permissions and random connection IDs | Protect source/output config, preserve records/shared references/consent, do not rerun onboarding or print values. Symlinked/noncanonical credential paths may fail validation. |
| Other resources | Commented examples use `~/.config/ynab-updater/...` and upstream `~/.config/amazonorders/config.yml`; background script sources `$HOME/.amazon.conf` | Do not cosmetically rename home roots, upstream configs, retailer source files or environment variables. Map only invalidated path values. |
| Persisted identities | `AmazonAuthNotice` embeds old slug; notice consumers include matcher/processor/enrichment; classifier policies `amazon-choice-v1` and `walmart-choice-v1` have different instructions and fingerprint scopes | Recognize both notices; do not bulk rewrite memos or rename policy/cache/provenance strings. |
| External references | Go module uses old repository URL; Walmart setup wrappers invoke old Java class; README DeepWiki, `.agents/setup` profile markers, logback, `test_setup.groovy`, active `.kiro` structure guidance refer to old identity | Update current references coherently, preserve historical/retired records, avoid duplicate setup profile blocks, verify external destinations. |
| Deployment/CI | CI builds base/native distributions on `master`; no checked-in Nextcloud scheduler/service manifest found | Keep branch name; verify base/native packaging and inventory host details before any external mutation. |

## 1. Required architecture and branding

Branding and architecture are distinct workstreams but **both are required for this proposal**. Implement behavior-preserving source cleanup first, verify it, then change configuration behavior and branding. This does not authorize implementation during proposal drafting.

| Current name | Required target |
| --- | --- |
| `com.ynab.amazon` root package and Gradle group | `com.ynab.budgetassistant`, including existing `config`, `model`, `service` subpackages and corresponding tests |
| `YNABAmazonTransactionUpdater` | `BudgetAssistant`; main class `com.ynab.budgetassistant.BudgetAssistant` |
| `TransactionMatcher` | `AmazonTransactionMatcher`, because its eligibility/matching rules are Amazon-specific |
| `WalmartOnboarding extends CategoryOnboarding` | Remove empty compatibility subclass; route Walmart commands directly through `CategoryOnboarding` with explicit retailer selection |
| `WalmartRemoteClassifier extends RemoteItemClassifier` | Remove empty compatibility subclass; use `RemoteItemClassifier` for Walmart enrichment/comparison with unchanged policy/config |
| `WalmartProviderKeys extends ProviderKeys` | Remove empty compatibility subclass; update callers to `ProviderKeys` |

Update imports, filenames, constructors, reflection tests, Gradle entrypoint, logging references, packaged setup wrappers, `test_setup.groovy` and active structural guidance together. Do not keep old fully qualified class shims. Retain `TransactionProcessor` and `TransactionMatch`: the source confirms both serve Amazon and Walmart, so relabeling them Amazon-only would be incorrect. Retain genuine retailer class names, bridge scripts, requirement filenames and CLI flags. A neutral root fixes the misleading ownership boundary without a plugin framework, universal order model, extra budget backend or unrelated rewrite. YNAB remains the only budgeting system.

Pin the distribution/launcher identity independently of checkout name. Retain the old executable-name alias for at least one transition release; it launches the **new** runtime and therefore rejects old config too. This executable alias is not configuration compatibility. Persisted auth notices remain readable in both brand variants without a time cutoff. Emit the new slug only during normal eligible writes; no bulk memo edits.

## 2. Breaking application configuration schema

New configs use YAML with the following shape (outline, not a complete runnable configuration):

```yaml
schema_version: 2
budget:
  ynab: {}          # api_key, budget_id, base_url and other supported YNAB settings
runtime: {}         # former app settings, including dry_run and processed_transactions_file
retailers:
  amazon: {}        # former Amazon subtree
  walmart: {}       # former Walmart subtree
  sheetz: {}        # former Sheetz subtree
```

The structure reflects one YNAB connection, application-wide runtime settings and independent retailer settings. It does not add interchangeable budgeting backends. `config.yml` remains the default filename and normal startup still resolves it from CWD; this proposal does not invent a general `--config` workflow flag.

| Legacy property path | Version 2 property path |
| --- | --- |
| `ynab.<property>` | `budget.ynab.<property>` |
| `app.<property>` | `runtime.<property>` |
| `amazon.<property>` | `retailers.amazon.<property>` |
| `walmart.<property>` | `retailers.walmart.<property>` |
| `sheetz.<property>` | `retailers.sheetz.<property>` |
| No version | `schema_version: 2` |

Supported leaf properties keep their meanings and spellings; their old full property paths are not accepted. Move nested categorization/source/provider maps intact, preserving lists, numeric/boolean/null values and absent-versus-explicit semantics. In particular, unset Sheetz unmatched default still means explicit category clearing when its guarded writes are enabled. Preserve retailer defaults, account/category IDs, consent, policy IDs, provider connection references and inference bounds. No categorization or paid inference is enabled by migration.

There are **no runtime legacy readers, aliases, fallback lookups, dual-format writers or automatic startup migrations**. `schema_version: 2` is necessary but insufficient: reject legacy root keys even when a version is added, reject mixed old/new structures, and validate required nested types/properties. Unknown/unmapped properties fail validation with key paths only instead of being silently dropped. The migration implementation must enumerate supported legacy properties from the loader and direct YAML consumers, including accepted-but-unused properties; explicitly diagnose obsolete/unknown settings rather than reinterpret them.

## 3. Offline migration script contract

Deliver `scripts/migrate-config.sh` and `scripts/migrate-config.bat` in source and both packaged distributions. They invoke `com.ynab.budgetassistant.config.ConfigMigration` using the packaged runtime/dependencies and existing SnakeYAML, not a separately installed YAML tool. Source-checkout invocation may require `installDist`; script usage and startup errors must explain the resolved location/prerequisite. This is the only component that reads legacy application properties; the normal loader contains no legacy fallback.

Required interface:

```text
scripts/migrate-config.sh --input OLD_CONFIG --output NEW_CONFIG --legacy-working-directory OLD_CWD --check
scripts/migrate-config.sh --input OLD_CONFIG --output NEW_CONFIG --legacy-working-directory OLD_CWD --apply
```

`--check` validates/converts in memory and reports only key/path diagnostics, without printing config or writing files. `--apply` must be explicit and writes a separate output; never overwrite input or an existing output, even if the paths alias through symlinks. Reject unsafe/nonregular paths and YAML duplicate keys before conversion. Require owner-only input/output storage; create output atomically with mode 0600 and protected parent directory, failing safely where equivalent permissions cannot be verified. Failure leaves source and any existing output unchanged, without a partial config or leaked temporary file.

The old working directory is mandatory because implicit state/default paths and different path consumers cannot safely be reconstructed from the config's parent. Resolve actual old paths using each legacy consumer's semantics, not a blanket `~` expansion. Materialize old effective defaults needed to preserve behavior (including `dry_run: false` when omitted by the old loader, journal paths, and source-dependent defaults), without turning omitted unsafe settings into new permissions. Distinguish an absent processed-state setting from a configured-but-missing state file. Do not alter credential-record contents, connection IDs, cache scopes, state schemas or state bytes.

For a checkout move, support explicit paired `--old-checkout` and `--new-checkout` roots. Remap only path-valued settings resolved beneath the old checkout, on path-component boundaries; never replace arbitrary text or unrelated home paths. A path already external remains external. Preserve shared provider-file references as one identity. Report invalidated Python environments for recreation; the script does not install dependencies, copy journals, fetch sessions or relocate secrets. Referenced resources are staged separately while writers are stopped, then verified before publishing the new config.

Reject malformed, mixed, unknown-property or unsupported-version input without output. An already-version-2 input produces a clear “already migrated” result and no rewrite; no downgrade or reverse converter is supplied. Parsing/dumping may normalize comments/layout but not values. The script makes no YNAB, retailer or inference requests and never enables a schedule or application run. Back up the secret-bearing source securely before eventual execution; keep it for rollback instead of reconstructing it from v2.

## 4. Startup and operator-command rejection

Put schema detection before config value binding, service construction, network/paid calls, prompts, credential deletion, setup-state locking/writing or journal initialization. Apply it to normal startup, setup/category/payment-account/disconnect commands and direct application-config edit entrypoints. All writers emit version 2 exclusively. The separate synthetic provider-comparison YAML is not an application config and keeps its explicit separate contract; it must not become a bypass for loading legacy production config.

For an unversioned legacy config or a v2 file containing legacy roots, return nonzero with an actionable error such as:

```text
Configuration migration required: <config-path> uses legacy application properties.
Run <distribution>/scripts/migrate-config.sh --input <config-path> --output <new-config-path> --legacy-working-directory <old-working-directory> --check
Then rerun with --apply, stage/verify referenced resources, and replace config.yml while all writers are stopped. See the configuration migration guide; use migrate-config.bat on Windows.
```

Resolve the script's real installed path when possible; shell-quote paths correctly and show no secret values. Missing files, invalid YAML, missing/wrong-type version, mixed properties and unsupported future versions also fail closed before side effects. Future-version errors say “unsupported schema version” and do not imply the legacy migrator can downgrade them. A missing version never means “use defaults”, and manually adding `schema_version: 2` to an otherwise old file does not pass the gate.

## 5. Ordered future migration

1. **Inventory/baseline.** Establish deployed revision, old effective CWD, host model, scheduler/user/environment, all writers, source config/state locations, permissions and backup restoration. Record legacy preview with explicit `app.dry_run: true` only under separate authorization for any live retrieval. Check Nextcloud sync does not expose secrets or run multiple copies.
2. **Implement/qualify locally.** Complete required source cleanup, new schema/gate, all config writers, migration scripts and branding. Test a legacy fixture through script conversion and new-runtime preview with no network. Qualify the old-release rollback artifact against both notice variants and retain its matching old-format config. No runtime accepts both schemas.
3. **Approve/rename GitHub.** Check availability, rename the existing repository under the same owner, retain history/default branch, align Go module/canonical links/remotes and verify redirects, Actions, protections, releases, integrations, Amp association and DeepWiki. Keep `master`; do not occupy the old slug.
4. **Approve/stage host.** Pause and drain every writer. Take final protected config/state snapshots. Stage release under basename `ynab-budget-assistant`, recreate path-sensitive venvs and stage unchanged state/credentials. Run migration `--check` then explicitly `--apply` into a distinct protected file, with old CWD and explicit checkout roots. Validate it before atomically publishing `config.yml`. Update external executable/CWD paths. Never run an old binary with v2 config or a new binary with legacy config.
5. **Preview gate.** Temporarily set `runtime.dry_run: true` in staged v2 config, retaining the exact migrated write setting for restoration. Launch from actual intended CWD; verify complete state/resources and compare proposals/settings to baseline apart from branding. Missing state, permissions or changed proposals block cutover. Live read-only retrieval requires separate authorization.
6. **Resume/observe.** Restore only prior approved write settings, enable one schedule, verify exit/log rotation and a scheduled run. Keep stopped old checkout, old-format config, qualified rollback artifact and snapshots through an agreed observation period; cleanup needs separate approval.

## 6. Validation and rollback

Future acceptance includes full `.agents/scripts/run-tests.sh`, Python bridge contracts, clean Gradle base and `-Pwalmart` distribution verification, and packaged migration/launch scripts from an arbitrary checkout basename. Update reflection tests/imports to new names; verify unchanged retailer outputs before/after refactor. Exercise Windows structure and permissions and report native Windows execution limitations honestly.

Regression matrix: legacy config rejected before calls/prompts/writes for every app-config command; fake v2/mixed roots rejected; valid v2 accepted; future versions/malformed YAML/duplicate keys fail; migrated nondefault values, null/absent settings, source-dependent defaults and old omitted dry-run semantics preserved; check-only leaves no files; apply cannot overwrite source/output; unknown keys do not disappear; secrets never enter diagnostics; rerun of v2 is nonmutating; absolute/relative/home paths and checkout-boundary remaps work; processed IDs, provider identity/shared references, journals/pending outcomes and fingerprints remain unchanged. Script output must be accepted by the same schema validator as runtime/setup writers. Notice tests retain old/new/both variants, rerun idempotency and memo limits.

Before writes, restore quiesced state plus the old binary and its **saved legacy config** together; never feed v2 config to it or teach the new runtime to read legacy properties for rollback. After writes, preserve/reconcile current journals and processed state against current YNAB state; stale snapshot restoration can lose ownership. Use a qualified rollback build that understands both notice variants with its own matching schema. Reverting code/config does not reverse financial writes and does not authorize bulk transaction undo. Keep all schedules stopped during recovery, then resume only one qualified writer.

Host rollback can leave the GitHub rename in place and update the old checkout's remote. Renaming GitHub back, deleting backups or removing a stopped checkout requires separate approval.

## Deployment unknowns and execution gates

- Actual Nextcloud host/account/parent path/current basename and whether it is synced storage, a mounted share, bind mount or ordinary checkout.
- Scheduler/service, execution user, old/new CWD, environment, concurrency/locks, timezone/frequency, logs and other machines/callers. Inspect actual systemd units/drop-ins/timers only if that is the verified execution model.
- Config/state/session/secret locations, filesystem permission semantics, Nextcloud exclusions/version retention, backup destination and tested restoration. Keep secrets/state outside unapproved sync.
- Deployed revision, OS/architecture, Java/Python/native helper availability, reproducible build/install method. Docker examples are not deployment evidence.
- GitHub availability/permissions/integrations and external class/module callers; acceptable downtime, observation period and preview/rollback owner.
- Future OAuth eligibility and any DNS name remain separate reviews. Display/DNS naming requirements do not establish approval of the technical slug or prohibition of this personal-token deployment.

These unknowns block external execution, not proposal review. Architectural cleanup and breaking property migration are decided requirements, not open scope questions.
