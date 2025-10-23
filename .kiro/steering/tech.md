# Technology Stack

## Core Technologies

- **Language**: Groovy 3.0.19 (JVM-based)
- **Build System**: Gradle with Gradle Wrapper
- **Java Version**: Java 11+ (source and target compatibility)
- **Testing Framework**: Spock Framework 2.3 with Groovy 3.0

## Key Dependencies

### Runtime Libraries
- **HTTP Client**: Apache HttpComponents (httpclient 4.5.14, httpcore 4.4.16)
- **JSON Processing**: Jackson Databind 2.15.2
- **Configuration**: SnakeYAML 2.0 for YAML parsing
- **Logging**: Logback Classic 1.4.7 with SLF4J
- **Email**: JavaMail API 1.6.2 for Amazon order email parsing

### Testing Libraries
- **Spock Framework**: Primary testing framework with BDD-style specifications
- **Mockito**: 5.3.1 for mocking in tests
- **CGLib**: 3.3.0 for class mocking support
- **Objenesis**: 3.3 for object instantiation in mocks

## Build Commands

### Basic Operations
```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Clean build artifacts
./gradlew clean
```

### Testing
```bash
# Run unit tests only
./gradlew unitTest

# Run integration tests only  
./gradlew integrationTest

# Run all tests (unit then integration)
./gradlew test
```

### Project Scripts
```bash
# Build using project script
./.agents/scripts/build.sh

# Run all tests using project script
./.agents/scripts/run-tests.sh

# Run unit tests only
./.agents/scripts/run-unit-tests.sh

# Run integration tests only
./.agents/scripts/run-integration-tests.sh

# Clean project
./.agents/scripts/clean.sh
```

## Configuration

- **Format**: YAML configuration in `config.yml`
- **Example**: `config.example.yml` provides template
- **Validation**: Configuration class handles validation and defaults
- **Logging**: Configurable via logback and application settings

## Architecture Notes

- **Service-oriented**: Clear separation between YNAB, Amazon, matching, and processing services
- **Model-driven**: Dedicated model classes for transactions, orders, and matches
- **Configuration-first**: Centralized configuration management
- **Test-friendly**: Comprehensive unit and integration test coverage with Spock