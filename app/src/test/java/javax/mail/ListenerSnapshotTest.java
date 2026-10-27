package javax.mail;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.mail.event.ConnectionAdapter;
import javax.mail.event.ConnectionEvent;
import javax.mail.event.ConnectionListener;

import static org.junit.Assert.*;

/** Exercises actual JavaMail event queues without opening a server connection. */
public class ListenerSnapshotTest {
    private static Session session(ExecutorService executor) {
        Properties properties = new Properties();
        properties.put("mail.event.executor", executor);
        return Session.getInstance(properties);
    }

    private static class TestService extends Service {
        TestService(Session session) {
            super(session, new URLName("imap", "localhost", -1, null, "test", null));
        }

        void emit(int type) {
            notifyConnectionListeners(type);
        }
    }

    private static class TestFolder extends IMAPFolder {
        TestFolder(Session session) {
            super("INBOX", '/', new IMAPStore(session,
                    new URLName("imap", "localhost", -1, null, "test", null)), false);
        }

        void emit(int type) {
            notifyConnectionListeners(type);
        }
    }

    private static void exercise(Consumer<ConnectionListener> add,
                                 Consumer<ConnectionListener> remove,
                                 IntConsumer emit) throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch opened = new CountDownLatch(2);
        CountDownLatch disconnected = new CountDownLatch(2);
        ConnectionListener third = new ConnectionAdapter() {
            @Override
            public void opened(ConnectionEvent event) {
                received.add("third-opened");
            }

            @Override
            public void disconnected(ConnectionEvent event) {
                received.add("third-disconnected");
                disconnected.countDown();
            }
        };
        ConnectionListener second = new ConnectionAdapter() {
            @Override
            public void opened(ConnectionEvent event) {
                received.add("second-opened");
                opened.countDown();
            }

            @Override
            public void disconnected(ConnectionEvent event) {
                received.add("second-disconnected");
            }
        };
        ConnectionListener first = new ConnectionAdapter() {
            @Override
            public void opened(ConnectionEvent event) {
                received.add("first-opened");
                remove.accept(second);
                add.accept(third);
                opened.countDown();
            }

            @Override
            public void disconnected(ConnectionEvent event) {
                received.add("first-disconnected");
                disconnected.countDown();
            }
        };
        add.accept(first);
        add.accept(second);
        emit.accept(ConnectionEvent.OPENED);
        assertTrue("Original listeners must both receive the queued event",
                opened.await(5, TimeUnit.SECONDS));
        emit.accept(ConnectionEvent.DISCONNECTED);
        assertTrue("Next event must see listener changes from the first delivery",
                disconnected.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("first-opened", "second-opened",
                "first-disconnected", "third-disconnected"), received);
    }

    @Test
    public void serviceFreezesListenerOrderAndMembershipForEachEvent() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TestService service = new TestService(session(executor));
        try {
            exercise(service::addConnectionListener, service::removeConnectionListener, service::emit);
        } finally {
            service.emit(ConnectionEvent.CLOSED);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void folderFreezesListenerOrderAndMembershipForEachEvent() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TestFolder folder = new TestFolder(session(executor));
        try {
            exercise(folder::addConnectionListener, folder::removeConnectionListener, folder::emit);
        } finally {
            folder.emit(ConnectionEvent.CLOSED);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
