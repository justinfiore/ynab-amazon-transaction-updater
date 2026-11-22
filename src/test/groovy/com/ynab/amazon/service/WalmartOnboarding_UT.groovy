package com.ynab.amazon.service

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class WalmartOnboarding_UT extends Specification {
    @TempDir File directory
    def catalog=[[id:'a',group_name:'Food',name:'Same'],[id:'b',group_name:'Home',name:'Same']]
    def 'numbered category setup preserves unrelated values and mode, normalizes IDs and protects atomic save'() {
        given:
        File file=new File(directory,'config.yml')
        file.text='ynab: {api_key: fixture, budget_id: plan}\namazon: {email: preserved}\nwalmart: {enabled: false, enrichment: link_only}\n'
        def ynab=Stub(YNABService) { getWalmartCategories() >> catalog }
        def output=new StringWriter()

        when:
        boolean saved=WalmartOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1,2\n2\nyes\n')),new PrintWriter(output,true))
        def config=new Yaml().load(file.text)

        then:
        saved
        config.amazon.email=='preserved'
        !config.walmart.enabled
        config.walmart.enrichment=='link_only'
        config.walmart.default_category_id=='b'
        config.walmart.categorization.categories==[[id:'a'],[id:'b']]
        output.toString().contains('Food / Same') && output.toString().contains('Home / Same')
        java.nio.file.Files.getPosixFilePermissions(file.toPath()).toString()=='[OWNER_READ, OWNER_WRITE]' ||
            java.nio.file.Files.getPosixFilePermissions(file.toPath()).size()==2
    }
    @Unroll
    def 'invalid selection or cancellation leaves original bytes: #answer'() {
        given:
        File file=new File(directory,'config.yml'); file.text='walmart: {enabled: false}\n'
        def ynab=Stub(YNABService) { getWalmartCategories() >> catalog }
        String before=file.text

        expect:
        !WalmartOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader(answer)),new PrintWriter(new StringWriter()))
        file.text==before

        where:
        answer << ['q\n','1,1\n','0\n','3\n','x\n','1\n2\n','1\n1\nno\n']
    }
    def 'category deletion after confirmation and concurrent config edits cannot overwrite config'() {
        given:
        File file=new File(directory,'config.yml'); file.text='walmart: {enabled: false}\n'
        def ynab=Stub(YNABService) { getWalmartCategories() >>> [catalog,[]] }

        when:
        WalmartOnboarding.selectCategories(file.toPath(),ynab,new BufferedReader(new StringReader('1\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        file.text=='walmart: {enabled: false}\n'

        when:
        def changed=Stub(YNABService) { getWalmartCategories() >> { file.text='other: changed\n'; catalog } }
        WalmartOnboarding.selectCategories(file.toPath(),changed,new BufferedReader(new StringReader('1\n1\nyes\n')),new PrintWriter(new StringWriter()))

        then:
        thrown(IllegalStateException)
        file.text=='other: changed\n'
    }
}
