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
        Map root=[version:1,status:'ok',orders:[[id:'123',date:'2026-11-20',url:'https://www.walmart.com/orders/details?orderId=123',currency:'USD',
            total_cents:1000,complete:true,items:[[id:'item',product_id:'p',title:'Weighted apples',quantity:'1.25',gross_cents:1000]],
            charges:[[key:'charge',payment:'VISA:1234',date:'2026-11-21',currency:'USD',cents:1000]],adjustments:[]]]]
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
        orders[0].charges[0].cents==1000L
        orders[0].totalAmount==10G

        when:
        executable.text=prefix+"print(${json.writeValueAsString(encoded)})\nsys.exit(1)\n"
        new GoWalmartOrderFetcher(config).fetchOrders()

        then:
        def failure=thrown(IllegalStateException)
        !failure.message.contains('Weighted')
        failure.message.contains('NON_ZERO_EXIT')

        when:
        root.orders[0].charges[0].cents=1000.1
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

    def 'packaged native helper rejects malformed input without requiring Go or accounts'() {
        given:
        String helper=System.getProperty('walmart.helper')

        expect:
        if (helper) {
            def result=new BoundedProcessRunner(GoWalmartOrderFetcher.ENVIRONMENT).runWithInput([helper],java.time.Duration.ofSeconds(3),1024,'{"version":999}')
            assert result.status==ProcessResult.Status.NON_ZERO_EXIT
            assert json.readValue(result.stdout,Map)==[version:1,status:'fetch_failed']
            assert !result.stderr
        }
    }
}
