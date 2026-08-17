package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import spock.lang.Specification
import spock.lang.TempDir

class AmazonService_SourceSelection_UT extends Specification {
    @TempDir File tempDir

    def "uses injected fetcher and appends CSV orders"() {
        given:
        File csv = new File(tempDir, 'orders.csv')
        csv.text = 'Order ID,Order Date,Title,Price,Quantity\ncsv,2026-08-01,CSV item,3.00,1\n'
        def config = new Configuration(amazonCsvFilePath: csv.path)
        def fetcher = Mock(AmazonOrderFetcher)

        when:
        def orders = new AmazonService(config, fetcher).getOrders()

        then:
        1 * fetcher.fetchOrders() >> [new AmazonOrder(orderId: 'automatic')]
        orders*.orderId == ['automatic', 'csv']
    }

    def "CSV-only configuration does not construct or invoke an automatic source"() {
        given:
        File csv = new File(tempDir, 'orders.csv')
        csv.text = 'Order ID,Order Date,Title,Price,Quantity\ncsv,2026-08-01,CSV item,3.00,1\n'

        expect:
        new AmazonService(new Configuration(amazonCsvFilePath: csv.path)).getOrders()*.orderId == ['csv']
    }

    def "selects the email implementation by default"() {
        expect:
        selected(new AmazonService(new Configuration(amazonEmail: 'email@example.com', amazonEmailPassword: 'app-password'))) instanceof EmailAmazonOrderFetcher
    }

    def "selects the Python implementation only when configured"() {
        given:
        def config = new Configuration(amazonOrderFetcher: Configuration.AMAZON_FETCHER_PYTHON,
            amazonPythonExecutable: 'python3', amazonPythonBridgeScript: 'bridge.py', amazonPythonConfigPath: 'session.yml')

        expect:
        selected(new AmazonService(config)) instanceof PythonAmazonOrderFetcher
    }

    def "returns an empty aggregate without downstream error when all sources are empty"() {
        given:
        File csv = new File(tempDir, 'empty.csv')
        csv.text = 'Order ID,Order Date,Title,Price,Quantity\n'
        def fetcher = Stub(AmazonOrderFetcher) { fetchOrders() >> [] }

        expect:
        new AmazonService(new Configuration(amazonCsvFilePath: csv.path), fetcher).getOrders().empty
    }

    private static AmazonOrderFetcher selected(AmazonService service) {
        def field = AmazonService.getDeclaredField('orderFetcher')
        field.accessible = true
        field.get(service) as AmazonOrderFetcher
    }
}
