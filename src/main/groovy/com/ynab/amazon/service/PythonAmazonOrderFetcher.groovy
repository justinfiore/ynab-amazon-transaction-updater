package com.ynab.amazon.service

import com.fasterxml.jackson.databind.JsonNode
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
            JsonNode envelope = objectMapper.readTree(output)
            if (envelope == null || envelope.path('schema_version').asInt(-1) != 1 || !envelope.path('orders').isArray()) {
                logger.error('Python Amazon bridge returned an unsupported or malformed schema-v1 envelope')
                return []
            }
            Map<String, AmazonOrder> orders = new LinkedHashMap<>()
            envelope.path('orders').eachWithIndex { JsonNode record, int index ->
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

    private AmazonOrder mapOrder(JsonNode record, int index) {
        String orderNumber = text(record, 'order_number')
        String orderDate = text(record, 'order_placed_date')
        JsonNode grandTotal = record.path('grand_total')
        if (record.path('cancelled').asBoolean(false) || !orderNumber || !orderDate || !grandTotal.isNumber()) {
            logger.warn("Skipping invalid or cancelled Python Amazon order at index ${index}${orderNumber ? " (${orderNumber})" : ''}")
            return null
        }
        try {
            LocalDate.parse(orderDate)
            BigDecimal total = grandTotal.decimalValue()
            if (total <= 0) throw new IllegalArgumentException('grand_total must be positive')
            AmazonOrder order = new AmazonOrder(orderId: orderNumber, orderDate: orderDate, totalAmount: total.negate(), isReturn: false)
            String payment = text(record, 'payment_method')
            String lastFour = record.path('payment_method_last_4').isMissingNode() || record.path('payment_method_last_4').isNull() ? null : record.path('payment_method_last_4').asText()
            order.paymentMethod = payment && lastFour ? "${payment} (${lastFour})" : payment
            if (record.path('items').isArray()) record.path('items').each { JsonNode item -> order.addItem(mapItem(item)) }
            return order
        } catch (Exception e) {
            logger.warn("Skipping invalid Python Amazon order at index ${index}${orderNumber ? " (${orderNumber})" : ''}")
            return null
        }
    }

    private static AmazonOrderItem mapItem(JsonNode item) {
        new AmazonOrderItem(title: text(item, 'title'), asin: text(item, 'asin'),
            price: item.path('price').isNumber() ? item.path('price').decimalValue() : null,
            quantity: item.path('quantity').isInt() && item.path('quantity').asInt() > 0 ? item.path('quantity').asInt() : 1)
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field)
        value.isTextual() && value.asText().trim() ? value.asText().trim() : null
    }

    private static String sanitize(String value) {
        if (!value) return 'no diagnostic provided'
        value.replaceAll(/(?i)(password|token|cookie|secret)\s*[:=]\s*\S+/, '$1=[redacted]').replaceAll(/[\r\n]+/, ' ').take(500)
    }
}
