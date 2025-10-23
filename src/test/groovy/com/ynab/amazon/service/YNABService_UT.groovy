package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.YNABTransaction
import org.apache.http.HttpEntity
import org.apache.http.StatusLine
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPatch
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * Test class for YNABService
 */
class YNABService_UT extends Specification {

    Configuration config
    CloseableHttpClient httpClient
    YNABService service
    
    def setup() {
        // Create real configuration
        config = new Configuration()
        config.ynabApiKey = "test-api-key"
        config.ynabBudgetId = "test-budget-id"
        config.ynabBaseUrl = "https://api.ynab.com/v1"
        config.lookBackDays = 30
        
        // Create a mock HTTP client
        httpClient = Mock(CloseableHttpClient)
        
        // Create YNABService with overridden httpClient
        service = new YNABService(config)
        def field = YNABService.class.getDeclaredField("httpClient")
        field.setAccessible(true)
        field.set(service, httpClient)
    }
    
    def "getTransactions should fetch and parse transactions from the YNAB API"() {
        given: "a successful API response"
        def jsonResponse = """
        {
          "data": {
            "transactions": [
              {
                "id": "tx1",
                "date": "2023-05-15",
                "amount": 25990,
                "memo": "Test memo",
                "payee_name": "AMAZON.COM",
                "cleared": "cleared",
                "approved": true
              },
              {
                "id": "tx2",
                "date": "2023-05-16",
                "amount": 49990,
                "memo": null,
                "payee_name": "AMAZON MARKETPLACE",
                "cleared": "cleared",
                "approved": true
              }
            ]
          }
        }
        """
        
        and: "the HTTP client is set up to return the response"
        def response = Mock(CloseableHttpResponse)
        def statusLine = Mock(StatusLine)
        def entity = Mock(HttpEntity)
        
        statusLine.getStatusCode() >> 200
        entity.getContent() >> new ByteArrayInputStream(jsonResponse.bytes)
        response.getStatusLine() >> statusLine
        response.getEntity() >> entity
        httpClient.execute(_ as HttpUriRequest) >> response
        
        when: "getTransactions is called"
        def result = service.getTransactions()
        
        then: "transactions are properly parsed"
        result.size() == 2
        result[0].id == "tx1"
        result[0].amount == 25990
        result[0].memo == "Test memo"
    }
    
    def "updateTransactionMemo should update transaction memos"() {
        given: "a successful API response"
        def jsonResponse = """
        {
          "data": {
            "transaction": {
              "id": "tx1",
              "memo": "Updated memo"
            }
          }
        }
        """
        
        and: "the HTTP client is set up to return the response"
        def response = Mock(CloseableHttpResponse)
        def statusLine = Mock(StatusLine)
        def entity = Mock(HttpEntity)
        
        statusLine.getStatusCode() >> 200
        entity.getContent() >> new ByteArrayInputStream(jsonResponse.bytes)
        response.getStatusLine() >> statusLine
        response.getEntity() >> entity
        httpClient.execute(_ as HttpUriRequest) >> response
        
        when: "updateTransactionMemo is called"
        def result = service.updateTransactionMemo("tx1", "Updated memo")
        
        then: "the result is true"
        result == true
    }
    
    def "close should close the HTTP client"() {
        when: "close is called"
        service.close()
        
        then: "the HTTP client is closed"
        1 * httpClient.close()
    }
}
