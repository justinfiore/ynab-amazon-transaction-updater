package com.ynab.budgetassistant.service

/** Shared marker handling so a login notice is never mistaken for order details. */
class AmazonAuthNotice {
    static final String MESSAGE = '[ERROR: Amazon Login Required on ynab-budget-assistant]'
    static final String LEGACY_MESSAGE = '[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]'

    static boolean containsNotice(String memo) {
        memo != null && (memo.contains(MESSAGE) || memo.contains(LEGACY_MESSAGE))
    }

    static String withoutNotice(String memo) {
        [MESSAGE, LEGACY_MESSAGE].inject(memo) { String text, String notice ->
            text?.replace(" | ${notice}", '')?.replace(notice, '')
        }
    }
}
