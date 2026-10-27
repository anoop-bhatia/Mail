package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GraphReplyRoutingTest {
    @Test
    public void directRepliesUseProviderReplyRouteWhileNewMessagesUseSendMail() {
        assertEquals("https://graph.microsoft.com/v1.0/me/sendMail",
                MicrosoftGraph.getDirectSendUrl(null));
        assertEquals("https://graph.microsoft.com/v1.0/me/messages/source%2Fid%2B%3D%3D/reply",
                MicrosoftGraph.getDirectSendUrl("source/id+=="));
    }

    @Test
    public void unresolvedReplySourceDoesNotInventAProviderId() {
        EntityMessage source = new EntityMessage();
        source.thread = "fairEmail-thread-is-not-a-Graph-message-id";
        assertEquals("https://graph.microsoft.com/v1.0/me/sendMail",
                MicrosoftGraph.getDirectSendUrl(NativeApiReply.graphMessageId(
                        java.util.Collections.singletonList(source))));
    }
}
