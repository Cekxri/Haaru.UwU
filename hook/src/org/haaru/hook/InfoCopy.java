/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import java.lang.reflect.*;

/** Copies a Parcelable value before touching fields. Never calls a telephony service setter. */
final class InfoCopy {
    private final Class<?> type;
    private Constructor<?> builderConstructor;
    private Method build, builderCountry, builderCarrier, builderDisplay;
    private Method obtain, write, position, create, recycle;
    private Object creator;
    private Mutator country, carrier, display;

    InfoCopy(Class<?> info, ClassLoader loader) {
        type = info;
        try {
            Class<?> builder = loader.loadClass(info.getName() + "$Builder");
            builderConstructor = builder.getConstructor(info); build = builder.getMethod("build");
            builderCountry = method(builder, "setCountryIso", String.class);
            builderCarrier = method(builder, "setCarrierName", CharSequence.class);
            builderDisplay = method(builder, "setDisplayName", CharSequence.class);
        } catch (ReflectiveOperationException unavailable) { builderConstructor = null; build = null; }
        try {
            Class<?> parcel = loader.loadClass("android.os.Parcel");
            obtain = parcel.getMethod("obtain"); position = parcel.getMethod("setDataPosition", int.class);
            recycle = parcel.getMethod("recycle"); write = info.getMethod("writeToParcel", parcel, int.class);
            creator = info.getField("CREATOR").get(null);
            create = loader.loadClass("android.os.Parcelable$Creator").getMethod("createFromParcel", parcel);
            country = mutator(info, "mCountryIso", "setCountryIso", String.class);
            carrier = mutator(info, "mCarrierName", "setCarrierName", CharSequence.class);
            display = mutator(info, "mDisplayName", "setDisplayName", CharSequence.class);
        } catch (ReflectiveOperationException unavailable) { creator = null; }
        catch (RuntimeException inaccessible) { creator = null; }
    }

    boolean supports(boolean changeCountry, boolean changeName) {
        return (builderConstructor != null && build != null && (!changeCountry || builderCountry != null)
                && (!changeName || (builderCarrier != null && builderDisplay != null)))
                || (creator != null && (!changeCountry || country != null) && (!changeName || (carrier != null && display != null)));
    }

    Object copy(Object original, String iso, String name) throws ReflectiveOperationException {
        if (!type.isInstance(original)) return original;
        boolean changeCountry = !iso.isEmpty(), changeName = !name.isEmpty();
        if (!supports(changeCountry, changeName)) throw new IllegalStateException("This framework cannot copy the requested SIM fields");
        Object copy;
        if (builderConstructor != null && build != null && (!changeCountry || builderCountry != null)
                && (!changeName || (builderCarrier != null && builderDisplay != null))) {
            Object builder = builderConstructor.newInstance(original);
            if (changeCountry) builderCountry.invoke(builder, iso);
            if (changeName) { builderCarrier.invoke(builder, name); builderDisplay.invoke(builder, name); }
            copy = build.invoke(builder);
            independent(original, copy);
        } else {
            Object parcel = obtain.invoke(null);
            try { write.invoke(original, parcel, 0); position.invoke(parcel, 0); copy = create.invoke(creator, parcel); }
            finally { recycle.invoke(parcel); }
            independent(original, copy);
            if (changeCountry) country.set(copy, iso);
            if (changeName) { carrier.set(copy, name); display.set(copy, name); }
        }
        verify(copy, "getCountryIso", changeCountry ? iso : null);
        verify(copy, "getCarrierName", changeName ? name : null);
        verify(copy, "getDisplayName", changeName ? name : null);
        return copy;
    }

    private void independent(Object original, Object copy) throws ReflectiveOperationException {
        if (copy == null || copy == original || copy.getClass() != original.getClass())
            throw new IllegalStateException("Framework did not create an independent SIM information copy");
        for (String getter : new String[] {"getSubscriptionId", "getSimSlotIndex"}) {
            Method method = type.getMethod(getter);
            if (!method.invoke(original).equals(method.invoke(copy))) throw new IllegalStateException("SIM copy changed identity metadata");
        }
    }
    private void verify(Object copy, String getter, String expected) throws ReflectiveOperationException {
        if (expected == null) return;
        Method method = method(type, getter);
        if (method != null && !expected.equals(String.valueOf(method.invoke(copy))))
            throw new IllegalStateException("SIM copy did not accept the requested field");
    }
    private static Method method(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); } catch (NoSuchMethodException missing) { return null; }
    }
    private interface Mutator { void set(Object copy, String value) throws ReflectiveOperationException; }
    private static Mutator mutator(Class<?> type, String fieldName, String setterName, Class<?> argument) {
        for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
            try {
                final Field field = parent.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isAssignableFrom(String.class)) return null;
                field.setAccessible(true);
                return new Mutator() { @Override public void set(Object copy, String value) throws IllegalAccessException { field.set(copy, value); } };
            } catch (NoSuchFieldException missing) { /* Check inherited framework storage. */ }
            catch (RuntimeException inaccessible) { break; }
        }
        final Method method = method(type, setterName, argument);
        return method == null ? null : new Mutator() {
            @Override public void set(Object copy, String value) throws ReflectiveOperationException { method.invoke(copy, value); }
        };
    }
}
