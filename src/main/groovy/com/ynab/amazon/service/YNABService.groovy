package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.YNABTransaction
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPatch
import org.apache.hc.client5.http.classic.methods.HttpPut
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Service class for interacting with the YNAB API
 */
class YNABService {
    private static final Logger logger = LoggerFactory.getLogger(YNABService.class)
    
    private final Configuration config
    private final ObjectMapper objectMapper
    private final CloseableHttpClient httpClient
    private final CloseableHttpClient walmartHttpClient
    
    YNABService(Configuration config) {
        this.config = config
        this.objectMapper = new ObjectMapper()
        this.httpClient = HttpClients.createDefault()
        // A lost PUT response has an unknown outcome; only journal/read-back may retry it.
        this.walmartHttpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()
    }
    
    /**
     * Fetch all transactions for the configured account
     */
    List<YNABTransaction> getTransactions() {
        try {
            def lookBackDate = LocalDate.now().minusDays(config.lookBackDays)
            String sinceDate = lookBackDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            logger.info("Looking back ${config.lookBackDays} days for YNAB transactions (since ${sinceDate})")
            String url = "${config.ynabBaseUrl}/budgets/${config.ynabBudgetId}/transactions?since_date=${sinceDate}"
            
            HttpGet request = new HttpGet(url)
            request.setHeader("Authorization", "Bearer ${config.ynabApiKey}")
            request.setHeader("Content-Type", "application/json")
            
            logger.debug("Fetching transactions from YNAB: ${url}")
            
            def response = httpClient.execute(request)
            def entity = response.getEntity()
            def responseBody = EntityUtils.toString(entity)
            
            if (response.getCode() != 200) {
                logger.error("Failed to fetch YNAB transactions. Status: ${response.getCode()}, Response: ${responseBody}")
                return []
            }
            logger.trace("YNAB Response Body: ${responseBody}")
            def jsonResponse = objectMapper.readTree(responseBody)
            def transactionsData = jsonResponse.get("data").get("transactions")
            
            List<YNABTransaction> transactions = []
            transactionsData.each { transactionJson ->
                YNABTransaction transaction = new YNABTransaction()
                transaction.id = transactionJson.get("id")?.asText()
                transaction.date = transactionJson.get("date")?.asText()
                transaction.amount = transactionJson.get("amount")?.asLong()
                transaction.memo = transactionJson.get("memo")?.asText()
                transaction.payee_name = transactionJson.get("payee_name")?.asText()
                transaction.payee_id = transactionJson.get('payee_id')?.isNull() ? null : transactionJson.get('payee_id')?.asText()
                transaction.flag_color = transactionJson.get('flag_color')?.isNull() ? null : transactionJson.get('flag_color')?.asText()
                transaction.category_name = transactionJson.get("category_name")?.asText()
                transaction.category_id = transactionJson.get('category_id')?.isNull() ? null : transactionJson.get('category_id')?.asText()
                transaction.subtransactions = transactionJson.get('subtransactions') ? objectMapper.convertValue(transactionJson.get('subtransactions'), List) : []
                transaction.account_id = transactionJson.get("account_id")?.asText()
                transaction.cleared = transactionJson.get("cleared")?.asText()
                transaction.approved = transactionJson.get("approved")?.asText()
                transaction.import_id = transactionJson.get("import_id")?.asText()
                transaction.transfer_account_id = transactionJson.get('transfer_account_id')?.isNull() ? null : transactionJson.get('transfer_account_id')?.asText()
                transaction.transfer_transaction_id = transactionJson.get("transfer_transaction_id")?.asText()
                transaction.matched_transaction_id = transactionJson.get("matched_transaction_id")?.asText()
                transaction.deleted = transactionJson.get("deleted")?.asText()
                
                transactions.add(transaction)
            }
            
            logger.info("Successfully fetched ${transactions.size()} transactions from YNAB")
            return transactions
            
        } catch (Exception e) {
            logger.error("Error fetching YNAB transactions", e)
            return []
        }
    }
    
    /**
     * Update a transaction's memo field
     */
    boolean updateTransactionMemo(String transactionId, String newMemo) {
        try {
            String url = "${config.ynabBaseUrl}/budgets/${config.ynabBudgetId}/transactions/${transactionId}"
            
            HttpPatch request = new HttpPatch(url)
            request.setHeader("Authorization", "Bearer ${config.ynabApiKey}")
            request.setHeader("Content-Type", "application/json")
            
            def updateData = [
                transaction: [
                    memo: newMemo
                ]
            ]
            
            String requestBody = objectMapper.writeValueAsString(updateData)
            request.setEntity(new StringEntity(requestBody))
            
            logger.debug("Updating transaction ${transactionId} with memo: ${newMemo}")
            
            def response = httpClient.execute(request)
            def entity = response.getEntity()
            def responseBody = EntityUtils.toString(entity)
            
            if (response.getCode() != 200) {
                logger.error("Failed to update YNAB transaction. Status: ${response.getCode()}, Response: ${responseBody}")
                return false
            }
            
            logger.info("Successfully updated transaction ${transactionId}")
            return true
            
        } catch (Exception e) {
            logger.error("Error updating YNAB transaction ${transactionId}", e)
            return false
        }
    }
    
    /**
     * Close HTTP client resources
     */
    Map getWalmartTransaction(String id) {
        walmartRequest('GET', "/transactions/${pathId(id)}").data.transaction as Map
    }

    String getWalmartPlanCurrency() {
        try {
            walmartRequest('GET', '/settings').data?.settings?.currency_format?.iso_code as String
        } catch (Exception ignored) {
            logger.warn('YNAB plan currency unavailable; Walmart matching and updates are disabled')
            null
        }
    }

    List<Map> getWalmartCategories() {
        def groups = walmartRequest('GET', '/categories').data.category_groups
        groups.collectMany { group ->
            group.categories.collect { category ->
                category + [group_name: group.name, group_hidden: group.hidden, group_deleted: group.deleted,
                            group_internal: group.internal]
            }
        }
    }

    Set<String> getWalmartBudgetAccounts() {
        walmartRequest('GET', '/accounts').data.accounts.findAll { it.on_budget == true && !it.deleted && !it.closed }*.id as Set
    }

    void putWalmartTransaction(String id, Map changes) {
        walmartRequest('PUT', "/transactions/${pathId(id)}", [transaction: changes])
    }

    private Map walmartRequest(String method, String suffix, Map body = null) {
        // Canonical current API uses /plans. Kept separate from Amazon's legacy API.
        String url = "${config.ynabBaseUrl}/plans/${pathId(config.ynabBudgetId)}${suffix}"
        def request = method == 'GET' ? new HttpGet(url) : new HttpPut(url)
        request.setHeader('Authorization', "Bearer ${config.ynabApiKey}")
        request.setHeader('Content-Type', 'application/json')
        if (body != null) request.entity = new StringEntity(objectMapper.writeValueAsString(body), org.apache.hc.core5.http.ContentType.APPLICATION_JSON)
        request.config = org.apache.hc.client5.http.config.RequestConfig.custom()
            .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(30)).setRedirectsEnabled(false).build()
        walmartHttpClient.execute(request).withCloseable { response ->
            if (response.code != 200) throw new IllegalStateException('YNAB Walmart request failed')
            String text = EntityUtils.toString(response.entity, 'UTF-8', 8388608)
            objectMapper.readValue(text, Map)
        }
    }

    private static String pathId(String id) {
        if (!(id ==~ /[A-Za-z0-9_-]{1,100}/)) throw new IllegalArgumentException('Invalid YNAB resource ID')
        id
    }

    void close() {
        try {
            httpClient.close()
            walmartHttpClient.close()
        } catch (Exception e) {
            logger.warn("Error closing HTTP client", e)
        }
    }
}
