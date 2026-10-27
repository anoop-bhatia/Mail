package in.anoopbhatia.mail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoApiTest {
    @Test
    public void messagePagesAreBoundedAndUseRegionalApiDomainAndZohoAuthHeader() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":[{" +
                                "\"messageId\":\"opaque-1\",\"status\":\"read\",\"flagId\":2," +
                                "\"receivedTime\":1710000000000,\"sentDateInGMT\":1709990000000," +
                                "\"size\":17,\"summary\":\"list preview\"}] }"));

        ZohoApi.Page page = api.listMessages("folder/one", 1, ZohoApi.PAGE_SIZE);
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/messages/view?start=1&limit=10&sortBy=date&sortorder=false&folderId=folder%2Fone",
                opened[0].getURL().toString());
        assertEquals("Zoho-oauthtoken access-fixture", opened[0].getRequestProperty("Authorization"));
        assertEquals(1, page.messages.size());
        assertEquals(1, page.resultItemCount);
        assertEquals(200, page.httpStatus);
        assertEquals("opaque-1", page.messages.get(0).id);
        assertEquals(0, page.messages.get(0).position);
        assertTrue(page.messages.get(0).seen);
        assertTrue(page.messages.get(0).flagged);
        assertEquals(1710000000000L, page.messages.get(0).received);
        assertEquals("list preview", page.messages.get(0).summary);

        try {
            api.listMessages("folder/one", 1, 11);
            fail("Zoho receive must stay bounded to ten items");
        } catch (IllegalArgumentException expected) {
            // The REST client enforces the Mail per-request cap.
        }
    }

    @Test
    public void aLostGetSocketIsReplayedOnceInsideThePass() throws Exception {
        final int[] attempts = {0};
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42", url -> {
            if (attempts[0]++ == 0)
                throw new java.net.SocketException("connection reset");
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"status\":{\"code\":200},\"data\":[]}");
        });

        ZohoApi.Page page = api.listMessages("folder/one", 1, ZohoApi.PAGE_SIZE);

        assertEquals(2, attempts[0]);
        assertEquals(0, page.messages.size());
    }

    @Test
    public void everyHttpAttemptIsReportedForQuotaPacing() throws Exception {
        final int[] attempts = {0};
        final int[] reported = {0};
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42", url -> {
            attempts[0]++;
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"status\":{\"code\":200},\"data\":[]}");
        });
        api.setRequestListener(() -> reported[0]++);

        api.listMessages("folder/one", 1, ZohoApi.PAGE_SIZE);

        assertEquals(1, attempts[0]);
        assertEquals(attempts[0], reported[0]);
    }

    @Test
    public void throttledAttemptIsReportedOnceAndNeverReplayedForQuotaPacing() throws Exception {
        final int[] attempts = {0};
        final int[] reported = {0};
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42", url -> {
            attempts[0]++;
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 429,
                    "{\"status\":{\"code\":429,\"description\":\"Too many requests\"}}");
            connection.responseHeaders.put("Retry-After", "30");
            return connection;
        });
        api.setRequestListener(() -> reported[0]++);

        try {
            api.listMessages("folder/one", 1, ZohoApi.PAGE_SIZE);
            fail("Expected Zoho throttle");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(429, ex.status);
        }

        assertEquals(1, attempts[0]);
        assertEquals(attempts[0], reported[0]);
    }

    @Test
    public void messageMetadataUsesDocumentedFolderScopedDetailsRoute() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{" +
                                "\"messageId\":\"98765\",\"folderId\":\"101\"}}"));

        ZohoApi.MessageMetadata metadata = api.getMessageMetadata("101", "98765");

        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/101/messages/98765/details",
                opened[0].getURL().toString());
        assertEquals("Zoho-oauthtoken access-fixture", opened[0].getRequestProperty("Authorization"));
        assertEquals("98765", metadata.messageId);
        assertEquals("101", metadata.folderId);
        assertEquals(null, metadata.seen);
        assertEquals(null, metadata.flagged);
    }

    @Test
    public void messageMetadataParsesOptionalReadAndFlagStateWithoutDefaultingMissingFields() throws Exception {
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                url -> new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{" +
                                "\"messageId\":\"98765\",\"folderId\":\"101\"," +
                                "\"status\":\"unread\",\"flagId\":2}}"));
        ZohoApi.MessageMetadata metadata = api.getMessageMetadata("101", "98765");
        assertEquals(Boolean.FALSE, metadata.seen);
        assertEquals(Boolean.TRUE, metadata.flagged);
    }

    @Test
    public void nullableOrUnknownDetailFlagsAreNotInferredAsUnflagged() throws Exception {
        for (String flag : new String[]{"null", "\"future-state\""}) {
            ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                    url -> new ZohoTestHttpsConnection(url, 200,
                            "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"101\",\"flagId\":" + flag + "}}"));
            assertEquals(null, api.getMessageMetadata("101", "98765").flagged);
        }
    }

    @Test
    public void listFoldersFetchesServerMetadataFromTheRegionalApi() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.in", "acct/42",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":[" +
                                "{\"folderId\":\"9001\",\"folderName\":\"Trash\",\"folderType\":\"Trash\"}]}"));

        List<ZohoApi.Folder> folders = api.listFolders();

        assertEquals("GET", opened[0].getRequestMethod());
        assertEquals("https://mail.zoho.in/api/accounts/acct%2F42/folders",
                opened[0].getURL().toString());
        assertEquals("Zoho-oauthtoken access-fixture", opened[0].getRequestProperty("Authorization"));
        assertEquals(1, folders.size());
        assertEquals("9001", folders.get(0).id);
        assertEquals("Trash", folders.get(0).name);
        assertEquals("Trash", folders.get(0).type);
        assertTrue(folders.get(0).selectable);
    }

    @Test
    public void multipleZohoMessagesWithSameProviderThreadIdShareTheConversationKey() throws Exception {
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":[" +
                                "{\"messageId\":\"101\",\"threadId\":\"shared-thread\"}," +
                                "{\"messageId\":\"102\",\"threadId\":\"shared-thread\"}]}"));
        ZohoApi.Page page = api.listMessages("1", 1, ZohoApi.PAGE_SIZE);

        assertEquals(2, page.messages.size());
        assertEquals("shared-thread", page.messages.get(0).threadId);
        assertEquals("shared-thread", page.messages.get(1).threadId);
        assertEquals(ZohoReceiver.getThreadKey(9L, "101", "shared-thread"),
                ZohoReceiver.getThreadKey(9L, "102", "shared-thread"));
        assertEquals("zoho:9:101", ZohoReceiver.getThreadKey(9L, "101", null));
    }

    @Test
    public void newSendUsesGenericRouteWhileReplyUsesDocumentedSourceRouteAndAction() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct/42", url -> {
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200,
                    "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"98765\"}}");
            opened.add(connection);
            return connection;
        });

        api.send(createSimpleMessage());
        api.send(createSimpleMessage(), "12345");

        assertEquals(2, opened.size());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/messages",
                opened.get(0).getURL().toString());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/messages/12345",
                opened.get(1).getURL().toString());
        JSONObject newMessage = new JSONObject(opened.get(0).requestBody.toString(StandardCharsets.UTF_8.name()));
        JSONObject reply = new JSONObject(opened.get(1).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertFalse(newMessage.has("action"));
        assertEquals("Reply", reply.getString("action"));
    }

    @Test
    public void messageMutationsUseDocumentedPayloadsAndFolderScopedDeleteRoutes() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                url -> {
                    ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(
                            url, 200, "{\"status\":{\"code\":200}}");
                    opened.add(connection);
                    return connection;
                });

        api.setMessageRead("98765", true);
        api.setMessageFlagged("101", "98765", true);
        api.setMessageFlagged("101", "98765", false);
        api.moveMessage("101", "98765", "202");
        api.deleteMessage("101", "98765", false);
        api.deleteMessage("303", "98765", true);
        api.emptyFolder("303");

        assertEquals(7, opened.size());
        JSONObject read = new JSONObject(opened.get(0).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("PUT", opened.get(0).getRequestMethod());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/updatemessage",
                opened.get(0).getURL().toString());
        assertEquals("markAsRead", read.getString("mode"));
        assertEquals(98765L, read.getJSONArray("messageId").getLong(0));

        JSONObject flag = new JSONObject(opened.get(1).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("setFlag", flag.getString("mode"));
        assertEquals("important", flag.getString("flagid"));
        assertTrue(flag.getBoolean("isFolderSpecific"));
        assertEquals(101L, flag.getLong("folderId"));
        JSONObject unflag = new JSONObject(opened.get(2).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("flag_not_set", unflag.getString("flagid"));

        JSONObject move = new JSONObject(opened.get(3).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("moveMessage", move.getString("mode"));
        assertEquals(101L, move.getLong("folderId"));
        assertEquals(202L, move.getLong("destfolderId"));

        assertEquals("DELETE", opened.get(4).getRequestMethod());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/101/messages/98765?expunge=false",
                opened.get(4).getURL().toString());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/303/messages/98765?expunge=true",
                opened.get(5).getURL().toString());

        JSONObject empty = new JSONObject(opened.get(6).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("PUT", opened.get(6).getRequestMethod());
        assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/303",
                opened.get(6).getURL().toString());
        assertEquals("emptyFolder", empty.getString("mode"));
        assertEquals("Zoho-oauthtoken access-fixture", opened.get(6).getRequestProperty("Authorization"));
    }

    @Test
    public void invalidMutationIdsAreRejectedBeforeOpeningARequest() throws Exception {
        int[] opened = {0};
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> {
                    opened[0]++;
                    return new ZohoTestHttpsConnection(url, 200, "{\"status\":{\"code\":200}}");
                });
        try {
            api.deleteMessage("folder", "opaque-id", true);
            fail("Zoho mutation IDs must be valid remote numeric IDs");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, opened[0]);
        }
    }

    @Test
    public void messageFlagMetadataRecognizesZohoStringFlagValues() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":[" +
                                "{\"messageId\":\"100\",\"flagId\":\"important\"}," +
                                "{\"messageId\":\"101\",\"flagId\":\"flag_not_set\"}] }"));
        ZohoApi.Page page = api.listMessages("1", 1, ZohoApi.PAGE_SIZE);
        assertEquals(2, page.resultItemCount);
        assertTrue(page.messages.get(0).flagged);
        assertFalse(page.messages.get(1).flagged);
    }

    @Test
    public void unauthorizedRequestRefreshesOnceAndUsesNewAccessToken() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("old-access", "https://www.zohoapis.eu", "acct",
                url -> {
                    int status = (opened.isEmpty() ? 401 : 200);
                    ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, status,
                            (status == 200 ? "{\"status\":{\"code\":200},\"data\":[{" +
                                    "\"accountId\":\"acct\",\"primaryEmailAddress\":\"a@example.invalid\"}]}" : ""));
                    opened.add(connection);
                    return connection;
                });
        api.setTokenRefresher(rejected -> {
            assertEquals("old-access", rejected);
            return "new-access";
        });

        assertEquals("a@example.invalid", api.listAccounts().get(0).email);
        assertEquals(2, opened.size());
        assertEquals(200, api.getLastHttpStatus());
        assertEquals("Zoho-oauthtoken old-access", opened.get(0).getRequestProperty("Authorization"));
        assertEquals("Zoho-oauthtoken new-access", opened.get(1).getRequestProperty("Authorization"));
    }

    @Test
    public void documentedMessageRoutesSeparateBodyHeadersAttachmentMetadataAndBytes() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        String logoSource = "/mail/ImageDisplay?imgWidth=160&cid=logo%40fixture.invalid";
        String badgeSource = "/mail/ImageDisplay?cid=badge%40fixture.invalid&imgWidth=80";
        String completeHtml = "<img src=\"/mail/ImageDisplay?imgWidth=160&amp;cid=logo%40fixture.invalid\">" +
                "<img src=\"/mail/ImageDisplay?cid=badge%40fixture.invalid&amp;imgWidth=80\">";
        String bodyResponse = "{\"status\":{\"code\":200},\"data\":{\"content\":" +
                JSONObject.quote(completeHtml) + "}}";
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct/42", url -> {
            String path = url.getPath();
            ZohoTestHttpsConnection connection;
            if (path.endsWith("/header"))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{\"headerContent\":" +
                                JSONObject.quote("From: sender@example.invalid\r\nSubject: test") + "}}");
            else if (path.endsWith("/content")) {
                connection = new ZohoTestHttpsConnection(url, 200, bodyResponse);
                connection.responseHeaders.put("Content-Length", Integer.toString(
                        bodyResponse.getBytes(StandardCharsets.UTF_8).length));
            }
            else if (path.endsWith("/attachmentinfo"))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{" +
                                "\"attachments\":[{\"attachmentId\":\"file-1\",\"attachmentName\":\"file.pdf\"," +
                                "\"attachmentSize\":4}]," +
                                "\"inline\":[" +
                                "{\"attachmentId\":\"inline-1\",\"attachmentName\":\"logo.png\"," +
                                "\"attachmentSize\":3,\"cid\":\"logo@fixture.invalid\"}," +
                                "{\"attachmentId\":\"inline-2\",\"attachmentName\":\"badge.jpg\"," +
                                "\"attachmentSize\":2,\"cid\":\"badge@fixture.invalid\"}]}}");
            else if (path.endsWith("/inline"))
                connection = new ZohoTestHttpsConnection(url, 200, "img");
            else if (path.contains("/attachments/"))
                connection = new ZohoTestHttpsConnection(url, 200, "file");
            else
                throw new AssertionError("Unexpected Zoho endpoint " + url);
            opened.add(connection);
            return connection;
        });

        String headers = api.getMessageHeaders("folder/1", "message/2");
        String body = api.getMessageContent("folder/1", "message/2");
        assertTrue(api.getLastContentRequestSequence() > 0);
        assertEquals(bodyResponse.getBytes(StandardCharsets.UTF_8).length,
                api.getLastResponseContentLength());
        assertEquals(bodyResponse.getBytes(StandardCharsets.UTF_8).length,
                api.getLastResponseBytes());
        List<NativeApiContent.Attachment> attachments =
                api.getMessageAttachments("folder/1", "message/2");
        java.io.File target = java.io.File.createTempFile("zoho-selected-attachment-", ".bin");
        java.io.File inlineTarget = java.io.File.createTempFile("zoho-selected-inline-", ".bin");
        java.io.File secondInlineTarget = java.io.File.createTempFile("zoho-selected-inline-2-", ".bin");
        try {
            assertEquals("Attachment-info must not fetch image bytes", 3, opened.size());
            api.downloadAttachment("folder/1", "message/2", "file/3", target);
            api.downloadInlineImage("folder/1", "message/2", "<logo@fixture.invalid>",
                    "logo.png", inlineTarget);
            api.downloadInlineImage("folder/1", "message/2", "<badge@fixture.invalid>",
                    "badge.jpg", secondInlineTarget);
            assertEquals("From: sender@example.invalid\r\nSubject: test", headers);
            assertEquals(completeHtml, body);
            assertEquals(3, attachments.size());
            assertEquals("file-1", attachments.get(0).id);
            assertEquals(ZohoApi.ORDINARY_ATTACHMENT_TYPE, attachments.get(0).providerType);
            assertEquals("Ordinary attachment MIME metadata remains provider-owned",
                    null, attachments.get(0).contentType);
            assertTrue("Sync metadata must not include ordinary attachment bytes",
                    attachments.get(0).inlineBytes == null);
            assertEquals("inline-1", attachments.get(1).id);
            assertEquals(ZohoApi.INLINE_ATTACHMENT_TYPE, attachments.get(1).providerType);
            assertEquals("logo@fixture.invalid", attachments.get(1).contentId);
            assertEquals("image/png", attachments.get(1).contentType);
            assertTrue(attachments.get(1).related);
            assertTrue("Sync metadata must not include inline image bytes",
                    attachments.get(1).inlineBytes == null);
            assertEquals("inline-2", attachments.get(2).id);
            assertEquals("badge@fixture.invalid", attachments.get(2).contentId);
            assertEquals("image/jpeg", attachments.get(2).contentType);
            assertTrue("Second inline metadata row must remain lazy", attachments.get(2).inlineBytes == null);

            MimeMessage synthetic = NativeApiContent.createMessage(
                    Session.getInstance(new Properties()), null, null,
                    "text/html; charset=UTF-8", completeHtml, attachments);
            Multipart mixed = (Multipart) synthetic.getContent();
            Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
            Part htmlPart = related.getBodyPart(0);
            assertEquals("text/html", new javax.mail.internet.ContentType(
                    htmlPart.getContentType()).getBaseType());
            assertEquals(completeHtml, htmlPart.getContent());
            Part inlinePart = related.getBodyPart(1);
            assertEquals("image/png", new javax.mail.internet.ContentType(
                    inlinePart.getContentType()).getBaseType());
            EntityAttachment stored = new EntityAttachment();
            NativeApiContent.applyRemoteMetadata(inlinePart, stored);
            stored.type = new javax.mail.internet.ContentType(inlinePart.getContentType()).getBaseType();
            stored.cid = inlinePart.getHeader("Content-ID")[0];
            stored.disposition = inlinePart.getDisposition();
            Part secondInlinePart = related.getBodyPart(2);
            EntityAttachment storedSecond = new EntityAttachment();
            NativeApiContent.applyRemoteMetadata(secondInlinePart, storedSecond);
            storedSecond.type = new javax.mail.internet.ContentType(
                    secondInlinePart.getContentType()).getBaseType();
            storedSecond.cid = secondInlinePart.getHeader("Content-ID")[0];
            storedSecond.disposition = secondInlinePart.getDisposition();
            EntityMessage syntheticMessage = new EntityMessage();
            syntheticMessage.zoho_id = "fixture-message";
            assertTrue(NativeApiContentOperations.matchesZohoCidReference(
                    syntheticMessage, stored, logoSource));
            assertTrue(NativeApiContentOperations.matchesZohoCidReference(
                    syntheticMessage, storedSecond, badgeSource));
            assertFalse(NativeApiContentOperations.matchesZohoCidReference(
                    syntheticMessage, stored, "https://images.example.test/logo.png"));
            assertTrue(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                    syntheticMessage, Collections.singletonList(stored),
                    logoSource, false, true, true));
            assertFalse(NativeApiContentOperations.shouldFetchZohoCidImagesOnOpen(
                    syntheticMessage, Collections.singletonList(stored),
                    logoSource, false, false, true));

            assertEquals("file", new String(java.nio.file.Files.readAllBytes(target.toPath()),
                    StandardCharsets.UTF_8));
            assertEquals("img", new String(java.nio.file.Files.readAllBytes(inlineTarget.toPath()),
                    StandardCharsets.UTF_8));
            assertEquals("img", new String(java.nio.file.Files.readAllBytes(secondInlineTarget.toPath()),
                    StandardCharsets.UTF_8));
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/header?raw=true",
                    opened.get(0).getURL().toString());
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/content?includeBlockContent=true",
                    opened.get(1).getURL().toString());
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/attachmentinfo?includeInline=true",
                    opened.get(2).getURL().toString());
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/attachments/file%2F3",
                    opened.get(3).getURL().toString());
            assertEquals("application/octet-stream", opened.get(3).getRequestProperty("Accept"));
            assertEquals("application/json", opened.get(3).getRequestProperty("Content-Type"));
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/inline?contentId=logo%40fixture.invalid&fileName=logo.png",
                    opened.get(4).getURL().toString());
            assertEquals("*/*", opened.get(4).getRequestProperty("Accept"));
            assertEquals(null, opened.get(4).getRequestProperty("Content-Type"));
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/messages/message%2F2/inline?contentId=badge%40fixture.invalid&fileName=badge.jpg",
                    opened.get(5).getURL().toString());
            assertEquals("*/*", opened.get(5).getRequestProperty("Accept"));
            assertEquals(null, opened.get(5).getRequestProperty("Content-Type"));
            assertEquals(6, opened.size());
            assertFalse("Ordinary sync and selected-attachment download must not request complete MIME",
                    opened.stream().anyMatch(connection -> connection.getURL().getPath().endsWith("/originalmessage")));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
            if (inlineTarget.exists())
                assertTrue(inlineTarget.delete());
            if (secondInlineTarget.exists())
                assertTrue(secondInlineTarget.delete());
        }
    }

    @Test
    public void inlineImageSuccessUsesBroadAcceptWithoutContentTypeAndWritesBinaryResponse() throws Exception {
        byte[] payload = new byte[]{0, 1, 2, 3, 4, 126};
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        java.io.File target = java.io.File.createTempFile("zoho-inline-success-", ".bin");
        try {
            ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                    url -> {
                        opened[0] = new ZohoTestHttpsConnection(url, 200,
                                new String(payload, StandardCharsets.ISO_8859_1));
                        opened[0].responseHeaders.put("Content-Type", "image/png");
                        return opened[0];
                    });

            api.downloadInlineImage("folder/1", "message/2", "<logo@example.test>",
                    "logo.png", target);

            assertEquals("GET", opened[0].getRequestMethod());
            assertEquals("*/*", opened[0].getRequestProperty("Accept"));
            assertEquals(null, opened[0].getRequestProperty("Content-Type"));
            assertEquals(0, opened[0].requestBody.size());
            assertTrue(java.util.Arrays.equals(payload,
                    java.nio.file.Files.readAllBytes(target.toPath())));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void inlineImageHttp406CapturesRawProviderBodyWithoutRequestCredentials() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        String errorBody = "{\"error\":{\"errorCode\":\"NOT_ACCEPTABLE\"," +
                "\"errorMessage\":\"private@example.test provider diagnostic\"}," +
                "\"status\":{\"code\":406,\"description\":\"Not Acceptable\"}}";
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42",
                url -> {
                    opened[0] = new ZohoTestHttpsConnection(url, 406, errorBody);
                    opened[0].responseHeaders.put("Content-Type", "application/json");
                    opened[0].responseHeaders.put("Content-Length",
                            Integer.toString(errorBody.getBytes(StandardCharsets.UTF_8).length));
                    return opened[0];
                });
        EntityAttachment metadataRow = new EntityAttachment();
        metadataRow.id = 987L;
        metadataRow.message = 123L;
        metadataRow.provider_id = "remote-inline-77";
        metadataRow.provider_folder = "folder/1";
        metadataRow.cid = "<logo@example.test>";
        java.io.File target = java.io.File.createTempFile("zoho-inline-error-", ".bin");
        try {
            try {
                api.downloadInlineImage("folder/1", "message/2", "<logo@example.test>",
                        "logo.png", target, metadataRow);
                fail("A rejected Zoho inline download must preserve the HTTP error");
            } catch (ZohoApi.ApiException expected) {
                assertEquals(406, expected.status);
                assertEquals("406", expected.errorCode);
                assertEquals("NOT_ACCEPTABLE", expected.errorDescription);
                assertEquals(errorBody.getBytes(StandardCharsets.UTF_8).length,
                        opened[0].errorStreamBytesRead);
                String diagnostic = ZohoApiDiagnostics.formatInlineHttpErrorExchange(4, "GET",
                        opened[0].getURL().getPath() + "?contentId=logo%40example.test&fileName=logo.png",
                        1, 406, 12, "application/json", opened[0].errorStreamBytesRead,
                        opened[0].getContentLength(), errorBody, "complete");
                assertTrue(diagnostic.contains("error_code=406 error_description=NOT_ACCEPTABLE"));
                assertTrue(diagnostic.contains("response_bytes=" +
                        errorBody.getBytes(StandardCharsets.UTF_8).length));
                assertTrue(diagnostic.contains("content_length_header=" +
                        errorBody.getBytes(StandardCharsets.UTF_8).length));
                assertTrue(diagnostic.contains("error.errorMessage:string"));
                assertTrue(diagnostic.contains("error_body_raw=" +
                        org.json.JSONObject.quote(errorBody)));
                assertTrue(diagnostic.contains("private@example.test"));
                assertFalse(diagnostic.contains("Authorization:"));
                assertFalse(diagnostic.contains("access-fixture"));
                assertFalse(diagnostic.contains("logo%40example.test"));
            }
            assertEquals("https://mail.zoho.eu/api/accounts/acct%2F42/folders/folder%2F1/" +
                            "messages/message%2F2/inline?contentId=logo%40example.test&fileName=logo.png",
                    opened[0].getURL().toString());
            assertEquals("GET", opened[0].getRequestMethod());
            assertEquals("*/*", opened[0].getRequestProperty("Accept"));
            assertEquals(null, opened[0].getRequestProperty("Content-Type"));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void originalMessageReturnsRawMimeBytes() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        String mime = "From: sender@example.invalid\r\nSubject: test\r\n" +
                "Content-Type: multipart/mixed; boundary=full\r\n\r\n" +
                "--full\r\nContent-Type: text/plain\r\n\r\nbody\r\n" +
                "--full\r\nContent-Type: application/octet-stream; name=\"file.bin\"\r\n" +
                "Content-Disposition: attachment; filename=\"file.bin\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n--full--\r\n";
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{\"content\":" +
                                JSONObject.quote(mime) + "}}"));
        assertEquals(mime, new String(api.getOriginalMessage("id/1"), StandardCharsets.UTF_8));
        assertTrue(opened[0].getURL().getPath().endsWith("/messages/id%2F1/originalmessage"));
    }

    @Test
    public void sendUploadsNamedTextAttachmentsAndSerializesZohoJsonPayload() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> {
                    int index = opened.size();
                    String response = (index == 0
                            ? "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"store-1\"," +
                                    "\"attachmentPath\":\"/Mail/a\",\"attachmentName\":\"note.txt\"}}"
                            : "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"202\"}}");
                    ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200, response);
                    opened.add(connection);
                    return connection;
                });

        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.invalid"));
        message.setRecipients(Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress("to@example.invalid"), new InternetAddress("other@example.invalid")});
        message.setRecipients(Message.RecipientType.CC,
                new InternetAddress[]{new InternetAddress("copy@example.invalid")});
        message.setSubject("Zoho API test", "UTF-8");
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setContent("<b>hello</b>", "text/html; charset=UTF-8");
        multipart.addBodyPart(body);
        final byte[] attachmentBytes = "attachment text".getBytes(StandardCharsets.UTF_8);
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(attachmentBytes);
            }

            @Override
            public int getSize() {
                return attachmentBytes.length;
            }
        };
        attachment.setText("attachment text", "UTF-8", "plain");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("note.txt");
        multipart.addBodyPart(attachment);
        message.setContent((Multipart) multipart);
        message.saveChanges();

        JSONObject response = api.send(message);
        assertEquals("202", ZohoApi.getSentMessageId(response));
        assertEquals(2, opened.size());
        assertTrue(opened.get(0).getURL().getPath().endsWith("/messages/attachments"));
        assertTrue(opened.get(0).getURL().getQuery().contains("fileName=note.txt"));
        assertTrue(opened.get(0).getURL().getQuery().contains("isInline=false"));
        assertEquals(ZohoUploadTimeouts.CONNECT_TIMEOUT_MILLIS, opened.get(0).getConnectTimeout());
        assertEquals(ZohoUploadTimeouts.READ_TIMEOUT_MILLIS, opened.get(0).getReadTimeout());
        assertEquals("Zoho-oauthtoken access", opened.get(0).getRequestProperty("Authorization"));
        assertEquals("attachment text", opened.get(0).requestBody.toString(StandardCharsets.UTF_8.name()));

        JSONObject payload = new JSONObject(opened.get(1).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("sender@example.invalid", payload.getString("fromAddress"));
        assertEquals("to@example.invalid,other@example.invalid", payload.getString("toAddress"));
        assertEquals("copy@example.invalid", payload.getString("ccAddress"));
        assertEquals("Zoho API test", payload.getString("subject"));
        assertEquals("<b>hello</b>", payload.getString("content"));
        assertEquals("html", payload.getString("mailFormat"));
        JSONArray attachments = payload.getJSONArray("attachments");
        assertEquals(1, attachments.length());
        assertEquals("store-1", attachments.getJSONObject(0).getString("storeName"));
        assertEquals("/Mail/a", attachments.getJSONObject(0).getString("attachmentPath"));
        assertEquals("note.txt", attachments.getJSONObject(0).getString("attachmentName"));
        assertFalse(payload.getString("content").contains("attachment text"));
    }

    @Test
    public void unknownSizeAttachmentIsUploadedWithFixedLengthRawBody() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            int index = opened.size();
            String response = (index == 0
                    ? "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"store-1\"," +
                            "\"attachmentPath\":\"/Mail/signature\",\"attachmentName\":\"signature.asc\"}}"
                    : "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"203\"}}");
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200, response);
            opened.add(connection);
            return connection;
        });
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.invalid"));
        message.setRecipients(Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress("to@example.invalid")});
        message.setSubject("Unknown size upload test", "UTF-8");
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("signed body", "UTF-8");
        multipart.addBodyPart(body);
        final byte[] attachmentBytes = "signature bytes".getBytes(StandardCharsets.UTF_8);
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(attachmentBytes);
            }

            @Override
            public int getSize() {
                return -1;
            }
        };
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("signature.asc");
        multipart.addBodyPart(attachment);
        message.setContent((Multipart) multipart);
        message.saveChanges();

        api.send(message);

        assertEquals(2, opened.size());
        assertEquals(attachmentBytes.length, opened.get(0).fixedLengthStreamingMode);
        assertEquals(-1, opened.get(0).chunkedStreamingMode);
        assertEquals("application/octet-stream", opened.get(0).getRequestProperty("Content-Type"));
        assertArrayEquals(attachmentBytes, opened.get(0).requestBody.toByteArray());
        assertEquals("https://mail.zoho.eu/api/accounts/acct/messages/attachments?fileName=signature.asc&isInline=false",
                opened.get(0).getURL().toString());
    }

    @Test
    public void detachedPgpSignatureUploadsAsNormalAttachmentAndKeepsSignedMimePart() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        final byte[] signatureBytes =
                "-----BEGIN PGP SIGNATURE-----\r\nmock-signature\r\n-----END PGP SIGNATURE-----\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            int index = opened.size();
            String response = (index == 0
                    ? "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"store-signature\"," +
                            "\"attachmentPath\":\"/Mail/signature.asc\",\"attachmentName\":\"signature.asc\"}}"
                    : "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"204\"}}");
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200, response);
            opened.add(connection);
            return connection;
        });

        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.invalid"));
        message.setRecipients(Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress("to@example.invalid")});
        message.setSubject("PGP signed attachment test", "UTF-8");
        MimeMultipart signed = new MimeMultipart("signed");
        MimeBodyPart content = new MimeBodyPart();
        content.setText("signed body", "UTF-8");
        signed.addBodyPart(content);
        MimeBodyPart signature = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(signatureBytes);
            }

            @Override
            public int getSize() {
                return signatureBytes.length;
            }
        };
        signature.setHeader("Content-Type", "application/pgp-signature");
        signature.setFileName("signature.asc");
        signature.setDisposition(Part.INLINE);
        signed.addBodyPart(signature);
        message.setContent(signed);
        message.saveChanges();
        javax.mail.internet.ContentType signedType =
                new javax.mail.internet.ContentType(message.getContentType());
        signedType.setParameter("protocol", "application/pgp-signature");
        signedType.setParameter("micalg", "pgp-sha256");
        message.setHeader("Content-Type", signedType.toString());

        javax.mail.internet.ContentType beforeSend =
                new javax.mail.internet.ContentType(message.getContentType());
        assertEquals("multipart/signed", beforeSend.getBaseType());
        assertEquals("application/pgp-signature", beforeSend.getParameter("protocol"));
        assertEquals("pgp-sha256", beforeSend.getParameter("micalg"));
        Part originalSignature = ((Multipart) message.getContent()).getBodyPart(1);
        assertTrue(originalSignature.isMimeType("application/pgp-signature"));
        assertEquals("signature.asc", originalSignature.getFileName());
        assertEquals(Part.INLINE, originalSignature.getDisposition());
        assertTrue(originalSignature.getHeader("Content-ID") == null);

        JSONObject sent = api.send(message);

        assertEquals("204", ZohoApi.getSentMessageId(sent));
        assertEquals(2, opened.size());
        assertEquals("https://mail.zoho.eu/api/accounts/acct/messages/attachments?fileName=signature.asc&isInline=false",
                opened.get(0).getURL().toString());
        assertEquals(signatureBytes.length, opened.get(0).fixedLengthStreamingMode);
        assertEquals(-1, opened.get(0).chunkedStreamingMode);
        assertEquals("application/octet-stream", opened.get(0).getRequestProperty("Content-Type"));
        assertArrayEquals(signatureBytes, opened.get(0).requestBody.toByteArray());

        JSONObject sendPayload = new JSONObject(
                opened.get(1).requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("signed body", sendPayload.getString("content"));
        JSONArray attachments = sendPayload.getJSONArray("attachments");
        assertEquals(1, attachments.length());
        assertEquals("store-signature", attachments.getJSONObject(0).getString("storeName"));
        assertEquals("/Mail/signature.asc", attachments.getJSONObject(0).getString("attachmentPath"));
        assertEquals("signature.asc", attachments.getJSONObject(0).getString("attachmentName"));

        javax.mail.internet.ContentType afterSend =
                new javax.mail.internet.ContentType(message.getContentType());
        assertEquals("multipart/signed", afterSend.getBaseType());
        assertEquals("application/pgp-signature", afterSend.getParameter("protocol"));
        assertEquals("pgp-sha256", afterSend.getParameter("micalg"));
        Part finalSignature = ((Multipart) message.getContent()).getBodyPart(1);
        assertTrue(finalSignature.isMimeType("application/pgp-signature"));
        assertEquals(Part.INLINE, finalSignature.getDisposition());
    }

    @Test
    public void nonPgpInlinePartsKeepInlineUploadSemantics() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        final byte[] imageBytes = new byte[]{1, 2, 3};
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            int index = opened.size();
            String response = (index == 0
                    ? "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"store-inline\"," +
                            "\"attachmentPath\":\"/compose/logo.png\",\"attachmentName\":\"logo.png\"}}"
                    : index == 1
                            ? "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"store-smime\"," +
                                    "\"attachmentPath\":\"/compose/smime.p7s\",\"attachmentName\":\"smime.p7s\"}}"
                            : "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"205\"}}");
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200, response);
            opened.add(connection);
            return connection;
        });

        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.invalid"));
        message.setRecipients(Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress("to@example.invalid")});
        message.setSubject("CID inline preservation test", "UTF-8");
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<img src=\"cid:logo@fixture.invalid\">", "text/html; charset=UTF-8");
        related.addBodyPart(html);
        MimeBodyPart image = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(imageBytes);
            }

            @Override
            public int getSize() {
                return imageBytes.length;
            }
        };
        image.setHeader("Content-Type", "image/png");
        image.setFileName("logo.png");
        image.setDisposition(Part.INLINE);
        image.setHeader("Content-ID", "<logo@fixture.invalid>");
        related.addBodyPart(image);
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart relatedContainer = new MimeBodyPart();
        relatedContainer.setContent(related);
        mixed.addBodyPart(relatedContainer);
        final byte[] smimeBytes = new byte[]{4, 5, 6};
        MimeBodyPart smimeSignature = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(smimeBytes);
            }

            @Override
            public int getSize() {
                return smimeBytes.length;
            }
        };
        smimeSignature.setHeader("Content-Type", "application/pkcs7-signature");
        smimeSignature.setFileName("smime.p7s");
        smimeSignature.setDisposition(Part.INLINE);
        mixed.addBodyPart(smimeSignature);
        message.setContent(mixed);
        message.saveChanges();

        api.send(message);

        assertEquals(3, opened.size());
        assertEquals("https://mail.zoho.eu/api/accounts/acct/messages/attachments?fileName=logo.png&isInline=true",
                opened.get(0).getURL().toString());
        assertEquals("https://mail.zoho.eu/api/accounts/acct/messages/attachments?fileName=smime.p7s&isInline=true",
                opened.get(1).getURL().toString());
        JSONObject sendPayload = new JSONObject(
                opened.get(2).requestBody.toString(StandardCharsets.UTF_8.name()));
        JSONArray attachments = sendPayload.getJSONArray("attachments");
        assertEquals(2, attachments.length());
        assertEquals("logo.png", attachments.getJSONObject(0).getString("attachmentName"));
        assertEquals("smime.p7s", attachments.getJSONObject(1).getString("attachmentName"));
    }

    @Test
    public void ambiguousFinalMessagePostTimeoutIsNotRetried() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200, "") {
                @Override
                public int getResponseCode() throws IOException {
                    throw new SocketTimeoutException("private transport detail");
                }
            };
            opened.add(connection);
            return connection;
        });

        try {
            api.send(createSimpleMessage());
            fail("An ambiguous response timeout must fail the send");
        } catch (ZohoApi.SendOutcomeUnknownException expected) {
            assertEquals(-1, expected.status);
            assertTrue(expected.getCause() instanceof SocketTimeoutException);
        }

        assertEquals(1, opened.size());
        assertEquals("POST", opened.get(0).getRequestMethod());
        assertEquals("https://mail.zoho.eu/api/accounts/acct/messages",
                opened.get(0).getURL().toString());
        assertTrue(opened.get(0).requestBody.size() > 0);
    }

    @Test
    public void finalMessagePostHttpFiveHundredIsClassifiedUnknownAndNotRetried() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 500,
                    "{\"code\":\"INTERNAL_ERROR\"}");
            opened.add(connection);
            return connection;
        });

        try {
            api.send(createSimpleMessage());
            fail("A final-send 5xx may follow acceptance and must be classified as unknown");
        } catch (ZohoApi.SendOutcomeUnknownException expected) {
            assertEquals(500, expected.status);
        }

        assertEquals(1, opened.size());
        assertTrue(opened.get(0).requestBody.size() > 0);
    }

    @Test
    public void zeroByteAttachmentFailsBeforeOpeningUploadRequest() throws Exception {
        int[] opened = {0};
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> {
                    opened[0]++;
                    return new ZohoTestHttpsConnection(url, 200,
                            "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"fixture\"," +
                                    "\"attachmentPath\":\"/Mail/fixture\",\"attachmentName\":\"empty.txt\"}}");
                });

        MimeMessage message = createSimpleMessage();
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("test body", "UTF-8");
        multipart.addBodyPart(text);
        final byte[] nonEmptyBytes = "keep this attachment".getBytes(StandardCharsets.UTF_8);
        MimeBodyPart nonEmpty = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(nonEmptyBytes);
            }

            @Override
            public int getSize() {
                return nonEmptyBytes.length;
            }
        };
        nonEmpty.setText("keep this attachment", "UTF-8");
        nonEmpty.setDisposition(Part.ATTACHMENT);
        nonEmpty.setFileName("first.txt");
        multipart.addBodyPart(nonEmpty);
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public int getSize() {
                return 0;
            }
        };
        attachment.setText("", "UTF-8");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("empty.txt");
        multipart.addBodyPart(attachment);
        message.setContent((Multipart) multipart);
        message.saveChanges();

        try {
            api.send(message);
            fail("A zero-byte attachment must be rejected before upload");
        } catch (IOException expected) {
            assertEquals(ZohoApi.ZERO_BYTE_ATTACHMENT_ERROR, expected.getMessage());
            assertTrue(expected.getMessage().contains("Remove the empty attachment"));
            assertTrue(expected.getMessage().contains("non-empty file"));
        }
        assertEquals(0, opened[0]);
    }

    @Test
    public void httpFailuresExposeOnlyStatusNotResponseBody() throws Exception {
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 403, "private response text"));
        try {
            api.listAccounts();
            fail("HTTP 403 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(403, ex.status);
            assertEquals(403, api.getLastHttpStatus());
            assertFalse(ex.getMessage().contains("private response text"));
        }
    }

    @Test
    public void rateLimitResponseHonorsRetryAfterAndSaturatesLargeValues() throws Exception {
        for (String retryAfter : new String[]{"5", "9223372036854776"}) {
            ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                    url -> {
                        ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(
                                url, 429, "private response text");
                        connection.responseHeaders.put("Retry-After", retryAfter);
                        return connection;
                    });
            try {
                api.listAccounts();
                fail("HTTP 429 should fail");
            } catch (ZohoApi.ApiException ex) {
                assertEquals(429, ex.status);
                assertTrue(ex.isRateLimitFailure());
                assertEquals(("5".equals(retryAfter) ? 5000L : Long.MAX_VALUE), ex.retryAfterMs);
            }
        }
    }

    @Test
    public void http403UsesValidRetryAfterPresenceRatherThanPositiveDelayForRateLimitClassification()
            throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String futureDate = format.format(new Date(System.currentTimeMillis() + 120000L));
        String pastDate = format.format(new Date(System.currentTimeMillis() - 120000L));

        ZohoApi.ApiException future = getZoho403Exception(futureDate);
        assertEquals(403, future.status);
        assertTrue(future.hasValidRetryAfter);
        assertTrue(future.retryAfterMs > 0);
        assertTrue(future.isRateLimitFailure());

        for (String retryAfter : new String[]{pastDate, "0"}) {
            ZohoApi.ApiException zeroDelay = getZoho403Exception(retryAfter);
            assertTrue(zeroDelay.hasValidRetryAfter);
            assertEquals(0L, zeroDelay.retryAfterMs);
            assertTrue(zeroDelay.isRateLimitFailure());
        }

        for (String retryAfter : new String[]{null, "not-a-date"}) {
            ZohoApi.ApiException ordinary = getZoho403Exception(retryAfter);
            assertFalse(ordinary.hasValidRetryAfter);
            assertEquals(RetryAfter.UNAVAILABLE, ordinary.retryAfterMs);
            assertFalse(ordinary.isRateLimitFailure());
        }
    }

    private static ZohoApi.ApiException getZoho403Exception(String retryAfter) throws Exception {
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> {
                    ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(
                            url, 403, "private response text");
                    if (retryAfter != null)
                        connection.responseHeaders.put("Retry-After", retryAfter);
                    return connection;
                });
        try {
            api.listAccounts();
            fail("HTTP 403 should fail");
        } catch (ZohoApi.ApiException expected) {
            return expected;
        }
        throw new AssertionError("HTTP 403 did not produce an API exception");
    }

    @Test
    public void sendFailureIncludesOnlyAllowlistedZohoErrorFields() throws Exception {
        JSONObject status = new JSONObject();
        status.put("code", 400);
        status.put("description", "BAD REQUEST - The input parameters provided in the request are invalid or incorrect. " +
                "The requester must modify the request and resend the request.");
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("fromAddress", "private-sender@example.invalid");
        body.put("toAddress", "private-recipient@example.invalid");
        body.put("subject", "private-subject");
        body.put("content", "private-message-body");
        body.put("messageId", "private-message-id");
        body.put("folderId", "private-folder-id");
        body.put("access_token", "private-access-token");
        body.put("client_secret", "private-client-secret");

        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, body.toString()));
        try {
            api.send(createSimpleMessage());
            fail("HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(400, ex.status);
            assertEquals("400", ex.errorCode);
            assertEquals("BAD_REQUEST", ex.errorDescription);
            assertEquals("send", ex.requestStage);
            assertTrue(ex.getMessage().contains("Zoho Mail API HTTP 400 stage=send code=400 description=BAD_REQUEST"));

            // These forms are used by exception loggers and persisted error summaries.
            String logged = ex.toString() + " " + ex.getMessage() + " " + ex.getLocalizedMessage();
            assertPrivateValuesAbsent(logged);
        }
    }

    @Test
    public void sendFailureRejectsDynamicDescriptionAndKeepsOnlySafeCode() throws Exception {
        JSONObject status = new JSONObject();
        status.put("code", 400);
        status.put("description", "BAD REQUEST for private-recipient@example.invalid, private-message-id");
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("content", "private-message-body");
        body.put("access_token", "private-access-token");

        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, body.toString()));
        try {
            api.send(createSimpleMessage());
            fail("HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals("400", ex.errorCode);
            assertEquals(null, ex.errorDescription);
            assertEquals("Zoho Mail API HTTP 400 stage=send code=400", ex.getMessage());
            assertPrivateValuesAbsent(ex.toString() + " " + ex.getMessage());
        }
    }

    @Test
    public void malformedSendErrorRetainsStatusAndStageAndHttp400IsPermanent() throws Exception {
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, "private malformed response"));
        try {
            api.send(createSimpleMessage());
            fail("HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals("Zoho Mail API HTTP 400 stage=send", ex.getMessage());
            assertEquals(null, ex.getCause());
            assertPrivateValuesAbsent(ex.toString() + " " + ex.getMessage());
            assertTrue(ex.isPermanentRequestFailure());
        }

        assertFalse(new ZohoApi.ApiException(500, 0).isPermanentRequestFailure());
    }

    @Test
    public void staleMessagePredicateScopesInvalidInputToFolderMetadataReads() throws Exception {
        // A metadata probe can use Zoho's documented 400 "invalid input" as absence, but a write
        // with the same response is a rejected mutation and must not be silently discarded.
        assertTrue(new ZohoApi.ApiException(404, 0).isMessageAbsentFromFolder());
        String invalidInput = "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}";
        ZohoApi metadataApi = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, invalidInput));
        try {
            metadataApi.getMessageMetadata("101", "98765");
            fail("Expected stale folder/message pair");
        } catch (ZohoApi.ApiException expected) {
            assertTrue(expected.isMessageAbsentFromFolder());
        }

        assertFalse(zohoFailure(400, invalidInput).isMessageAbsentFromFolder());
        assertMutationInvalidInputIsNotAbsence(api -> api.setMessageRead("98765", true));
        assertMutationInvalidInputIsNotAbsence(api -> api.setMessageFlagged("101", "98765", true));
        assertMutationInvalidInputIsNotAbsence(api -> api.moveMessage("101", "98765", "202"));
        assertFalse(zohoFailure(400,
                "{\"status\":{\"code\":400,\"description\":\"Bad Request\"}}")
                .isMessageAbsentFromFolder());
        assertFalse(zohoFailure(400, "{}").isMessageAbsentFromFolder());
        assertFalse(new ZohoApi.ApiException(400, 0).isMessageAbsentFromFolder());
        for (int status : new int[]{401, 403, 406, 429, 500}) {
            assertFalse("status " + status + " is not folder absence",
                    new ZohoApi.ApiException(status, 0).isMessageAbsentFromFolder());
        }
    }

    private interface MutationCall {
        void run(ZohoApi api) throws Exception;
    }

    private static void assertMutationInvalidInputIsNotAbsence(MutationCall call) throws Exception {
        String body = "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}";
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, body));
        try {
            call.run(api);
            fail("Expected rejected mutation");
        } catch (ZohoApi.ApiException expected) {
            assertFalse(expected.isMessageAbsentFromFolder());
        }
    }

    private static ZohoApi.ApiException zohoFailure(int status, String body) throws Exception {
        ZohoApi api = new ZohoApi("access", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, status, body));
        try {
            api.listAccounts();
            throw new AssertionError("Expected HTTP " + status);
        } catch (ZohoApi.ApiException ex) {
            return ex;
        }
    }

    @Test
    public void attachmentUploadFailureUsesSafeStageAndAllowlistedFields() throws Exception {
        JSONObject status = new JSONObject();
        status.put("code", 400);
        status.put("description", "BAD REQUEST");
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("content", "private-message-body");
        body.put("toAddress", "private-recipient@example.invalid");
        body.put("messageId", "private-message-id");
        body.put("access_token", "private-access-token");

        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, body.toString()));
        try {
            api.send(createMessageWithAttachment());
            fail("Attachment upload HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(400, ex.status);
            assertEquals("attachment_upload", ex.requestStage);
            assertEquals("400", ex.errorCode);
            assertEquals("BAD_REQUEST", ex.errorDescription);
            assertTrue(ex.getMessage().contains("stage=attachment_upload"));
            assertPrivateValuesAbsent(ex.toString() + " " + ex.getMessage());
        }
    }

    @Test
    public void attachmentUploadFailureCapturesSanitizedProviderErrorFields() throws Exception {
        JSONObject status = new JSONObject();
        status.put("code", 400);
        status.put("description", "BAD REQUEST");
        JSONObject error = new JSONObject();
        error.put("errorCode", "invalid-attachment");
        error.put("errorMessage", "Unsupported attachment type");
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("error", error);
        body.put("toAddress", "private-recipient@example.invalid");
        body.put("access_token", "private-access-token");
        body.put("signature", "-----BEGIN PGP SIGNATURE-----\nprivate-signature-content");

        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 400, body.toString()));
        try {
            api.send(createMessageWithAttachment());
            fail("Attachment upload HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(400, ex.status);
            assertEquals("attachment_upload", ex.requestStage);
            assertEquals("400", ex.errorCode);
            assertEquals("INVALID_ATTACHMENT", ex.providerErrorCode);
            assertEquals("Unsupported attachment type", ex.errorDescription);
            assertTrue(ex.getMessage().contains("error_code=INVALID_ATTACHMENT"));
            assertPrivateValuesAbsent(ex.toString() + " " + ex.getMessage());
            assertFalse(ex.getMessage().contains("BEGIN PGP SIGNATURE"));

            String log = ZohoApiDiagnostics.formatExchange(17, "POST",
                    "/api/accounts/private-account/messages/attachments?fileName=signature.asc&isInline=true",
                    1, 400, 10, "application/octet-stream", 265,
                    "application/json", -1, "http_error", null, "attachment_upload",
                    ex.providerErrorCode, ex.errorDescription);
            assertTrue(log.contains("error_code=INVALID_ATTACHMENT"));
            assertTrue(log.contains("error_description=Unsupported attachment type"));
            assertPrivateValuesAbsent(log);
            assertFalse(log.contains("BEGIN PGP SIGNATURE"));
            assertFalse(log.contains("private-signature-content"));
            assertFalse(log.contains(body.toString()));
        }
        assertTrue(opened[0] != null);
    }

    @Test
    public void attachmentUploadFailureDropsUnsafeProviderFields() throws Exception {
        JSONObject status = new JSONObject();
        status.put("code", 400);
        status.put("description", "BAD REQUEST");
        JSONObject error = new JSONObject();
        error.put("errorCode", "private-access-token-value");
        error.put("errorMessage", "Rejected for private-recipient@example.invalid " +
                "Authorization: Zoho-oauthtoken private-access-token -----BEGIN PGP SIGNATURE-----");
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("error", error);

        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> new ZohoTestHttpsConnection(url, 400, body.toString()));
        try {
            api.send(createMessageWithAttachment());
            fail("Attachment upload HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals("400", ex.errorCode);
            assertEquals(null, ex.providerErrorCode);
            assertEquals("BAD_REQUEST", ex.errorDescription);
            assertPrivateValuesAbsent(ex.toString() + " " + ex.getMessage());
            assertFalse(ex.getMessage().contains("Authorization"));
            assertFalse(ex.getMessage().contains("Zoho-oauthtoken"));
            assertFalse(ex.getMessage().contains("BEGIN PGP SIGNATURE"));
        }
    }

    @Test
    public void attachmentUploadErrorResponseReadIsBoundedToFourKilobytes() throws Exception {
        StringBuilder response = new StringBuilder(
                "{\"status\":{\"code\":400},\"error\":{\"errorCode\":\"INVALID_ATTACHMENT\"," +
                        "\"errorMessage\":\"Unsupported attachment type\"},\"padding\":\"");
        for (int i = 0; i < 6000; i++)
            response.append('x');
        response.append("\"}");
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct",
                url -> opened[0] = new ZohoTestHttpsConnection(url, 400, response.toString()));
        try {
            api.send(createMessageWithAttachment());
            fail("Attachment upload HTTP 400 should fail");
        } catch (ZohoApi.ApiException ex) {
            assertEquals(400, ex.status);
            assertEquals("attachment_upload", ex.requestStage);
            assertEquals(null, ex.errorCode);
            assertEquals(null, ex.providerErrorCode);
            assertEquals(null, ex.errorDescription);
            assertFalse(ex.getMessage().contains("INVALID_ATTACHMENT"));
        }
        assertTrue(opened[0].errorStreamBytesRead <= 4 * 1024 + 1);
        assertTrue(opened[0].errorStreamBytesRead < response.length());
    }

    @Test
    public void laterZohoAttachmentUploadFailureStopsBeforeSendWithoutRetryingOrInventingCleanup() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct", url -> {
            int index = opened.size();
            ZohoTestHttpsConnection connection = (index == 0
                    ? new ZohoTestHttpsConnection(url, 200,
                            "{\"status\":{\"code\":200},\"data\":{\"storeName\":\"1\"," +
                                    "\"attachmentName\":\"a.txt\",\"attachmentPath\":\"/Mail/a\"}}")
                    : new ZohoTestHttpsConnection(url, 400,
                            "{\"status\":{\"code\":400,\"description\":\"BAD REQUEST\"}}"));
            opened.add(connection);
            return connection;
        });

        try {
            api.send(createMessageWithTwoAttachments());
            fail("The second attachment upload should stop the send");
        } catch (ZohoApi.ApiException expected) {
            assertEquals("attachment_upload", expected.requestStage);
            assertEquals(400, expected.status);
        }

        assertEquals(2, opened.size());
        for (ZohoTestHttpsConnection connection : opened) {
            assertEquals("POST", connection.getRequestMethod());
            assertTrue(connection.getURL().getPath().endsWith("/messages/attachments"));
        }
    }

    @Test
    public void successfulZohoResponseWithoutMessageIdCanStillBeMatchedToAUniqueSentCopy() throws Exception {
        JSONObject response = new JSONObject("{\"status\":{\"code\":200},\"data\":{}} ");
        assertEquals(null, ZohoApi.getSentMessageId(response));
        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.id = 3L;
        sent.account = 8L;
        EntityMessage local = new EntityMessage();
        local.account = 8L;
        local.folder = 3L;
        local.msgid = "<send-id@example.invalid>";
        local.sent = 100000L;

        assertSame(local, NativeSentMessageMatcher.findUnique(Collections.singletonList(local),
                8L, sent, "<send-id@example.invalid>", 100100L));
    }

    @Test
    public void successfulZohoResponseCanLinkWithoutASentFolderRemoteMapping() throws Exception {
        JSONObject response = new JSONObject("{\"status\":{\"code\":200},\"data\":{\"messageId\":\"98765\"}} ");
        assertEquals("98765", ZohoApi.getSentMessageId(response));
        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.id = 3L;
        sent.account = 8L;
        assertEquals(null, sent.zoho_folder_id);
        assertEquals(null, ZohoMailMutator.getSentFolderMembership(sent));
        sent.zoho_folder_id = "123";
        assertEquals("|123|", ZohoMailMutator.getSentFolderMembership(sent));
        assertEquals(null, ZohoMailMutator.getSentFolderMembership(null));
    }

    @Test
    public void controlSendsAndBodiesGetProviderSizedTimeoutBudgets() throws Exception {
        List<ZohoTestHttpsConnection> opened = new ArrayList<>();
        ZohoApi api = new ZohoApi("access-fixture", "https://www.zohoapis.eu", "acct/42", url -> {
            String target = url.toString();
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200,
                    target.contains("/attachments/")
                            ? "attachment bytes"
                            : (target.endsWith("/originalmessage")
                                    ? "{\"status\":{\"code\":200},\"data\":{\"content\":\"Subject: fixture\"}}"
                                    : "{\"status\":{\"code\":200},\"data\":[]}"));
            opened.add(connection);
            return connection;
        });

        api.listMessages("folder/one", 1, ZohoApi.PAGE_SIZE);
        assertEquals(ZohoTimeouts.CONNECT_MILLIS, opened.get(0).getConnectTimeout());
        assertEquals(ZohoTimeouts.CONTROL_READ_MILLIS, opened.get(0).getReadTimeout());

        // A send must outlast the control-plane budget: Gmail and Graph both allow 90 seconds.
        api.send(createSimpleMessage());
        assertEquals(ZohoTimeouts.SEND_READ_MILLIS, opened.get(1).getReadTimeout());

        api.getOriginalMessage("message/1");
        assertEquals(ZohoTimeouts.BODY_READ_MILLIS, opened.get(2).getReadTimeout());

        File target = File.createTempFile("zoho-timeout", ".bin");
        try {
            api.downloadAttachment("folder/1", "message/2", "file/3", target);
            assertEquals(ZohoTimeouts.BODY_READ_MILLIS, opened.get(3).getReadTimeout());
        } finally {
            assertTrue(target.delete());
        }
    }

    private static MimeMessage createSimpleMessage() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.invalid"));
        message.setRecipients(Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress("recipient@example.invalid")});
        message.setSubject("test subject", "UTF-8");
        message.setText("test body", "UTF-8");
        message.saveChanges();
        return message;
    }

    private static MimeMessage createMessageWithAttachment() throws Exception {
        MimeMessage message = createSimpleMessage();
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("test body", "UTF-8");
        multipart.addBodyPart(text);

        final byte[] attachmentBytes = "private attachment content".getBytes(StandardCharsets.UTF_8);
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(attachmentBytes);
            }

            @Override
            public int getSize() {
                return attachmentBytes.length;
            }
        };
        attachment.setText(new String(attachmentBytes, StandardCharsets.UTF_8), "UTF-8", "plain");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("private-attachment-name.txt");
        multipart.addBodyPart(attachment);
        message.setContent((Multipart) multipart);
        message.saveChanges();
        return message;
    }

    private static MimeMessage createMessageWithTwoAttachments() throws Exception {
        MimeMessage message = createSimpleMessage();
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("test body", "UTF-8");
        multipart.addBodyPart(text);
        for (String name : new String[]{"a.txt", "b.txt"}) {
            final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            MimeBodyPart attachment = new MimeBodyPart() {
                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public int getSize() {
                    return bytes.length;
                }
            };
            attachment.setText(new String(bytes, StandardCharsets.UTF_8), "UTF-8", "plain");
            attachment.setDisposition(Part.ATTACHMENT);
            attachment.setFileName(name);
            multipart.addBodyPart(attachment);
        }
        message.setContent((Multipart) multipart);
        message.saveChanges();
        return message;
    }

    private static void assertPrivateValuesAbsent(String text) {
        assertFalse(text.contains("private-sender@example.invalid"));
        assertFalse(text.contains("private-recipient@example.invalid"));
        assertFalse(text.contains("private-subject"));
        assertFalse(text.contains("private-message-body"));
        assertFalse(text.contains("private-message-id"));
        assertFalse(text.contains("private-folder-id"));
        assertFalse(text.contains("private-access-token"));
        assertFalse(text.contains("private-client-secret"));
        assertFalse(text.contains("private attachment content"));
        assertFalse(text.contains("private-attachment-name.txt"));
        assertFalse(text.contains("private malformed response"));
    }
}
