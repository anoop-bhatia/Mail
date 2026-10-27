package com.sun.mail.imap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.mail.imap.protocol.FetchItem;

import java.lang.reflect.Field;
import java.util.Properties;

import javax.mail.FetchProfile;
import javax.mail.Session;

import org.junit.Test;

/** Regression tests for identity-based IMAP SIZE fetch capabilities. */
public class FetchProfileConditionTest {
    private final IMAPMessage message = new IMAPMessage(
            Session.getInstance(new Properties()));

    private static IMAPMessage.FetchProfileCondition condition(FetchProfile.Item... items) {
        FetchProfile profile = new FetchProfile();
        for (FetchProfile.Item item : items)
            profile.add(item);
        return new IMAPMessage.FetchProfileCondition(profile, new FetchItem[0]);
    }

    private void setSize(long value) throws Exception {
        Field size = IMAPMessage.class.getDeclaredField("size");
        size.setAccessible(true);
        size.setLong(message, value);
    }

    @Test
    public void canonicalSizeRequiresMissingSizeAndAcceptsCachedSize() throws Exception {
        setSize(-1);
        assertTrue(condition(FetchProfile.Item.SIZE).test(message));
        setSize(42);
        assertFalse(condition(FetchProfile.Item.SIZE).test(message));
    }

    @Test
    public void legacySizeRequiresMissingSizeAndAcceptsCachedSize() throws Exception {
        setSize(-1);
        assertTrue(condition(IMAPFolder.FetchProfileItem.SIZE).test(message));
        setSize(42);
        assertFalse(condition(IMAPFolder.FetchProfileItem.SIZE).test(message));
    }

    @Test
    public void canonicalAndLegacyTogetherRemainOneSizeCapability() throws Exception {
        setSize(-1);
        assertTrue(condition(FetchProfile.Item.SIZE,
                IMAPFolder.FetchProfileItem.SIZE).test(message));
        setSize(42);
        assertFalse(condition(FetchProfile.Item.SIZE,
                IMAPFolder.FetchProfileItem.SIZE).test(message));
    }

    @Test
    public void nonSizeItemsDoNotRequestSize() throws Exception {
        setSize(-1);
        assertFalse(condition(FetchProfile.Item.FLAGS).test(message));
    }

    @Test
    public void equalNamedCustomProviderTokenDoesNotRequestSize() throws Exception {
        setSize(-1);
        assertFalse(condition(new CustomFetchProfileItem("SIZE")).test(message));
    }

    private static final class CustomFetchProfileItem
            extends IMAPFolder.FetchProfileItem {
        private CustomFetchProfileItem(String name) {
            super(name);
        }
    }
}
