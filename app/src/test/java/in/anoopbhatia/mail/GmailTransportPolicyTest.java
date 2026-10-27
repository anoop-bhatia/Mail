package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailTransportPolicyTest {
    @Test
    public void gmailImapOauthAndAppPasswordCanUseGenericProtocolTransport() {
        assertFalse(GmailTransportPolicy.shouldBlockGenericProtocol(
                "imap.gmail.com", ServiceAuthenticator.AUTH_TYPE_OAUTH, "oauth-token"));
        assertFalse(GmailTransportPolicy.shouldBlockGenericProtocol(
                "imap.gmail.com", ServiceAuthenticator.AUTH_TYPE_PASSWORD, "app-password"));
        assertFalse(GmailTransportPolicy.shouldBlockGenericProtocol(
                "smtp.gmail.com", ServiceAuthenticator.AUTH_TYPE_OAUTH, "oauth-token"));
    }

    @Test
    public void nativeGmailApiCredentialsCannotUseGenericProtocolTransport() {
        assertTrue(GmailTransportPolicy.shouldBlockGenericProtocol(
                "imap.gmail.com", ServiceAuthenticator.AUTH_TYPE_GMAIL, "api-state"));
        assertFalse(GmailTransportPolicy.shouldBlockGenericProtocol(
                "imap.example.org", ServiceAuthenticator.AUTH_TYPE_GMAIL, "api-state"));
    }

    @Test
    public void onlyNativeGmailApiAccountsUseRestSyncAndRequireApiIdentityForLegacySend() {
        EntityAccount imap = account(ServiceAuthenticator.AUTH_TYPE_OAUTH, "imap.gmail.com", "oauth-token");
        EntityIdentity imapIdentity = identity(ServiceAuthenticator.AUTH_TYPE_OAUTH, "smtp.gmail.com", "oauth-token");
        assertFalse(GmailTransportPolicy.shouldUseNativeApiSynchronization(imap));
        assertFalse(GmailTransportPolicy.shouldBlockLegacySmtp(imap, imapIdentity));

        EntityAccount api = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com", "api-state");
        EntityIdentity apiIdentity = identity(ServiceAuthenticator.AUTH_TYPE_GMAIL, "smtp.gmail.com", "api-state");
        assertTrue(GmailTransportPolicy.shouldUseNativeApiSynchronization(api));
        assertTrue(GmailTransportPolicy.shouldBlockLegacySmtp(api, imapIdentity));
        assertFalse(GmailTransportPolicy.shouldBlockLegacySmtp(api, apiIdentity));
    }

    private static EntityAccount account(int authType, String host, String password) {
        EntityAccount account = new EntityAccount();
        account.auth_type = authType;
        account.host = host;
        account.password = password;
        account.provider = "gmail";
        return account;
    }

    private static EntityIdentity identity(int authType, String host, String password) {
        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = authType;
        identity.host = host;
        identity.password = password;
        identity.provider = "gmail";
        return identity;
    }
}
