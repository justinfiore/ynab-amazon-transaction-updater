#!/usr/bin/env python3
"""Machine-readable, non-interactive adapter for amazon-orders history."""
import argparse
import datetime as dt
import getpass
import importlib
import importlib.metadata
import json
import logging
import os
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


def browser_auth_forms(config, session_class, origin):
    # Imports and browser startup are reachable only through explicit manual login.
    from amazonauth.contrib.browser.playwright import PlaywrightAcicForm, PlaywrightJSAuthForm

    class GuardedBrowserForm:
        def submit(self, last_response):
            from playwright.sync_api import sync_playwright

            def allowed(url):
                target = urlsplit(url)
                return (target.scheme == "https" and target.hostname == origin.hostname and
                        target.port in (None, 443) and target.username is None and target.password is None)

            if not allowed(last_response.url):
                raise ValueError("Refused a browser challenge outside the configured Amazon HTTPS origin")

            # Do not inherit account credentials, proxy settings or browser overrides.
            environment = {key: val for key, val in os.environ.items()
                           if key in {"PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR",
                                      "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "TZ"}}

            def forward(route):
                if not allowed(route.request.url):
                    route.abort()
                    return
                # Chromium does not re-route every HTTP redirect. Never let an
                # intercepted request follow a redirect outside this guard.
                response = route.fetch(max_redirects=0, timeout=config.browser_timeout * 1000)
                if 300 <= response.status < 400:
                    route.abort()
                else:
                    route.fulfill(response=response)

            # The Playwright Node driver also performs HTTP requests and must
            # not inherit executable overrides, NODE_OPTIONS or TLS overrides.
            previous_environment = dict(os.environ)
            try:
                os.environ.clear()
                os.environ.update(environment)
                with sync_playwright() as pw:
                    browser = pw.chromium.launch(
                        headless=True, chromium_sandbox=True, env=environment,
                        args=["--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=<-loopback>",
                              "--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--disable-quic"])
                    try:
                        # Chromium's own traffic has a dead-end proxy; only the
                        # guarded driver fetches above can reach HTTPS origins.
                        context = browser.new_context(service_workers="block", accept_downloads=False)
                        context.set_default_timeout(config.browser_timeout * 1000)
                        context.set_default_navigation_timeout(config.browser_timeout * 1000)
                        context.route("**/*", forward)
                        context.route_web_socket("**/*", lambda socket: socket.close())
                        self._inject_cookies(context, last_response.url)
                        page = context.new_page()
                        page.goto(last_response.url)
                        self._on_challenge_page(page, context, None)
                        page.wait_for_url(lambda url: not self._is_challenge_url(url, last_response.url))
                        final_url = page.url
                        if not allowed(final_url):
                            raise ValueError("Refused a browser redirect outside the configured Amazon HTTPS origin")
                        self._harvest_cookies(context)
                    finally:
                        browser.close()
            finally:
                os.environ.clear()
                os.environ.update(previous_environment)
            response = self.amazon_session.get(final_url, persist_cookies=True)
            self.clear_form()
            return response

    class BrowserAcicForm(GuardedBrowserForm, PlaywrightAcicForm):
        pass

    class BrowserJSForm(GuardedBrowserForm, PlaywrightJSAuthForm):
        def _on_challenge_page(self, page, context, output_dir):
            # The challenge may already have resolved during goto(). Unlike
            # upstream, do not require a successful page to grow again.
            page.wait_for_function(
                "([pattern, markupPattern]) => {"
                "  const text = document.body && document.body.innerText || '';"
                "  return text.trim().length > 0 && !new RegExp(pattern, 'i').test(text) &&"
                "    !new RegExp(markupPattern).test(document.documentElement.innerHTML);"
                "}", arg=[self.regex, self.markup_regex])

    forms = session_class.default_auth_forms(config)
    # Keep the library's normal password/OTP chain and its fail-closed blockers.
    forms[-2:-2] = [BrowserAcicForm(config), BrowserJSForm(config)]
    return forms


def create_session(config_path, interactive=False, browser_login=False):
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
                if isinstance(form, CaptchaForm) or (not browser_login and isinstance(form, (AcicAuthBlocker, JSAuthBlocker))):
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
    forms = browser_auth_forms(config, session_class, origin) if browser_login else None
    return ObservedSession(config=config, io=IODefault() if interactive else NonInteractiveIO(),
                           debug=False, auth_forms=forms, **credentials)


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
    mode.add_argument("--browser-login", action="store_true", help="Manual login with fixed, same-origin Chromium ACIC/JS challenge handlers.")
    args = parser.parse_args(argv)
    previous_logging_level = logging.root.manager.disable
    logging.disable(logging.CRITICAL)  # Upstream diagnostics can include account/page data.
    try:
        verify_runtime()
        if args.preflight:
            preflight(args.config_path)
            return 0
        if args.browser_login:
            if not sys.stdin.isatty():
                print("Amazon browser login requires an interactive terminal", file=sys.stderr)
                return 1
            for package, version in (("playwright", "1.65.2"), ("greenlet", "3.5.6")):
                if importlib.metadata.version(package) != version:
                    raise RuntimeError("Install the reviewed browser requirements lock")
        if args.login or args.browser_login:
            session = create_session(args.config_path, interactive=True, browser_login=args.browser_login)
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
        print("Amazon login required; use --login or the optional --browser-login with the same --config-path; unattended browser/CAPTCHA flows are disabled", file=sys.stderr)
        return LOGIN_REQUIRED_EXIT_CODE
    except Exception:
        if args.browser_login:
            print("Amazon browser login failed; check the browser requirements lock, Chromium sandbox and supported challenge restrictions", file=sys.stderr)
        else:
            print("amazon-orders bridge failed; check runtime configuration and Amazon availability", file=sys.stderr)
        return 1
    finally:
        logging.disable(previous_logging_level)


if __name__ == "__main__":
    sys.exit(main())
