package com.ynab.budgetassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import com.ynab.budgetassistant.config.Configuration
import spock.lang.Specification

class YNABRequestEfficiency_IT extends Specification {
    def json = new ObjectMapper()
    HttpServer server
    YNABService service
    List<String> requests = []
    Map<String, Map> remote = [old:[id:'old', date:'2020-01-01', approved:true, category_id:'a']]
    boolean limit = false
    Map batch

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/plans/plan') { ex ->
            String path = ex.requestURI.path - '/v1/plans/plan'
            requests << "${ex.requestMethod} ${path}".toString()
            if (limit) { ex.sendResponseHeaders(429, -1); ex.close(); return }
            Map body
            if (path == '/accounts') body = [data:[accounts:[[id:'account', on_budget:true]]]]
            else if (path == '/categories') body = [data:[category_groups:[[name:'Group', categories:[[id:'cat']]]]]]
            else if (path == '/transactions' && ex.requestMethod == 'GET') body = [data:[transactions:remote.values().toList()]]
            else if (path == '/transactions' && ex.requestMethod == 'PATCH') {
                batch = json.readValue(ex.requestBody, Map)
                body = [data:[transaction_ids:batch.transactions*.id]]
            } else if (ex.requestMethod == 'PUT') body = [data:[transaction:remote.old]]
            else body = [data:[transaction:remote.old]]
            byte[] bytes = json.writeValueAsBytes(body)
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        service = new YNABService(new Configuration(ynabApiKey:'fixture', ynabBudgetId:'plan',
            ynabBaseUrl:"http://127.0.0.1:${server.address.port}/v1"))
    }

    def cleanup() { service?.close(); server?.stop(0) }

    def 'workflow shares metadata and historical snapshot but refreshes after writes'() {
        when:
        service.beginWorkflow()
        service.getBudgetAccounts(); service.getWalmartBudgetAccounts()
        service.getCategoryCatalog(); service.getWalmartCategories()
        def old = service.getLearningTransactions()
        service.getLearningTransactions()
        service.putTransaction('old', [memo:'changed'])
        service.getLearningTransactions()

        then:
        old.old.date == '2020-01-01'
        requests.count('GET /accounts') == 1
        requests.count('GET /categories') == 1
        requests.count('GET /transactions') == 2
        requests.count('PUT /transactions/old') == 1
    }

    def 'notice updates use collection PATCH and return only acknowledged ids'() {
        when:
        def result = service.updateTransactionMemos([[id:'old', memo:'notice', approved:true]])

        then:
        requests == ['PATCH /transactions']
        batch.transactions == [[id:'old', memo:'notice', approved:true]]
        result == ['old'] as Set
    }

    def '429 stops subsequent requests without retry'() {
        given:
        limit = true

        when:
        service.getBudgetAccounts()

        then:
        thrown(IllegalStateException)

        when:
        service.getTransaction('old')

        then:
        thrown(IllegalStateException)
        requests == ['GET /accounts']
    }
}
