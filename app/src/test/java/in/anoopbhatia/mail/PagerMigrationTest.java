package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PagerMigrationTest {
    @Test
    public void legendAndOptionsUseViewPager2WithoutLegacyPagerApis() throws Exception {
        String legend = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/FragmentLegend.java"));
        String options = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/FragmentOptions.java"));
        String layouts = Files.readString(Path.of("src/main/res/layout/fragment_legend.xml")) +
                Files.readString(Path.of("src/main/res/layout/fragment_options.xml"));

        assertTrue(legend.contains("FragmentStateAdapter"));
        assertTrue(options.contains("FragmentStateAdapter"));
        assertTrue(legend.contains("TabLayoutMediator"));
        assertTrue(options.contains("TabLayoutMediator"));
        assertTrue(layouts.contains("androidx.viewpager2.widget.ViewPager2"));
        assertFalse(legend.contains("FragmentStatePagerAdapter"));
        assertFalse(options.contains("FragmentStatePagerAdapter"));
        assertFalse(layouts.contains("androidx.viewpager.widget.ViewPager"));
        assertTrue(options.contains("registerFragmentLifecycleCallbacks(searchLifecycleCallbacks"));
        assertTrue(options.contains("unregisterFragmentLifecycleCallbacks(searchLifecycleCallbacks"));
        assertTrue(options.contains("pager.post(FragmentOptions.this::applyPendingSearch)"));
    }

    @Test
    public void fixedPagesRetainTitlesSelectionAndOffscreenRetention() throws Exception {
        String legend = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/FragmentLegend.java"));
        String options = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/FragmentOptions.java"));

        assertTrue(legend.contains("return 5;"));
        assertTrue(legend.contains("pager.setCurrentItem(3)"));
        assertTrue(legend.contains("getPageTitle"));
        assertTrue(options.contains("TAB_PAGES.length"));
        assertTrue(options.contains("TAB_LABELS.indexOf(tab)"));
        assertTrue(options.contains("pager.setOffscreenPageLimit(1)"));
        assertTrue(options.contains("new ViewPager2.OnPageChangeCallback()"));
        for (String layout : new String[]{"fragment_legend.xml", "fragment_options.xml"}) {
            String xml = Files.readString(Path.of("src/main/res/layout", layout));
            assertTrue(layout, xml.contains("app:layout_constraintTop_toBottomOf=\"@id/tab_layout\""));
            assertTrue(layout, xml.contains("android:layout_height=\"0dp\""));
        }
    }
}
