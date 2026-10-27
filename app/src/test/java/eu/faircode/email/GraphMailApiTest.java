package eu.faircode.email;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraphMailApiTest {
    @Test
    public void listsAndRecursivelyDiscoversFoldersWithProviderPaging() throws Exception {
        String root = GraphMailApi.BASE + "/me/mailFolders?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount&$top=100";
        String rootNext = GraphMailApi.BASE + "/me/mailFolders?opaque=next%2Ffolder+page";
        String child = GraphMailApi.BASE + "/me/mailFolders/inbox/childFolders?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount&$top=100";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (url.toString().equals(root))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"inbox\",\"displayName\":\"Inbox\",\"parentFolderId\":\"msgfolderroot\",\"childFolderCount\":1,\"totalItemCount\":42,\"unreadItemCount\":7}],\"@odata.nextLink\":\"" + rootNext + "\"}");
            if (url.toString().equals(rootNext))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"archive\",\"displayName\":\"Archive\",\"parentFolderId\":\"msgfolderroot\",\"childFolderCount\":0}]}");
            if (url.toString().equals(child))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"project\",\"displayName\":\"Projects\",\"parentFolderId\":\"inbox\",\"childFolderCount\":0}]}");
            throw new AssertionError("Unexpected folder URL " + url);
        });

        List<GraphMailApi.MailFolder> folders = api.listFolders();

        assertEquals(Arrays.asList(root, rootNext, child), requested);
        assertEquals(3, folders.size());
        assertEquals("inbox", folders.get(0).id);
        assertEquals("project", folders.get(2).id);
        assertEquals("inbox", folders.get(2).parentId);
        assertEquals(Integer.valueOf(42), folders.get(0).totalItemCount);
        assertEquals(Integer.valueOf(7), folders.get(0).unreadItemCount);
    }

    @Test
    public void readsRemoteFolderCountsWithoutListingMessages() throws Exception {
        String expected = GraphMailApi.BASE + "/me/mailFolders/folder%2Fid?" +
                "$select=id,totalItemCount,unreadItemCount";
        final String[] actual = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            actual[0] = url.toString();
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"id\":\"folder/id\",\"totalItemCount\":105,\"unreadItemCount\":12}");
        });

        GraphMailApi.FolderStats stats = api.getFolderStats("folder/id");

        assertEquals(expected, actual[0]);
        assertEquals(Integer.valueOf(105), stats.totalItemCount);
        assertEquals(Integer.valueOf(12), stats.unreadItemCount);
    }

    @Test
    public void messageListOmitsUnsupportedMessageSizeAndParsesDeltaLinksAndFlags() throws Exception {
        String expected = GraphMailApi.BASE + "/me/mailFolders/folder%2Fid/messages" +
                "?$select=id,receivedDateTime,sentDateTime,isRead,flag,conversationId,internetMessageId" +
                "&$orderby=receivedDateTime%20desc&$top=10";
        final String[] actual = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            actual[0] = url.toString();
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"value\":[{\"id\":\"message-1\",\"receivedDateTime\":\"2026-10-01T11:12:13.456Z\",\"sentDateTime\":\"2026-10-01T11:10:00Z\",\"isRead\":true,\"flag\":{\"flagStatus\":\"flagged\"},\"conversationId\":\"conversation-1\",\"internetMessageId\":\"<first@example.invalid>\"}," +
                            "{\"id\":\"message-2\",\"receivedDateTime\":\"2026-10-01T11:12:13.456Z\",\"sentDateTime\":\"2026-10-01T11:10:00Z\",\"isRead\":true,\"conversationId\":\"conversation-1\",\"internetMessageId\":\"<second@example.invalid>\"}]," +
                            "\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/messages?opaque=next\",\"@odata.deltaLink\":\"https://graph.microsoft.com/v1.0/me/messages/delta?opaque=delta\"}");
        });

        GraphMailApi.Page page = api.listMessages("folder/id");

        assertEquals(expected, actual[0]);
        assertEquals(2, page.messages.size());
        assertEquals("message-1", page.messages.get(0).id);
        assertTrue(page.messages.get(0).seen);
        assertTrue(page.messages.get(0).flagged);
        assertNull("Graph message size is not part of the supported projection", page.messages.get(0).size);
        assertEquals("conversation-1", page.messages.get(0).conversationId);
        assertEquals("<first@example.invalid>", page.messages.get(0).internetMessageId);
        assertEquals(GraphMailReceiver.getThreadKey(4L, page.messages.get(0).id, page.messages.get(0).conversationId),
                GraphMailReceiver.getThreadKey(4L, page.messages.get(1).id, page.messages.get(1).conversationId));
        assertEquals("graph:4:message-1", GraphMailReceiver.getThreadKey(4L, "message-1", null));
        assertEquals("https://graph.microsoft.com/v1.0/me/messages?opaque=next", page.nextLink);
        assertEquals("https://graph.microsoft.com/v1.0/me/messages/delta?opaque=delta", page.deltaLink);
    }

    @Test
    public void deltaQueryOmitsUnsupportedMessageSizeAndPreservesOpaqueCheckpoint() throws Exception {
        String initial = GraphMailApi.BASE + "/me/mailFolders/folder%2Fid/messages/delta" +
                "?$select=id,receivedDateTime,sentDateTime,isRead,flag,conversationId,internetMessageId" +
                "&$orderby=receivedDateTime%20desc&$top=10" +
                "&$filter=receivedDateTime%20ge%202026-10-01T11%3A12%3A13Z";
        String checkpoint = GraphMailApi.BASE + "/me/messages/delta?opaque=checkpoint%2Funchanged";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (initial.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[],\"@odata.deltaLink\":\"" + checkpoint + "\"}");
            if (checkpoint.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200, "{\"value\":[]}");
            throw new AssertionError("Unexpected Graph delta URL " + url);
        });

        GraphMailApi.Page first = api.listDelta("folder/id", 1790853133456L);
        GraphMailApi.Page next = api.listDelta(first.deltaLink, true);

        assertEquals(java.util.Arrays.asList(initial, checkpoint), requested);
        assertEquals(checkpoint, first.deltaLink);
        assertEquals(null, next.deltaLink);
    }

    @Test
    public void messageBodyAndAttachmentMetadataAreSeparateAndMetadataOmitsBytes() throws Exception {
        String messageId = "message/id+with space==";
        String encodedMessageId = "message%2Fid%2Bwith%20space%3D%3D";
        String metadataUrl = GraphMailApi.BASE + "/me/messages/" + encodedMessageId + "?$select=" +
                "id,receivedDateTime,sentDateTime,isRead,flag,hasAttachments,bodyPreview,internetMessageHeaders";
        String bodyUrl = metadataUrl + ",body";
        String attachmentsUrl = GraphMailApi.BASE + "/me/messages/" + encodedMessageId + "/attachments" +
                "?$select=id,name,contentType,size,isInline";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (url.toString().equals(metadataUrl))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"message/id+with space==\",\"receivedDateTime\":\"2026-10-01T11:12:13Z\"," +
                                "\"hasAttachments\":true,\"bodyPreview\":\"preview\"," +
                                "\"internetMessageHeaders\":[{\"name\":\"Subject\",\"value\":\"test\"}]}" );
            if (url.toString().equals(bodyUrl))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"message/id+with space==\",\"receivedDateTime\":\"2026-10-01T11:12:13Z\"," +
                                "\"body\":{\"contentType\":\"html\",\"content\":\"<p>body</p>\"}," +
                                "\"internetMessageHeaders\":[{\"name\":\"Subject\",\"value\":\"test\"}]}");
            if (url.toString().equals(attachmentsUrl))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"@odata.type\":\"#microsoft.graph.fileAttachment\"," +
                                "\"id\":\"attachment\",\"name\":\"file.txt\",\"contentType\":\"text/plain\"," +
                                "\"size\":4,\"isInline\":false}]}");
            throw new AssertionError("Unexpected metadata URL " + url);
        });

        GraphMailApi.MessageDetails metadata = api.getMessage(messageId, false);
        GraphMailApi.MessageDetails body = api.getMessage(messageId, true);
        List<NativeApiContent.Attachment> attachments = api.listMessageAttachments(messageId);

        assertEquals(Arrays.asList(metadataUrl, bodyUrl, attachmentsUrl), requested);
        assertNull("Message details must not depend on unsupported message size", metadata.size);
        assertEquals(null, metadata.body);
        assertEquals("<p>body</p>", body.body);
        assertEquals("Subject", body.headers.get(0).name);
        assertEquals("test", body.headers.get(0).value);
        assertEquals(1, attachments.size());
        assertEquals("attachment", attachments.get(0).id);
        assertTrue(requested.get(0).contains("internetMessageHeaders"));
        assertTrue(requested.get(1).contains(",body"));
        assertFalse("Message details must not select unsupported message size", requested.get(0).contains(",size,"));
        assertFalse("Message body details must not select unsupported message size", requested.get(1).contains(",size,"));
        assertTrue(requested.get(0).contains("message%2Fid%2Bwith%20space%3D%3D"));
        assertNull(attachments.get(0).contentId);
        assertTrue(requested.get(2).contains("$select=id,name,contentType,size,isInline"));
        assertFalse(requested.get(2).contains("contentId"));
        assertFalse(requested.get(2).contains("contentBytes"));
        assertFalse(requested.get(1).contains("contentBytes"));
        assertFalse(requested.stream().anyMatch(value -> value.endsWith("/$value")));
        assertFalse(requested.stream().anyMatch(value -> value.endsWith("/$value") ||
                value.endsWith("/messages/message/$value")));
    }

    @Test
    public void fetchingBodyIsReadOnlyAndReadStateUsesAnIndependentPatch() throws Exception {
        String messageId = "message-1";
        String bodyUrl = GraphMailApi.BASE + "/me/messages/message-1?$select=" +
                "id,receivedDateTime,sentDateTime,isRead,flag,hasAttachments,bodyPreview," +
                "internetMessageHeaders,body";
        String readUrl = GraphMailApi.BASE + "/me/messages/message-1";
        List<String> requests = new ArrayList<>();
        List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            ZohoTestHttpsConnection connection;
            if (bodyUrl.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"message-1\",\"isRead\":false," +
                                "\"body\":{\"contentType\":\"text\",\"content\":\"body\"}}");
            else if (readUrl.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 204, "");
            else
                throw new AssertionError("Unexpected Graph request " + url);
            requests.add(url.toString());
            connections.add(connection);
            return connection;
        });

        GraphMailApi.MessageDetails body = api.getMessage(messageId, true);
        api.setMessageRead(messageId, true);

        assertEquals("body", body.body);
        assertEquals(java.util.Arrays.asList(bodyUrl, readUrl), requests);
        assertEquals("GET", connections.get(0).getRequestMethod());
        assertEquals("PATCH", connections.get(1).getRequestMethod());
    }

    @Test
    public void attachmentContinuationIsUnchangedAndAuthorizedIncludingRefreshRetry() throws Exception {
        String first = GraphMailApi.BASE + "/me/messages/message%2Fid/attachments" +
                "?$select=id,name,contentType,size,isInline";
        String continuation = GraphMailApi.BASE + "/me/messages/message%2Fid/attachments" +
                "?$skiptoken=opaque%2Fcursor+page&$select=id,name,contentType,size,isInline";
        List<String> requested = new ArrayList<>();
        List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        int[] continuationRequests = {0};
        int[] refreshes = {0};
        GraphMailApi api = new GraphMailApi(null, "initial-test-token", url -> {
            requested.add(url.toString());
            ZohoTestHttpsConnection connection;
            if (first.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[],\"@odata.nextLink\":\"" + continuation + "\"}");
            else if (continuation.equals(url.toString()) && continuationRequests[0]++ == 0)
                connection = new ZohoTestHttpsConnection(url, 401,
                        "{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}");
            else if (continuation.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"attachment\",\"name\":\"file.txt\"}]}");
            else
                throw new AssertionError("Unexpected attachment URL");
            connections.add(connection);
            return connection;
        });
        api.setTokenRefresher(rejected -> {
            refreshes[0]++;
            assertTrue(rejected != null && !rejected.isEmpty());
            return "refreshed-test-token";
        });

        List<NativeApiContent.Attachment> attachments = api.listMessageAttachments("message/id");

        assertEquals(Arrays.asList(first, continuation, continuation), requested);
        assertEquals(1, attachments.size());
        assertEquals(1, refreshes[0]);
        for (ZohoTestHttpsConnection connection : connections) {
            assertEquals("GET", connection.getRequestMethod());
            assertTrue(connection.getRequestProperty("Authorization").startsWith("Bearer "));
        }
    }

    @Test
    public void attachmentListHttp400FailsAfterOneRequestWithoutTransientRetry() throws Exception {
        String expected = GraphMailApi.BASE + "/me/messages/message/attachments" +
                "?$select=id,name,contentType,size,isInline";
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "test-token", url -> {
            requests[0]++;
            assertEquals(expected, url.toString());
            return new ZohoTestHttpsConnection(url, 400,
                    "{\"error\":{\"code\":\"BadRequest\"}}");
        });

        try {
            api.listMessageAttachments("message");
            fail("Expected HTTP 400");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(400, ex.status);
            assertFalse(ex.isRateLimitFailure());
            assertFalse(ApiNetworkHibernation.isRetryableApiFailure(ex));
        }
        assertEquals(1, requests[0]);
    }

    @Test
    public void inlineFileAttachmentContentIdIsFetchedSeparatelyAndJoinedToCidBody() throws Exception {
        String messageId = "message/id";
        String attachmentId = "attachment/id+cid";
        String listUrl = GraphMailApi.BASE + "/me/messages/message%2Fid/attachments" +
                "?$select=id,name,contentType,size,isInline";
        String contentIdUrl = GraphMailApi.BASE + "/me/messages/message%2Fid/attachments/" +
                "attachment%2Fid%2Bcid/microsoft.graph.fileAttachment?$select=contentId";
        List<String> requested = new ArrayList<>();
        List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            ZohoTestHttpsConnection connection;
            if (listUrl.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"@odata.type\":\"#microsoft.graph.fileAttachment\"," +
                                "\"id\":\"attachment/id+cid\",\"name\":\"logo.png\"," +
                                "\"contentType\":\"image/png\",\"size\":11,\"isInline\":true}]}");
            else if (contentIdUrl.equals(url.toString()))
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"@odata.type\":\"#microsoft.graph.fileAttachment\"," +
                                "\"contentId\":\"logo@example.test\"}");
            else
                throw new AssertionError("Unexpected Graph attachment URL");
            connections.add(connection);
            return connection;
        });

        List<NativeApiContent.Attachment> attachments = api.listMessageAttachments(messageId);

        assertEquals(Arrays.asList(listUrl, contentIdUrl), requested);
        assertEquals(1, attachments.size());
        assertEquals("logo@example.test", attachments.get(0).contentId);
        assertTrue(contentIdUrl.endsWith("?$select=contentId"));
        assertFalse(contentIdUrl.contains("contentBytes"));
        assertFalse(contentIdUrl.endsWith("/$value"));
        for (ZohoTestHttpsConnection connection : connections) {
            assertEquals("GET", connection.getRequestMethod());
            assertTrue(connection.getRequestProperty("Authorization").startsWith("Bearer "));
        }

        MimeMessage message = NativeApiContent.createMessage(Session.getInstance(new Properties()),
                null, null, "text/html; charset=UTF-8",
                "<img src=\"cid:logo@example.test\">", attachments);
        Multipart mixed = (Multipart) message.getContent();
        MimeBodyPart relatedContainer = (MimeBodyPart) mixed.getBodyPart(0);
        Multipart related = (Multipart) relatedContainer.getContent();
        assertEquals("<img src=\"cid:logo@example.test\">", related.getBodyPart(0).getContent());
        assertEquals("<logo@example.test>", related.getBodyPart(1).getHeader("Content-ID")[0]);
    }

    @Test
    public void inlineNonFileAttachmentDoesNotRequestFileAttachmentContentId() throws Exception {
        String listUrl = GraphMailApi.BASE + "/me/messages/message/attachments" +
                "?$select=id,name,contentType,size,isInline";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"value\":[{\"@odata.type\":\"#microsoft.graph.itemAttachment\"," +
                            "\"id\":\"embedded-item\",\"name\":\"embedded.eml\"," +
                            "\"contentType\":\"message/rfc822\",\"isInline\":true}]}");
        });

        List<NativeApiContent.Attachment> attachments = api.listMessageAttachments("message");

        assertEquals(java.util.Collections.singletonList(listUrl), requested);
        assertEquals(1, attachments.size());
        assertNull(attachments.get(0).contentId);
        assertNull("Graph may omit attachment size metadata", attachments.get(0).size);
    }

    @Test
    public void selectedGraphAttachmentRequestsOnlyThatAttachmentBytes() throws Exception {
        byte[] bytes = "file".getBytes(StandardCharsets.UTF_8);
        int[] requests = {0};
        ZohoTestHttpsConnection[] response = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            assertEquals(GraphMailApi.BASE + "/me/messages/message/attachments/attachment%2Fid/$value",
                    url.toString());
            response[0] = new ZohoTestHttpsConnection(url, 200,
                    new String(bytes, StandardCharsets.UTF_8));
            response[0].responseHeaders.put("Content-Length", Integer.toString(bytes.length));
            return response[0];
        });
        File target = File.createTempFile("graph-selected-attachment-", ".bin");
        try {
            api.downloadAttachment("message", "attachment/id", target);
            assertTrue(Arrays.equals(bytes, Files.readAllBytes(target.toPath())));
            assertEquals("identity", response[0].getRequestProperty("Accept-Encoding"));
            assertEquals("A single tap retrieves only the selected Graph attachment", 1, requests[0]);
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void shortGraphValueResponseIsRejectedUsingHttpLengthAndTemporaryFileIsRemoved() throws Exception {
        ZohoTestHttpsConnection[] response = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            response[0] = new ZohoTestHttpsConnection(url, 200, "short");
            response[0].responseHeaders.put("Content-Length", "12");
            return response[0];
        });
        File target = File.createTempFile("graph-truncated-attachment-", ".bin");
        try {
            try {
                api.downloadAttachment("message", "attachment", target);
                fail("A response shorter than its HTTP Content-Length must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("HTTP Content-Length"));
            }
            assertFalse("The incomplete response must not remain available", target.exists());
            assertEquals("identity", response[0].getRequestProperty("Accept-Encoding"));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void compressedGraphValueSkipsEncodedLengthComparison() throws Exception {
        byte[] decoded = "decoded payload".getBytes(StandardCharsets.UTF_8);
        ZohoTestHttpsConnection[] response = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            response[0] = new ZohoTestHttpsConnection(url, 200,
                    new String(decoded, StandardCharsets.UTF_8));
            response[0].responseHeaders.put("Content-Length", "3");
            response[0].responseHeaders.put("Content-Encoding", "gzip");
            return response[0];
        });
        File target = File.createTempFile("graph-decoded-attachment-", ".bin");
        try {
            api.downloadAttachment("message", "attachment", target);
            assertTrue(Arrays.equals(decoded, Files.readAllBytes(target.toPath())));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void selectedGraphCidRenderResourceUsesOnlyItsAttachmentValueRoute() throws Exception {
        byte[] bytes = "cid-image-bytes".getBytes(StandardCharsets.UTF_8);
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            assertEquals(GraphMailApi.BASE + "/me/messages/message/attachments/inline%2Flogo/$value",
                    url.toString());
            return new ZohoTestHttpsConnection(url, 200, new String(bytes, StandardCharsets.UTF_8));
        });
        File target = File.createTempFile("graph-cid-render-resource-", ".bin");
        try {
            api.downloadAttachment("message", "inline/logo", target);
            assertTrue(Arrays.equals(bytes, Files.readAllBytes(target.toPath())));
            assertEquals("Only the referenced CID attachment is fetched", 1, requests[0]);
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void originalMessageRequestsMimeValueAndStreamsRawBytesToFile() throws Exception {
        byte[] mime = ("From: sender@example.net\r\nSubject: test\r\n" +
                "Content-Type: multipart/mixed; boundary=full\r\n\r\n" +
                "--full\r\nContent-Type: text/plain\r\n\r\nbody\r\n" +
                "--full\r\nContent-Type: application/octet-stream; name=\"file.bin\"\r\n" +
                "Content-Disposition: attachment; filename=\"file.bin\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n--full--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final ZohoTestHttpsConnection[] connection = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            assertEquals(GraphMailApi.BASE + "/me/messages/id%2Fwith%20space/$value", url.toString());
            connection[0] = new ZohoTestHttpsConnection(url, 200,
                    new String(mime, StandardCharsets.UTF_8));
            return connection[0];
        });

        File target = File.createTempFile("graph-mime-test-", ".eml");
        try {
            api.downloadOriginalMessage("id/with space", target);
            assertTrue(Arrays.equals(mime, Files.readAllBytes(target.toPath())));
            assertEquals("Bearer token", connection[0].getRequestProperty("Authorization"));
            assertEquals("text/plain", connection[0].getRequestProperty("Accept"));
        } finally {
            if (target.exists())
                assertTrue(target.delete());
        }
    }

    @Test
    public void continuationRejectsNonGraphOriginBeforeOpeningConnection() throws Exception {
        final int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            throw new AssertionError("Untrusted URL must not be opened");
        });
        try {
            api.listMessages("https://evil.example/v1.0/me/messages?token=steal", true);
            fail("Expected unsafe continuation to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unexpected continuation origin"));
        }
        assertEquals(0, requests[0]);
    }

    @Test
    public void unauthorizedRequestRefreshesTokenAndRetriesOnlyOnce() throws Exception {
        final int[] requests = {0};
        final int[] refreshes = {0};
        final List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "stale-token", url -> {
            ZohoTestHttpsConnection connection = (requests[0]++ == 0
                    ? new ZohoTestHttpsConnection(url, 401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}")
                    : new ZohoTestHttpsConnection(url, 200, "{\"value\":[]}"));
            connections.add(connection);
            return connection;
        });
        api.setTokenRefresher(rejected -> {
            refreshes[0]++;
            assertEquals("stale-token", rejected);
            return "fresh-token";
        });

        GraphMailApi.Page result = api.listMessages("inbox");

        assertEquals(0, result.messages.size());
        assertEquals(2, requests[0]);
        assertEquals(1, refreshes[0]);
        assertEquals("Bearer stale-token", connections.get(0).getRequestProperty("Authorization"));
        assertEquals("Bearer fresh-token", connections.get(1).getRequestProperty("Authorization"));
    }

    @Test
    public void rateLimitPreservesRetryAfterAndDoesNotExposeErrorBody() throws Exception {
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 429,
                    "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"private detail\"}}");
            connection.responseHeaders.put("Retry-After", "75");
            return connection;
        });
        try {
            api.listMessages("inbox");
            fail("Expected Graph throttling response");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(429, ex.status);
            assertEquals(75_000L, ex.retryAfterMs);
            assertTrue(ex.isRateLimitFailure());
            assertTrue(ex.getMessage().contains("TooManyRequests"));
            assertFalse(ex.getMessage().contains("private detail"));
        }
    }

    @Test
    public void productionMutationBuilderUsesLiteralPatchOnStandardMeRoute() throws Exception {
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("isRead", false);
        okhttp3.Request request = GraphMailApi.buildMutationRequest(
                new URL(GraphMailApi.BASE + "/me/messages/message-id"), "PATCH", "token", body);

        assertEquals("PATCH", request.method());
        assertEquals("https://graph.microsoft.com/v1.0/me/messages/message-id", request.url().toString());
        assertEquals("Bearer token", request.header("Authorization"));
        assertEquals("application/json; charset=utf-8", request.body().contentType().toString());
        okio.Buffer payload = new okio.Buffer();
        request.body().writeTo(payload);
        assertEquals("{\"isRead\":false}", payload.readUtf8());
    }

    @Test
    public void approvedWritesUseOnlyStandardMeMessageEndpoints() throws Exception {
        List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            ZohoTestHttpsConnection connection;
            if (url.getPath().endsWith("/move"))
                connection = new ZohoTestHttpsConnection(url, 201,
                        "{\"id\":\"moved-id\",\"parentFolderId\":\"archive\",\"isRead\":true," +
                                "\"flag\":{\"flagStatus\":\"flagged\"}}");
            else if (url.getQuery() != null)
                connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"message-id\",\"parentFolderId\":\"inbox\",\"isRead\":false," +
                                "\"flag\":{\"flagStatus\":\"notFlagged\"}}");
            else if (url.getPath().endsWith("message-id"))
                connection = new ZohoTestHttpsConnection(url, 204, "");
            else
                connection = new ZohoTestHttpsConnection(url, 200, "");
            connections.add(connection);
            return connection;
        });

        api.setMessageRead("message-id", false);
        api.setMessageFlagged("message-id", true);
        GraphMailApi.MessageState moved = api.moveMessage("message-id", "archive/id");
        api.deleteMessageFromFolder("trash/id", "message-id");
        GraphMailApi.MessageState state = api.getMessageState("message-id");

        assertEquals(5, connections.size());
        assertEquals("PATCH", connections.get(0).getRequestMethod());
        assertEquals(GraphMailApi.BASE + "/me/messages/message-id", connections.get(0).getURL().toString());
        assertFalse(new org.json.JSONObject(connections.get(0).requestBody.toString("UTF-8")).getBoolean("isRead"));
        assertEquals("PATCH", connections.get(1).getRequestMethod());
        assertEquals("flagged", new org.json.JSONObject(connections.get(1).requestBody.toString("UTF-8"))
                .getJSONObject("flag").getString("flagStatus"));
        assertEquals("POST", connections.get(2).getRequestMethod());
        assertEquals(GraphMailApi.BASE + "/me/messages/message-id/move", connections.get(2).getURL().toString());
        assertTrue(connections.get(2).getRequestProperty("Prefer").contains("return=minimal"));
        assertEquals("archive/id", new org.json.JSONObject(connections.get(2).requestBody.toString("UTF-8"))
                .getString("destinationId"));
        assertEquals("moved-id", moved.id);
        assertEquals("archive", moved.parentFolderId);
        assertTrue(moved.flagged);
        assertEquals("DELETE", connections.get(3).getRequestMethod());
        assertEquals(GraphMailApi.BASE + "/me/mailFolders/trash%2Fid/messages/message-id",
                connections.get(3).getURL().toString());
        assertEquals("GET", connections.get(4).getRequestMethod());
        assertTrue(connections.get(4).getURL().toString().contains("/me/messages/message-id?$select="));
        assertEquals(Boolean.FALSE, state.seen);
        assertEquals(Boolean.FALSE, state.flagged);
    }

    @Test
    public void normalDeleteMovesToWellKnownDeletedItems() throws Exception {
        final ZohoTestHttpsConnection[] connection = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            connection[0] = new ZohoTestHttpsConnection(url, 201,
                    "{\"id\":\"moved-id\",\"parentFolderId\":\"deleted-items-id\"}");
            return connection[0];
        });

        GraphMailApi.MessageState moved = api.moveMessageToDeletedItems("message/id");

        assertEquals("POST", connection[0].getRequestMethod());
        assertEquals(GraphMailApi.BASE + "/me/messages/message%2Fid/move",
                connection[0].getURL().toString());
        assertEquals("deleteditems", new org.json.JSONObject(
                connection[0].requestBody.toString("UTF-8")).getString("destinationId"));
        assertEquals("moved-id", moved.id);
        assertEquals("deleted-items-id", moved.parentFolderId);
    }

    @Test
    public void individualTrashDeleteUsesFolderScopedOrdinaryDelete() throws Exception {
        final ZohoTestHttpsConnection[] connection = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            connection[0] = new ZohoTestHttpsConnection(url, 204, "");
            return connection[0];
        });

        api.deleteMessageFromFolder("deleted/items", "message/id");

        assertEquals("DELETE", connection[0].getRequestMethod());
        assertEquals(GraphMailApi.BASE + "/me/mailFolders/deleted%2Fitems/messages/message%2Fid",
                connection[0].getURL().toString());
    }

    @Test
    public void mutation401RefreshesAndRetriesOnlyAfterExplicitRejection() throws Exception {
        List<ZohoTestHttpsConnection> connections = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "old-token", url -> {
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url,
                    connections.isEmpty() ? 401 : 204,
                    connections.isEmpty() ? "{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}" : "");
            connections.add(connection);
            return connection;
        });
        final int[] refreshes = {0};
        api.setTokenRefresher(rejected -> {
            assertEquals("old-token", rejected);
            refreshes[0]++;
            return "new-token";
        });

        api.setMessageRead("message-id", false);

        assertEquals(1, refreshes[0]);
        assertEquals(2, connections.size());
        assertEquals("PATCH", connections.get(0).getRequestMethod());
        assertEquals("Bearer old-token", connections.get(0).getRequestProperty("Authorization"));
        assertEquals("PATCH", connections.get(1).getRequestMethod());
        assertEquals("Bearer new-token", connections.get(1).getRequestProperty("Authorization"));
    }

    @Test
    public void mutation429HonorsRetryAfterAndNeverRetriesOrRefreshesToken() throws Exception {
        final int[] requests = {0};
        final int[] refreshes = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 429,
                    "{\"error\":{\"code\":\"TooManyRequests\"}}");
            connection.responseHeaders.put("Retry-After", "37");
            return connection;
        });
        api.setTokenRefresher(rejected -> {
            refreshes[0]++;
            return "fresh-token";
        });

        try {
            api.moveMessage("message-id", "archive");
            fail("Expected Graph throttling response");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(429, ex.status);
            assertEquals(37_000L, ex.retryAfterMs);
        }

        assertEquals(1, requests[0]);
        assertEquals(0, refreshes[0]);
    }

    @Test
    public void mutation503IsSingleAttemptAndCarriesRetryAfterForReconciliationBackoff() throws Exception {
        final int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 503,
                    "{\"error\":{\"code\":\"ServiceUnavailable\"}}");
            connection.responseHeaders.put("Retry-After", "19");
            return connection;
        });

        try {
            api.moveMessage("message-id", "archive");
            fail("Expected Graph service failure");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(503, ex.status);
            assertEquals(19_000L, ex.retryAfterMs);
            assertTrue(ex.isRateLimitFailure());
        }
        assertEquals(1, requests[0]);
    }

    @Test
    public void deltaDateParserAcceptsGraphUtcTimestamps() {
        assertEquals(1790853133456L, GraphMailApi.parseGraphDate("2026-10-01T11:12:13.456Z"));
        assertEquals(0L, GraphMailApi.parseGraphDate("not-a-date"));
    }

    @Test
    public void listsUserTrashMessagesAcrossOpaqueContinuationPages() throws Exception {
        String first = GraphMailApi.BASE + "/users/alice%40example.com/mailFolders/trash%2Fid/messages" +
                "?$select=id,parentFolderId&$top=" + GraphMailApi.PURGE_PAGE_SIZE;
        String next = GraphMailApi.BASE + "/users/alice%40example.com/mailFolders/trash%2Fid/messages?opaque=next%2Fpage";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (first.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"msg-1\",\"parentFolderId\":\"trash/id\"}]," +
                                "\"@odata.nextLink\":\"" + next + "\"}");
            if (next.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"msg-2\",\"parentFolderId\":\"trash/id\"}]}");
            throw new AssertionError("Unexpected Graph purge page " + url);
        });

        GraphMailApi.Page page1 = api.listUserFolderMessages("alice@example.com", "trash/id");
        GraphMailApi.Page page2 = api.listUserFolderMessages(page1.nextLink, true);

        assertEquals(Arrays.asList(first, next), requested);
        assertEquals(1, page1.messages.size());
        assertEquals("msg-1", page1.messages.get(0).id);
        assertEquals(next, page1.nextLink);
        assertEquals(1, page2.messages.size());
        assertEquals("msg-2", page2.messages.get(0).id);
        assertEquals(null, page2.nextLink);
    }

    @Test
    public void userFolderAndMessageStateUseEncodedUsersRoutes() throws Exception {
        String folderUrl = GraphMailApi.BASE + "/users/alice%40example.com/mailFolders/trash%2Fid" +
                "?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount";
        String messageUrl = GraphMailApi.BASE + "/users/alice%40example.com/messages/msg%2Fid" +
                "?$select=id,parentFolderId,isRead,flag";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (folderUrl.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"trash/id\",\"displayName\":\"Deleted Items\"}");
            if (messageUrl.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"msg/id\",\"parentFolderId\":\"trash/id\",\"isRead\":false}");
            throw new AssertionError("Unexpected Graph identity URL " + url);
        });

        GraphMailApi.MailFolder folder = api.getUserMailFolder("alice@example.com", "trash/id");
        GraphMailApi.MessageState message = api.getUserMessageState("alice@example.com", "msg/id");

        assertEquals(Arrays.asList(folderUrl, messageUrl), requested);
        assertEquals("trash/id", folder.id);
        assertEquals("Deleted Items", folder.name);
        assertEquals("trash/id", message.parentFolderId);
        assertEquals(Boolean.FALSE, message.seen);
    }

    @Test
    public void permanentDeleteUsesUsersRouteAndAnEmptyPostBody() throws Exception {
        final URL[] requestedUrl = {null};
        final String[] requestedMethod = {null};
        final org.json.JSONObject[] requestedBody = {null};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            throw new AssertionError("Mutation must use the injected offline executor");
        }, (url, method, token, body) -> {
            requestedUrl[0] = url;
            requestedMethod[0] = method;
            requestedBody[0] = body;
            assertEquals("token", token);
            return new GraphMailApi.MutationResult(204, 0, null, new byte[0]);
        });

        api.permanentDeleteMessage("alice@example.com", "trash/id", "msg/id");

        assertEquals(GraphMailApi.BASE + "/users/alice%40example.com/mailFolders/trash%2Fid/messages/" +
                        "msg%2Fid/permanentDelete",
                requestedUrl[0].toString());
        assertEquals("POST", requestedMethod[0]);
        assertEquals(null, requestedBody[0]);
        okhttp3.Request request = GraphMailApi.buildMutationRequest(
                requestedUrl[0], "POST", "token", null);
        assertEquals("POST", request.method());
        assertEquals(0L, request.body().contentLength());
        assertEquals("application/json; charset=utf-8", request.body().contentType().toString());
        assertEquals("return=minimal", request.header("Prefer"));
    }

    @Test
    public void purgePageWithoutMessageIdFailsClosed() throws Exception {
        GraphMailApi api = new GraphMailApi(null, "token", url ->
                new ZohoTestHttpsConnection(url, 200, "{\"value\":[{}]}"));
        try {
            api.listUserFolderMessages("alice@example.com", "trash-id");
            fail("Expected malformed purge page to be rejected");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("without an ID"));
        }
    }

    @Test
    public void unexpectedPermanentDeleteSuccessIsNotAcceptedAsConfirmed204() throws Exception {
        final int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            throw new AssertionError("Mutation must use the injected offline executor");
        }, (url, method, token, body) -> {
            requests[0]++;
            return new GraphMailApi.MutationResult(202, 0, null, new byte[0]);
        });
        try {
            api.permanentDeleteMessage("alice@example.com", "trash-id", "message-id");
            fail("Expected an undocumented success status to be treated as uncertain");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("unexpected HTTP 202"));
        }
        assertEquals(1, requests[0]);
    }
}
