package com.github.haaru.ksu;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uses the device's boot-class-path AIDL interfaces, never a fixed Binder transaction number.
 * This class deliberately has no compile-time dependency on the Android SDK.
 */
public final class AndroidBackend implements Backend {
    private static final String COUNTRY = "sim_country_iso_override_string";
    private static final String CARRIER_OVERRIDE = "carrier_name_override_bool";
    private static final String CARRIER_NAME = "carrier_name_string";
    private static final String CONFIG_READY = "carrier_config_applied_bool";
    private static final int MAX_SLOTS = 8;
    private final Path originalShadow;
    private final String boot;
    private Object contextTelephony;

    public AndroidBackend() throws Exception {
        this(null, null);
    }

    /** Production helper can recover the phone engine's original same-boot property view. */
    public AndroidBackend(Path originalShadow, String boot) throws Exception {
        checkRootAndSdk();
        this.originalShadow = originalShadow == null ? null : originalShadow.toAbsolutePath().normalize();
        this.boot = boot;
    }

    public static void checkRootAndSdk() throws Exception {
        Class<?> process = Class.forName("android.os.Process");
        int uid = ((Number) call(process.getMethod("myUid"), null)).intValue();
        if (uid != 0) {
            throw new SecurityException("Haaru KernelSU backend requires UID 0");
        }
        if (sdk() < 29) {
            throw new UnsupportedOperationException(
                    "Haaru requires Android 10 / API 29 or newer");
        }
    }

    @Override
    public List<Subscription> subscriptions() throws Exception {
        Remote sub = service("isub", "com.android.internal.telephony.ISub");
        Set<Integer> ids = new LinkedHashSet<Integer>();
        Map<Integer, Object> information = new LinkedHashMap<Integer, Object>();
        Map<Integer, Integer> knownSlots = new LinkedHashMap<Integer, Integer>();
        boolean enumerated = false;
        Exception enumerationFailure = null;

        if (sub != null) {
            try {
                Method active = optional(sub.type, "getActiveSubIdList", boolean.class);
                Object value;
                if (active != null) {
                    value = call(active, sub.target, false);
                } else {
                    active = optional(sub.type, "getActiveSubIdList");
                    if (active == null) {
                        throw new UnsupportedOperationException("No active subscription ID API");
                    }
                    value = call(active, sub.target);
                }
                if (value == null) {
                    throw new IllegalStateException("Active subscription IDs are not ready");
                }
                addIds(ids, value);
                enumerated = true;
            } catch (Exception error) {
                enumerationFailure = error;
            }

            // The list supplies per-SIM identity. Its absence must not invalidate a working ID API.
            try {
                Object value = activeInformation(sub);
                if (value == null) {
                    throw new IllegalStateException("Active subscription information is not ready");
                }
                if (!(value instanceof List)) {
                    throw new IllegalStateException("Unexpected active subscription list type");
                }
                if (value instanceof List) {
                    for (Object info : (List<?>) value) {
                        if (info == null) {
                            continue;
                        }
                        int id = intMethod(info, "getSubscriptionId");
                        int slot = intMethod(info, "getSimSlotIndex");
                        if (valid(id, slot)) {
                            ids.add(id);
                            information.put(id, info);
                            knownSlots.put(id, slot);
                        }
                    }
                }
                enumerated = true;
            } catch (Exception error) {
                if (enumerationFailure == null) {
                    enumerationFailure = error;
                }
            }
        }

        if (!enumerated) {
            try {
                enumerateStatic(ids, knownSlots);
                enumerated = true;
            } catch (Exception error) {
                if (enumerationFailure != null) {
                    error.addSuppressed(enumerationFailure);
                }
                throw error;
            }
        }

        List<Subscription> result = new ArrayList<Subscription>();
        for (Integer id : ids) {
            if (id == null || id.intValue() < 0) {
                continue;
            }
            Integer slot = knownSlots.get(id);
            if (slot == null) {
                try {
                    if (sub != null) {
                        slot = ((Number) call(
                                sub.type.getMethod("getSlotIndex", int.class),
                                sub.target, id.intValue())).intValue();
                    } else {
                        Class<?> manager = Class.forName("android.telephony.SubscriptionManager");
                        slot = ((Number) call(
                                manager.getMethod("getSlotIndex", int.class),
                                null, id.intValue())).intValue();
                    }
                } catch (Exception error) {
                    // Do not guess which SIM an ID belongs to.
                    throw new IllegalStateException(
                            "Cannot resolve slot for active subscription " + id, error);
                }
            }
            if (!valid(id.intValue(), slot.intValue())) {
                continue;
            }
            Object info = information.get(id);
            if (info == null && sub != null) {
                try {
                    info = subscriptionInformation(sub, id.intValue());
                } catch (Exception ignored) {
                    // Identity access is optional; its weaker guarantee is explicit below.
                }
            }
            result.add(new Subscription(slot.intValue(), id.intValue(),
                    identity(info, slot.intValue(), id.intValue())));
        }
        Collections.sort(result, new Comparator<Subscription>() {
            @Override
            public int compare(Subscription a, Subscription b) {
                int slotOrder = Integer.compare(a.slot, b.slot);
                return slotOrder != 0 ? slotOrder : Integer.compare(a.subId, b.subId);
            }
        });
        return result;
    }

    @Override
    public Values read(int subId) throws Exception {
        requireSubId(subId);
        Remote carrier = requireService("carrier_config",
                "com.android.internal.telephony.ICarrierConfigLoader");
        Method method = optional(carrier.type, "getConfigForSubIdWithFeature",
                int.class, String.class, String.class);
        Object bundle;
        if (method != null) {
            bundle = call(method, carrier.target, subId, null, null);
        } else {
            method = optional(carrier.type, "getConfigForSubId", int.class, String.class);
            if (method != null) {
                bundle = call(method, carrier.target, subId, null);
            } else {
                method = optional(carrier.type, "getConfigForSubId", int.class);
                if (method == null) {
                    throw new UnsupportedOperationException("No supported CarrierConfig read API");
                }
                bundle = call(method, carrier.target, subId);
            }
        }
        if (bundle == null) {
            throw new IllegalStateException("CarrierConfig service returned no configuration");
        }
        Class<?> persistable = Class.forName("android.os.PersistableBundle");
        if (!persistable.isInstance(bundle)) {
            throw new IllegalStateException("Unexpected CarrierConfig bundle type");
        }
        Method getString = persistable.getMethod("getString", String.class);
        Method getBoolean = persistable.getMethod("getBoolean", String.class);
        String country = string(call(getString, bundle, COUNTRY));
        boolean override = Boolean.TRUE.equals(call(getBoolean, bundle, CARRIER_OVERRIDE));
        String name = string(call(getString, bundle, CARRIER_NAME));
        boolean ready = Boolean.TRUE.equals(call(getBoolean, bundle, CONFIG_READY));
        return new Values(country, override, name, ready);
    }

    @Override
    public String originalCarrierName(int subId) throws Exception {
        requireSubId(subId);
        Remote sub = service("isub", "com.android.internal.telephony.ISub");
        if (sub == null) return "";
        Object info;
        try { info = subscriptionInformation(sub, subId); }
        catch (UnsupportedOperationException unavailable) { info = null; }
        if (info == null) {
            Object list;
            try { list = activeInformation(sub); }
            catch (UnsupportedOperationException unavailable) { return ""; }
            if (list == null) return "";
            if (!(list instanceof List))
                throw new IllegalStateException("Unexpected active subscription list type");
            for (Object item : (List<?>) list) {
                if (item == null || intMethod(item, "getSubscriptionId") != subId) continue;
                if (info != null)
                    throw new IllegalStateException("Original carrier subscription mapping is ambiguous");
                info = item;
            }
        }
        if (info == null) return "";
        if (intMethod(info, "getSubscriptionId") != subId
                || !valid(subId, intMethod(info, "getSimSlotIndex")))
            throw new IllegalStateException("Original carrier subscription is not active");
        Class<?> information = Class.forName("android.telephony.SubscriptionInfo");
        Method getCarrierName = optional(information, "getCarrierName");
        if (getCarrierName == null) return "";
        Object value = call(getCarrierName, info);
        if (value == null) return "";
        if (!(value instanceof CharSequence))
            throw new IllegalStateException("Unexpected original carrier name type");
        // Read ISub's label rather than TelephonyManager's overlaid operator property.
        return value.toString();
    }

    @Override
    public String simCountry(int subId) throws Exception {
        requireSubId(subId);
        String original = originalSimCountry(subId);
        if (original != null) return original;
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager");
        // getDefault() is a contextless singleton. On current Android,
        // createForSubscriptionId() passes that null Context to a constructor
        // which dereferences Context.getApplicationContext(). Prefer the
        // device's equivalent static getter in app_process instead.
        Method bySubscription = optional(telephony, "getSimCountryIso", int.class);
        if (bySubscription != null && Modifier.isStatic(bySubscription.getModifiers())) {
            try {
                String country = string(call(bySubscription, null, subId));
                if (!country.isEmpty()) return country;
                // app_process may have an uninitialized SubscriptionManager cache:
                // its static phone ID is -1 while direct ISub still resolves the SIM.
            }
            catch (UnsupportedOperationException unavailable) { /* Try the other supported form. */ }
        }
        // Mapping failures remain explicit even on ROMs needing a Context fallback.
        int phoneId = logicalPhoneId(subId);
        Method byPhone = optional(telephony, "getSimCountryIsoForPhone", int.class);
        if (byPhone != null && Modifier.isStatic(byPhone.getModifiers())) {
            try {
                return string(call(byPhone, null, phoneId));
            } catch (UnsupportedOperationException unavailable) { /* Use a real Context, never getDefault(). */ }
        }
        Object manager = contextTelephonyManager();
        Method forSubscription = telephony.getMethod("createForSubscriptionId", int.class);
        Object scoped = call(forSubscription, manager, subId);
        if (scoped == null) {
            throw new IllegalStateException("No TelephonyManager for subscription");
        }
        return string(call(telephony.getMethod("getSimCountryIso"), scoped));
    }

    /** Resolve the logical phone from fresh ISub data, without the app_process static cache. */
    private int logicalPhoneId(int subId) throws Exception {
        Remote remote = requireService("isub", "com.android.internal.telephony.ISub");
        List<Subscription> active = new ArrayList<Subscription>();
        try {
            Object value = activeInformation(remote);
            if (!(value instanceof List)) throw new IllegalStateException("Active subscription information is not ready");
            // Validate the raw list before subscriptions() can coalesce duplicate IDs.
            for (Object info : (List<?>) value) {
                if (info == null) continue;
                int id = intMethod(info, "getSubscriptionId"), slot = intMethod(info, "getSimSlotIndex");
                if (id < 0 || slot < 0) continue;
                if (!valid(id, slot)) throw new IllegalStateException("Invalid active SIM logical slot");
                active.add(new Subscription(slot, id, ""));
            }
        } catch (UnsupportedOperationException unavailable) { active = subscriptions(); }
        Set<Integer> ids = new LinkedHashSet<Integer>(), slots = new LinkedHashSet<Integer>();
        int selectedSlot = -1;
        for (Subscription item : active) {
            if (!ids.add(item.subId) || !slots.add(item.slot))
                throw new IllegalStateException("Active SIM phone mapping is ambiguous");
            if (item.subId == subId) selectedSlot = item.slot;
        }
        if (selectedSlot < 0) throw new IllegalStateException("Cannot resolve phone: subscription is not active");
        for (String name : new String[] {"getPhoneId", "getSlotIndex"}) {
            Method method = optional(remote.type, name, int.class);
            if (method == null) continue;
            Object value;
            try { value = call(method, remote.target, subId); }
            catch (UnsupportedOperationException unavailable) { continue; }
            if (!(value instanceof Number)) throw new IllegalStateException("Invalid direct ISub phone mapping type");
            int phoneId = ((Number) value).intValue();
            if (phoneId < 0) continue;
            if (phoneId >= MAX_SLOTS || phoneId != selectedSlot)
                throw new IllegalStateException("Direct ISub phone mapping disagrees with the active logical SIM slot");
            return phoneId;
        }
        // SubscriptionInfo.getSimSlotIndex is the logical phone slot on this API.
        // It is used only after validating the complete active mapping above.
        return selectedSlot;
    }

    /** Only ROMs without the static country getters require an app_process Context. */
    private synchronized Object contextTelephonyManager() throws Exception {
        if (contextTelephony != null) return contextTelephony;
        Class<?> contextType = Class.forName("android.content.Context");
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object context = null;
        Method application = optional(activityThread, "currentApplication");
        if (application != null) context = call(application, null);
        if (context == null) {
            Method current = optional(activityThread, "currentActivityThread");
            Object thread = current == null ? null : call(current, null);
            if (thread == null) {
                prepareShellLooper();
                Method systemMain = optional(activityThread, "systemMain");
                if (systemMain == null) throw new UnsupportedOperationException("Android shell Context initializer is unavailable");
                thread = call(systemMain, null);
            }
            if (thread != null) context = call(activityThread.getMethod("getSystemContext"), thread);
        }
        if (context == null || !contextType.isInstance(context))
            throw new IllegalStateException("Android shell Context is unavailable");
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager");
        Object manager = call(contextType.getMethod("getSystemService", String.class), context, "phone");
        if (manager == null) {
            try { manager = telephony.getConstructor(contextType).newInstance(context); }
            catch (InvocationTargetException error) { throwCause(error); }
        }
        if (manager == null || !telephony.isInstance(manager))
            throw new IllegalStateException("Context-backed TelephonyManager is unavailable");
        contextTelephony = manager;
        return manager;
    }

    private static void prepareShellLooper() throws Exception {
        Class<?> looper = Class.forName("android.os.Looper");
        if (call(looper.getMethod("myLooper"), null) != null) return;
        Object main = call(looper.getMethod("getMainLooper"), null);
        Method prepare = optional(looper, main == null ? "prepareMainLooper" : "prepare");
        if (prepare == null) throw new UnsupportedOperationException("Android shell Looper initializer is unavailable");
        call(prepare, null);
    }

    /** null means no usable current-boot shadow; empty string is a valid original slot value. */
    private String originalSimCountry(int subId) throws Exception {
        if (originalShadow == null || boot == null || boot.isEmpty()) return null;
        byte[] bytes;
        try {
            Controller.rejectLinks(originalShadow);
            if (!Files.isRegularFile(originalShadow, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(originalShadow) > 65536) return null;
            try (FileChannel input = FileChannel.open(originalShadow, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(65537);
                while (buffer.hasRemaining() && input.read(buffer) > 0) { }
                if (!buffer.hasRemaining()) return null;
                bytes = new byte[buffer.position()]; buffer.flip(); buffer.get(bytes);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException unsafe) { return null; }
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Object decoded;
        try { decoded = Json.decode(json); }
        catch (IllegalArgumentException invalid) { throw new IOException("Original SIM country shadow contains invalid JSON", invalid); }
        if (!(decoded instanceof Map)) throw new IOException("Original SIM country shadow must be a JSON object");
        Map<?, ?> shadow = (Map<?, ?>) decoded;
        Object shadowBoot = shadow.get("bootId");
        if (!(shadowBoot instanceof String)) throw new IOException("Original SIM country shadow has no valid boot identity");
        if (!boot.equals(shadowBoot)) return null;
        Object raw = shadow.get("countryList");
        if (!(raw instanceof String)) throw new IOException("Current-boot original SIM country list is missing or invalid");
        List<String> countries = countryTokens((String) raw);
        int slot = -1;
        List<Subscription> active = subscriptions();
        for (Subscription sub : active) {
            if (sub.subId != subId) continue;
            if (slot >= 0) throw new IOException("Original SIM country subscription mapping is ambiguous");
            slot = sub.slot;
        }
        if (slot < 0) throw new IOException("Original SIM country subscription is not active");
        for (Subscription sub : active) {
            if (sub.slot == slot && sub.subId != subId)
                throw new IOException("Original SIM country slot mapping is ambiguous");
        }
        if (slot >= countries.size()) throw new IOException("Current-boot original SIM country slot is absent from shadow");
        return countries.get(slot);
    }

    /** Mirrors the native comma/backslash property list format, including trailing empty slots. */
    private static List<String> countryTokens(String raw) throws IOException {
        List<String> out = new ArrayList<String>(); StringBuilder token = new StringBuilder(); boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (escaped) { token.append(value); escaped = false; }
            else if (value == '\\') escaped = true;
            else if (value == ',') { out.add(token.toString()); token.setLength(0); }
            else token.append(value);
        }
        if (escaped) token.append('\\');
        out.add(token.toString());
        if (out.size() > MAX_SLOTS) throw new IOException("Current-boot original SIM country list has too many slots");
        for (int i = 0; i < out.size(); i++) {
            String value = out.get(i);
            if (!value.isEmpty() && !value.matches("[A-Za-z]{2}"))
                throw new IOException("Current-boot original SIM country token is invalid");
            out.set(i, value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    @Override
    public int defaultSubId() throws Exception {
        Class<?> manager = Class.forName("android.telephony.SubscriptionManager");
        Method method = optional(manager, "getDefaultSubscriptionId");
        if (method == null) method = optional(manager, "getDefaultDataSubscriptionId");
        if (method == null) return -1;
        Object value = call(method, null);
        return value instanceof Number && ((Number) value).intValue() >= 0
                ? ((Number) value).intValue() : -1;
    }

    private static Object activeInformation(Remote sub) throws Exception {
        Method method = optional(sub.type, "getActiveSubscriptionInfoList",
                String.class, String.class, boolean.class);
        if (method != null) {
            return call(method, sub.target, null, null, true);
        }
        method = optional(sub.type, "getActiveSubscriptionInfoList", String.class, String.class);
        if (method != null) {
            return call(method, sub.target, null, null);
        }
        method = optional(sub.type, "getActiveSubscriptionInfoList", String.class);
        if (method != null) {
            return call(method, sub.target, (Object) null);
        }
        method = optional(sub.type, "getActiveSubscriptionInfoList");
        if (method != null) {
            return call(method, sub.target);
        }
        throw new UnsupportedOperationException("No supported active subscription information API");
    }

    private static Object subscriptionInformation(Remote sub, int subId) throws Exception {
        Method method = optional(sub.type, "getActiveSubscriptionInfo",
                int.class, String.class, String.class);
        if (method != null) {
            return call(method, sub.target, subId, null, null);
        }
        method = optional(sub.type, "getActiveSubscriptionInfo", int.class, String.class);
        if (method != null) {
            return call(method, sub.target, subId, null);
        }
        method = optional(sub.type, "getActiveSubscriptionInfo", int.class);
        return method == null ? null : call(method, sub.target, subId);
    }

    private static void enumerateStatic(Set<Integer> ids,
            Map<Integer, Integer> slots) throws Exception {
        Class<?> subscriptions = Class.forName("android.telephony.SubscriptionManager");
        Method getSubId = subscriptions.getMethod("getSubId", int.class);
        if (!Modifier.isStatic(getSubId.getModifiers())) {
            throw new UnsupportedOperationException("SubscriptionManager.getSubId is not static");
        }
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager");
        Object manager = defaultTelephonyManager();
        Method countMethod = optional(telephony, "getActiveModemCount");
        if (countMethod == null) {
            countMethod = optional(telephony, "getPhoneCount");
        }
        if (countMethod == null) {
            throw new UnsupportedOperationException("Cannot determine active modem count");
        }
        int count = ((Number) call(countMethod, manager)).intValue();
        if (count < 0) {
            throw new IllegalStateException("Invalid active modem count");
        }
        for (int slot = 0; slot < Math.min(count, MAX_SLOTS); slot++) {
            Object value = call(getSubId, null, slot);
            Set<Integer> found = new LinkedHashSet<Integer>();
            addIds(found, value);
            for (Integer id : found) {
                if (id.intValue() >= 0) {
                    ids.add(id);
                    slots.put(id, slot);
                }
            }
        }
    }

    private static Object defaultTelephonyManager() throws Exception {
        Class<?> telephony = Class.forName("android.telephony.TelephonyManager");
        Object manager = call(telephony.getMethod("getDefault"), null);
        if (manager == null) {
            throw new IllegalStateException("TelephonyManager is unavailable");
        }
        return manager;
    }

    private static void addIds(Set<Integer> destination, Object value) throws Exception {
        if (value == null) {
            return;
        }
        if (value instanceof int[]) {
            for (int id : (int[]) value) {
                if (id >= 0) {
                    destination.add(id);
                }
            }
            return;
        }
        if (value instanceof Integer) {
            int id = ((Integer) value).intValue();
            if (id >= 0) {
                destination.add(id);
            }
            return;
        }
        throw new IllegalStateException("Unexpected subscription ID result type");
    }

    private static String identity(Object info, int slot, int subId) {
        if (info != null) {
            try {
                Class<?> information = Class.forName("android.telephony.SubscriptionInfo");
                String iccid = string(call(information.getMethod("getIccId"), info)).trim();
                // Redacted identifiers must not be treated as a strong SIM identity.
                if (iccid.matches("[0-9]{18,22}") && !iccid.matches("0+") && !iccid.matches("9+")) {
                    byte[] hash = MessageDigest.getInstance("SHA-256")
                            .digest(iccid.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex = new StringBuilder(hash.length * 2);
                    for (byte item : hash) {
                        int number = item & 0xff;
                        hex.append(Character.forDigit(number >>> 4, 16));
                        hex.append(Character.forDigit(number & 15, 16));
                    }
                    return "iccid-sha256:" + hex.toString();
                }
            } catch (Exception ignored) {
                // Do not expose an ICCID in diagnostics. Controller sees the weak: marker.
            }
        }
        return "weak:slot=" + slot + ";subId=" + subId;
    }

    private static int intMethod(Object info, String name) throws Exception {
        Class<?> information = Class.forName("android.telephony.SubscriptionInfo");
        return ((Number) call(information.getMethod(name), info)).intValue();
    }

    private static boolean valid(int subId, int slot) {
        return subId >= 0 && slot >= 0 && slot < MAX_SLOTS;
    }

    private static void requireSubId(int subId) {
        if (subId < 0) {
            throw new IllegalArgumentException("Subscription ID must be non-negative");
        }
    }

    private static int sdk() throws Exception {
        return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
    }

    private static Remote requireService(String name, String interfaceName) throws Exception {
        Remote remote = service(name, interfaceName);
        if (remote == null) {
            throw new IllegalStateException("Android Binder service is unavailable: " + name);
        }
        return remote;
    }

    private static Remote service(String name, String interfaceName) throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Object binder = call(manager.getMethod("getService", String.class), null, name);
        if (binder == null) {
            return null;
        }
        Class<?> binderClass = Class.forName("android.os.IBinder");
        Class<?> stub = Class.forName(interfaceName + "$Stub");
        Class<?> type = Class.forName(interfaceName);
        Object target = call(stub.getMethod("asInterface", binderClass), null, binder);
        if (target == null) {
            throw new IllegalStateException("Android Binder interface is unavailable: " + name);
        }
        return new Remote(type, target);
    }

    private static Method optional(Class<?> type, String name, Class<?>... arguments) {
        try {
            return type.getMethod(name, arguments);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : (String) value;
    }

    private static Object call(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            throwCause(error);
            throw new AssertionError("Unreachable");
        }
    }

    private static void throwCause(InvocationTargetException wrapper) throws Exception {
        Throwable cause = wrapper.getCause();
        if (cause instanceof Exception) {
            throw (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new IllegalStateException("Android reflection call failed", cause);
    }

    private static final class Remote {
        final Class<?> type;
        final Object target;

        Remote(Class<?> type, Object target) {
            this.type = type;
            this.target = target;
        }
    }
}

