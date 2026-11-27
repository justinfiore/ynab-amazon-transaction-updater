import base64
import contextlib
from decimal import Decimal
import io
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

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
        with patch.object(bridge, 'sign_in') as sign_in, patch.object(bridge, 'fetch', return_value=(200, self.data)) as fetch:
            result = self.run_bridge()
        sign_in.assert_not_called()
        fetch.assert_called_once_with(self.good)
        self.assertNotIn('paymentDetails', result['orders'][0])
        self.assertNotIn('extra', result['orders'][0]['items'][0])

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
