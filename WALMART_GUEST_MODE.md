# Walmart Guest Mode Implementation

## Overview

The Walmart Guest Mode allows fetching Walmart order details without requiring your Walmart password. Instead, it uses order lookup emails combined with Walmart's guest order tracking feature.

## How It Works

1. **Email Search**: Searches your email (via IMAP) for Walmart order notifications
   - Looks for emails from `help@walmart.com` or `noreply@walmart.com`
   - Also searches emails from `config.walmart.forwardFromAddress` if configured
   - Searches emails from the last `lookBackDays + 2` days

2. **Order ID Extraction**: Extracts order IDs from email content
   - Searches for patterns like "Order number: XXXXXXX-XXXXXXXX"
   - Also looks for "Order #: XXXXXXX-XXXXXXXX"
   - Falls back to direct pattern matching for order ID format

3. **Deduplication**: Keeps only the latest email for each order ID
   - Multiple emails may exist for the same order (shipped, delivered, etc.)
   - Uses the most recent email's sent date as the order date

4. **Order Lookup**: For each order ID, uses Walmart's guest order lookup
   - Navigates to `https://walmart.com/orders`
   - Fills in the Walmart email address
   - Fills in the order number
   - Clicks "View order status"
   - Handles bot detection ("Press & Hold" buttons)

5. **Order Details Extraction**: Extracts the same data as login mode
   - Order number, date, status
   - Final charge amounts (from charge history)
   - Product summaries
   - Individual item details

## Configuration

### Mode Constants

```groovy
Configuration.WALMART_MODE_GUEST = "guest"  // Default
Configuration.WALMART_MODE_LOGIN = "login"
```

### Required Fields

**Guest Mode:**
- `walmart.email` - Your Walmart account email
- Amazon email credentials (reused for IMAP access)

**Login Mode:**
- `walmart.email` - Your Walmart account email
- `walmart.password` - Your Walmart account password

### Optional Fields

- `walmart.forwardFromAddress` - Email address that forwards Walmart emails (guest mode only)
- `walmart.headless` - Run browser in headless mode (default: true)
- `walmart.browser_timeout` - Browser timeout in milliseconds (default: 30000)
- `walmart.bot_detection_hold_time_ms` - How long to hold the bot detection button (default: 15000)

## Example Configuration

```yaml
walmart:
  enabled: true
  mode: "guest"  # Use guest mode (no password required)
  email: "your.email@example.com"
  # password not needed for guest mode
  headless: true
```

## Bot Detection Handling

The guest mode includes the same robust bot detection handling as login mode:

1. Detects bot verification pages by looking for:
   - "Press & Hold" text
   - "Robot or human?" dialog
   - "Activate and hold the button" message
   - `#px-captcha` iframe

2. Handles verification:
   - Locates the button (in iframe or main page)
   - Clicks to activate
   - Holds mouse down for configured time (default 15 seconds)
   - Follows button if it moves during hold
   - Waits for verification to complete

3. Screenshots failed attempts to `logs/walmart-guest-bot-detection-{timestamp}.png`

## Advantages of Guest Mode

- ✅ No password storage required
- ✅ More secure (uses existing email credentials)
- ✅ Same order detail extraction as login mode
- ✅ Handles multiple charges per order
- ✅ Extracts final charge amounts (ignores temporary holds)

## Limitations

- Requires Walmart order notification emails to be present in your inbox
- If you delete Walmart emails, those orders won't be found
- Still requires browser automation (but no login flow)

## Testing

Unit tests: `WalmartGuestOrderFetcher_UT`
- Tests order ID extraction from various formats
- Tests date parsing
- Tests email content extraction
- Tests configuration validation

Integration tests: `WalmartGuestOrderFetcher_IT`
- Tests against actual sample email files
- Verifies all sample emails have extractable order IDs
- Tests date format parsing from real emails
