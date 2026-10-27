package in.anoopbhatia.mail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Resource/source contracts for drawer UI that local JVM Android stubs cannot render. */
public class NavigationFooterTest {
    @Test
    public void footerIsTwoQuietNonInteractiveLinesWithoutPipe() throws Exception {
        Document footer = xml("layout/include_nav_footer.xml");
        NodeList labels = footer.getElementsByTagName("TextView");
        assertEquals(2, labels.getLength());
        Element version = (Element) labels.item(0);
        Element author = (Element) labels.item(1);
        assertEquals("@+id/tvNavVersion", version.getAttribute("android:id"));
        assertEquals("@style/TextAppearance.AppCompat.Small", version.getAttribute("android:textAppearance"));
        assertEquals("12sp", author.getAttribute("android:textSize"));
        assertEquals("@string/nav_footer_author", author.getAttribute("android:text"));
        for (Element label : new Element[]{version, author}) {
            assertEquals("?android:attr/textColorSecondary", label.getAttribute("android:textColor"));
            assertFalse(label.hasAttribute("android:maxLines"));
            assertFalse(label.hasAttribute("android:singleLine"));
        }
        String source = resource("layout/include_nav_footer.xml");
        assertFalse(source.contains("android:onClick"));
        assertFalse(source.contains("android:clickable=\"true\""));
        assertFalse(source.contains("selectableItemBackground"));
        assertEquals("Mail v%1$s (%2$s)", string("nav_footer_version"));
        assertEquals("By Anoop Bhatia", string("nav_footer_author"));
        assertFalse(string("nav_footer_version").contains("|"));
        assertFalse(string("nav_footer_author").contains("|"));
    }

    @Test
    public void bothDrawersUseSharedFooterAndLiveBuildVersionWithoutAboutRoute() throws Exception {
        for (String activity : new String[]{"ActivityView", "ActivitySetup"}) {
            String source = java(activity);
            assertTrue(source.replaceAll("\\s+", " ").contains(
                    "getString(R.string.nav_footer_version, BuildConfig.VERSION_NAME + BuildConfig.REVISION, BuildConfig.BUILD_TYPE)"));
            assertFalse(source.contains("R.string.menu_about"));
            assertFalse(source.contains("onMenuAbout"));
            assertFalse(source.contains("new FragmentAbout()"));
            assertTrue(source.contains("R.string.menu_faq"));
        }
        assertTrue(resource("layout/include_nav.xml").contains("layout=\"@layout/include_nav_footer\""));
        assertTrue(resource("layout/include_nav.xml").contains("app:layout_constraintTop_toBottomOf=\"@id/rvMenuExtra\""));
        String setup = resource("layout/activity_setup.xml");
        assertTrue(setup.contains("layout=\"@layout/include_nav_footer\""));
        assertTrue(setup.contains("app:layout_constraintBottom_toTopOf=\"@id/navFooter\""));
        // Every mail layout shares include_nav, including landscape/split layouts.
        for (String layout : new String[]{"activity_view_portrait", "activity_view_portrait_split", "activity_view_landscape_split"})
            assertTrue(resource("layout/" + layout + ".xml").contains("@layout/include_nav"));
    }

    @Test
    public void iconOnlyDrawerHidesFooterInitiallyAndAfterWidthToggle() throws Exception {
        String source = java("ActivityView");
        String visibility = "drawerContainer.findViewById(R.id.navFooter).setVisibility(nav_expanded ? View.VISIBLE : View.GONE);";
        assertTrue(source.indexOf(visibility) >= 0);
        assertTrue(source.lastIndexOf(visibility) > source.indexOf(visibility));
        assertTrue(source.substring(source.indexOf("private void setDrawerExpanded")).contains(visibility));
    }

    @Test
    public void oldSavedAboutScreenCanRestoreButContainsOnlyFooter() throws Exception {
        String source = java("FragmentAbout");
        assertTrue(source.contains("class FragmentAbout extends FragmentBase"));
        assertTrue(source.contains("inflate(R.layout.include_nav_footer"));
        assertFalse(source.contains("R.layout.fragment_about"));
        assertFalse(source.contains("tvRelease"));
        assertFalse(source.contains("tvDownloaded"));
        assertFalse(source.contains("tvUpdated"));
        assertFalse(Files.exists(Paths.get("src/main/res/layout/fragment_about.xml")));
    }

    private static String java(String name) throws Exception {
        return Files.readString(Paths.get("src/main/java/in/anoopbhatia/mail/" + name + ".java"));
    }

    private static String resource(String path) throws Exception {
        return Files.readString(Paths.get("src/main/res/" + path));
    }

    private static Document xml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(Paths.get("src/main/res/" + path).toFile());
    }

    private static String string(String name) throws Exception {
        NodeList strings = xml("values/strings.xml").getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element element = (Element) strings.item(i);
            if (name.equals(element.getAttribute("name")))
                return element.getTextContent();
        }
        throw new AssertionError("Missing string " + name);
    }
}
