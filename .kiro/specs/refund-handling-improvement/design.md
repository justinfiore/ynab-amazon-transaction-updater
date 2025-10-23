# Design Document: Refund Handling Improvement

## Overview

This design enhances the Amazon refund email parsing capabilities in the `AmazonOrderFetcher` class. The current implementation has basic refund detection but fails to parse several refund email formats correctly. This design adds robust parsing logic to extract refund amounts, product information, and order IDs from various refund email formats.

The solution focuses on improving the `parseOrderFromEmail` method to better handle refund-specific patterns and data extraction from the text/plain MIME part of refund notification emails.

## Architecture

### Component Overview

The refund handling improvement is contained within the existing `AmazonOrderFetcher` service class. No new classes or services are required.

```
AmazonOrderFetcher
├── fetchOrdersFromEmail() [existing]
├── parseOrderFromEmail() [enhanced]
├── parseRefundFromEmail() [new]
├── getEmailContent() [existing]
└── extractRefundDetails() [new helper methods]
```

### Data Flow

1. Email fetching identifies messages from return@amazon.com
2. `parseOrderFromEmail` detects refund emails and delegates to `parseRefundFromEmail`
3. `parseRefundFromEmail` extracts refund-specific information:
   - Total refund amount from "Return summary" section
   - Product title from subject line and email body
   - Original order ID
   - Refund date
4. Creates `AmazonOrder` object with `isReturn: true` and positive amount
5. Returns order to be matched with YNAB transactions

## Components and Interfaces

### Enhanced parseOrderFromEmail Method

**Purpose:** Detect refund emails and route to specialized refund parser

**Changes:**
- Add early detection for refund emails based on sender (return@amazon.com)
- Delegate refund email parsing to new `parseRefundFromEmail` method
- Maintain backward compatibility with existing order parsing

### New parseRefundFromEmail Method

**Signature:**
```groovy
private AmazonOrder parseRefundFromEmail(Message message)
```

**Purpose:** Parse refund-specific information from Amazon refund notification emails

**Inputs:**
- `message`: javax.mail.Message object containing the refund email

**Outputs:**
- `AmazonOrder` object with refund details, or null if parsing fails

**Logic:**
1. Extract email content (text/plain part)
2. Extract product title from subject line
3. Extract order ID from email body
4. Extract total refund amount from "Return summary" section
5. Extract full product title from "Item to be returned" section (if available)
6. Create order ID with "RETURN-" prefix
7. Set order date to email sent date
8. Create AmazonOrder with isReturn: true and positive amount

### Refund Pattern Constants

Add new regex patterns to the class:

```groovy
// Refund email patterns
private static final Pattern REFUND_SUBJECT_PATTERN = ~/Your refund for (.+?)\.{3,}$/
private static final Pattern REFUND_TOTAL_PATTERN = ~/Total refund\s+\$?([0-9]+\.?[0-9]{0,2})/
private static final Pattern REFUND_SUBTOTAL_PATTERN = ~/Refund subtotal\s+\$?([0-9]+\.?[0-9]{0,2})/
private static final Pattern REFUND_ITEM_TITLE_PATTERN = ~/\[([^\]]+)\]\(https:\/\/www\.amazon\.com\/gp\/product/
private static final Pattern REFUND_ORDER_ID_PATTERN = ~/orderId=([0-9]{3}-[0-9]{7}-[0-9]{7})/
```

### Helper Methods

**extractRefundAmount(String content)**
- Attempts to extract "Total refund" amount
- Falls back to "Refund subtotal" if total not found
- Returns BigDecimal or null

**extractRefundProductTitle(String subject, String content)**
- Extracts product name from subject line
- Attempts to extract full title from email body
- Prefers body title over subject title
- Returns String or "Refund" as fallback

**extractRefundOrderId(String content)**
- Extracts original order ID from email body
- Looks for orderId parameter in URLs
- Returns String or null

## Data Models

### AmazonOrder Enhancement

No changes required to the `AmazonOrder` model. The existing structure supports refunds:

```groovy
class AmazonOrder {
    String orderId          // Will be prefixed with "RETURN-"
    String orderDate        // Date of refund email
    BigDecimal totalAmount  // Positive value for refunds
    List<AmazonOrderItem> items
    Boolean isReturn        // Set to true for refunds
}
```

### AmazonOrderItem

No changes required. Will contain:
- `title`: Product name from refund email
- `price`: Refund amount (positive)
- `quantity`: 1

## Error Handling

### Missing Refund Amount
- **Scenario:** Email doesn't contain "Total refund" or "Refund subtotal"
- **Handling:** Log warning with order ID and subject, return null
- **Rationale:** Cannot create meaningful transaction without amount

### Missing Order ID
- **Scenario:** Cannot extract original order ID from email
- **Handling:** Log warning with subject, return null
- **Rationale:** Order ID is critical for tracking and matching

### Missing Product Title
- **Scenario:** Cannot extract product name from subject or body
- **Handling:** Use "Amazon Refund" as default title, continue processing
- **Rationale:** Amount and order ID are more critical than title

### Email Parsing Errors
- **Scenario:** Exception during email content extraction
- **Handling:** Log error with stack trace, return null
- **Rationale:** Fail gracefully, don't break entire email processing

### Malformed Amounts
- **Scenario:** Refund amount cannot be parsed as BigDecimal
- **Handling:** Log warning with raw amount string, return null
- **Rationale:** Invalid amount makes transaction unusable

## Testing Strategy

### Unit Tests

**Test Class:** `AmazonOrderFetcher_Refund_UT.groovy`

**Test Cases:**

1. **testParseRefundEmail_WithTotalRefund**
   - Input: Refund email with "Total refund" amount
   - Verify: Correct amount, order ID, product title extracted
   - Verify: isReturn = true, amount is positive

2. **testParseRefundEmail_WithPromoDeduction**
   - Input: Refund email with promo discount deduction
   - Verify: Total refund amount used (not subtotal)
   - Verify: Correct net refund amount extracted

3. **testParseRefundEmail_DelayedRefund**
   - Input: Refund email with "Refund issuance is delayed" status
   - Verify: Refund still processed correctly
   - Verify: All fields extracted properly

4. **testParseRefundEmail_ProductTitleFromSubject**
   - Input: Refund email with truncated subject
   - Verify: Product title extracted from subject
   - Verify: Ellipsis removed from title

5. **testParseRefundEmail_ProductTitleFromBody**
   - Input: Refund email with full title in body
   - Verify: Full product title extracted from body
   - Verify: Body title preferred over subject title

6. **testParseRefundEmail_OrderIdExtraction**
   - Input: Refund email with order ID in URL
   - Verify: Order ID extracted correctly
   - Verify: "RETURN-" prefix added

7. **testParseRefundEmail_MissingAmount**
   - Input: Refund email without total amount
   - Verify: Returns null
   - Verify: Warning logged

8. **testParseRefundEmail_MissingOrderId**
   - Input: Refund email without order ID
   - Verify: Returns null
   - Verify: Warning logged

9. **testParseRefundEmail_MultipleRefunds**
   - Input: Multiple refund emails for different orders
   - Verify: Each refund parsed independently
   - Verify: Correct order IDs and amounts

10. **testParseRefundEmail_RealExamples**
    - Input: Four provided example refund emails
    - Verify: All parse successfully
    - Verify: Correct amounts extracted for each

### Integration Tests

**Test Class:** `AmazonOrderFetcher_Refund_IT.groovy`

**Test Cases:**

1. **testFetchRefundsFromEmail**
   - Setup: Test email account with refund emails
   - Verify: Refunds fetched and parsed correctly
   - Verify: Mixed with regular orders correctly

2. **testRefundEmailDetection**
   - Setup: Mix of order confirmations and refund emails
   - Verify: Refunds identified by return@amazon.com sender
   - Verify: Regular orders still processed normally

### Test Data

Use the four provided example emails as test resources:
- `Your refund for Ekouaer 2 Pack Womens Pajama.....eml`
- `Your refund for Rubies Women's Wizard Of Oz.....eml`
- `Your refund for SAMPEEL Women's V Neck Color.....eml`
- `Your refund for WIHOLL Long Sleeve Shirts for.....eml`

Create helper class `RefundEmailTestHelper` to:
- Load .eml files from test resources
- Extract text/plain content
- Provide parsed Message objects for testing

## Implementation Notes

### Regex Pattern Design

The refund patterns are designed to be flexible:
- Handle optional whitespace
- Support both "$XX.XX" and "XX.XX" formats
- Case-insensitive matching where appropriate
- Handle multi-line content with DOTALL flag

### Text/Plain Content Extraction

The existing `getEmailContent` method already extracts text/plain content from multipart emails. This is the correct approach for refund emails as:
- HTML content may have complex formatting
- Text/plain is more reliable for pattern matching
- Consistent with existing order parsing

### Backward Compatibility

The changes maintain full backward compatibility:
- Existing order parsing logic unchanged
- New refund parsing only triggered for return@amazon.com emails
- No changes to AmazonOrder or AmazonOrderItem models
- Existing tests continue to pass

### Performance Considerations

- Regex patterns compiled as static constants for efficiency
- Early return on missing critical fields (amount, order ID)
- No additional email fetching or network calls
- Minimal memory overhead (same as existing order parsing)

## Alternative Approaches Considered

### Separate RefundParser Class
**Rejected:** Adds unnecessary complexity for a single email type. Keeping refund parsing in AmazonOrderFetcher maintains cohesion.

### HTML Content Parsing
**Rejected:** HTML parsing is more fragile and complex. Text/plain content is sufficient and more reliable.

### Machine Learning for Amount Extraction
**Rejected:** Overkill for structured email format. Regex patterns are sufficient and more maintainable.

### Separate Refund Model
**Rejected:** AmazonOrder with isReturn flag is sufficient. No need for separate model.

## Future Enhancements

1. **Partial Refunds:** Handle emails for partial refunds of multi-item orders
2. **Refund Reasons:** Extract refund reason from email if available
3. **Refund Status Tracking:** Track "delayed" vs "issued" status
4. **Gift Card Refunds:** Handle refunds to gift card balance
5. **International Refunds:** Support non-USD currency formats
