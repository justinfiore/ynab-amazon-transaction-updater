package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import spock.lang.Specification

class WalmartService_UT extends Specification {
    def 'disabled Walmart fetching returns no orders'() {
        expect:
        new WalmartService(new Configuration()).getOrders() == []
    }

    def 'enabled Walmart fetching reports the removed implementation'() {
        when:
        new WalmartService(new Configuration(walmartEnabled: true)).getOrders()

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('Walmart fetching is unavailable')
        error.message.contains('walmart.enabled to false')
    }
}
