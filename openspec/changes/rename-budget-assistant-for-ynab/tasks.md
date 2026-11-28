## 1. Required behavior-preserving architecture cleanup

- [x] 1.1 Move main/test packages and Gradle group to `com.ynab.budgetassistant`; rename the entrypoint `BudgetAssistant` and the Amazon-only matcher `AmazonTransactionMatcher`. Retain genuinely multi-retailer `TransactionProcessor`/`TransactionMatch` names and retailer-specific models/services.
- [x] 1.2 Remove empty `WalmartOnboarding`, `WalmartRemoteClassifier` and `WalmartProviderKeys` compatibility subclasses; update callers to shared implementations with explicit retailer/policy selection and unchanged behavior.
- [x] 1.3 Align imports, filenames/constructors, reflection tests, logback, Gradle main class, packaged wrappers, `test_setup.groovy` and active structural guidance. Do not retain old fully qualified class shims or introduce a plugin framework/budgeting backend abstraction.
- [x] 1.4 Verify existing retailer behavior unchanged before implementing the breaking config contract.

## 2. Breaking configuration and explicit migration

- [x] 2.1 Define/validate `schema_version: 2`, `budget.ynab`, `runtime` and `retailers.{amazon,walmart,sheetz}`. Enumerate supported legacy properties and mapping/default semantics from all consumers. Reject legacy roots, mixed schemas, unknown properties and unsupported versions; no aliases or auto-migration.
- [x] 2.2 Add the schema gate before side effects in startup and all application-config commands/readers/writers. Unmigrated configs exit nonzero with config path and actionable `scripts/migrate-config.sh`/`.bat` instructions. Update config examples and the operator migration guide.
- [x] 2.3 Provide source/packaged migration scripts backed by `config.ConfigMigration` and existing YAML dependencies, with `--input`, `--output`, `--legacy-working-directory`, `--check` and explicit `--apply`. Keep legacy parsing isolated to the migrator.
- [x] 2.4 Implement protected atomic separate output, no overwrite, duplicate/unknown/mixed-schema rejection, already-migrated no-op, redacted diagnostics and no network/state mutations. Preserve effective defaults, null/absent semantics, consent, policies, account/category mappings and shared provider connections.
- [x] 2.5 Implement path-aware optional paired checkout-root remapping, using old effective CWD and per-consumer resolution. Preserve unchanged external paths and state bytes; report venv recreation and missing resources without creating replacement state.
- [x] 2.6 Verify conversion and runtime gate through synthetic configs covering default/nondefault/absent/null settings, misleading version tags, nested/mixed properties, malformed/duplicate YAML, unknown/future versions, permissions, overwrite attempts, repeated migration and no secret disclosure. Assert rejection precedes calls/prompts/journal or credential writes.

## 3. Required branding and release qualification

- [ ] 3.1 Update active text to Budget Assistant for YNAB and document current Amazon/Walmart/Sheetz scope and planned Costco/Sam's Club. Pin `ynab-budget-assistant` distribution/launcher identity independent of checkout basename; align active links/metadata and setup profile markers without rewriting history.
- [x] 3.2 Update shell/Windows packaging instructions and provide the deprecated old executable alias for at least one transition release. Both executable names run the new schema-only runtime; neither accepts legacy config.
- [x] 3.3 Emit new auth notice and recognize both old/new strings in all detection/removal/matching paths, preserving user text, memo limits and write guards without bulk migration.
- [ ] 3.4 Run full unit/integration/Python bridge contracts and clean base/`-Pwalmart` packaging checks, including installed migration scripts, arguments/exit status, arbitrary checkout names, notice reruns and unchanged proposals after property migration. Report Windows execution limitations.
  - Full suites, both packages, launcher checks and the Amazon migrated dry-run and pending-intent replay passed in a loopback-only network namespace. Comparative migrated Walmart and Sheetz proposals remain unverified; Windows execution remains best effort.
- [ ] 3.5 Qualify rollback artifact notice handling and saved legacy config; verify state/connection/fingerprint continuity and restoration. Do not use dual-schema runtime parsing as a rollback strategy.

## 4. External migration (separately approved; not executed by proposal work)

- [ ] 4.1 Resolve actual Nextcloud host/path/model, old effective CWD, user/schedule/environment, writers/locks, sync protection, state/secrets, permissions, backup restore, downtime and observation/rollback owner.
- [ ] 4.2 Obtain approval for GitHub rename; confirm target availability, rename existing repository, align Go module/canonical references/remotes, verify CI/integrations/redirects/Amp/DeepWiki. Retain owner/history/`master`; do not occupy old slug.
- [ ] 4.3 Obtain separate host-cutover approval; pause/drain all writers, snapshot legacy config/current state, stage tested release under `ynab-budget-assistant`, recreate venvs and stage unchanged protected resources.
- [ ] 4.4 Run migration check/apply into distinct protected v2 output with verified CWD/root mapping; validate before publishing config and external launch paths. Preview using explicit `runtime.dry_run: true`; compare effective settings/proposals/state before enabling writes.
- [ ] 4.5 Restore exact prior approved write settings and resume one schedule. Observe a scheduled run and retain saved old-format config/stopped checkout/rollback artifact. After writes, reconcile current state rather than restoring stale journals; cleanup requires separate approval.

## Proposal boundary

Checked boxes record completed work; unchecked boxes include outstanding qualification and separately approved external execution. This change authorizes no live migration.
