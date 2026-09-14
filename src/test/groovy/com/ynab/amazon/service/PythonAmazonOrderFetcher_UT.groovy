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

    def "contains every typed process failure"() {
        given:
        def runner = Stub(ProcessRunner) { run(_, _, _) >> new ProcessResult(status, 2, completeEnvelope(), 'password=private') }

        expect:
        new PythonAmazonOrderFetcher(config, runner).fetchOrders().empty

        where:
        status << [ProcessResult.Status.START_FAILED, ProcessResult.Status.NON_ZERO_EXIT,
                   ProcessResult.Status.TIMED_OUT, ProcessResult.Status.INTERRUPTED,
                   ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED]
    }

    def "handles complete minimal and invalid records independently"() {
        expect:
        new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(payload)*.orderId == expected

        where:
        payload                                                                                                                                            || expected
        completeEnvelope()                                                                                                                                 || ['complete']
        '{"schema_version":1,"orders":[{"order_number":"minimal","order_placed_date":"2026-01-01","grand_total":1,"items":[{}]}]}'                 || ['minimal']
        '{"schema_version":1,"orders":[]}'                                                                                                               || []
        '{"schema_version":1,"orders":[{"order_placed_date":"2026-01-01","grand_total":1}]}'                                                       || []
        '{"schema_version":1,"orders":[{"order_number":"missing-date","grand_total":1}]}'                                                         || []
        '{"schema_version":1,"orders":[{"order_number":"missing-total","order_placed_date":"2026-01-01"}]}'                                      || []
        '{"schema_version":1,"orders":[{"order_number":"cancelled","order_placed_date":"2026-01-01","grand_total":1,"cancelled":true}]}'        || []
        '{"schema_version":1,"orders":[{"order_number":"bad-total","order_placed_date":"2026-01-01","grand_total":-1}]}'                         || []
        '{"schema_version":1,"orders":[{"order_number":"bad-date","order_placed_date":"bad","grand_total":1}]}'                                  || []
    }

    def "maps minimal item fields with quantity default and optional payment"() {
        when:
        def order = new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(
            '{"schema_version":1,"orders":[{"order_number":"minimal","order_placed_date":"2026-01-01","grand_total":1,"items":[{}]}]}')[0]

        then:
        order.totalAmount == -1G
        !order.paymentMethod
        order.items[0].quantity == 1
        !order.items[0].title
        !order.items[0].asin
    }

    def "bounds and redacts diagnostics"() {
        when:
        def method = PythonAmazonOrderFetcher.getDeclaredMethod('sanitize', String)
        method.accessible = true
        String diagnostic = method.invoke(null, "password=private token:secret " + ('x' * 600))

        then:
        diagnostic.contains('password=[redacted]')
        diagnostic.contains('token=[redacted]')
        !diagnostic.contains('private')
        !diagnostic.contains('secret')
        diagnostic.size() <= 500
    }

    private static String completeEnvelope() {
        '{"schema_version":1,"orders":[{"order_number":"complete","order_placed_date":"2026-01-01","grand_total":2.50,"payment_method":"Visa","payment_method_last_4":1234,"items":[{"title":"item","asin":"ASIN","price":2.50}]}]}'
    }

    private static String resource(String name) {
        PythonAmazonOrderFetcher_UT.getResource("/${name}").text
    }
}
