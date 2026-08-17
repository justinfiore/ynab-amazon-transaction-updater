## Why

The project currently targets Java 11, Gradle 7.6, and Groovy 3.0.19, which prevents it from using the current supported runtime and build ecosystem. Upgrading the stack now reduces compatibility and maintenance risk while preserving the transaction updater's existing behavior under its full unit and integration test suite.

## What Changes

- **BREAKING**: Raise the supported build and runtime baseline from Java 11 to Java 25.
- Upgrade the Gradle wrapper from 7.6 to the latest Gradle 9 release used by the reference modernization pattern.
- Upgrade application Groovy dependencies to Groovy 5.1.0 and align the latest stable Spock/JUnit Platform releases with Groovy 5.
- Upgrade all direct runtime and test dependencies to their latest stable releases, migrate legacy HTTP and mail artifact families, remove obsolete test support libraries, and keep every committed version pinned.
- Modernize Gradle test-task wiring so the full verification command runs unit tests before integration tests and emits JUnit XML and HTML reports.
- Update repository test helpers and operator documentation for the Java 25 baseline and current script locations.
- Preserve application behavior and prove compatibility with the complete unit and integration suite plus build/distribution verification.

## Capabilities

### New Capabilities
- `runtime-platform`: Defines the supported Java, Gradle, Groovy, dependency, build, test, and documentation contract for the transaction updater runtime.

### Modified Capabilities

None. This repository has no existing OpenSpec capability specifications, and the migration is intended to preserve application-level behavior.

## Impact

- Build/runtime files: `build.gradle`, Gradle wrapper properties/JAR/scripts, and repository helper scripts.
- Test infrastructure: Spock/JUnit Platform dependencies, unit/integration task orchestration, and generated report paths.
- Documentation: `README.md`, setup/quick-start guidance, and agent build/test instructions.
- Dependencies: Groovy, Spock, logging, HTTP/JSON/YAML, mail, Playwright, and associated runtime/test libraries as compatibility requires.
- Runtime consumers must use Java 25 after this change.
