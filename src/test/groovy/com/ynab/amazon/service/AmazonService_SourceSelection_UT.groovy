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
}
