package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import com.ynab.amazon.model.AmazonOrderItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.mail.*
import javax.mail.internet.MimeMultipart
import javax.mail.internet.InternetAddress
import java.text.SimpleDateFormat
import java.util.regex.Pattern
import java.util.regex.Matcher

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
    
    // Regex patterns for extracting order information from emails
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("Order #([A-Z0-9-]+)", Pattern.CASE_INSENSITIVE)
    private static final Pattern ORDER_DATE_PATTERN = Pattern.compile("Ordered on ([A-Za-z]+ \\d{1,2}, \\d{4})", Pattern.CASE_INSENSITIVE)
    private static final Pattern ITEM_PATTERN = Pattern.compile("([^\\n]+?)\\s*\\$([0-9]+\\.[0-9]{2})", Pattern.CASE_INSENSITIVE)
    private static final Pattern TOTAL_PATTERN = Pattern.compile("Total:\\s*\\$([0-9]+\\.[0-9]{2})", Pattern.CASE_INSENSITIVE)
    
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
        
        // Try web scraping as fallback
        logger.info("Attempting to fetch orders via web scraping...")
        orders = fetchOrdersFromWeb()
        if (orders) {
            logger.info("Successfully fetched ${orders.size()} orders from web")
            return orders
        }
        
        // Try API alternatives
        logger.info("Attempting to fetch orders via API alternatives...")
        orders = fetchOrdersFromAPI()
        if (orders) {
            logger.info("Successfully fetched ${orders.size()} orders from API")
            return orders
        }
        
        logger.warn("Could not fetch orders automatically. Falling back to CSV file.")
        return []
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
            
            // Search for Amazon order emails from the last 30 days
            Calendar cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -30)
            Date fromDate = cal.getTime()
            
            Message[] messages = inbox.search(
                new AndTerm(
                    new FromTerm(new InternetAddress("order-confirmation@amazon.com")),
                    new ReceivedDateTerm(ComparisonTerm.GT, fromDate)
                )
            )
            
            messages.each { message ->
                try {
                    AmazonOrder order = parseOrderFromEmail(message)
                    if (order) {
                        // Merge with existing order if same order ID
                        AmazonOrder existingOrder = orderMap.get(order.orderId)
                        if (existingOrder) {
                            existingOrder.items.addAll(order.items)
                            existingOrder.totalAmount += order.totalAmount
                        } else {
                            orderMap[order.orderId] = order
                            orders.add(order)
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
            logger.error("Error fetching orders from email: ${e.message}")
            return []
        }
    }
    
    /**
     * Parse order information from an email message
     */
    private AmazonOrder parseOrderFromEmail(Message message) {
        try {
            String subject = message.getSubject()
            String content = getEmailContent(message)
            
            // Extract order ID
            Matcher orderIdMatcher = ORDER_ID_PATTERN.matcher(content)
            if (!orderIdMatcher.find()) {
                return null
            }
            String orderId = orderIdMatcher.group(1)
            
            // Extract order date
            Matcher dateMatcher = ORDER_DATE_PATTERN.matcher(content)
            String orderDate = "Unknown"
            if (dateMatcher.find()) {
                orderDate = parseEmailDate(dateMatcher.group(1))
            }
            
            AmazonOrder order = new AmazonOrder()
            order.orderId = orderId
            order.orderDate = orderDate
            order.totalAmount = 0
            
            // Extract items
            Matcher itemMatcher = ITEM_PATTERN.matcher(content)
            while (itemMatcher.find()) {
                String title = itemMatcher.group(1).trim()
                BigDecimal price = new BigDecimal(itemMatcher.group(2))
                
                AmazonOrderItem item = new AmazonOrderItem()
                item.title = title
                item.price = price
                item.quantity = 1
                
                order.addItem(item)
                order.totalAmount += price
            }
            
            return order
            
        } catch (Exception e) {
            logger.warn("Error parsing email content: ${e.message}")
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
     * Parse date from email format to YYYY-MM-DD
     */
    private String parseEmailDate(String dateStr) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("MMMM d, yyyy")
            Date date = inputFormat.parse(dateStr)
            SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd")
            return outputFormat.format(date)
        } catch (Exception e) {
            logger.warn("Could not parse date: ${dateStr}")
            return "Unknown"
        }
    }
    
    /**
     * Fetch orders via web scraping (fallback method)
     */
    private List<AmazonOrder> fetchOrdersFromWeb() {
        // This would require implementing web scraping with Selenium or similar
        // For now, return empty list as this is complex and may violate ToS
        logger.info("Web scraping not implemented (may violate Amazon ToS)")
        return []
    }
    
    /**
     * Fetch orders via API alternatives (third-party services)
     */
    private List<AmazonOrder> fetchOrdersFromAPI() {
        // This could integrate with services like:
        // - Plaid (for bank transaction data)
        // - Yodlee
        // - Other financial data aggregators
        logger.info("API alternatives not implemented")
        return []
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