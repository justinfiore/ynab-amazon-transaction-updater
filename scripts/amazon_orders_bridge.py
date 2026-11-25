#!/usr/bin/env python3
"""Machine-readable, non-interactive adapter for amazon-orders history."""
import argparse
import datetime as dt
import getpass
import importlib
import importlib.metadata
import json
import logging
import re
import hashlib
from decimal import Decimal, InvalidOperation
import sys
from urllib.parse import parse_qs, urlsplit

SCHEMA_VERSION = 1
LOGIN_REQUIRED_EXIT_CODE = 3
ADJUSTMENTS = {
    "shipping_total": "shipping", "free_shipping": "discount",
    "promotion_applied": "discount", "coupon_savings": "discount",
    "subscription_discount": "discount", "multibuy_discount": "discount",
    "amazon_discount": "discount", "estimated_tax": "tax", "gift_wrap": "fee",
}


def cents(raw):
    if raw is None or isinstance(raw, bool):
        return None
    try:
        amount = Decimal(str(raw)) * 100
        return int(amount) if amount.is_finite() and amount == amount.to_integral_value() else None
    except (InvalidOperation, ValueError, OverflowError):
        return None


def financial_evidence(order):
    """Versioned allowlist. Unknown layout/absence is not a zero adjustment."""
    fields = {name: cents(value(order, name)) for name in
              (*ADJUSTMENTS, "subtotal", "total_before_tax", "grand_total", "gift_card", "reward_points", "refund_total")}
    items = []
    for index, item in enumerate(value(order, "items") or []):
        quantity = value(item, "quantity")
        price = cents(value(item, "price"))
        items.append({"itemId": str(index), "productId": value(item, "asin"), "title": value(item, "title"),
                      "quantity": quantity, "cents": price * quantity if price is not None and
                      isinstance(quantity, int) and not isinstance(quantity, bool) and quantity > 0 else None})
    # The library strips currency and can retain history grand_total when cloning.
    # Retain both totals; qualify only explicit, nonoverlapping detail labels.
    detail_total = None
    parsed = value(order, "parsed")
    config = value(order, "config")
    rows = []
    if parsed is not None and config is not None:
        from amazonauth.util import select
        rows = select(parsed, config.selectors.FIELD_ORDER_SUBTOTALS_TAG_ITERATOR_SELECTOR)
        # The current totals selector includes this nonfinancial section heading.
        rows = [row for row in rows if not (row.select_one('h5') is not None and
                row.get_text(' ', strip=True) == 'Order Summary')]
        try:
            detail_total = cents(order._parse_grand_total())
        except Exception:
            pass
    # This is a qualified US detail layout, not substring-based permission for
    # arbitrary future labels. A missing/unparseable known field is not zero.
    label_fields = {"item(s) subtotal": "subtotal", "subtotal": "subtotal", "shipping & handling": "shipping_total",
                    "shipping": "shipping_total", "free shipping": "free_shipping", "promotion applied": "promotion_applied",
                    "coupon savings": "coupon_savings", "your coupon savings": "coupon_savings", "subscribe & save": "subscription_discount",
                    "subscription discount": "subscription_discount", "multibuy discount": "multibuy_discount",
                    "amazon discount": "amazon_discount", "estimated tax": "estimated_tax", "tax collected": "estimated_tax",
                    "estimated tax to be collected": "estimated_tax",
                    "gift wrap": "gift_wrap", "total before tax": "total_before_tax", "grand total": "grand_total",
                    "total for this order": "grand_total", "gift card amount": "gift_card", "gift card": "gift_card",
                    "reward points": "reward_points", "refund total": "refund_total"}
    observed = []
    labels_known = bool(rows)
    for row in rows:
        text = row.get_text(" ", strip=True).lower()
        match = re.fullmatch(r"(.+?)\s*:?\s+(-?\$[0-9,]+\.[0-9]{2})", text)
        label = match[1].rstrip(": ") if match else None
        amount = cents(match[2].replace("$", "").replace(",", "")) if match else None
        # This exact informational subtotal is not an adjustment or payment.
        # Still reject duplicate, malformed, negative or non-USD rows.
        if label == "fsa or hsa eligible: (inc. tax and shipping)":
            if label in observed or amount is None or amount < 0:
                labels_known = False
            observed.append(label)
            continue
        field = label_fields.get(label)
        if not field or field in observed or amount is None or (field != "grand_total" and fields[field] != amount):
            labels_known = False
        observed.append(field)
    usd = bool(config and urlsplit(config.constants.BASE_URL).hostname == "www.amazon.com" and rows and
               all("$" in row.get_text() and not re.search(r"(?:CAD|AUD|EUR|GBP|CDN|CA\$)", row.get_text()) for row in rows))
    failures = bool(value(order, "parse_failures") or any(value(item, "parse_failures") for item in value(order, "items") or []))
    prices_usd = bool(config and all(value(item, "parsed") is not None and
                      re.fullmatch(r"\$[0-9,]+\.[0-9]{2}", item.simple_parse(config.selectors.FIELD_ITEM_PRICE_SELECTOR) or "")
                      for item in value(order, "items") or []))
    count = value(order, "item_count")
    # Only the observed blank quantity layout on a single whole-unit item may
    # infer one, backed by an exact unit-price/subtotal match. Unknown layouts,
    # weighted items and malformed explicit quantity badges remain unknown.
    if (len(items) == 1 and items[0]["quantity"] is None and labels_known and usd and prices_usd and
            not failures and count in (None, 1)):
        item = value(order, "items")[0]
        blank = item.parsed.select('[data-component="quantity"]')
        badges = select(item.parsed, config.selectors.FIELD_ITEM_QUANTITY_SELECTOR)
        price = cents(value(item, "price"))
        if (len(blank) == 1 and len(blank[0].select('span.a-size-small')) == 1 and
                not blank[0].get_text(strip=True) and all(not badge.get_text(strip=True) for badge in badges) and
                not re.search(r"\bQty\s*:", item.parsed.get_text(" ", strip=True), re.IGNORECASE) and
                price is not None and price > 0 and price == fields["subtotal"]):
            items[0].update(quantity=1, cents=price, quantity_source="single_item_subtotal")
    count_matches = count is None or (all(isinstance(i["quantity"], int) for i in items) and count == sum(i["quantity"] for i in items))
    adjustments = [{"id": name, "kind": kind, "cents": fields[name]} for name, kind in ADJUSTMENTS.items()
                   if fields[name] is not None and fields[name] != 0]
    discounts = [a for a in adjustments if a["kind"] == "discount" and a["id"] != "free_shipping"]
    # Multiple promotion fields can overlap; never use a reconciliation coincidence to bless them.
    distinct = len(discounts) <= 1
    full_total = (fields["subtotal"] + sum(a["cents"] for a in adjustments)) if fields["subtotal"] is not None else None
    gift = abs(fields["gift_card"] or 0) if labels_known else None
    rewards = abs(fields["reward_points"] or 0) if labels_known else None
    item_sum = sum(i["cents"] for i in items) if items and all(i["cents"] is not None for i in items) else None
    # Qualify the original purchase, never a purchase net of refunds. Original
    # item costs/totals must still reconcile; each credit needs its own payment
    # and item evidence downstream, not the order's cumulative refund total.
    complete = (value(order, "full_details") is True and not value(order, "cancelled") and not value(order, "bridge_conflicting_summary") and labels_known and usd and prices_usd and count_matches and
                not failures and distinct and item_sum is not None and item_sum == fields["subtotal"] and
                fields["estimated_tax"] is not None and fields["total_before_tax"] is not None and full_total is not None and
                fields["total_before_tax"] + fields["estimated_tax"] == full_total and
                detail_total in (full_total, full_total - (gift or 0) - (rewards or 0)) and
                fields["grand_total"] in (full_total, detail_total) and
                (fields["refund_total"] is None or 0 <= fields["refund_total"] <= full_total))
    method = re.sub(r"\s*\*+\s*\d+.*$", "", value(order, "payment_method") or "").strip().lower()
    last4 = value(order, "payment_method_last_4")
    identity = "python:" + method + ":" + str(last4) if re.fullmatch(r"[a-z0-9 -]+", method) and re.fullmatch(r"\d{4}", str(last4)) else None
    return {"version": 1, "source": "python", "currency": "USD" if usd else None, "complete": bool(complete),
            "payment_identity": identity, "purchase_eligible": value(order, "bridge_purchase_eligible") is True,
            "reason": "incomplete_source" if not complete else None, "items": items, "adjustments": adjustments,
            "adjustments_distinct": distinct, "full_total_cents": full_total if complete else None, "gift_cents": gift, "reward_cents": rewards,
            "payments_complete": value(order, "bridge_payments_complete") is True,
            "history_total_cents": fields["grand_total"], "details_total_cents": detail_total,
            "fields": fields, "parse_failed": failures,
            "conflicting_source": value(order, "bridge_conflicting_summary") is True}


def payment_history(session, days, order_id=None):
    """Bound pages locally and inspect orderData BEFORE upstream first-reference flattening."""
    from amazonorders.transactions import TransactionsPage

    class ObservedPage(TransactionsPage):
        qualified = True
        refreshed = False

        def _get_form_page(self, amazon_session, url, next_page_data):
            response = amazon_session.post(url, data=next_page_data)
            amazon_session.check_response(response, meta=next_page_data)
            # Amazon sometimes returns HTTP 200 with its refresh-required error
            # page instead of a ledger. Refresh once per history, never certify
            # that response as empty or relax parsing of the replacement page.
            alerts = response.parsed.select('.a-alert-container')
            if not self.refreshed and any(
                    alert.select_one('.a-alert-heading') is not None and
                    ' '.join(alert.select_one('.a-alert-heading').get_text().split()) == 'Oops. Something went wrong.' and
                    alert.select_one('.a-alert-content') is not None and
                    ' '.join(alert.select_one('.a-alert-content').get_text().split()) ==
                    'We encountered an error. Please refresh your page.' for alert in alerts):
                self.refreshed = True
                response = amazon_session.post(url, data=next_page_data)
                amazon_session.check_response(response, meta=next_page_data)
            return self.parse_page(response.parsed)

        def _parse_transaction_data_tag(self, tag):
            state = json.loads(tag.string or "")["props"]["pageProps"]["state"]["transactionResponseState"]
            if state.get("hasMore") and not state.get("lastEvaluatedPageKey"):
                raise ValueError("Missing transaction cursor")
            return super()._parse_transaction_data_tag(tag)

        def _parse_transaction_list(self, records):
            if any(len(row.get("orderData") or []) != 1 or not re.fullmatch(r"[+-]?\$[0-9,]+\.[0-9]{2}", str(row.get("formattedAmount", "")))
                   for row in records):
                self.qualified = False
            result = super()._parse_transaction_list(records)
            if len(result) != len(records):
                self.qualified = False
            return result

        def _parse_transaction_form_tag(self, tag):
            from amazonauth.util import select

            result, cursor = super()._parse_transaction_form_tag(tag)
            selectors = self.config.selectors
            # The upstream parser can skip date groups or select only the first
            # amount/reference. Qualify the observed form, not those fallbacks.
            rows = tag.select('div.apx-transactions-line-item-component-container')
            if not rows or sorted(map(id, rows)) != sorted(id(t.parsed) for t in result):
                raise ValueError("Unqualified transaction history layout")
            for selector in (selectors.TRANSACTIONS_NEXT_PAGE_INPUT_STATE_SELECTOR,
                             selectors.TRANSACTIONS_NEXT_PAGE_INPUT_IE_SELECTOR):
                controls = select(tag, selector)
                if len(controls) != 1 or not controls[0].get('value'):
                    raise ValueError("Unqualified transaction history layout")
            next_buttons = select(tag, selectors.TRANSACTIONS_NEXT_PAGE_INPUT_SELECTOR)
            if len(next_buttons) > 1 or (next_buttons and not cursor):
                raise ValueError("Unqualified transaction history layout")
            for transaction in result:
                row = transaction.parsed
                amounts = select(row, selectors.FIELD_TRANSACTION_GRAND_TOTAL_SELECTOR)
                payments = select(row, selectors.FIELD_TRANSACTION_PAYMENT_METHOD_SELECTOR)
                links = select(row, selectors.FIELD_TRANSACTION_ORDER_LINK_SELECTOR)
                statuses = row.select(':scope > div:nth-child(2)')
                if len(amounts) != 1 or len(payments) != 1 or len(links) != 1 or len(statuses) != 1:
                    raise ValueError("Unqualified transaction history layout")
                amount = amounts[0].get_text(strip=True)
                link = urlsplit(links[0].get('href', ''))
                number = transaction.order_number
                status = statuses[0].get_text(' ', strip=True)
                if (not re.fullmatch(r'[+-]?\$[0-9,]+\.[0-9]{2}', amount) or
                        cents(amount.replace('$', '').replace(',', '')) != cents(transaction.grand_total) or
                        not re.fullmatch(r'[A-Za-z0-9 -]+ \*{4}\d{4}', payments[0].get_text(strip=True)) or
                        status not in ('Pending', 'Order #' + str(number)) or
                        links[0].get_text(strip=True) != 'Order #' + str(number) or
                        set(re.findall(r'\d{3}-\d{7}-\d{7}', row.get_text(' ', strip=True))) != {number} or
                        link.scheme not in ('', 'https') or link.hostname not in (None, 'www.amazon.com') or
                        parse_qs(link.query).get(self.config.constants.ORDER_ID_QUERY_PARAM) != [number]):
                    raise ValueError("Unqualified transaction history layout")
                transaction.bridge_pending = status == 'Pending'
            return result, cursor

    page = ObservedPage(session.config)
    url = session.config.constants.TRANSACTION_HISTORY_URL
    if order_id:
        url += "?transactionTag=" + order_id
    cursor = None
    seen = set()
    events = []
    previous_oldest = None
    # Bank parents at the lookback edge can match source events 14 days earlier.
    cutoff = dt.date.today() - dt.timedelta(days=days + 14)
    for _ in range(20):
        loaded, cursor = page.get_page(session, url, cursor)
        for transaction in loaded:
            date = date_value(value(transaction, "completed_date"))
            amount = cents(value(transaction, "grand_total"))
            number = value(transaction, "order_number")
            method = value(transaction, "payment_method") or ""
            last4 = value(transaction, "payment_method_last_4")
            method = re.sub(r"\s*\*+\s*\d+.*$", "", method).strip().lower()
            identity = "python:" + method + ":" + str(last4) if method and re.fullmatch(r"\d{4}", str(last4)) else None
            facts = [number, str(date), amount, identity]
            key = hashlib.sha256(json.dumps(facts, separators=(",", ":")).encode()).hexdigest()
            valid = bool(date and amount and re.fullmatch(r"\d{3}-\d{7}-\d{7}", str(number)) and identity and
                         not value(transaction, "parse_failures") and not value(transaction, "bridge_pending"))
            events.append({"key": key, "order_id": number, "date": str(date) if date else None,
                           "signed_cents": amount, "payment_identity": identity, "currency": "USD",
                           "complete": valid, "source": "python"})
        if not cursor:
            break
        signature = json.dumps(cursor, sort_keys=True)
        if signature in seen:
            raise ValueError("Repeated transaction cursor")
        seen.add(signature)
        if not order_id:
            # Rows can be reordered within a page. Require newest-first page
            # ranges and a wholly older page before stopping, keeping the full
            # inclusive boundary. Order ledgers must instead reach their end.
            dates = [date_value(value(t, 'completed_date')) for t in loaded]
            if (any(d is None for d in dates) or
                    (previous_oldest and dates and max(dates) > previous_oldest)):
                raise ValueError("Unordered transaction history")
            if dates:
                previous_oldest = min(dates)
                if max(dates) < cutoff:
                    break
    else:
        raise ValueError("Transaction page bound")
    if not page.qualified:
        # Qualification belongs to the retrieval, not just its rows: an unknown
        # empty layout must not look like a successfully observed empty ledger.
        raise ValueError("Unqualified transaction history layout")
    counts = {}
    for event in events:
        counts[event["key"]] = counts.get(event["key"], 0) + 1
    for event in events:
        event["complete"] = (event["complete"] and page.qualified and counts[event["key"]] == 1 and
                             urlsplit(session.config.constants.BASE_URL).hostname == "www.amazon.com")
    return [e for e in events if order_id or (date_value(e["date"]) and date_value(e["date"]) >= cutoff)]


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
            "quantity": value(item, "quantity"),
        })
    return {
        "order_number": value(order, "order_number", "order_id", "id"),
        "order_placed_date": str(date_value(value(order, "order_placed_date", "order_date", "date")) or ""),
        "grand_total": value(order, "grand_total", "total"),
        "payment_method": value(order, "payment_method", "payment_method_name"),
        "payment_method_last_4": value(order, "payment_method_last_4", "payment_last_four"),
        "cancelled": bool(value(order, "cancelled", "is_cancelled")),
        "items": items,
        "financial_evidence": financial_evidence(order),
        "payment_events": value(order, "bridge_payment_events") or [],
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


def load_history(config_path, look_back_days, account_only=False, transaction_evidence=False):
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
        summary_facts = {}
        for year in range(cutoff.year, dt.date.today().year + 1):
            summaries = orders.get_orders(year=year, full_details=False,
                                          family_member_id="self" if account_only else None)
            for order in summaries:
                placed = date_value(value(order, "order_placed_date"))
                number = value(order, "order_number")
                if not placed or placed < cutoff or not number or value(order, "cancelled"):
                    continue
                facts = [str(placed), cents(value(order, "grand_total"))]
                if number in seen:
                    if summary_facts[number] != facts:
                        next(o for o in history if value(o, "order_number") == number).bridge_conflicting_summary = True
                    continue
                summary_facts[number] = facts
                seen.add(number)
                if len(seen) > 200:
                    raise ValueError("Order detail bound")
                # Reuse the pinned parser's detail builder so Whole Foods and
                # unsupported summary types keep their existing semantics.
                history.append(orders._build_order(order.parsed, full_details=True, current_index=order.index))
        if transaction_evidence:
            recent_complete = True
            try:
                recent = payment_history(session, look_back_days)
            except LoginRequired:
                raise
            except Exception:
                recent = []  # Existing display records remain usable, without payment authority.
                recent_complete = False
            by_id = {value(order, "order_number"): order for order in history}
            for event in recent:
                number = event["order_id"]
                if number not in by_id and re.fullmatch(r"\d{3}-\d{7}-\d{7}", str(number)):
                    if len(by_id) >= 200:
                        raise ValueError("Order detail bound")
                    # Recent charges outside purchase lookback still compete, but do
                    # not trigger purchase-detail backfill or authorize historical writes.
                    by_id[number] = {"order_number": number}
                    if any(e["order_id"] == number and (e["signed_cents"] or 0) > 0 and
                           date_value(e["date"]) >= cutoff for e in recent):
                        try:
                            by_id[number] = orders.get_order(number)
                        except LoginRequired:
                            raise
                        except Exception:
                            pass  # Event-only record: missing original facts stay unknown.
            for number, order in by_id.items():
                observed = [e for e in recent if e["order_id"] == number]
                charges = []
                complete = False
                if number in seen:
                    try:
                        ledger = payment_history(session, look_back_days, number)
                        complete = bool(ledger) and all(e["complete"] and e["order_id"] == number for e in ledger)
                        charges = [e for e in ledger if (e["signed_cents"] or 0) < 0]
                    except LoginRequired:
                        raise
                    except Exception:
                        pass
                # A single charge in each source can corroborate despite date
                # differences. Never collapse repeated occurrences within a source.
                observed_charges = [e for e in observed if (e["signed_cents"] or 0) < 0]
                if (len(observed_charges) == len(charges) == 1 and
                        all(observed_charges[0][field] == charges[0][field] for field in
                            ("order_id", "signed_cents", "payment_identity", "currency"))):
                    global_charge, ledger_charge = observed_charges[0], charges[0]
                    charges = [dict(ledger_charge, key=global_charge["key"], date=global_charge["date"],
                                    matching_dates=sorted({global_charge["date"], ledger_charge["date"]}))]
                complete = complete and all(any(c["key"] == e["key"] for c in charges) for e in observed_charges)
                # Corroborating histories describe the same occurrences. Retain the
                # larger multiplicity for each key; indistinguishable duplicates stay unsafe.
                versions = {}
                for source, events in enumerate((observed, charges)):
                    for event in events:
                        versions.setdefault(event["key"], [[], []])[source].append(event)
                combined = []
                for global_copies, order_copies in versions.values():
                    copies = max((global_copies, order_copies), key=len)
                    valid = recent_complete and len(copies) == 1 and all(e["complete"] for e in global_copies + order_copies)
                    for event in copies:
                        merged = dict(event, complete=valid)
                        dates = {date for e in global_copies + order_copies for date in e.get("matching_dates", [e["date"]])}
                        if len(dates) > 1:
                            merged["matching_dates"] = sorted(dates)
                        if ((event["signed_cents"] or 0) < 0 and event["order_id"] not in seen or
                                (event["signed_cents"] or 0) > 0 and date_value(event["date"]) < cutoff):
                            merged["competition_only"] = True
                        combined.append(merged)
                complete = complete and all(e["complete"] for e in combined if (e["signed_cents"] or 0) < 0)
                if isinstance(order, dict):
                    order["bridge_payment_events"] = combined
                    order["bridge_payments_complete"] = complete
                else:
                    order.bridge_payment_events = combined
                    order.bridge_payments_complete = complete
            history = list(by_id.values())
        for order in history:
            eligible = value(order, "order_number") in seen
            if isinstance(order, dict):
                order["bridge_purchase_eligible"] = eligible
            else:
                order.bridge_purchase_eligible = eligible
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
    parser.add_argument("--transaction-evidence", action="store_true", help="Retrieve bounded payment events in this authenticated session.")
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
        for order in load_history(args.config_path, args.look_back_days, args.account_only, args.transaction_evidence):
            record = serialize(order)
            order_date = date_value(record["order_placed_date"])
            if record["payment_events"] and record["order_number"]:
                unique[record["order_number"]] = record
            elif (order_date and order_date >= cutoff and record["order_number"] and
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
