package in.anoopbhatia.mail;

import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Restores the class-name/payload format written by Parcel.writeParcelable. */
final class ParcelableCreatorCompat {
    private ParcelableCreatorCompat() {
    }

    static <T extends Parcelable> T create(String className, Parcel parcel, Class<T> type)
            throws ReflectiveOperationException {
        if (className == null)
            return null;

        ClassLoader loader = type.getClassLoader();
        Class<?> parcelableClass = Class.forName(className, false, loader);
        // Check the expected type before accessing a creator or initializing a class.
        if (!type.isAssignableFrom(parcelableClass))
            throw new IllegalArgumentException("Unexpected parcelable class " + className);
        Field field = parcelableClass.getField("CREATOR");
        if (!Modifier.isStatic(field.getModifiers()))
            throw new IllegalArgumentException("Non-static CREATOR for " + className);
        if (!Parcelable.Creator.class.isAssignableFrom(field.getType()))
            throw new IllegalArgumentException("Invalid CREATOR type for " + className);
        Object value = field.get(null);
        if (!(value instanceof Parcelable.Creator<?>))
            throw new IllegalArgumentException("Invalid CREATOR for " + className);

        Object restored;
        if (value instanceof Parcelable.ClassLoaderCreator<?>)
            restored = ((Parcelable.ClassLoaderCreator<?>) value).createFromParcel(parcel, loader);
        else
            restored = ((Parcelable.Creator<?>) value).createFromParcel(parcel);
        if (restored != null && !type.isInstance(restored))
            throw new IllegalArgumentException("Unexpected restored type for " + className);
        return type.cast(restored);
    }
}
