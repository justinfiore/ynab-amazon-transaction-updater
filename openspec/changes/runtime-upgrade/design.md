## Context

The repository is a single-project Groovy application built with Gradle 7.6. It compiles for Java 11, depends on the legacy `org.codehaus.groovy:groovy-all:3.0.19` aggregate, and tests with Spock 2.3 for Groovy 3. The default `test` task is disabled but depends on custom `unitTest` and `integrationTest` tasks. Repository helper scripts also hard-code a Java 11 guard, and the agent rule still names obsolete `.windsurf/scripts` paths even though the scripts now live under `.agents/scripts`.

A Java 11 baseline run completed before migration: 214 unit tests and 51 integration tests were discovered, with zero failures/errors and 16 intentionally skipped tests. Java 25 is already installed on the host. The final target is Java 25, Groovy 5.1.0, Gradle 9, and latest-stable pinned direct dependencies.

## Goals / Non-Goals

**Goals:**

- Build and run the project on Java 25 using a Gradle 9 wrapper and Java toolchain declaration.
- Use Groovy 5.1.0 with the latest stable matching Spock variant and explicit JUnit Platform launcher.
- Upgrade direct runtime and test dependencies to latest stable releases, including successor artifact families where the old coordinates are frozen.
- Preserve the existing unit/integration split while making the full verification task deterministic, report-producing, and failure-safe.
- Keep repository scripts and documentation aligned with the actual Java and command requirements.
- Prove the migrated source, tests, application distribution, and wrapper are functional.

**Non-Goals:**

- Change Amazon, Walmart, YNAB, matching, or persistence behavior.
- Replace Apache HttpClient, JavaMail's `javax` API, or Playwright unless compatibility testing requires it.
- Enable credential-dependent live email/browser/API tests that are intentionally skipped.
- Introduce new application features or configuration formats.

## Decisions

### Pin Gradle 9.6.1 and regenerate the complete wrapper

Use Gradle 9.6.1, matching the provided reference commit, and regenerate `gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, and wrapper properties together. Merely editing the distribution URL would leave stale wrapper components.

Alternative considered: use the minimum Gradle 9 release. Rejected because the reference provides a known repository-local precedent and newer Gradle 9 maintenance releases include Java 25 fixes.

### Use Java toolchains with Java 25 as the only supported baseline

Declare `JavaLanguageVersion.of(25)` rather than only source/target compatibility. Repository helpers will validate Java 25 instead of attempting to source a host-specific `/opt/devin-scripts` file.

Alternative considered: compile for Java 11 while running Gradle on Java 25. Rejected because the request explicitly raises the project runtime to Java 25.

### Replace the Groovy aggregate with the Groovy 5 BOM/module

Use the `org.apache.groovy` group and Groovy 5.1.0. The application currently relies on core Groovy language features only, so use the core module/BOM rather than the oversized legacy `groovy-all` aggregate.

### Align Spock and JUnit Platform

Use `org.spockframework:spock-core:2.4-groovy-5.0` with JUnit Platform 6.1.3. Remove unused JUnit 4 rules and the `spock-junit4` bridge rather than carrying legacy test-engine support. All Gradle `Test` tasks use JUnit Platform and produce XML/HTML reports.

### Preserve custom unit and integration tasks behind a real lifecycle task

Keep `_UT` and `_IT` class-pattern tasks because they encode the existing suite split. Configure `test` as the unit suite and make `integrationTest` run after successful unit tests through task dependencies/order, rather than disabling `test` or using `finalizedBy`. The full helper command will invoke a composed `testAll` lifecycle task so downstream integration tests do not run after unit failures.

### Upgrade direct dependencies to latest stable releases and pin every version

Resolve Maven Central metadata to identify stable releases, then commit only concrete versions. Migrate Apache HttpClient 4 to HttpClient 5 and JavaMail to Jakarta Mail with Angus Mail, including application and test import/API updates. Remove unused direct CGLIB and Objenesis declarations rather than retaining redundant test support libraries.

Pinned direct targets discovered from Maven Central metadata:

- Groovy BOM 5.1.0 and Spock 2.4-groovy-5.0
- SnakeYAML 2.6
- Apache HttpClient 5.6.4 (resolving HttpCore 5.4.3)
- Jackson Databind 2.22.2
- Logback Classic 1.6.3
- Jakarta Mail API 2.1.5 with Angus Mail 2.0.5
- Playwright 1.62.0
- Mockito 5.23.0 and JUnit Platform launcher 6.1.3

### Update all operator and agent entry points together

Update `README.md`, `QUICKSTART.md`, `SETUP.md`, `.agents/rules/build-and-test.md`, and `.agents/scripts/init-tools.sh` so prerequisites and commands are consistent. The scripts remain the canonical automation entry points.

## Risks / Trade-offs

- [Groovy 5 changes dynamic dispatch or compiler behavior] → Compile early, fix only evidenced incompatibilities, and rerun all unit/integration tests.
- [Spock/JUnit changes alter discovery] → Compare post-upgrade discovery with the 265-test baseline and inspect XML reports, not only Gradle exit status.
- [A newer dependency introduces an API break] → Upgrade incrementally, use dependency insight, and retain a compatible pinned version when migration would exceed scope.
- [Gradle task rewiring falsely reports green] → Verify concrete suite report directories/counts and ensure the full task has real executed test actions.
- [Credential-dependent tests remain skipped] → Report skipped counts explicitly; do not fabricate live credentials or turn those tests into false unit substitutes.

## Migration Plan

1. Regenerate the wrapper at Gradle 9.6.1 and update build DSL/dependencies for Java 25 and Groovy 5.1.0.
2. Compile and resolve compatibility failures iteratively.
3. Correct test orchestration and repository helper scripts.
4. Update Java/Gradle documentation.
5. Run wrapper/version, dependency insight, clean full tests, build, and distribution verification.
6. Roll back by reverting the migration commit; no application data/config migration is involved.

## Open Questions

None. Target versions and direct Hermes execution were explicitly selected.
