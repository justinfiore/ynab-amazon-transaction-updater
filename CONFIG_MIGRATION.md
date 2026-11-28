# Configuration migration

Budget Assistant for YNAB uses `schema_version: 2`. The runtime does not read older configuration properties. The offline migrator moves `ynab` to `budget.ynab`, `app` to `runtime`, and the retailer sections to `retailers`. It uses the supplied old working directory to preserve the meaning of relative paths; that directory is not necessarily the directory containing the config.

Stop and drain every writer first. Keep a protected backup of the original config and all state, provider records and journals. Confirm the old working directory and the locations and permissions of all referenced resources. Run `./gradlew installDist` in a source checkout, or use the scripts directory of an installed distribution. The input file and the output's parent directory must be owner-only. The output must not already exist.

```sh
scripts/migrate-config.sh --input /protected/old.yml --output /protected/new.yml --legacy-working-directory /old/working/directory --check
scripts/migrate-config.sh --input /protected/old.yml --output /protected/new.yml --legacy-working-directory /old/working/directory --apply
```

On Windows use `scripts\migrate-config.bat`; permissions must be verifiable by the runtime. Add both `--old-checkout /old/root` and `--new-checkout /new/root` only when moving a checkout. The migrator does not copy files or recreate Python environments: stage and verify each resource separately and recreate relocated virtual environments. A missing configured state file is not permission to create fresh state. Review every missing-resource diagnostic before publishing the new configuration. No network requests are made by migration.

If Walmart was enabled without an explicit `helper_executable`, the old runtime discovered its helper relative to its installed JAR, not the working directory. Supply its actual absolute path with `--legacy-walmart-helper`; the migrator cannot safely infer that location from the old working directory alone. Stage the helper at the remapped location, if applicable.

For an Amazon provider connection the migrator records the original credential-path spelling in `runtime.amazon_fingerprint_credentials_file`. The runtime uses it only to preserve existing Amazon intent fingerprints while opening the relocated credential file through its current path. Do not edit it by hand; Amazon setup and disconnect remove it when the connection changes.

Keep writers stopped while publishing the verified output as `config.yml` in the intended working directory. Preview with `runtime.dry_run: true` under separate approval before restoring the previous write setting and starting one writer. Never run an old binary with v2 config or a new binary with legacy config. To roll back, use the saved legacy config with a qualified old binary and reconcile any financial writes against current state; restoring stale journals can lose ownership information. This guide does not authorize a repository rename, live retrieval or deployment cutover.
