package in.anoopbhatia.mail;

import android.text.Spanned;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HtmlExSpanQueryTest {
    private static Spanned spans(String[] values, Throwable failure) {
        return (Spanned) Proxy.newProxyInstance(Spanned.class.getClassLoader(),
                new Class<?>[]{Spanned.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getSpans"))
                        throw new AssertionError("Unexpected operation: " + method);
                    assertEquals(2, args[0]);
                    assertEquals(5, args[1]);
                    assertEquals(String.class, args[2]);
                    if (failure != null)
                        throw failure;
                    return values;
                });
    }

    private static List<?> query(Spanned text) throws Exception {
        Method query = HtmlEx.class.getDeclaredMethod("getSpans",
                Spanned.class, int.class, int.class, Class.class);
        query.setAccessible(true);
        Object result = query.invoke(null, text, 2, 5, String.class);
        assertTrue(result instanceof List<?>);
        return (List<?>) result;
    }

    @Test
    public void spanOrderAndNullEntriesArePreserved() throws Exception {
        assertEquals(Arrays.asList("first", null, "last"),
                query(spans(new String[]{"first", null, "last"}, null)));
    }

    @Test
    public void emptyQueryRemainsEmpty() throws Exception {
        assertEquals(Collections.emptyList(), query(spans(new String[0], null)));
    }

    @Test
    public void corruptPlatformSpanArrayFallsBackToNoSpans() throws Exception {
        assertEquals(Collections.emptyList(),
                query(spans(null, new ArrayStoreException("corrupt platform span tree"))));
    }
}
