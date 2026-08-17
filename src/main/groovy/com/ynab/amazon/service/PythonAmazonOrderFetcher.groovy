package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration
import java.time.LocalDate

class PythonAmazonOrderFetcher implements AmazonOrderFetcher {
    private static final Logger logger = LoggerFactory.getLogger(PythonAmazonOrderFetcher.class)
    private static final ObjectMapper objectMapper = new ObjectMapper()

    private final Configuration config
    private final ProcessRunner processRunner

    PythonAmazonOrderFetcher(Configuration config, ProcessRunner processRunner = new BoundedProcessRunner()) {
        this.config = config
        this.processRunner = processRunner
    }

    @Override
    List<AmazonOrder> fetchOrders() {
        List<String> command = [config.amazonPythonExecutable, config.amazonPythonBridgeScript,
                                '--config-path', config.amazonPythonConfigPath,
                                '--look-back-days', String.valueOf(config.lookBackDays)]
        ProcessResult result = processRunner.run(command, Duration.ofSeconds(config.amazonPythonTimeoutSeconds), config.amazonPythonMaxOutputBytes)
        if (!result.success) {
            logger.warn("Python Amazon fetch failed (${result.status}${result.exitCode >= 0 ? ", exit ${result.exitCode}" : ''}): ${sanitize(result.stderr ?: result.detail)}")
            return []
        }
        return mapEnvelope(result.stdout)
    }

    List<AmazonOrder> mapEnvelope(String output) {
        try {
            PythonBridgeEnvelope envelope = objectMapper.readValue(output, PythonBridgeEnvelope)
            if (envelope == null || envelope.schemaVersion != 1 || envelope.orders == null) {
                logger.error('Python Amazon bridge returned an unsupported or malformed schema-v1 envelope')
                return []
            }
            Map<String, AmazonOrder> orders = new LinkedHashMap<>()
            envelope.orders.eachWithIndex { PythonBridgeOrder record, int index ->
                AmazonOrder order = mapOrder(record, index)
                if (order != null) {
                    if (orders.containsKey(order.orderId)) logger.warn("Skipping duplicate Python Amazon order ${order.orderId}")
                    else orders[order.orderId] = order
                }
            }
            return new ArrayList<>(orders.values())
        } catch (Exception e) {
            logger.error("Unable to parse Python Amazon bridge output: ${sanitize(e.message)}")
            return []
        }
    }

    private AmazonOrder mapOrder(PythonBridgeOrder record, int index) {
        String orderNumber = text(record.orderNumber)
        String orderDate = text(record.orderPlacedDate)
        if (record.cancelled || !orderNumber || !orderDate || record.grandTotal == null) {
            logger.warn("Skipping invalid or cancelled Python Amazon order at index ${index}${orderNumber ? " (${orderNumber})" : ''}")
            return null
        }
        try {
            LocalDate.parse(orderDate)
            BigDecimal total = record.grandTotal
            if (total <= 0) throw new IllegalArgumentException('grand_total must be positive')
            AmazonOrder order = new AmazonOrder(orderId: orderNumber, orderDate: orderDate, totalAmount: total.negate(), isReturn: false)
            String payment = text(record.paymentMethod)
            String lastFour = text(record.paymentMethodLast4)
            order.paymentMethod = payment && lastFour ? "${payment} (${lastFour})" : payment
            record.items?.each { PythonBridgeItem item -> order.addItem(mapItem(item)) }
            return order
        } catch (Exception e) {
            logger.warn("Skipping invalid Python Amazon order at index ${index}${orderNumber ? " (${orderNumber})" : ''}")
            return null
        }
    }

    private static AmazonOrderItem mapItem(PythonBridgeItem item) {
        new AmazonOrderItem(title: text(item.title), asin: text(item.asin), price: item.price,
            quantity: item.quantity != null && item.quantity > 0 ? item.quantity : 1)
    }

    private static String text(String value) {
        value?.trim() ?: null
    }

    private static String sanitize(String value) {
        if (!value) return 'no diagnostic provided'
        value.replaceAll(/(?i)(password|token|cookie|secret)\s*[:=]\s*\S+/, '$1=[redacted]').replaceAll(/[\r\n]+/, ' ').take(500)
    }
}
