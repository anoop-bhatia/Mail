package in.anoopbhatia.mail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

/** Behaviour tests for the API-28 NetworkCapabilities roaming implementation. */
public class NetworkRoamingCompatibilityTest {
    @Test
    public void sourceUsesCapabilitiesOnlyAndKeepsApi28Minimum() throws Exception {
        String connection = readProjectFile("app/src/main/java/in/anoopbhatia/mail/ConnectionHelper.java");
        String method = extractMethod(connection, "static NetworkState getNetworkState(Context context)");
        assertTrue("the API-28 method must not retain the pre-28 cellular fallback",
                !(method.contains("isLegacyCellularRoaming") || method.contains("TRANSPORT_CELLULAR")));

        String gradle = readProjectFile("app/build.gradle.kts");
        assertTrue("Android 9.0 is the supported minimum",
                Pattern.compile("minSdk\\s*=\\s*28").matcher(gradle).find());
    }

    @Test
    public void notRoamingCapabilityControlsCellularWifiAndVpn() throws Exception {
        try (Harness h = new Harness()) {
            for (String transport : new String[]{"cellular", "wifi", "vpn"}) {
                Config c = new Config().transport(transport).notRoaming(true);
                assertFalse(transport, h.state(c).roaming);
                c.notRoaming(false);
                assertTrue(transport, h.state(c).roaming);
            }
        }
    }

    @Test
    public void missingNetworkDataLeavesRoamingUnknownAndDisconnectedIsUnsuitable() throws Exception {
        try (Harness h = new Harness()) {
            for (int missing = 0; missing < 3; missing++) {
                Config c = new Config();
                if (missing == 0) c.installManager = false;
                if (missing == 1) c.installNetwork = false;
                if (missing == 2) c.installCapabilities = false;
                Result result = h.state(c);
                assertFalse(result.connected);
                assertFalse(result.suitable);
                assertFalse(result.unmetered);
                assertNull("missing data must leave roaming unknown", result.roaming);
            }
            Result disconnected = h.state(new Config().installNetwork(false));
            assertFalse(disconnected.connected);
            assertFalse(disconnected.suitable);
            assertFalse(disconnected.unmetered);
        }
    }

    @Test
    public void userAllowedRoamingSkipsRoamingInspection() throws Exception {
        try (Harness h = new Harness()) {
            Config c = new Config().allowRoaming(true).notRoaming(false);
            Result result = h.state(c);
            assertNull(result.roaming);
            assertTrue(result.connected);
            assertTrue("roaming inspection must be skipped", result.capabilityQueries == 0);
        }
    }

    @Test
    public void meteringPreferenceControlsSuitabilityAndUnmeteredState() throws Exception {
        try (Harness h = new Harness()) {
            Result allowed = h.state(new Config().actualMetered(true).meteredPreference(true));
            assertTrue(allowed.connected);
            assertTrue(allowed.suitable);
            assertFalse(allowed.unmetered);

            Result disallowed = h.state(new Config().actualMetered(true).meteredPreference(false));
            assertTrue(disallowed.connected);
            assertFalse(disallowed.suitable);
            assertFalse(disallowed.unmetered);

            Result free = h.state(new Config().actualMetered(false).meteredPreference(false));
            assertTrue(free.connected);
            assertTrue(free.suitable);
            assertTrue(free.unmetered);
        }
    }

    @Test
    public void rlahRequiresBothKnownEuCountriesAndCanBeDisabled() throws Exception {
        try (Harness h = new Harness()) {
            assertFalse(h.state(new Config().notRoaming(false).rlah(true).countries("DE", "FR")).roaming);
            assertTrue(h.state(new Config().notRoaming(false).rlah(true).countries("DE", "US")).roaming);
            assertTrue(h.state(new Config().notRoaming(false).rlah(true).countries("ZZ", "FR")).roaming);
            assertTrue(h.state(new Config().notRoaming(false).rlah(false).countries("DE", "FR")).roaming);
            assertTrue(h.state(new Config().notRoaming(false).rlah(true).countries(null, "FR")).roaming);
            assertTrue(h.state(new Config().notRoaming(false).rlah(true).countries("DE", null)).roaming);
            assertFalse(h.state(new Config().notRoaming(false).rlah(true).countries("de", "fr")).roaming);
        }
    }

    @Test
    public void rlahCountryLookupExceptionDoesNotEscapeNetworkState() throws Exception {
        try (Harness h = new Harness()) {
            Result result = h.state(new Config().notRoaming(false).rlah(true).countries("THROW", "US"));
            assertTrue(result.connected);
            assertTrue(result.roaming);
        }
    }

    private static final class Config {
        boolean installManager = true, installNetwork = true, installCapabilities = true;
        boolean allowRoaming = false, rlah = false, notRoaming = true;
        boolean wifi, vpn;
        Boolean actualMetered = false;
        boolean meteredPreference = true;
        String sim = "US", network = "US";
        Config transport(String value) { wifi = "wifi".equals(value); vpn = "vpn".equals(value); return this; }
        Config notRoaming(boolean value) { notRoaming = value; return this; }
        Config allowRoaming(boolean value) { allowRoaming = value; return this; }
        Config rlah(boolean value) { rlah = value; return this; }
        Config countries(String s, String n) { sim = s; network = n; return this; }
        Config actualMetered(Boolean value) { actualMetered = value; return this; }
        Config meteredPreference(boolean value) { meteredPreference = value; return this; }
        Config installNetwork(boolean value) { installNetwork = value; return this; }
    }

    private static final class Result {
        final boolean connected, suitable, unmetered;
        final Boolean roaming;
        final int capabilityQueries;
        Result(boolean c, boolean s, boolean u, Boolean r, int q) { connected=c; suitable=s; unmetered=u; roaming=r; capabilityQueries=q; }
    }

    private static final class Harness implements AutoCloseable {
        private final Path root;
        private final URLClassLoader loader;
        private final Class<?> configType;
        private final Constructor<?> configConstructor;
        private final Method evaluate;

        Harness() throws Exception {
            root = Files.createTempDirectory("network-roaming-harness");
            writeSources(root);
            // Android's compile-time bootclasspath omits javax.tools. Invoke the host
            // JDK compiler as a subprocess instead of adding Android runtime APIs.
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
            args.add("-d");
            Path classes = root.resolve("classes");
            args.add(classes.toString());
            try (var paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
            }
            Process compiler = new ProcessBuilder(args).redirectErrorStream(true).start();
            String diagnostics = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("temporary framework harness failed to compile: " + diagnostics, compiler.waitFor() == 0);
            // Do not parent-first-load Android or helper fakes from the test runtime.
            loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
            Class<?> harness = loader.loadClass("in.anoopbhatia.mail.NetworkRoamingHarness");
            configType = loader.loadClass("in.anoopbhatia.mail.NetworkRoamingHarness$Config");
            configConstructor = configType.getDeclaredConstructor(); configConstructor.setAccessible(true);
            evaluate = harness.getMethod("evaluate", configType);
        }

        Result state(Config source) throws Exception {
            Object c = configConstructor.newInstance();
            for (var f : Config.class.getDeclaredFields()) {
                var target = configType.getDeclaredField(f.getName()); f.setAccessible(true); target.setAccessible(true); target.set(c, f.get(source));
            }
            Object value = evaluate.invoke(null, c); Class<?> type=value.getClass();
            Method connected=type.getDeclaredMethod("isConnected"), suitable=type.getDeclaredMethod("isSuitable"),
                    unmetered=type.getDeclaredMethod("isUnmetered"), queries=harnessQueryMethod();
            java.lang.reflect.Field rawRoaming=type.getDeclaredField("roaming");
            for (Method m : new Method[]{connected,suitable,unmetered,queries}) m.setAccessible(true);
            rawRoaming.setAccessible(true);
            return new Result((Boolean)connected.invoke(value),(Boolean)suitable.invoke(value),(Boolean)unmetered.invoke(value),
                    (Boolean)rawRoaming.get(value),(Integer)queries.invoke(null));
        }
        private Method harnessQueryMethod() throws Exception { return loader.loadClass("in.anoopbhatia.mail.NetworkRoamingHarness").getMethod("queries"); }
        @Override public void close() throws IOException { loader.close(); delete(root); }
    }

    // Only getNetworkState and NetworkState are under test. isMetered is an explicit
    // snapshot stub: these tests do not claim to execute the VPN-underlay classifier.
    private static void writeSources(Path root) throws IOException {
        String source=readProjectFile("app/src/main/java/in/anoopbhatia/mail/ConnectionHelper.java");
        String state=extractClass(source,"static class NetworkState");
        String method=extractMethod(source,"static NetworkState getNetworkState(Context context)");
        int start=source.indexOf("private static final List<String> RLAH_COUNTRY_CODES");
        String countries=source.substring(start,source.indexOf("));",start)+3);
        String harness="package in.anoopbhatia.mail; import android.content.*; import android.net.*; import android.telephony.*; import java.util.*; import androidx.preference.PreferenceManager;\n"+
            "import androidx.annotation.Nullable; public class NetworkRoamingHarness { "+countries+" "+state+" "+method+" static Boolean configuredMetered; public static int capabilityQueries;\n"+
            " static Boolean isMetered(Context c){ ConnectivityManager cm=Helper.getSystemService(c,ConnectivityManager.class); if(configuredMetered==null||cm==null||cm.getActiveNetwork()==null||cm.getNetworkCapabilities(cm.getActiveNetwork())==null)return null; return configuredMetered;}\n"+
            " static Network getActiveNetwork(Context c){ ConnectivityManager cm=Helper.getSystemService(c,ConnectivityManager.class); return cm==null?null:cm.getActiveNetwork(); }\n"+
            " public static Config last; public static NetworkState evaluate(Config x){last=x; capabilityQueries=0; configuredMetered=x.actualMetered; Scenario c=new Scenario(x); if(x.installNetwork)c.cm.active=new Network(); if(x.installCapabilities)c.cm.caps=new NetworkCapabilities(x.notRoaming,x.wifi,x.vpn); if(x.installManager)c.services.put(ConnectivityManager.class,c.cm); c.services.put(TelephonyManager.class,new TelephonyManager(x.sim,x.network)); PreferenceManager.prefs=c.prefs; return getNetworkState(c); } public static int queries(){return capabilityQueries;}\n"+
            " public static class Config { public boolean installManager=true,installNetwork=true,installCapabilities=true,allowRoaming=false,rlah=false,notRoaming=true,wifi=false,vpn=false,meteredPreference=true; public Boolean actualMetered=false; public String sim=\"US\",network=\"US\";}\n"+
            " static class Scenario extends Context { final ConnectivityManager cm=new ConnectivityManager(); final Map<Class<?>,Object> services=new HashMap<>(); final P prefs; Scenario(Config x){prefs=new P(x);} public Object service(Class<?> c){return services.get(c);} }\n"+
            " static class P implements SharedPreferences { final Config x; P(Config c){x=c;} public boolean getBoolean(String k,boolean d){if(k.equals(\"metered\"))return x.meteredPreference; if(k.equals(\"roaming\"))return x.allowRoaming; if(k.equals(\"rlah\"))return x.rlah; return d;} } }\n";
        write(root,"in/anoopbhatia/mail/NetworkRoamingHarness.java",harness);
        write(root,"android/content/Context.java","package android.content; public class Context { public Object service(Class<?> c){return null;} }\n");
        write(root,"android/content/SharedPreferences.java","package android.content; public interface SharedPreferences { boolean getBoolean(String k,boolean d); }\n");
        write(root,"android/net/Network.java","package android.net; public class Network {}\n");
        write(root,"android/net/NetworkCapabilities.java","package android.net; import in.anoopbhatia.mail.NetworkRoamingHarness; public class NetworkCapabilities { public static final int NET_CAPABILITY_NOT_ROAMING=1,NET_CAPABILITY_NOT_VPN=2,NET_CAPABILITY_INTERNET=3,NET_CAPABILITY_NOT_RESTRICTED=4,NET_CAPABILITY_FOREGROUND=5,NET_CAPABILITY_VALIDATED=6,NET_CAPABILITY_CAPTIVE_PORTAL=7,NET_CAPABILITY_NOT_METERED=8,TRANSPORT_CELLULAR=9,TRANSPORT_WIFI=10,TRANSPORT_VPN=11; final boolean notRoaming,wifi,vpn; public NetworkCapabilities(boolean n,boolean w,boolean v){notRoaming=n;wifi=w;vpn=v;} public boolean hasTransport(int x){return x==(wifi?TRANSPORT_WIFI:vpn?TRANSPORT_VPN:TRANSPORT_CELLULAR);} public boolean hasCapability(int x){NetworkRoamingHarness.capabilityQueries++;return x==NET_CAPABILITY_NOT_ROAMING&&notRoaming;} }\n");
        write(root,"android/net/ConnectivityManager.java","package android.net; public class ConnectivityManager { public Network active; public NetworkCapabilities caps; public Network getActiveNetwork(){return active;} public NetworkCapabilities getNetworkCapabilities(Network n){return caps;} public boolean isActiveNetworkMetered(){return true;} public Network[] getAllNetworks(){return new Network[0];} }\n");
        write(root,"android/os/Build.java","package android.os; public class Build { public static class VERSION { public static final int SDK_INT=28; } public static class VERSION_CODES { public static final int P=28; } }\n");
        write(root,"android/telephony/TelephonyManager.java","package android.telephony; public class TelephonyManager { final String s,n; public TelephonyManager(String s,String n){this.s=s;this.n=n;} public String getSimCountryIso(){if(\"THROW\".equals(s))throw new RuntimeException();return s;} public String getNetworkCountryIso(){return n;} }\n");
        write(root,"androidx/preference/PreferenceManager.java","package androidx.preference; import android.content.*; public class PreferenceManager { public static SharedPreferences prefs; public static SharedPreferences getDefaultSharedPreferences(Context c){return prefs;} }\n");
        write(root,"androidx/annotation/Nullable.java","package androidx.annotation; public @interface Nullable {}\n");
        write(root,"in/anoopbhatia/mail/Helper.java","package in.anoopbhatia.mail; import android.content.*; public class Helper { public static <T>T getSystemService(Context c,Class<T> t){return t.cast(c.service(t));} }\n");
        write(root,"in/anoopbhatia/mail/Log.java","package in.anoopbhatia.mail; public class Log { public static void i(String s){} public static void w(Throwable t){} public static void e(Throwable t){} }\n");
    }
    private static String extractClass(String s,String m){return balanced(s,s.indexOf(m),s.indexOf('{',s.indexOf(m)));}
    private static String extractMethod(String s,String m){return balanced(s,s.indexOf(m),s.indexOf('{',s.indexOf(m)));}
    private static String balanced(String s,int start,int brace){int depth=0;for(int i=brace;i<s.length();i++){char c=s.charAt(i);if(c=='{')depth++;else if(c=='}'&&--depth==0)return s.substring(start,i+1);}throw new IllegalArgumentException("unbalanced source");}
    private static String readProjectFile(String relative){try{Path p=Path.of(relative);if(!Files.exists(p))p=Path.of("..",relative);return Files.readString(p,StandardCharsets.UTF_8);}catch(IOException e){throw new AssertionError(e);}}
    private static void write(Path root,String relative,String text)throws IOException{Path p=root.resolve(relative);Files.createDirectories(p.getParent());Files.writeString(p,text,StandardCharsets.UTF_8);}
    private static void delete(Path p)throws IOException{if(!Files.exists(p))return;try(var paths=Files.walk(p)){paths.sorted((a,b)->b.compareTo(a)).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new RuntimeException(e);}});}}
}
