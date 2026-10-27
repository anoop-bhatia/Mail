package in.anoopbhatia.mail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Executes the complete production class with host doubles, not Android WindowManager or TalkBack. */
public class ToastExTest {
    private static Harness harness;

    @BeforeClass
    public static void compileHarness() throws Exception {
        harness = new Harness();
    }

    @AfterClass
    public static void closeHarness() throws Exception {
        if (harness != null)
            harness.close();
    }

    @Test public void normalAndBwRetainBackgroundTextAndLiveUpdates() throws Exception { harness.run("style"); }
    @Test public void shortLongAndAccessibilityTimeoutValuesAreExact() throws Exception { harness.run("timeout"); }
    @Test public void replacementExternalDismissAndTimeoutReleaseListenersAndHandles() throws Exception { harness.run("cleanup"); }
    @Test public void wrappedAndApplicationContextsChooseTheRightOwnerOrBackgroundFallback() throws Exception { harness.run("contexts"); }
    @Test public void pausedDestroyedFinishingDetachedAndCollectedOwnersAreRejected() throws Exception { harness.run("lifecycle"); }
    @Test public void multipleResumedActivitiesKeepIndependentWeakOwnership() throws Exception { harness.run("multiwindow"); }
    @Test public void workerDispatchPreservesTextShowCancelAndPauseOrdering() throws Exception { harness.run("thread"); }
    @Test public void appWindowIsNonInteractiveAndDoesNotModifyAnExistingModal() throws Exception { harness.run("window"); }
    @Test public void showAndAsynchronousUpdateFailuresCleanUp() throws Exception { harness.run("failure"); }
    @Test public void screenCoordinatesBottomOffsetClampingAndFrameChangesAreExact() throws Exception { harness.run("geometry"); }
    @Test public void preDrawPositionRetriesAreBoundedAndContentMeasurementIsCapped() throws Exception { harness.run("measure"); }

    @Test
    public void lifecycleHooksAndExistingStylesAreRetainedWithoutDeprecatedToastMutation() throws Exception {
        String source = read("app/src/main/java/in/anoopbhatia/mail/ToastEx.java");
        assertFalse(source.contains("setView("));
        assertFalse(source.contains("SuppressWarnings"));
        assertFalse(source.contains("setType("));
        String app = read("app/src/main/java/in/anoopbhatia/mail/ApplicationEx.java");
        for (String callback : new String[]{"onActivityResumed", "onActivityPaused", "onActivityDestroyed"}) {
            String body = app.substring(app.indexOf("public void " + callback + "("));
            body = body.substring(0, body.indexOf("\n        }"));
            assertTrue(callback, body.contains("ToastEx." + (callback.equals("onActivityResumed") ?
                    "onActivityResumed(activity)" : "onActivityPaused(activity)")));
        }
        String layout = read("app/src/main/res/layout/toast.xml");
        for (String value : new String[]{"@android:id/message", "paddingStart=\"12dp\"", "paddingEnd=\"12dp\"",
                "paddingTop=\"6dp\"", "paddingBottom=\"6dp\"", "@style/NoticeText", "gravity=\"center\""})
            assertTrue(value, layout.contains(value));
        String background = read("app/src/main/res/drawable/toast_background.xml");
        assertTrue(background.contains("radius=\"12dp\""));
        assertTrue(background.contains("?attr/colorInfoBackground"));
        String theme = read("app/src/main/res/values/notice.xml");
        for (String value : new String[]{"android:windowNoTitle\">true", "@android:color/transparent",
                "android:backgroundDimEnabled\">false", "android:windowMinWidthMajor\">0dp",
                "android:windowMinWidthMinor\">0dp", "android:windowAnimationStyle\">@null"})
            assertTrue(value, theme.contains(value));
        assertTrue(read("app/build.gradle.kts").contains("minSdk = 28"));
    }

    @Test
    public void noticeInflationNeverTransplantsAThemeFromAnotherContext() throws Exception {
        String source = read("app/src/main/java/in/anoopbhatia/mail/ToastEx.java");
        // A copied Resources.Theme may not resolve ?attr references, so the notice uses the
        // window's own theme.
        assertFalse(source.contains("newTheme()"));
        assertFalse(source.contains("setTo("));
        assertFalse(source.contains("ContextThemeWrapper"));
        harness.run("style");
    }

    @Test
    public void noticeTextNeverResolvesThemeAttributes() throws IOException {
        String layout = read("app/src/main/res/layout/toast.xml");
        // AppCompat's Small appearance maps android:textColorLink to ?android:textColorLink, and
        // TextView resolves that through the theme of the inflating context. The Material You
        // themes left it unresolved there, and TypedArray.getColorStateList(index 6) throws, which
        // aborted the whole notice with an InflateException. The notice therefore uses a local
        // appearance that only carries a size; ToastEx sets the color itself.
        assertFalse(layout.contains("TextAppearance.AppCompat"));
        String styles = read("app/src/main/res/values/notice.xml");
        int start = styles.indexOf("<style name=\"NoticeText\"");
        assertTrue("Missing NoticeText appearance", start >= 0);
        String block = styles.substring(start, styles.indexOf("</style>", start));
        assertTrue(block, block.contains("android:textSize\">14sp"));
        assertFalse("A theme attribute here fails wherever the inflating theme cannot resolve it",
                block.contains("?"));
    }

    private static String read(String relative) throws IOException {
        for (Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath(); path != null; path = path.getParent()) {
            Path file = path.resolve(relative);
            if (Files.exists(file))
                return Files.readString(file);
        }
        throw new IOException("Missing project file " + relative);
    }

    private static final class Harness implements AutoCloseable {
        private final Path root = Files.createTempDirectory("toast-notice");
        private final URLClassLoader loader;
        private final java.lang.reflect.Method run;

        Harness() throws Exception {
            writeSources();
            Path classes = Files.createDirectories(root.resolve("classes"));
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
            args.add("-d");
            args.add(classes.toString());
            try (var paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> args.add(path.toString()));
            }
            Path log = root.resolve("javac.log");
            Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            if (!done)
                process.destroyForcibly();
            assertTrue("Host compilation: " + Files.readString(log), done && process.exitValue() == 0);
            loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
            run = loader.loadClass("in.anoopbhatia.mail.ToastHarness").getMethod("run", String.class);
        }

        void run(String scenario) throws Exception {
            try {
                run.invoke(null, scenario);
            } catch (InvocationTargetException ex) {
                throw new AssertionError("Scenario " + scenario, ex.getCause());
            }
        }

        @Override
        public void close() throws Exception {
            loader.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

        private void write(String relative, String source) throws IOException {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        }

        private void writeSources() throws IOException {
            write("in/anoopbhatia/mail/ToastEx.java", read("app/src/main/java/in/anoopbhatia/mail/ToastEx.java"));
            write("in/anoopbhatia/mail/R.java", "package in.anoopbhatia.mail; public class R { public static class layout {public static final int toast=1;} public static class drawable {public static final int toast_background=2;} public static class attr {public static final int colorInfoForeground=3;} public static class style {public static final int Theme_App_Notice=4;} }");
            write("in/anoopbhatia/mail/Helper.java", "package in.anoopbhatia.mail; public class Helper {public static int dp2pixels(android.content.Context c,int n){return n;} public static int resolveColor(android.content.Context c,int n){return n*100;} }");
            write("in/anoopbhatia/mail/Log.java", "package in.anoopbhatia.mail; public class Log {public static int warnings; public static void w(Throwable e){warnings++;} }");
            write("android/R.java", "package android; public class R {public static class id {public static final int message=7;} public static class attr {public static final int textColorPrimary=8,textColorPrimaryInverse=9;} }");
            write("android/content/res/Resources.java", """
                    package android.content.res;
                    public class Resources {
                        public int dimensionId, dimension=56;
                        public Theme newTheme(){return new Theme();}
                        public int getIdentifier(String n,String t,String p){return dimensionId;}
                        public int getDimensionPixelSize(int id){return dimension;}
                        public static class Theme {
                            public int marker;
                            public void setTo(Theme other){marker=other.marker;}
                        }
                        public static class NotFoundException extends RuntimeException {}
                    }
                    """);
            write("android/content/Context.java", """
                    package android.content;
                    import android.content.res.Resources;
                    public class Context {
                        public static final String ACCESSIBILITY_SERVICE="accessibility";
                        public final Resources resources=new Resources();
                        public final Resources.Theme theme=resources.newTheme();
                        public Context getApplicationContext(){return this;}
                        public Resources getResources(){return resources;}
                        public Resources.Theme getTheme(){return theme;}
                        public CharSequence getText(int id){return "resource-"+id;}
                        public String getPackageName(){return "mail";}
                        public android.graphics.drawable.Drawable getDrawable(int id){return new android.graphics.drawable.Drawable(id);}
                        public Object getSystemService(String name){return new android.view.accessibility.AccessibilityManager();}
                    }
                    """);
            write("android/content/ContextWrapper.java", """
                    package android.content;
                    public class ContextWrapper extends Context {
                        private final Context base;
                        public ContextWrapper(Context base){this.base=base;}
                        public Context getBaseContext(){return base;}
                        public Context getApplicationContext(){return base.getApplicationContext();}
                        public android.content.res.Resources getResources(){return base.getResources();}
                        public android.content.res.Resources.Theme getTheme(){return base.getTheme();}
                        public Object getSystemService(String name){return base.getSystemService(name);}
                    }
                    """);
            write("android/graphics/drawable/Drawable.java", "package android.graphics.drawable; public class Drawable {public final int id; public Drawable(int id){this.id=id;} }");
            write("android/graphics/Rect.java", """
                    package android.graphics;
                    public class Rect {
                        public int left,top,right,bottom;
                        public Rect(){} public Rect(int l,int t,int r,int b){set(l,t,r,b);}
                        public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
                        public void set(Rect r){set(r.left,r.top,r.right,r.bottom);}
                        public int width(){return right-left;} public int height(){return bottom-top;}
                        public boolean intersect(Rect r){
                            int l=Math.max(left,r.left),t=Math.max(top,r.top),rr=Math.min(right,r.right),b=Math.min(bottom,r.bottom);
                            if(l>=rr||t>=b)return false;
                            set(l,t,rr,b);return true;
                        }
                    }
                    """);
            write("android/os/Build.java", "package android.os; public class Build {public static class VERSION {public static int SDK_INT=28;} public static class VERSION_CODES {public static final int Q=29;} }");
            write("android/os/Looper.java", "package android.os; public class Looper {private static final Thread OWNER=Thread.currentThread(); private static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return Thread.currentThread()==OWNER?MAIN:null;} }");
            write("android/os/Handler.java", """
                    package android.os;
                    import java.util.*;
                    public class Handler {
                        public static final List<Runnable> immediate=new ArrayList<>();
                        public static final List<Task> delayed=new ArrayList<>();
                        public static class Task {public final Runnable action;public final long delay;Task(Runnable a,long d){action=a;delay=d;}}
                        public Handler(Looper looper){}
                        public boolean post(Runnable action){immediate.add(action);return true;}
                        public boolean postDelayed(Runnable action,long delay){delayed.add(new Task(action,delay));return true;}
                        public void removeCallbacks(Runnable action){immediate.removeIf(r->r==action);delayed.removeIf(t->t.action==action);}
                        public static void drain(){while(!immediate.isEmpty())immediate.remove(0).run();}
                        public static void expire(){while(!delayed.isEmpty())delayed.remove(0).action.run();}
                    }
                    """);
            write("android/view/Gravity.java", """
                    package android.view;
                    import android.graphics.Rect;
                    public class Gravity {
                        public static final int TOP=48,BOTTOM=80,LEFT=3,RIGHT=5,CENTER_HORIZONTAL=1,START=0x800003;
                        public static void apply(int g,int w,int h,Rect f,int x,int y,Rect out,int direction){
                            int horizontal=g&7;
                            if((g&0x800000)!=0&&direction==1)horizontal=(horizontal==LEFT?RIGHT:LEFT);
                            int l=horizontal==RIGHT?f.right-w-x:horizontal==CENTER_HORIZONTAL?f.left+(f.width()-w)/2+x:f.left+x;
                            int t=(g&112)==BOTTOM?f.bottom-h-y:f.top+y;
                            out.set(l,t,l+w,t+h);
                        }
                    }
                    """);
            write("android/view/ViewTreeObserver.java", """
                    package android.view;
                    import java.util.*;
                    public class ViewTreeObserver {
                        public interface OnGlobalLayoutListener {void onGlobalLayout();}
                        public interface OnPreDrawListener {boolean onPreDraw();}
                        public final List<OnGlobalLayoutListener> global=new ArrayList<>();
                        public final List<OnPreDrawListener> preDraw=new ArrayList<>();
                        public boolean isAlive(){return true;}
                        public void addOnGlobalLayoutListener(OnGlobalLayoutListener l){global.add(l);}
                        public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l){global.remove(l);}
                        public void addOnPreDrawListener(OnPreDrawListener l){preDraw.add(l);}
                        public void removeOnPreDrawListener(OnPreDrawListener l){preDraw.remove(l);}
                        public void layout(){for(var l:new ArrayList<>(global))l.onGlobalLayout();}
                        public boolean draw(){boolean result=true;for(var l:new ArrayList<>(preDraw))result&=l.onPreDraw();return result;}
                    }
                    """);
            write("android/view/View.java", """
                    package android.view;
                    import android.content.Context;
                    import android.graphics.Rect;
                    public class View {
                        public static final int ACCESSIBILITY_LIVE_REGION_POLITE=1;
                        public final Context context;
                        public int width=120,height=40,sx,sy,direction,requests,invalidations,backgroundColor;
                        public boolean attached=true;
                        public Object background;
                        public Rect visible;
                        public final ViewTreeObserver observer=new ViewTreeObserver();
                        public View(Context context){this.context=context;}
                        public Context getContext(){return context;}
                        public int getWidth(){return width;} public int getHeight(){return height;}
                        public boolean isAttachedToWindow(){return attached;}
                        public int getLayoutDirection(){return direction;}
                        public void getLocationOnScreen(int[] p){p[0]=sx;p[1]=sy;}
                        public void getWindowVisibleDisplayFrame(Rect r){if(visible==null)r.set(sx,sy,sx+width,sy+height);else r.set(visible);}
                        public ViewTreeObserver getViewTreeObserver(){return observer;}
                        public void requestLayout(){requests++;} public void invalidate(){invalidations++;}
                        public void setBackground(Object b){background=b;}
                        public void setBackgroundColor(int color){backgroundColor=color;}
                        public <T extends View> T findViewById(int id){return null;}
                        public static class MeasureSpec {
                            public static final int UNSPECIFIED=0,EXACTLY=0x40000000,AT_MOST=0x80000000;
                            public static int makeMeasureSpec(int size,int mode){return (size&0x3fffffff)|mode;}
                            public static int getSize(int spec){return spec&0x3fffffff;}
                            public static int getMode(int spec){return spec&0xc0000000;}
                        }
                    }
                    """);
            write("android/view/ViewGroup.java", """
                    package android.view;
                    import android.content.Context;
                    public class ViewGroup extends View {
                        public final java.util.List<View> children=new java.util.ArrayList<>();
                        public ViewGroup(Context c){super(c);}
                        public static class LayoutParams {public static final int WRAP_CONTENT=-2;public LayoutParams(int w,int h){}}
                        public void addView(View view){children.add(view);}
                        public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);}
                        public <T extends View> T findViewById(int id){
                            for(View child:children){T found=child.findViewById(id);if(found!=null)return found;}
                            return null;
                        }
                    }
                    """);
            write("android/widget/FrameLayout.java", """
                    package android.widget;
                    import android.view.*;
                    public class FrameLayout extends ViewGroup {
                        public int measuredWidthSpec,measuredHeightSpec;
                        public FrameLayout(android.content.Context c){super(c);}
                        protected void onMeasure(int w,int h){measuredWidthSpec=w;measuredHeightSpec=h;}
                    }
                    """);
            write("android/widget/TextView.java", """
                    package android.widget;
                    import android.view.View;
                    public class TextView extends View {
                        public CharSequence text="placeholder";public int color,live;
                        public TextView(android.content.Context c){super(c);}
                        public void setText(CharSequence t){text=t;}
                        public void setTextColor(int c){color=c;}
                        public void setAccessibilityLiveRegion(int v){live=v;}
                    }
                    """);
            write("android/view/ContextThemeWrapper.java", """
                    package android.view;
                    public class ContextThemeWrapper extends android.content.ContextWrapper {
                        private final android.content.res.Resources.Theme copied;
                        public ContextThemeWrapper(android.content.Context c,android.content.res.Resources.Theme theme){super(c);copied=theme;}
                        public android.content.res.Resources.Theme getTheme(){return copied;}
                    }
                    """);
            write("android/view/LayoutInflater.java", """
                    package android.view;
                    import android.content.Context;
                    import android.widget.TextView;
                    public class LayoutInflater {
                        private final Context context;
                        private LayoutInflater(Context c){context=c;}
                        public static LayoutInflater from(Context c){return new LayoutInflater(c);}
                        public View inflate(int id,ViewGroup parent,boolean attach){
                            return new ViewGroup(context) {
                                final TextView message=new TextView(context);
                                {addView(message);}
                                public <T extends View> T findViewById(int id){return (T)message;}
                            };
                        }
                    }
                    """);
            write("android/view/accessibility/AccessibilityManager.java", """
                    package android.view.accessibility;
                    public class AccessibilityManager {
                        public static final int FLAG_CONTENT_TEXT=1;
                        public static int calls,multiplier=1,lastFlags;
                        public int getRecommendedTimeoutMillis(int original,int flags){calls++;lastFlags=flags;return original*multiplier;}
                    }
                    """);
            write("android/view/WindowManager.java", """
                    package android.view;
                    public interface WindowManager {
                        class BadTokenException extends RuntimeException {}
                        class LayoutParams {
                            public static final int FLAG_NOT_FOCUSABLE=8,FLAG_NOT_TOUCHABLE=16,FLAG_DIM_BEHIND=2;
                            public int flags=FLAG_DIM_BEHIND,x,y,type=2;
                        }
                    }
                    """);
            write("android/view/Window.java", """
                    package android.view;
                    public class Window {
                        public final View decor;
                        public final WindowManager.LayoutParams attributes=new WindowManager.LayoutParams();
                        public int gravity,layoutWidth,layoutHeight,updates,baseX,baseY,failUpdate;
                        public boolean ignoreUpdates;
                        public Window(android.content.Context c){decor=new View(c);}
                        public View getDecorView(){return decor;}
                        public void addFlags(int flags){attributes.flags|=flags;}
                        public void clearFlags(int flags){attributes.flags&=~flags;}
                        public void setGravity(int gravity){this.gravity=gravity;}
                        public void setLayout(int width,int height){layoutWidth=width;layoutHeight=height;}
                        public WindowManager.LayoutParams getAttributes(){return attributes;}
                        public void setAttributes(WindowManager.LayoutParams attrs){
                            updates++;
                            if(failUpdate==1)throw new WindowManager.BadTokenException();
                            if(failUpdate==2)throw new IllegalStateException();
                            if(failUpdate==3)throw new IllegalArgumentException();
                            if(!ignoreUpdates){decor.sx=baseX+attrs.x;decor.sy=baseY+attrs.y;}
                        }
                    }
                    """);
            write("android/app/Activity.java", """
                    package android.app;
                    public class Activity extends android.content.ContextWrapper {
                        public final android.view.Window window=new android.view.Window(this);
                        public boolean finishing,destroyed;
                        public Activity(android.content.Context c){super(c);window.decor.width=500;window.decor.height=800;}
                        public android.view.Window getWindow(){return window;}
                        public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;}
                    }
                    """);
            write("android/app/Dialog.java", """
                    package android.app;
                    import android.view.*;
                    public class Dialog {
                        public interface OnDismissListener {void onDismiss(Dialog dialog);}
                        public static final java.util.List<Dialog> stack=new java.util.ArrayList<>();
                        public static Dialog last;public static boolean failNextShow;
                        public final Activity activity;
                        public Activity owner;
                        public final int style;
                        public final Window window;
                        public View content;
                        public boolean cancelable=true,outside=true,showing;
                        public OnDismissListener listener;
                        public Dialog(Activity activity,int style){this.activity=activity;this.style=style;window=new Window(activity);last=this;}
                        public void setOwnerActivity(Activity activity){owner=activity;}
                        public void setCancelable(boolean b){cancelable=b;} public void setCanceledOnTouchOutside(boolean b){outside=b;}
                        public Window getWindow(){return window;}
                        public void setContentView(View content){this.content=content;}
                        public void setOnDismissListener(OnDismissListener listener){this.listener=listener;}
                        public void show(){
                            if(failNextShow){failNextShow=false;throw new WindowManager.BadTokenException();}
                            showing=true;stack.add(this);
                            android.graphics.Rect frame=new android.graphics.Rect();
                            activity.window.decor.getWindowVisibleDisplayFrame(frame);
                            window.baseX=frame.left;window.baseY=frame.top;
                            window.decor.sx=window.baseX;window.decor.sy=window.baseY;
                        }
                        public void dismiss(){boolean old=showing;showing=false;stack.remove(this);if(old&&listener!=null)listener.onDismiss(this);}
                        public boolean isShowing(){return showing;}
                    }
                    """);
            write("android/widget/Toast.java", """
                    package android.widget;
                    public class Toast {
                        public static final int LENGTH_SHORT=0,LENGTH_LONG=1;
                        public static int made,shown,cancelled;public static Toast last;
                        public CharSequence text;public int duration;
                        public Toast(android.content.Context c){}
                        public static Toast makeText(android.content.Context c,CharSequence text,int duration){
                            made++;last=new Toast(c);last.text=text;last.duration=duration;return last;
                        }
                        public void show(){shown++;} public void cancel(){cancelled++;}
                        public void setText(CharSequence text){this.text=text;} public void setText(int id){}
                        public void setDuration(int duration){} public int getDuration(){return 0;}
                        public void setGravity(int g,int x,int y){} public int getGravity(){return 0;}
                        public int getXOffset(){return 0;} public int getYOffset(){return 0;}
                    }
                    """);
            write("in/anoopbhatia/mail/ToastHarness.java", scenarioSource());
        }

        private String scenarioSource() {
            return """
                    package in.anoopbhatia.mail;
                    import android.app.*;
                    import android.content.*;
                    import android.graphics.Rect;
                    import android.os.*;
                    import android.view.*;
                    import android.view.accessibility.AccessibilityManager;
                    import android.widget.*;
                    import java.lang.ref.WeakReference;

                    public class ToastHarness {
                        static Context app;
                        static Activity activity;
                        static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
                        static void equal(long expected,long actual,String message){check(expected==actual,message+": expected="+expected+" actual="+actual);}
                        static Object field(Object object,String name)throws Exception {
                            Class<?> type=object==null?ToastEx.class:object.getClass();
                            var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);
                        }
                        static void reset()throws Exception {
                            ToastEx previous=(ToastEx)field(null,"active");
                            if(previous!=null)previous.cancel();
                            ((java.util.List<?>)field(null,"resumed")).clear();
                            Handler.immediate.clear();Handler.delayed.clear();Dialog.stack.clear();Dialog.last=null;
                            Dialog.failNextShow=false;Toast.made=Toast.shown=Toast.cancelled=0;Toast.last=null;
                            AccessibilityManager.calls=0;AccessibilityManager.multiplier=1;Build.VERSION.SDK_INT=28;Log.warnings=0;
                            app=new Context();activity=new Activity(app);ToastEx.onActivityResumed(activity);
                        }
                        public static void run(String scenario)throws Exception {
                            reset();
                            try {
                                switch(scenario) {
                                    case "style":style();break;
                                    case "timeout":timeout();break;
                                    case "cleanup":cleanup();break;
                                    case "contexts":contexts();break;
                                    case "lifecycle":lifecycle();break;
                                    case "multiwindow":multiwindow();break;
                                    case "thread":thread();break;
                                    case "window":window();break;
                                    case "failure":failure();break;
                                    case "geometry":geometry();break;
                                    case "measure":measure();break;
                                    default:throw new AssertionError(scenario);
                                }
                            } finally {
                                ToastEx current=(ToastEx)field(null,"active");if(current!=null)current.cancel();
                            }
                        }
                        static TextView message(Dialog dialog){return dialog.content.findViewById(android.R.id.message);}
                        static View styled(Dialog dialog){return ((ViewGroup)dialog.content).getChildAt(0);}
                        static void display(Dialog dialog){
                            check(!dialog.window.decor.observer.draw(),"first pre-draw populates attached live region");
                            dialog.window.decor.observer.draw();
                            check(dialog.window.decor.observer.draw(),"position stabilizes before drawing");
                        }
                        static void released(Dialog dialog)throws Exception {
                            check(!dialog.isShowing(),"window dismissed");
                            equal(0,dialog.window.decor.observer.preDraw.size(),"notice listener removed");
                            equal(0,activity.window.decor.observer.global.size(),"host listener removed");
                            equal(0,Handler.delayed.size(),"timeout removed");
                            check(field(null,"active")==null,"static active cleared");
                        }
                        static long delay(){equal(1,Handler.delayed.size(),"one pending timeout");return Handler.delayed.get(0).delay;}

                        static void style()throws Exception {
                            activity.getTheme().marker=42;
                            ToastEx normal=ToastEx.makeText(new ContextWrapper(activity),"hello",Toast.LENGTH_SHORT);
                            normal.show();Dialog first=Dialog.last;
                            check(message(first).text.toString().isEmpty(),"XML placeholder removed before attach");
                            display(first);
                            equal(300,message(first).color,"normal info foreground");
                            equal(2,((android.graphics.drawable.Drawable)styled(first).background).id,"rounded background resource");
                            equal(1,message(first).live,"polite live region");
                            check(message(first).text.toString().equals("hello"),"initial message");
                            check(styled(first).context.getApplicationContext()==app,"notice stays in the application");
                            check(styled(first).context.getTheme()==activity.getTheme(),"notice inflates with the owning window theme, not a copy");
                            equal(42,styled(first).context.getTheme().marker,"owning window theme attributes");
                            normal.setText("updated");check(message(first).text.toString().equals("updated"),"live text update");
                            normal.setText(11);check(message(first).text.toString().equals("resource-11"),"resource update");
                            normal.cancel();
                            ToastEx bw=ToastEx.makeTextBw(activity,"hint",Toast.LENGTH_LONG);bw.show();display(Dialog.last);
                            equal(800,styled(Dialog.last).backgroundColor,"BW primary background");
                            equal(900,message(Dialog.last).color,"BW inverse text");
                            check(message(Dialog.last).text.toString().equals("hint"),"BW text");
                            bw.cancel();
                        }
                        static void timeout() {
                            ToastEx shortNotice=ToastEx.makeText(activity,"x",Toast.LENGTH_SHORT);shortNotice.show();
                            equal(2000,delay(),"API 28 short duration");equal(0,AccessibilityManager.calls,"no API 29 call on 28");shortNotice.cancel();
                            ToastEx longNotice=ToastEx.makeText(activity,"x",Toast.LENGTH_LONG);longNotice.show();
                            equal(3500,delay(),"API 28 long duration");longNotice.cancel();
                            Build.VERSION.SDK_INT=29;AccessibilityManager.multiplier=3;
                            shortNotice.show();equal(6000,delay(),"recommended short timeout");shortNotice.cancel();
                            longNotice.show();equal(10500,delay(),"recommended long timeout");
                            equal(2,AccessibilityManager.calls,"one recommendation per show");
                            equal(AccessibilityManager.FLAG_CONTENT_TEXT,AccessibilityManager.lastFlags,"text content flag");longNotice.cancel();
                            app.resources.dimensionId=1;app.resources.dimension=63;
                            equal(126,ToastEx.makeText(activity,"offset",0).getYOffset(),"two navigation heights");
                        }
                        static void cleanup()throws Exception {
                            ToastEx first=ToastEx.makeText(activity,"first",0),second=ToastEx.makeText(activity,"second",0);
                            first.show();Dialog old=Dialog.last;
                            equal(1,old.window.decor.observer.preDraw.size(),"notice listener installed");
                            equal(1,activity.window.decor.observer.global.size(),"host listener installed");
                            second.show();Dialog current=Dialog.last;
                            check(!old.isShowing(),"replacement dismisses old notice");
                            equal(0,old.window.decor.observer.preDraw.size(),"replaced listener removed");
                            check(field(first,"notice")==null,"old handle releases Activity-owned Notice");
                            equal(1,Handler.delayed.size(),"replacement timer only");
                            first.cancel();check(current.isShowing(),"old cancel cannot cancel replacement");
                            current.dismiss();released(current);check(field(second,"notice")==null,"external dismiss releases handle");
                            second.show();Dialog repeated=Dialog.last;second.show();check(!repeated.isShowing(),"repeated show replaces same handle");
                            Dialog expired=Dialog.last;Handler.expire();released(expired);
                        }
                        static void contexts()throws Exception {
                            ToastEx wrapped=ToastEx.makeText(new ContextWrapper(activity),"wrapped",0);wrapped.show();
                            check(Dialog.last.owner==activity,"unwrapped Activity owner");wrapped.cancel();
                            ToastEx foreground=ToastEx.makeText(app,"foreground",0);foreground.show();
                            check(Dialog.last.owner==activity,"application notice uses foreground owner");equal(0,Toast.made,"no foreground generic toast");foreground.cancel();
                            ToastEx.onActivityPaused(activity);
                            ToastEx background=ToastEx.makeText(app,"background",Toast.LENGTH_LONG);background.show();
                            equal(1,Toast.made,"background system fallback");equal(1,Toast.shown,"fallback shown");
                            check(Toast.last.text.toString().equals("background"),"fallback message retained");
                            equal(Toast.LENGTH_LONG,Toast.last.duration,"fallback duration");
                            background.setText("changed");check(Toast.last.text.toString().equals("changed"),"fallback update");
                            background.cancel();equal(1,Toast.cancelled,"fallback cancel");equal(0,Handler.delayed.size(),"fallback timeout removed");
                        }
                        static void lifecycle()throws Exception {
                            ToastEx handle=ToastEx.makeText(activity,"x",0);handle.show();Dialog visible=Dialog.last;
                            ToastEx.onActivityPaused(activity);released(visible);handle.show();
                            equal(0,Toast.made,"paused Activity is not redirected to system toast");check(!visible.isShowing(),"stale handle stays hidden");
                            ToastEx.onActivityResumed(activity);activity.finishing=true;handle.show();check(field(null,"active")==null,"finishing rejected");
                            activity.finishing=false;activity.destroyed=true;handle.show();check(field(null,"active")==null,"destroyed rejected");
                            activity.destroyed=false;activity.window.decor.attached=false;handle.show();check(field(null,"active")==null,"detached rejected");
                            activity.window.decor.attached=true;
                            ((WeakReference<?>)field(handle,"owner")).clear();handle.show();check(field(null,"active")==null,"collected owner rejected");
                            equal(0,Toast.made,"collected owner never becomes fallback");equal(0,Handler.delayed.size(),"no stale timers");
                        }
                        static void multiwindow()throws Exception {
                            Activity second=new Activity(app);ToastEx.onActivityResumed(second);
                            ToastEx older=ToastEx.makeText(activity,"old still resumed",0);older.show();
                            check(Dialog.last.owner==activity,"still-resumed owner accepted even when not most recent");older.cancel();
                            ToastEx latest=ToastEx.makeText(app,"latest",0);latest.show();Dialog notice=Dialog.last;
                            check(notice.owner==second,"application context chooses most recent resumed Activity");
                            ToastEx.onActivityPaused(activity);check(notice.isShowing(),"other Activity pause cannot cancel owner");
                            ToastEx.onActivityPaused(second);check(!notice.isShowing(),"owner pause cleans up");
                            equal(0,second.window.decor.observer.global.size(),"second host listener removed");
                        }
                        static void thread()throws Exception {
                            ToastEx notice=ToastEx.makeText(activity,"before",0);
                            Thread worker=new Thread(()->{notice.setText("after");notice.show();});worker.start();worker.join();
                            equal(2,Handler.immediate.size(),"worker operations queued in order");check(Dialog.last==null,"no worker window operation");
                            Handler.drain();Dialog shown=Dialog.last;display(shown);
                            check(message(shown).text.toString().equals("after"),"text precedes show");equal(1,Handler.delayed.size(),"draining main queue does not expire timer");
                            Thread cancel=new Thread(notice::cancel);cancel.start();cancel.join();check(shown.isShowing(),"worker cancel marshalled");
                            Handler.drain();released(shown);
                            Thread late=new Thread(notice::show);late.start();late.join();ToastEx.onActivityPaused(activity);Handler.drain();
                            check(field(null,"active")==null,"queued show rechecks lifecycle");equal(0,Toast.made,"queued stale notice not redirected");
                        }
                        static void window() {
                            Dialog modal=new Dialog(activity,99);View original=new View(app);modal.setContentView(original);modal.show();
                            ToastEx handle=ToastEx.makeText(activity,"above modal",0);handle.show();Dialog notice=Dialog.last;
                            check(Dialog.stack.get(Dialog.stack.size()-1)==notice,"new application window above prior modal in model");
                            check(notice.owner==activity,"explicit owner");equal(4,notice.style,"window-only theme");
                            equal(24,notice.window.attributes.flags,"nonfocusable/nontouchable without dim");
                            equal(2,notice.window.attributes.type,"ordinary application type, no overlay permission");
                            equal(Gravity.TOP|Gravity.LEFT,notice.window.gravity,"screen correction uses top-left window gravity");
                            equal(-2,notice.window.layoutWidth,"wrap window width");equal(-2,notice.window.layoutHeight,"wrap window height");
                            check(!notice.cancelable&&!notice.outside,"not interactive");
                            check(modal.content==original&&modal.isShowing(),"modal content and visibility unchanged");
                            equal(2,modal.window.attributes.flags,"modal window flags untouched");
                            modal.dismiss();check(notice.isShowing(),"modal dismissal does not lose notice");handle.cancel();
                        }
                        static void failure()throws Exception {
                            Dialog.failNextShow=true;ToastEx notice=ToastEx.makeText(activity,"x",0);notice.show();
                            released(Dialog.last);equal(1,Log.warnings,"failed token logged");check(field(notice,"notice")==null,"failed show releases owner");
                            for(int failure=1;failure<=3;failure++) {
                                notice.show();Dialog window=Dialog.last;window.window.failUpdate=failure;
                                check(!window.window.decor.observer.draw(),"populate before positioning");
                                window.window.decor.observer.draw();released(window);
                                check(field(notice,"notice")==null,"failed asynchronous update releases owner");
                            }
                            equal(4,Log.warnings,"all expected window failure types handled");
                        }
                        static void geometry() {
                            View host=activity.window.decor;host.sx=100;host.sy=50;host.width=400;host.height=600;host.direction=1;
                            host.visible=new Rect(110,74,480,620);
                            ToastEx hint=ToastEx.makeTextBw(activity,"hint",0);hint.setGravity(Gravity.TOP|Gravity.START,120,90);hint.show();
                            Dialog window=Dialog.last;display(window);
                            equal(120,window.window.decor.sx,"physical screen X even in RTL");equal(90,window.window.decor.sy,"physical screen Y without doubled insets");hint.cancel();
                            ToastEx normal=ToastEx.makeText(activity,"normal",0);normal.show();window=Dialog.last;display(window);
                            equal(235,window.window.decor.sx,"bottom horizontal center");equal(468,window.window.decor.sy,"112px above visible bottom");
                            host.visible.bottom=400;host.observer.layout();check(window.window.decor.requests>0,"frame change requests remeasurement");
                            window.window.decor.observer.draw();equal(248,window.window.decor.sy,"moves above changed keyboard frame");normal.cancel();
                            hint.setGravity(Gravity.TOP|Gravity.START,-200,1000);hint.show();window=Dialog.last;display(window);
                            equal(110,window.window.decor.sx,"left edge clamped");equal(360,window.window.decor.sy,"bottom edge clamped");hint.cancel();
                        }
                        static void measure()throws Exception {
                            activity.window.decor.visible=new Rect(10,20,230,200);
                            ToastEx notice=ToastEx.makeText(activity,"x",0);notice.show();Dialog window=Dialog.last;
                            FrameLayout content=(FrameLayout)window.content;
                            var method=content.getClass().getDeclaredMethod("onMeasure",int.class,int.class);method.setAccessible(true);
                            method.invoke(content,View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.AT_MOST));
                            equal(220,View.MeasureSpec.getSize(content.measuredWidthSpec),"visible frame width cap");
                            equal(180,View.MeasureSpec.getSize(content.measuredHeightSpec),"visible frame height cap");
                            equal(View.MeasureSpec.AT_MOST,View.MeasureSpec.getMode(content.measuredWidthSpec),"content can wrap below cap");
                            method.invoke(content,View.MeasureSpec.makeMeasureSpec(80,View.MeasureSpec.EXACTLY),0);
                            equal(80,View.MeasureSpec.getSize(content.measuredWidthSpec),"smaller parent cap preserved");
                            equal(180,View.MeasureSpec.getSize(content.measuredHeightSpec),"unspecified parent still bounded to visible frame");
                            window.window.ignoreUpdates=true;
                            check(!window.window.decor.observer.draw(),"initial attached population");
                            for(int i=0;i<3;i++)check(!window.window.decor.observer.draw(),"bounded correction defers drawing");
                            check(window.window.decor.observer.draw(),"OEM clamp cannot create endless pre-draw loop");
                            equal(3,window.window.updates,"maximum correction requests");notice.cancel();
                        }
                    }
                    """;
        }
    }
}
