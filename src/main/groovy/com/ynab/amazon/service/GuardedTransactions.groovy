package com.ynab.amazon.service

/** Read-back comparison shared by journaled writes. Never retries an unknown outcome. */
class GuardedTransactions {
    static boolean same(Map actual, Map expected) {
        if (!actual || !expected) return false
        def fields = ['id','date','amount','account_id','payee_id','payee_name','memo','category_id','cleared','approved','import_id','flag_color','deleted','transfer_account_id']
        if (expected.subtransactions && expected.category_id == null) fields.remove('category_id')
        fields.every { actual[it] == expected[it] } &&
            (actual.subtransactions ?: []).collect { [it.amount,it.category_id,it.memo,it.payee_id] }.sort { it.toString() } ==
            (expected.subtransactions ?: []).collect { [it.amount,it.category_id,it.memo,it.payee_id] }.sort { it.toString() }
    }
}
