package com.ynab.amazon.service

import com.microsoft.playwright.*
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart
import javax.mail.search.AndTerm
import javax.mail.search.OrTerm
import javax.mail.search.FromTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.BodyTerm
import java.util.regex.Pattern
import java.util.regex.Matcher

/**
 * Service class for fetching Walmart orders using guest checkout (no login required)
 * Uses IMAP to search for order emails and then uses the order lookup feature
 */
class WalmartGuestOrderFetcher {
    private static final Logger logger = LoggerFactory.getLogger(WalmartGuestOrderFetcher.class)
    
    private static final int MAX_RETRIES = 3
    private static final int INITIAL_RETRY_DELAY_MS = 1000
    
    // Email patterns for Walmart order confirmations
    private static final List<String> WALMART_EMAIL_PATTERNS = [
        "help@walmart.com",
        "noreply@walmart.com"
    ]
    
    // Regex patterns for extracting order information from emails
    private static final Pattern ORDER_ID_PATTERN = ~/Order\s+(number|#)[\s:&nbsp;]*<?a[^>]*>([0-9A-Z-]+)</
    private static final Pattern ORDER_ID_TEXT_PATTERN = ~/Order\s+(number|#)[\s:&nbsp;]+([0-9A-Z-]+)/
    private static final Pattern ORDER_ID_DIRECT_PATTERN = ~/([0-9]{7}-[0-9]{8})/  // Direct pattern for order ID format
    private static final Pattern ORDER_DATE_PATTERN = ~/Order date[:=\s]+([A-Za-z,\s0-9]+)/
    
    private final Configuration config
    private Playwright playwright
    private Browser browser
    private BrowserContext context
    private Page page
    private int skippedOrdersCount = 0
    
    WalmartGuestOrderFetcher(Configuration config) {
        this.config = config
    }
    
    /**
     * Main method to fetch Walmart orders using guest mode
     * @return List of WalmartOrder objects
     */
    List<WalmartOrder> fetchOrders() {
        List<WalmartOrder> orders = []
        skippedOrdersCount = 0
        
        try {
            logger.info("Fetching Walmart orders using guest mode (email lookup)")
            
            // Step 1: Get order IDs and dates from emails
            Map<String, Date> orderIdToDateMap = fetchOrderIdsFromEmail()
            
            if (orderIdToDateMap.isEmpty()) {
                logger.info("No Walmart order emails found in the last ${config.lookBackDays} days")
                return []
            }
            
            logger.info("Found ${orderIdToDateMap.size()} unique order IDs from emails")
            
            // Step 2: Initialize browser
            initBrowser()
            
            // Step 3: For each order ID, fetch order details
            orderIdToDateMap.each { orderId, emailDate ->
                try {
                    WalmartOrder order = fetchOrderDetails(orderId, emailDate)
                    if (order) {
                        orders.add(order)
                        logger.info("Successfully fetched order: ${orderId}")
                    } else {
                        logger.warn("Failed to fetch order details for: ${orderId}")
                        skippedOrdersCount++
                    }
                } catch (Exception e) {
                    logger.error("Error fetching order ${orderId}: ${e.message}", e)
                    skippedOrdersCount++
                }
            }
            
            logger.info("Successfully fetched ${orders.size()} Walmart orders (skipped ${skippedOrdersCount} orders)")
            
        } catch (Exception e) {
            logger.error("Error fetching Walmart orders: ${e.message}", e)
        } finally {
            closeBrowser()
        }
        
        return orders
    }
    
    /**
     * Fetch order IDs from email using IMAP
     * @return Map of order ID to email date
     */
    private Map<String, Date> fetchOrderIdsFromEmail() {
        Map<String, Date> orderIdToDateMap = [:]
        
        try {
            Properties props = new Properties()
            props.put("mail.store.protocol", "imaps")
            props.put("mail.imaps.host", "imap.gmail.com")
            props.put("mail.imaps.port", "993")
            props.put("mail.imaps.ssl.enable", "true")
            
            Session session = Session.getInstance(props, null)
            Store store = session.getStore("imaps")
            store.connect(config.amazonEmail, config.amazonEmailPassword)
            
            Folder inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            
            // Calculate date range
            Calendar cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -(config.lookBackDays + 2))
            Date fromDate = cal.getTime()
            
            logger.info("Looking back ${config.lookBackDays + 2} days for Walmart order emails")
            
            // Build list of from addresses to search
            List<FromTerm> fromTerms = WALMART_EMAIL_PATTERNS.collect { 
                new FromTerm(new InternetAddress(it)) 
            }
            
            // Add forward_from_address if configured
            if (config.walmartForwardFromAddress) {
                fromTerms.add(new FromTerm(new InternetAddress(config.walmartForwardFromAddress)))
            }
            
            // Search for messages with "Order number" or "Order #" in body
            Message[] messages = inbox.search(
                new AndTerm(
                    new OrTerm(fromTerms as FromTerm[]),
                    new ReceivedDateTerm(ComparisonTerm.GT, fromDate)
                )
            )
            
            logger.info("Found ${messages.length} Walmart emails")
            
            // Extract order IDs from each message
            messages.each { message ->
                try {
                    String content = getEmailContent(message)
                    Date sentDate = message.getSentDate()
                    
                    // Extract order ID
                    String orderId = extractOrderId(content)
                    if (orderId) {
                        // Keep the latest date for each order ID
                        if (!orderIdToDateMap.containsKey(orderId) || 
                            sentDate.after(orderIdToDateMap[orderId])) {
                            orderIdToDateMap[orderId] = sentDate
                            logger.debug("Found order ID: ${orderId} with date: ${sentDate}")
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error parsing email: ${e.message}")
                }
            }
            
            inbox.close(false)
            store.close()
            
        } catch (Exception e) {
            logger.error("Error fetching order IDs from email: ${e.message}", e)
        }
        
        return orderIdToDateMap
    }
    
    /**
     * Extract order ID from email content
     */
    private String extractOrderId(String content) {
        // Try HTML pattern first (with anchor tag)
        Matcher matcher = ORDER_ID_PATTERN.matcher(content)
        if (matcher.find()) {
            return matcher.group(2)
        }
        
        // Try plain text pattern
        matcher = ORDER_ID_TEXT_PATTERN.matcher(content)
        if (matcher.find()) {
            return matcher.group(2)
        }
        
        // Try direct pattern (XXXXXXX-XXXXXXXX format)
        matcher = ORDER_ID_DIRECT_PATTERN.matcher(content)
        if (matcher.find()) {
            return matcher.group(1)
        }
        
        return null
    }
    
    /**
     * Get text content from email message
     */
    private String getEmailContent(Message message) {
        try {
            Object content = message.getContent()
            if (content instanceof String) {
                return content
            } else if (content instanceof Multipart) {
                return extractContentFromMultipart(content)
            }
        } catch (Exception e) {
            logger.warn("Error extracting email content: ${e.message}")
        }
        return ""
    }
    
    /**
     * Extract content from multipart message (handles nested multiparts)
     */
    private String extractContentFromMultipart(Multipart multipart) {
        StringBuilder textContent = new StringBuilder()
        
        try {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i)
                
                if (bodyPart.isMimeType("text/plain")) {
                    textContent.append(bodyPart.getContent())
                } else if (bodyPart.isMimeType("text/html")) {
                    textContent.append(bodyPart.getContent())
                } else if (bodyPart.getContent() instanceof Multipart) {
                    // Handle nested multipart
                    textContent.append(extractContentFromMultipart((Multipart) bodyPart.getContent()))
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting content from multipart: ${e.message}")
        }
        
        return textContent.toString()
    }
    
    /**
     * Fetch order details for a specific order ID using the guest checkout flow
     */
    private WalmartOrder fetchOrderDetails(String orderId, Date emailDate) {
        try {
            // Navigate to orders page
            logger.debug("Navigating to Walmart orders page for order ${orderId}")
            page.navigate(config.walmartOrdersUrl)
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED)
            randomDelay(1000, 2000)
            
            // Check for bot detection after page load
            boolean botHandled = handleBotDetectionIfPresent()
            if (!botHandled) {
                logger.error("Failed to handle bot detection on orders page for order ${orderId}")
                return null
            }
            
            // Wait a bit after bot detection handling
            if (botHandled) {
                randomDelay(2000, 3000)
            }
            
            // Fill in email address
            def emailInput = page.locator("input[name='emailAddress']")
            if (emailInput.count() == 0) {
                logger.error("Could not find email input field")
                return null
            }
            emailInput.fill(config.walmartEmail)
            randomDelay(500, 1000)
            
            // Fill in order number
            def orderInput = page.locator("input[name='orderNumber']")
            if (orderInput.count() == 0) {
                logger.error("Could not find order number input field")
                return null
            }
            orderInput.fill(orderId)
            randomDelay(500, 1000)
            
            // Click "View order status" button
            def viewButton = page.locator("text=View order status, button:has-text('View order status')")
            if (viewButton.count() == 0) {
                logger.error("Could not find View order status button")
                return null
            }
            viewButton.first().click()
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            randomDelay(1000, 2000)
            
            // Check for bot detection after submitting form
            boolean botHandledAfterSubmit = handleBotDetectionIfPresent()
            if (!botHandledAfterSubmit) {
                logger.error("Failed to handle bot detection after form submission for order ${orderId}")
                return null
            }
            
            // Wait a bit after bot detection handling
            if (botHandledAfterSubmit) {
                randomDelay(2000, 3000)
            }
            
            // Parse order details (reuse logic from WalmartOrderFetcher)
            return parseOrderDetails(orderId, emailDate)
            
        } catch (Exception e) {
            logger.error("Error fetching order details for ${orderId}: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Initialize Playwright browser - tries Firefox first for non-headless, then Chromium with special flags
     * (Reused from WalmartOrderFetcher)
     */
    public void initBrowser() {
        String browserMode = config.walmartHeadless ? "headless" : "non-headless"
        logger.info("Initializing ${browserMode} browser for Walmart Guest Mode")
        
        playwright = Playwright.create()
        
        // For non-headless mode, try Firefox first as it's more stable on macOS
        if (!config.walmartHeadless) {
            try {
                logger.debug("Attempting to launch Firefox in non-headless mode with anti-detection")
                def firefoxOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setSlowMo(150 + (int)(Math.random() * 200)) // Random slow mo
                    .setArgs([
                        "--disable-blink-features=AutomationControlled"
                    ])

                browser = playwright.firefox().launch(firefoxOptions)

                // Create context with realistic browser fingerprint
                def contextOptions = new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                    .setLocale("en-US")
                    .setTimezoneId("America/New_York")

                context = browser.newContext(contextOptions)
                page = context.newPage()
                page.setDefaultTimeout(config.walmartBrowserTimeout)

                // Add human-like browser behavior
                addHumanBehaviorScripts(page)

                logger.info("Firefox browser initialized successfully in non-headless mode with anti-detection measures")
                return
            } catch (Exception e) {
                logger.warn("Firefox initialization failed: ${e.message}, trying Chromium")
                cleanupBrowser()
                playwright = Playwright.create()
            }
        }
        
        // Try Chromium with enhanced anti-detection measures
        try {
            def launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(config.walmartHeadless)

            // Basic anti-detection arguments
            def args = [
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox"
            ]

            if (!config.walmartHeadless) {
                logger.debug("Launching Chromium in non-headless mode with enhanced anti-detection")
                args.add("--disable-background-timer-throttling")
                args.add("--disable-renderer-backgrounding")
                launchOptions.setSlowMo(150 + (int)(Math.random() * 200)) // Random slow mo 150-350ms
            }

            launchOptions.setArgs(args)
            browser = playwright.chromium().launch(launchOptions)

            // Create context with realistic browser fingerprint
            def contextOptions = new Browser.NewContextOptions()
                .setViewportSize(1920, 1080)  // More common resolution
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .setLocale("en-US")
                .setTimezoneId("America/New_York")

            context = browser.newContext(contextOptions)
            page = context.newPage()
            page.setDefaultTimeout(config.walmartBrowserTimeout)

            // Add human-like browser behavior
            addHumanBehaviorScripts(page)

            logger.info("Chromium browser initialized successfully in ${browserMode} mode with anti-detection measures")
        } catch (Exception e) {
            logger.error("Failed to initialize Chromium browser: ${e.message}", e)
            throw new RuntimeException("Browser initialization failed", e)
        }
    }
    
    /**
     * Helper method to clean up browser resources
     */
    private void cleanupBrowser() {
        try {
            if (page != null) page.close()
        } catch (Exception ignored) {}
        try {
            if (context != null) context.close()
        } catch (Exception ignored) {}
        try {
            if (browser != null) browser.close()
        } catch (Exception ignored) {}
        try {
            if (playwright != null) playwright.close()
        } catch (Exception ignored) {}
        
        page = null
        context = null
        browser = null
        playwright = null
    }
    
    /**
     * Add JavaScript to make browser behavior appear more human-like
     */
    private void addHumanBehaviorScripts(Page page) {
        page.addInitScript("""
            // Override navigator.webdriver to undefined
            Object.defineProperty(navigator, 'webdriver', {
                get: () => undefined,
            });

            // Mock plugins and languages to appear more like a real browser
            Object.defineProperty(navigator, 'plugins', {
                get: () => [
                    { name: 'Chrome PDF Plugin', description: 'Portable Document Format', filename: 'internal-pdf-viewer' },
                    { name: 'Chrome PDF Viewer', description: '', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai' },
                    { name: 'Native Client', description: '', filename: 'internal-nacl-plugin' }
                ],
            });

            Object.defineProperty(navigator, 'languages', {
                get: () => ['en-US', 'en'],
            });

            // Add some realistic timing variations
            const originalGetTime = Date.prototype.getTime;
            Date.prototype.getTime = function() {
                return originalGetTime.call(this) + Math.floor(Math.random() * 10);
            };
        """)
    }
    
    /**
     * Close browser and cleanup resources
     */
    public void closeBrowser() {
        try {
            if (page != null) {
                page.close()
                logger.debug("Page closed")
            }
        } catch (Exception e) {
            logger.warn("Error closing page: ${e.message}")
        }
        
        try {
            if (context != null) {
                context.close()
                logger.debug("Browser context closed")
            }
        } catch (Exception e) {
            logger.warn("Error closing browser context: ${e.message}")
        }
        
        try {
            if (browser != null) {
                browser.close()
                logger.debug("Browser closed")
            }
        } catch (Exception e) {
            logger.warn("Error closing browser: ${e.message}")
        }
        
        try {
            if (playwright != null) {
                playwright.close()
                logger.debug("Playwright closed")
            }
        } catch (Exception e) {
            logger.warn("Error closing Playwright: ${e.message}")
        }
    }
    
    /**
     * Parse order details from the order details page
     * (Reused from WalmartOrderFetcher)
     */
    private WalmartOrder parseOrderDetails(String orderId, Date emailDate) {
        try {
            WalmartOrder order = new WalmartOrder()
            
            // Set order ID
            order.orderId = orderId
            
            // Extract order date - use email date as fallback
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
            order.orderDate = sdf.format(emailDate)
            
            // Try to extract order date from page if available
            String pageOrderDate = extractOrderDateFromDetails()
            if (pageOrderDate) {
                order.orderDate = pageOrderDate
            }
            
            // Extract order status
            order.orderStatus = extractOrderStatusFromDetails()
            
            // Extract total amount
            order.totalAmount = extractTotalAmount()
            
            // Extract items
            order.items = extractOrderItems()
            
            // Extract final charge amounts from charge history
            order.finalChargeAmounts = extractFinalChargeAmounts()
            
            // Set order URL
            order.orderUrl = "https://www.walmart.com/orders/details?orderId=${order.orderId}"
            
            logger.debug("Parsed order: ${order}")
            return order
            
        } catch (Exception e) {
            logger.error("Error parsing order details: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Extract order date from order details page
     */
    private String extractOrderDateFromDetails() {
        try {
            def dateLocator = page.locator('text=/Ordered\\s+on|Order\\s+date/i').locator("..").first()
            if (dateLocator.count() > 0) {
                String dateText = dateLocator.textContent()
                return parseDateString(dateText)
            }
        } catch (Exception e) {
            logger.debug("Could not extract order date from details: ${e.message}")
        }
        return null
    }
    
    /**
     * Extract order status from order details page
     */
    private String extractOrderStatusFromDetails() {
        try {
            def statusLocator = page.locator(".order-status, [data-automation-id='order-status']")
            if (statusLocator.count() > 0) {
                return statusLocator.first().textContent().trim()
            }
        } catch (Exception e) {
            logger.debug("Could not extract order status from details: ${e.message}")
        }
        return "Delivered" // Default assumption
    }
    
    /**
     * Extract total amount from order details page
     */
    private BigDecimal extractTotalAmount() {
        try {
            def totalLocator = page.locator('text=/Total|Order\\s+total/i').locator("..").first()
            if (totalLocator.count() > 0) {
                String text = totalLocator.textContent()
                def matcher = (text =~ /\$([0-9]+\.?[0-9]{0,2})/)
                if (matcher.find()) {
                    // Walmart charges are negative in YNAB (same as Amazon)
                    BigDecimal amount = new BigDecimal(matcher.group(1))
                    return amount * -1.0
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract total amount: ${e.message}")
        }
        return null
    }
    
    /**
     * Extract order items from order details page
     */
    private List<WalmartOrderItem> extractOrderItems() {
        List<WalmartOrderItem> items = []
        
        try {
            def itemLocators = page.locator(".order-item, [data-automation-id='order-item']").all()
            
            for (def itemLocator : itemLocators) {
                try {
                    WalmartOrderItem item = new WalmartOrderItem()
                    
                    // Extract title
                    def titleLocator = itemLocator.locator(".item-title, [data-automation-id='item-title']")
                    if (titleLocator.count() > 0) {
                        item.title = titleLocator.first().textContent().trim()
                    }
                    
                    // Extract price
                    def priceLocator = itemLocator.locator('text=/\\$\\d+\\.?\\d*/')
                    if (priceLocator.count() > 0) {
                        String priceText = priceLocator.first().textContent()
                        def matcher = (priceText =~ /\$([0-9]+\.?[0-9]{0,2})/)
                        if (matcher.find()) {
                            // Walmart item prices are negative in YNAB (same as Amazon)
                            BigDecimal price = new BigDecimal(matcher.group(1))
                            item.price = price * -1.0
                        }
                    }
                    
                    // Extract quantity (default to 1)
                    item.quantity = 1
                    
                    if (item.title) {
                        items.add(item)
                    }
                } catch (Exception e) {
                    logger.debug("Error parsing item: ${e.message}")
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract order items: ${e.message}")
        }
        
        return items
    }
    
    /**
     * Extract final charge amounts from charge history
     * Clicks "Charge History" button and extracts only "Final Order Charges"
     */
    private List<BigDecimal> extractFinalChargeAmounts() {
        List<BigDecimal> finalCharges = []
        
        try {
            // Look for "Charge History" button and click it
            def chargeHistoryButton = page.locator("text=Charge History, button:has-text('Charge History')")
            if (chargeHistoryButton.count() > 0) {
                // Scroll to button
                chargeHistoryButton.first().scrollIntoViewIfNeeded()
                
                // Click to expand
                chargeHistoryButton.first().click()
                logger.debug("Clicked Charge History button")
                
                // Wait for charge history panel to appear
                page.waitForTimeout(1000)
                
                // Look for "Final Order Charges" section
                def finalChargesSection = page.locator('text=/Final\\s+Order\\s+Charge/i').locator("..")
                if (finalChargesSection.count() > 0) {
                    // Extract all amounts from this section
                    def amountLocators = finalChargesSection.locator('text=/\\$\\d+\\.?\\d*/').all()
                    
                    for (def amountLocator : amountLocators) {
                        String amountText = amountLocator.textContent()
                        def matcher = (amountText =~ /\$([0-9]+\.?[0-9]{0,2})/)
                        if (matcher.find()) {
                            BigDecimal amount = new BigDecimal(matcher.group(1))
                            // Walmart final charges are negative in YNAB (same as Amazon)
                            BigDecimal negativeAmount = amount * -1.0
                            finalCharges.add(negativeAmount)
                            logger.debug("Found final charge: \$${negativeAmount}")
                        }
                    }
                }
                
                logger.info("Extracted ${finalCharges.size()} final charge amounts")
            } else {
                logger.debug("Charge History button not found")
            }
        } catch (Exception e) {
            logger.warn("Could not extract final charge amounts: ${e.message}")
        }
        
        return finalCharges
    }
    
    /**
     * Parse date string to yyyy-MM-dd format
     */
    private String parseDateString(String dateText) {
        try {
            // Try common date formats
            SimpleDateFormat[] formats = [
                new SimpleDateFormat("MMMM d, yyyy"),
                new SimpleDateFormat("MMM d, yyyy"),
                new SimpleDateFormat("MM/dd/yyyy"),
                new SimpleDateFormat("yyyy-MM-dd"),
                new SimpleDateFormat("EEE, MMM d, yyyy")  // Fri, Oct 24, 2025
            ]
            
            for (SimpleDateFormat format : formats) {
                try {
                    Date date = format.parse(dateText)
                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd")
                    return outputFormat.format(date)
                } catch (Exception e) {
                    // Try next format
                }
            }
        } catch (Exception e) {
            logger.debug("Could not parse date: ${dateText}")
        }
        return null
    }
    
    private void randomDelay(int minMs, int maxMs) {
        try {
            Thread.sleep(getRandomDelay(minMs, maxMs))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }
    
    private static int getRandomDelay(int min, int max) {
        return min + (int) (Math.random() * (max - min))
    }
    
    /**
     * Detect and handle bot verification page if present
     * Looks for "Press & Hold" button and holds it for configured time
     * @return true if no bot detection or successfully bypassed, false if failed
     */
    private boolean handleBotDetectionIfPresent() {
        try {
            // Check if we're on a blocked/verification page or have bot detection elements
            boolean isBlocked = page.url().contains("/blocked") ||
                               page.locator("text=Activate and hold the button").count() > 0 ||
                               page.locator("text=confirm that you're human").count() > 0 ||
                               page.locator("text=Press & Hold").count() > 0 ||
                               page.locator("text=Press and hold").count() > 0 ||
                               page.locator("text=Robot or human").count() > 0 ||
                               page.locator("#px-captcha").count() > 0

            if (!isBlocked) {
                logger.debug("No bot detection present, proceeding...")
                return true
            }

            logger.warn("Bot detection detected - looking for 'Press & Hold' button...")

            // Check if the button is inside an iframe
            FrameLocator captchaFrame = null
            try {
                captchaFrame = page.frameLocator("#px-captcha iframe")
                logger.debug("Found captcha iframe, will search inside it")
            } catch (Exception e) {
                logger.debug("No captcha iframe found, searching in main page")
            }

            // Look for the verification button - prioritize actual clickable elements
            def buttonSelectors = [
                "button:has-text('Press')",
                "button:has-text('Hold')",
                "[role='button']:has-text('Press')",
                "[role='button']:has-text('Hold')",
                "div[role='button']:has-text('Press')",
                "div[role='button']:has-text('Hold')",
                "span[role='button']:has-text('Press')",
                "span[role='button']:has-text('Hold')",
                "[aria-label*='Press']",
                "[aria-label*='Hold']",
                "div:has-text('Press & Hold')",
                "div:has-text('Press and hold')",
                "div:has-text('Press')",
                "div:has-text('Hold')",
                "text=Press & Hold",
                "text=Press and hold",
                "text=Press",
                "text=Hold",
                "[data-testid*='press']",
                "[data-testid*='hold']"
            ]

            Locator verifyButton = null
            for (String selector : buttonSelectors) {
                try {
                    Locator locator
                    if (captchaFrame != null) {
                        // Search inside the iframe
                        locator = captchaFrame.locator(selector)
                    } else {
                        // Search in main page
                        locator = page.locator(selector)
                    }

                    int count = locator.count()
                    logger.debug("Selector '${selector}' found ${count} elements" + (captchaFrame != null ? " (in iframe)" : " (in main page)"))
                    if (count > 0) {
                        verifyButton = locator.first()
                        logger.debug("Using selector: ${selector}" + (captchaFrame != null ? " (in iframe)" : " (in main page)"))
                        break
                    }
                } catch (Exception e) {
                    logger.debug("Error with selector '${selector}': ${e.message}")
                }
            }

            if (verifyButton == null) {
                logger.error("Bot detection present but could not find verification button")
                logger.error("Page URL: ${page.url()}")
                logger.error("Page title: ${page.title()}")
                
                // Take a screenshot for debugging
                try {
                    String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                    String filename = "walmart-guest-bot-detection-${timestamp}.png"
                    String screenshotPath = new File("logs/${filename}").absolutePath
                    new File("logs").mkdirs()
                    page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get(screenshotPath)).setFullPage(true))
                    logger.error("Screenshot saved to: ${screenshotPath}")
                } catch (Exception se) {
                    logger.debug("Could not take screenshot: ${se.message}")
                }
                
                return false
            }

            // Ensure the button is visible and scroll it into view
            try {
                if (captchaFrame != null) {
                    verifyButton.scrollIntoViewIfNeeded()
                } else {
                    verifyButton.evaluate("element => element.scrollIntoView({block: 'center', inline: 'center'})")
                }
                randomDelay(500, 1000)

                verifyButton.evaluate("""
                    element => {
                        element.style.display = 'block';
                        element.style.visibility = 'visible';
                        element.style.opacity = '1';
                        element.style.pointerEvents = 'auto';
                    }
                """)
                randomDelay(200, 500)
            } catch (Exception e) {
                logger.debug("Warning: Could not prepare button for interaction: ${e.message}")
            }

            // First, click on the button to activate it
            logger.debug("Clicking verification button to activate it...")
            try {
                verifyButton.click()
                randomDelay(500, 1000)
                logger.debug("Button clicked, now starting hold sequence...")
            } catch (Exception e) {
                logger.debug("Warning: Could not click button, trying mouse approach: ${e.message}")
            }

            // Get button position and hold it down
            logger.debug("Starting verification button hold sequence...")
            def box = verifyButton.boundingBox()
            if (box == null) {
                logger.error("Could not get initial button bounding box")
                return false
            }

            double centerX = box.x + (box.width / 2)
            double centerY = box.y + (box.height / 2)

            // Move mouse to position and press down
            page.mouse().move(centerX, centerY)
            randomDelay(200, 500)
            page.mouse().down()
            logger.debug("Mouse button pressed down, holding for extended period...")

            // Hold for the configured time
            long holdTimeMs = config.walmartBotDetectionHoldTimeMs
            long startTime = System.currentTimeMillis()
            long checkInterval = 500

            while (System.currentTimeMillis() - startTime < holdTimeMs) {
                try {
                    // Recheck button position and adjust mouse if needed
                    def currentBox = verifyButton.boundingBox()
                    if (currentBox != null) {
                        double currentCenterX = currentBox.x + (currentBox.width / 2)
                        double currentCenterY = currentBox.y + (currentBox.height / 2)

                        // If position changed significantly, move mouse
                        if (Math.abs(currentCenterX - centerX) > 10 || Math.abs(currentCenterY - centerY) > 10) {
                            page.mouse().move(currentCenterX, currentCenterY)
                            centerX = currentCenterX
                            centerY = currentCenterY
                            logger.debug("Adjusted mouse position to follow moving button")
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Warning: Could not recheck button position: ${e.message}")
                }

                Thread.sleep(checkInterval)
            }

            // Release the mouse button
            page.mouse().up()
            logger.info("Mouse button released after ${holdTimeMs}ms hold")

            // Wait for verification to process
            Thread.sleep(5000)
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(10000))

            // Check if still blocked
            boolean stillBlocked = page.url().contains("/blocked")
            if (!stillBlocked) {
                logger.info("Bot verification appears successful")
            } else {
                logger.warn("Still on blocked page after verification attempt")
            }
            return !stillBlocked

        } catch (Exception e) {
            logger.error("Error handling bot detection: ${e.message}", e)
            return false
        }
    }
}
