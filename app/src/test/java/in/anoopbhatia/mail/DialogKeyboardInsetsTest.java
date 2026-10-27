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

/** Executes the complete production helper against host doubles, not Android/OEM window layout. */
public class DialogKeyboardInsetsTest {
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

    @Test public void nativeInsetsPreserveExistingMaskAndNonAdjustmentBits() throws Exception { harness.run("native"); }
    @Test public void api28And29ShrinkAndRestoreWholePanelIncludingButtons() throws Exception { harness.run("keyboard"); }
    @Test public void hostCallbacksDoNotApplyStaleWindowOffsets() throws Exception { harness.run("ordering"); }
    @Test public void rotationAndNonzeroHostOriginClipToHostBounds() throws Exception { harness.run("rotation"); }
    @Test public void initiallyVisibleKeyboardNeedsNoHiddenBaseline() throws Exception { harness.run("initial"); }
    @Test public void emptyAndUnattachedFramesAreIgnored() throws Exception { harness.run("invalid"); }
    @Test public void detachReattachAndIndependentDialogsOwnTheirListeners() throws Exception { harness.run("lifecycle"); }
    @Test public void measurementRespectsParentLimitAndCanGrowAgain() throws Exception { harness.run("measure"); }

    @Test
    public void bothDialogsRetainScrollFocusValidationAndEditorActions() throws Exception {
        String helper = read("app/src/main/java/in/anoopbhatia/mail/DialogKeyboardInsets.java");
        assertFalse(helper.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertFalse(helper.contains("SOFT_INPUT_ADJUST_PAN"));
        assertFalse(helper.contains("SuppressWarnings"));
        for (String name : new String[]{"Import", "Export"}) {
            String source = read("app/src/main/java/in/anoopbhatia/mail/FragmentDialog" + name + ".java");
            assertTrue(source.contains("DialogKeyboardInsets.install(dialog, requireActivity().getWindow().getDecorView())"));
            assertFalse(source.contains("SOFT_INPUT_ADJUST_RESIZE"));
            assertTrue(source.contains(".setPositiveButton("));
            assertTrue(source.contains(".setNegativeButton("));
            assertTrue(source.contains("EditorInfo.IME_ACTION_DONE"));
            assertTrue(source.contains("btnOk.performClick()"));
            String layout = read("app/src/main/res/layout/dialog_" + name.toLowerCase(java.util.Locale.ROOT) + ".xml");
            assertTrue(layout.contains("<ScrollView "));
            assertTrue(layout.contains("<requestFocus />"));
            assertTrue(layout.contains("android:inputType=\"textPassword\""));
        }
        String export = read("app/src/main/java/in/anoopbhatia/mail/FragmentDialogExport.java");
        assertTrue(export.contains("p1.equals(p2)"));
        assertTrue(export.contains("if (btnOk.isEnabled())"));
        String layout = read("app/src/main/res/layout/dialog_export.xml");
        assertTrue(layout.contains("android:imeOptions=\"actionNext\""));
        assertTrue(layout.contains("android:imeOptions=\"actionDone\""));
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
        private final Path root = Files.createTempDirectory("dialog-keyboard-insets");
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
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
            }
            Path log = root.resolve("javac.log");
            Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            if (!done)
                process.destroyForcibly();
            assertTrue("Host compilation failed: " + Files.readString(log), done && process.exitValue() == 0);
            loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
            run = loader.loadClass("in.anoopbhatia.mail.KeyboardHarness").getMethod("run", String.class);
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
            write("in/anoopbhatia/mail/DialogKeyboardInsets.java", read("app/src/main/java/in/anoopbhatia/mail/DialogKeyboardInsets.java"));
            write("android/R.java", "package android; public final class R { public static final class id { public static final int content=1; } }");
            write("android/content/Context.java", "package android.content; public class Context {}");
            write("android/os/Build.java", "package android.os; public class Build { public static class VERSION { public static int SDK_INT; } public static class VERSION_CODES { public static final int R=30; } }");
            write("android/graphics/Rect.java", """
                    package android.graphics;
                    public class Rect {
                        public int left, top, right, bottom;
                        public Rect() {}
                        public Rect(int l,int t,int r,int b) {set(l,t,r,b);}
                        public void set(int l,int t,int r,int b) {left=l;top=t;right=r;bottom=b;}
                        public int height() {return bottom-top;}
                        public boolean intersect(Rect r) {
                            if (left<r.right && r.left<right && top<r.bottom && r.top<bottom) {
                                set(Math.max(left,r.left),Math.max(top,r.top),Math.min(right,r.right),Math.min(bottom,r.bottom));
                                return true;
                            }
                            return false;
                        }
                    }
                    """);
            write("android/view/ViewTreeObserver.java", """
                    package android.view;
                    public class ViewTreeObserver {
                        public interface OnGlobalLayoutListener {void onGlobalLayout();}
                        public boolean alive=true;
                        public final java.util.List<OnGlobalLayoutListener> listeners=new java.util.ArrayList<>();
                        public boolean isAlive() {return alive;}
                        public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) {listeners.add(l);}
                        public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l) {listeners.remove(l);}
                        public void fire() {for(var l:new java.util.ArrayList<>(listeners)) l.onGlobalLayout();}
                    }
                    """);
            write("android/view/View.java", """
                    package android.view;
                    import android.content.Context;
                    import android.graphics.Rect;
                    public class View {
                        public int width=400,height,naturalHeight,screenX,screenY,paddingTop,paddingBottom,requests;
                        public boolean attached;
                        private final Context context;
                        private ViewGroup.LayoutParams params;
                        public final ViewTreeObserver observer=new ViewTreeObserver();
                        private final java.util.List<OnAttachStateChangeListener> attachListeners=new java.util.ArrayList<>();
                        public View() {this(new Context());}
                        public View(Context c) {context=c;}
                        public Context getContext() {return context;}
                        public ViewGroup.LayoutParams getLayoutParams() {return params;}
                        public void setLayoutParams(ViewGroup.LayoutParams p) {params=p;}
                        public int getHeight() {return height;}
                        public int getWidth() {return width;}
                        public int getPaddingTop() {return paddingTop;}
                        public int getPaddingBottom() {return paddingBottom;}
                        public void getLocationOnScreen(int[] a) {a[0]=screenX;a[1]=screenY;}
                        public void getWindowVisibleDisplayFrame(Rect out) {throw new AssertionError("Must read host frame");}
                        public boolean isAttachedToWindow() {return attached;}
                        public ViewTreeObserver getViewTreeObserver() {return observer;}
                        public void requestLayout() {requests++;}
                        public interface OnAttachStateChangeListener {
                            void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v);
                        }
                        public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) {attachListeners.add(l);}
                        public void attach() {attached=true;for(var l:attachListeners)l.onViewAttachedToWindow(this);}
                        public void detach() {attached=false;for(var l:attachListeners)l.onViewDetachedFromWindow(this);}
                        public final void measure(int w,int h) {onMeasure(w,h);}
                        protected void onMeasure(int w,int h) {height=resolve(naturalHeight,h);}
                        public static int resolve(int desired,int spec) {
                            int mode=MeasureSpec.getMode(spec),size=MeasureSpec.getSize(spec);
                            return mode==MeasureSpec.UNSPECIFIED?desired:(mode==MeasureSpec.EXACTLY?size:Math.min(desired,size));
                        }
                        public static class MeasureSpec {
                            public static final int UNSPECIFIED=0,EXACTLY=0x40000000,AT_MOST=0x80000000;
                            public static int getMode(int s) {return s&0xc0000000;}
                            public static int getSize(int s) {return s&0x3fffffff;}
                            public static int makeMeasureSpec(int size,int mode) {return (size&0x3fffffff)|mode;}
                        }
                    }
                    """);
            write("android/view/ViewGroup.java", """
                    package android.view;
                    import android.content.Context;
                    public class ViewGroup extends View {
                        public ViewGroup() {} public ViewGroup(Context c) {super(c);}
                        public static class LayoutParams {
                            public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;
                            public int width,height;
                            public LayoutParams(int w,int h) {width=w;height=h;}
                        }
                        public final java.util.List<View> children=new java.util.ArrayList<>();
                        public int getChildCount() {return children.size();}
                        public View getChildAt(int i) {return children.get(i);}
                        public void removeViewAt(int i) {children.remove(i);}
                        public void addView(View v) {children.add(v);}
                        public void addView(View v,LayoutParams p) {v.setLayoutParams(p);addView(v);}
                    }
                    """);
            write("android/widget/FrameLayout.java", """
                    package android.widget;
                    import android.content.Context;
                    import android.view.*;
                    public class FrameLayout extends ViewGroup {
                        public FrameLayout(Context c) {super(c);}
                        protected void onMeasure(int width,int heightSpec) {
                            int desired=0;
                            for(View child:children) {child.measure(width,heightSpec);desired=Math.max(desired,child.getHeight());}
                            height=resolve(desired,heightSpec);
                        }
                    }
                    """);
            write("android/view/WindowInsets.java", """
                    package android.view;
                    public class WindowInsets {
                        public static void require30() {if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("API 30 call on legacy");}
                        public static class Type {
                            public static int systemBars() {require30();return 7;}
                            public static int ime() {require30();return 8;}
                        }
                    }
                    """);
            write("android/view/WindowManager.java", """
                    package android.view;
                    public interface WindowManager {
                        class LayoutParams {
                            public static final int SOFT_INPUT_MASK_ADJUST=0xf0,SOFT_INPUT_ADJUST_NOTHING=0x30;
                            public int softInputMode=0x1058,y;
                            public int fit=64;
                            public boolean ignore=true;
                            public int getFitInsetsTypes() {WindowInsets.require30();return fit;}
                            public void setFitInsetsTypes(int types) {WindowInsets.require30();fit=types;}
                            public void setFitInsetsIgnoringVisibility(boolean value) {WindowInsets.require30();ignore=value;}
                        }
                    }
                    """);
            write("android/view/Window.java", """
                    package android.view;
                    public class Window {
                        public final View decor=new View();
                        public final ViewGroup content=new ViewGroup();
                        public final WindowManager.LayoutParams attributes=new WindowManager.LayoutParams();
                        public int writes,renderedY,parentTop=40,parentBottom=940;
                        public WindowManager.LayoutParams getAttributes() {return attributes;}
                        public void setAttributes(WindowManager.LayoutParams p) {writes++;decor.requestLayout();}
                        public View getDecorView() {return decor;}
                        public void applyGeometry() {
                            renderedY=attributes.y;
                            decor.screenY=parentTop+(parentBottom-parentTop-decor.height)/2+renderedY;
                        }
                    }
                    """);
            write("android/app/Dialog.java", """
                    package android.app;
                    import android.view.*;
                    public class Dialog {
                        public final Window window=new Window();
                        public int creates;
                        public Window getWindow() {return window;}
                        public void create() {creates++;}
                        @SuppressWarnings("unchecked")
                        public <T extends View> T findViewById(int id) {return (T)window.content;}
                    }
                    """);
            write("in/anoopbhatia/mail/KeyboardHarness.java", """
                    package in.anoopbhatia.mail;
                    import android.app.Dialog;
                    import android.graphics.Rect;
                    import android.os.Build;
                    import android.view.*;
                    import static android.view.View.MeasureSpec.*;
                    public class KeyboardHarness {
                        static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
                        static class Host extends View {
                            final Rect frame=new Rect(0,40,400,940);
                            Host() {attached=true;height=1000;}
                            public void getWindowVisibleDisplayFrame(Rect out) {out.set(frame.left,frame.top,frame.right,frame.bottom);}
                        }
                        // Models AlertDialogLayout's fixed button reservation before measuring its scrolling custom panel.
                        static class Panel extends View {
                            final int buttons=56;
                            int scrollHeight;
                            Panel() {naturalHeight=856;}
                            protected void onMeasure(int w,int h) {
                                height=resolve(naturalHeight,h);
                                scrollHeight=Math.max(0,height-buttons);
                            }
                        }
                        static class Fixture {
                            final Dialog dialog=new Dialog();
                            final Host host=new Host();
                            final Window window=dialog.window;
                            final Panel panel=new Panel();
                            View body;
                            Fixture(int api,boolean attach) {
                                Build.VERSION.SDK_INT=api;
                                window.decor.paddingTop=window.decor.paddingBottom=8;
                                window.content.paddingTop=window.content.paddingBottom=4;
                                window.content.addView(panel,new ViewGroup.LayoutParams(-1,-2));
                                DialogKeyboardInsets.install(dialog,host);
                                if(api<30)body=window.content.getChildAt(0);
                                if(attach)window.decor.attach();
                            }
                            void pass() {
                                body.measure(makeMeasureSpec(400,EXACTLY),makeMeasureSpec(900,AT_MOST));
                                window.decor.height=body.height+24;
                                window.applyGeometry();
                                window.decor.observer.fire();
                            }
                            void settle() {for(int i=0;i<6;i++)pass();}
                            void bounds(int top,int bottom) {
                                check(window.decor.screenY==DialogKeyboardInsets.centeredTop(top,bottom,window.decor.height),"not centered");
                                check(window.decor.screenY>=top && window.decor.screenY+window.decor.height<=bottom,"outside usable frame");
                            }
                        }
                        public static void run(String scenario) {
                            switch(scenario) {
                                case "native" -> nativeInsets();
                                case "keyboard" -> keyboard();
                                case "ordering" -> ordering();
                                case "rotation" -> rotation();
                                case "initial" -> initial();
                                case "invalid" -> invalid();
                                case "lifecycle" -> lifecycle();
                                case "measure" -> measure();
                                default -> throw new AssertionError(scenario);
                            }
                        }
                        static void nativeInsets() {
                            for(int api:new int[]{30,31,35,37}) {
                                Fixture f=new Fixture(api,true);
                                var p=f.window.attributes;
                                check(p.fit==(64|7|8),"lost existing mask or missing bars/IME");
                                check(!p.ignore,"must follow IME visibility");
                                check(p.softInputMode==((0x1058&~0xf0)|0x30),"lost state/non-adjust bits");
                                check(f.dialog.creates==0 && f.window.content.getChildAt(0)==f.panel,"native path rewrote panel");
                                check(f.host.observer.listeners.isEmpty(),"native legacy listener");
                            }
                        }
                        static void keyboard() {
                            for(int api:new int[]{28,29}) {
                                Fixture f=new Fixture(api,true);f.settle();f.bounds(40,940);
                                check(f.panel.height==856,"natural panel changed");
                                check(((ViewGroup)f.body).getChildAt(0)==f.panel,"must wrap whole panel");
                                check(f.window.attributes.softInputMode==((0x1058&~0xf0)|0x30),"legacy adjustment bits");
                                f.host.frame.bottom=540;f.host.observer.fire();f.settle();f.bounds(40,540);
                                check(f.panel.height==476 && f.panel.scrollHeight==420,"buttons/padding not reserved");
                                int y=f.window.attributes.y;f.settle();check(f.window.attributes.y==y,"position drift");
                                f.host.frame.bottom=940;f.host.observer.fire();f.settle();f.bounds(40,940);
                                check(f.panel.height==856,"did not restore natural size");
                            }
                        }
                        static void ordering() {
                            Fixture f=new Fixture(28,true);f.settle();
                            f.host.frame.bottom=540;
                            int writes=f.window.writes,y=f.window.attributes.y,requests=f.window.decor.requests;
                            for(int i=0;i<5;i++)f.host.observer.fire();
                            check(f.window.writes==writes && f.window.attributes.y==y,"host used stale screen offset");
                            check(f.window.decor.requests==requests+5,"host did not schedule layout");
                            f.settle();f.bounds(40,540);
                            writes=f.window.writes;f.settle();check(f.window.writes==writes,"redundant window updates");
                        }
                        static void rotation() {
                            Fixture f=new Fixture(29,true);f.settle();
                            f.host.screenY=100;f.host.height=400;
                            f.host.frame.set(-10,40,800,600);
                            f.window.parentTop=100;f.window.parentBottom=500;
                            f.host.observer.fire();f.settle();f.bounds(100,500);
                            check(f.panel.height==376,"did not intersect host window bounds");
                            f.host.screenY=0;f.host.height=600;f.host.frame.set(0,24,400,560);
                            f.window.parentTop=24;f.window.parentBottom=560;f.settle();f.bounds(24,560);
                        }
                        static void initial() {
                            Fixture f=new Fixture(28,false);
                            f.host.frame.set(0,100,400,420);
                            f.window.decor.attach();f.settle();f.bounds(100,420);
                            check(f.panel.height==296,"initial IME requires no closed baseline");
                        }
                        static void invalid() {
                            Fixture f=new Fixture(28,true);f.settle();
                            int writes=f.window.writes,requests=f.body.requests;
                            f.host.frame.set(0,0,0,0);f.window.decor.observer.fire();
                            check(f.window.writes==writes && f.body.requests==requests,"empty frame changed layout");
                            f.host.frame.set(0,40,400,540);f.host.attached=false;f.window.decor.observer.fire();
                            check(f.window.writes==writes && f.body.requests==requests,"unattached host changed layout");
                            f.host.attached=true;f.window.decor.attached=false;f.window.decor.observer.fire();
                            check(f.window.writes==writes && f.body.requests==requests,"unattached dialog changed layout");
                        }
                        static void lifecycle() {
                            Fixture f=new Fixture(28,false);check(f.host.observer.listeners.isEmpty(),"listener before attach");
                            f.window.decor.attach();check(f.host.observer.listeners.size()==1,"missing host listener");
                            f.window.decor.detach();check(f.host.observer.listeners.isEmpty() && f.window.decor.observer.listeners.isEmpty(),"leaked listeners");
                            f.window.decor.attach();check(f.host.observer.listeners.size()==1 && f.window.decor.observer.listeners.size()==1,"reattach duplicated listener");
                            Dialog second=new Dialog();second.window.content.addView(new Panel());
                            DialogKeyboardInsets.install(second,f.host);second.window.decor.attach();
                            check(f.host.observer.listeners.size()==2,"dialogs not independent");
                            f.window.decor.detach();check(f.host.observer.listeners.size()==1,"closed other dialog listener");
                            second.window.decor.detach();check(f.host.observer.listeners.isEmpty(),"last listener leaked");
                        }
                        static void measure() {
                            Fixture f=new Fixture(28,true);f.host.frame.bottom=540;f.settle();
                            f.body.measure(0,makeMeasureSpec(300,AT_MOST));check(f.body.height==300,"exceeded parent cap");
                            f.body.measure(0,makeMeasureSpec(300,EXACTLY));check(f.body.height==300,"ignored exact upper bound");
                            f.body.measure(0,makeMeasureSpec(0,UNSPECIFIED));check(f.body.height==476,"ignored own cap");
                            f.host.frame.bottom=940;f.settle();check(f.body.height==856,"cap cannot grow");
                        }
                    }
                    """);
        }
    }
}
