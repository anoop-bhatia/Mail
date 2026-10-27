package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeRestIdentityPolicyTest {
    @Test
    public void restIdentityEmailIsReadOnlyOnlyWhenEditingAnExistingApiIdentity() {
        for (EntityIdentity identity : Arrays.asList(
                identity(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com", "gmail-api-state"),
                identity(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com", "zoho-api-state"),
                identity(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com", "graph-api-state"))) {
            identity.id = 23L;
            identity.email = "sender@example.com";
            assertTrue(NativeRestIdentityPolicy.shouldMakeEmailReadOnly(identity, true));
            assertEquals("sender@example.com",
                    NativeRestIdentityPolicy.getEmailForSave(identity, true, "changed@example.net"));
            assertFalse(NativeRestIdentityPolicy.shouldMakeEmailReadOnly(identity, false));
            assertEquals("alias@example.net",
                    NativeRestIdentityPolicy.getEmailForSave(identity, false, "alias@example.net"));
        }
    }

    @Test
    public void smtpValidationIsSkippedOnlyForNativeRestIdentityOrAccount() {
        EntityAccount gmailApi = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zohoApi = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graphApi = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");
        for (EntityAccount account : Arrays.asList(gmailApi, zohoApi, graphApi)) {
            assertFalse(NativeRestIdentityPolicy.shouldValidateSmtp(null, account));
            assertTrue(NativeRestIdentityPolicy.isNativeRestIdentity(null, account));
        }

        EntityAccount legacyGmailImap = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.gmail.com");
        EntityAccount legacyImap = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org");
        assertTrue(NativeRestIdentityPolicy.shouldValidateSmtp(null, legacyGmailImap));
        assertTrue(NativeRestIdentityPolicy.shouldValidateSmtp(null, legacyImap));
        assertFalse(NativeRestIdentityPolicy.isNativeRestIdentity(null, legacyGmailImap));
    }

    @Test
    public void oauthTokenIsNeverCopiedIntoIdentityPasswordField() {
        EntityIdentity restIdentity = identity(ServiceAuthenticator.AUTH_TYPE_GMAIL,
                "imap.gmail.com", "opaque-access-refresh-token");
        EntityAccount restAccount = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API,
                "outlook.office365.com");
        restAccount.password = "opaque-account-token";

        assertNull(NativeRestIdentityPolicy.getPasswordFieldValue(restIdentity, null));
        assertNull(NativeRestIdentityPolicy.getPasswordFieldValue(null, restAccount));

        EntityIdentity legacy = identity(ServiceAuthenticator.AUTH_TYPE_PASSWORD,
                "smtp.example.org", "legacy-password");
        assertEquals("legacy-password", NativeRestIdentityPolicy.getPasswordFieldValue(legacy, null));
    }

    @Test
    public void restEditorHidesOnlyTransportAndEnvelopeFields() {
        Set<Integer> transport = asSet(NativeRestIdentityPolicy.getNativeRestTransportViewIds());
        Set<Integer> advancedSmtp = asSet(NativeRestIdentityPolicy.getNativeRestSmtpAdvancedViewIds());

        assertTrue(transport.contains(R.id.cardServer));
        assertTrue(transport.contains(R.id.tvUser));
        assertTrue(transport.contains(R.id.etUser));
        assertTrue(transport.contains(R.id.tilPassword));
        assertTrue(transport.contains(R.id.cbLoginBeforeSend));
        assertTrue(transport.contains(R.id.cbUseIp));
        assertTrue(transport.contains(R.id.etEhlo));
        assertTrue(advancedSmtp.contains(R.id.tvEnvelopeFrom));
        assertTrue(advancedSmtp.contains(R.id.etEnvelopeFrom));
        assertTrue(advancedSmtp.contains(R.id.cbOctetMime));

        for (int retained : new int[]{
                R.id.tvName, R.id.etName, R.id.tvEmail, R.id.etEmail,
                R.id.tvDisplay, R.id.etDisplay, R.id.btnSignature,
                R.id.cbSynchronize, R.id.cbPrimary, R.id.cbSelf,
                R.id.cbSenderExtra, R.id.etReplyTo, R.id.etCc, R.id.etBcc,
                R.id.etInternal, R.id.btnUri, R.id.cbSignDefault, R.id.cbEncryptDefault,
                R.id.spReceiptType, R.id.spSensitivity, R.id.cbUnicode,
                R.id.etMaxSize}) {
            assertFalse("unexpectedly hidden REST identity setting " + retained,
                    transport.contains(retained) || advancedSmtp.contains(retained));
        }
    }

    private static EntityIdentity identity(int authType, String host, String password) {
        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = authType;
        identity.host = host;
        identity.password = password;
        return identity;
    }

    private static EntityAccount account(int authType, String host) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        account.host = host;
        return account;
    }

    private static Set<Integer> asSet(int[] ids) {
        Set<Integer> result = new HashSet<>();
        for (int id : ids)
            result.add(id);
        return result;
    }
}
