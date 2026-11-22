// The helper is a dedicated process. No browser, raw-response persistence or login.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	walmart "github.com/eshaffer321/walmart-client-go/v2"
)

const maxBytes = 8 << 20

var invalid = errors.New("unsupported_source")
var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,100}$`)

type Request struct {
	Version int    `json:"version"`
	Session string `json:"session_file"`
	Capture string `json:"capture_file,omitempty"`
	Action  string `json:"action"`
	Since   string `json:"since,omitempty"`
	Pages   int    `json:"max_pages,omitempty"`
}
type Envelope struct {
	Version int     `json:"version"`
	Status  string  `json:"status"`
	Orders  []Order `json:"orders"`
}
type Order struct {
	ID          string       `json:"id"`
	Date        string       `json:"date"`
	URL         string       `json:"url"`
	Currency    string       `json:"currency"`
	Total       int64        `json:"total_cents"`
	Complete    bool         `json:"complete"`
	Reason      string       `json:"reason,omitempty"`
	Items       []Item       `json:"items"`
	Charges     []Charge     `json:"charges"`
	Adjustments []Adjustment `json:"adjustments"`
}
type Item struct {
	ID       string `json:"id"`
	Product  string `json:"product_id"`
	Title    string `json:"title"`
	Quantity string `json:"quantity"`
	Gross    int64  `json:"gross_cents"`
}
type Charge struct {
	Key      string `json:"key"`
	Date     string `json:"date"`
	Currency string `json:"currency"`
	Payment  string `json:"payment"`
	Cents    int64  `json:"cents"`
}
type Adjustment struct {
	Kind  string `json:"kind"`
	Cents int64  `json:"cents"`
}

// Upstream has no public transport setter and uses http.DefaultTransport. Set it
// once, in this single-purpose process, before constructing the reviewed client.
// Keep bounded raw bodies only in memory to retain fields its DTOs omit (currency,
// ledger lineType/refunds). Never print upstream errors or response bodies.
type guardedTransport struct {
	base   http.RoundTripper
	bodies map[string][]byte
}

func (g *guardedTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Scheme != "https" || r.URL.Host != "www.walmart.com" || r.URL.User != nil ||
		!strings.HasPrefix(r.URL.Path, "/orchestra/") || r.Method != "GET" {
		return nil, invalid
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	response, err := g.base.RoundTrip(r.Clone(ctx))
	if err != nil {
		return nil, errors.New("request_failed")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, errors.New("session_or_request_failed")
	}
	b, err := io.ReadAll(io.LimitReader(response.Body, maxBytes+1))
	if err != nil || len(b) > maxBytes || !json.Valid(b) {
		return nil, invalid
	}
	if strings.Contains(r.URL.Path, "/PurchaseHistoryV2/") {
		// The upstream value-typed DTO silently accepts missing/null objects.
		// Preserve the difference between an empty history and a failed query.
		var envelope struct {
			Errors []json.RawMessage `json:"errors"`
			Data   *struct {
				History *struct {
					Groups *[]json.RawMessage `json:"orderGroups"`
					Page   *struct {
						Next json.RawMessage `json:"nextPageCursor"`
					} `json:"pageInfo"`
				} `json:"orderHistoryV2"`
			} `json:"data"`
		}
		if json.Unmarshal(b, &envelope) != nil || len(envelope.Errors) != 0 || envelope.Data == nil ||
			envelope.Data.History == nil || envelope.Data.History.Groups == nil || envelope.Data.History.Page == nil ||
			len(envelope.Data.History.Page.Next) == 0 {
			return nil, errors.New("history_failed")
		}
		var cursor string
		if json.Unmarshal(envelope.Data.History.Page.Next, &cursor) != nil {
			return nil, errors.New("history_failed")
		}
	}
	g.bodies[r.URL.Path] = b
	response.Body = io.NopCloser(bytes.NewReader(b))
	return response, nil
}
func (g *guardedTransport) body(operation string) []byte {
	for path, b := range g.bodies {
		if strings.Contains(path, "/"+operation+"/") {
			return b
		}
	}
	return nil
}

func cents(f float64) (int64, error) {
	if math.IsNaN(f) || math.IsInf(f, 0) || math.Abs(f) > 1e9 {
		return 0, invalid
	}
	scaled := f * 100
	// Only floating representation error, not a monetary matching tolerance.
	if math.Abs(scaled-math.Round(scaled)) > 1e-7 {
		return 0, invalid
	}
	return int64(math.Round(scaled)), nil
}
func currency(b []byte) string {
	var root any
	if json.Unmarshal(b, &root) != nil {
		return ""
	}
	found := map[string]bool{}
	var walk func(any)
	walk = func(v any) {
		switch x := v.(type) {
		case map[string]any:
			for k, value := range x {
				if k == "currency" || k == "currencyCode" {
					if s, ok := value.(string); ok {
						found[s] = true
					}
				} else {
					walk(value)
				}
			}
		case []any:
			for _, value := range x {
				walk(value)
			}
		}
	}
	walk(root)
	if len(found) == 1 && found["USD"] {
		return "USD"
	}
	return ""
}
func normalize(o *walmart.Order, detail, ledger []byte) (Order, error) {
	result := Order{ID: o.ID, Currency: currency(detail), Complete: true,
		Items: []Item{}, Charges: []Charge{}, Adjustments: []Adjustment{}}
	if !idPattern.MatchString(o.ID) {
		return result, invalid
	}
	result.URL = "https://www.walmart.com/orders/details?orderId=" + url.QueryEscape(o.ID)
	if len(o.OrderDate) < 10 {
		return result, invalid
	}
	result.Date = o.OrderDate[:10]
	if _, err := time.Parse("2006-01-02", result.Date); err != nil {
		return result, invalid
	}
	if result.Currency != "USD" {
		result.Complete = false
		result.Reason = "currency_unproven"
	}
	p := o.PriceDetails
	if p == nil || p.GrandTotal == nil {
		return result, invalid
	}
	total := p.GrandTotal.Value
	if p.TotalWithTip != nil {
		total = p.TotalWithTip.Value
	} else if p.DriverTip != nil {
		total += p.DriverTip.Value
	}
	var err error
	result.Total, err = cents(total)
	if err != nil || result.Total <= 0 {
		return result, invalid
	}
	add := func(kind string, f float64) error {
		c, e := cents(f)
		if e != nil || c < 0 {
			return invalid
		}
		if c != 0 {
			result.Adjustments = append(result.Adjustments, Adjustment{kind, c})
		}
		return nil
	}
	if p.TaxTotal != nil {
		if err = add("tax", p.TaxTotal.Value); err != nil {
			return result, err
		}
	}
	if p.DriverTip != nil {
		if err = add("tip", p.DriverTip.Value); err != nil {
			return result, err
		}
	}
	for _, fee := range p.Fees {
		if err = add("fee", fee.Value); err != nil {
			return result, err
		}
	}
	if p.Savings != nil && p.Savings.Value != 0 {
		// Reviewed DTO cannot prove line-price gross/net semantics. No guessing.
		result.Complete = false
		result.Reason = "discount_basis_unproven"
	}
	items := map[string]Item{}
	for _, group := range o.Groups {
		if group.PriceDetails != nil && group.PriceDetails.Savings != nil && group.PriceDetails.Savings.Value != 0 {
			result.Complete = false
			result.Reason = "discount_basis_unproven"
		}
		if group.Status.StatusType != "DELIVERED" && group.Status.StatusType != "PICKED_UP" {
			result.Complete = false
			result.Reason = "unfulfilled_or_unsupported"
		}
		all := append([]walmart.OrderItem{}, group.Items...)
		for _, c := range group.Categories {
			all = append(all, c.Items...)
		}
		for _, sub := range group.SubGroups {
			for _, c := range sub.Categories {
				all = append(all, c.Items...)
			}
		}
		for _, item := range all {
			if item.ID == "" || item.ReturnID != "" || item.ProductInfo == nil || item.PriceInfo == nil || item.PriceInfo.LinePrice == nil ||
				item.Quantity <= 0 || math.IsNaN(item.Quantity) || math.IsInf(item.Quantity, 0) {
				return result, invalid
			}
			cost, e := cents(item.PriceInfo.LinePrice.Value)
			if e != nil || cost < 0 || len(item.ProductInfo.Name) > 1000 {
				return result, invalid
			}
			next := Item{item.ID, item.ProductInfo.USItemID, item.ProductInfo.Name, strconv.FormatFloat(item.Quantity, 'f', -1, 64), cost}
			if prior, ok := items[item.ID]; ok && prior != next {
				return result, invalid
			}
			items[item.ID] = next
		}
	}
	keys := []string{}
	for key := range items {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		result.Items = append(result.Items, items[key])
	}
	// Do not use upstream's simplified ledger: it also includes refund row types.
	var raw walmart.OrderLedgerResponse
	if json.Unmarshal(ledger, &raw) != nil {
		return result, invalid
	}
	ordinal := map[string]int{}
	for _, pm := range raw.Data.GetOrderLedger.PaymentMethodsLedgers {
		last := regexp.MustCompile(`(?:^|\D)(\d{4})$`).FindStringSubmatch(pm.Description)
		payment := pm.PaymentType + ":" + pm.CardType + ":"
		if len(last) > 1 {
			payment += last[1]
		}
		for _, tx := range pm.Transactions {
			if tx.ChargeType != "FINAL_CHARGES" {
				continue
			}
			for _, line := range tx.TransactionLines {
				date, e := time.Parse("Jan 2, 2006", line.Date)
				if e != nil {
					date, e = time.Parse("January 2, 2006", line.Date)
				}
				if e != nil {
					return result, invalid
				}
				for _, row := range line.RowLines {
					if row.LineType != "ORDER_CHARGE" {
						result.Complete = false
						result.Reason = "refund_or_unsupported_ledger"
						continue
					}
					if len(row.DisplayValues) != 1 {
						return result, invalid
					}
					text := row.DisplayValues[0]
					if !regexp.MustCompile(`^\$[0-9,]+\.[0-9]{2}$`).MatchString(text) {
						return result, invalid
					}
					f, e := strconv.ParseFloat(strings.ReplaceAll(strings.TrimPrefix(text, "$"), ",", ""), 64)
					if e != nil {
						return result, invalid
					}
					c, e := cents(f)
					if e != nil || c <= 0 {
						return result, invalid
					}
					base := fmt.Sprintf("%s|%s|%s|%d", o.ID, payment, date.Format("2006-01-02"), c)
					ordinal[base]++
					result.Charges = append(result.Charges, Charge{fmt.Sprintf("%s|%d", base, ordinal[base]), date.Format("2006-01-02"), result.Currency, payment, c})
				}
			}
		}
	}
	if len(result.Items) == 0 || len(result.Charges) == 0 {
		result.Complete = false
		result.Reason = "missing_items_or_charges"
	}
	return result, nil
}

func run(r Request) (Envelope, error) {
	result := Envelope{Version: 1, Status: "ok", Orders: []Order{}}
	if r.Version != 1 || !filepath.IsAbs(r.Session) {
		return result, invalid
	}
	if r.Action == "import" {
		if err := importSession(r.Capture, r.Session); err != nil {
			return result, err
		}
		return result, nil
	}
	if r.Action != "fetch" || r.Pages < 1 || r.Pages > 20 {
		return result, invalid
	}
	since, err := time.Parse("2006-01-02", r.Since)
	if err != nil {
		return result, invalid
	}
	if err = protectedSession(r.Session); err != nil {
		return result, err
	}
	transport := &guardedTransport{http.DefaultTransport.(*http.Transport).Clone(), map[string][]byte{}}
	transport.base.(*http.Transport).Proxy = nil
	http.DefaultTransport = transport
	client, err := walmart.NewWalmartClient(walmart.ClientConfig{CookieFile: r.Session, MaxRetries: -1})
	if err != nil {
		return result, invalid
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	cursor := ""
	seen := map[string]bool{}
	min := since.Unix()
	for page := 0; page < r.Pages; page++ {
		history, e := client.GetPurchaseHistory(ctx, walmart.PurchaseHistoryRequest{Cursor: cursor, Limit: 20, MinTimestamp: &min})
		if e != nil {
			return result, errors.New("history_failed")
		}
		for _, summary := range history.Data.OrderHistoryV2.OrderGroups {
			if seen[summary.OrderID] {
				continue
			}
			seen[summary.OrderID] = true
			if !idPattern.MatchString(summary.OrderID) {
				return result, invalid
			}
			transport.bodies = map[string][]byte{}
			order, e := client.GetOrderWithGroup(ctx, summary.OrderID, summary.GroupID, false)
			if e != nil {
				return result, errors.New("details_failed")
			}
			if _, e = client.GetOrderLedger(ctx, summary.OrderID); e != nil {
				return result, errors.New("ledger_failed")
			}
			normalized, e := normalize(order, transport.body("getOrder"), transport.body("getOrderLedger"))
			if e != nil {
				return result, e
			}
			result.Orders = append(result.Orders, normalized)
		}
		cursor = history.Data.OrderHistoryV2.PageInfo.NextPageCursor
		if cursor == "" {
			return result, nil
		}
	}
	return result, errors.New("pagination_limit")
}
func main() {
	var request Request
	data, err := io.ReadAll(io.LimitReader(os.Stdin, 65537))
	if err != nil || len(data) > 65536 {
		fail()
		return
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil || decoder.Decode(new(any)) != io.EOF {
		fail()
		return
	}
	result, err := run(request)
	if err != nil {
		fail()
		return
	}
	output, err := json.Marshal(result)
	if err != nil || len(output) > maxBytes {
		fail()
		return
	}
	fmt.Println(string(output))
}
func fail() { fmt.Println(`{"version":1,"status":"fetch_failed"}`); os.Exit(1) }
