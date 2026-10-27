package in.anoopbhatia.mail;

import android.content.Context;
import android.net.DnsResolver;
import android.os.Looper;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DnsHelperResolverRegressionTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/DnsHelper.java"));
    }

    @Test
    public void resolverConstructorInTheCompileSdkIsNotDeprecated() throws Exception {
        assertFalse(DnsResolver.class.getConstructor(Context.class, Looper.class)
                .isAnnotationPresent(Deprecated.class));
    }

    @Test
    public void platformResolverRequiresApi37OrDnsExtension22() throws Exception {
        String source = source().replaceAll("\\s+", " ");
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= 37 || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 22)) return new AndroidDataSource(new DnsResolver(context, Looper.getMainLooper()));"));
        assertTrue(source.contains("@RequiresApi(Build.VERSION_CODES.Q) private static class AndroidDataSource"));
    }

    @Test
    public void olderPlatformsHaveAnExplicitMiniDnsFallbackWithoutDeprecatedLookup() throws Exception {
        String source = source();
        assertTrue(source.contains("return new NetworkDataSource();"));
        assertFalse(source.contains("DnsResolver.getInstance()"));
        assertFalse(source.contains("getSystemService(DnsResolver.class)"));
        assertFalse(source.contains("SuppressWarnings(\"deprecation\")"));
    }

    @Test
    public void ordinaryAndDaneLookupsBothUseTheSafeFactory() throws Exception {
        String source = source();
        String normalized = source().replaceAll("\\s+", " ");
        String statement = "client.setDataSource(createDnsDataSource(context));";
        int first = normalized.indexOf(statement);
        assertTrue(first >= 0);
        assertTrue(normalized.indexOf(statement, first + statement.length()) > first);
        assertTrue(normalized.contains("} else if (!dns_custom) " + statement));
    }
}
