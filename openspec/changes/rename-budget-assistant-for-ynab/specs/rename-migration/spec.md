## ADDED Requirements

### Requirement: Versioned configuration without legacy compatibility

The runtime SHALL accept only `schema_version: 2` application config with `budget.ynab`, `runtime` and `retailers.{amazon,walmart,sheetz}`. Legacy `ynab`, `app`, `amazon`, `walmart` and `sheetz` roots SHALL NOT be accepted, even with a v2 version tag. No aliases, legacy fallback parsing, automatic migration or dual-format writes SHALL exist in runtime/setup paths. `config.yml` and normal CWD loading SHALL remain unchanged.

#### Scenario: A user adds only a version tag

- **WHEN** a config has `schema_version: 2` but retains legacy root properties or mixes old and new structures
- **THEN** it is rejected rather than silently ignoring settings or treating them as defaults

### Requirement: Early rejection with migration instructions

Every application-config entrypoint SHALL reject unmigrated configs with a nonzero exit before service construction, prompts, network/paid calls, journal creation or config/credential writes. The error SHALL identify the config path and point to `scripts/migrate-config.sh` or `.bat` with actionable usage, without secrets. Missing/invalid/mixed/unsupported configs SHALL fail closed; future-version errors SHALL NOT suggest a downgrade migration.

#### Scenario: Startup receives an unversioned config

- **WHEN** the application is launched with the legacy application config
- **THEN** it exits nonzero with “Configuration migration required”, the config path and script instructions before any account, retailer or inference work

#### Scenario: An operator command receives legacy properties

- **WHEN** setup, category selection, payment-account selection or disconnect reads legacy app config
- **THEN** it fails with the same migration guidance before prompting, creating setup state or deleting provider credentials

### Requirement: Explicit offline property migration

The release SHALL provide source/packaged `scripts/migrate-config.sh` and `.bat` using existing YAML dependencies. They SHALL map old roots to the version-2 paths specified in design.md, preserve effective values/defaults and require input, separate output and old working directory. `--check` SHALL be nonmutating; explicit `--apply` SHALL create protected atomic output without overwriting source/existing output. Migration SHALL make no network calls or state mutations.

#### Scenario: Nondefault settings and omitted safety settings are migrated

- **WHEN** a valid legacy file contains distinct retailer settings, shared provider references, explicit nulls and omitted old defaults
- **THEN** output passes the runtime schema, preserves their effective meanings including old omitted dry-run behavior, and does not enable new inference or financial permissions

#### Scenario: Conversion cannot safely preserve the input

- **WHEN** input has unknown/unmapped keys, duplicate YAML keys, mixed schema, unsupported version or unsafe output permissions
- **THEN** migration fails with key/path-only diagnostics and leaves source and existing output unchanged with no partial config

#### Scenario: A conversion is checked or repeated

- **WHEN** the operator uses `--check` or supplies an already-valid v2 config
- **THEN** no config/state file is changed, and valid v2 input reports that it is already migrated

### Requirement: Path and protected state continuity

Migration SHALL resolve effective paths using the old working directory and each legacy consumer's rules. Explicit paired checkout roots MAY remap only paths beneath the old root on component boundaries. Migration SHALL preserve processed IDs, journals/recovery/pending state, provider records/connection IDs, shared references, sessions, consent and cache identities. It SHALL NOT create replacement state, relocate unrelated home roots or expose secret values.

#### Scenario: The checkout moves but a home-directory session path does not

- **WHEN** config references an unchanged `~/.config/ynab-updater/` session and an absolute journal path beneath the old checkout
- **THEN** only the invalidated checkout-dependent path is remapped, both resources are verified under the execution user, and non-path settings remain identical

#### Scenario: Protected state is missing or unsafe after staging

- **WHEN** processed state, a journal or required credentials cannot be verified at the staged path with required ownership and permissions
- **THEN** cutover is blocked rather than treating existing transactions as unprocessed or weakening credential validation

### Requirement: Inventory and approval before external changes

GitHub and Nextcloud changes SHALL require separate approval and a tested compatible release. Before host execution, inventory SHALL establish host/path, user, scheduler/launch/CWD, state/secrets, sync and rollback. GitHub migration SHALL retain owner, history and default branch, align repository metadata including the Go module path, and verify canonical access and integrations rather than relying on redirects.

#### Scenario: Nextcloud's execution model is unknown

- **WHEN** no actual host path or scheduler definition has been verified
- **THEN** the proposal may be accepted but deployment execution remains blocked and no guessed cron, service, container or Nextcloud command is run

#### Scenario: GitHub is renamed before the host checkout

- **WHEN** the authorized remote rename completes but host cutover has not occurred
- **THEN** the existing stopped-or-running deployment can retain its old local basename, canonical remote access is verified, and changing its checkout remains a separate controlled operation

### Requirement: Quiesced single-writer cutover and preview gate

Cutover SHALL drain all writers and snapshot protected legacy config/state. Explicit script conversion SHALL produce a validated separate v2 file before publishing config. The new launch/CWD SHALL pass an explicit `runtime.dry_run: true` preview comparing state, proposals and settings with baseline. Only prior approved write settings SHALL be restored and one schedule resumed. Invalidated virtual environments SHALL be recreated; symlinks SHALL NOT be assumed safe.

#### Scenario: A staged preview differs beyond approved branding

- **WHEN** the preview changes retailer enablement, categories, proposals, state identity or write permissions beyond the approved rename
- **THEN** the schedule stays disabled until the difference is explained and corrected

#### Scenario: The old checkout could still run

- **WHEN** the new path is ready for scheduled operation
- **THEN** the operator verifies the old writer is disabled and no synced or alternate launcher can write concurrently before enabling the new schedule

### Requirement: Rollback preserves financial and ownership state

Rollback SHALL pair the old binary with its saved legacy config, not v2 config or dual-schema parsing. Its notice handling SHALL be qualified before new-branded writes. Before writes, rollback MAY restore the quiesced snapshot. After writes, rollback SHALL reconcile current journals/state against YNAB, not restore stale ownership or imply financial reversal. GitHub reversal and backup/checkout deletion SHALL need separate approval.

#### Scenario: Host verification fails before any writes

- **WHEN** the staged launch fails a preview or path/permission check
- **THEN** writers remain paused while the prior binary, saved legacy config, launch/CWD/path mapping and verified state are restored and only one qualified invocation may resume

#### Scenario: Rollback is requested after a new-branded write

- **WHEN** the new release has already updated YNAB or persisted journal changes
- **THEN** the operator uses the compatible rollback artifact with verified current state and does not blindly restore the old snapshot, rerun both versions or bulk revert transactions
