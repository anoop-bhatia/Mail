package in.anoopbhatia.mail;

import android.os.Parcel;
import android.os.Parcelable;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ParcelableCreatorCompatTest {
    public static class Value implements Parcelable {
        final String value;

        Value(String value) {
            this.value = value;
        }

        public static final Creator<Value> CREATOR = new Creator<Value>() {
            @Override
            public Value createFromParcel(Parcel source) {
                return new Value("payload");
            }

            @Override
            public Value[] newArray(int size) {
                return new Value[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
        }
    }

    private static class InheritedCreatorValue extends Value {
        InheritedCreatorValue() {
            super("unused");
        }
    }

    public static class LoaderValue extends Value {
        static ClassLoader receivedLoader;

        LoaderValue() {
            super("loader");
        }

        public static final ClassLoaderCreator<Value> CREATOR = new ClassLoaderCreator<Value>() {
            @Override
            public Value createFromParcel(Parcel source, ClassLoader loader) {
                receivedLoader = loader;
                return new LoaderValue();
            }

            @Override
            public Value createFromParcel(Parcel source) {
                throw new AssertionError("Loader-aware creator must receive the loader");
            }

            @Override
            public Value[] newArray(int size) {
                return new Value[size];
            }
        };
    }

    public static class InvalidCreatorValue extends Value {
        InvalidCreatorValue() {
            super("invalid");
        }

        public static final String CREATOR = "not a creator";
    }

    public static class WrongResultValue extends Value {
        WrongResultValue() {
            super("invalid result");
        }

        public static final Creator<String> CREATOR = new Creator<String>() {
            @Override
            public String createFromParcel(Parcel source) {
                return "not the expected type";
            }

            @Override
            public String[] newArray(int size) {
                return new String[size];
            }
        };
    }

    @Test
    public void nullClassNamePreservesNullParcelableWithoutConsumingPayload() throws Exception {
        assertNull(ParcelableCreatorCompat.create(null, null, Value.class));
    }

    @Test
    public void invokesPublicCreatorForExpectedClass() throws Exception {
        Value value = ParcelableCreatorCompat.create(Value.class.getName(), null, Value.class);
        assertEquals("payload", value.value);
    }

    @Test
    public void inheritedCreatorIsSupportedLikeFrameworkUriSubclasses() throws Exception {
        Value value = ParcelableCreatorCompat.create(InheritedCreatorValue.class.getName(), null, Value.class);
        assertEquals("payload", value.value);
    }

    @Test
    public void loaderAwareCreatorReceivesExpectedClassLoader() throws Exception {
        Value value = ParcelableCreatorCompat.create(LoaderValue.class.getName(), null, Value.class);
        assertEquals("loader", value.value);
        assertSame(Value.class.getClassLoader(), LoaderValue.receivedLoader);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnexpectedClassBeforeCallingItsCreator() throws Exception {
        ParcelableCreatorCompat.create(String.class.getName(), null, Value.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidCreator() throws Exception {
        ParcelableCreatorCompat.create(InvalidCreatorValue.class.getName(), null, Value.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCreatorReturningUnexpectedType() throws Exception {
        ParcelableCreatorCompat.create(WrongResultValue.class.getName(), null, Value.class);
    }
}
