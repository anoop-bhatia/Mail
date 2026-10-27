package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards for retiring cached native REST messages that the provider reports as gone. */
public class NativeAbsentMessageTest {
    @Test
    public void onlyAnExplicitProviderAbsenceSignalRetiresACachedMessage() {
        assertTrue(NativeAbsentMessage.isAbsent(new GmailApi.ApiException(404, "missing")));
        assertTrue(NativeAbsentMessage.isAbsent(new GmailApi.ApiException(410, "gone")));
        assertFalse(NativeAbsentMessage.isAbsent(new GmailApi.ApiException(429, "throttled")));
        assertFalse(NativeAbsentMessage.isAbsent(new GmailApi.ApiException(401, "expired token")));
        assertFalse(NativeAbsentMessage.isAbsent(new GmailApi.ApiException(500, "backend error")));

        assertTrue(NativeAbsentMessage.isAbsent(new GraphMailApi.ApiException(404, -1, "ErrorItemNotFound")));
        assertTrue(NativeAbsentMessage.isAbsent(new GraphMailApi.ApiException(410, -1, null)));
        assertFalse(NativeAbsentMessage.isAbsent(new GraphMailApi.ApiException(503, 5_000L, null)));
        assertFalse(NativeAbsentMessage.isAbsent(new GraphMailApi.ApiException(403, -1, "ErrorAccessDenied")));

        assertTrue(NativeAbsentMessage.isAbsent(new ZohoApi.ApiException(404, -1)));
        // Zoho answers a moved or deleted message with a documented 400 "invalid input"; a bare
        // 400 (no documented description) is a rejected request and must keep the cached row.
        assertFalse(NativeAbsentMessage.isAbsent(new ZohoApi.ApiException(400, -1)));
        assertFalse(NativeAbsentMessage.isAbsent(new ZohoApi.ApiException(429, 1_000L)));

        // Transport, parse and unrelated failures are never absence.
        assertFalse(NativeAbsentMessage.isAbsent(new IOException("connection reset")));
        assertFalse(NativeAbsentMessage.isAbsent(new org.json.JSONException("truncated body")));
        assertFalse(NativeAbsentMessage.isAbsent(null));
    }

    @Test
    public void aWrappedProviderAbsenceStillRetiresTheMessage() {
        assertTrue(NativeAbsentMessage.isAbsent(new IOException("body install failed",
                new GraphMailApi.ApiException(404, -1, null))));
        assertTrue(NativeAbsentMessage.isAbsent(new IOException("body install failed",
                new IOException("parse", new GmailApi.ApiException(404, "missing")))));
        assertFalse(NativeAbsentMessage.isAbsent(new IOException("body install failed",
                new GraphMailApi.ApiException(500, -1, null))));
    }

    @Test
    public void cachedImageNamesAreScopedToTheirOwnMessage() {
        assertTrue(NativeAbsentMessage.belongsToMessage("412_1.jpg", 412L));
        assertTrue(NativeAbsentMessage.belongsToMessage("412.2", 412L));
        assertTrue(NativeAbsentMessage.belongsToMessage("412", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage("4120_1.jpg", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage("41_1.jpg", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage("412abc.jpg", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage("_412.jpg", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage("banner.png", 412L));
        assertFalse(NativeAbsentMessage.belongsToMessage(null, 412L));
    }

    @Test
    public void retirementDeletesTheRowBeforeItsCacheAndRefreshesTheList() throws Exception {
        String helper = source("NativeAbsentMessage");
        int readback = helper.indexOf("EntityMessage current = db.message().getMessage(message.id);");
        int guard = helper.indexOf("!Objects.equals(providerId, providerId(current))");
        int delete = helper.indexOf("db.message().deleteMessage(current.id)");
        int files = helper.indexOf("deleteCachedFiles(context, message, attachments)");
        assertTrue("A concurrent re-point to another provider id must keep the row",
                readback > 0 && readback < guard && guard < delete);
        assertTrue("Cache files are removed only after the row is committed", delete < files);
        assertTrue(helper.contains("Helper.secureDelete(message.getFile(context))"));
        assertTrue(helper.contains("Helper.secureDelete(message.getRawFile(context))"));
        assertTrue(helper.contains("Helper.secureDelete(message.getRefFile(context))"));
        assertTrue(helper.contains("Helper.secureDelete(attachment.getFile(context))"));
        assertTrue("The message list, search and navigation must be refreshed",
                helper.contains("ServiceSynchronize.reload(context, message.account, false, "
                        + "\"stale message removed\")"));
    }

    @Test
    public void anOpenedMessageIsRetiredOnlyForAnAbsenceAnswerAndNeverForAnAttachment() throws Exception {
        String source = source("NativeApiContentOperations");
        int start = source.indexOf("} catch (Throwable ex) {");
        String handler = source.substring(start, source.indexOf("} finally {", start));
        assertTrue(handler.contains(
                "if (bodyOperation && message != null && NativeAbsentMessage.isAbsent(ex))"));
        assertTrue("Gmail and Graph remove the row; Zoho resolves a possible move first",
                handler.contains("!account.isZohoApi()"));
        assertTrue(handler.contains("ZohoMessageSync.refresh(context, db, message, zoho)"));
        int retirement = handler.indexOf("NativeAbsentMessage.isAbsent(ex)");
        int retry = handler.indexOf("handleFailedFetch(");
        assertTrue("A retired message must not also be reported as a retryable failure",
                retirement < retry);
    }

    @Test
    public void zohoRetiresACachedRowOnlyWhenNoMembershipRemainsAfterProbes() throws Exception {
        String source = source("ZohoMessageSync");
        String refresh = source.substring(source.indexOf("static boolean refresh("),
                source.indexOf("static boolean hasPendingLocationChange("));
        assertTrue(refresh.contains("readback.sourceMissing && readback.found.isEmpty()"));
        assertTrue("A queued move or delete keeps the row until it is resolved",
                refresh.contains("boolean locationQueued = " +
                        "hasPendingLocationChange(db.operation(), message.id);"));
        assertTrue("An unmapped membership must keep the cached row until it can be checked",
                refresh.contains("hasNoRemainingMemberships(readback.memberships)"));
        assertTrue(refresh.contains("NativeAbsentMessage.purge(context, db, message, "
                + "\"not in any local Zoho folder\")"));
        int probe = refresh.indexOf("Readback readback = resolve(api");
        int purge = refresh.indexOf("NativeAbsentMessage.purge(");
        int relocate = refresh.indexOf("db.runInTransaction(() -> {");
        assertTrue("Absence is decided from the complete readback, before memberships are rewritten",
                probe < purge && purge < relocate);
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
