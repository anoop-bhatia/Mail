package javax.mail.internet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class InternetAddressParseTest {
    @Test
    public void nonStrictHeaderTreatsOutOfGroupSemicolonAsAddressDelimiter() throws Exception {
        InternetAddress[] addresses = InternetAddress.parseHeader(
                "first@example.com;second@example.com", false);

        assertEquals(2, addresses.length);
        assertEquals("first@example.com", addresses[0].getAddress());
        assertEquals("second@example.com", addresses[1].getAddress());
    }

    @Test
    public void strictHeaderStillRejectsOutOfGroupSemicolon() {
        String input = "first@example.com;second@example.com";
        AddressException exception = assertThrows(AddressException.class, () ->
                InternetAddress.parseHeader(input, true));

        assertEquals("Illegal semicolon, not in group", exception.getMessage());
        assertEquals(17, exception.getPos());
        assertEquals(input, exception.getRef());
    }

    @Test
    public void groupsAndEmptyDelimitersAreHandledWithoutSpuriousAddresses() throws Exception {
        InternetAddress[] addresses = InternetAddress.parseHeader(
                "Friends: one@example.com,;,, second@example.com;", false);

        assertEquals(2, addresses.length);
        assertEquals("Friends: one@example.com", addresses[0].getAddress());
        assertEquals("second@example.com", addresses[1].getAddress());
        assertEquals(0, InternetAddress.parseHeader(";, ,", false).length);
    }

    @Test
    public void commentsAndPersonalNamesSurviveDelimiterParsing() throws Exception {
        InternetAddress[] addresses = InternetAddress.parseHeader(
                "Alice <alice@example.com> (comment); Bob <bob@example.com>", false);

        assertEquals(2, addresses.length);
        assertEquals("alice@example.com", addresses[0].getAddress());
        assertEquals("Alice", addresses[0].getPersonal());
        assertEquals("bob@example.com", addresses[1].getAddress());
        assertEquals("Bob", addresses[1].getPersonal());
    }

    @Test
    public void nonStrictHeaderRecoversReversedPersonalAndEmailFields() throws Exception {
        InternetAddress[] addresses = InternetAddress.parseHeader(
                "\"Jane Doe\" (john.doe@example.com);next@example.com", false);

        assertEquals(2, addresses.length);
        assertEquals("john.doe@example.com", addresses[0].getAddress());
        assertEquals("\"Jane Doe\"", addresses[0].getPersonal());
        assertEquals("next@example.com", addresses[1].getAddress());
        assertEquals(null, addresses[1].getPersonal());
    }

    @Test
    public void parseRejectsOutOfGroupSemicolonEvenWhenNonStrict() {
        String input = "first@example.com;second@example.com";
        for (boolean strict : new boolean[]{false, true}) {
            AddressException exception = assertThrows(AddressException.class, () ->
                    InternetAddress.parse(input, strict));
            assertEquals("Illegal semicolon, not in group", exception.getMessage());
            assertEquals(17, exception.getPos());
            assertEquals(input, exception.getRef());
        }
    }

    @Test
    public void emptyGroupCompletionRetainsGroupInStrictAndNonStrictModes() throws Exception {
        String input = "Friends:;,next@example.com";
        for (boolean strict : new boolean[]{false, true}) {
            InternetAddress[] addresses = InternetAddress.parseHeader(input, strict);
            assertEquals(2, addresses.length);
            assertEquals("Friends:;", addresses[0].getAddress());
            assertEquals(0, addresses[0].getGroup(strict).length);
            assertEquals("next@example.com", addresses[1].getAddress());
        }
    }

    @Test
    public void nonStrictHeaderRetainsGroupNameAsLocalPartBeforeAtSign() throws Exception {
        InternetAddress[] addresses = InternetAddress.parseHeader(
                "Undisclosed-Recipient:;@java.sun.com,next@example.com", false);

        assertEquals(2, addresses.length);
        assertEquals("Undisclosed-Recipient:;@java.sun.com", addresses[0].getAddress());
        assertEquals("next@example.com", addresses[1].getAddress());
    }
}
