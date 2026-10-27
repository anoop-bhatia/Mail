package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Host-JavaCompiler tests for bundle diagnostics. The generated program calls the extracted
 * production methods directly; its tiny Android doubles deliberately model only the APIs needed
 * by these methods, not Android parceling, class loaders, or framework lifecycle machinery.
 */
public class BundleDiagnosticCompatibilityTest {
    private static Harness harness;

    @BeforeClass public static void compileHarness() throws Exception { harness = new Harness(); }
    @AfterClass public static void closeHarness() throws Exception { if (harness != null) harness.close(); }

    @Test public void activityBundleValueReturnsRawIdentity() throws Exception {
        assertEquals("true", harness.run("activityIdentity"));
    }

    @Test public void breadcrumbFormatsPrimitiveNumbers() throws Exception {
        assertEquals("bool=true;double=2.5;float=1.5;int=7;long=8", harness.run("breadcrumbPrimitives"));
    }

    @Test public void breadcrumbLeavesExactlyFiftyCharactersUnchanged() throws Exception {
        assertEquals("s=" + "12345678901234567890123456789012345678901234567890", harness.run("breadcrumbCap50"));
    }
    @Test public void breadcrumbTruncatesFiftyOneCharacters() throws Exception {
        assertEquals("s=" + "12345678901234567890123456789012345678901234567890...", harness.run("breadcrumbCap51"));
    }
    @Test public void breadcrumbTruncatesLongSpannedValue() throws Exception {
        assertEquals("span=" + "12345678901234567890123456789012345678901234567890...", harness.run("breadcrumbSpanCap"));
    }

    @Test public void breadcrumbFormatsSpannedLikePrintableString() throws Exception {
        assertEquals("span=spanned text", harness.run("breadcrumbSpanned"));
    }

    @Test public void breadcrumbUsesNullMarker() throws Exception {
        assertEquals("null=<null>", harness.run("breadcrumbNull"));
    }

    @Test public void breadcrumbDoesNotStringifyArbitraryObjects() throws Exception {
        assertEquals("object=<in.anoopbhatia.mail.BundleDiagnosticHarness$Secret>", harness.run("breadcrumbObject"));
    }

    @Test public void breadcrumbTreatsCharSequenceAndArraysAsClassOnly() throws Exception {
        assertEquals("array=<[Ljava.lang.String;>;chars=<java.lang.StringBuilder>", harness.run("breadcrumbClassOnly"));
    }

    @Test public void extrasHandlesNullBundleAndLongHex() throws Exception {
        assertEquals("null=null|n=15 (0xf) (Long)", harness.run("extrasNullLong"));
    }
    @Test public void extrasCapsArrayBoundariesAndFormatsPrimitiveLongArray() throws Exception {
        assertEquals("a0=[] (String[]:0)|a10=[0,1,2,3,4,5,6,7,8,9] (String[]:10)|a11=[0,1,2,3,4,5,6,7,8,9, ...] (String[]:11)|longs=[1 (0x1),2 (0x2)] (long[]:2)", harness.run("extrasBoundaries"));
    }

    @Test public void extrasCapsArraysAndKeepsOriginalLength() throws Exception {
        assertEquals("a=[one,two,<null>,four,five,six,seven,eight,nine,ten, ...] (String[]:12)", harness.run("extrasArray"));
    }

    @Test public void extrasUsesSpannedAndNestedBundleFormatting() throws Exception {
        assertEquals("span=(span:hello) (Span)|nested={x=3 (Integer)} (Bundle)", harness.run("extrasNested"));
    }

    @Test public void extrasPreservesRawObjectStringification() throws Exception {
        assertEquals("o=secret-value ()", harness.run("extrasObject"));
    }

    @Test public void extrasCatchesThrowableAndReturnsPrefix() throws Exception {
        assertEquals("before=ok (String)", harness.run("extrasFailure"));
    }

    @Test public void serviceKeepsActionExtrasAndSuperResult() throws Exception {
        assertEquals("action=go;value=payload;super:17:go:7:11", harness.run("service"));
    }
    @Test public void servicePreservesRawLongAndNullExtras() throws Exception {
        assertEquals("action=go;long=15;null=null;raw=123456789012345678901234567890123456789012345678901;super:17:go:7:11", harness.run("serviceRaw"));
    }
    @Test public void serviceActionOnlyIntentPreservesIdentityAndSuperResult() throws Exception {
        assertEquals("action=only;super:17:only:3:5", harness.run("serviceActionOnly"));
    }

    @Test public void serviceAcceptsNullIntentAndPreservesSuperFlags() throws Exception {
        assertEquals("super:null:4:9", harness.run("serviceNull"));
    }

    @Test public void sourceGuardsRetainNecessaryGenericBundleReadsWithoutSuppressions() throws Exception {
        String activity = readProjectFile("app/src/main/java/in/anoopbhatia/mail/ActivityBase.java");
        String log = readProjectFile("app/src/main/java/in/anoopbhatia/mail/Log.java");
        String service = readProjectFile("app/src/main/java/in/anoopbhatia/mail/ServiceBase.java");
        String getValue = extractDeclaration(activity, "private static Object getBundleValue(Bundle bundle, String key)");
        String breadcrumb = extractDeclaration(log, "public static void breadcrumb(String name, Bundle args)");
        String extras = extractDeclaration(log, "static List<String> getExtras(Bundle data)");
        String start = extractDeclaration(service, "public int onStartCommand(Intent intent, int flags, int startId)");
        assertTrue("getBundleValue must retain the generic read", getValue.contains("bundle.get(key)"));
        assertTrue("breadcrumb must retain the generic read", breadcrumb.contains("args.get(key)"));
        assertTrue("getExtras must retain the generic read", extras.contains("data.get(key)"));
        assertTrue("onStartCommand must retain the generic read", start.contains("data.get(key)"));
        assertTrue("getBundleValue suppression should be removed", !getValue.contains("SuppressWarnings"));
        assertTrue("breadcrumb suppression should be removed", !breadcrumb.contains("SuppressWarnings"));
        assertTrue("getExtras suppression should be removed", !extras.contains("SuppressWarnings"));
        assertTrue("onStartCommand suppression should be removed", !start.contains("SuppressWarnings"));
    }

    private static final class Harness implements AutoCloseable {
        private final Path root;
        Harness() throws Exception {
            root = Files.createTempDirectory("bundle-diagnostic-harness");
            writeSources(root);
            Path classes = root.resolve("classes"); Files.createDirectories(classes);
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
            args.add("-d"); args.add(classes.toString());
            try (var paths = Files.walk(root)) { paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString())); }
            Path log = root.resolve("javac.log");
            Process p = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS); if (!done) p.destroyForcibly();
            String diagnostics = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue("temporary framework harness timed out: " + diagnostics, done);
            assertEquals("temporary framework harness failed to compile: " + diagnostics, 0, p.exitValue());
        }
        String run(String scenario) throws Exception {
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", root.resolve("classes").toString(), "in.anoopbhatia.mail.BundleDiagnosticHarness", scenario)
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS); if (!done) p.destroyForcibly();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue("harness timed out for " + scenario, done);
            assertEquals("harness failed for " + scenario + ": " + out, 0, p.exitValue());
            return out;
        }
        @Override public void close() throws IOException { delete(root); }
    }

    private static void writeSources(Path root) throws IOException {
        String activity = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/ActivityBase.java"), "private static Object getBundleValue(Bundle bundle, String key)");
        String breadcrumb = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/Log.java"), "public static void breadcrumb(String name, Bundle args)");
        String extras = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/Log.java"), "static List<String> getExtras(Bundle data)");
        String service = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/ServiceBase.java"), "public int onStartCommand(Intent intent, int flags, int startId)");
        String printable = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/Helper.java"), "static boolean isPrintableChar(int codepoint)");
        String printableString = extractMethod(readProjectFile("app/src/main/java/in/anoopbhatia/mail/Helper.java"), "static String getPrintableString(String value, boolean debug)");
        write(root, "android/os/Bundle.java", "package android.os; import java.util.*; public class Bundle { private final Map<String,Object> m=new LinkedHashMap<>(); public void put(String k,Object v){m.put(k,v);} public Object get(String k){return m.get(k);} public Set<String> keySet(){return m.keySet();} }");
        write(root, "android/content/Intent.java", "package android.content; import android.os.Bundle; public class Intent { private String a; private Bundle b; public Intent(String a,Bundle b){this.a=a;this.b=b;} public String getAction(){return a;} public Bundle getExtras(){return b;} }");
        write(root, "android/text/Spanned.java", "package android.text; public interface Spanned extends CharSequence { }");
        write(root, "android/text/TextUtils.java", "package android.text; public final class TextUtils { public static String join(String s, Object[] x){StringBuilder b=new StringBuilder(); for(int i=0;i<x.length;i++){if(i>0)b.append(s);b.append(x[i]);} return b.toString();} public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;} public static String join(String s, java.util.List<?> x){StringBuilder b=new StringBuilder(); for(Object o:x){if(b.length()>0)b.append(s);b.append(o);} return b.toString();} }");
        write(root, "android/content/Context.java", "package android.content; public class Context { }");
        write(root, "androidx/lifecycle/LifecycleService.java", "package androidx.lifecycle; import android.content.Intent; import in.anoopbhatia.mail.BundleDiagnosticState; public class LifecycleService { public int onStartCommand(Intent i,int f,int s){BundleDiagnosticState.superFlags=f; BundleDiagnosticState.superStart=s; BundleDiagnosticState.superAction=i==null?null:i.getAction(); return 17;} }");
        write(root, "in/anoopbhatia/mail/BundleDiagnosticState.java", "package in.anoopbhatia.mail; public final class BundleDiagnosticState { public static int superFlags,superStart; public static String superAction; }");
        write(root, "in/anoopbhatia/mail/Helper.java", "package in.anoopbhatia.mail; import android.text.TextUtils; public final class Helper { " + printable + printableString + " }");
        write(root, "in/anoopbhatia/mail/Log.java", "package in.anoopbhatia.mail; import android.os.Bundle; import java.util.*; public final class Log { static Map<String,String> crumb; static void breadcrumb(String n,Map<String,String> c){crumb=new LinkedHashMap<>(c); } static void e(Throwable x){} }");
        write(root, "in/anoopbhatia/mail/BundleDiagnosticHarness.java", "package in.anoopbhatia.mail; import android.os.*; import java.lang.reflect.Array; import android.content.*; import android.text.*; import androidx.lifecycle.*; import java.util.*; public class BundleDiagnosticHarness extends LifecycleService {\n" + activity + "\n" + breadcrumb + "\n" + extras + "\n" + service + "\n" +
                """
                static class Secret implements java.io.Serializable {
                    public String toString() { throw new AssertionError("must not stringify"); }
                }
                static class Span implements Spanned {
                    String s; Span(String s) { this.s=s; }
                    public int length() { return s.length(); }
                    public char charAt(int i) { return s.charAt(i); }
                    public CharSequence subSequence(int a,int b) { return s.substring(a,b); }
                    public String toString() { return s; }
                }
                static void breadcrumb(String n,Map<String,String> c) { Log.crumb=new LinkedHashMap<>(c); }
                static Bundle b() { return new Bundle(); }
                static void out() {
                    StringJoiner x=new StringJoiner(";");
                    for(var e:new TreeMap<>(Log.crumb).entrySet()) x.add(e.getKey()+"="+e.getValue());
                    System.out.print(x);
                }
                static void checkService(Intent intent,int flags,int id) {
                    int result=new BundleDiagnosticHarness().onStartCommand(intent,flags,id);
                    if(result!=17 || BundleDiagnosticState.superFlags!=flags || BundleDiagnosticState.superStart!=id)
                        throw new AssertionError("super dispatch changed");
                    out();
                    System.out.print(";super:"+result+":"+BundleDiagnosticState.superAction+":"+flags+":"+id);
                }
                public static void main(String[] z) {
                    String q=z[0]; Bundle b=b();
                    String fifty="12345678901234567890123456789012345678901234567890";
                    switch(q) {
                        case "activityIdentity":
                            Object o=new Secret(); b.put("x",o);
                            System.out.print(getBundleValue(b,"x")==o); return;
                        case "breadcrumbPrimitives":
                            b.put("bool",true); b.put("int",7); b.put("long",8L);
                            b.put("float",1.5f); b.put("double",2.5); break;
                        case "breadcrumbCap50": b.put("s",fifty); break;
                        case "breadcrumbCap51": b.put("s",fifty+"1"); break;
                        case "breadcrumbSpanCap": b.put("span",new Span(fifty+"1234")); break;
                        case "breadcrumbSpanned": b.put("span",new Span("spanned text")); break;
                        case "breadcrumbNull": b.put("null",null); break;
                        case "breadcrumbObject": b.put("object",new Secret()); break;
                        case "breadcrumbClassOnly":
                            b.put("chars",new StringBuilder("chars")); b.put("array",new String[]{"x"}); break;
                        case "extrasNullLong":
                            if(!getExtras(null).isEmpty()) throw new AssertionError("null bundle");
                            b.put("null",null); b.put("n",15L); break;
                        case "extrasBoundaries":
                            b.put("a0",new String[0]);
                            b.put("a10",new String[]{"0","1","2","3","4","5","6","7","8","9"});
                            b.put("a11",new String[]{"0","1","2","3","4","5","6","7","8","9","10"});
                            b.put("longs",new long[]{1L,2L}); break;
                        case "extrasArray":
                            b.put("a",new String[]{"one","two",null,"four","five","six","seven","eight","nine","ten","eleven","twelve"}); break;
                        case "extrasNested":
                            Bundle n=b(); n.put("x",3); b.put("span",new Span("hello")); b.put("nested",n); break;
                        case "extrasObject":
                            b.put("o",new Secret(){public String toString(){return "secret-value";}}); break;
                        case "extrasFailure":
                            b.put("before","ok");
                            b.put("bad",new Object(){public String toString(){throw new RuntimeException();}}); break;
                        case "service":
                            b.put("value","payload"); checkService(new Intent("go",b),7,11); return;
                        case "serviceRaw":
                            b.put("long",15L); b.put("null",null); b.put("raw",fifty+"1");
                            checkService(new Intent("go",b),7,11); return;
                        case "serviceActionOnly": checkService(new Intent("only",null),3,5); return;
                        case "serviceNull":
                            int result=new BundleDiagnosticHarness().onStartCommand(null,4,9);
                            if(result!=17 || BundleDiagnosticState.superAction!=null) throw new AssertionError("null intent");
                            System.out.print("super:null:"+BundleDiagnosticState.superFlags+":"+BundleDiagnosticState.superStart); return;
                        default: throw new AssertionError("Unknown scenario: "+q);
                    }
                    if(q.startsWith("extras")) System.out.print(String.join("|",getExtras(b)));
                    else { breadcrumb("x",b); out(); }
                }
                }
                """);
    }

    private static String readProjectFile(String relative) throws IOException { Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath(); for(;p!=null;p=p.getParent()){Path c=p.resolve(relative);if(Files.exists(c))return Files.readString(c);} throw new IOException(relative); }
    private static String extractMethod(String source,String sig){int s=source.indexOf(sig);if(s<0)throw new IllegalArgumentException(sig);int o=source.indexOf('{',s),d=0;for(int i=o;i<source.length();i++){if(source.charAt(i)=='{')d++;else if(source.charAt(i)=='}'&&--d==0)return source.substring(s,i+1);}throw new IllegalArgumentException("unclosed "+sig);}
    private static String extractDeclaration(String source,String sig){int s=source.indexOf(sig);if(s<0)throw new IllegalArgumentException(sig);int line=source.lastIndexOf('\n',s-1)+1;while(line>0){int prev=source.lastIndexOf('\n',line-2)+1;String prefix=source.substring(prev,line).trim();if(!prefix.startsWith("@"))break;line=prev;}return source.substring(line,s)+extractMethod(source,sig);}
    private static void write(Path r,String n,String c)throws IOException{Path p=r.resolve(n);Files.createDirectories(p.getParent());Files.writeString(p,c);}
    private static void delete(Path p)throws IOException{if(!Files.exists(p))return;try(var s=Files.walk(p)){s.sorted((a,b)->b.compareTo(a)).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new RuntimeException(e);}});}}
}
