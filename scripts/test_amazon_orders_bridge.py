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
from unittest.mock import AsyncMock, patch

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
            def old_details(number):
                self.details.append(number)
                return Order(number, dt.date.today() - dt.timedelta(days=500), 65)
            return types.SimpleNamespace(get_orders=authenticated_history, _build_order=details, get_order=old_details)

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
        self.assertEqual([{"title": "First", "asin": "ASIN1", "price": 1.25, "quantity": 2}, {"title": "Second", "asin": "ASIN2", "price": 40.92, "quantity": None}], record["items"])
        self.assertIsNone(record["financial_evidence"]["items"][1]["cents"])
        self.assertFalse(record["financial_evidence"]["complete"])
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
            for source in ("file", "environment"):
                with self.subTest(override=override, source=source), tempfile.TemporaryDirectory() as directory:
                    marker = os.path.join(directory, "executed")
                    with open(os.path.join(directory, "hostile.py"), "w") as plugin:
                        plugin.write(f"open({marker!r}, 'w').write('executed')\nclass Payload: pass\n")
                    config_path = os.path.join(directory, "config.yml")
                    with open(config_path, "w") as config:
                        json.dump(override if source == "file" else {}, config)
                    env = {"AMAZON_" + key.upper(): json.dumps(val) for key, val in override.items()} if source == "environment" else {}
                    output, errors = io.StringIO(), io.StringIO()
                    with patch.dict(os.environ, env, clear=True), patch.object(sys, "path", [directory, *sys.path]), \
                            patch("requests.adapters.HTTPAdapter.send") as network, \
                            redirect_stdout(output), redirect_stderr(errors):
                        code = bridge.main(["--config-path", config_path])
                    self.assertEqual((1, ""), (code, output.getvalue()))
                    self.assertFalse(os.path.exists(marker))
                    network.assert_not_called()

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_real_detail_layout_reconciles_adjustments_and_funding_but_rejects_unknowns(self):
        from amazonorders.conf import AmazonOrdersConfig
        from amazonorders.entity.order import Order as RealOrder
        from bs4 import BeautifulSoup

        config = AmazonOrdersConfig(data={"domain": "amazon.com"})

        def detail(extra="", tax="5.00", quantity="1", first_price="$20.00"):
            totals = [("Item(s) Subtotal", "$60.00"), ("Total before tax", "$60.00"),
                      ("Estimated tax", "$" + tax), ("Grand Total", "$65.00")]
            html = '<div id="orderDetails"><div class="displayAddressDiv"><li class="displayAddressFullName">Fixture</li></div><div data-component="purchasedItems">'
            for title, asin, price, qty in [("Cable", "B000000001", first_price, quantity), ("Pet food", "B000000002", "$40.00", "1")]:
                html += f'''<div class="a-fixed-left-grid"><div data-component="itemTitle"><a href="/dp/{asin}">{title}</a></div>
                    <span class="od-item-view-qty">{qty}</span><div data-component="unitPrice"><span class="a-text-price"><span>{price}</span></span></div></div>'''
            html += '</div><div data-component="orderSubtotals">'
            html += ''.join(f'<div class="a-row"><span>{label}:</span><div class="a-span-last">{amount}</div></div>' for label, amount in totals)
            html += extra + '</div></div>'
            return RealOrder(BeautifulSoup(html, "html.parser"), config, full_details=True, order_number="113-8849250-2919462")

        order = detail()
        evidence = bridge.financial_evidence(order)
        self.assertTrue(evidence["complete"], evidence)
        self.assertEqual([2000, 4000], [i["cents"] for i in evidence["items"]])
        self.assertEqual([{"id": "estimated_tax", "kind": "tax", "cents": 500}], evidence["adjustments"])
        self.assertEqual((6500, 0), (evidence["full_total_cents"], evidence["gift_cents"]))
        gift = detail('<div class="a-row"><span>Gift Card:</span><div class="a-span-last">-$15.00</div></div>')
        self.assertEqual(1500, bridge.financial_evidence(gift)["gift_cents"])
        self.assertTrue(bridge.financial_evidence(gift)["complete"])
        for invalid in [detail(tax="unknown"), detail(quantity=""), detail(first_price="EUR20.00"),
                        detail('<div class="a-row"><span>New tax service:</span><div class="a-span-last">$0.00</div></div>')]:
            self.assertFalse(bridge.financial_evidence(invalid)["complete"])

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install the reviewed requirements lock")
    def test_real_payment_parser_preserves_pages_and_fails_closed_before_flattening(self):
        from amazonorders.conf import AmazonOrdersConfig
        from bs4 import BeautifulSoup
        config = AmazonOrdersConfig(data={"domain": "amazon.com"})
        today = dt.date.today()

        def row(amount="-$65.00", orders=None):
            return {"formattedDate": today.strftime("%B %d, %Y"), "formattedAmount": amount,
                    "orderData": orders if orders is not None else [{"orderDisplayString": "Order #113-8849250-2919462"}],
                    "paymentMethodDisplayStringData": {"paymentMethodName": "Visa", "paymentMethodNumber": {"lastDigits": "1234"}}}

        def page(records, more=False, key=None):
            state = {"visibleTransactionResponse": {"transactionList": records}, "hasMore": more, "lastEvaluatedPageKey": key}
            payload = {"props": {"pageProps": {"state": {"transactionResponseState": state}, "token": "fixture", "request": {"locale": "en_US"}}}}
            return types.SimpleNamespace(parsed=BeautifulSoup('<script id="__NEXT_DATA__">' + json.dumps(payload) + '</script>', "html.parser"))

        session = types.SimpleNamespace(config=config, check_response=lambda *args, **kwargs: None)
        calls = []
        def post(url, **kwargs):
            calls.append((url, kwargs))
            if "json" in kwargs:
                return types.SimpleNamespace(response=types.SimpleNamespace(json=lambda: {"displayResponse": {"transactionsList": [row("$10.00")]}}))
            return page([row()], True, "cursor-1")
        session.post = post
        events = bridge.payment_history(session, 30, "113-8849250-2919462")
        self.assertEqual([-6500, 1000], [e["signed_cents"] for e in events])
        self.assertTrue(all(e["complete"] for e in events), events)
        self.assertEqual("python:visa:1234", events[0]["payment_identity"])
        self.assertIn("transactionTag=113-8849250-2919462", calls[0][0])
        self.assertEqual("cursor-1", calls[1][1]["json"]["exclusiveStartKey"])
        for records in [[row(), row()], [row(orders=[{"orderDisplayString": "Order #113-8849250-2919462"}, {"orderDisplayString": "Order #114-1111111-2222222"}])], [row("-$65.00 CAD")]]:
            session.post = lambda *args, **kwargs: page(records)
            self.assertTrue(all(not e["complete"] for e in bridge.payment_history(session, 30)))
        session.post = lambda *args, **kwargs: page([row()], True, None)
        with self.assertRaises(ValueError):
            bridge.payment_history(session, 30)
        session.post = lambda *args, **kwargs: types.SimpleNamespace(response=types.SimpleNamespace(json=lambda:
            {"displayResponse": {"transactionsList": [row()], "lastEvaluatedKey": "repeated"}})) if "json" in kwargs else page([row()], True, "repeated")
        with self.assertRaises(ValueError):
            bridge.payment_history(session, 30)

    def test_recent_credit_fetches_old_original_without_purchase_lookback_or_guessed_refund_items(self):
        event = {"key": "credit", "order_id": "113-8849250-2919462", "signed_cents": 6500,
                 "date": dt.date.today().isoformat(), "complete": True, "payment_identity": "python:visa:1234", "currency": "USD"}
        with patch.object(bridge, "payment_history", return_value=[event]) as payments:
            code, output, errors, _ = self.run_bridge(arguments=["--transaction-evidence"])
        self.assertEqual((0, ""), (code, errors))
        self.assertEqual([event["order_id"]], self.details)
        record = json.loads(output)["orders"][0]
        self.assertEqual((dt.date.today() - dt.timedelta(days=500)).isoformat(), record["order_placed_date"])
        self.assertEqual([event], record["payment_events"])
        self.assertNotIn("refund_evidence", record)
        self.assertEqual(1, payments.call_count)

    def test_recent_charges_retain_competitors_and_corroboration_preserves_occurrences(self):
        today = dt.date.today()
        current, old = "113-8849250-2919462", "114-1111111-2222222"
        charge = {"key": "current", "order_id": current, "signed_cents": -6500,
                  "date": today.isoformat(), "complete": True, "payment_identity": "python:visa:1234", "currency": "USD"}
        competitor = dict(charge, key="old", order_id=old)
        for mode in ("corroborated", "detail_failure", "missing_detail_event", "global_duplicate", "detail_duplicate", "global_failure"):
            with self.subTest(mode=mode):
                def ledger(session, days, order_id=None):
                    if order_id is None:
                        if mode == "global_failure":
                            raise RuntimeError("unavailable history")
                        return [charge, competitor, *([charge] if mode == "global_duplicate" else [])]
                    if mode == "detail_failure":
                        raise RuntimeError("unavailable order ledger")
                    if mode == "missing_detail_event":
                        return []
                    return [charge, *([charge] if mode == "detail_duplicate" else [])]

                with patch.object(bridge, "payment_history", side_effect=ledger):
                    code, output, errors, _ = self.run_bridge({today.year: [Order(current, today, 65)]}, ["--transaction-evidence"])
                self.assertEqual((0, ""), (code, errors))
                records = {r["order_number"]: r for r in json.loads(output)["orders"]}
                self.assertEqual([current], self.details)  # No historical purchase-detail backfill.
                if mode != "global_failure":
                    self.assertEqual([dict(competitor, competition_only=True)], records[old]["payment_events"])
                events = records[current]["payment_events"]
                self.assertEqual(2 if mode.endswith("duplicate") else 1, len(events))
                self.assertTrue(all(e["key"] == "current" for e in events))
                self.assertEqual(mode not in ("global_duplicate", "detail_duplicate", "global_failure"), all(e["complete"] for e in events))
                self.assertEqual(mode == "corroborated", records[current]["financial_evidence"]["payments_complete"])

    def test_preflight_rejects_unreviewed_auth_version(self):
        output, errors = io.StringIO(), io.StringIO()
        versions = {"amazon-orders": "6.0.0", "amazon-auth": "1.0.0"}
        with patch("importlib.metadata.version", side_effect=versions.__getitem__), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "unused.yml", "--preflight"])
        self.assertEqual((1, ""), (code, output.getvalue()))


if __name__ == "__main__":
    unittest.main()
