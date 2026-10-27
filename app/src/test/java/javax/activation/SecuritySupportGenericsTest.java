package javax.activation;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SecuritySupportGenericsTest {
    @Test
    public void resourceStreamAcceptsParameterizedClass() throws IOException {
        try (java.io.InputStream stream = SecuritySupport.getResourceAsStream(
                SecuritySupportGenericsTest.class, "SecuritySupportGenericsTest.class")) {
            assertNotNull(stream);
        }
    }

    @Test
    public void resourcesAreReturnedFromClassLoaderInProviderOrder() throws IOException {
        Path first = Files.createTempDirectory("activation-resource-one");
        Path second = Files.createTempDirectory("activation-resource-two");
        try {
            Path firstResource = first.resolve("activation-test-resource.txt");
            Path secondResource = second.resolve("activation-test-resource.txt");
            Files.write(firstResource, "first".getBytes(StandardCharsets.UTF_8));
            Files.write(secondResource, "second".getBytes(StandardCharsets.UTF_8));
            try (URLClassLoader loader = new URLClassLoader(new URL[] {
                    first.toUri().toURL(), second.toUri().toURL()}, null)) {
                URL[] resources = SecuritySupport.getResources(loader, "activation-test-resource.txt");
                assertEquals(2, resources.length);
                assertArrayEquals(new URL[] {
                        firstResource.toUri().toURL(), secondResource.toUri().toURL()}, resources);
            }
        } finally {
            Files.deleteIfExists(first.resolve("activation-test-resource.txt"));
            Files.deleteIfExists(second.resolve("activation-test-resource.txt"));
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }
}
