package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import spock.lang.Specification

class PythonAmazonOrderFetcher_UT extends Specification {
    private final Configuration config = new Configuration(
        amazonPythonExecutable: 'python', amazonPythonBridgeScript: 'bridge.py', amazonPythonConfigPath: 'upstream.yml',
        amazonPythonTimeoutSeconds: 1, amazonPythonMaxOutputBytes: 1024, lookBackDays: 7)

    def "maps schema-v1 bridge fixture with expense sign and default quantity"() {
        given:
        def fetcher = new PythonAmazonOrderFetcher(config, Stub(ProcessRunner))
        String payload = resource('python/complete-envelope.json')

        when:
        def orders = fetcher.mapEnvelope(payload)

        then:
        orders.size() == 1
        with(orders[0]) {
            orderId == '123-4567890-1234567'
            orderDate == '2026-08-01'
            totalAmount == -42.17G
            paymentMethod == 'Visa (1234)'
            !isReturn
            items[0].quantity == 1
            items[0].asin == 'B000000000'
        }
    }

    def "skips invalid records and deterministically keeps the first duplicate"() {
        expect:
        new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(resource('python/invalid-and-duplicate-envelope.json'))*.orderId == ['one']
    }

    def "rejects malformed and unsupported envelopes"() {
        expect:
        new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(payload).empty

        where:
        payload << ['not json', '{"schema_version":2,"orders":[]}', '{"schema_version":1}']
    }

    def "contains process failures and does not parse their output"() {
        given:
        def runner = Stub(ProcessRunner) {
            run(_, _, _) >> new ProcessResult(ProcessResult.Status.NON_ZERO_EXIT, 2, '{"schema_version":1,"orders":[]}', 'password=private')
        }

        expect:
        new PythonAmazonOrderFetcher(config, runner).fetchOrders().empty
    }

    private static String resource(String name) {
        PythonAmazonOrderFetcher_UT.getResource("/${name}").text
    }
}
