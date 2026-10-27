package eu.faircode.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FetchMoreRoutingTest {
    private static final class Request {
        final long folderId;
        final int pageSize;

        Request(long folderId, int pageSize) {
            this.folderId = folderId;
            this.pageSize = pageSize;
        }
    }

    private static EntityAccount account(int authType, String provider) {
        EntityAccount account = new EntityAccount();
        account.id = 7L;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        account.provider = provider;
        return account;
    }

    private static EntityFolder folder(long id) {
        EntityFolder folder = new EntityFolder("INBOX", EntityFolder.INBOX);
        folder.id = id;
        folder.account = 7L;
        folder.selectable = true;
        folder.download = true;
        return folder;
    }

    private static FetchMoreRouting.Target nativeTarget(int authType, String provider, long id) {
        EntityAccount account = account(authType, provider);
        EntityFolder folder = folder(id);
        if (authType == ServiceAuthenticator.AUTH_TYPE_GMAIL) {
            folder.gmail_label_id = "INBOX";
            folder.gmail_page_token = "older-page-2";
        } else if (authType == ServiceAuthenticator.AUTH_TYPE_ZOHO) {
            folder.zoho_folder_id = "zoho-folder-" + id;
            folder.zoho_page_start = 11;
        } else if (authType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API) {
            folder.graph_folder_id = "graph-folder-" + id;
            folder.graph_delta_link = "https://graph.example/delta";
            folder.graph_page_link = "https://graph.example/page/2";
        }
        return FetchMoreRouting.Target.create(folder, account);
    }

    private static FetchMoreRouting.Target legacyTarget(long id) {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap");
        EntityFolder folder = folder(id);
        folder.download = false;
        return FetchMoreRouting.Target.create(folder, account);
    }

    private static void assertNativePage(String entryPoint, FetchMoreRouting.Target target,
                                         long expectedFolderId) {
        ArrayList<Request> requests = new ArrayList<>();
        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(Collections.singletonList(target),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        requests.add(new Request(folderId, pageSize));
                    }
                });
        assertEquals(entryPoint, Collections.emptyList(), result.legacyFolderIds);
        assertEquals(entryPoint, 1, result.nativePagesRequested);
        assertEquals(entryPoint, 1, requests.size());
        assertEquals(entryPoint, expectedFolderId, requests.get(0).folderId);
        assertEquals(entryPoint, 10, requests.get(0).pageSize);
    }

    @Test
    public void eachSingleFolderEntryUsesTenForGmailZohoAndGraph() {
        int[] providers = {
                ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.AUTH_TYPE_ZOHO,
                ServiceAuthenticator.AUTH_TYPE_GRAPH_API
        };
        String[] ids = {
                GmailApi.PROVIDER_ID,
                ZohoOAuth.LEGACY_PROVIDER_ID,
                GraphOAuth.LEGACY_PROVIDER_ID
        };
        String[] entryPoints = {"folder long-press", "message-list folder menu"};
        for (String entryPoint : entryPoints)
            for (int i = 0; i < providers.length; i++)
                assertNativePage(entryPoint, nativeTarget(providers[i], ids[i], 20 + i), 20 + i);
    }

    @Test
    public void subfolderLongPressQueuesTenItemPagesForEachEligibleChild() {
        int[] providers = {
                ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.AUTH_TYPE_ZOHO,
                ServiceAuthenticator.AUTH_TYPE_GRAPH_API
        };
        String[] ids = {GmailApi.PROVIDER_ID, ZohoOAuth.PROVIDER_ID, GraphOAuth.PROVIDER_ID};
        for (int i = 0; i < providers.length; i++) {
            ArrayList<Request> requests = new ArrayList<>();
            List<FetchMoreRouting.Target> children = Arrays.asList(
                    nativeTarget(providers[i], ids[i], 40),
                    nativeTarget(providers[i], ids[i], 41));
            FetchMoreRouting.Result result = FetchMoreRouting.dispatch(children,
                    new FetchMoreRouting.PageRequester() {
                        @Override
                        public void requestOlderPage(long folderId, int pageSize) {
                            requests.add(new Request(folderId, pageSize));
                        }
                    });
            assertTrue(result.legacyFolderIds.isEmpty());
            assertEquals(2, result.nativePagesRequested);
            assertEquals(Arrays.asList(40L, 41L), Arrays.asList(requests.get(0).folderId, requests.get(1).folderId));
            assertEquals(10, requests.get(0).pageSize);
            assertEquals(10, requests.get(1).pageSize);
        }
    }

    @Test
    public void globalAndMessageListUnifiedViewsDispatchApisAndRetainOnlyLegacyFolders() {
        int[] providers = {
                ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.AUTH_TYPE_ZOHO,
                ServiceAuthenticator.AUTH_TYPE_GRAPH_API
        };
        String[] ids = {GmailApi.PROVIDER_ID, ZohoOAuth.PROVIDER_ID, GraphOAuth.PROVIDER_ID};
        String[] entryPoints = {"global unified-inbox button", "message-list unified menu"};
        for (String entryPoint : entryPoints) {
            ArrayList<FetchMoreRouting.Target> targets = new ArrayList<>();
            for (int i = 0; i < providers.length; i++)
                targets.add(nativeTarget(providers[i], ids[i], 30 + i));
            targets.add(legacyTarget(39));
            ArrayList<Request> requests = new ArrayList<>();
            FetchMoreRouting.Result result = FetchMoreRouting.dispatch(targets,
                    new FetchMoreRouting.PageRequester() {
                        @Override
                        public void requestOlderPage(long folderId, int pageSize) {
                            requests.add(new Request(folderId, pageSize));
                        }
                    });
            assertEquals(entryPoint, 3, result.nativePagesRequested);
            assertEquals(entryPoint, Arrays.asList(39L), result.legacyFolderIds);
            assertEquals(entryPoint, 3, requests.size());
            for (Request request : requests)
                assertEquals(entryPoint, 10, request.pageSize);
        }
    }

    @Test
    public void legacyFolderStillRoutesToChooserRegardlessOfDownloadSetting() {
        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(
                Collections.singletonList(legacyTarget(51)),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        throw new AssertionError("Legacy account must not dispatch an API page");
                    }
                });
        assertEquals(Collections.singletonList(51L), result.legacyFolderIds);
        assertEquals(0, result.nativePagesRequested);
    }

    @Test
    public void exhaustedOrDownloadDisabledNativeFoldersDoNotOpenLegacyChooser() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, GmailApi.PROVIDER_ID);
        EntityFolder exhausted = folder(61);
        exhausted.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;
        FetchMoreRouting.Target exhaustedTarget = FetchMoreRouting.Target.create(exhausted, gmail);

        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, GraphOAuth.PROVIDER_ID);
        EntityFolder notDownloaded = folder(62);
        notDownloaded.download = false;
        notDownloaded.graph_delta_link = "https://graph.example/delta";
        notDownloaded.graph_page_link = "https://graph.example/page/2";
        FetchMoreRouting.Target disabledTarget = FetchMoreRouting.Target.create(notDownloaded, graph);

        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(
                Arrays.asList(exhaustedTarget, disabledTarget),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        throw new AssertionError("An ineligible native folder must not dispatch");
                    }
                });
        assertTrue(result.legacyFolderIds.isEmpty());
        assertEquals(0, result.nativePagesRequested);
    }

    @Test
    public void gmailDownloadDisabledFolderStillRoutesOneTenMessageOlderPage() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, GmailApi.PROVIDER_ID);
        EntityFolder folder = folder(63);
        folder.gmail_label_id = "INBOX";
        folder.download = false;
        folder.gmail_page_token = "gmail-page-2";
        ArrayList<Request> requests = new ArrayList<>();

        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(
                Collections.singletonList(FetchMoreRouting.Target.create(folder, gmail)),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        requests.add(new Request(folderId, pageSize));
                    }
                });

        assertTrue(result.legacyFolderIds.isEmpty());
        assertEquals(1, result.nativePagesRequested);
        assertEquals(1, requests.size());
        assertEquals(63L, requests.get(0).folderId);
        assertEquals(10, requests.get(0).pageSize);
    }

    @Test
    public void unsynchronizedGmailFolderWithNullCursorQueuesOneMarkedTenMessagePage() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, GmailApi.PROVIDER_ID);
        EntityFolder folder = folder(64);
        folder.gmail_label_id = "Label_42";
        folder.gmail_page_token = null;
        folder.synchronize = false;
        folder.poll = false;
        ArrayList<Request> requests = new ArrayList<>();

        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(
                Collections.singletonList(FetchMoreRouting.Target.create(folder, gmail)),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        requests.add(new Request(folderId, pageSize));
                    }
                });

        assertTrue(result.legacyFolderIds.isEmpty());
        assertEquals(1, result.nativePagesRequested);
        assertEquals(64L, requests.get(0).folderId);
        assertEquals(10, requests.get(0).pageSize);
        String queuedArgs = EntityOperation.createSyncArgs(folder, gmail, false, true).toString();
        assertTrue("The queue row carries the explicit older-page marker",
                NativeApiSyncRequest.isOlderPageRequest(queuedArgs));
        assertFalse("Queue serialization does not enable automatic sync", folder.synchronize);
        assertFalse("Queue serialization does not enable background polling", folder.poll);
        assertFalse("A manual queue marker does not set the folder's requested-sync state",
                GmailReceiver.isRequestedFolderSync(folder));
        assertTrue("The Gmail worker processes an explicit older-page marker anyway",
                NativeApiSyncRequest.shouldProcessNativeFolderSync(
                        GmailReceiver.isRequestedFolderSync(folder),
                        NativeApiSyncRequest.isOlderPageRequest(queuedArgs)));
        assertFalse("The disabled folder stays out of automatic initial-page seeding",
                GmailReceiver.shouldInitializeFolder(folder));
    }

    @Test
    public void unsynchronizedZohoAndGraphFoldersQueueMarkedInitialPages() {
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, ZohoOAuth.PROVIDER_ID);
        EntityFolder zohoFolder = folder(65);
        zohoFolder.zoho_folder_id = "zoho-projects";
        zohoFolder.zoho_page_start = null;
        zohoFolder.synchronize = false;
        zohoFolder.poll = false;
        zohoFolder.sync_state = "connected";

        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, GraphOAuth.PROVIDER_ID);
        EntityFolder graphFolder = folder(66);
        graphFolder.graph_folder_id = "graph-projects";
        graphFolder.graph_delta_link = null;
        graphFolder.graph_page_link = null;
        graphFolder.synchronize = false;
        graphFolder.poll = false;
        graphFolder.sync_state = "connected";

        ArrayList<Request> requests = new ArrayList<>();
        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(
                Arrays.asList(FetchMoreRouting.Target.create(zohoFolder, zoho),
                        FetchMoreRouting.Target.create(graphFolder, graph)),
                new FetchMoreRouting.PageRequester() {
                    @Override
                    public void requestOlderPage(long folderId, int pageSize) {
                        requests.add(new Request(folderId, pageSize));
                    }
                });

        assertTrue(result.legacyFolderIds.isEmpty());
        assertEquals(2, result.nativePagesRequested);
        assertEquals(Arrays.asList(65L, 66L), Arrays.asList(
                requests.get(0).folderId, requests.get(1).folderId));
        assertEquals(10, requests.get(0).pageSize);
        assertEquals(10, requests.get(1).pageSize);

        String zohoArgs = EntityOperation.createSyncArgs(zohoFolder, zoho, false, true).toString();
        String graphArgs = EntityOperation.createSyncArgs(graphFolder, graph, false, true).toString();
        assertTrue(NativeApiSyncRequest.isOlderPageRequest(zohoArgs));
        assertTrue(NativeApiSyncRequest.isOlderPageRequest(graphArgs));
        assertTrue("The explicit marker reaches Zoho despite a stale non-requested sync state",
                NativeApiSyncRequest.shouldProcessNativeFolderSync(
                        ZohoReceiver.isRequestedFolderSync(zohoFolder),
                        NativeApiSyncRequest.isOlderPageRequest(zohoArgs)));
        assertTrue("The explicit marker reaches Graph despite a stale non-requested sync state",
                NativeApiSyncRequest.shouldProcessNativeFolderSync(
                        GraphMailReceiver.isRequestedFolderSync(graphFolder),
                        NativeApiSyncRequest.isOlderPageRequest(graphArgs)));
        assertFalse(zohoFolder.synchronize);
        assertFalse(zohoFolder.poll);
        assertFalse(graphFolder.synchronize);
        assertFalse(graphFolder.poll);
        assertFalse(ZohoReceiver.shouldSyncAutomatically(zohoFolder));
        assertFalse(GraphMailReceiver.shouldSyncSelectedFolder(graphFolder));
    }

    @Test
    public void nativePageSizesAreExactlyTenAndLegacyMonthsRemainThirtyDayPeriods() {
        assertEquals(10, GmailReceiver.PAGE_SIZE);
        assertEquals(10, ZohoReceiver.PAGE_SIZE);
        assertEquals(10, GraphMailReceiver.PAGE_SIZE);
        assertEquals(Integer.valueOf(90), FetchMoreRouting.getKeepDays(3));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), FetchMoreRouting.getKeepDays(0));
        assertEquals(null, FetchMoreRouting.getKeepDays(-1));
    }
}
