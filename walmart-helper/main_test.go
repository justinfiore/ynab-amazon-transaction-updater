package main

import (
	"encoding/json"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	walmart "github.com/eshaffer321/walmart-client-go/v2"
)

func TestUnitMoney(t *testing.T) {
	for _, f := range []float64{12.345, math.NaN(), math.Inf(1), 1e12} {
		if _, e := cents(f); e == nil {
			t.Fatalf("accepted unsupported money %v", f)
		}
	}
	for f, want := range map[float64]int64{12.34: 1234, 0.29: 29, -5: -500} {
		got, e := cents(f)
		if e != nil || got != want {
			t.Fatalf("money got %d %v", got, e)
		}
	}
}

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestUnitNetworkGuard(t *testing.T) {
	called := 0
	g := &guardedTransport{roundTrip(func(r *http.Request) (*http.Response, error) {
		called++
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"data":{}}`))}, nil
	}), map[string][]byte{}}
	for _, u := range []string{"http://www.walmart.com/orchestra/a", "https://evil.test/orchestra/a", "https://www.walmart.com.evil.test/orchestra/a", "https://www.walmart.com:443/orchestra/a", "https://x@www.walmart.com/orchestra/a", "https://www.walmart.com/account"} {
		r, _ := http.NewRequest("GET", u, nil)
		if _, e := g.RoundTrip(r); e == nil {
			t.Fatalf("accepted %s", u)
		}
	}
	if called != 0 {
		t.Fatal("hostile destination reached transport")
	}
	r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/a", nil)
	response, e := g.RoundTrip(r)
	if e != nil || response.StatusCode != 200 || called != 1 {
		t.Fatal("valid request rejected")
	}
}
func TestUnitTransportFailures(t *testing.T) {
	for _, code := range []int{302, 401, 403, 456, 500} {
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader("cookie-secret address"))}, nil
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/a", nil)
		if _, e := g.RoundTrip(r); e == nil || strings.Contains(e.Error(), "secret") {
			t.Fatal("unsafe error")
		}
	}
	for _, body := range []string{"not-json", strings.Repeat("x", maxBytes+1)} {
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/a", nil)
		if _, e := g.RoundTrip(r); e == nil {
			t.Fatal("accepted invalid or oversized body")
		}
	}
}
func TestContractNormalize(t *testing.T) {
	detail := []byte(`{"currencyCode":"USD","data":{"order":{"id":"123","orderDate":"2026-11-20T10:00:00Z","priceDetails":{"grandTotal":{"value":100},"taxTotal":{"value":5},"fees":[{"value":3}],"driverTip":{"value":2},"totalWithTip":{"value":110}},"groups_2101":[{"status":{"statusType":"DELIVERED"},"items":[{"id":"a","quantity":1.25,"productInfo":{"name":"Weighted Apples","usItemId":"p1"},"priceInfo":{"linePrice":{"value":80}}},{"id":"b","quantity":2,"productInfo":{"name":"Soap","usItemId":"p2"},"priceInfo":{"linePrice":{"value":20}}}],"categories":[{"items":[{"id":"a","quantity":1.25,"productInfo":{"name":"Weighted Apples","usItemId":"p1"},"priceInfo":{"linePrice":{"value":80}}}]}]}]}}}`)
	ledger := []byte(`{"data":{"getOrderLedger":{"paymentMethodsLedgers":[{"paymentType":"CREDITCARD","cardType":"VISA","description":"Ending in 1234","transactions":[{"chargeType":"TEMPORARY_HOLDS","transactionLines":[{"date":"Nov 20, 2026","rowLines":[{"lineType":"ORDER_CHARGE","displayValues":["$999.00"]}]}]},{"chargeType":"FINAL_CHARGES","transactionLines":[{"date":"Nov 21, 2026","rowLines":[{"lineType":"ORDER_CHARGE","displayValues":["$55.00"]},{"lineType":"ORDER_CHARGE","displayValues":["$55.00"]},{"lineType":"ORDER_ADJUSTMENT_REFUND","displayValues":["-$5.00"]}]}]}]}]}}}`)
	var raw walmart.OrderResponse
	if json.Unmarshal(detail, &raw) != nil {
		t.Fatal("fixture")
	}
	got, e := normalize(raw.Data.Order, detail, ledger)
	if e != nil {
		t.Fatal(e)
	}
	if len(got.Items) != 2 || got.Items[0].Quantity != "1.25" || got.Items[0].Gross != 8000 || got.Total != 11000 {
		t.Fatalf("item normalization: %+v", got)
	}
	if len(got.Charges) != 2 || got.Charges[0].Cents != 5500 || got.Charges[0].Key == got.Charges[1].Key || got.Charges[0].Date != "2026-11-21" {
		t.Fatalf("charges: %+v", got.Charges)
	}
	if got.Complete || got.Reason != "refund_or_unsupported_ledger" {
		t.Fatal("refund incorrectly split ready")
	}
	encoded, _ := json.Marshal(got)
	for _, secret := range []string{"customer", "address", "cookie", "TEMPORARY", "REFUND"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatal("raw field leaked")
		}
	}
	if currency([]byte(`{"value":100}`)) != "" || currency([]byte(`{"currency":"CAD"}`)) != "" {
		t.Fatal("currency guessed")
	}
}
func TestContractGroupSavings(t *testing.T) {
	detail := []byte(`{"currencyCode":"USD","data":{"order":{"id":"123","orderDate":"2026-11-20","priceDetails":{"grandTotal":{"value":95}},"groups_2101":[{"status":{"statusType":"DELIVERED"},"priceDetails":{"savings":{"value":5}},"items":[{"id":"food","quantity":1,"productInfo":{"name":"Food"},"priceInfo":{"linePrice":{"value":80}}},{"id":"soap","quantity":1,"productInfo":{"name":"Soap"},"priceInfo":{"linePrice":{"value":15}}}]}]}}}`)
	ledger := []byte(`{"data":{"getOrderLedger":{"paymentMethodsLedgers":[{"paymentType":"CREDITCARD","transactions":[{"chargeType":"FINAL_CHARGES","transactionLines":[{"date":"Nov 21, 2026","rowLines":[{"lineType":"ORDER_CHARGE","displayValues":["$95.00"]}]}]}]}]}}}`)
	var raw walmart.OrderResponse
	if err := json.Unmarshal(detail, &raw); err != nil {
		t.Fatal(err)
	}
	got, err := normalize(raw.Data.Order, detail, ledger)
	if err != nil {
		t.Fatal(err)
	}
	if got.Complete || got.Reason != "discount_basis_unproven" {
		t.Fatalf("net prices authorized allocation: %+v", got)
	}
	// No discount evidence is a separate control, not a reason to infer gross.
	raw.Data.Order.Groups[0].PriceDetails.Savings.Value = 0
	got, err = normalize(raw.Data.Order, detail, ledger)
	if err != nil || !got.Complete {
		t.Fatalf("zero-savings control rejected: %+v %v", got, err)
	}
}

func TestContractHistoryEnvelope(t *testing.T) {
	for _, body := range []string{
		`{"errors":[{"message":"session secret expired"}],"data":null}`,
		`{"errors":[{"message":"expired"}],"data":{"orderHistoryV2":{"orderGroups":[],"pageInfo":{"nextPageCursor":""}}}}`,
		`{}`, `null`, `{"data":null}`, `{"data":{}}`,
		`{"data":{"orderHistoryV2":null}}`,
		`{"data":{"orderHistoryV2":{"orderGroups":[]}}}`,
		`{"data":{"orderHistoryV2":{"orderGroups":[],"pageInfo":null}}}`,
		`{"data":{"orderHistoryV2":{"orderGroups":null,"pageInfo":{"nextPageCursor":""}}}}`,
		`{"data":{"orderHistoryV2":{"pageInfo":{"nextPageCursor":""}}}}`,
		`{"data":{"orderHistoryV2":{"orderGroups":[],"pageInfo":{}}}}`,
		`{"data":{"orderHistoryV2":{"orderGroups":[],"pageInfo":{"nextPageCursor":5}}}}`,
	} {
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/cph/graphql/PurchaseHistoryV2/hash", nil)
		if _, err := g.RoundTrip(r); err == nil || strings.Contains(err.Error(), "secret") {
			t.Fatalf("accepted invalid history or leaked response: %s", body)
		}
	}
	for _, cursor := range []string{`""`, `null`, `"next-page"`} {
		body := `{"errors":[],"data":{"orderHistoryV2":{"orderGroups":[],"pageInfo":{"nextPageCursor":` + cursor + `}}}}`
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/cph/graphql/PurchaseHistoryV2/hash", nil)
		response, err := g.RoundTrip(r)
		if err != nil {
			t.Fatalf("valid empty history rejected: %v", err)
		}
		var history walmart.PurchaseHistoryResponse
		if json.NewDecoder(response.Body).Decode(&history) != nil || len(history.Data.OrderHistoryV2.OrderGroups) != 0 {
			t.Fatal("valid empty history not preserved")
		}
		response.Body.Close()
	}
}

func TestContractSessionImport(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows ACL qualification pending; production import fails closed")
	}
	dir := t.TempDir()
	capture := filepath.Join(dir, "capture")
	session := filepath.Join(dir, "session.json")
	marker := filepath.Join(dir, "must-not-exist")
	text := "curl 'https://www.walmart.com/orchestra/orders/graphql/getOrder/" + strings.Repeat("a", 64) + "' -H 'cookie: CID=one; SPID=two; auth=three' ; touch " + marker
	if os.WriteFile(capture, []byte(text), 0600) != nil {
		t.Fatal("fixture")
	}
	if e := importSession(capture, session); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(marker); !os.IsNotExist(e) {
		t.Fatal("capture executed")
	}
	info, _ := os.Stat(session)
	if info.Mode().Perm() != 0600 {
		t.Fatal("session permissions")
	}
	if protectedSession(session) != nil {
		t.Fatal("session rejected")
	}
	os.Chmod(session, 0644)
	if protectedSession(session) == nil {
		t.Fatal("unprotected session accepted")
	}
}
