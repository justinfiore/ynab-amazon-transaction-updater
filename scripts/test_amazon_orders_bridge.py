import datetime as dt
import importlib.util
import io
import json
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


class Item:
    def __init__(self, title, asin, price, quantity=None):
        self.title, self.asin, self.price, self.quantity = title, asin, price, quantity


class BridgeTests(unittest.TestCase):
    def run_bridge(self, history_by_year=None, arguments=(), session_error=None, history_error=None, household_orders=(), challenge=False):
        calls = []
        root = types.ModuleType("amazonorders")
        root.__version__ = "6.0.0"
        conf = types.ModuleType("amazonorders.conf")
        conf.AmazonOrdersConfig = lambda **_: object()
        session = types.ModuleType("amazonorders.session")
        class Session:
            def __init__(self, **kwargs):
                self.is_authenticated = False
                self.io = kwargs.get("io")

            def login(self):
                if challenge:
                    self.io.echo("Authentication challenge")
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
            return types.SimpleNamespace(get_orders=authenticated_history)

        orders.AmazonOrders = order_client
        output, errors = io.StringIO(), io.StringIO()
        modules = {"amazonorders": root, "amazonorders.conf": conf, "amazonorders.session": session, "amazonorders.orders": orders}
        with patch.dict(sys.modules, modules), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "/safe/config.yml", *arguments])
        return code, output.getvalue(), errors.getvalue(), calls

    def test_exact_inclusive_boundaries_across_years_and_deduplicates(self):
        today = dt.date.today()
        cutoff = today - dt.timedelta(days=400)
        history = {
            cutoff.year: [Order("first", cutoff, 1), Order("outside", cutoff - dt.timedelta(days=1), 1)],
            today.year: [Order("last", today, 2), Order("first", today, 9)],
        }
        code, output, errors, calls = self.run_bridge(history, ["--look-back-days", "400"])
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual([cutoff.year, today.year], [call["year"] for call in calls])
        self.assertTrue(all(call["full_details"] for call in calls))
        self.assertTrue(all(call["family_member_id"] is None for call in calls))
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
        self.assertEqual((1, ""), (code, output))
        self.assertIn("Authentication challenge", errors)
        self.assertIn("requires interactive input", errors)
        self.assertNotIn("sensitive challenge data", errors)

    @unittest.skipUnless(importlib.util.find_spec("amazonorders"), "install requirements-amazon-orders.txt for the real-library contract test")
    def test_authenticates_with_real_library_before_fetching_history(self):
        from amazonorders.orders import AmazonOrders
        from requests import Response

        response = Response()
        response.status_code = 200
        response.url = "https://www.amazon.com/"
        response._content = b'<span id="nav-link-accountList-nav-line-1">Hello, Fixture</span>'
        today = dt.date.today()
        order = Order("real-api", today, 12.34, last_four="0012")
        output, errors = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            config_path = os.path.join(directory, "config.yml")
            with open(config_path, "w") as config:
                json.dump({"cookie_jar_path": os.path.join(directory, "cookies.json"), "output_dir": directory}, config)
            # Stub HTTP/history retrieval, not session construction, login, or the
            # public get_orders authentication guard. No real credentials/network.
            with patch.dict(os.environ, {}, clear=True), patch("requests.sessions.Session.request", return_value=response), \
                    patch.object(AmazonOrders, "_build_orders_async", new_callable=AsyncMock, return_value=([order], None, None, None)), \
                    redirect_stdout(output), redirect_stderr(errors):
                code = bridge.main(["--config-path", config_path])
        self.assertEqual((0, ""), (code, errors.getvalue()))
        self.assertEqual("real-api", json.loads(output.getvalue())["orders"][0]["order_number"])

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


if __name__ == "__main__":
    unittest.main()
