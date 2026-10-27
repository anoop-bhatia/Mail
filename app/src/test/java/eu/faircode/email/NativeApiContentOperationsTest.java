package eu.faircode.email;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class NativeApiContentOperationsTest {
    @Test
    public void nativeApiAccountsAreExplicitlyWhitelisted() {
        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        EntityAccount zoho = new EntityAccount();
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        EntityAccount graph = new EntityAccount();
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;

        assertTrue(NativeApiContentOperations.supportsAccount(gmail));
        assertTrue(NativeApiContentOperations.supportsAccount(zoho));
        assertTrue(NativeApiContentOperations.supportsAccount(graph));
        EntityAttachment inline = new EntityAttachment();
        inline.provider_id = "remote-attachment";
        assertTrue(NativeApiContentOperations.shouldFetchRenderedInline(graph, inline));
        inline.progress = 0;
        assertFalse(NativeApiContentOperations.shouldFetchRenderedInline(graph, inline));
        inline.progress = null;
        inline.available = true;
        assertFalse(NativeApiContentOperations.shouldFetchRenderedInline(graph, inline));
    }

    @Test
    public void graphCidImagesFetchOnlyForAnInlineBodyReferenceAndWakeTheSelectedAttachmentWorker() {
        EntityMessage message = new EntityMessage();
        message.graph_id = "graph-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example.test>";
        image.provider_id = "graph-inline-file";
        image.provider_type = "#microsoft.graph.fileAttachment";
        image.disposition = javax.mail.Part.INLINE;

        assertTrue(NativeApiContentOperations.isGraphApiCidImage(message, image));
        assertTrue(NativeApiContentOperations.hasUncachedGraphInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test"));
        assertTrue(NativeApiContentOperations.shouldFetchGraphCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", false, true));
        assertFalse(NativeApiContentOperations.shouldFetchGraphCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", true, true));
        assertFalse(NativeApiContentOperations.shouldFetchGraphCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", false, false));
        assertFalse(NativeApiContentOperations.hasUncachedGraphInlineImage(
                message, java.util.Collections.singletonList(image), "cid:other@example.test"));
        assertTrue(NativeApiContentOperations.shouldWakeForGraphCidImageRequest(message, image));

        image.progress = 0;
        assertFalse(NativeApiContentOperations.shouldWakeForGraphCidImageRequest(message, image));
        image.progress = null;
        image.available = true;
        assertFalse(NativeApiContentOperations.hasUncachedGraphInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test"));
        assertFalse(NativeApiContentOperations.shouldWakeForGraphCidImageRequest(message, image));
        image.available = false;
        image.cid = null;
        assertFalse(NativeApiContentOperations.isGraphApiCidImage(message, image));
    }

    @Test
    public void zohoCidImagesUseInlineRouteOnlyAndQueueForReferencedRendering() {
        EntityMessage message = new EntityMessage();
        message.zoho_id = "zoho-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example.test>";
        image.provider_id = "zoho-inline-file";
        image.provider_type = ZohoApi.INLINE_ATTACHMENT_TYPE;
        image.disposition = javax.mail.Part.INLINE;

        assertTrue(NativeApiContentOperations.usesZohoInlineImageEndpoint(image));
        assertTrue(NativeApiContentOperations.isZohoApiCidImage(message, image));
        assertTrue(NativeApiContentOperations.matchesZohoCidReference(
                message, image, "cid:logo@example.test"));
        String display = "/mail/ImageDisplay?imgWidth=160&cid=logo%40example.test";
        assertEquals("<logo@example.test>",
                NativeApiContentOperations.getZohoImageDisplayCid(display));
        assertTrue(NativeApiContentOperations.matchesZohoCidReference(message, image, display));
        assertTrue(NativeApiContentOperations.hasZohoInlineImageReference(
                message, java.util.Collections.singletonList(image), display));
        assertEquals("<logo@example.test>",
                NativeApiContentOperations.getZohoImageDisplayCid(
                        "/mail/ImageDisplay?cid=%3Clogo%40example.test%3E"));
        assertEquals(null, NativeApiContentOperations.getZohoImageDisplayCid(
                "/mail/ImageDisplay?cid=logo%40example.test&cid=other%40example.test"));
        assertEquals(null, NativeApiContentOperations.getZohoImageDisplayCid(
                "/mail/Other?cid=logo%40example.test"));
        assertEquals(null, NativeApiContentOperations.getZohoImageDisplayCid(
                "https://images.example.test/mail/ImageDisplay?cid=logo%40example.test"));
        assertFalse(NativeApiContentOperations.matchesZohoCidReference(
                message, image, "https://images.example.test/mail/ImageDisplay?cid=logo%40example.test"));
        assertTrue(NativeApiContentOperations.hasUncachedZohoInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test"));
        assertTrue(NativeApiContentOperations.hasUncachedZohoInlineImage(
                message, java.util.Collections.singletonList(image), display));
        assertTrue(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", false, true, true));
        assertTrue(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), display, false, true, true));
        assertFalse("Sender-specific Show images=false must block body auto-fetch",
                NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                        message, java.util.Collections.singletonList(image), display, false, true, false));
        assertFalse(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", true, true, true));
        assertFalse(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test", false, false, true));
        assertFalse(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                message, java.util.Collections.singletonList(image), display, false, false, true));
        assertTrue(NativeApiContentOperations.shouldWakeForZohoCidImageRequest(message, image));

        image.progress = 0;
        assertFalse(NativeApiContentOperations.shouldWakeForZohoCidImageRequest(message, image));
        image.progress = null;
        image.available = true;
        assertFalse(NativeApiContentOperations.hasUncachedZohoInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example.test"));
        assertTrue(NativeApiContentOperations.hasZohoInlineImageReference(
                message, java.util.Collections.singletonList(image), display));
        assertFalse(NativeApiContentOperations.hasUncachedZohoInlineImage(
                message, java.util.Collections.singletonList(image), display));
        assertFalse(NativeApiContentOperations.shouldWakeForZohoCidImageRequest(message, image));

        image.available = false;
        image.provider_type = ZohoApi.ORDINARY_ATTACHMENT_TYPE;
        assertFalse(NativeApiContentOperations.usesZohoInlineImageEndpoint(image));
        assertFalse(NativeApiContentOperations.isZohoApiCidImage(message, image));
        assertFalse(NativeApiContentOperations.matchesZohoCidReference(message, image, display));
        assertFalse(NativeApiContentOperations.hasZohoInlineImageReference(
                message, java.util.Collections.singletonList(image), display));
        image.provider_type = "zoho";
        assertTrue("Older Zoho inline rows retain their MIME disposition and CID",
                NativeApiContentOperations.usesZohoInlineImageEndpoint(image));
    }

    @Test
    public void zohoImageDisplayRewriteRequiresPermissionAvailableBytesAndMatchingCid() {
        EntityMessage message = new EntityMessage();
        message.zoho_id = "zoho-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example.test>";
        image.provider_id = "zoho-inline-file";
        image.provider_type = ZohoApi.INLINE_ATTACHMENT_TYPE;
        image.disposition = javax.mail.Part.INLINE;
        image.available = true;

        String source = "/mail/ImageDisplay?imgWidth=160&cid=logo%40example.test";
        String dataUri = "data:image/png;base64,AA==";

        assertTrue(NativeApiContentOperations.shouldEmbedZohoInlineImages(message, true, false));
        assertTrue(NativeApiContentOperations.shouldEmbedZohoInlineImages(message, false, true));
        assertFalse(NativeApiContentOperations.shouldEmbedZohoInlineImages(message, false, false));
        assertEquals(dataUri, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, true, false));
        assertEquals(dataUri, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, true));
        assertEquals(source, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, false));

        image.available = false;
        assertEquals(source, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, true, false));
        image.available = true;
        String otherSource = "/mail/ImageDisplay?cid=other%40example.test";
        assertEquals(otherSource, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, otherSource,
                dataUri, true, false));
        image.provider_type = ZohoApi.ORDINARY_ATTACHMENT_TYPE;
        assertEquals(source, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, true, false));
    }

    @Test
    public void zohoShowOriginalUsesCurrentInlinePreferenceToEmbedCachedImageDisplayBytes() {
        EntityMessage message = new EntityMessage();
        message.zoho_id = "zoho-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example.test>";
        image.provider_id = "zoho-inline-file";
        image.provider_type = ZohoApi.INLINE_ATTACHMENT_TYPE;
        image.disposition = javax.mail.Part.INLINE;
        image.available = true;

        String source = "/mail/ImageDisplay?imgWidth=160&cid=logo%40example.test";
        String dataUri = "data:image/png;base64,AA==";
        boolean inlineImages = NativeApiContentOperations.getInlineImagesPreference(
                message, true, false);

        // Show Original uses local=true for other providers; Zoho's web-only route must instead
        // be replaced with the already-cached REST bytes when either image permission allows it.
        assertEquals(dataUri, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, inlineImages));
        assertEquals(dataUri, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, true, false));

        inlineImages = NativeApiContentOperations.getInlineImagesPreference(
                message, false, true);
        assertFalse(inlineImages);
        assertEquals(source, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, inlineImages));

        String external = "https://images.example.test/mail/ImageDisplay?cid=logo%40example.test";
        assertEquals(external, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, external, dataUri, true, true));
    }

    @Test
    public void zohoCompactKeepsCidForTextViewWhileFullHtmlEmbedsCachedBytes() {
        EntityMessage message = new EntityMessage();
        message.zoho_id = "zoho-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example.test>";
        image.provider_id = "zoho-inline-file";
        image.provider_type = ZohoApi.INLINE_ATTACHMENT_TYPE;
        image.disposition = javax.mail.Part.INLINE;
        image.available = true;

        String source = "/mail/ImageDisplay?imgWidth=160&cid=logo%40example.test";
        String dataUri = "data:image/png;base64,AA==";
        String base = "https://mail.zoho.eu/";
        String baseResolved = java.net.URI.create(base).resolve(source).toString();
        boolean attachmentMatch = NativeApiContentOperations.hasZohoInlineImageReference(
                message, java.util.Collections.singletonList(image), source);
        assertTrue(attachmentMatch);
        assertFalse("Full HTML must keep matched Zoho image sources root-relative until cached-byte embedding",
                HtmlHelper.shouldGuessScheme("img", message,
                        java.util.Collections.singletonList(image), source));
        assertTrue(HtmlHelper.shouldGuessScheme("img", message,
                java.util.Collections.<EntityAttachment>emptyList(), source));
        assertTrue(HtmlHelper.shouldGuessScheme("a", message,
                java.util.Collections.singletonList(image), source));
        assertTrue(HtmlHelper.shouldGuessScheme("img", message,
                java.util.Collections.singletonList(image),
                "https://cdn.example.test/banner.png"));
        assertEquals(source, HtmlHelper.resolveRelativeLink(base, source, attachmentMatch));
        assertEquals(baseResolved, HtmlHelper.resolveRelativeLink(base, source,
                NativeApiContentOperations.hasZohoInlineImageReference(message,
                        java.util.Collections.<EntityAttachment>emptyList(), source)));
        assertEquals("https://mail.zoho.eu" + source,
                HtmlHelper.resolveRelativeLink(base, source, false));
        assertEquals("https://cdn.example.test/banner.png",
                HtmlHelper.resolveRelativeLink(base, "https://cdn.example.test/banner.png", true));

        String compact = NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, null, false, true, true);
        assertEquals("cid:logo@example.test", compact);
        assertTrue("Compact sanitization and ImageHelper must see a MIME CID source",
                compact.startsWith("cid:"));
        assertTrue(NativeApiContentOperations.matchesZohoCidReference(message, image, compact));
        assertEquals("cid:logo@example.test",
                NativeApiContentOperations.rewriteZohoImageDisplaySource(
                        message, image, "cid:logo@example.test", null, false, true, true));

        // Full HTML needs a data URI because its WebView has no Zoho Mail origin.
        assertEquals(dataUri, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, true, false));
        assertEquals(source, NativeApiContentOperations.rewriteZohoImageDisplaySource(
                message, image, source, dataUri, false, false, true));

        // This is the Full HTML precondition: base resolution makes the URL absolute, after which
        // the intentionally root-relative-only matcher no longer treats it as an API resource.
        assertEquals(null, NativeApiContentOperations.getZohoImageDisplayCid(baseResolved));
        assertTrue(ZohoApiDiagnostics.formatInlineMapping(1, 1, 1, 1,
                "html").contains("render_mode=html"));
    }

    @Test
    public void zohoShowOriginalUsesCurrentInlinePreferenceAndOtherProvidersKeepSnapshot() {
        EntityMessage zoho = new EntityMessage();
        zoho.zoho_id = "zoho-message";
        assertTrue(NativeApiContentOperations.getInlineImagesPreference(zoho, true, false));
        assertFalse(NativeApiContentOperations.getInlineImagesPreference(zoho, false, true));

        // Legacy IMAP/POP (including Gmail-hosted IMAP), Gmail API, and Graph keep prior behavior.
        EntityMessage legacy = new EntityMessage();
        assertTrue(NativeApiContentOperations.getInlineImagesPreference(legacy, false, true));
        assertFalse(NativeApiContentOperations.getInlineImagesPreference(legacy, true, false));
        EntityMessage gmailApi = new EntityMessage();
        gmailApi.gmail_id = "gmail-message";
        assertTrue(NativeApiContentOperations.getInlineImagesPreference(gmailApi, false, true));
        EntityMessage graph = new EntityMessage();
        graph.graph_id = "graph-message";
        assertFalse(NativeApiContentOperations.getInlineImagesPreference(graph, true, false));
    }

    @Test
    public void bulkAttachmentQueueWakesGmailAndZohoWorkersOnly() {
        EntityAccount zoho = new EntityAccount();
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        assertTrue(NativeApiContentOperations.shouldWakeAfterBulkAttachmentQueue(zoho));

        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertTrue(NativeApiContentOperations.shouldWakeAfterBulkAttachmentQueue(gmail));

        EntityAccount graph = new EntityAccount();
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertFalse(NativeApiContentOperations.shouldWakeAfterBulkAttachmentQueue(graph));

        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        imap.host = "imap.example.org";
        assertFalse(NativeApiContentOperations.shouldWakeAfterBulkAttachmentQueue(imap));
    }

    @Test
    public void attachmentTapQueuesOnlyOnceUntilProgressCompletes() {
        EntityAttachment attachment = new EntityAttachment();
        assertTrue(NativeApiContentOperations.shouldQueueAttachmentTap(attachment));

        // EntityOperation.queue marks the row pending; a fast second tap is ignored.
        attachment.progress = 0;
        assertFalse(NativeApiContentOperations.shouldQueueAttachmentTap(attachment));

        // A completed file also cannot be queued; a failed row has progress cleared and can retry.
        attachment.progress = null;
        attachment.available = true;
        assertFalse(NativeApiContentOperations.shouldQueueAttachmentTap(attachment));
        attachment.available = false;
        attachment.error = "stale cache or transport failure";
        assertTrue(NativeApiContentOperations.shouldQueueAttachmentTap(attachment));
    }

    @Test
    public void missingUidGuardAppliesOnlyToLegacyImapNotNativeRestAccounts() {
        EntityMessage message = new EntityMessage();
        message.uid = null;

        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        imap.host = "imap.example.org";
        imap.password = "legacy-password";
        assertTrue(NativeApiContentOperations.hasMissingLegacyImapUid(imap, message));

        EntityAccount gmailImap = new EntityAccount();
        gmailImap.protocol = EntityAccount.TYPE_IMAP;
        gmailImap.host = "imap.gmail.com";
        gmailImap.password = "legacy-password";
        assertTrue("Gmail-hosted legacy IMAP still requires a UID",
                NativeApiContentOperations.hasMissingLegacyImapUid(gmailImap, message));

        for (int authType : new int[]{ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.AUTH_TYPE_ZOHO, ServiceAuthenticator.AUTH_TYPE_GRAPH_API}) {
            EntityAccount api = new EntityAccount();
            api.protocol = EntityAccount.TYPE_IMAP; // Native REST persistence sentinel
            api.auth_type = authType;
            assertFalse("Native REST messages use provider IDs rather than IMAP UIDs",
                    NativeApiContentOperations.hasMissingLegacyImapUid(api, message));
        }

        imap.protocol = EntityAccount.TYPE_POP;
        assertFalse(NativeApiContentOperations.hasMissingLegacyImapUid(imap, message));
        message.uid = 42L;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(NativeApiContentOperations.hasMissingLegacyImapUid(imap, message));
    }

    @Test
    public void onlyAnExplicitSaveRawOperationEntersTheNativeRawExportPath() {
        EntityOperation operation = new EntityOperation();
        operation.name = EntityOperation.RAW;
        operation.args = "[]";
        assertFalse(NativeApiContentOperations.isRawExportOperation(operation));
        operation.args = "[\"" + NativeApiContentOperations.EXPLICIT_RAW_EXPORT_ARGUMENT + "\"]";
        assertTrue(NativeApiContentOperations.isRawExportOperation(operation));
        operation.name = EntityOperation.BODY;
        assertFalse(NativeApiContentOperations.isRawExportOperation(operation));
    }

    @Test
    public void legacyImapPopAndGmailHostedImapAccountsAreNotRouted() {
        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        imap.host = "imap.example.org";
        imap.password = "legacy-password";
        EntityAccount gmailImap = new EntityAccount();
        gmailImap.protocol = EntityAccount.TYPE_IMAP;
        gmailImap.host = "imap.gmail.com";
        gmailImap.password = "legacy-password";
        EntityAccount pop = new EntityAccount();
        pop.protocol = EntityAccount.TYPE_POP;
        pop.host = "pop.example.org";
        pop.password = "legacy-password";

        assertFalse(NativeApiContentOperations.supportsAccount(imap));
        assertFalse(NativeApiContentOperations.supportsAccount(gmailImap));
        assertFalse(NativeApiContentOperations.supportsAccount(pop));
        EntityAttachment inline = new EntityAttachment();
        inline.provider_id = "remote-attachment";
        assertFalse(NativeApiContentOperations.shouldFetchRenderedInline(gmailImap, inline));
        assertFalse(NativeApiContentOperations.shouldFetchRenderedInline(pop, inline));
    }

    @Test
    public void onlyUncachedGmailCidImagesKeepTheInlineRevealActionAvailable() {
        EntityMessage message = new EntityMessage();
        message.gmail_id = "gmail-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.cid = "<logo@example>";
        image.provider_id = "gmail-inline-attachment";
        image.provider_type = GmailApiMessageParser.ATTACHMENT_ID;

        assertTrue(NativeApiContentOperations.isGmailApiCidImage(message, image));
        assertTrue(NativeApiContentOperations.hasUncachedGmailInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example"));

        image.available = true;
        assertFalse(NativeApiContentOperations.hasUncachedGmailInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example"));
        image.available = false;
        image.provider_type = "ordinary-attachment";
        assertFalse(NativeApiContentOperations.isGmailApiCidImage(message, image));
        message.gmail_id = null;
        image.provider_type = GmailApiMessageParser.ATTACHMENT_ID;
        assertFalse(NativeApiContentOperations.hasUncachedGmailInlineImage(
                message, java.util.Collections.singletonList(image), "cid:logo@example"));
    }

    @Test
    public void gmailCidImageRequestsWakeAndRedrawEvenWhenMimeMarksThemAsAttachments() {
        EntityMessage message = new EntityMessage();
        message.gmail_id = "gmail-message";
        EntityAttachment image = new EntityAttachment();
        image.type = "image/png";
        image.disposition = "attachment";
        image.related = false;
        image.cid = "<logo@example>";
        image.provider_id = "gmail-inline-attachment";
        image.provider_type = GmailApiMessageParser.ATTACHMENT_ID;

        assertFalse(image.isInline());
        assertTrue(NativeApiContentOperations.shouldWakeForGmailCidImageRequest(message, image));
        assertFalse(NativeApiContentOperations.isAvailableGmailCidImage(message, image));

        image.progress = 0;
        assertFalse(NativeApiContentOperations.shouldWakeForGmailCidImageRequest(message, image));
        image.progress = null;
        image.available = true;
        assertFalse(NativeApiContentOperations.shouldWakeForGmailCidImageRequest(message, image));
        assertTrue(NativeApiContentOperations.isAvailableGmailCidImage(message, image));

        image.available = false;
        message.gmail_id = null;
        assertFalse(NativeApiContentOperations.shouldWakeForGmailCidImageRequest(message, image));
        assertFalse(NativeApiContentOperations.isAvailableGmailCidImage(message, image));
    }

    @Test
    public void openingBodylessNativeMessagesQueuesOnlyDownloadedProviderMessages() {
        EntityAccount gmail = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GMAIL, 101L);
        EntityAccount zoho = nativeAccount(ServiceAuthenticator.AUTH_TYPE_ZOHO, 102L);
        EntityAccount graph = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, 103L);
        EntityFolder folder = new EntityFolder("Inbox", EntityFolder.INBOX);
        folder.id = 201L;
        folder.account = gmail.id;
        folder.download = true;
        EntityMessage message = new EntityMessage();
        message.id = 301L;
        message.account = gmail.id;
        message.folder = folder.id;
        message.gmail_id = "gmail-message";

        assertTrue(NativeApiContentOperations.shouldQueueBodyOnOpen(gmail, folder, message));
        message.gmail_id = null;
        message.zoho_id = "zoho-message";
        message.account = zoho.id;
        folder.account = zoho.id;
        assertTrue(NativeApiContentOperations.shouldQueueBodyOnOpen(zoho, folder, message));
        message.zoho_id = null;
        message.graph_id = "graph-message";
        message.account = graph.id;
        folder.account = graph.id;
        assertTrue(NativeApiContentOperations.shouldQueueBodyOnOpen(graph, folder, message));

        folder.download = false;
        assertTrue(NativeApiContentOperations.shouldQueueBodyOnOpen(graph, folder, message));
        folder.download = true;
        message.content = true;
        assertFalse(NativeApiContentOperations.shouldQueueBodyOnOpen(graph, folder, message));
        message.content = false;
        message.graph_id = null;
        assertFalse(NativeApiContentOperations.shouldQueueBodyOnOpen(graph, folder, message));

        EntityAccount legacyImap = new EntityAccount();
        legacyImap.id = 104L;
        legacyImap.protocol = EntityAccount.TYPE_IMAP;
        message.account = legacyImap.id;
        message.uid = null;
        folder.account = legacyImap.id;
        assertFalse(NativeApiContentOperations.shouldQueueBodyOnOpen(legacyImap, folder, message));
    }

    @Test
    public void bodyOpenDecisionNamesEligibilityAndRetryReasons() {
        EntityAccount graph = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, 103L);
        EntityFolder folder = new EntityFolder("Inbox", EntityFolder.INBOX);
        folder.id = 201L;
        folder.account = graph.id;
        folder.download = true;
        EntityMessage message = new EntityMessage();
        message.id = 301L;
        message.account = graph.id;
        message.folder = folder.id;
        message.graph_id = "provider-message-id";

        assertEquals("eligible", NativeApiContentOperations.bodyOpenDecision(
                graph, folder, message, false));
        assertEquals("already_pending", NativeApiContentOperations.bodyOpenDecision(
                graph, folder, message, true));
        folder.download = false;
        assertEquals("eligible", NativeApiContentOperations.bodyOpenDecision(
                graph, folder, message, false));
        folder.download = true;
        message.graph_id = null;
        assertEquals("provider_id_missing", NativeApiContentOperations.bodyOpenDecision(
                graph, folder, message, false));
        message.content = true;
        assertEquals("already_complete", NativeApiContentOperations.bodyOpenDecision(
                graph, folder, message, false));
    }

    private static EntityAccount nativeAccount(int authType, long id) {
        EntityAccount account = new EntityAccount();
        account.auth_type = authType;
        account.id = id;
        return account;
    }

    @Test
    public void completeRawMessageIsInstalledOnceAndReusedForLaterSaves() throws Exception {
        File directory = Files.createTempDirectory("native-raw-cache-test").toFile();
        File destination = new File(directory, "message.eml");
        String mime = "From: sender@example.invalid\r\nSubject: original\r\n" +
                "Content-Type: multipart/mixed; boundary=part\r\n\r\n--part\r\n" +
                "Content-Type: text/plain\r\n\r\nhello\r\n--part\r\n" +
                "Content-Type: application/octet-stream; name=\"file.bin\"\r\n" +
                "Content-Disposition: attachment; filename=\"file.bin\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n--part--\r\n";
        final int[] requests = {0};
        try {
            assertTrue(NativeApiContentOperations.ensureRawFile(false, destination, temporary -> {
                requests[0]++;
                write(temporary, mime);
            }));
            assertEquals(mime, new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            assertTrue(NativeApiContentOperations.isCompleteRawCache(true, destination));

            assertFalse(NativeApiContentOperations.ensureRawFile(true, destination, temporary -> {
                requests[0]++;
                throw new IOException("A complete EML cache should have been reused");
            }));
            assertEquals(1, requests[0]);
        } finally {
            if (destination.exists())
                assertTrue(destination.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void incompleteRawResponseIsRemovedAndCanBeRetriedWithoutTouchingAttachments() throws Exception {
        File directory = Files.createTempDirectory("native-raw-retry-test").toFile();
        File destination = new File(directory, "message.eml");
        File attachment = new File(directory, "existing-attachment.bin");
        write(attachment, "attachment-cache");
        try {
            try {
                NativeApiContentOperations.ensureRawFile(false, destination, temporary -> {
                    write(temporary, "partial MIME");
                    throw new IOException("connection dropped before full EML completed");
                });
                fail("A failed provider response must not install a complete EML");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("connection dropped"));
            }
            assertFalse(destination.exists());
            assertEquals("attachment-cache", new String(Files.readAllBytes(attachment.toPath()),
                    StandardCharsets.UTF_8));
            assertEquals("Only the unrelated attachment cache remains", 1, directory.listFiles().length);

            assertTrue(NativeApiContentOperations.ensureRawFile(false, destination,
                    temporary -> write(temporary, "complete MIME on retry")));
            assertEquals("complete MIME on retry",
                    new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            assertEquals("attachment-cache", new String(Files.readAllBytes(attachment.toPath()),
                    StandardCharsets.UTF_8));
        } finally {
            if (destination.exists())
                assertTrue(destination.delete());
            if (attachment.exists())
                assertTrue(attachment.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void attachmentInstallIsAtomicAndNeverReplacesAnExistingCacheFile() throws Exception {
        File directory = Files.createTempDirectory("native-attachment-cache-test").toFile();
        File temporary = new File(directory, "download.tmp");
        File destination = new File(directory, "attachment.bin");
        try {
            write(temporary, "complete");
            NativeApiContentOperations.installAtomically(temporary, destination, false);
            assertTrue(destination.isFile());
            assertFalse(temporary.exists());
            assertEquals("complete", new String(Files.readAllBytes(destination.toPath()),
                    StandardCharsets.UTF_8));

            write(temporary, "replacement");
            try {
                NativeApiContentOperations.installAtomically(temporary, destination, false);
                fail("An existing attachment cache file must not be overwritten");
            } catch (IOException expected) {
                assertEquals("complete", new String(Files.readAllBytes(destination.toPath()),
                        StandardCharsets.UTF_8));
            }
            try {
                NativeApiContentOperations.verifyLength(destination, 1L);
                fail("Provider size mismatches must not be marked available");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("length"));
            }
        } finally {
            if (temporary.exists())
                assertTrue(temporary.delete());
            if (destination.exists())
                assertTrue(destination.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void invalidExistingAttachmentCacheIsRemovedSoARequestedDownloadCanRetry() throws Exception {
        File directory = Files.createTempDirectory("native-attachment-invalid-cache-test").toFile();
        File destination = new File(directory, "attachment.bin");
        try {
            write(destination, "partial");
            assertFalse(NativeApiContentOperations.reuseOrRemoveInvalidAttachmentCache(destination, 12L));
            assertFalse("Invalid stale bytes must not block the selected-file network retry",
                    destination.exists());

            write(destination, "complete");
            assertTrue(NativeApiContentOperations.reuseOrRemoveInvalidAttachmentCache(destination, 8L));
            assertEquals("complete", new String(Files.readAllBytes(destination.toPath()),
                    StandardCharsets.UTF_8));
        } finally {
            if (destination.exists())
                assertTrue(destination.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void graphAttachmentMetadataSizeCanDifferFromRawValueBytes() throws Exception {
        File directory = Files.createTempDirectory("graph-attachment-size-test").toFile();
        File attachment = new File(directory, "attachment.bin");
        try {
            // Microsoft Graph's large-attachment example reports metadata size 3,640,066
            // while the raw uploaded/$value file body is 3,483,322 bytes.
            try (RandomAccessFile output = new RandomAccessFile(attachment, "rw")) {
                output.setLength(3483322L);
            }
            assertEquals(3483322L, attachment.length());
            NativeApiContentOperations.verifyAttachmentLength(attachment, 3640066L, true);
            assertTrue("A complete Graph cache remains reusable despite metadata overhead",
                    NativeApiContentOperations.reuseOrRemoveInvalidAttachmentCache(
                            attachment, 3640066L, true));
            assertTrue(GraphMailApi.isRawContentAttachmentType("#microsoft.graph.fileAttachment"));
            assertTrue(GraphMailApi.isRawContentAttachmentType("#microsoft.graph.itemAttachment"));
            assertFalse(GraphMailApi.isRawContentAttachmentType("#microsoft.graph.referenceAttachment"));
            try {
                NativeApiContentOperations.verifyAttachmentLength(attachment, 3640066L, false);
                fail("Other providers and unclassified attachment types keep exact metadata validation");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("length"));
            }
        } finally {
            if (attachment.exists())
                assertTrue(attachment.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void graphAttachmentWithUnavailableSizeMustStillHavePayloadBytes() throws Exception {
        File directory = Files.createTempDirectory("graph-attachment-unknown-size-test").toFile();
        File attachment = new File(directory, "attachment.eml");
        try {
            write(attachment, "MIME item attachment");
            NativeApiContentOperations.verifyAttachmentLength(attachment, null, true);
            try (RandomAccessFile output = new RandomAccessFile(attachment, "rw")) {
                output.setLength(0);
            }
            try {
                NativeApiContentOperations.verifyAttachmentLength(attachment, null, true);
                fail("An empty Graph response must not be installed when size metadata is absent");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("empty"));
            }
        } finally {
            if (attachment.exists())
                assertTrue(attachment.delete());
            assertTrue(directory.delete());
        }
    }

    @Test
    public void failedFetchRemovesPartialFileAndReleasesOperationForRetry() throws Exception {
        File directory = Files.createTempDirectory("native-attachment-retry-test").toFile();
        File partial = new File(directory, "partial-download.tmp");
        write(partial, "partial");
        final Long[] failedAttachment = {null};
        final String[] attachmentError = {null};
        final Long[] loggedOperation = {null};
        final Long[] deletedOperation = {null};
        StringBuilder order = new StringBuilder();

        NativeApiContentOperations.handleFailedFetch(partial, 17L, 42L, "network failure",
                new NativeApiContentOperations.FailureHandler() {
                    @Override
                    public void setAttachmentError(long attachmentId, String error) {
                        assertFalse(partial.exists());
                        failedAttachment[0] = attachmentId;
                        attachmentError[0] = error;
                        order.append("error,");
                    }

                    @Override
                    public void logFailure(long operationId, String error) {
                        loggedOperation[0] = operationId;
                        order.append("log,");
                    }

                    @Override
                    public void deleteOperation(long operationId) {
                        deletedOperation[0] = operationId;
                        order.append("delete");
                    }
                });

        assertFalse(partial.exists());
        assertEquals(Long.valueOf(17L), failedAttachment[0]);
        assertEquals("network failure", attachmentError[0]);
        assertEquals(Long.valueOf(42L), loggedOperation[0]);
        assertEquals(Long.valueOf(42L), deletedOperation[0]);
        assertEquals("error,log,delete", order.toString());
        assertTrue(directory.delete());
    }

    @Test
    public void failedRawExportReleasesOnlyItsOperationAndLeavesAttachmentsUnchanged() throws Exception {
        File directory = Files.createTempDirectory("native-raw-operation-failure-test").toFile();
        File attachment = new File(directory, "attachment.bin");
        write(attachment, "cached attachment bytes");
        final Long[] loggedOperation = {null};
        final Long[] deletedOperation = {null};
        final int[] attachmentChanges = {0};
        try {
            NativeApiContentOperations.handleFailedFetch(null, null, 83L, "raw MIME request failed",
                    new NativeApiContentOperations.FailureHandler() {
                        @Override
                        public void setAttachmentError(long attachmentId, String error) {
                            attachmentChanges[0]++;
                        }

                        @Override
                        public void logFailure(long operationId, String error) {
                            loggedOperation[0] = operationId;
                        }

                        @Override
                        public void deleteOperation(long operationId) {
                            deletedOperation[0] = operationId;
                        }
                    });
            assertEquals(Long.valueOf(83L), loggedOperation[0]);
            assertEquals(Long.valueOf(83L), deletedOperation[0]);
            assertEquals(0, attachmentChanges[0]);
            assertEquals("cached attachment bytes", new String(Files.readAllBytes(attachment.toPath()),
                    StandardCharsets.UTF_8));
        } finally {
            if (attachment.exists())
                assertTrue(attachment.delete());
            assertTrue(directory.delete());
        }
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
