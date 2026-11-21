#!/usr/bin/env python3
"""Machine-readable, non-interactive adapter for amazon-orders history."""
import argparse
import datetime as dt
import importlib
import json
import sys

SCHEMA_VERSION = 1
LOGIN_REQUIRED_EXIT_CODE = 3


class LoginRequired(Exception):
    pass


class NonInteractiveIO:
    def echo(self, msg, **kwargs):
        print(msg, file=sys.stderr)

    def prompt(self, msg, **kwargs):
        raise LoginRequired("Amazon authentication requires interactive input; refresh the session with amazon-orders login")


def value(source, *names):
    for name in names:
        if isinstance(source, dict):
            if source.get(name) is not None:
                return source[name]
            continue
        if hasattr(source, name) and getattr(source, name) is not None:
            return getattr(source, name)
    return None


def date_value(raw):
    if isinstance(raw, dt.datetime):
        return raw.date()
    if isinstance(raw, dt.date):
        return raw
    if isinstance(raw, str):
        try:
            return dt.date.fromisoformat(raw[:10])
        except ValueError:
            return None
    return None


def serialize(order):
    items = []
    for item in value(order, "items", "order_items") or []:
        items.append({
            "title": value(item, "title", "name"),
            "asin": value(item, "asin"),
            "price": value(item, "price", "item_price"),
            "quantity": value(item, "quantity") or 1,
        })
    return {
        "order_number": value(order, "order_number", "order_id", "id"),
        "order_placed_date": str(date_value(value(order, "order_placed_date", "order_date", "date")) or ""),
        "grand_total": value(order, "grand_total", "total"),
        "payment_method": value(order, "payment_method", "payment_method_name"),
        "payment_method_last_4": value(order, "payment_method_last_4", "payment_last_four"),
        "cancelled": bool(value(order, "cancelled", "is_cancelled")),
        "items": items,
    }


def load_history(config_path, look_back_days, account_only=False):
    module = importlib.import_module("amazonorders")
    config_class = getattr(importlib.import_module("amazonorders.conf"), "AmazonOrdersConfig")
    session_class = getattr(importlib.import_module("amazonorders.session"), "AmazonSession")
    orders_class = getattr(importlib.import_module("amazonorders.orders"), "AmazonOrders")
    config = config_class(config_path=config_path)

    class ObservedSession(session_class):
        # SignInError has no response metadata. Keep the structured response
        # from the public request API, not exception text, for classification.
        last_response = None

        def request(self, *args, **kwargs):
            self.last_response = None
            self.last_response = super().request(*args, **kwargs)
            # login() processes forms without checking the HTTP status first.
            # Do not prompt or treat error-page markup as authentication evidence.
            self.last_response.response.raise_for_status()
            return self.last_response

    session = ObservedSession(config=config, io=NonInteractiveIO())
    auth_errors = importlib.import_module("amazonauth.exception")
    try:
        session.login()
        orders = orders_class(session, config=config)
        cutoff = dt.date.today() - dt.timedelta(days=look_back_days)
        # Fetch all calendar years covering the window, then filter locally.
        # None selects the whole Household; "self" selects only this account.
        history = []
        for year in range(cutoff.year, dt.date.today().year + 1):
            history.extend(orders.get_orders(year=year, full_details=True,
                                            family_member_id="self" if account_only else None))
        return history
    except auth_errors.AmazonAuthRedirectError as error:
        raise LoginRequired("Amazon sign-in is required") from error
    except auth_errors.AmazonAuthSignInError as error:
        # Upstream also raises SignInError for HTTP failures, unknown HTML,
        # exhausted retries and unsupported flows. Only a successful response
        # with both a credential form and its error box is a recognized rejection.
        response = session.last_response
        if response is not None and response.response.ok and response.parsed is not None:
            select_one = importlib.import_module("amazonauth.util").select_one
            selectors = config.selectors
            if (select_one(response.parsed, selectors.DEFAULT_ERROR_TAG_SELECTOR) is not None and
                    (select_one(response.parsed, selectors.SIGN_IN_FORM_SELECTOR) is not None or
                     select_one(response.parsed, selectors.CLAIM_FORM_SELECTOR) is not None)):
                raise LoginRequired("Amazon rejected sign-in") from error
        raise


def preflight(config_path):
    module = importlib.import_module("amazonorders")
    print(json.dumps({"schema_version": SCHEMA_VERSION, "package_version": getattr(module, "__version__", "unknown"), "config_path": config_path}))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Emit amazon-orders history as schema-v1 JSON.")
    parser.add_argument("--config-path", required=True)
    parser.add_argument("--look-back-days", type=int, default=30)
    parser.add_argument("--account-only", action="store_true", help="Exclude other Amazon Household members' orders.")
    parser.add_argument("--preflight", action="store_true", help="Verify package/schema without fetching account data.")
    args = parser.parse_args(argv)
    try:
        if args.preflight:
            preflight(args.config_path)
            return 0
        if args.look_back_days <= 0:
            raise ValueError("look-back-days must be positive")
        cutoff = dt.date.today() - dt.timedelta(days=args.look_back_days)
        unique = {}
        for order in load_history(args.config_path, args.look_back_days, args.account_only):
            record = serialize(order)
            order_date = date_value(record["order_placed_date"])
            if (order_date and order_date >= cutoff and record["order_number"] and
                    isinstance(record["grand_total"], (int, float)) and record["grand_total"] > 0 and
                    not record["cancelled"] and record["order_number"] not in unique):
                unique[record["order_number"]] = record
        print(json.dumps({"schema_version": SCHEMA_VERSION, "orders": list(unique.values())}, default=str, separators=(",", ":")))
        return 0
    except LoginRequired:
        print("Amazon login required; run amazon-orders login with the configured account and config path", file=sys.stderr)
        return LOGIN_REQUIRED_EXIT_CODE
    except Exception:
        print("amazon-orders bridge failed; check runtime configuration and Amazon availability", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
