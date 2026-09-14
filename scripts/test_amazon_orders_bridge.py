import datetime as dt
import importlib.util
import io
import json
import sys
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

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
    def run_bridge(self, history_by_year=None, arguments=(), session_error=None, history_error=None):
        calls = []
        root = types.ModuleType("amazonorders")
        root.__version__ = "4.4.7"
        conf = types.ModuleType("amazonorders.conf")
        conf.AmazonOrdersConfig = lambda **_: object()
        session = types.ModuleType("amazonorders.session")
        session.AmazonSession = lambda **_: (_ for _ in ()).throw(session_error) if session_error else object()
        orders = types.ModuleType("amazonorders.orders")

        def history(**kwargs):
            calls.append(kwargs)
            if history_error:
                raise history_error
            return (history_by_year or {}).get(kwargs["year"], [])

        orders.AmazonOrders = lambda *_args, **_kwargs: types.SimpleNamespace(get_order_history=history)
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
        self.assertEqual(["first", "last"], [record["order_number"] for record in json.loads(output)["orders"]])

    def test_serializes_complete_items_payment_and_missing_quantity(self):
        today = dt.date.today()
        order = Order("complete", today, 42.17, [Item("First", "ASIN1", 1.25, 2), Item("Second", "ASIN2", 40.92)], payment_method="Visa", last_four=1234)
        code, output, errors, _ = self.run_bridge({today.year: [order]})
        record = json.loads(output)["orders"][0]
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual("Visa", record["payment_method"])
        self.assertEqual(1234, record["payment_method_last_4"])
        self.assertEqual([{"title": "First", "asin": "ASIN1", "price": 1.25, "quantity": 2}, {"title": "Second", "asin": "ASIN2", "price": 40.92, "quantity": 1}], record["items"])
        self.assertNotIn("recipient", json.dumps(record))
        self.assertNotIn("shipping_address", json.dumps(record))

    def test_excludes_cancelled_invalid_duplicate_and_returns_empty_successfully(self):
        today = dt.date.today()
        history = {today.year: [Order("kept", today, 1), Order("kept", today, 2), Order("cancelled", today, 1, cancelled=True), Order(None, today, 1), Order("zero", today, 0)]}
        code, output, errors, _ = self.run_bridge(history)
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual(["kept"], [record["order_number"] for record in json.loads(output)["orders"]])
        code, output, errors, _ = self.run_bridge({today.year: []})
        self.assertEqual((0, ""), (code, errors))
        self.assertEqual({"schema_version": 1, "orders": []}, json.loads(output))

    def test_preflight_is_stdout_only_and_never_constructs_session(self):
        code, output, errors, calls = self.run_bridge(arguments=["--preflight"], session_error=RuntimeError("should not run"))
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual([], calls)
        payload = json.loads(output)
        self.assertEqual(1, payload["schema_version"])
        self.assertEqual("4.4.7", payload["package_version"])

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
