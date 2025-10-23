package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import com.ynab.amazon.model.AmazonOrder
import spock.lang.Specification
import spock.lang.Unroll
import java.text.SimpleDateFormat
import java.util.regex.Pattern

/**
 * Test class for Subscribe and Save functionality in AmazonOrderFetcher
 */
class AmazonOrderFetcher_SubscribeAndSave_UT extends Specification {
    
    Configuration mockConfig
    AmazonOrderFetcher orderFetcher
    
    def setup() {
        mockConfig = Mock(Configuration)
        orderFetcher = new AmazonOrderFetcher(mockConfig)
    }
    
    def "should extract text/plain content from real Subscribe and Save emails"() {
        given: "real Subscribe and Save email files"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        def augEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-Aug 11.eml")
        
        when: "extracting text/plain content"
        String julyContent = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        String augContent = EmailTestHelper.extractTextPlainContent(augEmlFile)
        
        then: "should contain Subscribe and Save indicators"
        julyEmlFile.exists()
        augEmlFile.exists()
        
        // Check for key Subscribe and Save email patterns
        julyContent.contains("Amazon Subscribe & Save")
        julyContent.contains("no-reply@amazon.com")
        julyContent.contains("review your upcoming delivery")
        
        augContent.contains("Amazon Subscribe & Save")
        augContent.contains("no-reply@amazon.com") 
        augContent.contains("review your upcoming delivery")
    }
    
    def "should extract delivery dates from real Subscribe and Save emails"() {
        given: "real Subscribe and Save email files"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        def augEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-Aug 11.eml")
        
        when: "extracting text/plain content and parsing delivery dates"
        String julyContent = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        String augContent = EmailTestHelper.extractTextPlainContent(augEmlFile)
        
        def pattern = ~/(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \d{1,2})/
        def julyMatcher = pattern.matcher(julyContent)
        def augMatcher = pattern.matcher(augContent)
        
        then: "should extract the delivery dates"
        julyMatcher.find()
        julyMatcher.group(1) == "Friday, Jul 11"
        
        augMatcher.find()
        augMatcher.group(1) == "Monday, Aug 11"
    }
    
    def "should extract prices from real Subscribe and Save emails"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        
        when: "extracting text/plain content and parsing prices"
        String content = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        
        def pricePattern = ~/\*\$([0-9]+\.?[0-9]{0,2})\*/
        def matcher = pricePattern.matcher(content)
        List<BigDecimal> prices = []
        
        while (matcher.find()) {
            prices.add(new BigDecimal(matcher.group(1)))
        }
        
        then: "should extract multiple prices"
        prices.size() > 0
        // Check for some known prices from the July email
        prices.contains(new BigDecimal("25.70")) // Progresso soup new price
        prices.contains(new BigDecimal("20.95")) // Progresso soup last price
        prices.contains(new BigDecimal("16.13")) // Olive oil new price
        prices.contains(new BigDecimal("42.49")) // Clearblue new price
    }
    
    def "should extract email metadata from real Subscribe and Save emails"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        
        when: "extracting email metadata and content"
        String subject = EmailTestHelper.extractSubject(julyEmlFile)
        String content = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        Date sentDate = EmailTestHelper.extractSentDate(julyEmlFile)
        
        then: "should extract correct metadata"
        subject.contains("review your upcoming delivery")
        // The .eml files are forwarded, so check content instead of From header
        content.contains("no-reply@amazon.com")
        content.contains("Amazon Subscribe & Save")
        sentDate != null
    }
    
    @Unroll
    def "should extract delivery dates from all real Subscribe and Save email files: #fileName"() {
        given: "real Subscribe and Save email file"
        def emlFile = new File("src/test/resources/${fileName}")
        
        when: "extracting text/plain content and parsing delivery date"
        String content = EmailTestHelper.extractTextPlainContent(emlFile)
        def pattern = ~/(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \d{1,2})/
        def matcher = pattern.matcher(content)
        
        then: "should extract the delivery date"
        matcher.find()
        matcher.group(1) == expectedDate
        
        where:
        fileName                                                      | expectedDate
        "Price changes_ review your upcoming delivery-July 11.eml"   | "Friday, Jul 11"
        "Price changes_ review your upcoming delivery-Aug 11.eml"    | "Monday, Aug 11"
        "Price changes_ review your upcoming delivery-Sept 30.eml"   | "Thursday, Sep 11"
        "Price changes_ review your upcoming delivery-Oct 30.eml"    | "Saturday, Oct 11"
    }
    
    def "should parse price patterns from real Subscribe and Save email content"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        
        when: "extracting text/plain content and parsing prices"
        String emailContent = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        def pricePattern = ~/\*\$([0-9]+\.?[0-9]{0,2})\*/
        def matcher = pricePattern.matcher(emailContent)
        List<BigDecimal> prices = []
        
        while (matcher.find()) {
            prices.add(new BigDecimal(matcher.group(1)))
        }
        
        then: "should extract all prices from real email"
        prices.size() > 10 // July email has many items
        // Verify some known prices from the actual email
        prices.contains(new BigDecimal("20.95")) // Progresso last price
        prices.contains(new BigDecimal("25.70")) // Progresso new price
        prices.contains(new BigDecimal("19.53")) // Olive oil last price
        prices.contains(new BigDecimal("16.13")) // Olive oil new price
        prices.contains(new BigDecimal("42.49")) // Clearblue new price
        prices.contains(new BigDecimal("30.01")) // Filtrete new price
    }
    
    def "should identify Subscribe and Save email patterns"() {
        given: "various email subjects and senders"
        def testCases = [
            [from: "no-reply@amazon.com", subject: "Price changes: review your upcoming delivery", expected: true],
            [from: "no-reply@amazon.com", subject: "Your subscription delivery", expected: true],
            [from: "order-confirmation@amazon.com", subject: "Your order has shipped", expected: false],
            [from: "no-reply@amazon.com", subject: "Regular order confirmation", expected: false]
        ]
        
        when: "checking each email pattern"
        def results = testCases.collect { testCase ->
            boolean isSubscription = testCase.from.contains("no-reply@amazon.com") && 
                                   (testCase.subject.toLowerCase().contains("review your upcoming delivery") ||
                                    testCase.subject.toLowerCase().contains("subscribe") ||
                                    testCase.subject.toLowerCase().contains("subscription"))
            return isSubscription
        }
        
        then: "should correctly identify Subscribe and Save emails"
        results[0] == true  // no-reply + review delivery
        results[1] == true  // no-reply + subscription
        results[2] == false // wrong sender
        results[3] == false // no-reply but wrong subject
    }
    
    def "should generate subscription order ID from email date and subject"() {
        given: "email date and subject"
        def emailDate = new Date(2024 - 1900, 6, 11) // July 11, 2024
        def subject = "Price changes: review your upcoming delivery"
        def sdf = new SimpleDateFormat("yyyy-MM-dd")
        
        when: "generating order ID"
        String orderId = "SUB-" + sdf.format(emailDate).replace("-", "") + "-" + 
                        subject.replaceAll(/[^A-Za-z0-9]/, "").take(8)
        
        then: "should create a valid subscription order ID"
        orderId.startsWith("SUB-")
        orderId.contains("20240711")
        orderId.length() > 12
    }
    
    @Unroll
    def "should parse delivery date with year: #inputDate"() {
        given: "a delivery date string without year"
        String dateStr = inputDate
        
        when: "parsing with current year"
        String currentYear = String.valueOf(Calendar.getInstance().get(Calendar.YEAR))
        String fullDateStr = dateStr + ", " + currentYear
        
        SimpleDateFormat emailDateFormat = new SimpleDateFormat("EEEE, MMM d, yyyy")
        Date parsedDate = emailDateFormat.parse(fullDateStr)
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd")
        String result = outputFormat.format(parsedDate)
        
        then: "should parse correctly with current year"
        result.startsWith(currentYear)
        result.contains(expectedMonth)
        result.endsWith(expectedDay)
        
        where:
        inputDate           | expectedMonth | expectedDay
        "Friday, Jul 11"    | "-07-"        | "-11"
        "Monday, Aug 11"    | "-08-"        | "-11"
        "Tuesday, Sep 30"   | "-09-"        | "-30"
        "Wednesday, Oct 30" | "-10-"        | "-30"
    }
    
    def "should parse complete Subscribe and Save email content from real file"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        
        when: "extracting and parsing the real email content"
        String emailContent = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        
        // Extract delivery date
        def deliveryMatcher = Pattern.compile('(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \\d{1,2})').matcher(emailContent)
        String deliveryDate = null
        if (deliveryMatcher.find()) {
            deliveryDate = deliveryMatcher.group(1)
        }
        
        // Extract prices
        def priceMatcher = Pattern.compile('\\*\\$([0-9]+\\.?[0-9]{0,2})\\*').matcher(emailContent)
        List<BigDecimal> prices = []
        while (priceMatcher.find()) {
            prices.add(new BigDecimal(priceMatcher.group(1)))
        }
        
        // Extract item count
        def itemCountMatcher = Pattern.compile('(\\d+) items in this delivery').matcher(emailContent)
        Integer itemCount = null
        if (itemCountMatcher.find()) {
            itemCount = Integer.parseInt(itemCountMatcher.group(1))
        }
        
        then: "should extract all key information from real email"
        deliveryDate == "Friday, Jul 11"
        emailContent.contains("no-reply@amazon.com")
        emailContent.contains("Amazon Subscribe & Save")
        emailContent.contains("review your upcoming delivery")
        emailContent.contains("Hi Jamie Fiore")
        itemCount == 18
        prices.size() > 0
        // Verify specific products mentioned in the email
        emailContent.contains("Progresso Light Chicken Noodle Soup")
        emailContent.contains("Pompeian Smooth Extra Virgin Olive Oil")
        emailContent.contains("Clearblue Fertility Monitor Test Sticks")
    }
    
    def "should validate Subscribe and Save regex patterns against real email content"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        String realContent = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        
        when: "testing regex patterns on real content"
        def deliveryPattern = ~/(?i)Arriving by ([A-Za-z]+, [A-Za-z]+ \d{1,2})/
        def pricePattern = ~/\*\$([0-9]+\.?[0-9]{0,2})\*/
        
        def deliveryMatcher = deliveryPattern.matcher(realContent)
        def priceMatcher = pricePattern.matcher(realContent)
        
        List<String> prices = []
        while (priceMatcher.find()) {
            prices.add(priceMatcher.group(1))
        }
        
        then: "patterns should work correctly on real email"
        deliveryMatcher.find()
        deliveryMatcher.group(1) == "Friday, Jul 11"
        prices.size() > 10 // Real email has many prices
        prices.contains("20.95") // Progresso last price
        prices.contains("25.70") // Progresso new price
    }
    
    def "should extract all product names from real Subscribe and Save email"() {
        given: "real Subscribe and Save email file"
        def julyEmlFile = new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml")
        
        when: "extracting text content"
        String content = EmailTestHelper.extractTextPlainContent(julyEmlFile)
        
        then: "should contain all expected product names from real email"
        // Products mentioned in the July 11 email
        content.contains("Progresso Light Chicken Noodle Soup")
        content.contains("Pompeian Smooth Extra Virgin Olive Oil")
        content.contains("Clearblue Fertility Monitor Test Sticks")
        content.contains("Filtrete 14x25x1 AC Furnace Air Filter")
        content.contains("Quilted Northern Ultra Plush Toilet Paper")
        content.contains("NATURELO Burpless Omega 3 Fish Oil")
        content.contains("Vitafusion Max Strength Melatonin")
        content.contains("Goldfish Cheddar Cheese Crackers")
        content.contains("Brushmo Plaque Control Replacement Toothbrush Heads")
        content.contains("BIOptimizers Magnesium Breakthrough")
    }
    
    def "should verify email structure matches expected Subscribe and Save format"() {
        given: "all real Subscribe and Save email files"
        def emailFiles = [
            new File("src/test/resources/Price changes_ review your upcoming delivery-July 11.eml"),
            new File("src/test/resources/Price changes_ review your upcoming delivery-Aug 11.eml"),
            new File("src/test/resources/Price changes_ review your upcoming delivery-Sept 30.eml"),
            new File("src/test/resources/Price changes_ review your upcoming delivery-Oct 30.eml")
        ]
        
        when: "extracting content from all emails"
        def allContents = emailFiles.collect { EmailTestHelper.extractTextPlainContent(it) }
        
        then: "all emails should have consistent Subscribe and Save structure"
        allContents.every { content ->
            content.contains("Amazon Subscribe & Save") &&
            content.contains("no-reply@amazon.com") &&
            content.contains("review your upcoming delivery") &&
            content.contains("Arriving by") &&
            content.contains("items in this delivery")
        }
    }
}