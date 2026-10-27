package com.sun.mail.smtp;

import com.sun.mail.util.MailLogger;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DigestMD5TokenizationTest {
    private static final Method TOKENIZE = tokenizeMethod();

    private static Method tokenizeMethod() {
        try {
            Method method = DigestMD5.class.getDeclaredMethod("tokenize", String.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static DigestMD5 digest() {
        return new DigestMD5(new MailLogger(DigestMD5TokenizationTest.class,
                "TEST", false, null));
    }

    private static String encoded(String decoded) {
        return "x:  " + Base64.getEncoder().encodeToString(
                decoded.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Object tokenize(String input) throws Exception {
        try {
            Object result = TOKENIZE.invoke(digest(), encoded(input));
            return result;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw exception;
        }
    }

    @Test
    public void duplicateKeysAndRealmsAreConcatenated() throws Exception {
        Map<String, String> expected = new HashMap<>();
        expected.put("realm", "one,two");
        expected.put("nonce", "first,second");
        assertEquals(expected, tokenize("realm=\"one\", nonce=\"first\", realm=\"two\", nonce=\"second\""));
    }

    @Test
    public void digitStartingUnquotedValuesAreValuesNotKeys() throws Exception {
        Map<String, String> expected = new HashMap<>();
        expected.put("nc", "00000001");
        expected.put("response", "0123456789abcdef");
        assertEquals(expected, tokenize("nc=00000001,response=0123456789abcdef"));
    }

    @Test
    public void quotedEscapesCommaAndEqualArePreserved() throws Exception {
        Map<String, String> expected = new HashMap<>();
        expected.put("value", "a,b=\"c\"");
        assertEquals(expected, tokenize("value=\"a,b=\\\"c\\\"\""));
    }

    @Test
    public void punctuationIsIgnoredWhilePendingKeyIsRetained() throws Exception {
        Map<String, String> expected = new HashMap<>();
        expected.put("nonce", "abc");
        assertEquals(expected, tokenize("nonce @ = ; abc"));
    }

    @Test
    public void orphanQuotedValueUsesNullKeyAndOrdinaryValuesDoNot() throws Exception {
        Map<String, String> expected = new HashMap<>();
        expected.put(null, "orphan");
        expected.put("key", "value");
        assertEquals(expected, tokenize("\"orphan\", key=\"value\""));
    }

    @Test
    public void emptyAndDanglingInputsAreHandled() throws Exception {
        assertEquals(new HashMap<String, String>(), tokenize(""));
        assertEquals(new HashMap<String, String>(), tokenize("dangling="));
        assertEquals(new HashMap<String, String>(), tokenize("dangling"));
    }

    @Test
    public void multilineBase64AndIso88591ValuesDecode() throws Exception {
        String value = "caf\u00e9";
        String decoded = "name=\"" + value + "\", second=\"line\"";
        String multiline = "x:  " + Base64.getMimeEncoder(8, new byte[]{'\n'})
                .encodeToString(decoded.getBytes(StandardCharsets.ISO_8859_1));
        try {
            Object result = TOKENIZE.invoke(digest(), multiline);
            Map<String, String> expected = new HashMap<>();
            expected.put("name", value);
            expected.put("second", "line");
            assertEquals(expected, result);
        } catch (InvocationTargetException exception) {
            throw (Exception) exception.getCause();
        }
    }

    @Test
    public void shortFramingIsEmptyButTruncatedBase64Fails() throws Exception {
        assertEquals(new HashMap<String, String>(), TOKENIZE.invoke(digest(), "bad"));
        try {
            TOKENIZE.invoke(digest(), "334 A");
            fail("truncated Base64 should fail");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            assertTrue(cause instanceof IOException);
        }
    }

    @Test
    public void authClientAndAuthServerAgreeForLegacyAndUtf8Credentials() throws Exception {
        assertAuthentication("Jörg", "päss", false);
        assertAuthentication("用户", "秘密", true);
    }

    private static void assertAuthentication(String user, String password, boolean utf8)
            throws Exception {
        String host = "mail.example.test";
        String realm = "example";
        String nonce = "fixed-server-nonce";
        String challenge = "realm=\"" + realm + "\",nonce=\"" + nonce + "\""
                + (utf8 ? ",charset=\"utf-8\"" : "");
        DigestMD5 client = digest();
        byte[] responseBytes = client.authClient(host, user, password, realm, encoded(challenge));
        String response = new String(Base64.getDecoder().decode(responseBytes),
                StandardCharsets.ISO_8859_1);
        String cnonce = field(response, "cnonce");
        String actualDigest = field(response, "response");
        byte[] credentials = (user + ":" + realm + ":" + password)
                .getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        MessageDigest a1 = MessageDigest.getInstance("MD5");
        a1.update(md5(credentials));
        a1.update((":" + nonce + ":" + cnonce).getBytes(StandardCharsets.ISO_8859_1));
        String ha1 = hex(a1.digest());
        String common = ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:";
        String expected = hex(md5((common + hex(md5(("AUTHENTICATE:smtp/" + host)
                .getBytes(StandardCharsets.ISO_8859_1)))).getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(expected, actualDigest);
        String rspauth = hex(md5((common + hex(md5((":" + "smtp/" + host)
                .getBytes(StandardCharsets.ISO_8859_1)))).getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(client.authServer(encoded("rspauth=" + rspauth)));
        assertFalse(client.authServer(encoded("rspauth=00000000000000000000000000000000")));
    }

    private static String field(String response, String name) {
        String prefix = name + "=";
        int start = response.indexOf(prefix) + prefix.length();
        if (response.charAt(start) == '"') {
            int end = response.indexOf('"', start + 1);
            return response.substring(start + 1, end);
        }
        int end = response.indexOf(',', start);
        return response.substring(start, end < 0 ? response.length() : end);
    }

    private static byte[] md5(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("MD5").digest(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
