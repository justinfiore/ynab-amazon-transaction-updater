package com.ynab.amazon.service

/** App-owned notices only; never truncate an existing memo or copy a provider error body. */
class ClassificationFundingNotice {
    static final Map PROVIDERS = [jev:[name:'Jev', url:'https://console.typesafe.ai/billing'],
        openai:[name:'OpenAI', url:'https://platform.openai.com/settings/organization/billing/overview']].asImmutable()

    static String message(String provider) {
        "[ERROR: ${PROVIDERS[provider].name} classification unavailable; check funding/billing]"
    }

    static String withoutNotice(String memo) {
        String result = memo
        PROVIDERS.each { provider, details ->
            String text = message(provider)
            result = result?.replace(text + ' ' + details.url, text)?.replace(' | ' + text, '')?.replace(text, '')
        }
        result
    }

    static String append(String memo, String provider) {
        String base = withoutNotice(memo) ?: ''
        String notice = (base ? ' | ' : '') + message(provider)
        if ((base + notice).length() > 500) return null
        String result = base + notice
        String link = ' ' + PROVIDERS[provider].url
        result.length() + link.length() <= 500 ? result + link : result
    }
}
