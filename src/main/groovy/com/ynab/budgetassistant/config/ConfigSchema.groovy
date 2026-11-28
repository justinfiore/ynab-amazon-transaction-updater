package com.ynab.budgetassistant.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** The application configuration boundary, shared by runtime and operator commands. */
class ConfigSchema {
    static final Map CATEGORIZATION = [categories:List, provider:String, model:String, policy_version:String,
        credentials_file:String, consent:Boolean, connect_ms:Number, read_ms:Number, total_ms:Number,
        max_input_bytes:Number, max_response_bytes:Number, batch_size:Number, concurrency:Number,
        max_requests:Number, max_retries:Number, acceptance:[probability:Number, confidence:Number, margin:Number]]
    static final Map RETAILERS = [
        amazon:[email:String, email_password:String, forward_from_address:String, csv_file_path:String,
            order_fetcher:String, enrichment:String, journal_file:String, payment_accounts:Map,
            categorization:CATEGORIZATION, transaction_evidence:[enabled:Boolean], refund_email_evidence:[enabled:Boolean],
            python:[executable:String, bridge_script:String, config_path:String, include_household_orders:Boolean,
                timeout_seconds:Number, max_output_bytes:Number], imap_host:String, imap_port:Number],
        walmart:[enabled:Boolean, currency:String, allow_approved_uncategorized:Boolean, helper_executable:String,
            session_file:String, timeout_seconds:Number, max_output_bytes:Number, max_pages:Number, enrichment:String,
            default_category_id:String, journal_file:String, payment_accounts:Map, categorization:CATEGORIZATION],
        sheetz:[enabled:Boolean, memo_writes:Boolean, category_writes:Boolean, allow_approved_uncategorized:Boolean,
            python_executable:String, bridge_script:String, credentials_file:String, token_file:String,
            gasoline_default_category_id:String, unmatched_default_category_id:String, accounts:List, categorization:CATEGORIZATION]
    ]
    static final Map LEGACY = [ynab:[api_key:String, budget_id:String, account_id:String, base_url:String],
        app:[processed_transactions_file:String, log_level:String, dry_run:Boolean, look_back_days:Number]] + RETAILERS
    static final Map VERSION_TWO = [schema_version:Number, budget:[ynab:LEGACY.ynab],
        runtime:LEGACY.app + [amazon_fingerprint_credentials_file:String], retailers:RETAILERS]

    static Map parse(String text, String path) {
        try {
            def options = new LoaderOptions()
            options.allowDuplicateKeys = false
            Object value = new Yaml(new SafeConstructor(options)).load(text)
            if (!(value instanceof Map)) throw new IllegalArgumentException('Configuration must be a mapping')
            return (Map)value
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid YAML configuration: ${path}")
        }
    }

    static Map load(File file) {
        if (!file.isFile()) throw new IllegalArgumentException("Configuration file not found: ${file.path}")
        Map config = parse(file.getText('UTF-8'), file.path)
        validate(config, file.path)
        config
    }

    static void validate(Map config, String path) {
        if (config.containsKey('schema_version') && (!(config.schema_version instanceof Integer) || config.schema_version != 2)) {
            throw new IllegalArgumentException("Unsupported schema version in ${path}")
        }
        if (!config.containsKey('schema_version') || config.keySet().any { it in LEGACY.keySet() }) {
            File location = new File(ConfigSchema.protectionDomain.codeSource.location.toURI())
            File script = location.name.endsWith('.jar') ? new File(location.parentFile.parentFile, 'scripts/migrate-config.sh') : new File('scripts/migrate-config.sh').absoluteFile
            String quotedScript = "'${script.path.replace("'", "'\\''")}'"
            throw new IllegalArgumentException("Configuration migration required: ${path} uses legacy application properties. Run ${quotedScript} --input '${path.replace("'", "'\\''")}' --output '<new-config-path>' --legacy-working-directory '<old-working-directory>' --check, then --apply; see CONFIG_MIGRATION.md and use scripts/migrate-config.bat on Windows. In a source checkout run ./gradlew installDist first.")
        }
        validateKeys(config, VERSION_TWO, '')
        if (!(config.budget instanceof Map) || !(config.budget.ynab instanceof Map) ||
            !(config.runtime instanceof Map) || !(config.retailers instanceof Map)) {
            throw new IllegalArgumentException('Configuration requires budget.ynab, runtime and retailers mappings')
        }
        if (!config.budget.ynab.api_key || !config.budget.ynab.budget_id) throw new IllegalArgumentException('Configuration requires budget.ynab.api_key and budget.ynab.budget_id')
    }

    static void validateKeys(Map values, Map schema, String prefix) {
        values.each { key, value ->
            if (!(key instanceof String) || !schema.containsKey(key)) throw new IllegalArgumentException("Unknown configuration property: ${prefix}${key instanceof String ? key : '<non-string>'}")
            def expected = schema[key]
            String path = prefix + key
            if (value == null) return
            if (expected instanceof Map) {
                if (!(value instanceof Map)) throw new IllegalArgumentException("Expected mapping: ${path}")
                validateKeys(value as Map, expected as Map, path + '.')
            } else if (!expected.isInstance(value)) throw new IllegalArgumentException("Invalid value type: ${path}")
            else if (key == 'payment_accounts' && value.any { k, v -> !(k instanceof String) || !(v instanceof String) }) {
                throw new IllegalArgumentException("Expected string payment account mappings: ${path}")
            } else if (key == 'accounts' && value.any { !(it instanceof String) }) {
                throw new IllegalArgumentException("Expected string account IDs: ${path}")
            }
            else if (key == 'categories') {
                value.eachWithIndex { category, index ->
                    if (!(category instanceof Map)) throw new IllegalArgumentException("Expected mapping: ${path}[${index}]")
                    validateKeys(category as Map, [id:String, description:String], "${path}[${index}].")
                    if (!category.id) throw new IllegalArgumentException("Missing category id: ${path}[${index}]")
                }
            }
        }
    }
}
