package com.ynab.amazon.service

import javax.mail.*
import javax.mail.internet.MimeMessage
import java.util.Properties

/**
 * Helper class for extracting and working with email content in tests
 */
class EmailTestHelper {
    
    /**
     * Extract the text/plain content from an .eml file
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
     * Get the subject from an .eml file
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
     * Get the from address from an .eml file
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
     * Get the sent date from an .eml file
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
