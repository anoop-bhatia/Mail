package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SendRecoveryPolicyTest {
    @Test
    public void unknownGraphOutcomeIsQuarantinedDuringOutboxSync() {
        EntityOperation operation = sendOperation();
        operation.error = EntityOperation.SEND_OUTCOME_UNKNOWN_ERROR;

        assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
        assertFalse(EntityOperation.shouldRecoverSendOnSync(operation));
    }

    @Test
    public void unknownGmailOutcomeIsQuarantinedDuringOutboxSync() {
        EntityOperation operation = sendOperation();
        operation.error = EntityOperation.GMAIL_SEND_OUTCOME_UNKNOWN_ERROR;

        assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
        assertFalse(EntityOperation.shouldRecoverSendOnSync(operation));
    }

    @Test
    public void definiteLegacySendFailureRemainsRecoverable() {
        EntityOperation operation = sendOperation();
        operation.error = "SMTP 550 5.1.1 recipient rejected";

        assertFalse(EntityOperation.isSendOutcomeUnknown(operation));
        assertTrue(EntityOperation.shouldRecoverSendOnSync(operation));
    }

    @Test
    public void absentSendOperationRemainsRecoverable() {
        assertTrue(EntityOperation.shouldRecoverSendOnSync(null));
    }

    @Test
    public void executingSendRemainsUntouched() {
        EntityOperation operation = sendOperation();
        operation.state = "executing";

        assertFalse(EntityOperation.shouldRecoverSendOnSync(operation));
    }

    private static EntityOperation sendOperation() {
        EntityOperation operation = new EntityOperation();
        operation.name = EntityOperation.SEND;
        return operation;
    }
}
