package in.anoopbhatia.mail;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Keyboard-safe sizing for the floating import/export AlertDialogs, including their buttons. */
final class DialogKeyboardInsets {
    private DialogKeyboardInsets() {
    }

    static void install(Dialog dialog, View activityDecor) {
        Window window = dialog.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        // Layout is owned by inset fitting (30+) or the visible-frame adapter (28/29), not pan.
        attributes.softInputMode = (attributes.softInputMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Fit the floating WINDOW, not just the custom view: AlertDialog's button panel must
            // remain above the IME too. The window manager remeasures its existing scrollable body.
            attributes.setFitInsetsTypes(attributes.getFitInsetsTypes()
                    | WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            attributes.setFitInsetsIgnoringVisibility(false);
            window.setAttributes(attributes);
            return;
        }
        window.setAttributes(attributes);

        // Before API 30 an adjust-nothing/floating window does not report useful IME insets.
        // Observe the full activity's visible frame instead; leave its adjustment mode untouched.
        dialog.create();
        ViewGroup content = dialog.findViewById(android.R.id.content);
        CappedLayout body = new CappedLayout(content.getContext());
        while (content.getChildCount() > 0) {
            View child = content.getChildAt(0);
            content.removeViewAt(0);
            body.addView(child);
        }
        content.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View decor = window.getDecorView();
        Rect visible = new Rect();
        Rect hostBounds = new Rect();
        int[] location = new int[2];
        ViewTreeObserver.OnGlobalLayoutListener layout = () -> {
            if (!decor.isAttachedToWindow() || !activityDecor.isAttachedToWindow())
                return;
            activityDecor.getWindowVisibleDisplayFrame(visible);
            activityDecor.getLocationOnScreen(location);
            hostBounds.set(location[0], location[1],
                    location[0] + activityDecor.getWidth(), location[1] + activityDecor.getHeight());
            if (!visible.intersect(hostBounds))
                return;
            int padding = decor.getPaddingTop() + decor.getPaddingBottom()
                    + content.getPaddingTop() + content.getPaddingBottom();
            if (body.setMaximumHeight(Math.max(0, visible.height() - padding)))
                return; // Centre after the capped AlertDialog (including buttons) is remeasured.
            decor.getLocationOnScreen(location);
            int offset = centeredTop(visible.top, visible.bottom, decor.getHeight()) - location[1];
            if (offset != 0) {
                WindowManager.LayoutParams current = window.getAttributes();
                current.y += offset;
                window.setAttributes(current);
            }
        };
        // Only the dialog's traversal changes its attributes. A host traversal can precede the
        // pending window relocation; applying the same screen delta there too would cause drift.
        ViewTreeObserver.OnGlobalLayoutListener hostLayout = decor::requestLayout;
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private ViewTreeObserver dialogObserver;
            private ViewTreeObserver activityObserver;

            @Override
            public void onViewAttachedToWindow(View v) {
                dialogObserver = decor.getViewTreeObserver();
                activityObserver = activityDecor.getViewTreeObserver();
                dialogObserver.addOnGlobalLayoutListener(layout);
                activityObserver.addOnGlobalLayoutListener(hostLayout);
                decor.requestLayout();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (dialogObserver != null && dialogObserver.isAlive())
                    dialogObserver.removeOnGlobalLayoutListener(layout);
                if (activityObserver != null && activityObserver.isAlive())
                    activityObserver.removeOnGlobalLayoutListener(hostLayout);
                dialogObserver = null;
                activityObserver = null;
            }
        });
    }

    static int centeredTop(int top, int bottom, int height) {
        return top + Math.max(0, bottom - top - height) / 2;
    }

    private static final class CappedLayout extends FrameLayout {
        private int maximumHeight = Integer.MAX_VALUE;

        CappedLayout(Context context) {
            super(context);
        }

        boolean setMaximumHeight(int height) {
            if (maximumHeight == height)
                return false;
            maximumHeight = height;
            requestLayout();
            return true;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int limit = (mode == MeasureSpec.UNSPECIFIED ? maximumHeight : Math.min(size, maximumHeight));
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
