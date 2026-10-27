package in.anoopbhatia.mail;

import android.app.Notification;
import android.view.HapticFeedbackConstants;

import androidx.core.app.NotificationCompat;
import androidx.core.view.HapticFeedbackConstantsCompat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Compatibility constants must not change the values passed to existing platform calls. */
public class CompatibilityConstantsTest {
    @Test
    public void foregroundServiceSchedulingValuesAreUnchanged() {
        assertEquals(Notification.FOREGROUND_SERVICE_DEFAULT,
                NotificationCompat.FOREGROUND_SERVICE_DEFAULT);
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE,
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        assertEquals(Notification.FOREGROUND_SERVICE_DEFERRED,
                NotificationCompat.FOREGROUND_SERVICE_DEFERRED);
    }

    @Test
    public void hapticValuesAreUnchanged() {
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstantsCompat.CONFIRM);
        assertEquals(HapticFeedbackConstants.GESTURE_END, HapticFeedbackConstantsCompat.GESTURE_END);
    }
}
