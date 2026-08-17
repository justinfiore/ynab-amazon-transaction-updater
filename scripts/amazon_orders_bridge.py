#!/usr/bin/env python3
"""Machine-readable, non-interactive adapter for amazon-orders history."""
import argparse
import datetime as dt
import importlib
import json
import sys

SCHEMA_VERSION = 1


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
        return dt.date.fromisoformat(raw[:10])
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


def load_history(config_path, look_back_days):
    module = importlib.import_module("amazonorders")
    config_class = getattr(importlib.import_module("amazonorders.conf"), "AmazonOrdersConfig")
    session_class = getattr(importlib.import_module("amazonorders.session"), "AmazonSession")
    orders_class = getattr(importlib.import_module("amazonorders.orders"), "AmazonOrders")
    config = config_class(config_path=config_path)
    session = session_class(config=config)
    orders = orders_class(session, config=config)
    cutoff = dt.date.today() - dt.timedelta(days=look_back_days)
    # These are the documented public APIs in amazon-orders 4.4.7. Fetch full
    # calendar years covering the exact window, then filter locally below.
    history = []
    for year in range(cutoff.year, dt.date.today().year + 1):
        history.extend(orders.get_order_history(year=year, full_details=True))
    return history


def preflight(config_path):
    module = importlib.import_module("amazonorders")
    print(json.dumps({"schema_version": SCHEMA_VERSION, "package_version": getattr(module, "__version__", "unknown"), "config_path": config_path}))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Emit amazon-orders history as schema-v1 JSON.")
    parser.add_argument("--config-path", required=True)
    parser.add_argument("--look-back-days", type=int, default=30)
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
        for order in load_history(args.config_path, args.look_back_days):
            record = serialize(order)
            order_date = date_value(record["order_placed_date"])
            if (order_date and order_date >= cutoff and record["order_number"] and
                    isinstance(record["grand_total"], (int, float)) and record["grand_total"] > 0 and
                    not record["cancelled"] and record["order_number"] not in unique):
                unique[record["order_number"]] = record
        print(json.dumps({"schema_version": SCHEMA_VERSION, "orders": list(unique.values())}, default=str, separators=(",", ":")))
        return 0
    except Exception as error:
        print("amazon-orders bridge failed: " + str(error)[:500], file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
