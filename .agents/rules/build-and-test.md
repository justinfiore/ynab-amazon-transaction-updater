---
trigger: always_on
---

# Building and Running Tests

The repository includes scripts for cleaning, building, and running unit and integration tests. Java 25 is required; the checked-in Gradle wrapper supplies Gradle 9.6.1.

## Cleaning the project

```bash
.agents/scripts/clean.sh
```

## Building the code and test classes

```bash
.agents/scripts/build.sh
```

## Running unit tests

```bash
.agents/scripts/run-unit-tests.sh
```

To run specific unit tests:

```bash
.agents/scripts/run-unit-tests.sh --tests '<fully.qualified.TestClass-or-pattern>'
```

The HTML report is copied to `test-results/test/index.html`.

## Running integration tests

```bash
.agents/scripts/run-integration-tests.sh
```

To run specific integration tests:

```bash
.agents/scripts/run-integration-tests.sh --tests '<fully.qualified.TestClass-or-pattern>'
```

The HTML report is copied to `test-results/integrationTest/index.html`.

## Running all tests

```bash
.agents/scripts/run-tests.sh
```

The full command runs unit tests first and integration tests only after the unit task succeeds. Reports are copied to:

- `test-results/test/index.html`
- `test-results/integrationTest/index.html`
