package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.TempDir

class GoWalmartOrderFetcher_IT extends Specification {
    @TempDir File directory
    def json=new ObjectMapper()

    def 'bounded real child validates minimal schema and drops failed partial payloads'() {
        given:
        File executable=new File(directory,'fixture-helper')
        Map root=[version:1,status:'ok',orders:[json.readValue(new File('walmart-helper/testdata/source-evidence.json'),Map)]]
        String encoded=json.writeValueAsString(root)
        String prefix='''#!/usr/bin/env python3
import sys, json, os
r=json.load(sys.stdin)
assert r['version']==1 and r['action']=='fetch' and r['max_pages']==10
assert not any(k.startswith(('AMAZON_','YNAB_','HF_','AWS_','PYTHON')) for k in os.environ)
'''
        executable.text=prefix+"print(${json.writeValueAsString(encoded)})\n"
        executable.setExecutable(true)
        def config=new Configuration(walmartHelperExecutable:executable.absolutePath,walmartSessionFile:new File(directory,'not-read').absolutePath,walmartTimeoutSeconds:1,walmartMaxOutputBytes:2048)

        when:
        def orders=new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        orders[0].items[0].quantity==1.25G
        orders[0].charges[0].cents==5500L
        orders[0].charges[0].key!=orders[0].charges[1].key
        orders[0].totalAmount==110G
        !orders[0].complete

        when:
        executable.text=prefix+"print(${json.writeValueAsString(encoded)})\nsys.exit(1)\n"
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        def failure=thrown(IllegalStateException)
        !failure.message.contains('Weighted')
        failure.message.contains('NON_ZERO_EXIT')

        when:
        root.orders[0].detail.data.order.groups_2101[0].items[0].priceInfo.linePrice.value=80.001
        executable.text=prefix+"print(${json.writeValueAsString(json.writeValueAsString(root))})\n"
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        thrown(IllegalStateException)

        when:
        executable.text=prefix+"print('x'*4096)\n"
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        thrown(IllegalStateException)

        when:
        executable.text=prefix+"import time\ntime.sleep(3)\n"
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        thrown(IllegalStateException)
    }

    def 'shared Go success envelope accepts empty history but missing or null orders stay invalid'() {
        given:
        String emptyEnvelope=new File('walmart-helper/testdata/empty-history.json').getText('UTF-8')
        File executable=new File(directory,'empty-helper')
        executable.setText('#!/usr/bin/env python3\nimport sys,json\njson.load(sys.stdin)\nprint('+json.writeValueAsString(emptyEnvelope)+')\n','UTF-8')
        executable.setExecutable(true)
        def config=new Configuration(walmartHelperExecutable:executable.absolutePath,
            walmartSessionFile:new File(directory,'not-read').absolutePath,walmartTimeoutSeconds:3,walmartMaxOutputBytes:1024)

        expect:
        new GoWalmartOrderFetcher(config).fetchOrders().empty

        when:
        executable.text='#!/usr/bin/env python3\nprint(\'{"version":1,"status":"ok"}\')\n'
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        def missing=thrown(IllegalStateException)
        missing.message.endsWith('INVALID_SCHEMA')

        when:
        executable.text='#!/usr/bin/env python3\nprint(\'{"version":1,"status":"ok","orders":null}\')\n'
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        def absent=thrown(IllegalStateException)
        absent.message.endsWith('INVALID_SCHEMA')
    }

    def 'packaged native helper rejects malformed input without requiring Go or accounts'() {
        given:
        String helper=System.getProperty('walmart.helper')

        expect:
        if (helper) {
            def result=new BoundedProcessRunner(GoWalmartOrderFetcher.ENVIRONMENT).runWithInput([helper],java.time.Duration.ofSeconds(3),1024,'{"version":999}')
            assert result.status==ProcessResult.Status.NON_ZERO_EXIT
            assert json.readValue(result.stdout,Map)==[version:1,status:'fetch_failed']
            assert !result.stderr
            byte[] binary=new File(helper).bytes
            String text=new String(binary, java.nio.charset.StandardCharsets.ISO_8859_1)
            assert !text.contains('OPENAI_API_KEY')
            assert !text.contains('YNAB_API_TOKEN')
            assert !text.contains('category_id')
            assert !text.contains('gpt-6')
            assert !text.contains('sk-')
        }
    }
}
