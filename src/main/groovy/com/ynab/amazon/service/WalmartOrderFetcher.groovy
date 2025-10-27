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
     * Initialize Playwright browser in headless mode
     */
    private void initBrowser() {
        try {
            String browserMode = config.walmartHeadless ? "headless" : "non-headless"
            logger.debug("Initializing ${browserMode} Chromium browser")
            playwright = Playwright.create()
            browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(config.walmartHeadless)
            )
            context = browser.newContext()
            page = context.newPage()
            page.setDefaultTimeout(config.walmartBrowserTimeout)
            logger.debug("Browser initialized successfully in ${browserMode} mode")
        } catch (Exception e) {
            logger.error("Failed to initialize browser: ${e.message}", e)
            throw new RuntimeException("Browser initialization failed", e)
        }
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
            logger.debug("Navigated to walmart.com")
            
            // Click "Sign In" button
            try {
                page.click("text=Sign In", new Page.ClickOptions().setTimeout(10000))
                logger.debug("Clicked Sign In button")
            } catch (Exception e) {
                logger.error("Failed to find Sign In button: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot find Sign In button", e)
            }
            
            // Wait for sign-in form to appear
            page.waitForSelector("input[type='email'], input[name='email']", new Page.WaitForSelectorOptions().setTimeout(10000))
            
            // Enter email
            try {
                page.fill("input[type='email'], input[name='email']", config.walmartEmail)
                logger.debug("Entered email address")
            } catch (Exception e) {
                logger.error("Failed to enter email: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot enter email", e)
            }
            
            // Enter password
            try {
                page.fill("input[type='password'], input[name='password']", config.walmartPassword)
                logger.debug("Entered password")
            } catch (Exception e) {
                logger.error("Failed to enter password: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot enter password", e)
            }
            
            // Click submit button
            try {
                page.click("button[type='submit'], button:has-text('Sign In')")
                logger.debug("Clicked submit button")
            } catch (Exception e) {
                logger.error("Failed to click submit button: ${e.message}")
                throw new RuntimeException("Authentication failed: Cannot submit login form", e)
            }
            
            // Wait for navigation after login - look for account indicator
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000))
                logger.debug("Page loaded after login")
                
                // Verify successful login by checking for account menu or "My Account" link
                boolean loggedIn = page.locator("text=Account, text=My Account").count() > 0 ||
                                   page.url().contains("account") ||
                                   !page.url().contains("signin")
                
                if (!loggedIn) {
                    logger.error("Login verification failed - account indicators not found")
                    throw new RuntimeException("Authentication failed: Login verification failed")
                }
                
                logger.info("Successfully authenticated with Walmart.com")
            } catch (TimeoutError e) {
                logger.error("Timeout waiting for login to complete: ${e.message}")
                throw new RuntimeException("Authentication failed: Login timeout", e)
            }
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
