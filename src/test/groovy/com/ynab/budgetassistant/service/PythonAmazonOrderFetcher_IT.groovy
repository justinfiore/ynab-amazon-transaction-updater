package com.ynab.budgetassistant.service

import com.ynab.budgetassistant.config.Configuration
import spock.lang.Specification
import spock.lang.TempDir

class PythonAmazonOrderFetcher_IT extends Specification {
    @TempDir File tempDir

    def "maps a fixture bridge child process and contains malformed output"() {
        given:
        File bridge = new File(tempDir, 'fixture_bridge.py')
        bridge.text = '''import sys
import json
if '--bad' in sys.argv:
    print('not json')
else:
    orders = [{"order_number":"fixture","order_placed_date":"2026-08-01","grand_total":2.50,"items":[{"title":"item","price":2.50}]}]
    if '--account-only' not in sys.argv:
        orders.append({"order_number":"partner","order_placed_date":"2026-08-02","grand_total":7.25})
    print(json.dumps({"schema_version":1,"orders":orders}))
'''
        def config = new Configuration(amazonPythonExecutable: 'python3', amazonPythonBridgeScript: bridge.absolutePath,
            amazonPythonConfigPath: tempDir.absolutePath, amazonPythonTimeoutSeconds: 5, amazonPythonMaxOutputBytes: 1024)

        expect:
        new PythonAmazonOrderFetcher(config).fetchOrders()*.orderId == ['fixture', 'partner']

        when:
        config.amazonPythonIncludeHouseholdOrders = false

        then:
        new PythonAmazonOrderFetcher(config).fetchOrders()*.orderId == ['fixture']

        when:
        bridge.text = "print('not json')\n"

        then:
        new PythonAmazonOrderFetcher(config).fetchOrders().empty
    }
}
