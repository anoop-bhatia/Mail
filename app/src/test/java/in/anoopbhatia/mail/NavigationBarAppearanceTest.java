package in.anoopbhatia.mail;

import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;

/** Host tests for the complete production helper, using deliberately small Android/View doubles. */
public class NavigationBarAppearanceTest {
  static Harness h;
  @BeforeClass public static void compile() throws Exception { h = new Harness(); }
  @AfterClass public static void close() throws Exception { if (h != null) h.close(); }
  @Test public void allEdgesAvoidDoublePaintingAndIgnoreStatusCutout() throws Exception { h.run("edges"); }
  @Test public void translucentCornersArePaintedOnceAndSmallWindowsClamp() throws Exception { h.run("corners"); }
  @Test public void resizeRotationHideNavigationAndNullInsetsAreSafe() throws Exception { h.run("changes"); }
  @Test public void imeSuppressesDockedAreaButNotFloatingKeyboard() throws Exception { h.run("ime"); }
  @Test public void iconsUseBlackWhiteCustomAndTransparentDefaultAppearance() throws Exception { h.run("icons"); }
  @Test public void attachDetachReattachAndCloseCleanUpOnlyOwningWindow() throws Exception { h.run("lifecycle"); }
  @Test public void activityAndOptionsUseTransparentEdgeToEdgeDelegate() throws Exception {
    String activity = h.read("app/src/main/java/in/anoopbhatia/mail/ActivityBase.java");
    assertTrue(activity.contains("EdgeToEdge.enable(this") && activity.contains("SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)") && activity.contains("SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)"));
    assertTrue(activity.contains("new NavigationBarAppearance(window, colorNavigation)"));
    assertTrue(!activity.contains("window.setNavigationBarColor"));
    String options = h.read("app/src/main/java/in/anoopbhatia/mail/FragmentOptionsDisplay.java");
    assertTrue(options.contains("((ActivityBase) activity).setNavigationBarColor(color)"));
    assertTrue(options.contains("setNavigationBarColor(Color.BLACK)"));
    assertTrue(!options.contains("SDK_INT >= 35") && !options.contains("SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM"));
  }

  static final class Harness implements AutoCloseable {
    final Path root=Files.createTempDirectory("nav-appearance"); final URLClassLoader loader; final Method run;
    Harness() throws Exception {
      src("in/anoopbhatia/mail/NavigationBarAppearance.java", read("app/src/main/java/in/anoopbhatia/mail/NavigationBarAppearance.java"));
      src("android/content/Context.java", "package android.content; public class Context {}");
      src("android/graphics/Color.java", "package android.graphics; public class Color { public static final int TRANSPARENT=0; public static final int BLACK=0xff000000, WHITE=0xffffffff; }");
      src("android/graphics/Paint.java", "package android.graphics; public class Paint { int color; public void setColor(int c){color=c;} public int getColor(){return color;} }");
      src("android/graphics/Canvas.java", "package android.graphics; import java.util.*; public class Canvas { public final List<int[]> rects=new ArrayList<>(); public void drawRect(float l,float t,float r,float b,Paint p){if(r>l && b>t)rects.add(new int[]{(int)l,(int)t,(int)r,(int)b,p.getColor()});} }");
      src("android/view/ViewTreeObserver.java", "package android.view; import java.util.*; public class ViewTreeObserver { boolean alive=true; public final List<OnPreDrawListener> ls=new ArrayList<>(); public interface OnPreDrawListener{boolean onPreDraw();} public boolean isAlive(){return alive;} public void addOnPreDrawListener(OnPreDrawListener l){ls.add(l);} public void removeOnPreDrawListener(OnPreDrawListener l){ls.remove(l);} public void fire(){for(OnPreDrawListener l:new ArrayList<>(ls))l.onPreDraw();} }");
      src("android/view/View.java", "package android.view; import android.content.*; import android.graphics.*; import java.util.*; public class View { protected final Context c; int w,h; boolean attached; final ViewTreeObserver vto=new ViewTreeObserver(); final List<OnAttachStateChangeListener> als=new ArrayList<>(); public View(Context c){this.c=c;} public Context getContext(){return c;} public boolean isAttachedToWindow(){return attached;} public ViewTreeObserver getViewTreeObserver(){return vto;} public int getWidth(){return w;} public int getHeight(){return h;} public void layout(int l,int t,int r,int b){w=r-l;h=b-t;} public void invalidate(){} public void setImportantForAccessibility(int x){} public void setWillNotDraw(boolean x){} public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){als.add(l);} public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){als.remove(l);} protected void onDraw(Canvas c){} public void draw(Canvas c){onDraw(c);} public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);} public static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2; }");
      src("android/view/ViewGroup.java", "package android.view; import android.content.*; import java.util.*; public class ViewGroup extends View { public final Overlay overlay=new Overlay(); public ViewGroup(Context c){super(c);} public Overlay getOverlay(){return overlay;} public void requestApplyInsets(){} public class Overlay {final List<View> views=new ArrayList<>(); public void add(View v){views.add(v);} public void remove(View v){views.remove(v);} public int size(){return views.size();}} public void attach(){attached=true;for(var l:new ArrayList<>(als))l.onViewAttachedToWindow(this);} public void detach(){attached=false;for(var l:new ArrayList<>(als))l.onViewDetachedFromWindow(this);} }");
      src("android/view/Window.java", "package android.view; import android.content.*; public class Window { final Context c=new Context(); final ViewGroup d=new ViewGroup(c); public Context getContext(){return c;} public ViewGroup getDecorView(){return d;} }");
      src("androidx/core/graphics/Insets.java", "package androidx.core.graphics; public final class Insets { public final int left,top,right,bottom; public static final Insets NONE=new Insets(0,0,0,0); public Insets(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;} public static Insets of(int l,int t,int r,int b){return new Insets(l,t,r,b);} public boolean equals(Object o){if(!(o instanceof Insets))return false; Insets x=(Insets)o;return left==x.left&&top==x.top&&right==x.right&&bottom==x.bottom;} public int hashCode(){return left+31*top+997*right+31337*bottom;} }");
      src("androidx/core/graphics/ColorUtils.java", "package androidx.core.graphics; public class ColorUtils { public static int setAlphaComponent(int c,int a){return (c&0xffffff)|(a<<24);} public static int compositeColors(int fg,int bg){int a=(fg>>>24); if(a==255)return fg; int ba=bg>>>24; int oa=a+(ba*(255-a)+127)/255; if(oa==0)return 0; int r=((fg>>16&255)*a+(bg>>16&255)*ba*(255-a)/255)/oa; int g=((fg>>8&255)*a+(bg>>8&255)*ba*(255-a)/255)/oa; int b=((fg&255)*a+(bg&255)*ba*(255-a)/255)/oa; return oa<<24|r<<16|g<<8|b;} public static double calculateContrast(int x,int y){double l=lum(x); return (Math.max(l,lum(y))+.05)/(Math.min(l,lum(y))+.05);} static double lum(int x){double r=((x>>16)&255)/255.,g=((x>>8)&255)/255.,b=(x&255)/255.; return .2126*linear(r)+.7152*linear(g)+.0722*linear(b); } static double linear(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);} }");
      src("androidx/core/view/WindowInsetsCompat.java", "package androidx.core.view; import androidx.core.graphics.*; public class WindowInsetsCompat { public static final class Type {public static int navigationBars(){return 1;} public static int ime(){return 2;}} final Insets nav,ime; public WindowInsetsCompat(Insets n,Insets i){nav=n;ime=i;} public Insets getInsets(int t){return t==Type.ime()?ime:nav;} }");
      src("androidx/core/view/ViewCompat.java", "package androidx.core.view; import android.view.*; public class ViewCompat { public static WindowInsetsCompat insets; public static WindowInsetsCompat getRootWindowInsets(View v){return insets;} }");
      src("androidx/core/view/WindowCompat.java", "package androidx.core.view; import android.view.*; public class WindowCompat { public static final Controller controller=new Controller(); public static Controller getInsetsController(Window w,View d){controller.calls++;return controller;} public static class Controller {public int calls;public boolean light; public void setAppearanceLightNavigationBars(boolean x){light=x;}} }");
      src("in/anoopbhatia/mail/NavigationHarness.java", harnessSource());
      Path classes=Files.createDirectories(root.resolve("classes")); List<String> a=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","javac").toString(),"-d",classes.toString())); try(var s=Files.walk(root)){s.filter(p->p.toString().endsWith(".java")).forEach(p->a.add(p.toString()));} Path log=root.resolve("javac.log"); Process p=new ProcessBuilder(a).redirectErrorStream(true).redirectOutput(log.toFile()).start(); boolean done=p.waitFor(30,TimeUnit.SECONDS); Assert.assertTrue("javac: "+Files.readString(log),done&&p.exitValue()==0); loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},null); run=loader.loadClass("in.anoopbhatia.mail.NavigationHarness").getMethod("run",String.class);
    }
    String read(String p)throws IOException{for(Path q=Path.of(System.getProperty("user.dir")).toAbsolutePath();q!=null;q=q.getParent()){Path f=q.resolve(p);if(Files.exists(f))return Files.readString(f);}throw new IOException(p);}
    void src(String p,String s)throws IOException{Path f=root.resolve(p);Files.createDirectories(f.getParent());Files.writeString(f,s);}
    void run(String s)throws Exception{try{run.invoke(null,s);}catch(InvocationTargetException e){throw new AssertionError(s+": "+e.getCause(),e.getCause());}}
    public void close()throws Exception{loader.close();try(var s=Files.walk(root)){for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    String harnessSource(){return """
package in.anoopbhatia.mail;
import android.graphics.*; import android.view.*; import androidx.core.graphics.*; import androidx.core.view.*;
public class NavigationHarness {
    static Window w;
    static ViewGroup d;
    static NavigationBarAppearance a;
    static void setup(int width, int height) {
        ViewCompat.insets = null;
        w = new Window();
        d = w.getDecorView();
        d.layout(0, 0, width, height);
        d.attach();
        a = new NavigationBarAppearance(w, 0xff202020);
    }
    static void in(Insets n, Insets ime) {
        ViewCompat.insets = new WindowInsetsCompat(n, ime);
        d.getViewTreeObserver().fire();
    }
    static Canvas draw() { Canvas c = new Canvas(); a.draw(c); return c; }
    static void eq(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void eq(int actual, int expected, String message) {
        eq(actual == expected, message + ": " + actual + " != " + expected);
    }
    static void pixels(Canvas c, Insets nav, int color) {
        int width = d.getWidth(), height = d.getHeight();
        for (int[] rect : c.rects) {
            eq(rect[0] >= 0 && rect[1] >= 0 && rect[2] <= width && rect[3] <= height,
                    "every painted rectangle stays inside the window");
            eq(rect[4], color, "custom color/alpha retained");
        }
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (int[] rect : c.rects)
                    if (x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]) count++;
                boolean expected = x < nav.left || x >= width-nav.right ||
                        y < nav.top || y >= height-nav.bottom;
                eq(count, expected ? 1 : 0, "pixel " + x + "," + y + " painted exactly once or not at all");
            }
    }
    public static void run(String scenario) {
        switch (scenario) {
            case "edges": edges(); break;
            case "corners": corners(); break;
            case "changes": changes(); break;
            case "ime": ime(); break;
            case "icons": icons(); break;
            case "lifecycle": life(); break;
            default: throw new AssertionError(scenario);
        }
    }
    static void edges() {
        setup(100,80);
        for (Insets nav : new Insets[]{Insets.of(0,0,0,10), Insets.of(7,0,0,0),
                Insets.of(0,0,9,0), Insets.of(0,6,0,0), Insets.of(3,4,5,6)}) {
            in(nav, Insets.NONE);
            a.setColor(0xff2468ac);
            pixels(draw(), nav, 0xff2468ac);
        }
        // No status/cutout inset is requested: only the navigation mask is observed.
        in(Insets.NONE, Insets.NONE);
        eq(draw().rects.size(), 0, "no navigation means no status/cutout paint");
    }
    static void corners() {
        setup(12,9);
        a.setColor(0x802468ac);
        Insets nav = Insets.of(3,2,4,3);
        in(nav, Insets.NONE);
        pixels(draw(), nav, 0x802468ac);
        d.layout(0,0,5,4);
        nav = Insets.of(10,10,10,10);
        in(nav, Insets.NONE);
        pixels(draw(), nav, 0x802468ac);
        in(Insets.NONE, Insets.NONE);
        eq(draw().rects.size(), 0, "hidden navigation paints nothing");
        d.layout(0,0,0,0);
        in(nav, Insets.NONE);
        eq(draw().rects.size(), 0, "zero-sized window paints nothing");
    }
    static void changes() {
        setup(100,80);
        a.setColor(Color.BLACK);
        Insets nav = Insets.of(0,0,0,10);
        in(nav, Insets.NONE);
        pixels(draw(), nav, Color.BLACK);
        d.layout(0,0,80,100);
        nav = Insets.of(0,0,10,0);
        in(nav, Insets.NONE);
        pixels(draw(), nav, Color.BLACK);
        eq(a.getWidth(), 80, "resized overlay width");
        eq(a.getHeight(), 100, "resized overlay height");
        in(Insets.NONE, Insets.NONE);
        eq(draw().rects.size(), 0, "hidden navigation clears stale geometry");
        in(nav, Insets.NONE);
        ViewCompat.insets = null;
        d.getViewTreeObserver().fire();
        eq(draw().rects.size(), 0, "null insets clear stale geometry");
    }
    static void ime() {
        setup(100,80);
        a.setColor(Color.BLACK);
        Insets nav = Insets.of(0,0,0,12);
        in(nav, Insets.of(0,0,0,20));
        eq(draw().rects.size(), 0, "no stripe above docked IME");
        in(nav, Insets.of(3,3,3,3));
        pixels(draw(), nav, Color.BLACK);
        in(nav, Insets.of(0,0,0,60));
        eq(draw().rects.size(), 0, "second docked IME hides stripe");
        in(nav, Insets.NONE);
        pixels(draw(), nav, Color.BLACK);
    }
    static void icons() {
        setup(100,80);
        eq(!WindowCompat.controller.light, "dark transparent default uses white icons");
        a.setColor(Color.BLACK);
        eq(!WindowCompat.controller.light, "black background uses white icons");
        a.setColor(Color.WHITE);
        eq(WindowCompat.controller.light, "white background uses black icons");
        a.setColor(0xffff0000);
        eq(WindowCompat.controller.light, "red custom color favors black icons");
        a.setBackgroundColors(Color.WHITE, Color.BLACK);
        in(Insets.of(0,0,0,8), Insets.NONE);
        a.setColor(0x40000000);
        eq(WindowCompat.controller.light, "translucent black over white favors black icons");
        a.setColor(0xc0000000);
        eq(!WindowCompat.controller.light, "opaque-enough black over white favors white icons");
        a.setColor(Color.TRANSPARENT);
        eq(WindowCompat.controller.light, "transparent restores actual bottom backing");
        in(Insets.of(8,0,0,0), Insets.NONE);
        eq(!WindowCompat.controller.light, "rotation uses side backing");
        a.setBackgroundColors(0x00000000, 0x00ffffff);
        eq(WindowCompat.controller.light, "backing alpha normalized to opaque");
    }
    static void life() {
        setup(30,30);
        eq(d.getOverlay().size(), 1, "owned overlay");
        eq(d.getViewTreeObserver().ls.size(), 1, "attached observer");
        d.detach();
        eq(d.getViewTreeObserver().ls.size(), 0, "detach removes observer");
        d.attach();
        eq(d.getViewTreeObserver().ls.size(), 1, "reattach once");
        a.onViewAttachedToWindow(d);
        eq(d.getViewTreeObserver().ls.size(), 1, "repeat attach cannot duplicate observer");
        Window w2 = new Window();
        NavigationBarAppearance b = new NavigationBarAppearance(w2, Color.BLACK);
        eq(w2.getDecorView().getViewTreeObserver().ls.size(), 0, "unattached second window waits");
        w2.getDecorView().attach();
        eq(w2.getDecorView().getViewTreeObserver().ls.size(), 1, "second window attaches independently");
        a.close();
        a.close();
        eq(d.getOverlay().size(), 0, "idempotent close removes own overlay");
        eq(d.getViewTreeObserver().ls.size(), 0, "close removes observer");
        eq(w2.getDecorView().getOverlay().size(), 1, "other window overlay retained");
        eq(w2.getDecorView().getViewTreeObserver().ls.size(), 1, "other window observer retained");
        int calls = WindowCompat.controller.calls;
        a.setColor(Color.WHITE);
        eq(WindowCompat.controller.calls, calls, "closed setter cannot update appearance");
        d.detach(); d.attach();
        eq(d.getViewTreeObserver().ls.size(), 0, "closed owner cannot re-register");
        b.close();
    }
}
""";}
  }
}
