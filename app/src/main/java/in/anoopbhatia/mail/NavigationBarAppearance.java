package in.anoopbhatia.mail;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** App-drawn navigation color behind a transparent system bar, owned by one Activity window. */
final class NavigationBarAppearance extends View implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener, AutoCloseable {
    private final Window window;
    private final ViewGroup decor;
    private int bottomBackgroundColor;
    private int sideBackgroundColor;
    private final Paint paint = new Paint();
    private Insets navigation = Insets.NONE;
    private ViewTreeObserver observer;
    private boolean closed;

    NavigationBarAppearance(Window window, int backgroundColor) {
        super(window.getContext());
        this.window = window;
        this.decor = (ViewGroup) window.getDecorView();
        this.bottomBackgroundColor = ColorUtils.setAlphaComponent(backgroundColor, 255);
        this.sideBackgroundColor = bottomBackgroundColor;
        paint.setColor(Color.TRANSPARENT);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        decor.getOverlay().add(this);
        decor.addOnAttachStateChangeListener(this);
        if (decor.isAttachedToWindow())
            onViewAttachedToWindow(decor);
        updateIcons(Color.TRANSPARENT);
    }

    void setColor(int color) {
        if (closed)
            return;
        paint.setColor(color);
        updateIcons(color);
        invalidate();
    }

    void setBackgroundColors(int bottom, int side) {
        bottomBackgroundColor = ColorUtils.setAlphaComponent(bottom, 255);
        sideBackgroundColor = ColorUtils.setAlphaComponent(side, 255);
        updateIcons(paint.getColor());
    }

    private void updateIcons(int color) {
        int background = navigation.bottom > 0 ? bottomBackgroundColor : sideBackgroundColor;
        int effective = ColorUtils.compositeColors(color, background);
        boolean light = ColorUtils.calculateContrast(Color.BLACK, effective) >
                ColorUtils.calculateContrast(Color.WHITE, effective);
        WindowCompat.getInsetsController(window, decor).setAppearanceLightNavigationBars(light);
    }

    @Override
    public boolean onPreDraw() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
        Insets next = Insets.NONE;
        if (insets != null) {
            next = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            // A docked IME owns its navigation area. In a resized window our bottom
            // is above that area; do not paint an extra stripe above the keyboard.
            if (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > next.bottom)
                next = Insets.NONE;
        }
        if (!next.equals(navigation)) {
            navigation = next;
            updateIcons(paint.getColor());
            invalidate();
        }
        if (getWidth() != decor.getWidth() || getHeight() != decor.getHeight())
            layout(0, 0, decor.getWidth(), decor.getHeight());
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int left = Math.min(w, navigation.left);
        int right = Math.max(left, w - navigation.right);
        int top = Math.min(h, navigation.top);
        int bottom = Math.max(top, h - navigation.bottom);
        // Disjoint rectangles avoid double-blending translucent colors at corners.
        canvas.drawRect(0, 0, w, top, paint);
        canvas.drawRect(0, bottom, w, h, paint);
        canvas.drawRect(0, top, left, bottom, paint);
        canvas.drawRect(right, top, w, bottom, paint);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        removeObserver();
        if (!closed) {
            observer = decor.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            decor.requestApplyInsets();
        }
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        removeObserver();
    }

    private void removeObserver() {
        if (observer != null && observer.isAlive())
            observer.removeOnPreDrawListener(this);
        observer = null;
    }

    @Override
    public void close() {
        closed = true;
        removeObserver();
        decor.removeOnAttachStateChangeListener(this);
        decor.getOverlay().remove(this);
    }
}
