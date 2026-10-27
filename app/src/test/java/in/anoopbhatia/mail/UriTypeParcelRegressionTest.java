package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wire-layout contracts complement the executable creator compatibility tests. */
public class UriTypeParcelRegressionTest {
    @Test
    public void writerPreservesPriorVersionClassNamePayloadAndMimeLayout() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/UriType.java"));
        assertTrue(source.contains("parcel.writeParcelable(this.uri, flags);\n" +
                "        parcel.writeString(this.type);"));
        assertTrue(source.contains("ParcelableCreatorCompat.create(in.readString(), in, Uri.class)"));
        assertTrue(source.contains("this.type = in.readString();"));
        assertFalse(source.contains("writeTypedObject"));
    }

    @Test
    public void restoreDoesNotInvokeApi33OrDeprecatedParcelableReader() throws Exception {
        String source = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/UriType.java"));
        String helper = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/ParcelableCreatorCompat.java"));
        assertFalse(source.contains(".readParcelable("));
        assertFalse(helper.contains(".readParcelable("));
        assertFalse(helper.contains("getMethod(\"readParcelable\""));
        assertTrue(helper.contains("parcelableClass.getField(\"CREATOR\")"));
        assertTrue(source.contains("return new UriType(in);"));
    }
}
