// Retrieval only. Financial interpretation belongs to the Groovy application.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
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
	Detail map[string]any `json:"detail"`
	Ledger map[string]any `json:"ledger"`
}

// Preserve source numbers and repeated entries; discard private unused fields.
// A nil leaf is a scalar; nested maps describe allowed object/array members.
func project(value any, fields map[string]any) (any, error) {
	if values, ok := value.([]any); ok {
		out := make([]any, 0, len(values))
		for _, v := range values {
			p, err := project(v, fields)
			if err != nil {
				return nil, err
			}
			out = append(out, p)
		}
		return out, nil
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, invalid
	}
	out := map[string]any{}
	for key, shape := range fields {
		v, present := object[key]
		if !present {
			continue
		}
		if v == nil {
			out[key] = nil
			continue
		}
		if nested, ok := shape.(map[string]any); ok {
			p, err := project(v, nested)
			if err != nil {
				return nil, err
			}
			out[key] = p
		} else {
			switch x := v.(type) {
			case string, json.Number, bool:
				out[key] = x
			case []any:
				for _, item := range x {
					if _, ok := item.(string); !ok {
						return nil, invalid
					}
				}
				out[key] = x
			default:
				return nil, invalid
			}
		}
	}
	return out, nil
}

func evidence(detail, ledger []byte) (Order, error) {
	money := map[string]any{"value": nil, "currency": nil, "currencyCode": nil}
	price := map[string]any{"grandTotal": money, "totalWithTip": money, "driverTip": money, "taxTotal": money, "fees": money, "savings": money,
		"deliveryFee": money, "tax": map[string]any{"taxAmount": money}}
	item := map[string]any{"id": nil, "returnId": nil, "quantity": nil, "productInfo": map[string]any{"name": nil, "usItemId": nil},
		"priceInfo": map[string]any{"linePrice": money, "unitPrice": money}}
	category := map[string]any{"items": item}
	group := map[string]any{"id": nil, "status": map[string]any{"statusType": nil}, "priceDetails": price, "items": item,
		"categories": category, "subGroups": map[string]any{"categories": category}}
	detailFields := map[string]any{"currency": nil, "currencyCode": nil, "data": map[string]any{"order": map[string]any{
		"id": nil, "orderDate": nil, "currency": nil, "currencyCode": nil, "priceDetails": price, "groups_2101": group}}}
	ledgerFields := map[string]any{"data": map[string]any{"getOrderLedger": map[string]any{"paymentMethodsLedgers": map[string]any{
		"paymentType": nil, "cardType": nil, "description": nil, "transactions": map[string]any{"chargeType": nil, "transactionLines": map[string]any{
			"date": nil, "rowLines": map[string]any{"lineType": nil, "displayValues": nil}}}}}}}
	decode := func(b []byte, fields map[string]any) (map[string]any, error) {
		var raw any
		d := json.NewDecoder(bytes.NewReader(b))
		d.UseNumber()
		if d.Decode(&raw) != nil {
			return nil, invalid
		}
		p, err := project(raw, fields)
		if err != nil {
			return nil, err
		}
		return p.(map[string]any), nil
	}
	d, err := decode(detail, detailFields)
	if err != nil {
		return Order{}, err
	}
	l, err := decode(ledger, ledgerFields)
	if err != nil {
		return Order{}, err
	}
	return Order{d, l}, nil
}

// The reviewed client uses DefaultTransport; set it before constructing it.
type guardedTransport struct {
	base   http.RoundTripper
	bodies map[string][]byte
}

// Only fixed operation/error labels and numeric status codes may leave this boundary.
// Never log upstream errors, URLs, headers, or response bodies.
func diagnostic(operation, failure string, status int) {
	fmt.Fprintf(os.Stderr, "walmart_helper operation=%s failure=%s http_status=%d\n", operation, failure, status)
}

func (g *guardedTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	operation := "unknown"
	for _, candidate := range []string{"PurchaseHistoryV2", "getOrder", "getOrderLedger"} {
		if strings.Contains(r.URL.Path, "/"+candidate+"/") {
			operation = candidate
			break
		}
	}
	if r.URL.Scheme != "https" || r.URL.Host != "www.walmart.com" || r.URL.User != nil ||
		!strings.HasPrefix(r.URL.Path, "/orchestra/") || r.Method != "GET" {
		diagnostic(operation, "destination_rejected", 0)
		return nil, invalid
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	response, err := g.base.RoundTrip(r.Clone(ctx))
	if err != nil {
		failure := "transport_failed"
		if errors.Is(err, context.DeadlineExceeded) || ctx.Err() == context.DeadlineExceeded {
			failure = "timeout"
		}
		diagnostic(operation, failure, 0)
		return nil, errors.New("request_failed")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		diagnostic(operation, "http_status", response.StatusCode)
		return nil, errors.New("session_or_request_failed")
	}
	b, err := io.ReadAll(io.LimitReader(response.Body, maxBytes+1))
	if err != nil {
		diagnostic(operation, "body_read_failed", response.StatusCode)
		return nil, invalid
	}
	if len(b) > maxBytes {
		diagnostic(operation, "body_limit", response.StatusCode)
		return nil, invalid
	}
	if !json.Valid(b) {
		diagnostic(operation, "invalid_json", response.StatusCode)
		return nil, invalid
	}
	var root struct {
		Errors []json.RawMessage `json:"errors"`
		Data   json.RawMessage   `json:"data"`
	}
	if json.Unmarshal(b, &root) != nil {
		diagnostic(operation, "invalid_schema", response.StatusCode)
		return nil, invalid
	}
	if len(root.Errors) != 0 {
		diagnostic(operation, "graphql_errors", response.StatusCode)
		return nil, invalid
	}
	if strings.Contains(r.URL.Path, "/PurchaseHistoryV2/") {
		var envelope struct {
			Data *struct {
				History *struct {
					Groups *[]json.RawMessage `json:"orderGroups"`
					Page   *struct {
						Next json.RawMessage `json:"nextPageCursor"`
					} `json:"pageInfo"`
				} `json:"orderHistoryV2"`
			} `json:"data"`
		}
		if json.Unmarshal(b, &envelope) != nil || envelope.Data == nil || envelope.Data.History == nil ||
			envelope.Data.History.Groups == nil || envelope.Data.History.Page == nil || len(envelope.Data.History.Page.Next) == 0 {
			diagnostic(operation, "history_schema", response.StatusCode)
			return nil, errors.New("history_failed")
		}
		var cursor string
		if json.Unmarshal(envelope.Data.History.Page.Next, &cursor) != nil {
			diagnostic(operation, "history_schema", response.StatusCode)
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

func run(r Request) (Envelope, error) {
	result := Envelope{Version: 1, Status: "ok", Orders: []Order{}}
	if r.Version != 1 || !filepath.IsAbs(r.Session) {
		return result, invalid
	}
	if r.Action == "import" {
		return result, importSession(r.Capture, r.Session)
	}
	if r.Action != "fetch" || r.Pages < 1 || r.Pages > 20 {
		return result, invalid
	}
	since, err := time.Parse("2006-01-02", r.Since)
	if err != nil {
		return result, invalid
	}
	if err = protectedSession(r.Session); err != nil {
		diagnostic("session", "session_unavailable", 0)
		return result, err
	}
	transport := &guardedTransport{http.DefaultTransport.(*http.Transport).Clone(), map[string][]byte{}}
	transport.base.(*http.Transport).Proxy = nil
	http.DefaultTransport = transport
	client, err := walmart.NewWalmartClient(walmart.ClientConfig{CookieFile: r.Session, MaxRetries: -1})
	if err != nil {
		diagnostic("client", "initialization_failed", 0)
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
			diagnostic("PurchaseHistoryV2", "client_failed", 0)
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
			if _, e = client.GetOrderWithGroup(ctx, summary.OrderID, summary.GroupID, false); e != nil {
				diagnostic("getOrder", "client_failed", 0)
				return result, errors.New("details_failed")
			}
			if _, e = client.GetOrderLedger(ctx, summary.OrderID); e != nil {
				diagnostic("getOrderLedger", "client_failed", 0)
				return result, errors.New("ledger_failed")
			}
			order, e := evidence(transport.body("getOrder"), transport.body("getOrderLedger"))
			if e != nil {
				diagnostic("evidence", "invalid_schema", 0)
				return result, e
			}
			result.Orders = append(result.Orders, order)
		}
		cursor = history.Data.OrderHistoryV2.PageInfo.NextPageCursor
		if cursor == "" {
			return result, nil
		}
	}
	diagnostic("PurchaseHistoryV2", "pagination_limit", 0)
	return result, errors.New("pagination_limit")
}
func main() {
	var request Request
	data, err := io.ReadAll(io.LimitReader(os.Stdin, 65537))
	if err != nil || len(data) > 65536 {
		fail()
		return
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&request) != nil || d.Decode(new(any)) != io.EOF {
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
