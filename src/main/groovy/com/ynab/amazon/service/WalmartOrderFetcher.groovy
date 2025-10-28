package com.ynab.amazon.service

import com.microsoft.playwright.*
import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.WalmartOrder
import com.ynab.amazon.model.WalmartOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Service class for fetching Walmart orders using browser automation
 * Uses Playwright to navigate walmart.com and extract order data
 */
class WalmartOrderFetcher {
    private static final Logger logger = LoggerFactory.getLogger(WalmartOrderFetcher.class)
    
    private static final int MAX_RETRIES = 3
    private static final int INITIAL_RETRY_DELAY_MS = 1000
    
    private final Configuration config
    private Playwright playwright
    private Browser browser
    private BrowserContext context
    private Page page
    private int skippedOrdersCount = 0
    
    WalmartOrderFetcher(Configuration config) {
        this.config = config
    }
    
    /**
     * Main method to fetch Walmart orders using browser automation
     * @return List of WalmartOrder objects
     */
    List<WalmartOrder> fetchOrders() {
        List<WalmartOrder> orders = []
        skippedOrdersCount = 0
        
        try {
            logger.info("Fetching Walmart orders using browser automation")
            initBrowser()
            authenticate()
            orders = fetchAndParseOrders()
            logger.info("Successfully fetched ${orders.size()} Walmart orders (skipped ${skippedOrdersCount} orders)")
        } catch (Exception e) {
            logger.error("Error fetching Walmart orders: ${e.message}", e)
        } finally {
            closeBrowser()
        }
        
        return orders
    }
    
    /**
     * Initialize Playwright browser - tries Firefox first for non-headless, then Chromium with special flags
     */
    private void initBrowser() {
        String browserMode = config.walmartHeadless ? "headless" : "non-headless"
        logger.info("Initializing ${browserMode} browser for Walmart")
        
        playwright = Playwright.create()
        
        // For non-headless mode, try Firefox first as it's more stable on macOS
        if (!config.walmartHeadless) {
            try {
                logger.debug("Attempting to launch Firefox in non-headless mode")
                def firefoxOptions = new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setSlowMo(100) // Slow down operations slightly for visibility
                
                browser = playwright.firefox().launch(firefoxOptions)
                context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1280, 1024))
                page = context.newPage()
                page.setDefaultTimeout(config.walmartBrowserTimeout)
                logger.info("Firefox browser initialized successfully in non-headless mode")
                return
            } catch (Exception e) {
                logger.warn("Firefox initialization failed: ${e.message}, trying Chromium")
                cleanupBrowser()
                playwright = Playwright.create()
            }
        }
        
        // Try Chromium with special flags
        try {
            def launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(config.walmartHeadless)
            
            // Add args to prevent crashes on macOS
            if (!config.walmartHeadless) {
                logger.debug("Launching Chromium in non-headless mode with GPU disabled")
                launchOptions.setArgs([
                    "--disable-gpu",
                    "--disable-software-rasterizer",  
                    "--disable-dev-shm-usage",
                    "--no-sandbox",
                    "--disable-setuid-sandbox",
                    "--disable-blink-features=AutomationControlled"
                ])
                launchOptions.setSlowMo(100)
            }
            
            browser = playwright.chromium().launch(launchOptions)
            context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 1024))
            page = context.newPage()
            page.setDefaultTimeout(config.walmartBrowserTimeout)
            logger.info("Chromium browser initialized successfully in ${browserMode} mode")
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
     * Close browser and cleanup resources
     * Ensures cleanup happens even if errors occur
     */
    private void closeBrowser() {
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
     * Authenticate with Walmart.com
     * Navigates to walmart.com and logs in using configured credentials
     */
    private void authenticate() {
        executeWithRetry("Authentication", MAX_RETRIES) {
            logger.info("Authenticating with Walmart.com")
            
            // Navigate to Walmart homepage
            page.navigate("https://www.walmart.com")
            logger.info("Navigated to walmart.com - URL: ${page.url()}")
            takeDebugScreenshot("01-homepage")
            
            // Wait for page to load
            page.waitForLoadState(LoadState.DOMCONTENTLOADED)
            
            // Click "Sign In" button - try multiple selectors
            try {
                logger.debug("Looking for Sign In button...")
                
                // Log all visible text on page for debugging
                String pageText = page.content()
                logger.debug("Page title: ${page.title()}")
                
                // Try different selectors for Sign In
                def signInSelectors = [
                    "text=Sign In",
                    "a:has-text('Sign In')",
                    "button:has-text('Sign In')",
                    "[data-automation-id='sign-in']",
                    "span:has-text('Sign In')"
                ]
                
                boolean clicked = false
                for (String selector : signInSelectors) {
                    try {
                        if (page.locator(selector).count() > 0) {
                            logger.info("Found Sign In button with selector: ${selector}")
                            page.click(selector, new Page.ClickOptions().setTimeout(5000))
                            clicked = true
                            break
                        }
                    } catch (Exception ignored) {
                        logger.debug("Selector '${selector}' didn't work")
                    }
                }
                
                if (!clicked) {
                    takeDebugScreenshot("02-signin-button-not-found")
                    logger.error("Could not find Sign In button with any selector")
                    throw new RuntimeException("Cannot find Sign In button")
                }
                
                logger.info("Clicked Sign In button")
                takeDebugScreenshot("03-after-signin-click")
            } catch (Exception e) {
                takeDebugScreenshot("error-signin-button")
                logger.error("Failed to find/click Sign In button: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot find Sign In button", e)
            }
            
            // Wait for sign-in form to appear
            logger.debug("Waiting for email input field...")
            try {
                page.waitForSelector("input[type='email'], input[name='email'], input[id*='email']", 
                    new Page.WaitForSelectorOptions().setTimeout(10000))
                logger.info("Email input field appeared")
                takeDebugScreenshot("04-signin-form")
            } catch (Exception e) {
                takeDebugScreenshot("error-no-email-field")
                logger.error("Email input field did not appear: ${e.message}")
                throw e
            }
            
            // Enter email
            try {
                String emailSelector = "input[type='email'], input[name='email'], input[id*='email']"
                page.fill(emailSelector, config.walmartEmail)
                logger.info("Entered email address")
                takeDebugScreenshot("05-email-entered")
            } catch (Exception e) {
                takeDebugScreenshot("error-email-entry")
                logger.error("Failed to enter email: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot enter email", e)
            }
            
            // Enter password
            try {
                String passwordSelector = "input[type='password'], input[name='password'], input[id*='password']"
                page.fill(passwordSelector, config.walmartPassword)
                logger.info("Entered password")
                takeDebugScreenshot("06-password-entered")
            } catch (Exception e) {
                takeDebugScreenshot("error-password-entry")
                logger.error("Failed to enter password: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot enter password", e)
            }
            
            // Click submit button
            try {
                logger.debug("Looking for submit button...")
                def submitSelectors = [
                    "button[type='submit']",
                    "button:has-text('Sign In')",
                    "button:has-text('Sign in')",
                    "[data-automation-id='sign-in-submit']"
                ]
                
                boolean submitted = false
                for (String selector : submitSelectors) {
                    try {
                        if (page.locator(selector).count() > 0) {
                            logger.info("Found submit button with selector: ${selector}")
                            page.click(selector)
                            submitted = true
                            break
                        }
                    } catch (Exception ignored) {}
                }
                
                if (!submitted) {
                    takeDebugScreenshot("error-no-submit-button")
                    throw new RuntimeException("Cannot find submit button")
                }
                
                logger.info("Clicked submit button")
                takeDebugScreenshot("07-submit-clicked")
            } catch (Exception e) {
                takeDebugScreenshot("error-submit-button")
                logger.error("Failed to click submit button: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot submit login form", e)
            }
            
            // Wait for navigation after login - look for account indicator
            try {
                logger.debug("Waiting for login to complete...")
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000))
                logger.info("Page loaded after login - URL: ${page.url()}")
                takeDebugScreenshot("08-after-login")
                
                // Verify successful login by checking for account menu or "My Account" link
                boolean loggedIn = page.locator("text=Account").count() > 0 ||
                                   page.locator("text=My Account").count() > 0 ||
                                   page.url().contains("account") ||
                                   !page.url().contains("signin")
                
                logger.debug("Login verification - Account locator count: ${page.locator('text=Account').count()}")
                logger.debug("Login verification - URL contains 'account': ${page.url().contains('account')}")
                logger.debug("Login verification - URL contains 'signin': ${page.url().contains('signin')}")
                
                if (!loggedIn) {
                    takeDebugScreenshot("09-login-verification-failed")
                    logger.error("Login verification failed - account indicators not found. URL: ${page.url()}")
                    logger.error("Page title: ${page.title()}")
                    throw new RuntimeException("Authentication failed: Login verification failed")
                }
                
                logger.info("Successfully authenticated with Walmart.com")
                takeDebugScreenshot("10-login-success")
            } catch (TimeoutError e) {
                takeDebugScreenshot("error-login-timeout")
                logger.error("Timeout waiting for login to complete: ${e.message}")
                logger.error("Current URL: ${page.url()}")
                throw new RuntimeException("Authentication failed: Login timeout", e)
            }
        }
    }
    
    /**
     * Take a debug screenshot for troubleshooting
     */
    private void takeDebugScreenshot(String step) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
            String filename = "walmart-debug-${step}-${timestamp}.png"
            String screenshotPath = new File("logs/${filename}").absolutePath
            
            // Ensure logs directory exists
            new File("logs").mkdirs()
            
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(screenshotPath)).setFullPage(true))
            logger.debug("Screenshot saved: ${screenshotPath}")
        } catch (Exception e) {
            logger.warn("Failed to take screenshot: ${e.message}")
        }
    }
    
    /**
     * Fetch and parse orders from Walmart orders page
     * @return List of parsed WalmartOrder objects
     */
    private List<WalmartOrder> fetchAndParseOrders() {
        List<WalmartOrder> orders = []
        
        try {
            // Navigate to orders page with retry
            executeWithRetry("Navigate to orders page", MAX_RETRIES) {
                logger.info("Navigating to Walmart orders page")
                page.navigate(config.walmartOrdersUrl)
                page.waitForLoadState(LoadState.NETWORKIDLE)
                logger.debug("Orders page loaded")
                
                // Wait for orders to load
                page.waitForSelector(".order-card, [data-automation-id='order-card']", 
                    new Page.WaitForSelectorOptions().setTimeout(10000))
            }
            
            // Calculate date range for filtering
            LocalDate cutoffDate = LocalDate.now().minusDays(config.lookBackDays)
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            logger.info("Filtering orders from the last ${config.lookBackDays} days (since ${cutoffDate.format(formatter)})")
            
            // Get all order cards
            def orderCards = page.locator(".order-card, [data-automation-id='order-card']").all()
            logger.info("Found ${orderCards.size()} order cards on page")
            
            for (int i = 0; i < orderCards.size(); i++) {
                try {
                    // Re-query order cards to avoid stale references after navigation
                    def currentOrderCards = page.locator(".order-card, [data-automation-id='order-card']").all()
                    if (i >= currentOrderCards.size()) {
                        logger.warn("Order card index ${i} out of bounds, stopping")
                        break
                    }
                    
                    def orderCard = currentOrderCards.get(i)
                    
                    // Extract order status
                    String orderStatus = extractOrderStatus(orderCard)
                    logger.debug("Order ${i + 1}: Status = ${orderStatus}")
                    
                    // Skip non-delivered orders
                    if (!orderStatus?.equalsIgnoreCase("Delivered")) {
                        logger.debug("Skipping order ${i + 1} - not delivered (status: ${orderStatus})")
                        skippedOrdersCount++
                        continue
                    }
                    
                    // Extract order date
                    String orderDateStr = extractOrderDate(orderCard)
                    if (orderDateStr) {
                        LocalDate orderDate = LocalDate.parse(orderDateStr, formatter)
                        if (orderDate.isBefore(cutoffDate)) {
                            logger.debug("Skipping order ${i + 1} - outside lookback period (${orderDateStr})")
                            skippedOrdersCount++
                            continue
                        }
                    }
                    
                    // Click "View Details" to open order details
                    orderCard.locator("text=View Details, a:has-text('View Details')").first().click()
                    page.waitForLoadState(LoadState.NETWORKIDLE)
                    logger.debug("Opened order details for order ${i + 1}")
                    
                    // Parse order details
                    WalmartOrder order = parseOrderDetails()
                    
                    if (order) {
                        orders.add(order)
                        logger.info("Successfully parsed order: ${order.orderId}")
                    } else {
                        logger.warn("Failed to parse order ${i + 1}")
                        skippedOrdersCount++
                    }
                    
                    // Navigate back to orders list
                    page.goBack()
                    page.waitForLoadState(LoadState.NETWORKIDLE)
                    page.waitForSelector(".order-card, [data-automation-id='order-card']", 
                        new Page.WaitForSelectorOptions().setTimeout(5000))
                    logger.debug("Navigated back to orders list")
                    
                } catch (Exception e) {
                    logger.warn("Error processing order ${i + 1}: ${e.message}")
                    skippedOrdersCount++
                    
                    // Try to navigate back to orders list if we're stuck
                    try {
                        if (!page.url().contains("/orders")) {
                            page.navigate(config.walmartOrdersUrl)
                            page.waitForLoadState(LoadState.NETWORKIDLE)
                        }
                    } catch (Exception navError) {
                        logger.error("Failed to recover navigation: ${navError.message}")
                    }
                }
            }
            
            logger.info("Fetched ${orders.size()} orders, skipped ${skippedOrdersCount} orders")
            
        } catch (Exception e) {
            logger.error("Error fetching orders: ${e.message}", e)
        }
        
        return orders
    }
    
    /**
     * Extract order status from order card
     */
    private String extractOrderStatus(Locator orderCard) {
        try {
            def statusLocator = orderCard.locator(".order-status, [data-automation-id='order-status']")
            if (statusLocator.count() > 0) {
                return statusLocator.first().textContent().trim()
            }
        } catch (Exception e) {
            logger.debug("Could not extract order status: ${e.message}")
        }
        return null
    }
    
    /**
     * Extract order date from order card
     */
    private String extractOrderDate(Locator orderCard) {
        try {
            def dateLocator = orderCard.locator(".order-date, [data-automation-id='order-date']")
            if (dateLocator.count() > 0) {
                String dateText = dateLocator.first().textContent().trim()
                // Parse date text and convert to yyyy-MM-dd format
                return parseDateString(dateText)
            }
        } catch (Exception e) {
            logger.debug("Could not extract order date: ${e.message}")
        }
        return null
    }
    
    /**
     * Parse order details from the order details page
     */
    private WalmartOrder parseOrderDetails() {
        try {
            WalmartOrder order = new WalmartOrder()
            
            // Extract order ID
            order.orderId = extractOrderId()
            if (!order.orderId) {
                logger.warn("Could not extract order ID")
                return null
            }
            
            // Extract order date
            order.orderDate = extractOrderDateFromDetails()
            
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
     * Extract order ID from order details page
     */
    private String extractOrderId() {
        try {
            def orderIdLocator = page.locator('text=/Order\\s*#?\\s*\\w+/i')
            if (orderIdLocator.count() > 0) {
                String text = orderIdLocator.first().textContent()
                def matcher = (text =~ /(?i)Order\s*#?\s*([A-Z0-9-]+)/)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract order ID: ${e.message}")
        }
        return null
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
        return "Delivered" // Default assumption if we got this far
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
                new SimpleDateFormat("yyyy-MM-dd")
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
    
    /**
     * Execute an operation with exponential backoff retry logic
     * @param operationName Name of the operation for logging
     * @param maxRetries Maximum number of retry attempts
     * @param operation Closure to execute
     */
    private void executeWithRetry(String operationName, int maxRetries, Closure operation) {
        int attempt = 0
        Exception lastException = null
        
        while (attempt < maxRetries) {
            try {
                operation.call()
                return // Success
            } catch (TimeoutError e) {
                lastException = e
                attempt++
                if (attempt < maxRetries) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt - 1)
                    logger.warn("${operationName} timeout (attempt ${attempt}/${maxRetries}), retrying in ${delayMs}ms: ${e.message}")
                    Thread.sleep(delayMs)
                } else {
                    logger.error("${operationName} failed after ${maxRetries} attempts")
                }
            } catch (Exception e) {
                lastException = e
                attempt++
                if (attempt < maxRetries) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt - 1)
                    logger.warn("${operationName} error (attempt ${attempt}/${maxRetries}), retrying in ${delayMs}ms: ${e.message}")
                    Thread.sleep(delayMs)
                } else {
                    logger.error("${operationName} failed after ${maxRetries} attempts")
                }
            }
        }
        
        // All retries exhausted
        throw new RuntimeException("${operationName} failed after ${maxRetries} attempts", lastException)
    }
}
