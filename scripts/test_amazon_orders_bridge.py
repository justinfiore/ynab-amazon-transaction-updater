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


class BridgeTests(unittest.TestCase):
    def run_bridge(self, history, arguments=[]):
        fake = types.ModuleType("amazonorders")
        fake.__version__ = "4.4.7"
        conf = types.ModuleType("amazonorders.conf")
        conf.AmazonOrdersConfig = lambda **_: object()
        session = types.ModuleType("amazonorders.session")
        session.AmazonSession = lambda **_: object()
        orders = types.ModuleType("amazonorders.orders")
        orders.AmazonOrders = lambda *_args, **_kwargs: types.SimpleNamespace(get_order_history=lambda **_: history)
        output, errors = io.StringIO(), io.StringIO()
        with patch.dict(sys.modules, {"amazonorders": fake, "amazonorders.conf": conf, "amazonorders.session": session, "amazonorders.orders": orders}), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "/safe/config.yml", *arguments])
        return code, output.getvalue(), errors.getvalue()

    def test_emits_filtered_schema_without_sensitive_fields(self):
        today = dt.date.today()
        history = [
            {"order_number": "new", "order_date": today.isoformat(), "grand_total": 4.5,
             "items": [{"title": "item", "asin": "A", "price": 4.5}], "recipient": "private"},
            {"order_number": "old", "order_date": (today - dt.timedelta(days=2)).isoformat(), "grand_total": 2},
            {"order_number": "new", "order_date": today.isoformat(), "grand_total": 9},
            {"order_number": "cancelled", "order_date": today.isoformat(), "grand_total": 2, "cancelled": True},
            {"order_number": "invalid", "order_date": today.isoformat(), "grand_total": 0},
        ]
        code, output, errors = self.run_bridge(history, ["--look-back-days", "1"])
        payload = json.loads(output)
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual(1, payload["schema_version"])
        self.assertEqual(["new"], [o["order_number"] for o in payload["orders"]])
        self.assertNotIn("recipient", json.dumps(payload))

    def test_preflight_does_not_fetch_history(self):
        code, output, errors = self.run_bridge([], ["--preflight"])
        self.assertEqual(0, code)
        self.assertEqual("", errors)
        self.assertEqual(1, json.loads(output)["schema_version"])

    def test_dependency_failure_writes_only_stderr(self):
        output, errors = io.StringIO(), io.StringIO()
        with patch("importlib.import_module", side_effect=ImportError("missing")), redirect_stdout(output), redirect_stderr(errors):
            code = bridge.main(["--config-path", "/safe/config.yml"])
        self.assertEqual(1, code)
        self.assertEqual("", output.getvalue())
        self.assertIn("bridge failed", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
