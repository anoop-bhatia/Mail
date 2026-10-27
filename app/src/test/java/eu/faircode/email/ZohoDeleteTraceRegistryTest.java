package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ZohoDeleteTraceRegistryTest {
    @Test
    public void sameFolderReappearanceReturnsOnlyTraceAndConsumesEntry() {
        String trace = ApiMutationLog.newTraceId();
        ZohoDeleteTraceRegistry.record(17L, "101", "98765", trace);

        assertNull(ZohoDeleteTraceRegistry.take(17L, "202", "98765"));
        assertEquals(trace, ZohoDeleteTraceRegistry.take(17L, "101", "98765"));
        assertNull(ZohoDeleteTraceRegistry.take(17L, "101", "98765"));
    }

    @Test
    public void invalidProviderIdsAndTraceAreNotRecorded() {
        ZohoDeleteTraceRegistry.record(17L, "opaque-folder", "98765", ApiMutationLog.newTraceId());
        ZohoDeleteTraceRegistry.record(17L, "101", "98765", "not-a-trace");

        assertNull(ZohoDeleteTraceRegistry.take(17L, "101", "98765"));
    }
}
