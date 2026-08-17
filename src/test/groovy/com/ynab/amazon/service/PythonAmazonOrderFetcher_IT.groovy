package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import spock.lang.Specification
import spock.lang.TempDir

class PythonAmazonOrderFetcher_IT extends Specification {
    @TempDir File tempDir

    def "maps a fixture bridge child process and contains malformed output"() {
        given:
        File bridge = new File(tempDir, 'fixture_bridge.py')
        bridge.text = '''import sys
if '--bad' in sys.argv:
    print('not json')
else:
    print('{"schema_version":1,"orders":[{"order_number":"fixture","order_placed_date":"2026-08-01","grand_total":2.50,"items":[{"title":"item","price":2.50}]}]}')
'''
        def config = new Configuration(amazonPythonExecutable: 'python3', amazonPythonBridgeScript: bridge.absolutePath,
            amazonPythonConfigPath: tempDir.absolutePath, amazonPythonTimeoutSeconds: 5, amazonPythonMaxOutputBytes: 1024)

        expect:
        new PythonAmazonOrderFetcher(config).fetchOrders()*.orderId == ['fixture']

        when:
        bridge.text = "print('not json')\n"

        then:
        new PythonAmazonOrderFetcher(config).fetchOrders().empty
    }
}
