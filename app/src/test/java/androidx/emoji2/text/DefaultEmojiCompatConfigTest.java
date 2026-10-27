package androidx.emoji2.text;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class DefaultEmojiCompatConfigTest {
    @Test
    public void legacyPolicyUsesOnlyOldestCertificateForRotatedSingleSigner() {
        String[] history = {"oldest", "intermediate", "current"};
        assertArrayEquals(new String[]{"oldest"},
                DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                        .getLegacySignatures(false, new String[]{"current"}, history));
        assertArrayEquals(new String[]{"oldest", "intermediate", "current"}, history);
    }

    @Test
    public void legacyPolicyKeepsAllCurrentMultiSignersWithoutReordering() {
        String[] current = {"second", "first"};
        assertSame(current,
                DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                        .getLegacySignatures(true, current, null));
        assertArrayEquals(new String[]{"second", "first"}, current);
    }

    @Test
    public void singleSignerWithoutRotationKeepsItsCertificate() {
        assertArrayEquals(new String[]{"current"},
                DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                        .getLegacySignatures(false, new String[]{"current"},
                                new String[]{"current"}));
    }

    @Test
    public void missingCertificateDataIsNotConvertedToAnEmptyTrustedSet() {
        assertNull(DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                .getLegacySignatures(false, null, null));
        assertNull(DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                .getLegacySignatures(true, null, null));
        assertArrayEquals(new String[0],
                DefaultEmojiCompatConfig.DefaultEmojiCompatConfigHelper_API28
                        .getLegacySignatures(false, null, new String[0]));
    }
}
