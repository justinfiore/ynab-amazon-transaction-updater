package com.ynab.budgetassistant.service

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir

class CategoryOnboarding_UT extends Specification {
    @TempDir File directory

    private static void asVersionTwo(File file) {
        Map old = new Yaml().load(file.text)
        file.text = new Yaml().dump([schema_version:2, budget:[ynab:[api_key:'fixture',budget_id:'plan']], runtime:[:], retailers:old])
    }

    def 'direct onboarding entrypoints reject legacy configuration before account calls or prompts'() {
        given:
        File file = new File(directory, 'legacy.yml')
        file.text = 'ynab: {api_key: fixture, budget_id: plan}\namazon: {}\n'
        String original = file.text
        def ynab = Mock(YNABService)
        def output = new StringWriter()
        def input = new BufferedReader(new StringReader('yes\n'))

        when:
        CategoryOnboarding.selectCategories(file.toPath(), ynab, input, new PrintWriter(output, true), [:], 'amazon')

        then:
        thrown(IllegalArgumentException)
        0 * ynab._
        output.toString().empty
        file.text == original
        directory.list().toList() == ['legacy.yml']

        when:
        CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(), ynab, input, new PrintWriter(output, true))

        then:
        thrown(IllegalArgumentException)
        0 * ynab._
        output.toString().empty
        file.text == original
        directory.list().toList() == ['legacy.yml']
    }

    def 'Amazon saves only its shortlist and explicit consent without default or enablement'() {
        given:
        File file = new File(directory,'config.yml')
        file.text = 'amazon: {enrichment: memo_only}\nwalmart: {default_category_id: groceries, enabled: false}\n'
        asVersionTwo(file)
        def catalog = [[id:'pets',name:'Pets',group_name:'Spending'],[id:'e',name:'Electronics',group_name:'Spending']]
        def ynab = Stub(YNABService) { getCategoryCatalog() >> catalog }
        def output = new StringWriter()

        when:
        boolean saved = CategoryOnboarding.selectCategories(file.toPath(),ynab,
            new BufferedReader(new StringReader('1,2\nyes\n')),new PrintWriter(output,true),
            [provider:'jev',secret:'fixture-key'.toCharArray()],'amazon')
        Map result = new Yaml().load(file.text)

        then:
        saved
        result.retailers.amazon.enrichment == 'memo_only'
        !result.retailers.amazon.containsKey('default_category_id')
        result.retailers.amazon.categorization.categories*.id == ['e','pets']
        result.retailers.amazon.categorization.consent
        result.retailers.amazon.categorization.policy_version == 'amazon-choice-v1'
        result.retailers.walmart == [default_category_id:'groceries',enabled:false]
        !output.toString().contains('Default:')
        !file.text.contains('fixture-key')
        ProviderKeys.read(result.retailers.amazon.categorization.credentials_file,'jev').consent
    }

    def 'disconnect retains a protected record shared with another retailer'() {
        given:
        File file = new File(directory,'config.yml')
        def key = ProviderKeys.stage(file.toPath(),'jev','fixture-key'.toCharArray(),'amazon')
        file.text = new Yaml().dump([amazon:[categorization:[provider:'jev',credentials_file:key.toString(),categories:[[id:'e']]]],
            (retailer):[categorization:[provider:'jev',credentials_file:key.toString()]]])
        asVersionTwo(file)
        def previousIn = System.in
        System.setIn(new ByteArrayInputStream('yes\n'.bytes))

        when:
        CategoryOnboarding.disconnect(file,'amazon')
        Map result = new Yaml().load(file.text)

        then:
        !result.retailers.amazon.categorization.credentials_file
        result.retailers.amazon.categorization.categories == [[id:'e']]
        result.retailers[retailer].categorization.credentials_file == key.toString()
        ProviderKeys.read(key.toString(),'jev').consent

        cleanup:
        System.setIn(previousIn)

        where:
        retailer << ['walmart', 'sheetz']
    }

    def 'payment identity requires confirmation and cannot choose a closed or stale account'() {
        given:
        File file = new File(directory,'config.yml')
        file.text = 'amazon: {enrichment: memo_only}\nwalmart: {payment_accounts: {other: untouched}}\n'
        asVersionTwo(file)
        def ynab = Stub(YNABService) { getBudgetAccounts() >> (['account'] as Set) }
        String original = file.text

        expect:
        !CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(),ynab,
            new BufferedReader(new StringReader('python:visa:1234\n1\nno\n')),new PrintWriter(new StringWriter()))
        file.text == original

        when:
        boolean saved = CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(),ynab,
            new BufferedReader(new StringReader('python:visa:1234\n1\nyes\n')),new PrintWriter(new StringWriter()))
        Map result = new Yaml().load(file.text)

        then:
        saved
        result.retailers.amazon.payment_accounts == ['python:visa:1234':'account']
        result.retailers.amazon.enrichment == 'memo_only'
        result.retailers.walmart.payment_accounts == [other:'untouched']

        when:
        def stale = Stub(YNABService) { getBudgetAccounts() >>> [(['account'] as Set),([] as Set)] }
        CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(),stale,
            new BufferedReader(new StringReader('python:visa:9999\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        new Yaml().load(file.text).retailers.amazon.payment_accounts == ['python:visa:1234':'account']
    }
}
