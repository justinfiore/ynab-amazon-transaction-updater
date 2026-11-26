package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"

	walmart "github.com/eshaffer321/walmart-client-go/v2"
)

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
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	previous := os.Stderr
	os.Stderr = writer
	defer func() { os.Stderr = previous; writer.Close(); reader.Close() }()
	for _, code := range []int{302, 401, 403, 456, 500} {
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader("cookie-secret address"))}, nil
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/orders/graphql/getOrder/private-order?cookie=private-token", nil)
		r.Header.Set("Cookie", "private-cookie")
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
	for _, failure := range []error{context.DeadlineExceeded, errors.New("private-transport-secret")} {
		g := &guardedTransport{roundTrip(func(*http.Request) (*http.Response, error) {
			return nil, failure
		}), map[string][]byte{}}
		r, _ := http.NewRequest("GET", "https://www.walmart.com/orchestra/cph/graphql/PurchaseHistoryV2/private-order", nil)
		if _, e := g.RoundTrip(r); e == nil {
			t.Fatal("accepted transport failure")
		}
	}
	writer.Close()
	logged, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	text := string(logged)
	for _, expected := range []string{"operation=getOrder failure=http_status http_status=403", "failure=invalid_json", "failure=body_limit", "operation=PurchaseHistoryV2 failure=timeout", "failure=transport_failed"} {
		if !strings.Contains(text, expected) {
			t.Fatalf("missing diagnostic %s", expected)
		}
	}
	for _, private := range []string{"private-", "secret", "https://", "address"} {
		if strings.Contains(text, private) {
			t.Fatal("private upstream data leaked into diagnostics")
		}
	}
}
func TestContractEvidence(t *testing.T) {
	fixture, err := os.ReadFile("testdata/source-evidence.json")
	if err != nil {
		t.Fatal(err)
	}
	var expected map[string]json.RawMessage
	if json.Unmarshal(fixture, &expected) != nil {
		t.Fatal("fixture")
	}
	// Inject unused private fields without changing source pricing or ledger evidence.
	detail := strings.Replace(string(expected["detail"]), `"id": "123"`, `"customer":{"email":"private"},"address":"private","id": "123"`, 1)
	got, err := evidence([]byte(detail), expected["ledger"])
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(got)
	var a, b any
	json.Unmarshal(encoded, &a)
	json.Unmarshal(fixture, &b)
	if !reflect.DeepEqual(a, b) || strings.Contains(string(encoded), "private") {
		t.Fatal("source evidence changed or private fields leaked")
	}
	// Preserve even unsupported fractional prices; only Groovy may reject them.
	got, err = evidence([]byte(`{"data":{"order":{"priceDetails":{"grandTotal":{"value":12.345}}}}}`), expected["ledger"])
	encoded, _ = json.Marshal(got)
	if err != nil || !strings.Contains(string(encoded), "12.345") {
		t.Fatal("retrieval rounded source money")
	}
}

func TestContractHistoryEnvelope(t *testing.T) {
	emptyContract, err := os.ReadFile("testdata/empty-history.json")
	if err != nil {
		t.Fatal(err)
	}
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
		encoded, err := json.Marshal(Envelope{Version: 1, Status: "ok", Orders: []Order{}})
		var actual, expected map[string]any
		if err != nil || json.Unmarshal(encoded, &actual) != nil || json.Unmarshal(emptyContract, &expected) != nil || !reflect.DeepEqual(actual, expected) {
			t.Fatalf("empty success violates JVM contract: %s %v", encoded, err)
		}
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
