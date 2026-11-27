#!/usr/bin/env python3
"""Read-only Sheetz receipt bridge. Paths arrive on stdin; secrets never appear in arguments."""
import base64
from decimal import Decimal
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse

URL = 'https://orderz.sheetz.com/mbff/v1/receipt/api/filtered-receipts?orderType=ANYWHERE&orderType=RETAIL_FUEL'
LIMIT = 8 * 1024 * 1024
DIAGNOSTIC_PATH_SEGMENTS = frozenset(('api', 'auth', 'authentication', 'authorize', 'identity', 'login',
                                      'oauth', 'session', 'signin', 'token', 'user', 'users', 'v1', 'v2'))


class BridgeError(ValueError):
    def __init__(self, code, diagnostic=None):
        self.code = code
        self.diagnostic = diagnostic


def trusted_origin(url):
    try:
        parsed = urlparse(url)
        return (parsed.scheme == 'https' and parsed.hostname == 'orders.sheetz.com' and
                parsed.port in (None, 443) and parsed.username is None and parsed.password is None)
    except ValueError:
        return False


def guard_navigation(route, main_frame):
    request = route.request
    if request.is_navigation_request() and request.frame == main_frame and not trusted_origin(request.url):
        route.abort()
    else:
        route.continue_()


def distinct_paths(settings):
    credentials = Path(settings['credentials_file']).expanduser()
    token = Path(settings['token_file']).expanduser()
    if credentials.resolve() == token.resolve() or (credentials.exists() and token.exists() and os.path.samefile(credentials, token)):
        raise BridgeError('credential_token_alias')
    return token.resolve()


def protected(path):
    path = Path(path).expanduser()
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError('unsafe protected file')
    if info.st_size > 65536:
        raise ValueError('protected file too large')
    return path.read_text(encoding='utf-8')


def valid(token):
    try:
        part = token.removeprefix('Bearer ').split('.')[1]
        return json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))['exp'] > time.time() + 30
    except (ValueError, TypeError, KeyError, IndexError):
        return False


def handle_cookie_dialog(page, counts):
    # The consent dialog can arrive after the form is visible. Playwright checks
    # this handler during actionability waits without retrying form submissions.
    decline = page.get_by_role('dialog').get_by_role('button', name='DO NOT ACCEPT', exact=True)
    def dismiss(button):
        button.click()
        counts['dismissals'] = min(counts['dismissals'] + 1, 999)
    page.add_locator_handler(decline, dismiss)


def failure_event(response):
    """Describe a failed request without logging arbitrary path segments or secrets."""
    try:
        request = response.request
        parsed = urlparse(response.url)
        host = parsed.hostname or ''
        origin = 'orders' if host == 'orders.sheetz.com' else 'orderz' if host == 'orderz.sheetz.com' else 'sheetz' if host.endswith('.sheetz.com') else 'other'
        method = request.method if request.method in ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS') else 'OTHER'
        kind = request.resource_type if request.resource_type in ('xhr', 'fetch', 'document') else 'other'
        segments = [part.lower() if part.lower() in DIAGNOSTIC_PATH_SEGMENTS else 'redacted'
                    for part in parsed.path.split('/') if part][:8]
        path = '/'.join(segments) or 'root'
        return f'{response.status}:{method}:{kind}:{origin}:{path}'
    except Exception:
        return None


def result_diagnostic(page, network):
    # Only fixed flags and allowlisted request metadata leave the browser.
    try:
        state = page.evaluate('''() => {
            const visible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
            const text = document.body?.innerText || '';
            return {
                password: [...document.querySelectorAll('input[type=password]')].some(visible),
                alert: [...document.querySelectorAll('[role=alert]')].some(visible),
                challenge: /captcha|verification code/i.test(text)
            };
        }''')
        flags = [trusted_origin(page.url), state['password'], state['alert'], state['challenge'],
                 network['http4xx'], network['http5xx'], network['failed'],
                 network['xhr400'], network['xhr401'], network['xhr403'], network['xhr404'], network['xhr429']]
        flags_text = ' '.join(f'{name}={str(bool(value)).lower()}' for name, value in zip(
            ('trusted_origin', 'password_visible', 'alert_visible', 'challenge_text', 'http4xx', 'http5xx', 'request_failed',
             'xhr400', 'xhr401', 'xhr403', 'xhr404', 'xhr429'), flags))
        return flags_text + ' events=' + (','.join(network['events']) or 'none')
    except Exception:
        return None


def sign_in(credentials):
    from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

    stage = 'signin_browser'
    counts = {'dismissals': 0}
    with sync_playwright() as playwright:
        # Use the full Chromium build in headless mode, not the separate headless shell.
        browser = playwright.chromium.launch(channel='chromium', headless=True)
        try:
            probe = browser.new_page()
            try:
                user_agent = probe.evaluate('navigator.userAgent').replace('HeadlessChrome/', 'Chrome/')
            finally:
                probe.close()
            page = browser.new_page(user_agent=user_agent, locale='en-US')
            handle_cookie_dialog(page, counts)
            page.route('**/*', lambda route: guard_navigation(route, page.main_frame))
            page.set_default_timeout(15000)
            stage = 'signin_page'
            response = page.goto('https://orders.sheetz.com/auth?destination=/order/history', wait_until='domcontentloaded')
            if response and response.status >= 400:
                raise BridgeError('signin_page_http')
            def check():
                if not trusted_origin(page.url):
                    raise BridgeError('signin_redirect')
                if page.get_by_text('captcha', exact=False).count() or page.get_by_text('verification code', exact=False).count():
                    raise BridgeError('signin_challenge')
            check()
            stage = 'signin_username'
            username = page.get_by_role('textbox', name='Email or Mobile Number')
            username.wait_for(state='visible')
            check()
            username.fill(credentials['username'])
            stage = 'signin_username_submit'
            page.get_by_role('button', name='SIGN IN / REGISTER').click()
            check()
            stage = 'signin_password'
            password = page.get_by_label('Password', exact=True)
            password.wait_for(state='visible')
            check()
            password.fill(credentials['password'])
            network = dict.fromkeys(('http4xx', 'http5xx', 'failed', 'xhr400', 'xhr401', 'xhr403', 'xhr404', 'xhr429'), False)
            network['events'] = []
            def observe_response(response):
                if 400 <= response.status <= 599 and len(network['events']) < 8:
                    event = failure_event(response)
                    if event:
                        network['events'].append(event)
                if 400 <= response.status < 500:
                    network['http4xx'] = True
                    key = f'xhr{response.status}'
                    if response.request.resource_type in ('xhr', 'fetch') and key in network:
                        network[key] = True
                elif response.status >= 500:
                    network['http5xx'] = True
            page.on('response', observe_response)
            page.on('requestfailed', lambda _: network.update(failed=True))
            stage = 'signin_password_submit'
            page.locator('form').filter(has=password).locator('button[type=submit]').click()
            stage = 'signin_result'
            page.wait_for_function("() => !!sessionStorage.getItem('authToken')", timeout=20000)
            check()
            token = page.evaluate("() => sessionStorage.getItem('authToken')")
            if not valid(token):
                raise BridgeError('signin_token')
            return token
        except PlaywrightTimeout:
            diagnostic = result_diagnostic(page, network) if stage == 'signin_result' else None
            raise BridgeError(stage + '_timeout', diagnostic) from None
        finally:
            print(f"sheetz_cookie_dismissals={counts['dismissals']}", file=sys.stderr)
            browser.close()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch(token):
    header = token if token.startswith('Bearer ') else 'Bearer ' + token
    request = urllib.request.Request(URL, headers={'Authorization': header, 'Accept': 'application/json'})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            body = response.read(LIMIT + 1)
            if len(body) > LIMIT:
                raise ValueError('response too large')
            return response.status, json.loads(body, parse_float=Decimal)
    except urllib.error.HTTPError as error:
        return error.code, None


def run(settings):
    token_path = distinct_paths(settings)
    try:
        token = protected(settings['token_file']).strip()
    except FileNotFoundError:
        token = ''
    attempted = False
    if not valid(token):
        token = sign_in(json.loads(protected(settings['credentials_file'])))
        attempted = True
    status, data = fetch(token)
    if status == 401 and not attempted:
        token = sign_in(json.loads(protected(settings['credentials_file'])))
        attempted = True
        status, data = fetch(token)
    if status != 200:
        raise BridgeError('receipt_401' if status == 401 else 'receipt_403' if status == 403 else 'receipt_http')
    if not isinstance(data, dict) or not isinstance(data.get('orders'), list):
        raise BridgeError('receipt_invalid')
    # Never emit credentials or account/payment details into the parent process.
    result = []
    for order in data['orders']:
        if not isinstance(order, dict):
            result.append(None)
            continue
        items = order.get('items')
        result.append({key: order.get(key) for key in ('orderType', 'orderNumber', 'orderTime', 'pickupTime', 'orderTotal')} | {
            'items': [{key: item.get(key) if isinstance(item.get(key), str) else None
                       for key in ('description', 'receiptText')} if isinstance(item, dict) else None
                      for item in items] if isinstance(items, list) else None
        })
        if isinstance(result[-1]['orderTotal'], Decimal):
            result[-1]['orderTotal'] = str(result[-1]['orderTotal'])
    if attempted:
        path = distinct_paths(settings)
        if path != token_path:
            raise BridgeError('credential_token_alias')
        fd, temporary = tempfile.mkstemp(prefix='.sheetz-token-', dir=path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as output:
                output.write(token)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    print(json.dumps({'orders': result}, separators=(',', ':'), default=lambda _: None))


if __name__ == '__main__':
    try:
        run(json.load(sys.stdin))
    except BridgeError as error:
        print(error.code + (' ' + error.diagnostic if error.diagnostic else ''), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('bridge_error', file=sys.stderr)
        sys.exit(1)
