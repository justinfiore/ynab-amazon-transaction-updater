package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import spock.lang.Specification

class EmailAmazonOrderFetcher_Characterization_UT extends Specification {
    def "parses a regular email using the established negative expense sign"() {
        given:
        def fetcher = new EmailAmazonOrderFetcher(new Configuration())
        def message = regularMessage('Order #123-4567890-1234567\nTotal: $12.34 USD\n* Widget Quantity: 1 12.34 USD')

        when:
        AmazonOrder order = invoke(fetcher, 'parseOrderFromEmail', message)

        then:
        order.orderId == '123-4567890-1234567'
        order.totalAmount == -12.34G
        !order.isReturn
        order.items*.title == ['Widget']
    }

    def "uses configured lookback plus the established two-day email buffer"() {
        given:
        def config = new Configuration(lookBackDays: 7)

        when:
        Date searchStart = new EmailAmazonOrderFetcher(config).emailSearchStartDate()

        then:
        Math.abs((new Date().time - searchStart.time) - 9 * 24 * 60 * 60 * 1000) < 2000
    }

    def "merges duplicate regular orders without replacing established fields"() {
        given:
        def existing = new AmazonOrder(orderId: 'same', orderDate: '2026-01-01', totalAmount: -1G, items: null)
        def duplicate = new AmazonOrder(orderId: 'same', orderDate: '2026-01-02', totalAmount: -2G, items: [new AmazonOrderItem(title: 'item', price: 2G, quantity: 1)])

        when:
        EmailAmazonOrderFetcher.mergeOrder(existing, duplicate)

        then:
        existing.orderDate == '2026-01-01'
        existing.totalAmount == -1G
        existing.items*.title == ['item']
    }

    def "contains message parsing and localhost connection errors"() {
        given:
        def fetcher = new EmailAmazonOrderFetcher(new Configuration())
        Message malformed = Mock()
        malformed.getSubject() >> 'bad'
        malformed.getFrom() >> [new InternetAddress('order-confirmation@amazon.com')]
        malformed.getContent() >> { throw new IllegalStateException('malformed fixture') }
        def failingConnection = new EmailAmazonOrderFetcher(new Configuration(amazonEmail: 'user@example.com', amazonEmailPassword: 'app-password', imapHost: '127.0.0.1', imapPort: 1))

        expect:
        invoke(fetcher, 'parseOrderFromEmail', malformed) == null
        failingConnection.fetchOrders().empty
    }

    private static MimeMessage regularMessage(String content) {
        def message = new MimeMessage(Session.getInstance(new Properties()))
        message.setFrom(new InternetAddress('order-confirmation@amazon.com'))
        message.setSubject('Your Amazon order')
        message.setSentDate(new Date())
        message.setText(content)
        message
    }

    private static Object invoke(Object target, String name, Object argument) {
        def method = target.class.getDeclaredMethod(name, Message.class)
        method.accessible = true
        method.invoke(target, argument)
    }
}
