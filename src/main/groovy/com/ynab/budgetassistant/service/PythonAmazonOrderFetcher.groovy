package com.ynab.budgetassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.budgetassistant.config.Configuration
import com.ynab.budgetassistant.model.AmazonOrder
import com.ynab.budgetassistant.model.AmazonOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration
import java.time.LocalDate

class PythonAmazonOrderFetcher implements AmazonOrderFetcher {
    private static final Logger logger = LoggerFactory.getLogger(PythonAmazonOrderFetcher.class)
    private static final ObjectMapper objectMapper = new ObjectMapper()

    private final Configuration config
    private final ProcessRunner processRunner
    private boolean loginRequired

    boolean isLoginRequired() { loginRequired }

    PythonAmazonOrderFetcher(Configuration config, ProcessRunner processRunner = new BoundedProcessRunner()) {
        this.config = config
        this.processRunner = processRunner
    }

    @Override
    List<AmazonOrder> fetchOrders() {
        loginRequired = false
        List<String> command = [config.amazonPythonExecutable, '-I', config.amazonPythonBridgeScript,
                                '--config-path', config.amazonPythonConfigPath,
                                '--look-back-days', String.valueOf(config.lookBackDays)]
        if (!config.amazonPythonIncludeHouseholdOrders) command.add('--account-only')
        if (config.isAmazonTransactionEvidenceEnabled()) command.add('--transaction-evidence')
        ProcessResult result = processRunner.run(command, Duration.ofSeconds(config.amazonPythonTimeoutSeconds), config.amazonPythonMaxOutputBytes)
        if (!result.success) {
            loginRequired = result.status == ProcessResult.Status.NON_ZERO_EXIT && result.exitCode == 3
            if (loginRequired) logger.warn('Amazon login required; run the configured bridge with --login and --config-path')
            else logger.warn("Python Amazon fetch failed (${result.status}, exit ${result.exitCode}); check runtime configuration and Amazon availability")
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
            logger.error('Unable to parse Python Amazon bridge output; expected a schema-v1 orders envelope')
            return []
        }
    }

    private AmazonOrder mapOrder(PythonBridgeOrder record, int index) {
        String orderNumber = text(record.orderNumber)
        String orderDate = text(record.orderPlacedDate)
        boolean eventOnly = record.paymentEvents && (!orderDate || record.grandTotal == null)
        if ((record.cancelled && !record.paymentEvents?.any { it.signed_cents > 0 }) || !orderNumber || (!eventOnly && (!orderDate || record.grandTotal == null))) {
            logger.warn("Skipping invalid or cancelled Python Amazon order at index ${index}${orderNumber ? " (${orderNumber})" : ''}")
            return null
        }
        try {
            if (orderDate) LocalDate.parse(orderDate)
            BigDecimal total = record.grandTotal
            if (total != null && (total < 0 || (total == 0 && !record.financialEvidence && !record.paymentEvents))) throw new IllegalArgumentException('grand_total must be positive without evidence')
            AmazonOrder order = new AmazonOrder(orderId: orderNumber, orderDate: orderDate, totalAmount: total?.negate(), isReturn: false)
            order.financialEvidence = record.financialEvidence ?: [:]
            order.paymentEvents = record.paymentEvents ?: []
            String payment = text(record.paymentMethod)
            String lastFour = text(record.paymentMethodLast4)
            order.paymentMethod = payment && lastFour ? "${payment} (${lastFour})" : payment
            // Matching requires a product title; retain the order and use its
            // empty-items fallback if upstream supplied no usable item names.
            record.items?.findAll { it != null && text(it.title) }?.each { PythonBridgeItem item -> order.addItem(mapItem(item)) }
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

}
