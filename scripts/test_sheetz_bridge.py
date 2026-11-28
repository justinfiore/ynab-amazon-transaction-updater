import base64
import contextlib
from decimal import Decimal
import io
import json
import os
from pathlib import Path
import tempfile
import time
import types
import unittest
from unittest.mock import MagicMock, Mock, patch

import sheetz_bridge as bridge


def token(exp):
    payload = base64.urlsafe_b64encode(json.dumps({'exp': exp}).encode()).decode().rstrip('=')
    return 'x.' + payload + '.x'


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.credentials = self.root / 'credentials'
        self.credentials.write_text('{"username":"synthetic","password":"synthetic"}')
        self.credentials.chmod(0o600)
        self.session = self.root / 'token'
        self.settings = {'credentials_file': str(self.credentials), 'token_file': str(self.session)}
        self.good = token(time.time() + 3600)
        self.data = {'orders': [{'orderType': 'ANYWHERE', 'orderNumber': 1, 'orderTime': '2026-11-01T12:00:00-05:00',
                                'orderTotal': 1.25, 'items': [{'description': 'synthetic', 'extra': 'private'}],
                                'paymentDetails': 'private'}]}

    def run_bridge(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            bridge.run(self.settings)
        return json.loads(output.getvalue())

    def test_reuse_valid_token_and_filter_fields(self):
        self.session.write_text(self.good)
        self.session.chmod(0o600)
        self.data['orders'][0]['items'][0]['totalPrice'] = Decimal('1.25')
        with patch.object(bridge, 'sign_in') as sign_in, patch.object(bridge, 'fetch', return_value=(200, self.data)) as fetch:
            result = self.run_bridge()
        sign_in.assert_not_called()
        fetch.assert_called_once_with(self.good)
        self.assertNotIn('paymentDetails', result['orders'][0])
        self.assertNotIn('extra', result['orders'][0]['items'][0])
        self.assertEqual(result['orders'][0]['items'][0]['totalPrice'], '1.25')

    def test_receipts_use_one_bounded_request_for_both_verified_types(self):
        response = MagicMock()
        response.status = 200
        response.read.return_value = b'{"orders":[]}'
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = response
        with patch.object(bridge.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual(bridge.fetch('synthetic'), (200, {'orders': []}))
        opener.open.assert_called_once()
        request = opener.open.call_args.args[0]
        self.assertEqual(opener.open.call_args.kwargs['timeout'], 30)
        self.assertEqual(request.get_method(), 'GET')
        self.assertEqual(request.full_url, bridge.URL)
        self.assertEqual(request.full_url.split('?')[1], 'orderType=ANYWHERE&orderType=RETAIL_FUEL')
        self.assertEqual(request.get_header('Authorization'), 'Bearer synthetic')

    def test_expired_token_signs_in_once_and_saves_protected_token(self):
        self.session.write_text(token(time.time() - 10))
        self.session.chmod(0o600)
        with patch.object(bridge, 'sign_in', return_value=self.good) as sign_in, patch.object(bridge, 'fetch', return_value=(200, self.data)):
            self.run_bridge()
        sign_in.assert_called_once()
        self.assertEqual(self.session.read_text(), self.good)
        self.assertEqual(self.session.stat().st_mode & 0o777, 0o600)

    def test_401_reauth_once_and_403_never_reauth(self):
        self.session.write_text(self.good)
        self.session.chmod(0o600)
        with patch.object(bridge, 'sign_in', return_value=self.good) as sign_in, patch.object(bridge, 'fetch', side_effect=[(401, None), (401, None)]):
            with self.assertRaises(bridge.BridgeError) as error: self.run_bridge()
        self.assertEqual(error.exception.code, 'receipt_401')
        sign_in.assert_called_once()
        with patch.object(bridge, 'sign_in') as sign_in, patch.object(bridge, 'fetch', return_value=(403, None)):
            with self.assertRaises(bridge.BridgeError) as error: self.run_bridge()
        self.assertEqual(error.exception.code, 'receipt_403')
        sign_in.assert_not_called()

    def test_sign_in_challenge_and_unsafe_credentials_fail_closed(self):
        with patch.object(bridge, 'sign_in', side_effect=ValueError('challenge')), patch.object(bridge, 'fetch') as fetch:
            with self.assertRaises(ValueError): self.run_bridge()
        fetch.assert_not_called()
        self.credentials.chmod(0o644)
        with patch.object(bridge, 'sign_in') as sign_in:
            with self.assertRaises(ValueError): self.run_bridge()
        sign_in.assert_not_called()

    def test_malformed_items_and_receipts_do_not_discard_other_records(self):
        self.session.write_text(self.good)
        self.session.chmod(0o600)
        data = {'orders': [self.data['orders'][0] | {'items': [None, {'description': 'good'}, {'description': Decimal('1.5')}]}, None]}
        with patch.object(bridge, 'fetch', return_value=(200, data)):
            result = self.run_bridge()
        self.assertEqual(result['orders'][0]['items'][0], None)
        self.assertEqual(result['orders'][0]['items'][1]['description'], 'good')
        self.assertIsNone(result['orders'][0]['items'][2]['description'])
        self.assertIsNone(result['orders'][1])

    def test_top_level_navigation_to_untrusted_origin_is_blocked(self):
        frame = object()
        route = Mock()
        route.request.frame = frame
        route.request.is_navigation_request.return_value = True
        route.request.url = 'https://attacker.example/sign-in'
        bridge.guard_navigation(route, frame)
        route.abort.assert_called_once()
        route.continue_.assert_not_called()
        self.assertFalse(bridge.trusted_origin('https://orders.sheetz.com:444/auth'))
        self.assertTrue(bridge.trusted_origin('https://orders.sheetz.com/auth'))

    def test_sign_in_uses_full_headless_chromium_and_browser_version_user_agent(self):
        api = types.ModuleType('playwright.sync_api')
        api.TimeoutError = type('PlaywrightTimeout', (Exception,), {})
        manager = MagicMock()
        api.sync_playwright = Mock(return_value=manager)
        browser = manager.__enter__.return_value.chromium.launch.return_value
        probe, page = Mock(), Mock()
        probe.evaluate.return_value = 'Mozilla/5.0 HeadlessChrome/159.0.0.0 Safari/537.36'
        browser.new_page.side_effect = [probe, page]
        page.goto.return_value.status = 404
        with patch.dict('sys.modules', {'playwright': types.ModuleType('playwright'), 'playwright.sync_api': api}):
            with self.assertRaises(bridge.BridgeError) as error:
                bridge.sign_in({'username': 'synthetic', 'password': 'synthetic'})
        self.assertEqual(error.exception.code, 'signin_page_http')
        manager.__enter__.return_value.chromium.launch.assert_called_once_with(channel='chromium', headless=True)
        browser.new_page.assert_any_call(user_agent='Mozilla/5.0 Chrome/159.0.0.0 Safari/537.36', locale='en-US')
        page.get_by_role.assert_called_once_with('dialog')

    def test_cookie_handler_only_declines_the_named_consent_button(self):
        page = Mock()
        dialog = page.get_by_role.return_value
        decline = dialog.get_by_role.return_value
        counts = {'dismissals': 0}
        bridge.handle_cookie_dialog(page, counts)
        page.get_by_role.assert_called_once_with('dialog')
        dialog.get_by_role.assert_called_once_with('button', name='DO NOT ACCEPT', exact=True)
        locator, handler = page.add_locator_handler.call_args.args
        self.assertIs(locator, decline)
        handler(decline)
        handler(decline)
        self.assertEqual(decline.click.call_count, 2)
        self.assertEqual(counts['dismissals'], 2)
        decline.click.side_effect = RuntimeError('blocked')
        with self.assertRaises(RuntimeError):
            handler(decline)
        self.assertEqual(counts['dismissals'], 2)

    def test_result_timeout_diagnostics_contain_only_boolean_flags(self):
        page = Mock()
        page.url = 'https://orders.sheetz.com/auth?private=secret'
        page.evaluate.return_value = {'password': True, 'alert': False, 'challenge': True}
        diagnostic = bridge.result_diagnostic(page, {'http4xx': True, 'http5xx': False, 'failed': False,
                                                      'xhr400': True, 'xhr401': False, 'xhr403': False,
                                                      'xhr404': False, 'xhr429': False, 'events': ['401:POST:fetch:orders:auth/redacted']})
        self.assertEqual(diagnostic, 'trusted_origin=true password_visible=true alert_visible=false '
                         'challenge_text=true http4xx=true http5xx=false request_failed=false '
                         'xhr400=true xhr401=false xhr403=false xhr404=false xhr429=false events=401:POST:fetch:orders:auth/redacted')
        self.assertNotIn('secret', diagnostic)
        page.evaluate.side_effect = RuntimeError('sensitive error')
        self.assertIsNone(bridge.result_diagnostic(page, {}))

    def test_failure_event_redacts_credentials_and_untrusted_path_segments(self):
        response = Mock()
        response.status = 401
        response.url = 'https://orders.sheetz.com/auth/private-password?password=private-password'
        response.request.method = 'POST'
        response.request.resource_type = 'fetch'
        self.assertEqual(bridge.failure_event(response), '401:POST:fetch:orders:auth/redacted')

    def test_sign_in_submits_password_form_once(self):
        api = types.ModuleType('playwright.sync_api')
        api.TimeoutError = type('PlaywrightTimeout', (Exception,), {})
        manager = MagicMock()
        api.sync_playwright = Mock(return_value=manager)
        browser = manager.__enter__.return_value.chromium.launch.return_value
        probe, page = MagicMock(), MagicMock()
        browser.new_page.side_effect = [probe, page]
        probe.evaluate.return_value = 'HeadlessChrome/1'
        page.url = 'https://orders.sheetz.com/auth'
        page.goto.return_value.status = 200
        page.get_by_text.return_value.count.return_value = 0
        page.evaluate.return_value = self.good
        password = page.get_by_label.return_value
        with patch.dict('sys.modules', {'playwright': types.ModuleType('playwright'), 'playwright.sync_api': api}):
            self.assertEqual(bridge.sign_in({'username': 'synthetic', 'password': 'synthetic'}), self.good)
        form = page.locator.return_value.filter
        form.assert_called_once_with(has=password)
        form.return_value.locator.assert_called_once_with('button[type=submit]')
        form.return_value.locator.return_value.click.assert_called_once_with()

    def test_credential_and_token_aliases_never_reach_sign_in(self):
        directory_alias = self.root / 'alias'
        directory_alias.symlink_to(self.root, target_is_directory=True)
        self.settings['token_file'] = str(directory_alias / 'credentials')
        with patch.object(bridge, 'sign_in') as sign_in:
            with self.assertRaises(bridge.BridgeError) as error:
                self.run_bridge()
        self.assertEqual(error.exception.code, 'credential_token_alias')
        sign_in.assert_not_called()
        hardlink = self.root / 'hardlink'
        os.link(self.credentials, hardlink)
        self.settings['token_file'] = str(hardlink)
        with self.assertRaises(bridge.BridgeError):
            self.run_bridge()


if __name__ == '__main__':
    unittest.main()
