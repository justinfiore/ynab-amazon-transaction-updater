package com.ynab.amazon.service

import javax.mail.*
import javax.mail.internet.MimeMessage
import java.util.Properties

/**
 * Helper class for loading and working with refund email files in tests
 */
class RefundEmailTestHelper {
    
    /**
     * Load a refund .eml file from test resources
     * @param fileName The name of the .eml file (e.g., "Your refund for Ekouaer 2 Pack Womens Pajama.....eml")
     * @return File object pointing to the .eml file
     */
    static File loadRefundEmailFile(String fileName) {
        File emlFile = new File("src/test/resources/${fileName}")
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Refund email file not found: ${emlFile.absolutePath}")
        }
        return emlFile
    }
    
    /**
     * Extract the text/plain content from a refund .eml file
     * @param emlFile The .eml file to extract content from
     * @return The text/plain content as a String
     */
    static String extractTextPlainContent(File emlFile) {
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Email file not found: ${emlFile.absolutePath}")
        }
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        emlFile.withInputStream { inputStream ->
            MimeMessage message = new MimeMessage(session, inputStream)
            return extractTextFromMessage(message)
        }
    }
    
    /**
     * Create a Message object from a refund .eml file for testing
     * @param emlFile The .eml file to create a Message from
     * @return Message object that can be used in tests
     */
    static Message createMessageFromFile(File emlFile) {
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Email file not found: ${emlFile.absolutePath}")
        }
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        emlFile.withInputStream { inputStream ->
            return new MimeMessage(session, inputStream)
        }
    }
    
    /**
     * Extract text content from a MimeMessage
     */
    private static String extractTextFromMessage(Message message) {
        try {
            Object content = message.getContent()
            
            if (content instanceof String) {
                return content
            } else if (content instanceof Multipart) {
                return extractTextFromMultipart((Multipart) content)
            }
        } catch (Exception e) {
            throw new RuntimeException("Error extracting text from message", e)
        }
        
        return ""
    }
    
    /**
     * Extract text/plain content from a Multipart message
     */
    private static String extractTextFromMultipart(Multipart multipart) {
        StringBuilder textContent = new StringBuilder()
        
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i)
            
            if (bodyPart.isMimeType("text/plain")) {
                textContent.append(bodyPart.getContent())
            } else if (bodyPart.getContent() instanceof Multipart) {
                textContent.append(extractTextFromMultipart((Multipart) bodyPart.getContent()))
            }
        }
        
        return textContent.toString()
    }
    
    /**
     * Get all refund email file names from test resources
     * @return List of refund email file names
     */
    static List<String> getAllRefundEmailFileNames() {
        return [
            "Your refund for Ekouaer 2 Pack Womens Pajama.....eml",
            "Your refund for Rubies Women's Wizard Of Oz.....eml",
            "Your refund for SAMPEEL Women's V Neck Color.....eml",
            "Your refund for WIHOLL Long Sleeve Shirts for.....eml"
        ]
    }
    
    /**
     * Load all refund email files from test resources
     * @return List of File objects for all refund emails
     */
    static List<File> loadAllRefundEmailFiles() {
        return getAllRefundEmailFileNames().collect { fileName ->
            loadRefundEmailFile(fileName)
        }
    }
    
    /**
     * Create Message objects from all refund email files
     * @return List of Message objects for all refund emails
     */
    static List<Message> createMessagesFromAllRefundFiles() {
        return loadAllRefundEmailFiles().collect { emlFile ->
            createMessageFromFile(emlFile)
        }
    }
    
    /**
     * Extract the subject from a refund .eml file
     * @param emlFile The .eml file to extract subject from
     * @return The email subject
     */
    static String extractSubject(File emlFile) {
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Email file not found: ${emlFile.absolutePath}")
        }
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        emlFile.withInputStream { inputStream ->
            MimeMessage message = new MimeMessage(session, inputStream)
            return message.getSubject()
        }
    }
    
    /**
     * Extract the from address from a refund .eml file
     * @param emlFile The .eml file to extract from address from
     * @return The from address as a String
     */
    static String extractFromAddress(File emlFile) {
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Email file not found: ${emlFile.absolutePath}")
        }
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        emlFile.withInputStream { inputStream ->
            MimeMessage message = new MimeMessage(session, inputStream)
            return message.getFrom()[0].toString()
        }
    }
    
    /**
     * Extract the sent date from a refund .eml file
     * @param emlFile The .eml file to extract sent date from
     * @return The sent date
     */
    static Date extractSentDate(File emlFile) {
        if (!emlFile.exists()) {
            throw new FileNotFoundException("Email file not found: ${emlFile.absolutePath}")
        }
        
        Properties props = new Properties()
        Session session = Session.getDefaultInstance(props, null)
        
        emlFile.withInputStream { inputStream ->
            MimeMessage message = new MimeMessage(session, inputStream)
            return message.getSentDate()
        }
    }
}
