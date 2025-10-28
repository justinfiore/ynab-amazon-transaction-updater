# Design Document: Walmart Transaction Support

## Overview

This design extends the YNAB Amazon Transaction Updater to support Walmart order data, following the established architecture patterns used for Amazon integration. The system will fetch Walmart orders using either API access (if available) or headless browser automation via Playwright, match them with YNAB transactions, and update transaction memos with Walmart order links.

A key challenge is handling Walmart's multi-transaction orders where a single order may result in multiple credit card charges. The matching algorithm must intelligently group related transactions and associate them with the correct Walmart order.

## Architecture

### High-Level Component Structure

```
YNABAmazonTransactionUpdater (Main)
    ├── Configuration (Extended)
    ├── YNABService (Existing)
    ├── AmazonService (Existing)
    ├── WalmartService (New)
    │   └── WalmartOrderFetcher (New)
    ├── TransactionMatcher (Extended)
    └── TransactionProcessor (Extended)
```

### Design Principles

1. **Consistency**: Follow the same patterns as Amazon integration
2. **Modularity**: Keep Walmart logic separate but integrated
3. **Flexibility**: Support multiple data sources (API vs browser automation)
4. **Extensibility**: Design for future retailer additions

## Components and Interfaces

### 1. WalmartService

Primary service for fetching and processing Walmart order data.

**Responsibilities:**
- Coordinate order fetching from configured data source
- Parse and normalize Walmart order data
- Handle errors and logging

**Interface:**
```groovy
class WalmartService {
    private final Configuration config
    private final WalmartOrderFetcher orderFetcher
    
    WalmartService(Configuration config)
    List<WalmartOrder> getOrders()
}
```

**Key Methods:**
- `getOrders()`: Returns list of Walmart orders from configured source
- Delegates to WalmartOrderFetcher for actual data retrieval

### 2. WalmartOrderFetcher

Handles the actual fetching of Walmart order data using different strategies.

**Responsibilities:**
- Implement browser automation using Playwright
- Handle authentication and session management
- Parse order data from HTML responses
- Handle pagination and date filtering

**Interface:**
```groovy
class WalmartOrderFetcher {
    private final Configuration config
    private Browser browser
    private BrowserContext context
    private Page page
    
    WalmartOrderFetcher(Configuration config)
    List<WalmartOrder> fetchOrders()
    private void initBrowser()
    private void closeBrowser()
    private void authenticate()
    private List<WalmartOrder> parseOrdersFromHtml(String html)
}
```

**Browser Automation Strategy:**

Walmart does not provide a consumer-facing API for order history. The only viable approach is browser automation:

- Use Playwright for Groovy/JVM
- Navigate to walmart.com/orders
- Authenticate using configured credentials
- Scrape order data from the orders page
- Handle pagination if needed
- Extract order details including:
  - Order number
  - Order date
  - Total amount
  - Individual transaction amounts (if multiple charges)
  - Item details

**Browser Automation Flow:**
```
1. Launch headless browser
2. Navigate to walmart.com
3. Click "Sign In"
4. Enter credentials
5. Navigate to "Account" → "Purchase History"
6. Filter by date range (lookBackDays)
7. For each order:
   - Check order status (skip if not "Delivered")
   - Click "View Details"
   - Scroll to "Charge History" section
   - Click "Charge History" button to expand panel
   - Extract "Final Order Charges" (ignore "Temporary Hold" charges)
   - Extract order number
   - Extract order date
   - Extract total amount
   - Extract individual final charge amounts
   - Extract item summaries
   - Navigate back to orders list
8. Close browser
9. Return parsed orders
```

### 3. WalmartOrder Model

Data model representing a Walmart order.

**Structure:**
```groovy
class WalmartOrder {
    String orderId              // Walmart order number
    String orderDate            // YYYY-MM-DD format
    String orderStatus          // Order status (e.g., "Delivered")
    BigDecimal totalAmount      // Total order amount
    List<BigDecimal> finalChargeAmounts  // Final charge amounts only (excludes temporary holds)
    List<WalmartOrderItem> items
    String orderUrl             // Link to order details page
    
    String getProductSummary()
    String getOrderLink()
    boolean isDelivered()
}

class WalmartOrderItem {
    String title
    BigDecimal price
    int quantity
    String imageUrl
    
    BigDecimal getTotalPrice()
}
```

**Key Fields:**
- `orderStatus`: Used to filter orders - only process "Delivered" orders
- `finalChargeAmounts`: Critical for multi-transaction matching. Contains only the "Final Order Charges" from the Charge History panel, excluding "Temporary Hold" charges
- `orderUrl`: Direct link to walmart.com order details page

### 4. Configuration Extensions

Extend existing Configuration class to support Walmart settings.

**New Configuration Fields:**
```groovy
class Configuration {
    // Existing fields...
    
    // Walmart Configuration
    String walmartEmail
    String walmartPassword
    boolean walmartEnabled = false
    int walmartBrowserTimeout = 30000  // 30 seconds
    String walmartOrdersUrl = "https://www.walmart.com/orders"
}
```

**Configuration Validation:**
- Require walmartEmail and walmartPassword if walmartEnabled is true
- Provide sensible defaults for optional settings (timeout, URL)
- Allow Walmart integration to be enabled/disabled independently

### 5. TransactionMatcher Extensions

Extend matching logic to handle Walmart orders and multi-transaction scenarios.

**New Matching Logic:**

**Single Transaction Match (existing pattern):**
- Amount matches exactly
- Date within acceptable range
- Payee contains "WALMART"

**Multi-Transaction Match (new pattern):**
- Each transaction amount matches a specific final charge amount from the order
- All transactions within date range of order
- All transactions have Walmart payee
- Each transaction corresponds to one final charge (no summing of transactions)

**Matching Algorithm:**
```groovy
class TransactionMatcher {
    // Existing methods...
    
    private static final List<String> WALMART_PAYEE_NAMES = [
        "WALMART",
        "WAL-MART",
        "WALMART.COM",
        "WALMART ONLINE"
    ]
    
    List<TransactionMatch> findWalmartMatches(
        List<YNABTransaction> transactions, 
        List<WalmartOrder> orders
    )
    
    private TransactionMatch findSingleTransactionMatch(
        YNABTransaction transaction, 
        List<WalmartOrder> orders
    )
    
    private TransactionMatch findMultiTransactionMatch(
        List<YNABTransaction> transactions, 
        WalmartOrder order
    )
    
    private double calculateWalmartMatchScore(
        List<YNABTransaction> transactions, 
        WalmartOrder order
    )
}
```

**Multi-Transaction Matching Strategy:**
1. For each Walmart order with multiple final charges:
   - For each unmatched Walmart transaction, try to match it to a specific final charge amount
   - Verify transaction is within order date range
   - Calculate confidence score based on:
     - Exact amount match with final charge (70%)
     - Date proximity (20%)
     - Payee consistency (10%)
2. Create individual TransactionMatch objects for each transaction-to-charge match
3. Do NOT sum transactions or match against order totals

**Confidence Scoring:**
- Single transaction: Use existing algorithm (amount 70%, date 20%, payee 10%)
- Multi-charge matching: Same as single transaction but match against individual final charges
  - Exact amount match with final charge: 70%
  - Date proximity to order date: 20%
  - Payee consistency (Walmart): 10%

### 6. TransactionProcessor Extensions

Extend processor to handle Walmart matches and multi-transaction updates.

**New Processing Logic:**
```groovy
class TransactionProcessor {
    // Existing methods...
    
    void processWalmartMatches(List<TransactionMatch> matches)
    
    private String generateWalmartMemo(
        YNABTransaction transaction, 
        WalmartOrder order,
        boolean isMultiTransaction
    )
}
```

**Memo Format:**
- Single transaction: `"[existing memo] | Walmart Order: [order_number] - [product_summary]"`
- Multi-transaction: `"[existing memo] | Walmart Order: [order_number] (Charge X of Y) - [product_summary]"`
- Include order link in memo or as separate field if YNAB API supports it

## Data Models

### WalmartOrder
```groovy
class WalmartOrder {
    String orderId
    String orderDate
    String orderStatus
    BigDecimal totalAmount
    List<BigDecimal> finalChargeAmounts
    List<WalmartOrderItem> items
    String orderUrl
    
    String getProductSummary() {
        if (!items || items.isEmpty()) {
            return "Walmart Order"
        }
        if (items.size() == 1) {
            return items[0].title
        }
        return "${items.size()} items: ${items.take(3).collect { it.title }.join(', ')}..."
    }
    
    String getOrderLink() {
        return "https://www.walmart.com/orders/details?orderId=${orderId}"
    }
    
    boolean isDelivered() {
        return orderStatus?.equalsIgnoreCase("Delivered")
    }
    
    boolean hasMultipleCharges() {
        return finalChargeAmounts && finalChargeAmounts.size() > 1
    }
}
```

### WalmartOrderItem
```groovy
class WalmartOrderItem {
    String title
    BigDecimal price
    int quantity
    String imageUrl
    
    BigDecimal getTotalPrice() {
        return price ? price * quantity : 0
    }
}
```

### TransactionMatch Extensions
```groovy
class TransactionMatch {
    // Existing fields...
    List<YNABTransaction> transactions  // Support multiple transactions
    WalmartOrder walmartOrder           // Add Walmart order reference
    boolean isMultiTransaction
    
    // Existing: AmazonOrder amazonOrder
    // Keep both to support both retailers
}
```

## Error Handling

### Browser Automation Errors

**Authentication Failures:**
- Log detailed error with credentials status (present/missing)
- Throw exception to halt processing
- Suggest user verify credentials in config

**Navigation Errors:**
- Retry up to 3 times with exponential backoff
- Log each attempt with timestamp
- If all retries fail, throw exception with last error

**Parsing Errors:**
- Log warning with problematic HTML snippet
- Skip individual order and continue processing
- Track count of skipped orders
- Log reason for skipping (e.g., "Order not delivered", "Missing charge history")

**Timeout Errors:**
- Use configurable timeout (default 30s)
- Log timeout with operation details
- Retry with increased timeout (up to 2x)

### Network Errors

**Connection Failures:**
- Retry with exponential backoff (1s, 2s, 4s)
- Maximum 3 retries
- Log each attempt
- Throw exception after max retries

**Rate Limiting:**
- Detect 429 status codes or rate limit messages
- Wait for specified retry-after period
- Log rate limit encounter
- Implement request throttling (1 request per 2 seconds)

### Data Validation Errors

**Missing Required Fields:**
- Log warning with order ID and missing field
- Skip order and continue processing
- Include in summary statistics

**Invalid Data Formats:**
- Attempt to parse with fallback formats
- Log warning if parsing fails
- Use default values where appropriate

## Testing Strategy

### Unit Tests

**WalmartService_UT:**
- Test order fetching with mocked fetcher
- Test error handling
- Test configuration validation

**WalmartOrderFetcher_UT:**
- Test HTML parsing with sample data
- Test authentication flow (mocked browser)
- Test order status filtering (skip non-delivered orders)
- Test charge history extraction (final charges only, ignore temporary holds)
- Test error scenarios
- Test pagination handling

**TransactionMatcher_UT (Extended):**
- Test single Walmart transaction matching
- Test multi-transaction matching
- Test confidence scoring
- Test edge cases (partial matches, date boundaries)

**TransactionProcessor_UT (Extended):**
- Test Walmart memo generation
- Test multi-transaction memo formatting
- Test order link inclusion

### Integration Tests

**WalmartService_IT:**
- Test end-to-end order fetching (with test account)
- Test browser automation flow
- Test data persistence
- Verify order data accuracy

**TransactionMatcher_IT:**
- Test matching with real Walmart order data
- Test multi-transaction scenarios
- Verify confidence scores

**End-to-End_IT:**
- Test complete flow: fetch → match → update
- Test with mixed Amazon and Walmart orders
- Verify YNAB updates
- Test dry-run mode

### Test Data

**Sample Walmart Orders:**
- Single final charge order (delivered)
- Multi-charge order with 2 final charges (delivered)
- Multi-charge order with 3+ final charges (delivered)
- Order with temporary holds (should extract only final charges)
- Order with single item
- Order with multiple items
- Recent delivered order (within lookback period)
- Old delivered order (outside lookback period)
- Non-delivered order (should be skipped)

**Sample HTML Responses:**
- Orders list page
- Order details page
- Empty orders page
- Error pages (404, 500)

## Implementation Notes

### Playwright Integration

**Dependency:**
```gradle
dependencies {
    implementation 'com.microsoft.playwright:playwright:1.40.0'
}
```

**Browser Setup:**
```groovy
import com.microsoft.playwright.*

class WalmartOrderFetcher {
    private Browser browser
    private BrowserContext context
    private Page page
    
    private void initBrowser() {
        Playwright playwright = Playwright.create()
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true)
        )
        context = browser.newContext()
        page = context.newPage()
    }
    
    private void closeBrowser() {
        if (page != null) page.close()
        if (context != null) context.close()
        if (browser != null) browser.close()
    }
}
```

### Walmart Order URL Pattern

Order details URL format:
```
https://www.walmart.com/orders/details?orderId={ORDER_ID}
```

Example:
```
https://www.walmart.com/orders/details?orderId=1234567890123
```

### Configuration Example

```yaml
walmart:
  enabled: true
  email: "user@example.com"
  password: "secure_password"
  browser_timeout: 30000  # milliseconds (optional, default: 30000)
```

### Multi-Transaction Matching Example

**Scenario:**
- Walmart Order #123: Total $150.00, Status: "Delivered"
- Charge History shows:
  - Temporary Hold: $150.00 (IGNORED)
  - Final Charge 1: $100.00 on 2024-01-15
  - Final Charge 2: $50.00 on 2024-01-16

**YNAB Transactions:**
- Transaction A: -$100.00, 2024-01-15, "WALMART.COM"
- Transaction B: -$50.00, 2024-01-16, "WALMART.COM"

**Matching:**
1. Fetch order, verify status is "Delivered"
2. Extract only Final Order Charges ($100, $50) - ignore Temporary Hold
3. Identify both YNAB transactions as potential Walmart transactions
4. Group by date proximity (1 day apart)
5. Sum amounts: $150.00
6. Match with Order #123 final charges
7. Create single TransactionMatch with both transactions
8. Update both memos with order link

### Logging Strategy

**Log Levels:**
- INFO: Order fetch start/complete, match statistics
- DEBUG: Individual order parsing, matching attempts
- WARN: Parsing errors, skipped orders, retries
- ERROR: Authentication failures, critical errors

**Key Log Messages:**
```groovy
logger.info("Fetching Walmart orders using browser automation")
logger.info("Successfully fetched ${orders.size()} Walmart orders")
logger.debug("Parsing order ${orderId}: ${orderData}")
logger.warn("Skipping order ${orderId}: missing required field ${field}")
logger.error("Authentication failed for Walmart account: ${config.walmartEmail}")
```

## Security Considerations

### Credential Storage

- Store credentials in config.yml (same as Amazon)
- Recommend using environment variables for sensitive data
- Document credential security best practices
- Consider adding encryption support in future

### Browser Automation Security

- Use headless mode to avoid UI exposure
- Clear cookies and session data after each run
- Implement request throttling to avoid detection
- Use realistic user-agent strings
- Add random delays between actions

### Data Privacy

- Do not log sensitive order details at INFO level
- Sanitize error messages to remove PII
- Clear browser cache after fetching
- Do not persist raw HTML responses

## Performance Considerations

### Browser Automation Performance

- Headless browser startup: ~2-3 seconds
- Authentication: ~3-5 seconds
- Order page load: ~2-3 seconds per page
- Total estimated time: 10-15 seconds for typical fetch

### Optimization Strategies

- Reuse browser instance across multiple fetches
- Implement caching for recently fetched orders
- Parallelize order detail fetching if possible
- Use browser context pooling for concurrent requests

### Resource Management

- Ensure browser cleanup in finally blocks
- Implement timeout for all browser operations
- Monitor memory usage during long-running operations
- Limit concurrent browser instances

## Future Enhancements

### Phase 2 Features

1. **Order Caching**: Cache fetched orders to reduce browser automation calls
2. **Partial Refunds**: Handle Walmart refunds similar to Amazon
3. **Order Tracking**: Include shipping status in memos
4. **Multi-Retailer Dashboard**: Unified view of all retailer orders
5. **CSV Export**: Allow exporting Walmart orders to CSV as backup

### Extensibility

The design supports adding additional retailers by:
1. Creating new `[Retailer]Service` class
2. Creating new `[Retailer]OrderFetcher` class
3. Adding retailer-specific model classes
4. Extending TransactionMatcher with retailer-specific logic
5. Updating Configuration with retailer settings

## Migration Path

### Existing Users

1. Update config.yml with Walmart settings
2. Set `walmart.enabled: false` by default
3. Existing Amazon functionality unchanged
4. Users opt-in to Walmart support

### Deployment

1. Add Playwright dependency to build.gradle
2. Update Configuration class
3. Add Walmart service classes
4. Extend TransactionMatcher
5. Update main application to process Walmart orders
6. Update documentation and examples

## Open Questions

1. **Multi-Transaction Detection**: How to reliably detect which transactions belong together?
   - Use date proximity + amount sum matching
   - Require explicit charge breakdown from Walmart order page
   - Decision: Implement heuristic-based grouping with confidence scoring

2. **Order Link Format**: Verify exact URL pattern for Walmart order details
   - Test with real Walmart account
   - Document any variations
   - Decision: Use standard pattern, handle variations in parsing

3. **Browser Detection**: Will Walmart block headless browsers?
   - Implement stealth mode techniques
   - Use realistic user agents
   - Add random delays between actions
   - Decision: Monitor and adapt as needed
