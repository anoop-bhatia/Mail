package in.anoopbhatia.mail;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Toast-compatible handle for a non-interactive, Activity-owned notice. The Toast base type is
 * retained for existing progress handles (show/cancel/setText), but no custom Toast is created.
 * Only genuinely background/application notices fall back to a platform text Toast.
 */
public class ToastEx extends Toast {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Accessed only on main; an idle handle never owns an Activity or its Context.
    private static final List<WeakReference<Activity>> resumed = new ArrayList<>();
    private static ToastEx active;

    private final Context application;
    private final WeakReference<Activity> owner;
    private final boolean activityContext;
    private CharSequence text = "";
    private boolean bw;
    private int duration = LENGTH_SHORT;
    private int gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    private int xOffset;
    private int yOffset;
    private boolean positioned;
    private Notice notice;
    private Toast fallback;
    private final Runnable dismiss = this::cancelOnMain;

    public ToastEx(Context context) {
        super(context.getApplicationContext());
        application = context.getApplicationContext();
        Activity activity = findActivity(context);
        owner = new WeakReference<>(activity);
        activityContext = (activity != null);
        yOffset = Helper.dp2pixels(context, 112);
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity)
                return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context)
                break;
            context = base;
        }
        return null;
    }

    public static ToastEx makeText(Context context, int resId, int duration) throws Resources.NotFoundException {
        return makeText(context, context.getText(resId), duration);
    }

    public static ToastEx makeText(Context context, CharSequence text, int duration) {
        ToastEx result = new ToastEx(context);
        result.text = text;
        result.duration = duration;
        int resId = context.getResources().getIdentifier(
                "design_bottom_navigation_height", "dimen", context.getPackageName());
        if (resId > 0)
            result.yOffset = 2 * context.getResources().getDimensionPixelSize(resId);
        return result;
    }

    public static ToastEx makeTextBw(Context context, CharSequence text, int duration) {
        ToastEx result = makeText(context, text, duration);
        result.bw = true;
        result.yOffset = Helper.dp2pixels(context, 64);
        return result;
    }

    @Override
    public void setDuration(int duration) {
        this.duration = duration;
    }

    @Override
    public int getDuration() {
        return duration;
    }

    @Override
    public void setGravity(int gravity, int xOffset, int yOffset) {
        this.gravity = gravity;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        positioned = true;
    }

    @Override
    public int getGravity() {
        return gravity;
    }

    @Override
    public int getXOffset() {
        return xOffset;
    }

    @Override
    public int getYOffset() {
        return yOffset;
    }

    @Override
    public void setText(int resId) {
        setText(application.getText(resId));
    }

    @Override
    public void setText(CharSequence text) {
        onMain(() -> {
            this.text = text;
            if (notice != null)
                notice.message.setText(text);
            if (fallback != null)
                fallback.setText(text);
        });
    }

    @Override
    public void show() {
        onMain(this::showOnMain);
    }

    @Override
    public void cancel() {
        onMain(this::cancelOnMain);
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper())
            action.run();
        else
            MAIN.post(action);
    }

    static void onActivityResumed(Activity activity) {
        resumed.removeIf(reference -> reference.get() == null || reference.get() == activity);
        resumed.add(new WeakReference<>(activity));
    }

    static void onActivityPaused(Activity activity) {
        resumed.removeIf(reference -> reference.get() == null || reference.get() == activity);
        if (active != null && active.notice != null && active.notice.activity == activity)
            active.cancelOnMain();
    }

    private static Activity currentActivity() {
        resumed.removeIf(reference -> reference.get() == null);
        return (resumed.isEmpty() ? null : resumed.get(resumed.size() - 1).get());
    }

    private static boolean isResumed(Activity activity) {
        for (WeakReference<Activity> reference : resumed)
            if (reference.get() == activity)
                return true;
        return false;
    }

    private void showOnMain() {
        cancelOnMain();
        Activity activity = (activityContext ? owner.get() : currentActivity());
        // Obsolete Activity handles must not escape onto a different screen or into a system Toast.
        if (activityContext && (activity == null || !isResumed(activity)))
            return;
        if (activity != null && (activity.isFinishing() || activity.isDestroyed()))
            return;
        if (active != null)
            active.cancelOnMain();
        if (activity == null) {
            fallback = Toast.makeText(application, text, duration);
            fallback.show();
            active = this;
            MAIN.postDelayed(dismiss, timeout(application));
            return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow())
            return;
        notice = new Notice(activity);
        active = this;
        try {
            notice.show();
            MAIN.postDelayed(dismiss, timeout(application));
        } catch (WindowManager.BadTokenException | IllegalStateException ex) {
            // A token can disappear between the lifecycle check and WindowManager.addView.
            Log.w(ex);
            cancelOnMain();
        }
    }

    private int timeout(Context context) {
        int millis = (duration == LENGTH_LONG ? 3500 : 2000);
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null)
            millis = manager.getRecommendedTimeoutMillis(millis, AccessibilityManager.FLAG_CONTENT_TEXT);
        return millis;
    }

    private void cancelOnMain() {
        MAIN.removeCallbacks(dismiss);
        if (notice != null) {
            Notice previous = notice;
            notice = null;
            previous.close();
        }
        if (fallback != null) {
            fallback.cancel();
            fallback = null;
        }
        if (active == this)
            active = null;
    }

    /** A normal app window, not an overlay-permission window and not a custom Toast window. */
    private final class Notice implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        final Activity activity;
        final View host;
        final Dialog dialog;
        final TextView message;
        final int[] actual = new int[2];
        private int availableWidth = -1;
        private int availableHeight = -1;
        private int targetLeft = Integer.MIN_VALUE;
        private int targetTop = Integer.MIN_VALUE;
        private int corrections;
        private boolean populated;
        ViewTreeObserver hostObserver;
        ViewTreeObserver noticeObserver;

        Notice(Activity activity) {
            this.activity = activity;
            host = activity.getWindow().getDecorView();
            dialog = new Dialog(activity, R.style.Theme_App_Notice);
            dialog.setOwnerActivity(activity);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            Window window = dialog.getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.TOP | Gravity.LEFT);
            // Constrain measurement without changing the host Activity/Dialog layout or its insets.
            FrameLayout content = new FrameLayout(activity) {
                @Override
                protected void onMeasure(int width, int height) {
                    Rect frame = frame();
                    super.onMeasure(cap(width, frame.width()), cap(height, frame.height()));
                }
            };
            // Inflate with the owning window's own theme. A theme transplanted from a caller
            // context cannot resolve ?attr references reliably, which fails the whole view.
            View view = LayoutInflater.from(activity).inflate(R.layout.toast, content, false);
            message = view.findViewById(android.R.id.message);
            if (bw) {
                view.setBackgroundColor(Helper.resolveColor(activity, android.R.attr.textColorPrimary));
                message.setTextColor(Helper.resolveColor(activity, android.R.attr.textColorPrimaryInverse));
            } else {
                view.setBackground(activity.getDrawable(R.drawable.toast_background));
                message.setTextColor(Helper.resolveColor(activity, R.attr.colorInfoForeground));
            }
            message.setText("");
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(view);
            dialog.setContentView(content);
            dialog.setOnDismissListener(ignored -> {
                if (notice == this)
                    cancelOnMain();
            });
        }

        private int cap(int spec, int available) {
            int limit = Math.max(0, available);
            if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED)
                limit = Math.min(limit, View.MeasureSpec.getSize(spec));
            return View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST);
        }

        private Rect frame() {
            Rect frame = new Rect();
            host.getWindowVisibleDisplayFrame(frame);
            host.getLocationOnScreen(actual);
            Rect bounds = new Rect(actual[0], actual[1],
                    actual[0] + host.getWidth(), actual[1] + host.getHeight());
            if (!frame.intersect(bounds))
                frame.set(bounds);
            return frame;
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hostObserver = host.getViewTreeObserver();
            noticeObserver = window.getDecorView().getViewTreeObserver();
            hostObserver.addOnGlobalLayoutListener(this);
            noticeObserver.addOnPreDrawListener(this);
        }

        @Override
        public void onGlobalLayout() {
            if (!dialog.isShowing())
                return;
            View decor = dialog.getWindow().getDecorView();
            Rect frame = frame();
            if (availableWidth != frame.width() || availableHeight != frame.height()) {
                availableWidth = frame.width();
                availableHeight = frame.height();
                decor.requestLayout();
            }
            decor.invalidate();
        }

        @Override
        public boolean onPreDraw() {
            if (!dialog.isShowing())
                return true;
            if (!populated) {
                populated = true;
                // Attached live-region change; postpone drawing until the real text is measured.
                message.setText(text);
                return false;
            }
            View decor = dialog.getWindow().getDecorView();
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0)
                return true;
            Rect frame = frame();
            Rect target = new Rect();
            int x = xOffset;
            int y = yOffset;
            int align = gravity;
            // Both TOP|START hint callers pass physical getLocationOnScreen coordinates.
            if (positioned && gravity == (Gravity.TOP | Gravity.START)) {
                align = Gravity.TOP | Gravity.LEFT;
                x -= frame.left;
                y -= frame.top;
            }
            Gravity.apply(align, decor.getWidth(), decor.getHeight(), frame,
                    x, y, target, host.getLayoutDirection());
            int left = Math.max(frame.left, Math.min(target.left, frame.right - decor.getWidth()));
            int top = Math.max(frame.top, Math.min(target.top, frame.bottom - decor.getHeight()));
            if (targetLeft != left || targetTop != top) {
                targetLeft = left;
                targetTop = top;
                corrections = 0;
            }
            decor.getLocationOnScreen(actual);
            if ((actual[0] != left || actual[1] != top) && corrections < 3) {
                Window window = dialog.getWindow();
                WindowManager.LayoutParams attrs = window.getAttributes();
                // Correct from actual screen coordinates, independent of WindowManager inset origin.
                attrs.x += left - actual[0];
                attrs.y += top - actual[1];
                try {
                    window.setAttributes(attrs);
                } catch (WindowManager.BadTokenException | IllegalStateException | IllegalArgumentException ex) {
                    Log.w(ex);
                    cancelOnMain();
                    return true;
                }
                corrections++;
                return false;
            }
            // Bound retries if an OEM WindowManager imposes a stricter frame than the host reports.
            return true;
        }

        void close() {
            if (hostObserver != null && hostObserver.isAlive())
                hostObserver.removeOnGlobalLayoutListener(this);
            if (noticeObserver != null && noticeObserver.isAlive())
                noticeObserver.removeOnPreDrawListener(this);
            hostObserver = null;
            noticeObserver = null;
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
    }
}
