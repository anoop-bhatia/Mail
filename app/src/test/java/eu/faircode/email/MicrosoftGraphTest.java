package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MicrosoftGraphTest {
    @Test
    public void onlyHttp202CountsAsSendAcceptance() {
        assertTrue(MicrosoftGraph.isSendAcceptedStatus(202));
        assertFalse(MicrosoftGraph.isSendAcceptedStatus(200));
        assertFalse(MicrosoftGraph.isSendAcceptedStatus(201));
        assertFalse(MicrosoftGraph.isSendAcceptedStatus(500));
    }

    @Test
    public void graphSendUsesBoundedRequestAndExistingSocketTimeouts() {
        assertEquals(20, MicrosoftGraph.GRAPH_TIMEOUT);
        assertEquals(5 * 60 * 1000L, MicrosoftGraph.GRAPH_SEND_TIMEOUT);
    }
}
