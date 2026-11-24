package com.ynab.amazon.service

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir

class CategoryOnboarding_UT extends Specification {
    @TempDir File directory

    def 'Amazon saves only its shortlist and explicit consent without default or enablement'() {
        given:
        File file = new File(directory,'config.yml')
        file.text = 'amazon: {enrichment: memo_only}\nwalmart: {default_category_id: groceries, enabled: false}\n'
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
        result.amazon.enrichment == 'memo_only'
        !result.amazon.containsKey('default_category_id')
        result.amazon.categorization.categories*.id == ['e','pets']
        result.amazon.categorization.consent
        result.amazon.categorization.policy_version == 'amazon-choice-v1'
        result.walmart == [default_category_id:'groceries',enabled:false]
        !output.toString().contains('Default:')
        !file.text.contains('fixture-key')
        ProviderKeys.read(result.amazon.categorization.credentials_file,'jev').consent
    }

    def 'disconnect removes only the Amazon reference when Walmart shares the protected record'() {
        given:
        File file = new File(directory,'config.yml')
        def key = ProviderKeys.stage(file.toPath(),'jev','fixture-key'.toCharArray(),'amazon')
        file.text = new Yaml().dump([amazon:[categorization:[provider:'jev',credentials_file:key.toString(),categories:[[id:'e']]]],
            walmart:[categorization:[provider:'jev',credentials_file:key.toString()]]])
        def previousIn = System.in
        System.setIn(new ByteArrayInputStream('yes\n'.bytes))

        when:
        CategoryOnboarding.disconnect(file,'amazon')
        Map result = new Yaml().load(file.text)

        then:
        !result.amazon.categorization.credentials_file
        result.amazon.categorization.categories == [[id:'e']]
        result.walmart.categorization.credentials_file == key.toString()
        ProviderKeys.read(key.toString(),'jev').consent

        cleanup:
        System.setIn(previousIn)
    }

    def 'payment identity requires confirmation and cannot choose a closed or stale account'() {
        given:
        File file = new File(directory,'config.yml')
        file.text = 'amazon: {enrichment: memo_only}\nwalmart: {payment_accounts: {other: untouched}}\n'
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
        result.amazon.payment_accounts == ['python:visa:1234':'account']
        result.amazon.enrichment == 'memo_only'
        result.walmart.payment_accounts == [other:'untouched']

        when:
        def stale = Stub(YNABService) { getBudgetAccounts() >>> [(['account'] as Set),([] as Set)] }
        CategoryOnboarding.selectAmazonPaymentAccount(file.toPath(),stale,
            new BufferedReader(new StringReader('python:visa:9999\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        new Yaml().load(file.text).amazon.payment_accounts == ['python:visa:1234':'account']
    }
}
