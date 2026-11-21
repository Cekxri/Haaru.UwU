/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

/** Central phone-service return-value copies. Never invokes subscription or eSIM setters. */
public final class GlobalHooks {
    public static final int CONFIG = 1, CONFIG_SUBSET = 2, INFO = 3, INFO_LIST = 4;
    private static final String COUNTRY_KEY = "sim_country_iso_override_string";
    private static final String NAME_KEY = "carrier_name_string";
    private static final String FLAG_KEY = "carrier_name_override_bool";
    private static volatile Map<Integer, Profile> profiles = Collections.emptyMap();
    private static volatile boolean healthy = true;
    private static volatile String diagnosticError = "";
    private static boolean installed;
    private static int installedCount;
    private static int requiredCount;
    private static volatile boolean coverageComplete;
    private static InfoCopy infoCopy;
    private static Class<?> informationType;
    private static ClassLoader installedLoader;
    private static final List<Object> callbacks = new ArrayList<Object>();
    private static final ThreadLocal<Integer> depth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };

    interface Installer { boolean install(Method target, Callback callback) throws Throwable; }
    interface Caller { Identity capture() throws Throwable; }
    interface OriginalScope { void enter(boolean active) throws Throwable; }
    static Installer installer = new Installer() {
        @Override public boolean install(Method target, Callback callback) { return nativeHookMethod(target, callback); }
    };
    static Caller caller = new Caller() {
        @Override public Identity capture() throws Throwable {
            return new Identity(nativeCallingUid(), internallyTrusted());
        }
    };
    static OriginalScope originalScope = new OriginalScope() {
        @Override public void enter(boolean active) { nativeEnterOriginal(active); }
    };
    private static native boolean nativeHookMethod(Method target, Callback callback);
    private static native void nativeAttached(Object context);
    private static native int nativeCallingUid();
    private static native boolean internallyTrusted();
    private static native void nativeEnterOriginal(boolean active);
    private GlobalHooks() {}

    private static synchronized void failed(Throwable failure) {
        profiles = Collections.emptyMap();
        healthy = false;
        if (!diagnosticError.isEmpty()) return;
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        String message = failure.getClass().getName();
        try {
            String detail = failure.getMessage();
            if (detail != null) message += ": " + detail.substring(0, Math.min(detail.length(), 1024));
        } catch (Throwable ignored) {} // Error reporting must not replace the original return/exception.
        int end = Math.min(message.length(), 1024);
        if (end < message.length() && end > 0 && Character.isHighSurrogate(message.charAt(end - 1))) end--;
        diagnosticError = message.substring(0, end);
    }

    static final class Identity {
        final int uid;
        final boolean trusted;
        Identity(int uid, boolean trusted) { this.uid = uid; this.trusted = trusted; }
        boolean external() { return uid >= 0 && uid != 0 && uid != 1001 && !trusted; }
    }
    private static final class Profile {
        final String country, name;
        Profile(String country, String name) { this.country = country; this.name = name; }
        boolean active() { return !country.isEmpty() || !name.isEmpty(); }
    }

    /** Arrays describe currently active subscriptions; complete snapshots replace prior scopes. */
    public static synchronized void configure(int[] ids, int[] slots, String[] countries, String[] names) {
        if (ids == null || slots == null || countries == null || names == null || ids.length > 8
                || slots.length != ids.length || countries.length != ids.length || names.length != ids.length)
            throw new IllegalArgumentException("Invalid profile arrays");
        Map<Integer, Profile> next = new HashMap<Integer, Profile>();
        Map<Integer, Boolean> seenSlots = new HashMap<Integer, Boolean>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || slots[i] < 0 || slots[i] > 7 || next.containsKey(ids[i]) || seenSlots.containsKey(slots[i]))
                throw new IllegalArgumentException("Invalid or duplicate SIM scope");
            String country = countries[i] == null ? "" : countries[i];
            String name = names[i] == null ? "" : names[i];
            if (!country.isEmpty() && !country.matches("[A-Za-z]{2}")) throw new IllegalArgumentException("Invalid country");
            if (name.codePointCount(0, name.length()) > 80 || name.matches("(?s).*[\\x00-\\x1f\\x7f].*"))
                throw new IllegalArgumentException("Invalid name");
            if (installed && infoCopy != null && !supportsProfile(country, name))
                throw new UnsupportedOperationException("This framework cannot copy the requested SIM fields");
            next.put(ids[i], new Profile(country.toLowerCase(Locale.ROOT), name));
            seenSlots.put(slots[i], Boolean.TRUE);
        }
        profiles = Collections.unmodifiableMap(next);
        healthy = true;
        diagnosticError = "";
    }

    public static boolean isHealthy() { return healthy; }
    public static String getError() { return diagnosticError; }
    public static int getRequiredHooks() { return requiredCount + 1; } // Application.attach plus discovered service readers.
    public static boolean isCoverageComplete() { return coverageComplete && requiredCount >= 2 && installedCount == requiredCount; }
    public static boolean supportsCountry() { return infoCopy != null && infoCopy.supports(true, false); }
    public static boolean supportsCarrierName() { return infoCopy != null && infoCopy.supports(false, true); }
    public static boolean supportsProfile(String country, String name) {
        return infoCopy != null && infoCopy.supports(country != null && !country.isEmpty(), name != null && !name.isEmpty());
    }

    /** Recheck current active identities and logical slots before publishing global properties. */
    public static boolean validateProfiles(Object context, int[] ids, int[] slots) {
        if (ids == null || slots == null || ids.length != slots.length || ids.length > 8) return false;
        if (ids.length == 0) return true;
        if (context == null) return false;
        boolean entered = false, accepted = false;
        try {
            originalScope.enter(true);
            entered = true;
            accepted = validateProfileMapping(context, ids, slots);
        } catch (Throwable ignored) { accepted = false; }
        finally {
            if (entered) {
                try { originalScope.enter(false); }
                catch (Throwable failure) { accepted = false; failed(failure); }
            }
        }
        return accepted;
    }
    private static boolean validateProfileMapping(Object context, int[] ids, int[] slots) throws ReflectiveOperationException {
        Class<?> base = Class.forName("android.content.Context");
        Class<?> subscriptions = Class.forName("android.telephony.SubscriptionManager");
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager");
        Class<?> info = Class.forName("android.telephony.SubscriptionInfo");
        Method service = base.getMethod("getSystemService", Class.class);
        Object subscriptionService = service.invoke(context, subscriptions);
        Object telephonyService = service.invoke(context, telephony);
        if (subscriptionService == null || telephonyService == null) return false;
        Object result = subscriptions.getMethod("getActiveSubscriptionInfoList").invoke(subscriptionService);
        if (!(result instanceof List<?>) || ((List<?>) result).isEmpty()) return false;
        Method subId = info.getMethod("getSubscriptionId"), slotIndex = info.getMethod("getSimSlotIndex");
        Method simState = telephony.getMethod("getSimState", int.class);
        Map<Integer, Integer> active = new HashMap<Integer, Integer>();
        Map<Integer, Boolean> activeSlots = new HashMap<Integer, Boolean>();
        for (Object subscription : (List<?>) result) {
            if (subscription == null) return false;
            int id = ((Integer) subId.invoke(subscription)).intValue();
            int slot = ((Integer) slotIndex.invoke(subscription)).intValue();
            if (id < 0 || slot < 0 || slot > 7 || active.containsKey(id) || activeSlots.containsKey(slot)) return false;
            active.put(id, slot); activeSlots.put(slot, Boolean.TRUE);
        }
        Map<Integer, Boolean> requestedIds = new HashMap<Integer, Boolean>(), requestedSlots = new HashMap<Integer, Boolean>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || slots[i] < 0 || slots[i] > 7 || requestedIds.containsKey(ids[i]) || requestedSlots.containsKey(slots[i])
                    || !Integer.valueOf(slots[i]).equals(active.get(ids[i]))) return false;
            requestedIds.put(ids[i], Boolean.TRUE); requestedSlots.put(slots[i], Boolean.TRUE);
            int state = ((Integer) simState.invoke(telephonyService, slots[i])).intValue();
            if (state != 5 && state != 10) return false; // SIM_STATE_READY / SIM_STATE_LOADED.
        }
        return true;
    }

    /** Notification only: listeners may query fresh copies without a CarrierConfig mutation. */
    public static boolean notifyChanged(Object context) {
        try {
            Class<?> base = Class.forName("android.content.Context");
            Class<?> registry = Class.forName("android.telephony.TelephonyRegistryManager");
            Object service = base.getMethod("getSystemService", Class.class).invoke(context, registry);
            if (service == null) return false;
            registry.getMethod("notifySubscriptionInfoChanged").invoke(service);
            return true;
        } catch (ReflectiveOperationException ignored) { return false; }
        catch (RuntimeException ignored) { return false; }
        catch (LinkageError ignored) { return false; }
    }

    /** Runs after the original Application.attach; the Context owns the phone APK classloader. */
    public static synchronized int initialize(Object context) throws Throwable {
        try {
            if (context == null) throw new IllegalArgumentException("Application.attach did not provide a phone Context");
            Class<?> base = Class.forName("android.content.Context");
            ClassLoader loader = (ClassLoader) base.getMethod("getClassLoader").invoke(context);
            return initializeWithLoader(loader);
        } catch (InvocationTargetException failure) { failed(failure); throw failure.getCause(); }
        catch (Throwable failure) { failed(failure); throw failure; }
    }

    /** Fallback for compiled framework callers which inline Application.attach.
     * currentApplication is published only after the app has its real Context.
     * Never construct a phone service or manufacture a classloader here. */
    public static Object findApplicationContext() throws ReflectiveOperationException {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Method current = thread.getDeclaredMethod("currentApplication");
        current.setAccessible(true);
        return current.invoke(null);
    }

    public static Method[] applicationCallers() throws ReflectiveOperationException {
        List<Method> callers = new ArrayList<Method>();
        for (String name : new String[] {"android.app.LoadedApk", "android.app.Instrumentation"}) {
            for (Method method : Class.forName(name).getDeclaredMethods()) {
                if (method.getName().startsWith("makeApplication") || method.getName().equals("newApplication")) {
                    method.setAccessible(true);
                    callers.add(method);
                }
            }
        }
        return callers.toArray(new Method[callers.size()]);
    }

    static synchronized int initializeWithLoader(ClassLoader loader) throws Throwable {
        if (installed) {
            if (installedLoader == loader && isCoverageComplete()) return installedCount;
            throw new IllegalStateException("Central service initialisation is incomplete" +
                    (diagnosticError.isEmpty() ? "" : ": " + diagnosticError));
        }
        installed = true;
        installedLoader = loader;
        try {
            if (loader == null) throw new IllegalArgumentException("Missing phone classloader");
            Class<?> config = loader.loadClass("com.android.phone.CarrierConfigLoader");
            Class<?> info = loader.loadClass("android.telephony.SubscriptionInfo");
            informationType = info;
            Class<?> bundle = loader.loadClass("android.os.PersistableBundle");
            // Resolve copy operations before publishing ready, without constructing service singletons.
            bundle.getConstructor(bundle); bundle.getMethod("isEmpty");
            bundle.getMethod("putString", String.class, String.class); bundle.getMethod("putBoolean", String.class, boolean.class);
            info.getMethod("getSubscriptionId"); info.getMethod("getSimSlotIndex");
            infoCopy = new InfoCopy(info, loader);
            caller.capture();
            Map<Method, Integer> readers = new LinkedHashMap<Method, Integer>();
            discover(config, optionalClass(loader, "com.android.internal.telephony.ICarrierConfigLoader"), bundle, true, readers);
            int carrierReaders = readers.size();
            if (carrierReaders == 0) throw new IllegalStateException("No exposed CarrierConfig SIM readers are available");
            Class<?> aidl = optionalClass(loader, "com.android.internal.telephony.ISub");
            // Both implementations may be present during a framework transition.
            for (String name : new String[] {"com.android.internal.telephony.subscription.SubscriptionManagerService", "com.android.internal.telephony.SubscriptionController"}) {
                Class<?> service = optionalClass(loader, name);
                if (service != null) discover(service, aidl, info, false, readers);
            }
            if (readers.size() == carrierReaders) throw new IllegalStateException("No exposed subscription information readers are available");
            boolean informationReaders = false;
            for (Map.Entry<Method, Integer> reader : readers.entrySet()) {
                if (reader.getValue() == INFO || (reader.getValue() == INFO_LIST && informationList(reader.getKey(), info))) informationReaders = true;
            }
            if (!informationReaders) throw new IllegalStateException("Cannot prove subscription information reader coverage");
            requiredCount = readers.size();
            for (Map.Entry<Method, Integer> reader : readers.entrySet()) {
                Method method = reader.getKey(); int keys = -1;
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) if (parameters[i] == String[].class) keys = i + 1;
                if (!attach(method, new Callback(reader.getValue(), keys)))
                    throw new IllegalStateException("Required central hook failed: " + method.toGenericString());
                installedCount++;
            }
            coverageComplete = installedCount == requiredCount;
            healthy = true;
            diagnosticError = "";
            return installedCount;
        } catch (Throwable failure) { failed(failure); throw failure; }
    }

    private static Class<?> optionalClass(ClassLoader loader, String name) throws ClassNotFoundException {
        try { return loader.loadClass(name); } catch (ClassNotFoundException absent) { return null; }
    }
    private static void discover(Class<?> service, Class<?> aidl, Class<?> valueType, boolean carrier,
            Map<Method, Integer> readers) throws ReflectiveOperationException {
        List<Method> methods = new ArrayList<Method>(Arrays.asList(service.getMethods()));
        if (aidl != null) for (Method exposed : aidl.getMethods()) {
            if (readerKind(exposed, valueType, carrier) != 0) methods.add(service.getMethod(exposed.getName(), exposed.getParameterTypes()));
        }
        Collections.sort(methods, new Comparator<Method>() {
            @Override public int compare(Method a, Method b) { return a.toGenericString().compareTo(b.toGenericString()); }
        });
        for (Method method : methods) {
            int kind = readerKind(method, valueType, carrier);
            if (kind == 0) continue;
            if (Modifier.isAbstract(method.getModifiers())) throw new IllegalStateException("Exposed reader has no concrete implementation: " + method);
            readers.put(method, kind);
        }
    }
    private static int readerKind(Method method, Class<?> valueType, boolean carrier) {
        if (Modifier.isStatic(method.getModifiers()) || !method.getName().startsWith("get")) return 0;
        if (carrier) {
            if (!valueType.isAssignableFrom(method.getReturnType()) || !method.getName().startsWith("getConfig")) return 0;
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().contains("SubId") || parameters.length == 0 || parameters[0] != int.class)
                throw new IllegalStateException("Cannot prove the SIM scope of exposed CarrierConfig reader: " + method);
            for (Class<?> parameter : parameters) if (parameter == String[].class) return CONFIG_SUBSET;
            return CONFIG;
        }
        if (valueType.isAssignableFrom(method.getReturnType())) return INFO;
        if (List.class.isAssignableFrom(method.getReturnType())) {
            if (method.getGenericReturnType() instanceof ParameterizedType) {
                java.lang.reflect.Type[] arguments = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments();
                if (arguments.length == 1 && arguments[0] == valueType) return INFO_LIST;
            }
            // Erased AIDL list signatures still expose a read-only result; non-info values are left intact.
            return INFO_LIST;
        }
        return 0;
    }
    private static boolean informationList(Method method, Class<?> info) {
        if (method.getGenericReturnType() instanceof ParameterizedType) {
            java.lang.reflect.Type[] arguments = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] == info) return true;
        }
        String name = method.getName();
        return name.contains("Subscription") || name.contains("SubInfo");
    }
    private static boolean attach(Method target, Callback object) throws Throwable {
        synchronized (object) {
            if (!installer.install(target, object) || object.backup == null) return false;
            callbacks.add(object);
            return true;
        }
    }
    private static Object original(Method backup, Object[] args) throws Throwable {
        if (backup == null) throw new IllegalStateException("Hook backup is not ready");
        try { return backup.invoke(args[0], Arrays.copyOfRange(args, 1, args.length)); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object guardedOriginal(Method backup, Object[] args) throws Throwable {
        boolean entered = false;
        Throwable priorFailure = null;
        try {
            originalScope.enter(true);
            entered = true;
            return original(backup, args);
        } catch (Throwable failure) {
            priorFailure = failure;
            if (!entered) failed(failure);
            throw failure;
        } finally {
            if (entered) {
                try { originalScope.enter(false); }
                catch (Throwable failure) {
                    failed(failure);
                    if (priorFailure == null) throw failure;
                    if (priorFailure != failure) priorFailure.addSuppressed(failure);
                }
            }
        }
    }

    public static final class AttachCallback {
        public volatile Method backup;
        public Object callback(Object[] args) throws Throwable {
            Method method;
            synchronized (this) { method = backup; }
            Object result = original(method, args);
            if (args.length > 1 && args[1] != null) {
                try { nativeAttached(args[1]); }
                catch (Throwable failure) { failed(failure); }
            }
            return result;
        }
    }

    public static final class Callback {
        public volatile Method backup;
        private final int kind;
        private final int keysIndex;
        public Callback(int kind) { this(kind, 4); }
        Callback(int kind, int keysIndex) { this.kind = kind; this.keysIndex = keysIndex; }
        public Object callback(Object[] args) throws Throwable {
            Method method;
            synchronized (this) { method = backup; }
            int currentDepth = depth.get().intValue();
            Map<Integer, Profile> snapshot = profiles;
            boolean eligible = !snapshot.isEmpty() && currentDepth == 0;
            // Capture identity at entry; service backups may clear Binder calling identity.
            if (eligible) {
                try { eligible = caller.capture().external(); }
                catch (Throwable ignored) { eligible = false; }
            }
            Object result;
            depth.set(currentDepth + 1);
            try { result = guardedOriginal(method, args); }
            finally { if (currentDepth == 0) depth.remove(); else depth.set(currentDepth); }
            if (!eligible || result == null || profiles != snapshot) return result;
            try {
                if (kind == CONFIG || kind == CONFIG_SUBSET) {
                    if (args.length < 2 || !(args[1] instanceof Integer)) return result;
                    Profile profile = snapshot.get((Integer) args[1]);
                    if (profile == null || !profile.active()) return result;
                    String[] keys = null;
                    if (kind == CONFIG_SUBSET) {
                        if (keysIndex < 1 || args.length <= keysIndex || !(args[keysIndex] instanceof String[])) return result;
                        keys = (String[]) args[keysIndex];
                    }
                    return copyConfig(result, profile, keys);
                }
                if (kind == INFO) return copyInfo(result, snapshot);
                if (kind == INFO_LIST && result instanceof List<?>) {
                    List<?> source = (List<?>) result;
                    List<Object> copy = new ArrayList<Object>(source.size());
                    boolean changed = false;
                    for (Object info : source) {
                        Object value = info == null ? null : copyInfo(info, snapshot);
                        copy.add(value); changed |= value != info;
                    }
                    return changed ? copy : result;
                }
            } catch (ReflectiveOperationException failure) { failed(failure); }
            catch (RuntimeException failure) { failed(failure); }
            catch (LinkageError failure) { failed(failure); }
            return result;
        }
    }

    private static boolean requested(String[] keys, String key) {
        if (keys == null) return true;
        for (String candidate : keys) if (key.equals(candidate)) return true;
        return false;
    }
    private static Object copyConfig(Object original, Profile profile, String[] keys) throws ReflectiveOperationException {
        Class<?> type = original.getClass();
        if (Boolean.TRUE.equals(type.getMethod("isEmpty").invoke(original))) return original;
        boolean country = !profile.country.isEmpty() && requested(keys, COUNTRY_KEY);
        boolean name = !profile.name.isEmpty() && requested(keys, NAME_KEY);
        boolean flag = !profile.name.isEmpty() && requested(keys, FLAG_KEY);
        if (!country && !name && !flag) return original;
        Object copy = type.getConstructor(type).newInstance(original);
        Method putString = type.getMethod("putString", String.class, String.class);
        if (country) putString.invoke(copy, COUNTRY_KEY, profile.country);
        if (name) putString.invoke(copy, NAME_KEY, profile.name);
        if (flag) type.getMethod("putBoolean", String.class, boolean.class).invoke(copy, FLAG_KEY, true);
        return copy;
    }
    private static Object copyInfo(Object original, Map<Integer, Profile> snapshot) throws ReflectiveOperationException {
        Class<?> type = original.getClass();
        Class<?> framework = informationType == null ? Class.forName("android.telephony.SubscriptionInfo", false, type.getClassLoader()) : informationType;
        if (!framework.isInstance(original)) return original;
        int id = ((Integer) type.getMethod("getSubscriptionId").invoke(original)).intValue();
        Profile profile = snapshot.get(id);
        if (profile == null || !profile.active()) return original;
        InfoCopy copier = infoCopy == null ? new InfoCopy(framework, type.getClassLoader()) : infoCopy;
        return copier.copy(original, profile.country, profile.name);
    }

    static synchronized void resetForTests() {
        profiles = Collections.emptyMap(); installed = false; installedCount = 0;
        requiredCount = 0; coverageComplete = false; infoCopy = null; informationType = null;
        healthy = true;
        diagnosticError = "";
        installedLoader = null; callbacks.clear(); depth.remove();
    }
}
