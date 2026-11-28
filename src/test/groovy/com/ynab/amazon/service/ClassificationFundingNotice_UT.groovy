package com.ynab.amazon.service

import spock.lang.Specification

class ClassificationFundingNotice_UT extends Specification {
    def 'notices retain user text, name the provider, and include billing links only when they fit'() {
        given:
        String text = ClassificationFundingNotice.message(provider)
        String base = 'u' * (500 - text.length() - 3 - spare)

        when:
        String memo = ClassificationFundingNotice.append(base,provider)

        then:
        memo.startsWith(base + ' | [ERROR: ' + name + ' classification unavailable; check funding/billing]')
        memo.length() <= 500
        memo.contains(url) == (spare >= url.length() + 1)
        ClassificationFundingNotice.withoutNotice(memo) == base
        ClassificationFundingNotice.append(memo,provider) == memo
        ClassificationFundingNotice.append('u' * 500,provider) == null

        where:
        provider | name     | url                                                                             | spare
        'jev'    | 'Jev'    | 'https://console.typesafe.ai/billing'                                             | 0
        'jev'    | 'Jev'    | 'https://console.typesafe.ai/billing'                                             | 35
        'jev'    | 'Jev'    | 'https://console.typesafe.ai/billing'                                             | 34
        'openai' | 'OpenAI' | 'https://platform.openai.com/settings/organization/billing/overview'              | 0
        'openai' | 'OpenAI' | 'https://platform.openai.com/settings/organization/billing/overview'              | 100
    }
}
