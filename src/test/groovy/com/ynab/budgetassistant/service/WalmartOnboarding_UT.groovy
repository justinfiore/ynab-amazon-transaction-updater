package com.ynab.budgetassistant.service

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class WalmartOnboarding_UT extends Specification {
    @TempDir File directory
    def catalog=[[id:'a',group_name:'Food',name:'Same'],[id:'b',group_name:'Home',name:'Same']]

    private static void asVersionTwo(File file) {
        Map old = new Yaml().load(file.text)
        file.text = new Yaml().dump([schema_version:2, budget:[ynab:old.remove('ynab') ?: [api_key:'fixture',budget_id:'plan']],
            runtime:[:], retailers:[amazon:[:],walmart:[:],sheetz:[:]] + old])
    }
    @Unroll
    def '#provider activation preserves duplicate category names through inference without leaking IDs'() {
        given:
        File file=new File(directory,'config.yml'); file.text='walmart: {enabled: false, enrichment: link_only}\n'
        asVersionTwo(file)
        def ynab=Stub(YNABService) { getWalmartCategories() >> catalog }
        def output=new StringWriter()
        Map pending=[provider:provider,secret:'synthetic-fixture-secret'.toCharArray()]

        when:
        boolean saved=CategoryOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1,2\n2\nyes\n')),new PrintWriter(output,true),pending)
        Map config=new Yaml().load(file.text)

        then:
        saved
        config.retailers.walmart.categorization.provider==provider
        config.retailers.walmart.categorization.model==RemoteItemClassifier.ROUTES[provider].model
        config.retailers.walmart.categorization.acceptance==[probability:0.8,confidence:0.8,margin:0.2]
        config.retailers.walmart.default_category_id=='b'
        !config.retailers.walmart.enabled && config.retailers.walmart.enrichment=='link_only'
        ProviderKeys.read(config.retailers.walmart.categorization.credentials_file,provider).consent
        !file.text.contains('synthetic-fixture-secret')
        !output.toString().contains('synthetic-fixture-secret')
        pending.secret.every { it==(char)0 }

        when: 'the saved duplicate names become distinct meaningful choices and return their original stable ID'
        Map normalized=WalmartAllocation.categories(catalog,config.retailers.walmart.categorization.categories,'b')
        Map providerConfig=config.retailers.walmart.categorization
        String wire
        def classifier=new RemoteItemClassifier(providerConfig,{ endpoint,key,body,limits ->
            wire=body
            Map scores=['Food / Same':0.01,'Home / Same':0.97,(RemoteItemClassifier.ABSTAIN):0.02]
            Map answer=[type:'choice',name:'item_0',choice:'Home / Same',confidence:0.95,
                probabilities:provider=='openai' ? scores.collect { k,v -> [value:k,probability:v] } : scores]
            [status:200,body:RemoteItemClassifier.JSON.writeValueAsString([model:providerConfig.model,
                answers:provider=='openai' ? [answer] : [item_0:answer],usage:[input_tokens:50,output_tokens:0]])]
        })
        def decisions=classifier.classify([new com.ynab.budgetassistant.model.WalmartOrderItem(itemId:'private-item',title:'A household product')],normalized)
        then:
        decisions['private-item'].category_id=='b'
        wire.contains('Food / Same') && wire.contains('Home / Same')
        !wire.contains('"a"') && !wire.contains('"b"') && !wire.contains('private-item')
        catalog*.id==['a','b']

        when:
        String before=file.text
        boolean cancelled=CategoryOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1\n1\nno\n')),new PrintWriter(output,true),[provider:'openai',secret:'cancelled-fixture'.toCharArray()])
        then:
        !cancelled && file.text==before
        new File(directory,'.walmart-credentials').listFiles().size()==1

        where:
        provider << ['openai','jev']
    }
    def 'numbered category setup preserves unrelated values and mode, normalizes IDs and protects atomic save'() {
        given:
        File file=new File(directory,'config.yml')
        file.text='ynab: {api_key: fixture, budget_id: plan}\namazon: {email: preserved}\nwalmart: {enabled: false, enrichment: link_only}\n'
        asVersionTwo(file)
        def ynab=Stub(YNABService) { getWalmartCategories() >> catalog }
        def output=new StringWriter()

        when:
        boolean saved=CategoryOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1,2\n2\nyes\n')),new PrintWriter(output,true))
        def config=new Yaml().load(file.text)

        then:
        saved
        config.retailers.amazon.email=='preserved'
        !config.retailers.walmart.enabled
        config.retailers.walmart.enrichment=='link_only'
        config.retailers.walmart.default_category_id=='b'
        config.retailers.walmart.categorization.categories==[[id:'a'],[id:'b']]
        output.toString().contains('Food / Same') && output.toString().contains('Home / Same')
        java.nio.file.Files.getPosixFilePermissions(file.toPath()).toString()=='[OWNER_READ, OWNER_WRITE]' ||
            java.nio.file.Files.getPosixFilePermissions(file.toPath()).size()==2
    }
    @Unroll
    def 'invalid selection or cancellation leaves original bytes: #answer'() {
        given:
        File file=new File(directory,'config.yml'); file.text='walmart: {enabled: false}\n'
        asVersionTwo(file)
        def ynab=Stub(YNABService) { getWalmartCategories() >> catalog }
        String before=file.text

        expect:
        !CategoryOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader(answer)),new PrintWriter(new StringWriter()))
        file.text==before

        where:
        answer << ['q\n','1,1\n','0\n','3\n','x\n','1\n2\n','1\n1\nno\n']
    }
    def 'category deletion after confirmation and concurrent config edits cannot overwrite config'() {
        given:
        File file=new File(directory,'config.yml'); file.text='walmart: {enabled: false}\n'
        asVersionTwo(file)
        String original = file.text
        def ynab=Stub(YNABService) { getWalmartCategories() >>> [catalog,[]] }

        when:
        CategoryOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        file.text==original

        when:
        def changed=Stub(YNABService) { getWalmartCategories() >> { file.text='other: changed\n'; catalog } }
        CategoryOnboarding.selectCategories(file.toPath(),changed,new BufferedReader(new StringReader('1\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        file.text=='other: changed\n'
    }

    def 'setup without a console does not save a key or send an inference request'() {
        given:
        File file=new File(directory,'config.yml')
        file.text=new File('config.example.yml').text.replace('your_actual_ynab_api_key_here','fixture').replace('your_actual_ynab_budget_id_here','plan')
        String before=file.text
        def previousIn=System.in
        def previousOut=System.out
        def output=new ByteArrayOutputStream()
        System.setIn(new ByteArrayInputStream('1\nyes\n'.bytes))
        System.setOut(new PrintStream(output))

        when:
        CategoryOnboarding.run(file, false)

        then:
        file.text==before
        output.toString().contains('Hidden input unavailable')
        !new File(directory,'.walmart-credentials').exists()

        when:
        file.text='walmart: {enabled: true, enrichment: exception_first, default_category_id: b, categorization: {categories: [{id: a}, {id: b}]}}\n'
        asVersionTwo(file)
        boolean rerun=CategoryOnboarding.selectCategories(file.toPath(),Stub(YNABService) { getWalmartCategories() >> catalog },
            new BufferedReader(new StringReader('\n\nyes\n')),new PrintWriter(new StringWriter()),[:])
        Map rerunConfig=new Yaml().load(file.text)
        then:
        rerun
        rerunConfig.retailers.walmart.enabled
        rerunConfig.retailers.walmart.enrichment=='exception_first'
        rerunConfig.retailers.walmart.categorization.categories*.id==['a','b']
        rerunConfig.retailers.walmart.default_category_id=='b'

        cleanup:
        System.setIn(previousIn)
        System.setOut(previousOut)
    }
}
