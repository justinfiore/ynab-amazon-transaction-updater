package com.ynab.budgetassistant.service

import com.ynab.budgetassistant.config.Configuration
import spock.lang.Specification

class WalmartService_UT extends Specification {
    def 'disabled Walmart fetching returns no orders'() {
        expect:
        new WalmartService(new Configuration()).getOrders() == []
    }

    def 'enabled Walmart fetching requires the helper and imported session'() {
        when:
        new WalmartService(new Configuration(walmartEnabled: true)).getOrders()

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('Walmart helper/session unavailable')
        error.message.contains('import a session')
    }
}
