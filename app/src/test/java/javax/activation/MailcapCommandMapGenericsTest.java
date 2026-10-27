package javax.activation;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class MailcapCommandMapGenericsTest {
    private static final String MIME = "application/x-cue-mailcap-regression";

    private static MailcapCommandMap commands() {
        MailcapCommandMap map = new MailcapCommandMap();
        map.addMailcap(MIME + ";; x-java-view=example.PrimaryView; x-java-edit=example.PrimaryEdit");
        map.addMailcap(MIME + ";; x-java-view=example.SecondView");
        map.addMailcap(MIME + ";; x-java-fallback-entry=true; x-java-view=example.FallbackView; x-java-print=example.FallbackPrint");
        return map;
    }

    private static List<String> classes(CommandInfo[] commands, String verb) {
        return Arrays.stream(commands)
                .filter(command -> verb.equals(command.getCommandName()))
                .map(CommandInfo::getCommandClass)
                .collect(Collectors.toList());
    }

    @Test
    public void allCommandsPreserveProviderOrderAndAppendFallbackLast() {
        assertEquals(Arrays.asList("example.PrimaryView", "example.SecondView", "example.FallbackView"),
                classes(commands().getAllCommands(MIME), "view"));
    }

    @Test
    public void preferredCommandsKeepFirstProviderAndFallbackOnlyForMissingVerbs() {
        CommandInfo[] preferred = commands().getPreferredCommands(MIME);
        assertEquals(Arrays.asList("example.PrimaryView"), classes(preferred, "view"));
        assertEquals(Arrays.asList("example.PrimaryEdit"), classes(preferred, "edit"));
        assertEquals(Arrays.asList("example.FallbackPrint"), classes(preferred, "print"));
    }

    @Test
    public void commandLookupUsesPrimaryBeforeFallbackAndNormalizesMimeCase() {
        MailcapCommandMap map = commands();
        assertEquals("example.PrimaryView", map.getCommand(MIME.toUpperCase(java.util.Locale.ROOT), "view").getCommandClass());
        assertEquals("example.FallbackPrint", map.getCommand(MIME, "print").getCommandClass());
    }
}
