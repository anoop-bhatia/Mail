package in.anoopbhatia.mail;

import com.sun.mail.util.LineInputStream;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeprecationCompatibilityTest {
    @Test
    public void fingerprintKeepsCurrentSignerOnApi28AndCompatAccessOnOlderAndroid() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/Helper.java"));
        String fingerprint = source.substring(source.indexOf("static String getFingerprint(Context context, String hash)"),
                source.indexOf("static boolean canAuthenticate(Context context)"));
        assertTrue(fingerprint.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"));
        assertTrue(fingerprint.contains("PackageManager.GET_SIGNING_CERTIFICATES"));
        assertTrue(fingerprint.contains("info.signingInfo.getApkContentsSigners()[0]"));
        assertTrue(fingerprint.contains("PackageInfoCompat.getSignatures(pm, pkg).get(0)"));
        assertTrue(fingerprint.contains("MessageDigest.getInstance(hash)"));
        assertTrue(fingerprint.contains("catch (Throwable ex)"));
        assertFalse(fingerprint.contains("GET_SIGNATURES"));
        assertFalse(fingerprint.contains("SuppressWarnings"));
    }

    @Test
    public void typedFallbacksRetainLegacyCastsAndApi33FrameworkReaders() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/Helper.java"));
        String accessors = source.substring(source.indexOf("static <T extends Parcelable> T getParcelable("),
                source.indexOf("static boolean hasPhotoPicker()"));
        assertTrue(accessors.contains("type.cast(BundleCompat.getParcelable(bundle, key, Parcelable.class))"));
        assertTrue(accessors.contains("type.cast(IntentCompat.getParcelableExtra(intent, key, Parcelable.class))"));
        assertTrue(accessors.contains("type.cast(BundleCompat.getSerializable(bundle, key, java.io.Serializable.class))"));
        assertTrue(accessors.contains("type.cast(IntentCompat.getSerializableExtra(intent, key, java.io.Serializable.class))"));
        assertTrue(accessors.contains("BundleCompat.getParcelableArrayList(bundle, key, type)"));
        assertTrue(accessors.contains("IntentCompat.getParcelableArrayListExtra(intent, key, type)"));
        assertTrue(accessors.contains("return bundle.getParcelable(key, type);"));
        assertTrue(accessors.contains("return intent.getSerializableExtra(key, type);"));
        assertFalse(accessors.contains("SuppressWarnings(\"deprecation\")"));
        assertFalse(accessors.contains("bundle.getParcelable(key)"));
        assertFalse(accessors.contains("intent.getParcelableExtra(key)"));
        assertFalse(accessors.contains("bundle.getSerializable(key)"));
        assertFalse(accessors.contains("intent.getSerializableExtra(key)"));
    }

    @Test
    public void savedSelectionUsesTypedCompatibilityReaderWithoutChangingWireKeys() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/androidx/recyclerview/selection/StorageStrategy.java"));
        assertTrue(source.contains("BundleCompat.getParcelableArrayList("));
        assertTrue(source.contains("state, SELECTION_ENTRIES, mParcelableType)"));
        assertTrue(source.contains("mParcelableType = type;"));
        assertTrue(source.contains("androidx.recyclerview.selection.entries"));
        assertTrue(source.contains("androidx.recyclerview.selection.type"));
        assertTrue(source.contains("bundle.putParcelableArrayList(SELECTION_ENTRIES, value)"));
        assertTrue(source.contains("keyType.equals(getKeyTypeName())"));
        assertFalse(source.contains("state.getParcelableArrayList("));
        assertFalse(source.contains("SuppressWarnings(\"deprecation\")"));
    }

    @Test
    public void legacyMailLineReaderPreservesEightBitOctets() throws IOException {
        byte[] octets = new byte[258];
        for (int i = 0; i < 256; i++)
            octets[i] = (byte) i;
        // Exclude only line terminators from a single protocol line.
        octets[10] = 'x';
        octets[13] = 'y';
        octets[256] = '\r';
        octets[257] = '\n';
        try (LineInputStream input = new LineInputStream(new ByteArrayInputStream(octets))) {
            assertEquals(new String(octets, 0, 256, StandardCharsets.ISO_8859_1), input.readLine());
            assertNull(input.readLine());
        }
    }

    @Test
    public void mailLineTerminatorsAndUtf8RemainUnchanged() throws IOException {
        byte[] bytes = "one\r\ntwo\nthree\r\r\nfour\rfive".getBytes(StandardCharsets.US_ASCII);
        assertLines(new ByteArrayInputStream(bytes));
        // Also exercise the pushback path used by non-markable network streams.
        assertLines(new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public boolean markSupported() {
                return false;
            }
        });
        try (LineInputStream input = new LineInputStream(new ByteArrayInputStream(
                "हिन्दी 日本語\r\n".getBytes(StandardCharsets.UTF_8)), true)) {
            assertEquals("हिन्दी 日本語", input.readLine());
            assertNull(input.readLine());
        }
    }

    private static void assertLines(java.io.InputStream stream) throws IOException {
        try (LineInputStream input = new LineInputStream(stream)) {
            for (String line : new String[]{"one", "two", "three", "four", "five"})
                assertEquals(line, input.readLine());
            assertNull(input.readLine());
        }
    }
}
