# Project Structure

## Directory Layout

```
src/
├── main/groovy/com/ynab/amazon/          # Main application code
│   ├── YNABAmazonTransactionUpdater.groovy  # Main entry point
│   ├── config/
│   │   └── Configuration.groovy          # YAML config loader and validation
│   ├── model/                           # Data models
│   │   ├── YNABTransaction.groovy       # YNAB transaction representation
│   │   ├── AmazonOrder.groovy           # Amazon order and item models
│   │   └── TransactionMatch.groovy      # Match result with confidence score
│   └── service/                         # Business logic services
│       ├── YNABService.groovy           # YNAB API integration
│       ├── AmazonService.groovy         # Amazon data parsing (CSV/email)
│       ├── TransactionMatcher.groovy    # Matching algorithm
│       └── TransactionProcessor.groovy  # Transaction update logic
├── main/resources/
│   └── logback.xml                      # Logging configuration
└── test/groovy/com/ynab/amazon/         # Test mirror structure
    ├── YNABAmazonTransactionUpdater_UT.groovy
    ├── config/Configuration_UT.groovy
    ├── model/                           # Model tests
    └── service/                         # Service tests
```

## Package Organization

- **`com.ynab.amazon`**: Root package containing main application class
- **`com.ynab.amazon.config`**: Configuration management
- **`com.ynab.amazon.model`**: Data transfer objects and domain models
- **`com.ynab.amazon.service`**: Business logic and external integrations

## Naming Conventions

### Classes
- **Main classes**: Descriptive names (e.g., `YNABAmazonTransactionUpdater`)
- **Services**: `[Domain]Service` pattern (e.g., `YNABService`, `AmazonService`)
- **Models**: Simple domain names (e.g., `YNABTransaction`, `AmazonOrder`)
- **Configuration**: `Configuration` for main config class

### Tests
- **Unit tests**: `[ClassName]_UT.groovy` suffix
- **Integration tests**: `[ClassName]_IT.groovy` suffix
- **Test methods**: Descriptive BDD-style names with "should" statements

### Files and Directories
- **Configuration**: `config.yml` (main), `config.example.yml` (template)
- **Data files**: `processed_transactions.json`, `amazon_orders.csv`
- **Scripts**: `.agents/scripts/` for build and test automation
- **Test results**: `test-results/` for HTML test reports

## Architecture Patterns

### Service Layer Pattern
- Clear separation of concerns with dedicated service classes
- Each service handles one domain (YNAB, Amazon, matching, processing)
- Services are injected/passed as dependencies

### Configuration Pattern
- Centralized configuration in YAML format
- Configuration validation at startup
- Environment-specific overrides supported

### Model-Driven Design
- Rich domain models with business logic methods
- Clear data transfer objects for API interactions
- Immutable-style data structures where appropriate

## File Conventions

- **Groovy files**: Use `.groovy` extension
- **Configuration**: YAML format preferred over properties
- **Documentation**: Markdown format for all docs
- **Scripts**: Shell scripts in `.agents/scripts/` directory
- **Logs**: Structured logging to `logs/` directory with rotation