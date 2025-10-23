package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import javax.mail.*
import javax.mail.internet.MimeMultipart
import javax.mail.internet.InternetAddress
import javax.mail.search.AndTerm
import javax.mail.search.OrTerm
import javax.mail.search.FromTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.ComparisonTerm
import java.text.SimpleDateFormat
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Service class for automatically fetching Amazon orders
 * Supports multiple methods: email parsing, web scraping, and API alternatives
 */
class AmazonOrderFetcher {
    private static final Logger logger = LoggerFactory.getLogger(AmazonOrderFetcher.class)
    
    private final Configuration config
    
    // Email patterns for Amazon order confirmations
    private static final List<String> AMAZON_EMAIL_PATTERNS = [
        "order-confirmation@amazon.com",
        "shipment-tracking@amazon.com",
        "digital-orders@amazon.com",
        "no-reply@amazon.com"
    ]
    
    // Email patterns for Amazon Subscribe and Save notifications
    private static final List<String> AMAZON_SUBSCRIPTION_EMAIL_PATTERNS = [
        "subscribe-and-save@amazon.com",
        "subscription-orders@amazon.com",
        "auto-delivery@amazon.com",
        "no-reply@amazon.com"
    ]
    
    // Regex patterns for extracting order information from emails
    private static final Pattern ORDER_ID_PATTERN = ~/(?i)Order\s*#\s*([A-Z0-9-]+)/
    private static final Pattern ORDER_DATE_PATTERN = ~/(?i)Ordered on ([A-Za-z]+ \d{1,2}, \d{4})/
    private static final Pattern ITEM_PATTERN = ~/\*\s*([^\r\n]+?)\s*Quantity: \d+\s*([\d.]+)\s*USD/
    private static final Pattern TOTAL_PATTERN = Pattern.compile('(?i)(?:Total|Total\\s+Amount|Total.+refund):?\\s*\\$?\\s*([0-9]+\\.?[0-9]{0,2})\\s*(?:USD)?', Pattern.DOTALL)
    
    // Regex patterns for Subscribe and Save emails
    private static final Pattern SUBSCRIPTION_ID_PATTERN = ~/(?i)(?:Subscription|Delivery)\s*(?:#|ID|Order)\s*([A-Z0-9-]+)/
    private static final Pattern SUBSCRIPTION_ITEM_PATTERN = ~/(?i)([^\r\n]+?)\s*.*?\*([0-9]+\.?[0-9]{0,2})\*/
    private static final Pattern DELIVERY_DATE_PATTERN = ~/(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \d{1,2})/
    private static final Pattern SUBSCRIPTION_TOTAL_PATTERN = Pattern.compile('(?i)(?:subscription total|amount charged|total cost):?\\s*\\$?\\s*([0-9]+\\.?[0-9]{0,2})\\s*(?:USD)?', Pattern.DOTALL)
    private static final Pattern SUBSCRIPTION_PRICE_PATTERN = ~/\*\$([0-9]+\.?[0-9]{0,2})\*/
    
    AmazonOrderFetcher(Configuration config) {
        this.config = config
    }
    
    /**
     * Main method to fetch Amazon orders using multiple strategies
     */
    List<AmazonOrder> fetchOrders() {
        List<AmazonOrder> orders = []
        
        // Try email parsing first
        if (config.amazonEmail && config.amazonEmailPassword) {
            logger.info("Attempting to fetch orders from email...")
            orders = fetchOrdersFromEmail()
            if (orders) {
                logger.info("Successfully fetched ${orders.size()} orders from email")
                return orders
            }
        }
        List<AmazonOrder> ordersWithDatesAndTotals = orders.findAll{ it.orderDate != null && it.totalAmount != null }
        logger.info("Found ${ordersWithDatesAndTotals.size()} orders with dates and totals")
        return orders
    }
    
    /**
     * Fetch orders from email by parsing Amazon order confirmation emails
     */
    private List<AmazonOrder> fetchOrdersFromEmail() {
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
            
            List<AmazonOrder> orders = []
            Map<String, AmazonOrder> orderMap = [:]
            
            // Search for Amazon order emails from the configured look-back period + 2 days
            Calendar cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -(config.lookBackDays + 2))
            Date fromDate = cal.getTime()
            logger.info("Looking back ${config.lookBackDays + 2} days (${config.lookBackDays} configured + 2 buffer) for Amazon orders")
            
            // Search for messages first - including Subscribe and Save emails
            // Build list of from addresses to search
            List<FromTerm> fromTerms = [
                new FromTerm(new InternetAddress("order-confirmation@amazon.com")),
                new FromTerm(new InternetAddress("auto-confirm@amazon.com")),
                new FromTerm(new InternetAddress("return@amazon.com")),
                new FromTerm(new InternetAddress("subscribe-and-save@amazon.com")),
                new FromTerm(new InternetAddress("subscription-orders@amazon.com")),
                new FromTerm(new InternetAddress("auto-delivery@amazon.com")),
                new FromTerm(new InternetAddress("no-reply@amazon.com"))
            ]
            
            // Add forward_from_address if configured (for forwarded S&S emails)
            if (config.amazonForwardFromAddress) {
                fromTerms.add(new FromTerm(new InternetAddress(config.amazonForwardFromAddress)))
            }
            
            Message[] messages = inbox.search(
                new AndTerm(
                    new OrTerm(fromTerms as FromTerm[]),
                    new ReceivedDateTerm(ComparisonTerm.GT, fromDate)
                )
            )
            logger.info("Num Amazon Messages: ${messages.length}")
            if(messages != null) {
                logger.info("Found ${messages.length} Amazon order emails")
            } else {
                logger.info("No Amazon order emails found")
            }
            
            // Sort messages by date (oldest first)
            messages = messages.sort { a, b ->
                try {
                    return a.sentDate <=> b.sentDate
                } catch (Exception e) {
                    return 0
                }
            }
            
            logger.info("Found ${messages.length} Amazon order emails (sorted oldest first)")
            messages.each { message ->
                try {
                    String fromAddress = message.getFrom()[0].toString().toLowerCase()
                    String subject = message.getSubject()
                    
                    logger.debug("Email from: ${fromAddress}, subject: ${subject}")
                    
                    // Check if this is a Subscribe and Save email
                    // Can be from no-reply@amazon.com OR forwarded from configured address
                    boolean isSubscribeAndSave = (subject.toLowerCase().contains("review your upcoming delivery") ||
                                                   subject.toLowerCase().contains("price changes")) &&
                                                  (fromAddress.contains("no-reply@amazon.com") || 
                                                   (config.amazonForwardFromAddress && fromAddress.contains(config.amazonForwardFromAddress.toLowerCase())))
                    
                    if (isSubscribeAndSave) {
                        logger.debug("Detected Subscribe and Save email")
                        // S&S emails return multiple orders (one per item)
                        List<AmazonOrder> subscriptionOrders = parseSubscriptionOrdersFromEmail(message)
                        subscriptionOrders.each { order ->
                            if (order) {
                                orderMap[order.orderId] = order
                                orders.add(order)
                            }
                        }
                    } else {
                        AmazonOrder order = parseOrderFromEmail(message)
                        if (order) {
                            // Merge with existing order if same order ID
                            AmazonOrder existingOrder = orderMap.get(order.orderId)
                            if (existingOrder) {
                                if(existingOrder.items == null && order.items != null && order.items.size() > 0) {
                                    existingOrder.items = order.items
                                }
                                if(existingOrder.totalAmount == null) {
                                    existingOrder.totalAmount = order.totalAmount
                                }
                                if(existingOrder.orderDate == null) {
                                    existingOrder.orderDate = order.orderDate
                                }
                            } else {
                                orderMap[order.orderId] = order
                                orders.add(order)
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error parsing email: ${e.message}")
                }
            }
            
            inbox.close(false)
            store.close()
            
            return orders
            
        } catch (Exception e) {
            logger.error("Error fetching orders from email: ${e.message}", e)
            return []
        }
    }
    
    /**
     * Parse Subscribe and Save order information from an email message
     * Returns a list of orders - one per item since Amazon charges separately for each S&S item
     */
    private List<AmazonOrder> parseSubscriptionOrdersFromEmail(Message message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
        List<AmazonOrder> orders = []
        
        try {
            String subject = message.getSubject()
            String content = getEmailContent(message)
            logger.debug("Parsing subscription email: ${subject} ...")
            
            // Extract delivery date from "Arriving by [Day], [Month] [Date]" pattern
            String orderDate = sdf.format(message.getSentDate()) // Default to email date
            Matcher deliveryDateMatcher = DELIVERY_DATE_PATTERN.matcher(content)
            if (deliveryDateMatcher.find()) {
                try {
                    String dateStr = deliveryDateMatcher.group(1)
                    logger.debug("Found delivery date string: ${dateStr}")
                    
                    // Parse "Friday, Jul 11" format - need to add year
                    String currentYear = String.valueOf(Calendar.getInstance().get(Calendar.YEAR))
                    String fullDateStr = dateStr + ", " + currentYear
                    
                    SimpleDateFormat emailDateFormat = new SimpleDateFormat("EEEE, MMM d, yyyy")
                    Date parsedDate = emailDateFormat.parse(fullDateStr)
                    orderDate = sdf.format(parsedDate)
                    logger.debug("Parsed delivery date: ${orderDate}")
                } catch (Exception e) {
                    logger.debug("Could not parse delivery date '${deliveryDateMatcher.group(1)}', using email date: ${e.message}")
                }
            }
            
            // Extract individual item prices using the *$XX.XX* pattern
            Matcher priceMatcher = SUBSCRIPTION_PRICE_PATTERN.matcher(content)
            int itemCount = 0
            while (priceMatcher.find()) {
                BigDecimal price = new BigDecimal(priceMatcher.group(1))
                itemCount++
                
                // Create a separate order for each item since Amazon charges separately
                String orderId = "S&S-" + orderDate.replace("-", "") + "-" + String.format("%02d", itemCount)
                String itemTitle = "S&S Item ${itemCount}"
                
                // Amazon charges are negative in YNAB
                BigDecimal amount = price * -1.0
                
                AmazonOrder order = new AmazonOrder(
                    orderId: orderId,
                    orderDate: orderDate,
                    totalAmount: amount,
                    items: [new AmazonOrderItem(title: itemTitle, price: price, quantity: 1)],
                    isReturn: false
                )
                
                orders.add(order)
            }
            
            // If no individual prices found, create a single order
            if (orders.isEmpty()) {
                // Look for any price pattern in the content
                Matcher anyPriceMatcher = Pattern.compile('\\$([0-9]+\\.?[0-9]{0,2})').matcher(content)
                if (anyPriceMatcher.find()) {
                    BigDecimal price = new BigDecimal(anyPriceMatcher.group(1))
                    String orderId = "S&S-" + orderDate.replace("-", "") + "-01"
                    
                    AmazonOrder order = new AmazonOrder(
                        orderId: orderId,
                        orderDate: orderDate,
                        totalAmount: price * -1.0,
                        items: [new AmazonOrderItem(title: "S&S Delivery", price: price, quantity: 1)],
                        isReturn: false
                    )
                    orders.add(order)
                } else {
                    logger.warn("No prices found in subscription email")
                    return []
                }
            }
            
            logger.debug("Parsed ${orders.size()} Subscribe & Save orders from email")
            return orders
            
        } catch (Exception e) {
            logger.error("Error parsing subscription from email: ${e.message}", e)
            return []
        }
    }
    
    /**
     * Parse order information from an email message
     */
    private AmazonOrder parseOrderFromEmail(Message message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
        try {
            String subject = message.getSubject()
            String content = getEmailContent(message)
            logger.debug("Parsing email: ${subject} ...")
            
            // Extract order ID
            Matcher orderIdMatcher = ORDER_ID_PATTERN.matcher(content)
            if (!orderIdMatcher.find()) {
                logger.debug("No order ID found in email: ${subject}")
                logger.trace("Email content: ${content}")
                return null
            }
            String orderId = orderIdMatcher.group(1)
            Boolean isReturn = false
            String orderDate = sdf.format(message.getSentDate())
            if(message.getFrom().toString().contains("return@amazon.com")) {
                isReturn = true
                orderId = "RETURN-" + orderId
            }
            // Extract total amount
            Matcher totalMatcher = TOTAL_PATTERN.matcher(content)
            if (!totalMatcher.find()) {
                logger.warn("No total amount found in order ${orderId}")
                logger.debug("Body Content: ${content}")
                return null
            }
            BigDecimal total = new BigDecimal(totalMatcher.group(1))
            
            // Extract items (for reference, but we'll use the total from above)
            List<AmazonOrderItem> items = []
            Matcher itemMatcher = ITEM_PATTERN.matcher(content)
            while (itemMatcher.find()) {
                String title = itemMatcher.group(1).trim()
                BigDecimal price = new BigDecimal(itemMatcher.group(2))
                items.add(new AmazonOrderItem(title: title, price: price))
            }
            if(!isReturn && total != null) {
                total = total * -1.0
            }
            AmazonOrder order = new AmazonOrder(
                orderId: orderId,
                orderDate: orderDate,
                totalAmount: total,
                items: items,
                isReturn: isReturn
            )
            logger.debug("Parsed Order: ${order}")
            return order
            
        } catch (Exception e) {
            logger.error("Error parsing order from email: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get text content from email message
     */
    private String getEmailContent(Message message) {
        try {
            Object content = message.getContent()
            if (content instanceof String) {
                return content
            } else if (content instanceof MimeMultipart) {
                MimeMultipart multipart = (MimeMultipart) content
                StringBuilder textContent = new StringBuilder()
                
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart bodyPart = multipart.getBodyPart(i)
                    if (bodyPart.isMimeType("text/plain")) {
                        textContent.append(bodyPart.getContent())
                    }
                }
                
                return textContent.toString()
            }
        } catch (Exception e) {
            logger.warn("Error extracting email content: ${e.message}")
        }
        return ""
    }
    
    /**
     * Save fetched orders to CSV file for backup
     */
    void saveOrdersToCsv(List<AmazonOrder> orders, String filePath) {
        try {
            File csvFile = new File(filePath)
            csvFile.parentFile?.mkdirs()
            
            StringBuilder csvContent = new StringBuilder()
            csvContent.append("Order ID,Order Date,Title,Price,Quantity\n")
            
            orders.each { order ->
                order.items.each { item ->
                    csvContent.append("${order.orderId},${order.orderDate},${item.title},${item.price},${item.quantity}\n")
                }
            }
            
            csvFile.text = csvContent.toString()
            logger.info("Saved ${orders.size()} orders to CSV: ${filePath}")
            
        } catch (Exception e) {
            logger.error("Error saving orders to CSV: ${e.message}")
        }
    }
} 