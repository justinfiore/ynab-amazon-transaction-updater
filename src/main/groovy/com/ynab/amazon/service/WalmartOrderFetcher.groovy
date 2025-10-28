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
    public void initBrowser() {
        String browserMode = config.walmartHeadless ? "headless" : "non-headless"
        logger.info("Initializing ${browserMode} browser for Walmart")
        
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
     * Ensures cleanup happens even if errors occur
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
     * Authenticate with Walmart.com
     * Navigates to walmart.com and logs in using configured credentials
     */
    public void authenticate() {
        executeWithRetry("Authentication", MAX_RETRIES) {
        logger.info("Authenticating with Walmart.com")

        // Navigate to Walmart homepage with random delay
        page.navigate("https://www.walmart.com")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED)
        randomDelay(1000, 3000)  // Wait like a human would

        // Check for bot detection immediately after page load (Walmart sometimes triggers it here)
        boolean botDetectionHandled = false
        if (handleBotDetectionIfPresent()) {
            botDetectionHandled = true
            logger.debug("Bot detection was present and handled, page may have changed")
        // Wait a bit for any redirects or page changes to complete
        randomDelay(2000, 4000)
        }

        // If bot detection was handled, we might need to re-navigate to the main page
        if (botDetectionHandled && !page.url().contains("walmart.com")) {
        logger.debug("Redirected after bot detection, navigating back to Walmart")
        page.navigate("https://www.walmart.com")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED)
        randomDelay(1000, 2000)
        }

        // Do some human-like browsing behavior before signing in
        performHumanBrowsing()

        // Debug: Check page state before looking for sign in button
        try {
        logger.debug("Page URL before sign in: ${page.url()}")
        logger.debug("Page title before sign in: ${page.title()}")
        int signInButtonCount = page.locator("text=Sign In").count()
        logger.debug("Sign In buttons found: ${signInButtonCount}")
        } catch (Exception e) {
        logger.debug("Error checking page state: ${e.message}")
        }

        // Click Sign In button with human-like behavior
        def signInSelectors = [
        "text=Sign In",
        "a:has-text('Sign In')",
        "button:has-text('Sign In')",
        "[data-automation-id='sign-in']"
        ]

        boolean clicked = false
        for (String selector : signInSelectors) {
        try {
            if (page.locator(selector).count() > 0) {
                humanClick(selector)
                clicked = true
                logger.debug("Clicked initial Sign In button")
                        break
            }
        } catch (Exception ignored) {}
            }

        if (!clicked) {
                throw new RuntimeException("Could not find initial Sign In button")
        }

        // Wait for dropdown panel to appear and click the actual sign in button
        randomDelay(1000, 2000)

        def actualSignInSelectors = [
                "[data-dca-name='SignIn']",
        "button:has-text('Sign in or create account')",
        "a:has-text('Sign in or create account')",
        "[data-automation-id='sign-in-create-account']"
        ]

        boolean actualClicked = false
        for (String selector : actualSignInSelectors) {
        try {
        if (page.locator(selector).count() > 0) {
            humanClick(selector)
        actualClicked = true
            logger.debug("Clicked actual sign in button: ${selector}")
                break
                    }
        } catch (Exception ignored) {}
        }

        if (!actualClicked) {
        throw new RuntimeException("Could not find actual sign in button in dropdown")
            }

        // Check for bot detection after clicking sign in
            if (!handleBotDetectionIfPresent()) {
        throw new RuntimeException("Failed to handle bot detection after clicking sign in")
        }

        // Wait for the email/phone input field with random delay
        page.waitForSelector("input[name='Phone number or email']",
            new Page.WaitForSelectorOptions().setTimeout(10000))
        randomDelay(500, 1500)

        // Enter email with human-like typing
        String emailSelector = "input[name='Phone number or email']"
        humanType(emailSelector, config.walmartEmail)

        // Click Continue button
        def continueSelectors = [
        "button:has-text('Continue')",
        "button[type='submit']",
        "[data-automation-id='continue']"
        ]

        boolean continued = false
        for (String selector : continueSelectors) {
        try {
                if (page.locator(selector).count() > 0) {
                        humanClick(selector)
                    continued = true
                    logger.debug("Clicked Continue button")
                break
            }
        } catch (Exception ignored) {}
        }

        if (!continued) {
        throw new RuntimeException("Could not find Continue button")
        }

            // Wait for the sign-in method selection page
        randomDelay(2000, 4000)
        page.waitForSelector("input[type='radio']", new Page.WaitForSelectorOptions().setTimeout(10000))

        // Select the "Password" radio button
        def passwordRadioSelectors = [
        "input[type='radio'][value*='password']",
        "input[type='radio']:has-text('Password')",
        "input[type='radio']",
                "[data-automation-id='password-radio']"
        ]

        boolean passwordSelected = false
        for (String selector : passwordRadioSelectors) {
        try {
        if (page.locator(selector).count() > 0) {
        page.check(selector)
        passwordSelected = true
        logger.debug("Selected Password sign-in method")
            break
        }
        } catch (Exception ignored) {}
        }

        // If no specific password radio found, try to find any radio button near "Password" text
        if (!passwordSelected) {
        try {
            def passwordOption = page.locator("text=Password").locator("xpath=ancestor::label//input[@type='radio']").first()
                    if (passwordOption.count() > 0) {
                passwordOption.check()
                passwordSelected = true
                    logger.debug("Selected Password sign-in method (via text locator)")
            }
        } catch (Exception ignored) {}
        }

            if (!passwordSelected) {
            throw new RuntimeException("Could not select Password sign-in method")
        }

        // Check for bot detection after selecting password method
        if (!handleBotDetectionIfPresent()) {
        throw new RuntimeException("Failed to handle bot detection after selecting password method")
        }

        // Wait for password field to appear
        page.waitForSelector("input[name='password']", new Page.WaitForSelectorOptions().setTimeout(5000))
        randomDelay(500, 1000)

        // Enter password with human-like typing
        String passwordSelector = "input[name='password']"
        humanType(passwordSelector, config.walmartPassword)

        // Click Sign In button
        def finalSignInSelectors = [
        "button:has-text('Sign In')",
        "button[type='submit']",
        "[data-automation-id='sign-in-submit']"
            ]

        boolean submitted = false
        for (String selector : finalSignInSelectors) {
        try {
        if (page.locator(selector).count() > 0) {
        humanClick(selector)
        submitted = true
        logger.debug("Clicked Sign In button")
        break
        }
        } catch (Exception ignored) {}
        }

            if (!submitted) {
        throw new RuntimeException("Could not find Sign In button")
        }

        // Check for bot detection after submitting login form
        if (!handleBotDetectionIfPresent()) {
        throw new RuntimeException("Failed to handle bot detection after submitting login")
        }

        // Wait for login to complete with random delay
        randomDelay(2000, 5000)
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
        new Page.WaitForLoadStateOptions().setTimeout(15000))
        }
    }
    
    /**
     * Detect and handle bot verification page if present
     * Looks for "Activate and hold the button to confirm that you're human" message
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
    page.locator("#px-captcha").count() > 0

    if (!isBlocked) {
    return true
    }

    logger.info("Bot detection detected, attempting to bypass...")

    // Debug: log page content to understand what's there
            try {
        String pageTitle = page.title()
        String currentUrl = page.url()
        logger.debug("Page title: ${pageTitle}")
        logger.debug("Current URL: ${currentUrl}")

    // Look for captcha iframe
    int captchaFrames = page.locator("#px-captcha").count()
    logger.debug("Captcha iframes found: ${captchaFrames}")

    // Look for any buttons on the page
    int totalButtons = page.locator("button").count()
                logger.debug("Total buttons found: ${totalButtons}")

    // Log text content that might indicate bot detection
    def pressHoldElements = page.locator("text=Press").all()
    logger.debug("Elements with 'Press' text: ${pressHoldElements.size()}")

    def holdElements = page.locator("text=Hold").all()
    logger.debug("Elements with 'Hold' text: ${holdElements.size()}")

    } catch (Exception e) {
    logger.debug("Error during debug logging: ${e.message}")
    }

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
                logger.error("Could not find verification button")
                return false
            }

            // Ensure the button is visible and scroll it into view
            try {
                if (captchaFrame != null) {
                    // For iframe elements, use scrollIntoViewIfNeeded which works across frames
                    verifyButton.scrollIntoViewIfNeeded()
                } else {
                    // For main page elements, use evaluate
                    verifyButton.evaluate("element => element.scrollIntoView({block: 'center', inline: 'center'})")
                }
                randomDelay(500, 1000)  // Wait for scroll to complete

                // Try to make sure the element is visible
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

            // First, click on the button to activate it, then hold it down
            logger.debug("Clicking verification button to activate it...")
            try {
                verifyButton.click()
                randomDelay(500, 1000)
                logger.debug("Button clicked, now starting hold sequence...")
            } catch (Exception e) {
                logger.debug("Warning: Could not click button, trying mouse approach: ${e.message}")
            }

            // Since the button moves, we'll try to hold it down for a longer time
            // and continuously check its position
            logger.debug("Starting verification button hold sequence...")

            // Initial position - get fresh position after click
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

            // Hold for the configured time since the button moves
            long holdTimeMs = config.walmartBotDetectionHoldTimeMs
            long startTime = System.currentTimeMillis()
            long checkInterval = 500 // Check position every 500ms

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
                    // Button might have disappeared or changed, continue holding
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
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
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
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
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
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
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
                            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
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

    private static int getRandomDelay(int min, int max) {
        return min + (int) (Math.random() * (max - min))
    }

    private void performHumanBrowsing() {
        // Simulate human browsing behavior - move mouse around, maybe scroll a bit
        try {
            // Random mouse movements
            def viewport = page.viewportSize()
            for (int i = 0; i < getRandomDelay(2, 5); i++) {
                int x = getRandomDelay(100, viewport.width - 100)
                int y = getRandomDelay(100, viewport.height - 100)
                page.mouse().move(x, y)
                randomDelay(200, 800)
            }

            // Maybe scroll down a bit like a human browsing
            if (Math.random() > 0.5) {
                page.mouse().wheel(0, getRandomDelay(200, 500))
                randomDelay(500, 1000)
            }
        } catch (Exception e) {
            // Ignore errors in human behavior simulation
        }
    }

    private void humanClick(String selector) {
        try {
            // Move mouse to element naturally
            def element = page.locator(selector).first()
            def box = element.boundingBox()
            if (box != null) {
                // Move to a random point within the element
                double targetX = box.x + getRandomDelay(5, (int)box.width - 5)
                double targetY = box.y + getRandomDelay(5, (int)box.height - 5)
                page.mouse().move(targetX, targetY)
                randomDelay(100, 300)
            }

            // Click with slight delay
            page.click(selector, new Page.ClickOptions().setDelay(getRandomDelay(50, 150)))
        } catch (Exception e) {
            // Fallback to regular click
            page.click(selector)
        }
    }

    private void humanType(String selector, String text) {
        // Focus on the field first
        page.focus(selector)
        randomDelay(100, 300)

        // Type each character with random delay
        for (char c : text.toCharArray()) {
            page.type(selector, String.valueOf(c))
            randomDelay(80, 200)  // Human typing speed
        }
    }

    private void randomDelay(int minMs, int maxMs) {
        try {
            Thread.sleep(getRandomDelay(minMs, maxMs))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    public void verifyLoginSuccess() {
        // Verify successful login by checking for account indicators
        boolean loggedIn = page.locator("text=Account").count() > 0 ||
                          page.locator("text=My Account").count() > 0 ||
                          page.url().contains("account") ||
                          !page.url().contains("signin")

        assert loggedIn : "Login verification failed. URL: ${page.url()}, Title: ${page.title()}"
    }
}
