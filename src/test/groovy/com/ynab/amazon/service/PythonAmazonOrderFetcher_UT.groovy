package com.ynab.amazon.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.ynab.amazon.config.Configuration
import org.slf4j.LoggerFactory
import spock.lang.Specification

class PythonAmazonOrderFetcher_UT extends Specification {
    private final Configuration config = new Configuration(
        amazonPythonExecutable: 'python', amazonPythonBridgeScript: 'bridge.py', amazonPythonConfigPath: 'upstream.yml',
        amazonPythonTimeoutSeconds: 1, amazonPythonMaxOutputBytes: 1024, lookBackDays: 7)

    def "maps schema-v1 bridge fixture with expense sign and default quantity"() {
        when:
        def orders = new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(resource('python/complete-envelope.json'))

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

    def "contains every typed process failure without parsing its output"() {
        given:
        def runner = Stub(ProcessRunner) { run(_, _, _) >> new ProcessResult(status, 2, completeEnvelope(), 'upstream failure') }

        expect:
        new PythonAmazonOrderFetcher(config, runner).fetchOrders().empty

        where:
        status << [ProcessResult.Status.START_FAILED, ProcessResult.Status.NON_ZERO_EXIT,
                   ProcessResult.Status.TIMED_OUT, ProcessResult.Status.INTERRUPTED,
                   ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED, ProcessResult.Status.OUTPUT_READ_FAILED]
    }

    def "handles complete minimal and invalid records independently"() {
        expect:
        new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(payload)*.orderId == expected

        where:
        payload || expected
        completeEnvelope() || ['complete']
        '{"schema_version":1,"orders":[{"order_number":"minimal","order_placed_date":"2026-01-01","grand_total":1,"items":[{}]}]}' || ['minimal']
        '{"schema_version":1,"orders":[]}' || []
        '{"schema_version":1,"orders":[{"order_placed_date":"2026-01-01","grand_total":1}]}' || []
        '{"schema_version":1,"orders":[{"order_number":"missing-date","grand_total":1}]}' || []
        '{"schema_version":1,"orders":[{"order_number":"missing-total","order_placed_date":"2026-01-01"}]}' || []
        '{"schema_version":1,"orders":[{"order_number":"cancelled","order_placed_date":"2026-01-01","grand_total":1,"cancelled":true}]}' || []
        '{"schema_version":1,"orders":[{"order_number":"bad-total","order_placed_date":"2026-01-01","grand_total":-1}]}' || []
        '{"schema_version":1,"orders":[{"order_number":"bad-date","order_placed_date":"bad","grand_total":1}]}' || []
    }

    def "maps minimal item fields with quantity default and optional payment"() {
        when:
        def order = new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(
            '{"schema_version":1,"orders":[{"order_number":"minimal","order_placed_date":"2026-01-01","grand_total":1,"items":[{"title":"Product"}]}]}')[0]

        then:
        order.totalAmount == -1G
        !order.paymentMethod
        order.items[0].quantity == 1
        order.items[0].title == 'Product'
        !order.items[0].asin
    }

    def "title-less items cannot crash matching and named items are retained"() {
        given:
        def payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString([schema_version: 1, orders: [
            [order_number: 'partial', order_placed_date: '2026-01-01', grand_total: 1, items: items]]])
        def transaction = new com.ynab.amazon.model.YNABTransaction(
            id: 'expense', payee_name: 'AMAZON.COM', amount: -1000, date: '2026-01-01')

        when:
        def orders = new PythonAmazonOrderFetcher(config, Stub(ProcessRunner)).mapEnvelope(payload)
        def matches = new TransactionMatcher().findMatches([transaction], orders)

        then:
        orders.size() == 1
        orders[0].items*.title == titles
        matches.size() == 1
        matches[0].isHighConfidence()
        matches[0].proposedMemo.contains(expectedMemo)

        where:
        items                                                  | titles       | expectedMemo
        [[:]]                                                  | []           | "Couldn't identify items"
        [null, [title: '  '], [title: 'Useful item'], [:]]       | ['Useful item'] | 'Useful item'
    }

    def "failure logs never disclose upstream credential diagnostics"() {
        given:
        Logger logger = (Logger) LoggerFactory.getLogger(PythonAmazonOrderFetcher)
        def appender = new ListAppender()
        appender.start()
        logger.addAppender(appender)
        def runner = Stub(ProcessRunner) {
            run(_, _, _) >> new ProcessResult(ProcessResult.Status.NON_ZERO_EXIT, 1, '',
                '{"password":"dummy-password","cookie":"dummy-cookie","otp_secret_key":"dummy-otp"}', 'dummy-detail')
        }

        when:
        new PythonAmazonOrderFetcher(config, runner).fetchOrders()

        then:
        String logged = appender.list*.formattedMessage.join('\n')
        logged.contains('NON_ZERO_EXIT')
        !logged.contains('dummy-')

        cleanup:
        logger.detachAppender(appender)
        appender.stop()
    }

    def "only explicit authentication exits require login and success clears the state"() {
        given:
        def runner = Stub(ProcessRunner) {
            run(_, _, _) >>> [new ProcessResult(status, exitCode, '', ''),
                              new ProcessResult(ProcessResult.Status.SUCCESS, 0, completeEnvelope(), '')]
        }
        def fetcher = new PythonAmazonOrderFetcher(config, runner)

        when:
        def failedOrders = fetcher.fetchOrders()
        boolean loginRequired = fetcher.isLoginRequired()
        def recoveredOrders = fetcher.fetchOrders()

        then:
        failedOrders.empty
        loginRequired == expected
        recoveredOrders*.orderId == ['complete']
        !fetcher.isLoginRequired()

        where:
        status                                         | exitCode || expected
        ProcessResult.Status.NON_ZERO_EXIT             | 3        || true
        ProcessResult.Status.NON_ZERO_EXIT             | 1        || false
        ProcessResult.Status.TIMED_OUT                 | 3        || false
        ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED      | 3        || false
    }

    private static String completeEnvelope() {
        '{"schema_version":1,"orders":[{"order_number":"complete","order_placed_date":"2026-01-01","grand_total":2.50,"payment_method":"Visa","payment_method_last_4":1234,"items":[{"title":"item","asin":"ASIN","price":2.50}]}]}'
    }

    private static String resource(String name) {
        PythonAmazonOrderFetcher_UT.getResource("/${name}").text
    }
}
