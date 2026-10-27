package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the asynchronous replacement of the deprecated ConnectivityManager.getAllNetworks().
 *
 * The connectivity classifiers stay synchronous, so they read a snapshot that a process wide
 * NetworkCallback maintains. This test only checks the wiring: the deprecated call must not
 * come back, and the classifiers must read the callback snapshot instead of the framework.
 */
public class NetworkEnumerationCompatibilityTest {
    @Test
    public void enumerationUsesTheCallbackSnapshotOnly() throws Exception {
        String source = source("ConnectionHelper");
        assertFalse("getAllNetworks() is deprecated and must not be called",
                source.contains("cm.getAllNetworks("));
        assertTrue("a request without capabilities matches every network, VPN included",
                source.contains("new NetworkRequest.Builder().build(), networksCallback"));
        assertTrue("capabilities must come from the callback",
                source.contains("caps = network.getValue();"));
        assertTrue("the VPN check reads the same snapshot",
                source.contains("for (NetworkCapabilities caps : getNetworks(cm).values())"));
    }

    @Test
    public void trackingStartsWithTheProcessBeforeAnyClassification() throws Exception {
        assertTrue(source("ApplicationEx").contains("ConnectionHelper.startNetworkTracking(this);"));
        assertTrue(source("ConnectionHelper").contains("static void startNetworkTracking(Context context)"));
        assertTrue(source("ConnectionHelper").contains("static Map<Network, NetworkCapabilities> getNetworks(ConnectivityManager cm)"));
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
