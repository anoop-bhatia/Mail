package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GmailApiEndpointLabelTest {
    @Test
    public void nativeGmailApiAccountAndIdentityRowsShowOnlyGmailApi() {
        assertEquals("Gmail API", GmailApiEndpointLabel.format(true, "Gmail API",
                "imap.gmail.com", 993, "ssl"));
        assertEquals("Gmail API", GmailApiEndpointLabel.format(true, "Gmail API",
                "smtp.gmail.com", 465, "ssl"));
    }

    @Test
    public void genericAccountAndIdentityRowsKeepHostPortAndEncryptionText() {
        assertEquals("imap.example.com:993/ssl", GmailApiEndpointLabel.format(false,
                "Gmail API", "imap.example.com", 993, "ssl"));
        assertEquals("smtp.example.com:465/ssl", GmailApiEndpointLabel.format(false,
                "Gmail API", "smtp.example.com", 465, "ssl"));
    }
}
