package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PagingBoundaryPolicyTest {
    @Test
    public void emptyGenerationLoadsOnceUntilInvalidation() {
        PagingBoundaryPolicy policy = new PagingBoundaryPolicy();
        assertTrue(policy.shouldDispatch(0, 0, null, 10));
        assertFalse(policy.shouldDispatch(0, 0, null, 10));
        policy.reset();
        assertTrue(policy.shouldDispatch(0, 0, null, 10));
    }

    @Test
    public void snapshotsDoNotLoadOlderUntilUserAccessesTheEnd() {
        PagingBoundaryPolicy policy = new PagingBoundaryPolicy();
        assertFalse(policy.shouldDispatch(100, 0, 100L, 10));
        policy.access(50);
        assertFalse(policy.shouldDispatch(100, 0, 100L, 10));
        policy.access(89);
        assertTrue(policy.shouldDispatch(100, 0, 100L, 10));
        policy.access(99);
        assertFalse(policy.shouldDispatch(100, 0, 100L, 10));
    }

    @Test
    public void localPlaceholdersNeverTriggerRemoteBoundary() {
        PagingBoundaryPolicy policy = new PagingBoundaryPolicy();
        policy.access(99);
        assertFalse(policy.shouldDispatch(100, 50, 50L, 10));
        assertTrue(policy.shouldDispatch(100, 0, 100L, 10));
    }

    @Test
    public void newEndAndNewGenerationCanTriggerButOrdinaryRebindCannot() {
        PagingBoundaryPolicy policy = new PagingBoundaryPolicy();
        policy.access(99);
        assertTrue(policy.shouldDispatch(100, 0, 100L, 10));
        assertFalse(policy.shouldDispatch(100, 0, 100L, 10));
        assertTrue(policy.shouldDispatch(110, 0, 110L, 10));
        policy.reset();
        assertFalse(policy.shouldDispatch(110, 0, 110L, 10));
        policy.access(109);
        assertTrue(policy.shouldDispatch(110, 0, 110L, 10));
    }
}
