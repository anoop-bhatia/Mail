package in.anoopbhatia.mail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RingBufferTest {
    @Test
    public void preservesFifoOrderAndOverwritesTheOldestEntry() {
        RingBuffer<String> ring = new RingBuffer<>(3);

        ring.push("one");
        ring.push("two");
        ring.push("three");
        ring.push("four");

        assertEquals(3, ring.size());
        assertEquals("two", ring.pop());
        assertEquals("three", ring.pop());
        assertEquals("four", ring.pop());
        assertTrue(ring.isEmpty());
    }

    @Test
    public void supportsNullEntriesAndWraparound() {
        RingBuffer<String> ring = new RingBuffer<>(2);

        ring.push(null);
        ring.push("second");
        assertNull(ring.pop());
        ring.push("third");

        assertEquals("second", ring.pop());
        assertEquals("third", ring.pop());
        assertEquals(0, ring.size());
    }

    @Test
    public void capacityOneAlwaysKeepsOnlyTheNewestEntry() {
        RingBuffer<Integer> ring = new RingBuffer<>(1);

        ring.push(1);
        ring.push(2);
        ring.push(3);

        assertEquals(1, ring.size());
        assertEquals(Integer.valueOf(3), ring.pop());
        assertTrue(ring.isEmpty());
    }

    @Test
    public void popClearsTheConsumedSlot() throws Exception {
        RingBuffer<Object> ring = new RingBuffer<>(2);
        Object item = new Object();
        ring.push(item);

        assertEquals(item, ring.pop());

        Field field = RingBuffer.class.getDeclaredField("buffer");
        field.setAccessible(true);
        List<?> buffer = (List<?>) field.get(ring);
        assertNull(buffer.get(0));
    }

    @Test
    public void emptyPopRetainsExistingCountBehavior() {
        RingBuffer<String> ring = new RingBuffer<>(2);

        assertNull(ring.pop());
        assertEquals(-1, ring.size());
        assertFalse(ring.isEmpty());
    }

    @Test(expected = NegativeArraySizeException.class)
    public void rejectsNegativeCapacityLikeTheArrayImplementation() {
        new RingBuffer<>(-1);
    }

    @Test
    public void zeroCapacityPushAndPopHaveArrayBoundsFailure() {
        RingBuffer<String> ring = new RingBuffer<>(0);

        try {
            ring.pop();
        } catch (ArrayIndexOutOfBoundsException expected) {
            try {
                ring.push("value");
            } catch (ArrayIndexOutOfBoundsException pushExpected) {
                return;
            }
            throw new AssertionError("zero-capacity push should fail");
        }
        throw new AssertionError("zero-capacity pop should fail");
    }
}
