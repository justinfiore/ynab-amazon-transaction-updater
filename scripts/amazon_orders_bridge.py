#!/usr/bin/env python3
"""Machine-readable, non-interactive adapter for amazon-orders history."""
import argparse
import datetime as dt
import getpass
import importlib
import importlib.metadata
import json
import logging
import sys
from urllib.parse import urlsplit

SCHEMA_VERSION = 1
LOGIN_REQUIRED_EXIT_CODE = 3


class LoginRequired(Exception):
    pass


class NonInteractiveIO:
    def echo(self, msg, **kwargs):
        # Upstream messages can contain page text or account information.
        pass

    def prompt(self, msg, **kwargs):
        raise LoginRequired("Amazon authentication requires interactive input")


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


def verify_runtime():
    if sys.version_info < (3, 11, 5):
        raise RuntimeError("Use a maintained Python runtime")
    for package, version in (("amazon-orders", "6.0.0"), ("amazon-auth", "1.1.0")):
        if importlib.metadata.version(package) != version:
            raise RuntimeError("Install the reviewed requirements lock")


def create_session(config_path, interactive=False):
    config_class = getattr(importlib.import_module("amazonorders.conf"), "AmazonOrdersConfig")
    session_class = getattr(importlib.import_module("amazonorders.session"), "AmazonSession")
    from requests.adapters import HTTPAdapter
    from amazonauth.exception import AmazonAuthSignInError
    from amazonauth.forms import CaptchaForm, AcicAuthBlocker, JSAuthBlocker
    from amazonauth.session import IODefault

    class BridgeConfig(config_class):
        def _validate_bs4_parser(self):
            # This is the first validation hook, before configured modules load.
            defaults = self.default_data()
            for key in (*[k for k in defaults if k.endswith("_class")],
                        "auth_forms_classes", "language_package", "bs4_parser"):
                if self._get(key) != defaults[key]:
                    raise ValueError("Custom Python classes/parsers are disabled in the bridge")
            super()._validate_bs4_parser()

    config = BridgeConfig(config_path=config_path)
    origin = urlsplit(config.constants.BASE_URL)

    class AmazonOnlyAdapter(HTTPAdapter):
        def send(self, request, **kwargs):
            target = urlsplit(request.url)
            if (target.scheme != "https" or target.hostname != origin.hostname or
                    target.port not in (None, 443) or target.username is not None or target.password is not None):
                raise ValueError("Refused a request outside the configured Amazon HTTPS origin")
            # Covers direct form/image requests and every redirect, not just the
            # public AmazonSession.request wrapper. No unlimited network waits.
            kwargs["timeout"] = (10, 30)
            kwargs["verify"] = True
            kwargs["proxies"] = {}
            return super().send(request, **kwargs)

    class ObservedSession(session_class):
        # SignInError has no response metadata. Keep the structured response
        # from the public request API, not exception text, for classification.
        last_response = None

        def _create_session(self):
            client = super()._create_session()
            # Ignore ambient proxy/CA settings and .netrc authentication.
            client.trust_env = False
            client.mount("https://", AmazonOnlyAdapter())
            client.mount("http://", AmazonOnlyAdapter())
            return client

        def _process_forms(self, response):
            for form in self.auth_forms:
                if isinstance(form, (CaptchaForm, AcicAuthBlocker, JSAuthBlocker)):
                    try:
                        selected = form.select_form(self, response.parsed)
                    except AmazonAuthSignInError as error:
                        # The pinned JS/ACIC blockers raise on recognized markup.
                        raise LoginRequired("Amazon browser challenge") from error
                    if selected:
                        # Do not open image viewers/browsers or contact paid solvers.
                        raise LoginRequired("Amazon CAPTCHA challenge")
            return super()._process_forms(response)

        def request(self, *args, **kwargs):
            self.last_response = None
            self.last_response = super().request(*args, **kwargs)
            # login() processes forms without checking the HTTP status first.
            # Do not prompt or treat error-page markup as authentication evidence.
            self.last_response.response.raise_for_status()
            return self.last_response

    credentials = {}
    if interactive:
        credentials["username"] = config.username or input("Amazon email: ")
        credentials["password"] = config.password or getpass.getpass("Amazon password: ")
    return ObservedSession(config=config, io=IODefault() if interactive else NonInteractiveIO(),
                           debug=False, **credentials)


def load_history(config_path, look_back_days, account_only=False):
    session = create_session(config_path)
    config = session.config
    orders_class = getattr(importlib.import_module("amazonorders.orders"), "AmazonOrders")
    auth_errors = importlib.import_module("amazonauth.exception")
    try:
        session.login()
        orders = orders_class(session, config=config)
        cutoff = dt.date.today() - dt.timedelta(days=look_back_days)
        # Fetch all calendar years covering the window, then filter locally.
        # None selects the whole Household; "self" selects only this account.
        history = []
        seen = set()
        for year in range(cutoff.year, dt.date.today().year + 1):
            summaries = orders.get_orders(year=year, full_details=False,
                                          family_member_id="self" if account_only else None)
            for order in summaries:
                placed = date_value(value(order, "order_placed_date"))
                number = value(order, "order_number")
                if not placed or placed < cutoff or not number or number in seen or value(order, "cancelled"):
                    continue
                seen.add(number)
                # Reuse the pinned parser's detail builder so Whole Foods and
                # unsupported summary types keep their existing semantics.
                history.append(orders._build_order(order.parsed, full_details=True, current_index=order.index))
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
    finally:
        session.session.close()


def preflight(config_path):
    module = importlib.import_module("amazonorders")
    print(json.dumps({"schema_version": SCHEMA_VERSION, "package_version": getattr(module, "__version__", "unknown"), "config_path": config_path}))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Emit amazon-orders history as schema-v1 JSON.")
    parser.add_argument("--config-path", required=True)
    parser.add_argument("--look-back-days", type=int, default=30)
    parser.add_argument("--account-only", action="store_true", help="Exclude other Amazon Household members' orders.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--preflight", action="store_true", help="Verify package/schema without fetching account data.")
    mode.add_argument("--login", action="store_true", help="Interactive password/OTP login with the bridge's network restrictions.")
    args = parser.parse_args(argv)
    previous_logging_level = logging.root.manager.disable
    logging.disable(logging.CRITICAL)  # Upstream diagnostics can include account/page data.
    try:
        verify_runtime()
        if args.preflight:
            preflight(args.config_path)
            return 0
        if args.login:
            session = create_session(args.config_path, interactive=True)
            try:
                session.login()
            finally:
                session.session.close()
            print("Amazon session saved; protect its cookie file as a password.")
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
        print("Amazon login required; run this bridge with --login and the same --config-path; browser/CAPTCHA flows are disabled", file=sys.stderr)
        return LOGIN_REQUIRED_EXIT_CODE
    except Exception:
        print("amazon-orders bridge failed; check runtime configuration and Amazon availability", file=sys.stderr)
        return 1
    finally:
        logging.disable(previous_logging_level)


if __name__ == "__main__":
    sys.exit(main())
