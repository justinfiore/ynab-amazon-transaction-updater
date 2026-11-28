package com.ynab.budgetassistant.config

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ConfigMigration_UT extends Specification {
    @TempDir File directory

    def 'check and apply preserve settings and resolve state against the old working directory'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = '''ynab: {api_key: fixture, budget_id: plan}
app: {processed_transactions_file: state.json}
amazon: {order_fetcher: python, categorization: {consent: false, credentials_file: keys/provider.json}, python: {executable: bin/python}}
walmart: {enabled: false}
sheetz: {unmatched_default_category_id: null}
'''
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')
        String[] args = ['--input',input.path,'--output',output.path,'--legacy-working-directory',directory.path]
        String before = input.text
        File state = new File(directory, 'state.json')
        state.text = '{"processed_transaction_ids":["already-processed"]}'
        Files.setPosixFilePermissions(state.toPath(), PosixFilePermissions.fromString('rw-------'))

        when:
        ConfigMigration.run(args + ['--check'])

        then:
        !output.exists()
        input.text == before

        when:
        ConfigMigration.run(args + ['--apply'])
        Map config = new Yaml().load(output.text)
        def loaded = new Configuration()
        loaded.loadConfiguration(output)

        then:
        input.text == before
        config.schema_version == 2
        config.runtime.dry_run == false
        config.runtime.processed_transactions_file == new File(directory,'state.json').absolutePath
        config.runtime.require_existing_processed_transactions_file == true
        config.retailers.amazon.python.executable == new File(directory,'bin/python').absolutePath
        config.retailers.amazon.categorization.consent == false
        config.retailers.amazon.categorization.credentials_file == new File(directory,'keys/provider.json').absolutePath
        loaded.amazonCategorizationFingerprint == [consent:false, credentials_file:'keys/provider.json']
        com.ynab.budgetassistant.service.ItemClassificationService.fingerprint(loaded.amazonCategorizationFingerprint) ==
            com.ynab.budgetassistant.service.ItemClassificationService.fingerprint([consent:false, credentials_file:'keys/provider.json'])
        config.retailers.sheetz.containsKey('unmatched_default_category_id')
        config.retailers.sheetz.unmatched_default_category_id == null
        loaded.isAmazonTransactionEvidenceEnabled()
        Files.getPosixFilePermissions(output.toPath()) == PosixFilePermissions.fromString('rw-------')

        when:
        ConfigMigration.run(args + ['--apply'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'schema gate rejects legacy, mixed, unsupported and duplicate configuration'() {
        when:
        Map parsed = ConfigSchema.parse(text, 'fixture.yml')
        ConfigSchema.validate(parsed, 'fixture.yml')

        then:
        thrown(IllegalArgumentException)

        where:
        text << ['ynab: {}', 'schema_version: 2\nynab: {}', 'schema_version: 3\nbudget: {ynab: {}}\nruntime: {}\nretailers: {}',
            'schema_version: 2\nschema_version: 2\nbudget: {ynab: {}}\nruntime: {}\nretailers: {}',
            'schema_version: 2\nbudget: {ynab: {}}\nruntime: {dry_runn: true}\nretailers: {}',
            'schema_version: 2\nbudget: [invalid\nruntime: {}']
    }

    def 'missing and null retailer sections are rejected'() {
        when:
        ConfigSchema.validate([schema_version:2, budget:[ynab:[api_key:'fixture', budget_id:'plan']],
            runtime:[:], retailers:retailers], 'fixture.yml')

        then:
        thrown(IllegalArgumentException)

        where:
        retailers << [[amazon:[:], walmart:[:]], [amazon:null, walmart:[:], sheetz:[:]],
            [amazon:[:], walmart:[:], sheetz:null]]
    }

    def 'schema rejects non-string payment mappings and account IDs without exposing values'() {
        when:
        ConfigSchema.validate([schema_version:2, budget:[ynab:[api_key:'fixture', budget_id:'plan']],
            runtime:[:], retailers:retailers], 'fixture.yml')

        then:
        def failure = thrown(IllegalArgumentException)
        !failure.message.contains('sensitive-value')

        where:
        retailers << [[amazon:[payment_accounts:['sensitive-value':42]]],
            [walmart:[payment_accounts:[42:'sensitive-value']]],
            [sheetz:[accounts:['sensitive-value', 42]]]]
    }

    def 'checkout remapping respects path boundaries and shared provider identity'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File oldRoot = new File(directory, 'old')
        oldRoot.mkdir()
        File sibling = new File(directory, 'old-other')
        sibling.mkdir()
        File state = new File(oldRoot, 'state.json')
        state.text = '{"pending":"unchanged"}'
        File input = new File(directory, 'old.yml')
        input.text = new Yaml().dump([ynab:[api_key:'fixture',budget_id:'plan'],
            app:[processed_transactions_file:state.path],
            amazon:[categorization:[credentials_file:new File(oldRoot,'credentials').path]],
            walmart:[session_file:new File(sibling,'session').path,
                categorization:[credentials_file:new File(oldRoot,'credentials').path]]])
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')
        File newRoot = new File(directory, 'new')

        when:
        ConfigMigration.run(['--input',input.path,'--output',output.path,'--legacy-working-directory',oldRoot.path,
            '--old-checkout',oldRoot.path,'--new-checkout',newRoot.path,'--apply'] as String[])
        Map result = new Yaml().load(output.text)

        then:
        result.runtime.processed_transactions_file == new File(newRoot,'state.json').path
        result.retailers.amazon.categorization.credentials_file == new File(newRoot,'credentials').path
        result.retailers.walmart.categorization.credentials_file == result.retailers.amazon.categorization.credentials_file
        result.retailers.walmart.session_file == new File(sibling,'session').path
        state.text == '{"pending":"unchanged"}'
    }

    def 'migrated processed state must be staged, protected and readable before use'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = 'ynab: {api_key: fixture, budget_id: plan}\napp: {processed_transactions_file: state.json}\n'
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')
        File oldRoot = new File(directory, 'old-checkout'); oldRoot.mkdir()
        File newRoot = new File(directory, 'new-checkout'); newRoot.mkdir()
        ConfigMigration.run(['--input', input.path, '--output', output.path,
            '--legacy-working-directory', oldRoot.path, '--old-checkout', oldRoot.path,
            '--new-checkout', newRoot.path, '--apply'] as String[])
        File state = new File(newRoot, 'state.json')

        when:
        new Configuration().loadConfiguration(output)

        then:
        thrown(IllegalStateException)
        !state.exists()

        when:
        state.text = '{"processed_transaction_ids":["already"]}'
        Files.setPosixFilePermissions(state.toPath(), PosixFilePermissions.fromString('rw-r--r--'))
        new Configuration().loadConfiguration(output)

        then:
        thrown(IllegalStateException)

        when:
        Files.setPosixFilePermissions(state.toPath(), PosixFilePermissions.fromString('rw-------'))
        state.text = '{invalid json}'
        new Configuration().loadConfiguration(output)

        then:
        thrown(IllegalStateException)

        when:
        state.text = '{"processed_transaction_ids":["already"]}'
        def config = new Configuration()
        config.loadConfiguration(output)
        def processor = new com.ynab.budgetassistant.service.TransactionProcessor(config)

        then:
        config.requireExistingProcessedTransactionsFile
        processor != null

        when:
        state.delete()
        new com.ynab.budgetassistant.service.TransactionProcessor(config)

        then:
        thrown(IllegalStateException)
    }

    def 'unsafe input permissions and existing output fail without modifying either file'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = 'ynab: {api_key: fixture, budget_id: plan}\n'
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-r--r--'))
        File output = new File(directory, 'new.yml')
        String[] args = ['--input',input.path,'--output',output.path,'--legacy-working-directory',directory.path,'--apply']
        String original = input.text

        when:
        ConfigMigration.run(args)

        then:
        thrown(IllegalArgumentException)
        !output.exists()
        input.text == original

        when:
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        output.text = 'existing output'
        ConfigMigration.run(args)

        then:
        thrown(IllegalArgumentException)
        output.text == 'existing output'
        input.text == original
    }

    def 'enabled Walmart requires the actual legacy bundled helper path'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = 'ynab: {api_key: fixture, budget_id: plan}\nwalmart: {enabled: true}\n'
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')
        String[] args = ['--input',input.path,'--output',output.path,'--legacy-working-directory',directory.path]

        when:
        ConfigMigration.run(args + ['--check'])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('--legacy-walmart-helper')
        !output.exists()

        when:
        ConfigMigration.run(args + ['--legacy-walmart-helper',new File(directory,'walmart-helper').path,'--apply'])
        Map migrated = new Yaml().load(output.text)

        then:
        migrated.retailers.walmart.helper_executable == new File(directory,'walmart-helper').path
    }

    def 'blank paths use old working directory defaults and the bundled helper'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = new Yaml().dump([ynab:[api_key:'fixture', budget_id:'plan'],
            amazon:[journal_file:'  '], walmart:[enabled:true, helper_executable:' ', journal_file:' '],
            sheetz:[bridge_script:'  ']])
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')
        String[] args = ['--input', input.path, '--output', output.path, '--legacy-working-directory', directory.path]

        when:
        ConfigMigration.run(args + ['--check'])

        then:
        thrown(IllegalArgumentException)
        !output.exists()

        when:
        ConfigMigration.run(args + ['--legacy-walmart-helper', new File(directory, 'helper').path, '--apply'])
        Map result = new Yaml().load(output.text)

        then:
        result.retailers.amazon.journal_file == new File(directory, 'amazon-journal.json').path
        result.retailers.walmart.journal_file == new File(directory, 'walmart-journal.json').path
        result.retailers.walmart.helper_executable == new File(directory, 'helper').path
        result.retailers.sheetz.bridge_script == new File(directory, 'scripts/sheetz_bridge.py').path
    }

    def 'invalid resource paths do not expose their configured values'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = 'ynab: {api_key: fixture, budget_id: plan}\namazon: {csv_file_path: "private-value\\0invalid"}\n'
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')

        when:
        ConfigMigration.run(['--input', input.path, '--output', output.path,
            '--legacy-working-directory', directory.path, '--apply'] as String[])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'Invalid path for csv_file_path'
        !failure.message.contains('private-value')
        !output.exists()
    }

    def 'unknown legacy properties fail without exposing values or creating output'() {
        given:
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString('rwx------'))
        File input = new File(directory, 'old.yml')
        input.text = 'ynab: {api_key: fixture, budget_id: plan}\namazon: {obsolete_setting: private-fixture-value}\n'
        Files.setPosixFilePermissions(input.toPath(), PosixFilePermissions.fromString('rw-------'))
        File output = new File(directory, 'new.yml')

        when:
        ConfigMigration.run(['--input',input.path,'--output',output.path,'--legacy-working-directory',directory.path,'--apply'] as String[])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('amazon.obsolete_setting')
        !failure.message.contains('private-fixture-value')
        !output.exists()
    }
}
