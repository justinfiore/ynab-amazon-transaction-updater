## ADDED Requirements

### Requirement: Java 25 runtime baseline
The project SHALL use Java 25 as its supported build and runtime baseline, and its Gradle build SHALL request a Java 25 toolchain.

#### Scenario: Build environment reports Java 25
- **WHEN** an operator runs the Gradle wrapper with Java 25 available
- **THEN** Gradle reports a Java 25 JVM/toolchain and compiles the application for the supported baseline

### Requirement: Reproducible Gradle 9 wrapper
The repository SHALL include a complete, regenerated Gradle 9 wrapper with a pinned distribution version and distribution URL validation.

#### Scenario: Wrapper runs without system Gradle
- **WHEN** an operator invokes `./gradlew --version` in a fresh checkout
- **THEN** the checked-in wrapper starts the pinned Gradle 9 distribution without requiring a separately installed Gradle

### Requirement: Groovy 5 application stack
The build SHALL use Groovy 5.1.0 from the `org.apache.groovy` module family and SHALL align the latest stable test framework with the Groovy 5 runtime.

#### Scenario: Dependency resolution is aligned
- **WHEN** Gradle resolves main and test runtime classpaths
- **THEN** application Groovy resolves to 5.1.0 and Spock resolves to a Groovy 5-compatible variant without mixed Groovy generations

### Requirement: Compatible pinned dependencies
All direct runtime and test dependencies SHALL use pinned latest-stable versions that resolve and execute on Java 25, Groovy 5.1.0, and Gradle 9; dynamic selectors SHALL NOT be committed.

Legacy JavaMail and Apache HttpClient 4 artifact families SHALL be replaced by Jakarta Mail with Angus Mail and Apache HttpClient 5 respectively. Unused JUnit 4, CGLIB, and direct Objenesis test support SHALL NOT remain declared.

#### Scenario: Clean dependency resolution
- **WHEN** the project builds from an empty build directory
- **THEN** all compile, runtime, and test dependencies resolve without version-range or dynamic-version requirements

### Requirement: Complete deterministic test verification
The repository SHALL provide a full-suite command that executes unit tests before integration tests, stops before integration tests when unit tests fail, and emits separate JUnit XML and HTML reports for both suites.

#### Scenario: Full suite passes
- **WHEN** an operator runs the documented full-suite helper
- **THEN** all non-skipped unit and integration tests pass and separate suite reports are generated

#### Scenario: Unit failure prevents integration execution
- **WHEN** the unit suite fails during the composed full-suite task
- **THEN** Gradle reports failure and does not execute the integration suite

### Requirement: Build and distribution verification
The upgraded project SHALL compile, package, and create an installable application distribution under Java 25 without requiring live YNAB, Amazon, Walmart, or email credentials.

#### Scenario: Offline-safe build verification
- **WHEN** an operator runs clean tests, build, and `installDist` without application credentials
- **THEN** Gradle successfully produces compiled classes, test reports, archives, and launcher scripts without performing live account updates

### Requirement: Accurate repository guidance
Operator and agent documentation SHALL identify Java 25 as required and SHALL reference the repository's existing `.agents/scripts` helper paths and current verification commands.

#### Scenario: New contributor follows documentation
- **WHEN** a contributor reads `README.md`, `QUICKSTART.md`, `SETUP.md`, or `AGENTS.md`-delegated build instructions
- **THEN** the documented Java requirement and build/test commands match the verified upgraded repository behavior

### Requirement: Pull request test visibility
GitHub Actions SHALL run the complete Java 25 unit and integration suite for pull requests and pushes targeting `master`, preserve reports as downloadable artifacts, and publish totals plus failing test names directly in the GitHub Actions check summary.

#### Scenario: Pull request tests pass
- **WHEN** a pull request targets `master` and the complete Gradle suite succeeds
- **THEN** the check reports success, displays unit/integration totals and skips in its summary, and retains JUnit XML, HTML reports, and Gradle output

#### Scenario: Pull request tests fail
- **WHEN** a unit or integration test fails in GitHub Actions
- **THEN** summary and artifact publication still run, the summary identifies failing test cases, and the job concludes with failure
