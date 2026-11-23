import datetime as dt
import importlib.util
import io
import json
import logging
import os
import tempfile
import sys
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import AsyncMock, MagicMock, patch

SPEC = importlib.util.spec_from_file_location("bridge", "scripts/amazon_orders_bridge.py")
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)


class SignInError(Exception):
    pass


class RedirectError(SignInError):
    pass


class Order:
    def __init__(self, number, date, total, items=None, cancelled=False, payment_method=None, last_four=None):
        self.order_number = number
        self.order_placed_date = date
        self.grand_total = total
        self.items = items or []
        self.cancelled = cancelled
        self.payment_method = payment_method
        self.payment_method_last_4 = last_four
        self.recipient = "must-not-be-serialized"
        self.shipping_address = "must-not-be-serialized"
        self.parsed = self
        self.index = 0


class Item:
    def __init__(self, title, asin, price, quantity=None):
        self.title, self.asin, self.price, self.quantity = title, asin, price, quantity


class BridgeTests(unittest.TestCase):
    def run_bridge(self, history_by_year=None, arguments=(), session_error=None, history_error=None, household_orders=(), challenge=False):
        calls = []
        self.details = []
        root = types.ModuleType("amazonorders")
        root.__version__ = "6.0.0"
        conf = types.ModuleType("amazonorders.conf")
        conf.AmazonOrdersConfig = lambda **_: object()
        session = types.ModuleType("amazonorders.session")
        class Session:
            def __init__(self, **kwargs):
                self.is_authenticated = False
                self.io = kwargs.get("io")
                self.config = object()
                self.session = types.SimpleNamespace(close=lambda: None)

            def login(self):
                if challenge:
                    self.io.echo("Authentication challenge")
                    logging.getLogger("amazonauth.fixture").warning("sensitive challenge data")
                    self.io.prompt("sensitive challenge data", type=str)
                if session_error:
                    raise session_error
                self.is_authenticated = True

        session.AmazonSession = Session
        orders = types.ModuleType("amazonorders.orders")

        def history(**kwargs):
            calls.append(kwargs)
            if history_error:
                raise history_error
            own_orders = (history_by_year or {}).get(kwargs["year"], [])
            return own_orders + (list(household_orders) if kwargs["family_member_id"] is None else [])

        def order_client(amazon_session, **kwargs):
            def authenticated_history(**filters):
                if not amazon_session.is_authenticated:
                    raise RuntimeError("Call AmazonSession.login() to authenticate first.")
                return history(**filters)
            def details(parsed, **kwargs):
                self.details.append(parsed.order_number)
                return parsed
            return types.SimpleNamespace(get_orders=authenticated_history, _build_order=details)

        orders.AmazonOrders = order_client
        output, errors = io.StringIO(), io.StringIO()
        auth_errors = types.ModuleType("amazonauth.exception")
        auth_errors.AmazonAuthSignInError = SignInError
        auth_errors.AmazonAuthRedirectError = RedirectError
        modules = {"amazonorders": root, "amazonorders.conf": conf, "amazonorders.session": session,
                   "amazonorders.orders": orders, "amazonauth.exception": auth_errors}
        with patch.dict(sys.modules, modules), patch.object(bridge, "verify_runtime"), \
                patch.object(bridge, "create_session", return_value=Session(io=bridge.NonInteractiveIO())), \
                redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "/safe/config.yml", *arguments])
        return code, output.getvalue(), errors.getvalue(), calls

    def test_exact_inclusive_boundaries_across_years_and_deduplicates(self):
        for today, expected_years in [(dt.date(2026, 11, 21), [2025, 2026]),
                                      (dt.date(2027, 1, 1), [2025, 2026, 2027])]:
            with self.subTest(today=today):
                class FrozenDate(dt.date):
                    @classmethod
                    def today(cls):
                        return cls(today.year, today.month, today.day)
                cutoff = today - dt.timedelta(days=400)
                history = {
                    cutoff.year: [Order("first", cutoff.isoformat(), 1),
                                  Order("outside", (cutoff - dt.timedelta(days=1)).isoformat(), 1)],
                    today.year: [Order("last", today.isoformat(), 2), Order("first", today.isoformat(), 9)],
                }
                clock = types.SimpleNamespace(date=FrozenDate, datetime=dt.datetime, timedelta=dt.timedelta)
                with patch.object(bridge, "dt", clock):
                    code, output, errors, calls = self.run_bridge(history, ["--look-back-days", "400"])
                self.assertEqual((0, ""), (code, errors))
                self.assertEqual(expected_years, [call["year"] for call in calls])
                self.assertTrue(all(not call["full_details"] for call in calls))
                self.assertTrue(all(call["family_member_id"] is None for call in calls))
                self.assertEqual(["first", "last"], self.details)
                self.assertEqual(["first", "last"], [record["order_number"] for record in json.loads(output)["orders"]])

    def test_household_orders_are_included_by_default_and_can_be_excluded(self):
        today = dt.date.today()
        own = {today.year: [Order("own", today, 12.34)]}
        household = [Order("partner", today, 56.78)]
        for arguments, expected in [((), ["own", "partner"]), (["--account-only"], ["own"])]:
            with self.subTest(arguments=arguments):
                code, output, errors, _ = self.run_bridge(own, arguments, household_orders=household)
                self.assertEqual((0, ""), (code, errors))
                self.assertEqual(expected, [order["order_number"] for order in json.loads(output)["orders"]])

    def test_serializes_complete_items_payment_and_missing_quantity(self):
        today = dt.date.today()
        order = Order("complete", today, 42.17, [Item("First", "ASIN1", 1.25, 2), Item("Second", "ASIN2", 40.92)], payment_method="Visa", last_four="0012")
        code, output, errors, _ = self.run_bridge({today.year: [order]})
        record = json.loads(output)["orders"][0]
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual("Visa", record["payment_method"])
        self.assertEqual("0012", record["payment_method_last_4"])
        self.assertEqual([{"title": "First", "asin": "ASIN1", "price": 1.25, "quantity": 2}, {"title": "Second", "asin": "ASIN2", "price": 40.92, "quantity": 1}], record["items"])
        self.assertNotIn("recipient", json.dumps(record))
        self.assertNotIn("shipping_address", json.dumps(record))

    def test_excludes_cancelled_invalid_duplicate_and_returns_empty_successfully(self):
        today = dt.date.today()
        history = {today.year: [Order("kept", today, 1), Order("kept", today, 2), Order("cancelled", today, 1, cancelled=True), Order(None, today, 1), Order("zero", today, 0), Order("missing-date", None, 2), Order("invalid-date", "not-a-date", 3), Order("cancelled-no-date", None, 4, cancelled=True), Order("after-invalid", today, 5)]}
        code, output, errors, _ = self.run_bridge(history)
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual(["kept", "after-invalid"], [record["order_number"] for record in json.loads(output)["orders"]])
        code, output, errors, _ = self.run_bridge({today.year: []})
        self.assertEqual((0, ""), (code, errors))
        self.assertEqual({"schema_version": 1, "orders": []}, json.loads(output))

    def test_authentication_challenge_fails_without_prompting_or_stdout(self):
        with patch("builtins.input", side_effect=AssertionError("must not prompt")):
            code, output, errors, _ = self.run_bridge(challenge=True)
        self.assertEqual((3, ""), (code, output))
        self.assertNotIn("Authentication challenge", errors)
        self.assertIn("Amazon login required", errors)
        self.assertNotIn("sensitive challenge data", errors)

    def test_only_explicit_redirect_errors_require_login_without_response_evidence(self):
        for error in [RedirectError('redirect'), SignInError('rejected'),
                      SignInError('Amazon login required'), RuntimeError('network unavailable'), ValueError('parse failed')]:
            for stage in ['session_error', 'history_error']:
                with self.subTest(error=error, stage=stage):
                    code, output, errors, _ = self.run_bridge(**{stage: error})
                    self.assertEqual(3 if isinstance(error, RedirectError) else 1, code)
                    self.assertEqual('', output)
                    self.assertEqual(isinstance(error, RedirectError), 'Amazon login required' in errors)

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install requirements-amazon-orders.txt for the real-library contract test")
    def test_real_library_classifies_response_evidence_not_sign_in_exception_text(self):
        from amazonorders.orders import AmazonOrders
        from requests import Response

        sign_in = '''<form name="signIn" method="post" action="/ap/signin">
            <input name="email" value=""><input name="password" value=""></form>'''
        rejected = '<div id="auth-error-message-box">Fixture rejected credentials</div>' + sign_in
        greeting = '<span id="nav-link-accountList-nav-line-1">Hello, Fixture</span>'
        otp = '<form id="auth-mfa-form"><input name="otpCode" value=""></form>'

        def response(status, html, path="/ap/signin"):
            result = Response()
            result.status_code = status
            result.url = "https://www.amazon.com" + path
            result._content = html.encode()
            return result

        cases = [
            ("503", [response(503, "Service unavailable")], False, 1),
            ("unknown HTML", [response(200, "Unknown page: Amazon login required")], False, 1),
            ("error box without credential form", [response(200, rejected.split('<form')[0])], False, 1),
            ("interactive OTP", [response(200, otp)], False, 3),
            ("CAPTCHA", [response(200, '<form action="/errors/validateCaptcha"><input id="captchacharacters"></form>')], False, 3),
            ("browser challenge", [response(200, '<div id="aa-challenge-page-captcha-container"></div>')], False, 3),
            ("rejected credentials", [response(200, sign_in), response(200, rejected)], True, 3),
            ("503 with auth markup", [response(200, sign_in), response(503, rejected)], True, 1),
            ("503 with OTP markup", [response(503, otp)], False, 1),
            ("403 with auth markup", [response(403, rejected)], True, 1),
            ("unknown HTML after submission", [response(200, sign_in), response(200, "Unknown page")], True, 1),
            ("history redirect", [response(200, greeting, "/"), response(200, sign_in)], False, 3),
            ("history 503", [response(200, greeting, "/"), response(503, sign_in)], False, 1),
        ]
        for name, responses, credentials, expected in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                config_path = os.path.join(directory, "config.yml")
                with open(config_path, "w") as config:
                    json.dump({"cookie_jar_path": os.path.join(directory, "cookies.json"),
                               "output_dir": directory, "max_auth_attempts": 2,
                               "auth_reattempt_wait": 0,
                               "username": "fixture@example.invalid" if credentials else None,
                               "password": "fixture-password" if credentials else None}, config)
                output, errors = io.StringIO(), io.StringIO()

                async def history_redirect(client, *args, **kwargs):
                    # Use the real check_response, as real order-page retrieval does.
                    client.amazon_session.check_response(client.amazon_session.get("https://www.amazon.com/orders"))

                with patch.dict(os.environ, {}, clear=True), \
                        patch("requests.sessions.Session.request",
                              side_effect=[response(200, "Fixture home", "/"), *responses]) as http, \
                        patch.object(AmazonOrders, "_build_orders_async", history_redirect), \
                        patch("builtins.input", side_effect=AssertionError("must not prompt")), \
                        redirect_stdout(output), redirect_stderr(errors):
                    code = bridge.main(["--config-path", config_path])
                self.assertEqual(expected, code, errors.getvalue())
                self.assertEqual("", output.getvalue())
                self.assertEqual(expected == 3, "Amazon login required" in errors.getvalue())
                self.assertNotIn("Fixture rejected credentials", errors.getvalue())
                self.assertNotIn("fixture-password", errors.getvalue())
                self.assertEqual(1 + len(responses), http.call_count)

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install requirements-amazon-orders.txt for the real-library contract test")
    def test_real_library_fetches_details_only_inside_the_window(self):
        from requests import Response

        today = dt.date.today()
        cutoff = today - dt.timedelta(days=30)

        def card(number, placed):
            return f'''<div class="order-card">
                <span data-component="orderId">{number}</span>
                <span data-component="orderDate">{placed.isoformat()}</span>
                <div class="yohtmlc-order-total"><span class="value">$12.34</span></div>
                </div>'''

        first, last, old = "123-1234567-1234567", "234-2345678-2345678", "345-3456789-3456789"
        history = card(old, cutoff - dt.timedelta(days=1)) + card(first, cutoff) + card(last, today) + card(first, cutoff)
        requested_details = []

        def network(adapter, request, **kwargs):
            result = Response()
            result.status_code, result.url, result.request = 200, request.url, request
            if "/your-orders/orders" in request.url:
                html = history
            elif "/gp/your-account/order-details" in request.url:
                number = request.url.split("orderID=")[1]
                requested_details.append(number)
                html = '''<div id="orderDetails">
                    <span data-testid="payment-instrument-name">Visa</span>
                    <span data-testid="payment-instrument-number">ending in 0012</span>
                    </div>'''
            else:
                html = '<span id="nav-link-accountList-nav-line-1">Hello, Fixture</span>'
            result._content = html.encode()
            return result

        output, errors = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            config_path = os.path.join(directory, "config.yml")
            with open(config_path, "w") as config:
                json.dump({"cookie_jar_path": os.path.join(directory, "cookies.json"), "output_dir": directory}, config)
            # Real login, history/summary/detail parsing, transport and output;
            # only the network is replaced. No real account or credentials.
            with patch.dict(os.environ, {}, clear=True), \
                    patch("requests.adapters.HTTPAdapter.send", autospec=True, side_effect=network), \
                    redirect_stdout(output), redirect_stderr(errors):
                code = bridge.main(["--config-path", config_path])
        self.assertEqual((0, ""), (code, errors.getvalue()))
        self.assertEqual([first, last], requested_details)
        records = json.loads(output.getvalue())["orders"]
        self.assertEqual([first, last], [order["order_number"] for order in records])
        self.assertEqual([12.34, 12.34], [order["grand_total"] for order in records])
        self.assertEqual(["0012", "0012"], [order["payment_method_last_4"] for order in records])

    def test_preflight_is_stdout_only_and_never_constructs_session(self):
        code, output, errors, calls = self.run_bridge(arguments=["--preflight"], session_error=RuntimeError("should not run"))
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual([], calls)
        payload = json.loads(output)
        self.assertEqual(1, payload["schema_version"])
        self.assertEqual("6.0.0", payload["package_version"])

    def test_import_session_and_upstream_failures_are_stderr_only(self):
        output, errors = io.StringIO(), io.StringIO()
        with patch("importlib.import_module", side_effect=ImportError("missing")), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "/safe/config.yml"])
        self.assertEqual((1, ""), (code, output.getvalue()))
        self.assertIn("bridge failed", errors.getvalue())
        for session_error, history_error in [(RuntimeError("authentication failed"), None), (None, RuntimeError("upstream failed"))]:
            code, output, errors, _ = self.run_bridge(session_error=session_error, history_error=history_error)
            self.assertEqual((1, ""), (code, output))
            self.assertIn("bridge failed", errors)

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_credentials_and_otp_never_reach_unsafe_form_destinations(self):
        from amazonorders.orders import AmazonOrders
        from requests import Response

        destinations = ["https://collector.invalid/steal", "https://www.amazon.com.evil.invalid/steal",
                        "https://seller.amazon.com/steal", "http://www.amazon.com/steal",
                        "https://www.amazon.com:8443/steal", "https://user:pass@www.amazon.com/steal",
                        "https://www.amazon.co.uk/steal", "/ap/signin"]
        for kind in ("password", "otp", "interactive"):
            for destination in destinations:
                with self.subTest(kind=kind, destination=destination), tempfile.TemporaryDirectory() as directory:
                    config_path = os.path.join(directory, "config.yml")
                    with open(config_path, "w") as config:
                        json.dump({"cookie_jar_path": os.path.join(directory, "cookies.json")}, config)
                    sent = []

                    def network(adapter, request, **kwargs):
                        sent.append(request)
                        self.assertTrue(request.url.startswith("https://www.amazon.com/"))
                        self.assertEqual((10, 30), kwargs["timeout"])
                        self.assertIs(True, kwargs["verify"])
                        self.assertEqual({}, kwargs["proxies"])
                        result = Response()
                        result.status_code, result.url, result.request = 200, request.url, request
                        if request.method == "POST":
                            html = '<span id="nav-link-accountList-nav-line-1">Hello, Fixture</span>'
                        elif "/ap/signin" in request.url:
                            attrs = 'id="auth-mfa-form"' if kind == "otp" else 'name="signIn"'
                            fields = '<input name="otpCode" value="">' if kind == "otp" else '<input name="email" value=""><input name="password" value="">'
                            html = f'<form {attrs} method="post" action="{destination}">{fields}</form>'
                        else:
                            html = "Fixture home"
                        result._content = html.encode()
                        return result

                    output, errors = io.StringIO(), io.StringIO()
                    env = {"AMAZON_USERNAME": "fixture@example.invalid", "AMAZON_PASSWORD": "fixture-password",
                           "AMAZON_OTP_SECRET_KEY": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", "HTTPS_PROXY": "http://proxy.invalid",
                           "REQUESTS_CA_BUNDLE": "/nonexistent/ca.pem"}
                    if kind == "interactive":
                        env = {}
                    with patch.dict(os.environ, env, clear=True), \
                            patch("requests.adapters.HTTPAdapter.send", autospec=True, side_effect=network), \
                            patch.object(AmazonOrders, "_build_orders_async", new_callable=AsyncMock, return_value=([], None, None, None)), \
                            patch("builtins.input", return_value="fixture@example.invalid"), \
                            patch.object(bridge.getpass, "getpass", return_value="fixture-password"), \
                            redirect_stdout(output), redirect_stderr(errors):
                        code = bridge.main(["--config-path", config_path, *(["--login"] if kind == "interactive" else [])])
                    allowed = destination == "/ap/signin"
                    self.assertEqual(0 if allowed else 1, code, errors.getvalue())
                    self.assertEqual(1 if allowed else 0, sum(r.method == "POST" for r in sent))
                    if allowed:
                        if kind == "interactive":
                            self.assertIn("Amazon session saved", output.getvalue())
                            cookies = os.path.join(directory, "cookies.json")
                            self.assertTrue(os.path.exists(cookies))
                            if os.name == "posix":
                                self.assertEqual(0o600, os.stat(cookies).st_mode & 0o777)
                            with open(cookies) as saved:
                                self.assertNotIn("fixture-password", saved.read())
                        else:
                            self.assertEqual([], json.loads(output.getvalue())["orders"])
                        self.assertIn("otpCode=" if kind == "otp" else "password=fixture-password", sent[-1].body)
                    else:
                        self.assertEqual("", output.getvalue())
                    self.assertNotIn("fixture-password", errors.getvalue())
                    self.assertNotIn("JBSWY3DPEHPK3PXP", errors.getvalue())

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_bodyless_redirects_cannot_escape_the_selected_region(self):
        from requests import Response

        for destination in ["https://collector.invalid/", "https://www.amazon.com/", "https://www.amazon.co.uk/allowed"]:
            with self.subTest(destination=destination), tempfile.TemporaryDirectory() as directory:
                config_path = os.path.join(directory, "config.yml")
                with open(config_path, "w") as config:
                    json.dump({"domain": "amazon.co.uk", "cookie_jar_path": os.path.join(directory, "cookies.json")}, config)
                sent = []

                def network(adapter, request, **kwargs):
                    sent.append(request.url)
                    result = Response()
                    result.url, result.request, result._content = request.url, request, b"Fixture"
                    result.status_code = 302 if len(sent) == 1 else 200
                    if len(sent) == 1:
                        result.headers["Location"] = destination
                    return result

                with patch.dict(os.environ, {}, clear=True), patch("requests.adapters.HTTPAdapter.send", autospec=True, side_effect=network):
                    session = bridge.create_session(config_path)
                    try:
                        if destination.endswith("/allowed"):
                            self.assertEqual(200, session.session.get("https://www.amazon.co.uk/start").status_code)
                            self.assertEqual(["https://www.amazon.co.uk/start", destination], sent)
                        else:
                            with self.assertRaises(ValueError):
                                session.session.get("https://www.amazon.co.uk/start")
                            self.assertEqual(["https://www.amazon.co.uk/start"], sent)
                    finally:
                        session.session.close()

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_executable_config_is_rejected_before_import_or_network(self):
        overrides = [{key: "hostile.Payload"} for key in ("constants_class", "selectors_class", "order_class", "output_class")]
        overrides += [{"auth_forms_classes": ["hostile.Payload"]}, {"language_package": "hostile"}, {"bs4_parser": "lxml"}]
        for override in overrides:
            for source, arguments in [(source, args) for source in ("file", "environment")
                                      for args in ([], ["--login"], ["--browser-login"])]:
                with self.subTest(override=override, source=source, arguments=arguments), tempfile.TemporaryDirectory() as directory:
                    marker = os.path.join(directory, "executed")
                    with open(os.path.join(directory, "hostile.py"), "w") as plugin:
                        plugin.write(f"open({marker!r}, 'w').write('executed')\nclass Payload: pass\n")
                    config_path = os.path.join(directory, "config.yml")
                    with open(config_path, "w") as config:
                        json.dump(override if source == "file" else {}, config)
                    env = {"AMAZON_" + key.upper(): json.dumps(val) for key, val in override.items()} if source == "environment" else {}
                    output, errors = io.StringIO(), io.StringIO()
                    versions = {"amazon-orders": "6.0.0", "amazon-auth": "1.1.0", "playwright": "1.65.2", "greenlet": "3.5.6"}
                    with patch.dict(os.environ, env, clear=True), patch.object(sys, "path", [directory, *sys.path]), \
                            patch.object(sys.stdin, "isatty", return_value=True), \
                            patch.object(bridge.importlib.metadata, "version", side_effect=versions.__getitem__), \
                            patch("requests.adapters.HTTPAdapter.send") as network, \
                            redirect_stdout(output), redirect_stderr(errors):
                        code = bridge.main(["--config-path", config_path, *arguments])
                    self.assertEqual((1, ""), (code, output.getvalue()))
                    self.assertFalse(os.path.exists(marker))
                    network.assert_not_called()

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_manual_browser_login_refreshes_session_without_enabling_unattended_browsers(self):
        from amazonorders.orders import AmazonOrders
        from requests import Response

        greeting = '<span id="nav-link-accountList-nav-line-1">Hello, Fixture</span>'
        cases = [("ACIC", [], None, 3), ("JS", ["--login"], None, 3),
                 ("ACIC", ["--browser-login"], None, 0), ("JS", ["--browser-login"], None, 0),
                 ("ACIC", ["--browser-login"], "https://collector.invalid/steal", 1),
                 ("ACIC", ["--browser-login"], "https://www.amazon.com.evil.invalid/steal", 1),
                 ("ACIC", ["--browser-login"], "http://www.amazon.com/steal", 1),
                 ("ACIC", ["--browser-login"], "https://www.amazon.com:8443/steal", 1),
                 ("ACIC", ["--browser-login"], "redirect", 1)]
        for challenge, arguments, blocked, expected in cases:
            with self.subTest(challenge=challenge, arguments=arguments, blocked=blocked), tempfile.TemporaryDirectory() as directory:
                config_path = os.path.join(directory, "config.yml")
                cookie_path = os.path.join(directory, "cookies.json")
                with open(config_path, "w") as config:
                    json.dump({"cookie_jar_path": cookie_path, "max_auth_attempts": 2}, config)
                sent = []

                def network(adapter, request, **kwargs):
                    sent.append(request)
                    result = Response()
                    result.status_code, result.url, result.request = 200, request.url, request
                    if "fixture-session=refreshed" in request.headers.get("Cookie", ""):
                        html = greeting
                    elif "/ap/signin" in request.url:
                        result.url = "https://www.amazon.com/ax/aaut/verify/ap/challenge"
                        html = ('<div id="aa-challenge-page-captcha-container"></div>' if challenge == "ACIC"
                                else "verify that you're not a robot. Enable JavaScript")
                    else:
                        html = "Fixture home"
                    result._content = html.encode()
                    return result

                # Only the external browser engine is simulated: use real pinned
                # auth forms, bridge HTTP guard, cookie persistence and CLI paths.
                context, page, browser = MagicMock(), MagicMock(), MagicMock()
                browser.new_context.return_value = context
                context.new_page.return_value = page
                page.url = "https://www.amazon.com/refreshed"
                context.cookies.return_value = [{"name": "fixture-session", "value": "refreshed",
                                                "domain": "www.amazon.com", "path": "/", "secure": True}]
                handlers = {}
                context.route.side_effect = lambda pattern, handler: handlers.update(http=handler)
                context.route_web_socket.side_effect = lambda pattern, handler: handlers.update(socket=handler)

                def navigate(url):
                    route = MagicMock()
                    route.request.url = url if blocked in (None, "redirect") else blocked
                    route.fetch.return_value.status = 302 if blocked == "redirect" else 200
                    route.abort.side_effect = ValueError("Fixture blocked request")
                    handlers["http"](route)
                    # Check the egress contract, not an unguarded browser redirect.
                    self.assertEqual(0, route.fetch.call_args.kwargs["max_redirects"])
                    socket = MagicMock()
                    handlers["socket"](socket)
                    self.assertTrue(socket.close.called)

                page.goto.side_effect = navigate
                page.wait_for_url.side_effect = lambda predicate: self.assertTrue(predicate(page.url))
                engine = MagicMock()
                engine.__enter__.return_value.chromium.launch.return_value = browser
                api = types.ModuleType("playwright.sync_api")
                def start_driver():
                    self.assertFalse(set(env) & set(os.environ))
                    return engine
                api.sync_playwright = MagicMock(side_effect=start_driver)
                api.TimeoutError = TimeoutError
                playwright = types.ModuleType("playwright")
                playwright.__spec__ = importlib.util.spec_from_loader("playwright", loader=None)
                modules = {"playwright": playwright, "playwright.sync_api": api}
                output, errors = io.StringIO(), io.StringIO()
                env = {"AMAZON_PASSWORD": "fixture-password", "YNAB_API_KEY": "fixture-ynab",
                       "HTTPS_PROXY": "http://proxy.invalid", "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH": "/hostile",
                       "PLAYWRIGHT_NODEJS_PATH": "/hostile-node", "NODE_OPTIONS": "--require=/hostile",
                       "NODE_TLS_REJECT_UNAUTHORIZED": "0", "NODE_EXTRA_CA_CERTS": "/hostile-ca"}
                versions = {"amazon-orders": "6.0.0", "amazon-auth": "1.1.0", "playwright": "1.65.2", "greenlet": "3.5.6"}
                with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, modules), \
                        patch.object(bridge.importlib.metadata, "version", side_effect=versions.__getitem__), \
                        patch.object(sys.stdin, "isatty", return_value=True), \
                        patch("requests.adapters.HTTPAdapter.send", autospec=True, side_effect=network), \
                        patch.object(AmazonOrders, "_build_orders_async", new_callable=AsyncMock, return_value=([], None, None, None)), \
                        patch("builtins.input", return_value="fixture@example.invalid"), \
                        patch.object(bridge.getpass, "getpass", return_value="fixture-password"), \
                        redirect_stdout(output), redirect_stderr(errors):
                    code = bridge.main(["--config-path", config_path, *arguments])
                    self.assertEqual(expected, code, errors.getvalue())
                    self.assertEqual(env, dict(os.environ))
                    if code == 0:
                        self.assertIn("Amazon session saved", output.getvalue())
                        self.assertTrue(os.path.exists(cookie_path))
                        if os.name == "posix":
                            self.assertEqual(0o600, os.stat(cookie_path).st_mode & 0o777)
                        options = engine.__enter__.return_value.chromium.launch.call_args.kwargs
                        self.assertTrue(options["chromium_sandbox"])
                        self.assertFalse(set(env) & set(options["env"]))
                        self.assertEqual("block", browser.new_context.call_args.kwargs["service_workers"])
                        output.seek(0)
                        output.truncate()
                        # Scheduled fetching must reuse the saved cookie without
                        # starting a browser or asking for credentials.
                        api.sync_playwright.side_effect = AssertionError("unattended browser")
                        code = bridge.main(["--config-path", config_path])
                        self.assertEqual(0, code, errors.getvalue())
                        self.assertEqual([], json.loads(output.getvalue())["orders"])
                        self.assertIn("fixture-session=refreshed", sent[-1].headers.get("Cookie", ""))
                    else:
                        self.assertEqual("", output.getvalue())
                    if "--browser-login" not in arguments:
                        api.sync_playwright.assert_not_called()
                self.assertNotIn("fixture-password", output.getvalue() + errors.getvalue())
                self.assertNotIn("fixture-ynab", output.getvalue() + errors.getvalue())

    def test_browser_login_requires_terminal_and_reviewed_dependencies_before_session_access(self):
        for terminal, browser_version in [(False, "1.65.2"), (True, "1.54.0")]:
            output, errors = io.StringIO(), io.StringIO()
            versions = {"amazon-orders": "6.0.0", "amazon-auth": "1.1.0", "playwright": browser_version}
            with self.subTest(terminal=terminal, version=browser_version), \
                    patch.object(sys.stdin, "isatty", return_value=terminal), \
                    patch.object(bridge.importlib.metadata, "version", side_effect=versions.__getitem__), \
                    patch.object(bridge, "create_session", side_effect=AssertionError("session accessed")), \
                    redirect_stdout(output), redirect_stderr(errors):
                self.assertEqual(1, bridge.main(["--config-path", "unused.yml", "--browser-login"]))
                self.assertEqual("", output.getvalue())

    def test_preflight_rejects_unreviewed_auth_version(self):
        output, errors = io.StringIO(), io.StringIO()
        versions = {"amazon-orders": "6.0.0", "amazon-auth": "1.0.0"}
        with patch("importlib.metadata.version", side_effect=versions.__getitem__), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "unused.yml", "--preflight"])
        self.assertEqual((1, ""), (code, output.getvalue()))


if __name__ == "__main__":
    unittest.main()
