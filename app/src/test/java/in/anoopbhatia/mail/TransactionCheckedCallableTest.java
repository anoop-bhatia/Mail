package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import javax.mail.MessagingException;
import org.junit.Test;

/**
 * Host-side JUnit4 contract for the checked adapter.  The model is the same
 * callback/begin/commit/finally-end model used by TransactionCallableTest and
 * therefore tests the API boundary without requiring Android SQLite.
 */
public class TransactionCheckedCallableTest {
    private static <T, E1 extends Exception, E2 extends Exception> T checked(
            TransactionCallableRoomModel room,
            DB.TransactionCheckedCallable<T, E1, E2> body) throws E1, E2 {
        Callable<T> adapter = () -> body.call();
        return room.run(adapter);
    }

    @Test public void preservesIOExceptionIdentityAndRollsBack() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        IOException expected = new IOException("io");
        try {
            checked(room, () -> { throw expected; });
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test public void preservesMessagingExceptionIdentity() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        MessagingException expected = new MessagingException("mail");
        try {
            checked(room, () -> { throw expected; });
            fail("expected MessagingException");
        } catch (MessagingException actual) {
            assertSame(expected, actual);
        }
    }

    @Test public void normalReturnAndNullCommit() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        assertEquals("ok", checked(room, () -> "ok"));
        assertEquals(Arrays.asList("begin", "commit", "end"), room.trace);
        assertNull(checked(room, () -> null));
    }

    @Test public void runtimeAndErrorIdentitySurvive() throws Exception {
        for (Throwable expected : new Throwable[]{
                new IllegalStateException("runtime"), new AssertionError("error")}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            try {
                checked(room, () -> {
                    if (expected instanceof RuntimeException)
                        throw (RuntimeException) expected;
                    throw (Error) expected;
                });
                fail("expected failure");
            } catch (RuntimeException | AssertionError actual) {
                assertSame(expected, actual);
            }
        }
    }

    @Test public void endFailureReplacesBodyFailureWithoutSuppression() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        IOException body = new IOException("body");
        Error end = new AssertionError("end");
        room.endFailure = end;
        try {
            checked(room, () -> { throw body; });
            fail("expected end");
        } catch (AssertionError actual) {
            assertSame(end, actual);
            assertEquals(0, actual.getSuppressed().length);
        }
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test public void beginFailureDoesNotCleanupOrInvokeCallback() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        room.beginFailure = begin;
        try {
            checked(room, () -> { fail("callback invoked"); return null; });
            fail("expected begin");
        } catch (RuntimeException actual) {
            assertSame(begin, actual);
        }
        assertEquals(Arrays.asList("begin"), room.trace);
    }

    private static Void invokeDual(TransactionCallableRoomModel room, IOException io,
                                   MessagingException mail, boolean chooseIo)
            throws IOException, MessagingException {
        return TransactionCheckedCallableTest.<Void, IOException, MessagingException>checked(room, () -> {
            if (chooseIo) throw io;
            throw mail;
        });
    }

    @Test public void sameBodyRetainsBothNarrowCheckedTypes() throws IOException, MessagingException {
        IOException io = new IOException("io");
        MessagingException mail = new MessagingException("mail");
        for (boolean chooseIo : new boolean[]{false, true}) {
            try {
                invokeDual(new TransactionCallableRoomModel(), io, mail, chooseIo);
                fail("expected checked failure");
            } catch (IOException | MessagingException actual) {
                assertSame(chooseIo ? io : mail, actual);
            }
        }
    }
}
