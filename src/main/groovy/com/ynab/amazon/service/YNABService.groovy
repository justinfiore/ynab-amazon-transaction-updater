package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.YNABTransaction
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.HttpEntity
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPatch
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Service class for interacting with the YNAB API
 */
class YNABService {
    private static final Logger logger = LoggerFactory.getLogger(YNABService.class)
    
    private final Configuration config
    private final ObjectMapper objectMapper
    private final CloseableHttpClient httpClient
    
    YNABService(Configuration config) {
        this.config = config
        this.objectMapper = new ObjectMapper()
        this.httpClient = HttpClients.createDefault()
    }
    
    /**
     * Fetch all transactions for the configured account
     */
    List<YNABTransaction> getTransactions() {
        try {
            String url = "${config.ynabBaseUrl}/budgets/last-used/accounts/${config.ynabAccountId}/transactions"
            
            HttpGet request = new HttpGet(url)
            request.setHeader("Authorization", "Bearer ${config.ynabApiKey}")
            request.setHeader("Content-Type", "application/json")
            
            logger.debug("Fetching transactions from YNAB: ${url}")
            
            def response = httpClient.execute(request)
            def entity = response.getEntity()
            def responseBody = EntityUtils.toString(entity)
            
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error("Failed to fetch YNAB transactions. Status: ${response.getStatusLine().getStatusCode()}, Response: ${responseBody}")
                return []
            }
            
            def jsonResponse = objectMapper.readTree(responseBody)
            def transactionsData = jsonResponse.get("data").get("transactions")
            
            List<YNABTransaction> transactions = []
            transactionsData.each { transactionJson ->
                YNABTransaction transaction = new YNABTransaction()
                transaction.id = transactionJson.get("id")?.asText()
                transaction.date = transactionJson.get("date")?.asText()
                transaction.amount = transactionJson.get("amount")?.asBigDecimal()
                transaction.memo = transactionJson.get("memo")?.asText()
                transaction.payee_name = transactionJson.get("payee_name")?.asText()
                transaction.category_name = transactionJson.get("category_name")?.asText()
                transaction.account_id = transactionJson.get("account_id")?.asText()
                transaction.cleared = transactionJson.get("cleared")?.asText()
                transaction.approved = transactionJson.get("approved")?.asText()
                transaction.import_id = transactionJson.get("import_id")?.asText()
                transaction.transfer_account_id = transactionJson.get("transfer_account_id")?.asText()
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
            String url = "${config.ynabBaseUrl}/budgets/last-used/transactions/${transactionId}"
            
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
            
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error("Failed to update YNAB transaction. Status: ${response.getStatusLine().getStatusCode()}, Response: ${responseBody}")
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
    void close() {
        try {
            httpClient.close()
        } catch (Exception e) {
            logger.warn("Error closing HTTP client", e)
        }
    }
} 