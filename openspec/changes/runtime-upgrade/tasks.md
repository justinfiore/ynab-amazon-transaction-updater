## 1. Baseline and Build Migration

- [x] 1.1 Run the pre-upgrade Java 11 full suite and record discovery/results (214 unit, 51 integration, 0 failures/errors, 16 skipped)
- [x] 1.2 Regenerate the complete wrapper at Gradle 9.6.1
- [x] 1.3 Configure the Java 25 toolchain and update Gradle DSL for Gradle 9
- [x] 1.4 Replace Groovy 3 with Groovy 5.0.6 modules and align Spock/JUnit Platform
- [x] 1.5 Upgrade and pin direct dependencies needed for Java 25/Groovy 5 compatibility

## 2. Test and Helper Modernization

- [x] 2.1 Wire real unit, integration, and composed full-suite tasks with deterministic fail-fast ordering
- [x] 2.2 Enable separate JUnit XML and HTML reports for each suite
- [x] 2.3 Update `.agents/scripts/init-tools.sh` and helper scripts for Java 25 and current Gradle tasks
- [x] 2.4 Verify with a temporary deliberate unit failure that integration tests do not run, then revert the probe

## 3. Documentation

- [x] 3.1 Update `README.md`, `QUICKSTART.md`, and `SETUP.md` to require Java 25 and describe current wrapper/build/test commands
- [x] 3.2 Correct `.agents/rules/build-and-test.md` to reference `.agents/scripts` and the generated report entry points

## 4. Verification

- [x] 4.1 Confirm `./gradlew --version` reports Gradle 9.6.1 on Java 25
- [x] 4.2 Confirm resolved Groovy 5.0.6 and Spock Groovy-5 variants with dependency insight
- [x] 4.3 Run a clean full unit/integration suite and verify report counts, failures, errors, and skips
- [x] 4.4 Run `build` and `installDist` and verify generated archives/launchers
- [x] 4.5 Run OpenSpec validation, inspect the final diff/status, and confirm no dynamic dependency selectors or unrelated files
