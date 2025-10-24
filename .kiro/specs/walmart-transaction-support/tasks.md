# Implementation Plan

- [ ] 1. Add Playwright dependency and create Walmart model classes
  - Add Playwright dependency to build.gradle
  - Create WalmartOrder model class with orderId, orderDate, orderStatus, totalAmount, finalChargeAmounts, items, and orderUrl fields
  - Create WalmartOrderItem model class with title, price, quantity, and imageUrl fields
  - Implement getProductSummary(), getOrderLink(), isDelivered(), and hasMultipleCharges() methods in WalmartOrder
  - _Requirements: 1.1, 3.3_

- [ ] 2. Extend Configuration class for Walmart settings
  - Add walmartEmail, walmartPassword, walmartEnabled, walmartBrowserTimeout, and walmartOrdersUrl fields to Configuration class
  - Implement validation logic to require walmartEmail and walmartPassword when walmartEnabled is true
  - Add Walmart configuration loading from config.yml in loadConfiguration() method
  - Provide sensible defaults for optional Walmart settings (timeout: 30000ms, ordersUrl: "https://www.walmart.com/orders")
  - _Requirements: 2.1, 2.3, 2.4, 2.5_

- [ ] 3. Implement WalmartOrderFetcher with browser automation
- [ ] 3.1 Create WalmartOrderFetcher class structure
  - Create WalmartOrderFetcher class with Configuration dependency
  - Add browser, context, and page fields for Playwright
  - Implement initBrowser() method to launch headless Chromium browser
  - Implement closeBrowser() method with proper cleanup in finally block
  - _Requirements: 1.1, 2.2, 6.5_

- [ ] 3.2 Implement authentication flow
  - Implement authenticate() method to navigate to walmart.com
  - Click "Sign In" button
  - Enter email and password from configuration
  - Handle authentication errors with detailed logging
  - Verify successful login before proceeding
  - _Requirements: 2.2, 6.2_

- [ ] 3.3 Implement order fetching and parsing
  - Navigate to walmart.com/orders after authentication
  - Filter orders by lookBackDays date range
  - For each order, check if status is "Delivered" (skip non-delivered orders)
  - Click "View Details" for delivered orders
  - Scroll to and click "Charge History" button to expand panel
  - Parse only "Final Order Charges" (ignore "Temporary Hold" charges)
  - Extract order number, date, total amount, final charge amounts, and item details
  - Navigate back to orders list for next order
  - _Requirements: 1.1, 2.2, 4.1_

- [ ] 3.4 Implement error handling and retries
  - Add try-catch blocks for navigation errors with exponential backoff retry (up to 3 attempts)
  - Handle timeout errors with configurable timeout from configuration
  - Log warnings for parsing errors and skip problematic orders
  - Track count of skipped orders and include in summary statistics
  - Ensure browser cleanup in finally block even on errors
  - _Requirements: 6.1, 6.2, 6.3_

- [ ] 3.5 Write unit tests for WalmartOrderFetcher
  - Test HTML parsing with sample order data
  - Test order status filtering (verify non-delivered orders are skipped)
  - Test charge history extraction (verify only final charges extracted, temporary holds ignored)
  - Test authentication flow with mocked browser
  - Test error scenarios (network errors, parsing errors, timeouts)
  - Test pagination handling if applicable
  - _Requirements: 7.1_

- [ ] 4. Implement WalmartService
  - Create WalmartService class following AmazonService pattern
  - Add Configuration and WalmartOrderFetcher dependencies
  - Implement getOrders() method that delegates to WalmartOrderFetcher
  - Add comprehensive logging for order fetch start, completion, and statistics
  - Implement error handling consistent with AmazonService
  - _Requirements: 1.1, 3.1, 3.4, 3.5, 6.1, 6.4_

- [ ] 4.1 Write unit tests for WalmartService
  - Test order fetching with mocked WalmartOrderFetcher
  - Test error handling scenarios
  - Test configuration validation
  - Verify logging output
  - _Requirements: 7.1_

- [ ] 5. Extend TransactionMatcher for Walmart orders
- [ ] 5.1 Add Walmart payee detection
  - Add WALMART_PAYEE_NAMES constant list with "WALMART", "WAL-MART", "WALMART.COM", "WALMART ONLINE"
  - Extend isPotentialWalmartTransaction() method to check for Walmart payees
  - _Requirements: 4.2_

- [ ] 5.2 Implement single transaction matching for Walmart
  - Create findWalmartMatches() method following Amazon pattern
  - Implement findSingleTransactionMatch() for single-charge Walmart orders
  - Use existing confidence scoring algorithm (amount 70%, date 20%, payee 10%)
  - _Requirements: 1.2, 4.2, 4.4_

- [ ] 5.3 Implement multi-transaction matching for Walmart
  - Implement findMultiTransactionMatch() to handle orders with multiple final charges
  - Group unmatched Walmart transactions by date proximity (within 7 days)
  - For each Walmart order with multiple finalChargeAmounts, find transaction groups where sum matches order total
  - Calculate confidence score: amount match 50%, date proximity 30%, payee consistency 20%
  - Create TransactionMatch containing multiple transactions for multi-charge orders
  - _Requirements: 1.4, 4.1, 4.3, 4.4_

- [ ] 5.4 Write unit tests for Walmart matching logic
  - Test single Walmart transaction matching
  - Test multi-transaction matching with 2 charges
  - Test multi-transaction matching with 3+ charges
  - Test confidence scoring for both single and multi-transaction scenarios
  - Test edge cases (partial matches, date boundaries, amount mismatches)
  - _Requirements: 7.2_

- [ ] 6. Extend TransactionProcessor for Walmart updates
  - Add processWalmartMatches() method following processAmazonMatches() pattern
  - Implement generateWalmartMemo() to create memo with Walmart order link and product summary
  - For multi-transaction matches, format memo as "[existing memo] | Walmart Order: [order_number] (Charge X of Y) - [product_summary]"
  - For single-transaction matches, format memo as "[existing memo] | Walmart Order: [order_number] - [product_summary]"
  - Preserve existing memo content when appending Walmart information
  - Respect dry-run mode and log proposed updates without modifying YNAB
  - _Requirements: 1.3, 1.4, 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 6.1 Write unit tests for Walmart transaction processing
  - Test Walmart memo generation for single-transaction orders
  - Test Walmart memo generation for multi-transaction orders
  - Test order link inclusion in memos
  - Test preservation of existing memo content
  - Test dry-run mode behavior
  - _Requirements: 7.1_

- [ ] 7. Integrate Walmart processing into main application
  - Update YNABAmazonTransactionUpdater to instantiate WalmartService when walmartEnabled is true
  - Add Walmart order fetching after Amazon order fetching
  - Call TransactionMatcher.findWalmartMatches() with fetched Walmart orders
  - Call TransactionProcessor.processWalmartMatches() with Walmart matches
  - Add logging for Walmart processing statistics (orders fetched, matches found, updates performed)
  - Ensure Walmart processing doesn't interfere with existing Amazon processing
  - _Requirements: 1.1, 1.5, 3.2, 6.4_

- [ ] 7.1 Write integration tests for end-to-end Walmart flow
  - Test complete flow: fetch → match → update with sample Walmart orders
  - Test with mixed Amazon and Walmart orders to verify both work together
  - Test multi-transaction order scenarios
  - Test delivered vs non-delivered order filtering
  - Test final charges vs temporary holds extraction
  - Verify YNAB transaction updates in dry-run mode
  - _Requirements: 7.3, 7.4, 7.5_

- [ ] 8. Update configuration files and documentation
  - Add Walmart configuration section to config.example.yml with example values
  - Update README.md with Walmart integration instructions
  - Document Walmart-specific configuration options (email, password, enabled, browser_timeout)
  - Add troubleshooting section for common Walmart integration issues
  - Document multi-transaction order behavior and charge history extraction
  - _Requirements: 2.1, 2.3_
