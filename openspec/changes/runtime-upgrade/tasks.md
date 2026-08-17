## 1. Baseline and Build Migration

- [x] 1.1 Run the pre-upgrade Java 11 full suite and record discovery/results (214 unit, 51 integration, 0 failures/errors, 16 skipped)
- [x] 1.2 Regenerate the complete wrapper at Gradle 9.6.1
- [x] 1.3 Configure the Java 25 toolchain and update Gradle DSL for Gradle 9
- [x] 1.4 Replace Groovy 3 with Groovy 5.1.0 modules and align the latest stable Spock/JUnit Platform
- [x] 1.5 Upgrade and pin all direct runtime/test dependencies, migrate HttpClient 4 and JavaMail, and remove obsolete test support libraries

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
- [x] 4.2 Confirm resolved Groovy 5.1.0, Spock Groovy-5, JUnit Platform 6, and upgraded core dependency versions with dependency insight
- [x] 4.3 Run a clean full unit/integration suite and verify report counts, failures, errors, and skips
- [x] 4.4 Run `build` and `installDist` and verify generated archives/launchers
- [x] 4.5 Run OpenSpec validation, inspect the final diff/status, and confirm no dynamic dependency selectors or unrelated files

## 5. Continuous Integration

- [x] 5.1 Add a Java 25 GitHub Actions workflow that runs the complete unit/integration suite on pushes and pull requests targeting `master`
- [x] 5.2 Publish JUnit totals and failing test names in the check summary while retaining XML, HTML, and Gradle-log artifacts
- [x] 5.3 Locally validate the summary parser against passing reports and a synthetic failing report before pushing
