/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Replaces values in this app's memory. Never calls a telephony setter. */
public final class Hooks {
    public static final int COUNTRY = 1, NAME = 2, CONFIG = 3;
    public static final int MANAGER = 1, SUB_ID = 2, SLOT = 3, INFO = 4;
    interface ScopeResolver { int resolve(Object object, int kind); }
    static ScopeResolver resolver = new ScopeResolver() {
        @Override public int resolve(Object object, int kind) { return nativeScope(object, kind); }
    };
    private static native int nativeScope(Object receiver, int kind);
    private static final Map<Integer, Profile> subscriptions = new HashMap<Integer, Profile>();
    private static final Map<Integer, Profile> slots = new HashMap<Integer, Profile>();
    private Hooks() {}

    public static void configure(int[] ids, int[] positions, String[] countries, String[] names) {
        subscriptions.clear(); slots.clear();
        if (ids == null || positions == null || countries == null || names == null || ids.length > 8
                || positions.length != ids.length || countries.length != ids.length || names.length != ids.length)
            throw new IllegalArgumentException("Invalid profile arrays");
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || positions[i] < 0 || positions[i] > 7) throw new IllegalArgumentException("Invalid SIM scope");
            String country = countries[i] == null ? "" : countries[i];
            String name = names[i] == null ? "" : names[i];
            if (!country.isEmpty() && !country.matches("[A-Za-z]{2}")) throw new IllegalArgumentException("Invalid country");
            if (name.codePointCount(0, name.length()) > 80 || name.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new IllegalArgumentException("Invalid name");
            Profile profile = new Profile(country.toLowerCase(Locale.ROOT), name);
            subscriptions.put(ids[i], profile); slots.put(positions[i], profile);
        }
    }

    private static final class Profile {
        final String country, name;
        Profile(String country, String name) { this.country = country; this.name = name; }
    }

    public static final class Callback {
        public volatile Method backup;
        private final int kind, scope;
        private final boolean isStatic;
        public Callback(int kind, int scope, boolean isStatic) {
            this.kind = kind; this.scope = scope; this.isStatic = isStatic;
        }
        public synchronized Object callback(Object[] args) throws Throwable {
            Object receiver = isStatic ? null : args[0];
            int first = isStatic ? 0 : 1;
            Profile profile = null;
            if ((scope == SUB_ID || scope == SLOT) && args.length > first && args[first] instanceof Integer) {
                int id = ((Integer) args[first]).intValue();
                profile = (scope == SLOT ? slots : subscriptions).get(id);
            } else if (receiver != null) {
                int id = resolver.resolve(receiver, scope);
                if (id >= 0) profile = subscriptions.get(id);
            }
            if (profile != null && kind == COUNTRY && !profile.country.isEmpty()) return profile.country;
            if (profile != null && kind == NAME && !profile.name.isEmpty()) return profile.name;
            Method method = backup;
            if (method == null) throw new IllegalStateException("Hook backup is not ready");
            Object result;
            try {
                result = method.invoke(receiver, isStatic ? args : Arrays.copyOfRange(args, 1, args.length));
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (profile == null || kind != CONFIG || result == null) return result;
            // This is a private copy of the app's returned bundle, not the service's config.
            try {
                Class<?> type = result.getClass();
                Object copy = type.getConstructor(type).newInstance(result);
                Method putString = type.getMethod("putString", String.class, String.class);
                if (!profile.country.isEmpty())
                    putString.invoke(copy, "sim_country_iso_override_string", profile.country);
                if (!profile.name.isEmpty()) {
                    type.getMethod("putBoolean", String.class, boolean.class)
                        .invoke(copy, "carrier_name_override_bool", true);
                    putString.invoke(copy, "carrier_name_string", profile.name);
                }
                return copy;
            } catch (ReflectiveOperationException ignored) { return result; }
        }
    }
}
