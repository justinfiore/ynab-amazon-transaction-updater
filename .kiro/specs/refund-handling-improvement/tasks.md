# Implementation Plan

- [x] 1. Add refund parsing regex patterns and helper methods
  - Add new regex pattern constants for refund email parsing (REFUND_SUBJECT_PATTERN, REFUND_TOTAL_PATTERN, REFUND_SUBTOTAL_PATTERN, REFUND_ITEM_TITLE_PATTERN, REFUND_ORDER_ID_PATTERN)
  - Implement extractRefundAmount(String content) helper method to extract total refund or subtotal
  - Implement extractRefundProductTitle(String subject, String content) helper method to extract product name from subject and body
  - Implement extractRefundOrderId(String content) helper method to extract original order ID from email body
  - _Requirements: 1.1, 1.2, 2.1, 2.2, 2.4, 3.1, 3.2, 3.3, 4.1, 4.3_

- [x] 2. Implement parseRefundFromEmail method
  - Create parseRefundFromEmail(Message message) method in AmazonOrderFetcher
  - Extract email content using existing getEmailContent method
  - Call helper methods to extract refund amount, product title, and order ID
  - Create order ID with "RETURN-" prefix
  - Set order date to email sent date
  - Create AmazonOrder object with isReturn: true and positive amount value
  - Add error handling for missing critical fields (amount, order ID)
  - Add logging for successful parsing and errors
  - _Requirements: 1.1, 1.4, 2.1, 2.3, 3.4, 3.5, 4.2, 4.4, 4.5_

- [x] 3. Enhance parseOrderFromEmail to detect and route refund emails
  - Add early detection for emails from return@amazon.com sender
  - Delegate to parseRefundFromEmail when refund email detected
  - Maintain existing order parsing logic for non-refund emails
  - Ensure backward compatibility with existing functionality
  - _Requirements: 1.1, 1.5_

- [x] 4. Create test helper for loading refund email files
  - Create RefundEmailTestHelper class in test directory
  - Implement method to load .eml files from test resources
  - Implement method to extract text/plain content from .eml files
  - Implement method to create Message objects from .eml files for testing
  - _Requirements: 5.1, 5.2_

- [x] 5. Write unit tests for refund parsing
- [x] 5.1 Create AmazonOrderFetcher_Refund_UT test class
  - Set up test class with Spock framework
  - Create test fixtures for refund email content
  - _Requirements: 5.1_

- [x] 5.2 Write tests for refund amount extraction
  - Test extraction of "Total refund" amount
  - Test handling of promo discount deductions
  - Test fallback to "Refund subtotal" when total not found
  - Test handling of missing amounts
  - _Requirements: 2.1, 2.2, 2.4, 2.5, 5.2, 5.4_

- [x] 5.3 Write tests for product title extraction
  - Test extraction from subject line
  - Test extraction from email body
  - Test preference of body title over subject title
  - Test handling of truncated subject titles
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 5.3_

- [x] 5.4 Write tests for order ID extraction
  - Test extraction of order ID from email body
  - Test "RETURN-" prefix addition
  - Test handling of missing order IDs
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 5.5 Write tests for complete refund parsing
  - Test parsing of all four example refund emails
  - Test refund order creation with isReturn flag
  - Test positive amount values for refunds
  - Test delayed refund status handling
  - _Requirements: 1.2, 1.3, 2.3, 3.5, 4.5, 5.2, 5.5_

- [x] 6. Write integration tests for refund email fetching
- [x] 6.1 Create AmazonOrderFetcher_Refund_IT test class
  - Set up integration test with test email resources
  - Create test scenarios with mixed order and refund emails
  - _Requirements: 5.1_

- [x] 6.2 Write integration test for refund detection
  - Test identification of refund emails by sender
  - Test correct routing to refund parser
  - Test that regular orders still process normally
  - _Requirements: 1.1, 1.5_

- [x] 6.3 Write integration test for end-to-end refund processing
  - Test fetching refunds from email
  - Test parsing and order creation
  - Test integration with existing order fetching
  - _Requirements: 1.1, 1.4, 1.5_
