# Amazon Subscribe and Save Support

This document explains how to set up and use the new Amazon Subscribe and Save transaction matching feature.

## Overview

Amazon Subscribe and Save transactions are challenging to match automatically because:
- They don't generate regular order confirmation emails
- They appear in a different section of your Amazon account
- They have different order ID patterns and data structures

This implementation automatically detects and matches Subscribe and Save transactions from email notifications.

## How It Works

### Automatic Email-Based Detection

The system now automatically detects Subscribe and Save emails from:
- `no-reply@amazon.com` with subject containing "review your upcoming delivery"
- Emails with "subscribe" or "subscription" in the subject

These emails contain delivery notifications like:
- "Arriving by Friday, Jul 11"
- Individual product prices in the format "*$XX.XX*"
- Multiple items per delivery

**Configuration:**
```yaml
amazon:
  email: "your_amazon_email@example.com"
  email_password: "your_app_password"
  # Optional: If you forward S&S emails to yourself
  # forward_from_address: "your_amazon_email@example.com"
```

That's it! Subscribe and Save emails are automatically detected and processed along with regular Amazon order emails.

**Note:** If you forward Subscribe & Save emails to yourself (e.g., from one email account to another), you can specify the `forward_from_address` to ensure those forwarded emails are also detected and processed.

## How to Get Your Subscribe and Save Data

### Check Your Email

1. Search your email for messages from `no-reply@amazon.com` with subject "Price changes: review your upcoming delivery"
2. Look for the "Arriving by [Day], [Month] [Date]" pattern in the email content
3. Note the individual product prices shown as "*$XX.XX*"
4. The system will automatically parse these if email fetching is enabled

## Quick Start

1. **Update your config.yml:**
   ```yaml
   amazon:
     email: "your_email@example.com"
     email_password: "your_app_password"
     # Optional: If you forward S&S emails
     # forward_from_address: "your_email@example.com"
   ```

2. **Run the application:**
   ```bash
   ./gradlew run
   ```

The system will automatically fetch both regular Amazon orders and Subscribe and Save deliveries from your email.

## Transaction Matching

Subscribe and Save transactions are identified by:
- Order IDs prefixed with "SUB-"
- YNAB memos prefixed with "S&S: " (e.g., "S&S: Tide Laundry Detergent")
- Negative amounts (representing charges from Amazon)

The matching algorithm uses the same scoring system as regular orders:
- Amount matching (40% weight)
- Date matching (30% weight)  
- Payee matching (20% weight)
- Memo matching (10% weight)

## Troubleshooting

### No Subscribe and Save transactions found
- Confirm you have Subscribe and Save delivery emails in your inbox
- Check that your email credentials are correct
- Verify the email search is finding Amazon subscription emails
- Ensure emails are from `no-reply@amazon.com` with "review your upcoming delivery" in subject

### Transactions not matching
- Verify the delivery dates in emails match your YNAB transaction dates
- Check that the amounts match (including tax and shipping)
- Ensure your YNAB transactions are within the lookback period
- Check the application logs for parsing errors

### Email parsing not working
- Verify your email provider supports IMAP (Gmail, Outlook, etc.)
- Check that you're using an app-specific password, not your main password
- Ensure the lookback period (`look_back_days`) covers your subscription deliveries

## Data Privacy

- All data processing happens locally on your machine
- No data is sent to external services
- Email credentials are only used to access your own email account
- Consider using app-specific passwords for email access