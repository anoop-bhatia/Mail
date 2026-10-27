package eu.faircode.email;

import android.os.OperationCanceledException;
import org.json.JSONException;
import java.io.IOException;
import javax.mail.MessagingException;

/** Runs successful folder-page work sequentially up to the current pass budget. */
final class GmailFolderPageBatch {
    private GmailFolderPageBatch() {
    }

    interface Processor {
        /** Returns false when no eligible requested folder remains. */
        boolean processNext() throws IOException, JSONException, MessagingException,
                OperationCanceledException;
    }

    static int drain(int pageLimit, Processor processor)
            throws IOException, JSONException, MessagingException,
            OperationCanceledException {
        int processed = 0;
        while (processed < pageLimit && processor.processNext())
            processed++;
        return processed;
    }
}
