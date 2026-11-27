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

    static Map fetch(Configuration config) {
        if (!config.sheetzPythonExecutable || !config.sheetzBridgeScript || !config.sheetzCredentialsFile || !config.sheetzTokenFile)
            throw new IllegalStateException('Sheetz source settings are incomplete')
        if (config.sheetzCredentialsFile == config.sheetzTokenFile)
            throw new IllegalStateException('Sheetz credentials and token paths must be distinct')
        def runner = new BoundedProcessRunner(['PATH','HOME','USERPROFILE','SYSTEMROOT','WINDIR','TEMP','TMP','TMPDIR','LANG','LC_ALL','LC_CTYPE','TZ'] as Set)
        String input = new ObjectMapper().writeValueAsString([credentials_file:config.sheetzCredentialsFile, token_file:config.sheetzTokenFile])
        def result = runner.runWithInput([config.sheetzPythonExecutable, config.sheetzBridgeScript], Duration.ofSeconds(120), 8388608, input)
        if (!result.success) {
            String code = result.stderr?.trim()
            LOG.warn('Sheetz source failed: {}', SAFE_ERRORS.contains(code) ? code : result.status.toString())
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
