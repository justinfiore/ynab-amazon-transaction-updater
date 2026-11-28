package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.amazon.config.Configuration
import java.time.Duration
import org.slf4j.LoggerFactory

/** Bounded bridge execution; source failures are never interpreted as an empty history. */
class SheetzSource {
    private static final LOG = LoggerFactory.getLogger(SheetzSource)
    private static final Set SAFE_ERRORS = ['signin_page_http', 'signin_redirect', 'signin_challenge', 'signin_token',
        'signin_page_timeout', 'signin_username_timeout', 'signin_username_submit_timeout', 'signin_password_timeout',
        'signin_password_submit_timeout', 'signin_result_timeout',
        'receipt_401', 'receipt_403', 'receipt_http', 'receipt_invalid', 'credential_token_alias', 'bridge_error'] as Set

    static String safeError(String stderr, String status) {
        String code = stderr?.trim()
        String segment = '(?:api|auth|authentication|authorize|identity|login|oauth|session|signin|token|user|users|v1|v2|redacted)'
        String event = /[45]\d\d:(?:GET|POST|PUT|PATCH|DELETE|OPTIONS|OTHER):(?:xhr|fetch|document|other):(?:orders|orderz|sheetz|other):/ +
            '(?:root|' + segment + '(?:/' + segment + '){0,7})'
        String flags = /signin_result_timeout trusted_origin=(true|false) password_visible=(true|false) alert_visible=(true|false) challenge_text=(true|false) http4xx=(true|false) http5xx=(true|false) request_failed=(true|false) xhr400=(true|false) xhr401=(true|false) xhr403=(true|false) xhr404=(true|false) xhr429=(true|false) events=/
        if (SAFE_ERRORS.contains(code) || (code ==~ flags + '(?:none|' + event + '(?:,' + event + '){0,7})'))
            return code
        status
    }

    static Map fetch(Configuration config) {
        if (!config.sheetzPythonExecutable || !config.sheetzBridgeScript || !config.sheetzCredentialsFile || !config.sheetzTokenFile)
            throw new IllegalStateException('Sheetz source settings are incomplete')
        if (config.sheetzCredentialsFile == config.sheetzTokenFile)
            throw new IllegalStateException('Sheetz credentials and token paths must be distinct')
        def runner = new BoundedProcessRunner(['PATH','HOME','USERPROFILE','SYSTEMROOT','WINDIR','TEMP','TMP','TMPDIR','LANG','LC_ALL','LC_CTYPE','TZ'] as Set)
        String input = new ObjectMapper().writeValueAsString([credentials_file:config.sheetzCredentialsFile, token_file:config.sheetzTokenFile])
        def result = runner.runWithInput([config.sheetzPythonExecutable, config.sheetzBridgeScript], Duration.ofSeconds(120), 8388608, input)
        String stderr = result.stderr ?: ''
        def count = stderr =~ /\Asheetz_cookie_dismissals=([0-9]{1,3})(?:\r?\n|\z)/
        if (count.find()) {
            LOG.info('Sheetz cookie consent dismissals: {}', count.group(1))
            stderr = stderr.substring(count.end())
        }
        if (!result.success) {
            LOG.warn('Sheetz source failed: {}', safeError(stderr, result.status.toString()))
            throw new IllegalStateException('Sheetz session or receipt retrieval needs attention')
        }
        Map parsed = SheetzEnrichment.normalizeResponse(new ObjectMapper().readValue(result.stdout, Map))
        parsed.errors.each { error ->
            LOG.warn('Sheetz record {} type={} field={} item={} reason={} affects_matching={}',
                error.record, error.type, error.field, error.item, error.reason, error.affects_matching)
        }
        parsed
    }
}
