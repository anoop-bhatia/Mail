package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EncryptionTogglePolicyTest {
    private static final Integer PGP_SIGN_AND_ENCRYPT = 1;
    private static final Integer SMIME_SIGN_AND_ENCRYPT = 3;
    private static final Integer PGP_SIGN_ONLY = 2;
    private static final Integer SMIME_SIGN_ONLY = 4;
    private static final Integer PGP_ENCRYPT_ONLY = 5;
    private static final Integer SMIME_ENCRYPT_ONLY = 6;

    @Test
    public void completedAutoEncryptDecisionWinsOverSignDefaultOnIdentitySelection() {
        assertEquals(PGP_SIGN_AND_ENCRYPT,
                EncryptionTogglePolicy.resolveComposeMode(
                        PGP_SIGN_AND_ENCRYPT, null, true,
                        false, false, true, false,
                        PGP_SIGN_AND_ENCRYPT, PGP_SIGN_ONLY));
        assertEquals(SMIME_SIGN_AND_ENCRYPT,
                EncryptionTogglePolicy.resolveComposeMode(
                        SMIME_SIGN_AND_ENCRYPT, null, true,
                        false, false, true, false,
                        SMIME_SIGN_AND_ENCRYPT, SMIME_SIGN_ONLY));
    }

    @Test
    public void defaultsStillApplyWhenAutoEncryptCannotSelectAMode() {
        assertEquals(SMIME_SIGN_AND_ENCRYPT,
                EncryptionTogglePolicy.resolveComposeMode(
                        null, null, true,
                        false, true, true, false,
                        SMIME_SIGN_AND_ENCRYPT, SMIME_SIGN_ONLY));
        assertEquals(PGP_SIGN_ONLY,
                EncryptionTogglePolicy.resolveComposeMode(
                        null, null, true,
                        false, false, true, false,
                        PGP_SIGN_AND_ENCRYPT, PGP_SIGN_ONLY));
        assertEquals(null,
                EncryptionTogglePolicy.resolveComposeMode(
                        null, null, true,
                        false, false, false, false,
                        PGP_SIGN_AND_ENCRYPT, PGP_SIGN_ONLY));
    }

    @Test
    public void autoDecryptIncludesEncryptOnlySignedAndInlineMessages() {
        for (Integer mode : new Integer[]{
                PGP_SIGN_AND_ENCRYPT, SMIME_SIGN_AND_ENCRYPT,
                PGP_ENCRYPT_ONLY, SMIME_ENCRYPT_ONLY})
            assertTrue("encrypted mode=" + mode,
                    EncryptionTogglePolicy.shouldAutoDecrypt(mode, false,
                            PGP_SIGN_AND_ENCRYPT, SMIME_SIGN_AND_ENCRYPT,
                            PGP_ENCRYPT_ONLY, SMIME_ENCRYPT_ONLY));

        assertTrue(EncryptionTogglePolicy.shouldAutoDecrypt(null, true,
                PGP_SIGN_AND_ENCRYPT, SMIME_SIGN_AND_ENCRYPT,
                PGP_ENCRYPT_ONLY, SMIME_ENCRYPT_ONLY));
        assertFalse(EncryptionTogglePolicy.shouldAutoDecrypt(PGP_SIGN_ONLY, false,
                PGP_SIGN_AND_ENCRYPT, SMIME_SIGN_AND_ENCRYPT,
                PGP_ENCRYPT_ONLY, SMIME_ENCRYPT_ONLY));
    }

    @Test
    public void autoVerifyRemainsLimitedToSignedOnlyMessages() {
        assertTrue(EncryptionTogglePolicy.shouldAutoVerify(PGP_SIGN_ONLY,
                PGP_SIGN_ONLY, SMIME_SIGN_ONLY));
        assertTrue(EncryptionTogglePolicy.shouldAutoVerify(SMIME_SIGN_ONLY,
                PGP_SIGN_ONLY, SMIME_SIGN_ONLY));
        assertFalse(EncryptionTogglePolicy.shouldAutoVerify(PGP_SIGN_AND_ENCRYPT,
                PGP_SIGN_ONLY, SMIME_SIGN_ONLY));
    }

    @Test
    public void smimeEncryptOnlyRoutesToSmimeDecryptHandler() {
        assertTrue(EncryptionTogglePolicy.isSmimeEncryptedMode(SMIME_SIGN_AND_ENCRYPT,
                SMIME_SIGN_AND_ENCRYPT, SMIME_ENCRYPT_ONLY));
        assertTrue(EncryptionTogglePolicy.isSmimeEncryptedMode(SMIME_ENCRYPT_ONLY,
                SMIME_SIGN_AND_ENCRYPT, SMIME_ENCRYPT_ONLY));
        assertFalse(EncryptionTogglePolicy.isSmimeEncryptedMode(PGP_ENCRYPT_ONLY,
                SMIME_SIGN_AND_ENCRYPT, SMIME_ENCRYPT_ONLY));
    }

    @Test
    public void automaticAttemptIsClearedOnlyWhenItDidNotComplete() {
        assertTrue(EncryptionTogglePolicy.shouldClearAutoAttempt(true, false));
        assertFalse(EncryptionTogglePolicy.shouldClearAutoAttempt(true, true));
        assertFalse(EncryptionTogglePolicy.shouldClearAutoAttempt(false, false));
    }
}
