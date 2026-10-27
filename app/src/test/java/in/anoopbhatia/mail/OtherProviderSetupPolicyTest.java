package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OtherProviderSetupPolicyTest {
    @Test
    public void gmailAddressesChooseOauthImapNotNativeApi() {
        for (String email : new String[]{"person@gmail.com", "person@googlemail.com", " PERSON@GMAIL.COM "})
            assertEquals(email, "gmail", OtherProviderSetupPolicy.getOAuthProviderId(email));
    }

    @Test
    public void microsoftConsumerDomainsChooseTheSingleMicrosoftOauthProfile() {
        for (String domain : new String[]{"outlook.com", "hotmail.com", "live.com", "msn.com",
                "outlook.in", "hotmail.co.uk", "live.co.uk", "OUTLOOK.COM"})
            assertEquals(domain, "microsoft", OtherProviderSetupPolicy.getOAuthProviderId("person@" + domain));
    }

    @Test
    public void otherDomainsKeepNormalProtocolDiscovery() {
        for (String email : new String[]{"person@example.org", "person@company.com", "person@yahoo.com",
                "person@gmail.com.example.org", "person@notoutlook.com", "person@outlook.com.attacker.org"})
            assertNull(email, OtherProviderSetupPolicy.getOAuthProviderId(email));
    }

    @Test
    public void invalidAddressesDoNotSelectOauth() {
        for (String email : new String[]{null, "", "gmail.com", "@gmail.com", "person@", "person@@gmail.com"})
            assertNull(email, OtherProviderSetupPolicy.getOAuthProviderId(email));
    }
}
