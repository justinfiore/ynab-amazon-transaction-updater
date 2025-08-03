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
    
    // Regex patterns for extracting order information from emails
    private static final Pattern ORDER_ID_PATTERN = ~/(?i)Order\s*#\s*([A-Z0-9-]+)/
    private static final Pattern ORDER_DATE_PATTERN = ~/(?i)Ordered on ([A-Za-z]+ \d{1,2}, \d{4})/
    private static final Pattern ITEM_PATTERN = ~/\*\s*([^\r\n]+?)\s*Quantity: \d+\s*([\d.]+)\s*USD/
    private static final Pattern TOTAL_PATTERN = Pattern.compile('(?i)(?:Total|Total\\s+Amount|Total.+refund):?\\s*\\$?\\s*([0-9]+\\.?[0-9]{0,2})\\s*(?:USD)?', Pattern.DOTALL)
    
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
            
            // Search for Amazon order emails from the last 30 days
            Calendar cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -30)
            Date fromDate = cal.getTime()
            
            // Search for messages first
            Message[] messages = inbox.search(
                new AndTerm(
                    new OrTerm(
                        new FromTerm(new InternetAddress("order-confirmation@amazon.com")),
                        new FromTerm(new InternetAddress("auto-confirm@amazon.com")),
                        new FromTerm(new InternetAddress("return@amazon.com"))
                    ),
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
                    AmazonOrder order = parseOrderFromEmail(message)
                    if (order) {
                        // Merge with existing order if same order ID
                        AmazonOrder existingOrder = orderMap.get(order.orderId)
                        if (existingOrder) {
                            if(existingOrder.items == null && existingItems.size() == 0 && order.items != null && order.items.size() > 0) {
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