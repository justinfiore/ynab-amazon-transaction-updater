package com.ynab.amazon.service

/** Shared marker handling so a login notice is never mistaken for order details. */
class AmazonAuthNotice {
    static final String MESSAGE = '[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]'

    static String withoutNotice(String memo) {
        memo?.replace(" | ${MESSAGE}", '')?.replace(MESSAGE, '')
    }
}
