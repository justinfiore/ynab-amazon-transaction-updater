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
    private final CloseableHttpClient guardedHttpClient
    private boolean workflowCacheEnabled = false
    private boolean rateLimited = false
    private Map<String, Integer> requestCounts = [:].withDefault { 0 }
    private List<Map> categoryCache
    private Set<String> accountCache
    private Map<String, Map> learningCache
    
    YNABService(Configuration config) {
        this.config = config
        this.objectMapper = new ObjectMapper()
        this.httpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()
        // A lost PUT response has an unknown outcome; only journal/read-back may retry it.
        this.guardedHttpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()
    }

    void beginWorkflow() {
        workflowCacheEnabled = true
        categoryCache = null
        accountCache = null
        learningCache = null
    }

    private void countRequest(String name) {
        if (rateLimited) throw new IllegalStateException('YNAB rate limit reached; defer remaining requests until the rolling window permits them')
        requestCounts[name]++
    }

    private void checkStatus(int status) {
        if (status == 429) {
            rateLimited = true
            logger.error('YNAB rate limit reached (429); stopping YNAB requests for this run. Try again after the rolling one-hour window permits requests.')
        }
        if (status != 200) throw new IllegalStateException("YNAB request failed with HTTP ${status}")
    }

    void logRequestCounts() {
        logger.info('YNAB request attempts by endpoint: {}', new TreeMap(requestCounts))
    }

    void checkRateLimit() {
        if (rateLimited) throw new IllegalStateException('YNAB rate limit reached; the workflow did not complete')
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
            
            countRequest('GET transactions')
            def response = httpClient.execute(request)
            checkStatus(response.getCode())
            def entity = response.getEntity()
            def responseBody = EntityUtils.toString(entity)
            
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
            throw new IllegalStateException('Unable to fetch YNAB transactions', e)
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
            
            countRequest('PATCH transaction')
            def response = httpClient.execute(request)
            checkStatus(response.getCode())
            def entity = response.getEntity()
            def responseBody = EntityUtils.toString(entity)
            
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
        getTransaction(id)
    }

    Map getTransaction(String id) {
        guardedRequest('GET', "/transactions/${pathId(id)}").data.transaction as Map
    }

    /** Full-plan snapshot for historical learning; write-boundary checks remain individual. */
    Map<String, Map> getLearningTransactions() {
        if (workflowCacheEnabled && learningCache != null) return learningCache
        def transactions = guardedRequest('GET', '/transactions').data?.transactions
        if (!(transactions instanceof List)) throw new IllegalStateException('Invalid YNAB transaction collection')
        if (transactions.any { !(it instanceof Map) || !(it.id instanceof String) || !it.id } ||
            transactions*.id.toSet().size() != transactions.size()) throw new IllegalStateException('Invalid YNAB transaction collection')
        Map<String, Map> snapshot = transactions.collectEntries { Map transaction -> [(transaction.id): transaction] }
        if (workflowCacheEnabled) learningCache = snapshot
        snapshot
    }

    String getWalmartPlanCurrency() {
        getPlanCurrency()
    }

    String getPlanCurrency() {
        try {
            guardedRequest('GET', '/settings').data?.settings?.currency_format?.iso_code as String
        } catch (Exception ignored) {
            logger.warn('YNAB plan currency unavailable; financial matching and updates are disabled')
            null
        }
    }

    List<Map> getWalmartCategories() {
        getCategoryCatalog()
    }

    List<Map> getCategoryCatalog() {
        if (workflowCacheEnabled && categoryCache != null) return categoryCache
        def groups = guardedRequest('GET', '/categories').data.category_groups
        List<Map> result = groups.collectMany { group ->
            group.categories.collect { category ->
                category + [group_name: group.name, group_hidden: group.hidden, group_deleted: group.deleted,
                            group_internal: group.internal]
            }
        }
        if (workflowCacheEnabled) categoryCache = result
        result
    }

    Set<String> getWalmartBudgetAccounts() {
        getBudgetAccounts()
    }

    Set<String> getBudgetAccounts() {
        if (workflowCacheEnabled && accountCache != null) return accountCache
        Set<String> result = guardedRequest('GET', '/accounts').data.accounts.findAll { it.on_budget == true && !it.deleted && !it.closed }*.id as Set
        if (workflowCacheEnabled) accountCache = result
        result
    }

    void putWalmartTransaction(String id, Map changes) {
        putTransaction(id, changes)
    }

    void putTransaction(String id, Map changes) {
        learningCache = null // A failed or lost response may still have changed the server.
        guardedRequest('PUT', "/transactions/${pathId(id)}", [transaction: changes])
    }

    Set<String> updateTransactionMemos(List<Map> updates) {
        if (!updates) return [] as Set
        learningCache = null
        Map response = guardedRequest('PATCH', '/transactions', [transactions: updates])
        def ids = response.data?.transaction_ids
        if (!(ids instanceof List)) throw new IllegalStateException('Invalid YNAB batch response')
        ids as Set<String>
    }

    private Map guardedRequest(String method, String suffix, Map body = null) {
        // Canonical current API uses /plans. Kept separate from Amazon's legacy API.
        String url = "${config.ynabBaseUrl}/plans/${pathId(config.ynabBudgetId)}${suffix}"
        def request = method == 'GET' ? new HttpGet(url) : method == 'PATCH' ? new HttpPatch(url) : new HttpPut(url)
        request.setHeader('Authorization', "Bearer ${config.ynabApiKey}")
        request.setHeader('Content-Type', 'application/json')
        if (body != null) request.entity = new StringEntity(objectMapper.writeValueAsString(body), org.apache.hc.core5.http.ContentType.APPLICATION_JSON)
        request.config = org.apache.hc.client5.http.config.RequestConfig.custom()
            .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(30)).setRedirectsEnabled(false).build()
        String endpoint = suffix.startsWith('/transactions/') ? '/transactions/{id}' : suffix
        countRequest("${method} ${endpoint}".toString())
        guardedHttpClient.execute(request).withCloseable { response ->
            checkStatus(response.code)
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
            guardedHttpClient.close()
        } catch (Exception e) {
            logger.warn("Error closing HTTP client", e)
        }
    }
}
