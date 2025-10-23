# Requirements Document

## Introduction

This feature improves the reliability of Amazon refund transaction detection and parsing from email notifications. The current implementation has limited refund detection capabilities and fails to properly parse several refund email formats, leading to missed refund transactions in YNAB.

## Glossary

- **Refund Email**: An email from return@amazon.com with subject line "Your refund for [Product Name]" containing refund amount and order details
- **YNAB Transaction Updater**: The system that matches Amazon orders with YNAB transactions and updates transaction memos
- **Email Parser**: The component that extracts order and refund information from Amazon email notifications
- **Refund Amount**: The monetary value being refunded to the customer, which may differ from the original purchase price due to promo deductions
- **Order ID**: The unique Amazon order identifier associated with the refund
- **Product Title**: The name of the product being refunded, extracted from the email subject or body

## Requirements

### Requirement 1

**User Story:** As a YNAB user, I want refund emails to be reliably detected, so that all my Amazon refunds are tracked in my budget

#### Acceptance Criteria

1. WHEN THE Email Parser receives an email from return@amazon.com, THE Email Parser SHALL identify the email as a refund notification
2. WHEN THE Email Parser processes a refund email with subject containing "Your refund for", THE Email Parser SHALL extract the refund status from the email body
3. WHEN THE Email Parser encounters a refund email with "Refund issuance is delayed" status, THE Email Parser SHALL still process the refund information
4. WHEN THE Email Parser encounters a refund email with "Your refund was issued" status, THE Email Parser SHALL process the refund information
5. THE Email Parser SHALL extract refund information from the text/plain MIME part of multipart emails

### Requirement 2

**User Story:** As a YNAB user, I want refund amounts to be accurately extracted from emails, so that my budget reflects the correct refund values

#### Acceptance Criteria

1. WHEN THE Email Parser processes a refund email, THE Email Parser SHALL extract the "Total refund" amount from the "Return summary" section
2. WHEN THE Email Parser encounters a refund with promo discount deductions, THE Email Parser SHALL use the calculated total refund amount after deductions
3. WHEN THE Email Parser extracts a refund amount, THE Email Parser SHALL represent the amount as a positive value
4. THE Email Parser SHALL extract refund amounts in the format "$XX.XX" or "XX.XX"
5. WHEN THE Email Parser cannot find a "Total refund" amount, THE Email Parser SHALL attempt to extract the refund amount from alternative patterns in the email body

### Requirement 3

**User Story:** As a YNAB user, I want refund transactions to include product information, so that I can easily identify what was refunded

#### Acceptance Criteria

1. WHEN THE Email Parser processes a refund email, THE Email Parser SHALL extract the product title from the email subject line
2. WHEN THE Email Parser extracts a product title from subject "Your refund for [Product]....", THE Email Parser SHALL capture the product name portion
3. WHEN THE Email Parser processes a refund email body, THE Email Parser SHALL extract the full product title from the "Item to be returned" section
4. THE Email Parser SHALL prefer the full product title from the email body over the truncated subject line title
5. WHEN THE Email Parser creates a refund order, THE Email Parser SHALL include the product title in the order items list

### Requirement 4

**User Story:** As a YNAB user, I want refunds to be associated with their original order IDs, so that I can track the relationship between purchases and refunds

#### Acceptance Criteria

1. WHEN THE Email Parser processes a refund email, THE Email Parser SHALL extract the original order ID from the email body
2. THE Email Parser SHALL create a refund order ID by prefixing the original order ID with "RETURN-"
3. WHEN THE Email Parser extracts an order ID from a refund email, THE Email Parser SHALL use the pattern "Order #XXX-XXXXXXX-XXXXXXX" or similar Amazon order formats
4. WHEN THE Email Parser cannot extract an order ID from the email body, THE Email Parser SHALL log a warning and skip the refund
5. THE Email Parser SHALL associate the refund order with the extracted order date or email received date

### Requirement 5

**User Story:** As a developer, I want comprehensive test coverage for refund parsing, so that I can verify the parser handles various refund email formats correctly

#### Acceptance Criteria

1. THE YNAB Transaction Updater SHALL include unit tests that verify refund email detection from return@amazon.com
2. THE YNAB Transaction Updater SHALL include unit tests that parse refund amounts from the four provided example emails
3. THE YNAB Transaction Updater SHALL include unit tests that extract product titles from refund emails
4. THE YNAB Transaction Updater SHALL include unit tests that handle refunds with promo discount deductions
5. THE YNAB Transaction Updater SHALL include unit tests that verify refund orders are created with positive amounts and isReturn flag set to true
