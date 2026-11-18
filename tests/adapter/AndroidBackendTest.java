package com.github.haaru.ksu;

import android.os.Build;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.ServiceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.app.ActivityThread;
import android.content.Context;
import android.os.Looper;
import com.android.internal.telephony.ICarrierConfigLoader;
import com.android.internal.telephony.ISub;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.io.IOException;

/**
 * JVM tests for the real reflection adapter against a deliberately private fake AIDL proxy.
 * These validate dispatch and failure semantics, not device/ROM compatibility.
 */
public final class AndroidBackendTest {
    private static final String COUNTRY = "sim_country_iso_override_string";
    private static final String FLAG = "carrier_name_override_bool";
    private static final String NAME = "carrier_name_string";
    private static final String READY = "carrier_config_applied_bool";
    private static final String ICCID = "89014103211118510720";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testPrivateProxyAndCurrentRead();
        testOriginalCarrierNames();
        testOriginalCarrierFallbacksAndFailures();
        testContextlessDefaultRegression();
        testStaticPhoneCountryFallback();
        testUninitializedStaticPhoneCache();
        testAuthoritativePhoneMappingFailures();
        testContextBackedCountryFallback();
        testExistingApplicationAndConstructorFallback();
        testContextErrorsAndGetterPermissions();
        testShadowBypassesUnavailableContext();
        testReadOnlySurfaceAndController();
        testPermissionExceptionUnwrapped();
        testSubscriptionsFilterAndIdentity();
        testWeakIdentifiers();
        testDynamicModemFallback();
        testMissingConfigAndNotReady();
        testOriginalSameBootShadow();
        testUidAndSdkGuards();
        System.out.println("AndroidBackend current-interface JVM tests passed (" + assertions
                + " assertions; no Android device compatibility claim)");
    }

    private static void testPrivateProxyAndCurrentRead() throws Exception {
        Fixture f = fixture();
        Object proxy = ICarrierConfigLoader.Stub.asInterface(ServiceManager.getService("carrier_config"));
        check(!Modifier.isPublic(proxy.getClass().getModifiers()), "fixture proxy must be private");
        Backend.Values values = f.backend.read(7);
        check("us".equals(values.country), "read country");
        check(values.carrierOverride && "T-Mobile".equals(values.carrierName), "read carrier fields");
        check(values.ready, "read loaded-state key");
        check(f.carrier.featureReads == 1 && f.carrier.legacyReads == 0, "prefer current feature API");
        check(f.carrier.lastPackage == null && f.carrier.lastFeature == null, "root read uses null attribution");
        check(f.carrier.lastReadSubId == 7, "read uses subscription ID");
        TelephonyManager.SIM_COUNTRIES.put(7, "jp");
        TelephonyManager.SIM_COUNTRIES.put(8, "gb");
        check("jp".equals(f.backend.simCountry(7)), "SIM country is scoped to requested subscription");
        SubscriptionManager.defaultSubscriptionId = 8;
        check(f.backend.defaultSubId() == 8, "default subscription is read without guessing first SIM");
    }

    private static void testOriginalCarrierNames() throws Exception {
        Fixture f = fixture();
        SubscriptionInfo first = new SubscriptionInfo(7, 0, ICCID);
        first.carrierName = new StringBuilder("中華電信");
        SubscriptionInfo second = new SubscriptionInfo(8, 1, "89014103211118510721");
        second.carrierName = "台灣大哥大";
        f.sub.information.add(first); f.sub.information.add(second);
        check("中華電信".equals(f.backend.originalCarrierName(7))
                        && "台灣大哥大".equals(f.backend.originalCarrierName(8)),
                "original labels remain scoped to each subscription and support CharSequence");
        check(f.sub.singleReads == 2 && f.sub.listReads == 0,
                "carrier label prefers the direct read-only active information API");
        check(f.sub.lastPackage == null && f.sub.lastFeature == null,
                "original carrier Binder read uses root null attribution");
        check("T-Mobile".equals(f.backend.read(7).carrierName),
                "original carrier label does not repurpose the configured override field");
        f.carrier.configuration.putBoolean(FLAG, false);
        f.carrier.configuration.putString(NAME, "");
        check(!f.backend.read(7).carrierOverride && "".equals(f.backend.read(7).carrierName)
                        && "中華電信".equals(f.backend.originalCarrierName(7)),
                "blank default CarrierConfig name is independent of original subscription label");
        first.carrierName = null;
        check("".equals(f.backend.originalCarrierName(7)), "null original label remains unknown");
        first.carrierName = "";
        check("".equals(f.backend.originalCarrierName(7)), "empty original label is not replaced by an override");
        check(TelephonyManager.staticCountryCalls == 0 && TelephonyManager.scopedCalls == 0
                        && ActivityThread.systemMainCalls == 0 && f.carrier.writes == 0,
                "original labels require no Context, overlaid operator getter, or setter");
    }

    private static void testOriginalCarrierFallbacksAndFailures() throws Exception {
        final Fixture f = fixture();
        SubscriptionInfo other = new SubscriptionInfo(8, 1, "89014103211118510721");
        other.carrierName = "other SIM";
        final SubscriptionInfo target = new SubscriptionInfo(7, 0, ICCID);
        target.carrierName = "actual SIM";
        f.sub.information.add(other); f.sub.information.add(target);
        f.sub.singleInformationFailure = new UnsupportedOperationException("direct API unavailable");
        check("actual SIM".equals(f.backend.originalCarrierName(7)),
                "unsupported direct API falls back to the list and matches the requested ID");
        check(f.sub.listReads == 1, "only the supported fallback list is used");
        f.sub.singleInformationFailure = null; f.sub.nullSingleInformation = true;
        check("actual SIM".equals(f.backend.originalCarrierName(7)),
                "null direct information can recover a valid active list label");
        check("".equals(f.backend.originalCarrierName(42)),
                "missing subscription never receives another SIM's label");
        f.sub.information.add(new SubscriptionInfo(7, 2, ICCID));
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(7); }
        }, "duplicate fallback IDs are not assigned an original carrier label");
        f.sub.information.remove(f.sub.information.size() - 1);
        f.sub.nullSingleInformation = false; f.sub.singleInformationOverride = other;
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(7); }
        }, "direct API returning a different SIM is rejected");
        f.sub.singleInformationOverride = new SubscriptionInfo(7, -1, ICCID);
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(7); }
        }, "inactive subscription info is not treated as an original active label");
        f.sub.singleInformationOverride = null;
        f.sub.singleInformationFailure = new SecurityException("original label denied");
        int listReads = f.sub.listReads;
        Exception denied = expect(SecurityException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(7); }
        }, "permission denial stays explicit instead of silently trying other sources");
        check("original label denied".equals(denied.getMessage()) && f.sub.listReads == listReads,
                "original carrier read preserves the platform permission cause");
        f.sub.singleInformationFailure = null;
        target.carrierNameFailure = new IllegalStateException("label unavailable");
        Exception label = expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(7); }
        }, "carrier label getter failure remains explicit");
        check("label unavailable".equals(label.getMessage()), "label reflection unwraps the actual failure");
        target.carrierNameFailure = null;
        f.sub.singleInformationFailure = new UnsupportedOperationException("direct missing");
        f.sub.informationFailure = new UnsupportedOperationException("all info APIs missing");
        check("".equals(f.backend.originalCarrierName(7)), "unsupported information APIs return unknown label");
        f.sub.informationFailure = null; f.sub.nullInformation = true;
        check("".equals(f.backend.originalCarrierName(7)), "unready list leaves the original label unknown");
        ServiceManager.clear();
        check("".equals(f.backend.originalCarrierName(7)), "missing ISub service returns unknown without guessing");
        expect(IllegalArgumentException.class, new Action() {
            @Override public void run() throws Exception { f.backend.originalCarrierName(-1); }
        }, "original carrier rejects invalid subscription IDs");
        check(f.carrier.writes == 0, "every carrier-label failure and fallback remains read-only");
    }

    private static void testContextlessDefaultRegression() throws Exception {
        final Fixture f = fixture();
        Exception oldFailure = expect(NullPointerException.class, new Action() {
            @Override public void run() { TelephonyManager.getDefault().createForSubscriptionId(7); }
        }, "old getDefault/scoped path reproduces the real Android null Context failure");
        check(oldFailure.getMessage().contains("Context.getApplicationContext()"), "fixture reproduces the reported platform constructor access");
        TelephonyManager.reset(); Context.resetCounters();
        mapPhone(f, 7, 0);
        TelephonyManager.SIM_COUNTRIES.put(7, "tw");
        TelephonyManager.SIM_COUNTRIES.put(8, "jp");
        check("tw".equals(f.backend.simCountry(7)) && "jp".equals(f.backend.simCountry(8)), "contextless app_process reads both subscriptions through the static device getter");
        check(TelephonyManager.staticCountryCalls == 2 && TelephonyManager.defaultCalls == 0 && TelephonyManager.scopedCalls == 0,
                "successful static reads never use the null Context singleton");
        check(ActivityThread.systemMainCalls == 0 && Context.phoneServiceReads == 0 && Looper.prepareMainCalls == 0,
                "common country reads do not initialize ActivityThread or the system Context");
        TelephonyManager.SIM_COUNTRIES.put(7, "");
        check("".equals(f.backend.simCountry(7)), "a genuinely blank system country remains blank without inventing an ISO");
    }

    private static void testStaticPhoneCountryFallback() throws Exception {
        Fixture f = fixture();
        TelephonyManager.staticCountryUnsupported = true;
        mapPhone(f, 7, 3);
        TelephonyManager.SIM_COUNTRIES.put(7, "fr");
        check("fr".equals(f.backend.simCountry(7)), "phone-only static getter uses the device subscription-to-phone mapping");
        check(TelephonyManager.staticPhoneCalls == 1 && TelephonyManager.defaultCalls == 0 && ActivityThread.systemMainCalls == 0,
                "phone form does not create a contextless scoped manager");
        check(f.carrier.writes == 0, "context-free getter fallback makes no platform writes");
    }

    private static void testUninitializedStaticPhoneCache() throws Exception {
        Fixture f = fixture(); mapPhone(f, 1, 0); mapPhone(f, 2, 1);
        SubscriptionManager.invalidPhoneCache = true;
        TelephonyManager.SIM_COUNTRIES.put(1, "cn"); TelephonyManager.SIM_COUNTRIES.put(2, "gb");
        check(SubscriptionManager.getPhoneId(1) == -1 && SubscriptionManager.getSlotIndex(2) == -1,
                "fixture reproduces the real app_process static cache failure");
        check("".equals(TelephonyManager.getSimCountryIso(1)), "old static subscription country path returns empty despite a real SIM");
        check("cn".equals(f.backend.simCountry(1)) && "gb".equals(f.backend.simCountry(2)),
                "fresh direct ISub phone mappings recover cn and gb independently of the broken static cache");
        check(f.sub.phoneReads == 2 && TelephonyManager.staticPhoneCalls == 2,
                "phone-index getter uses the authoritative direct ISub API");
        check(ActivityThread.systemMainCalls == 0 && TelephonyManager.defaultCalls == 0 && f.carrier.writes == 0,
                "mapping recovery requires no Context initialization or telephony setter");
        f.sub.phoneFailure = new UnsupportedOperationException("phone API unavailable");
        check("gb".equals(f.backend.simCountry(2)), "direct getSlotIndex recovers the logical phone when getPhoneId is unsupported");
        f.sub.slotFailure = new UnsupportedOperationException("slot API unavailable");
        check("cn".equals(f.backend.simCountry(1)), "validated active SubscriptionInfo logical slot supports both unavailable mapping methods");
        TelephonyManager.SIM_COUNTRIES.put(2, "");
        check("".equals(f.backend.simCountry(2)), "a genuinely empty country is accepted only after authoritative phone mapping");
    }

    private static void testAuthoritativePhoneMappingFailures() throws Exception {
        final Fixture f = fixture(); mapPhone(f, 7, 0); mapPhone(f, 8, 1);
        SubscriptionManager.invalidPhoneCache = true;
        TelephonyManager.SIM_COUNTRIES.put(7, "tw"); TelephonyManager.SIM_COUNTRIES.put(8, "jp");
        f.sub.information.add(new SubscriptionInfo(7, 2, ICCID));
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(7); }
        }, "raw duplicate active IDs cannot be coalesced into a country mapping");
        f.sub.information.remove(f.sub.information.size() - 1);
        f.sub.information.add(new SubscriptionInfo(9, 0, ICCID));
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(7); }
        }, "two active subscriptions sharing a logical slot are rejected");
        f.sub.information.remove(f.sub.information.size() - 1);
        f.sub.phones.put(7, 1);
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(7); }
        }, "direct phone mapping must agree with the active logical subscription slot");
        f.sub.phones.clear();
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(42); }
        }, "inactive subscription never borrows another phone's country");
        f.sub.phoneFailure = new SecurityException("phone mapping denied");
        Exception denied = expect(SecurityException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(7); }
        }, "direct mapping permission failure remains explicit");
        check("phone mapping denied".equals(denied.getMessage()) && ActivityThread.systemMainCalls == 0,
                "permission failure is not hidden by a Context fallback");
        check(TelephonyManager.staticPhoneCalls == 0, "no guessed or ambiguous phone index reaches a country getter");
    }

    private static void testContextBackedCountryFallback() throws Exception {
        Fixture f = fixture();
        TelephonyManager.staticCountryUnsupported = TelephonyManager.staticPhoneUnsupported = true;
        mapPhone(f, 7, 0); mapPhone(f, 8, 1);
        TelephonyManager.SIM_COUNTRIES.put(7, "tw"); TelephonyManager.SIM_COUNTRIES.put(8, "gb");
        check(ActivityThread.currentActivityThread() == null && Looper.myLooper() == null, "app_process fixture begins without Android application or Looper");
        check("tw".equals(f.backend.simCountry(7)) && "gb".equals(f.backend.simCountry(8)), "ROM without static getters uses a valid Context-backed scoped manager");
        check(ActivityThread.systemMainCalls == 1 && Looper.prepareMainCalls == 1,
                "fallback initializes one system Context only after preparing its Looper");
        check(Context.phoneServiceReads == 1 && TelephonyManager.scopedCalls == 2,
                "the Context-backed base manager is reused while each read still uses its own subId");
        check(TelephonyManager.defaultCalls == 0 && Context.applicationReads >= 3,
                "all scoped constructors receive an actual Context rather than getDefault");
        check(f.carrier.writes == 0, "shell Context creation never submits a telephony setter");
    }

    private static void testExistingApplicationAndConstructorFallback() throws Exception {
        Fixture f = fixture();
        TelephonyManager.staticCountryUnsupported = TelephonyManager.staticPhoneUnsupported = true;
        mapPhone(f, 7, 0); TelephonyManager.SIM_COUNTRIES.put(7, "sg");
        ActivityThread.application = new Context();
        check("sg".equals(f.backend.simCountry(7)), "existing application Context is preferred over creating a system thread");
        check(ActivityThread.systemMainCalls == 0 && Looper.prepareMainCalls == 0,
                "existing Context does not reinitialize ActivityThread or the main Looper");
        f = fixture();
        TelephonyManager.staticCountryUnsupported = TelephonyManager.staticPhoneUnsupported = true;
        mapPhone(f, 7, 0); TelephonyManager.SIM_COUNTRIES.put(7, "de");
        ActivityThread.systemContext.phoneServiceAvailable = false;
        check("de".equals(f.backend.simCountry(7)), "public Context constructor supports a system Context with no registered phone service instance");
        check(Context.phoneServiceReads == 1 && TelephonyManager.defaultCalls == 0,
                "constructor fallback still never uses the contextless singleton");
    }

    private static void testContextErrorsAndGetterPermissions() throws Exception {
        Fixture f = fixture();
        TelephonyManager.staticCountryFailure = new SecurityException("country read denied");
        final AndroidBackend denied = f.backend;
        Exception permission = expect(SecurityException.class, new Action() {
            @Override public void run() throws Exception { denied.simCountry(7); }
        }, "real getter permission errors are unwrapped rather than swallowed by fallback");
        check("country read denied".equals(permission.getMessage()) && ActivityThread.systemMainCalls == 0,
                "permission failure is preserved and does not initialize a bypass Context");
        f = fixture();
        TelephonyManager.staticCountryUnsupported = TelephonyManager.staticPhoneUnsupported = true;
        mapPhone(f, 7, 0); ActivityThread.nullSystemContext = true;
        final AndroidBackend missing = f.backend;
        Exception absent = expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { missing.simCountry(7); }
        }, "unavailable shell Context reports an actionable failure instead of a null dereference");
        check(absent.getMessage().contains("Context") && TelephonyManager.scopedCalls == 0,
                "no scoped manager is constructed when Context initialization failed");
        f = fixture();
        TelephonyManager.staticCountryUnsupported = TelephonyManager.staticPhoneUnsupported = true;
        mapPhone(f, 7, 0);
        ActivityThread.systemContext.applicationFailure = new IllegalStateException("fixture Context attribution unavailable");
        final AndroidBackend invalid = f.backend;
        Exception initializer = expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { invalid.simCountry(7); }
        }, "Context factory failure remains its original exception");
        check(initializer.getMessage().equals("fixture Context attribution unavailable"), "Context factory cause is not hidden by an invocation wrapper");
    }

    private static void testShadowBypassesUnavailableContext() throws Exception {
        Fixture f = fixture();
        f.sub.activeIds = new int[] {7}; f.sub.information.add(new SubscriptionInfo(7, 0, ICCID));
        TelephonyManager.staticCountryFailure = new SecurityException("static path must not be used");
        ActivityThread.initializationFailure = new IllegalStateException("Context path must not be used");
        Path path = Files.createTempDirectory("haaru-current-boot-country-").resolve("global-shadow.json");
        Files.write(path, "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw\"}".getBytes(StandardCharsets.UTF_8));
        AndroidBackend backend = new AndroidBackend(path, "fixture-boot");
        check("tw".equals(backend.simCountry(7)), "verified original shadow is preferred to every system getter and Context route");
        check(TelephonyManager.staticCountryCalls == 0 && ActivityThread.systemMainCalls == 0,
                "engine-original recovery does not accidentally read overlaid stock properties");
    }

    private static void testReadOnlySurfaceAndController() throws Exception {
        Fixture f = fixture();
        f.carrier.configuration.putBoolean("carrier_volte_available_bool", true);
        f.sub.activeIds = new int[] {7};
        f.sub.information.add(new SubscriptionInfo(7, 0, ICCID));
        TelephonyManager.SIM_COUNTRIES.put(7, "tw");
        Path dir = Files.createTempDirectory("haaru-adapter-v4-").resolve("data");
        Controller controller = new Controller(dir, f.backend, "fixture-boot", 0);
        controller.run("set", "0", "JP", "4e5454");
        controller.run("set", "0", "US", "-");
        controller.run("refresh"); controller.run("reset", "0"); controller.run("enable");
        controller.run("disable"); controller.run("forget", "0"); controller.run("clear");
        controller.run("saveprefs", "7b7d");
        java.util.Map<String, Object> runtime = controller.run("runtime");
        check(Boolean.TRUE.equals(runtime.get("global")) && ((Number) runtime.get("protocol")).intValue() == 2,
                "global protocol remains published when plans are empty");
        check(!runtime.containsKey("targetPackages"), "global protocol is independent of caller application package");
        check(f.carrier.writes == 0, "all controller mutations perform zero platform setters");
        check("us".equals(f.backend.read(7).country) && "T-Mobile".equals(f.backend.read(7).carrierName),
                "actual platform carrier values are unchanged");
        check("tw".equals(f.backend.simCountry(7)), "actual SIM country is unchanged");
        check(f.carrier.configuration.getBoolean("carrier_volte_available_bool"), "IMS key unchanged");
        for (java.lang.reflect.Method method : AndroidBackend.class.getDeclaredMethods())
            check(!"write".equals(method.getName()), "adapter exposes no write method");
    }

    private static void testPermissionExceptionUnwrapped() throws Exception {
        Fixture f = fixture();
        f.carrier.readFailure = new SecurityException("fixture read denied");
        Exception read = expect(SecurityException.class, new Action() {
            @Override public void run() throws Exception { f.backend.read(7); }
        }, "read permission denial is not a reflection wrapper");
        check("fixture read denied".equals(read.getMessage()), "read denial message preserved");
        check(f.carrier.writes == 0, "failed read did not attempt a setter");
    }

    private static void testSubscriptionsFilterAndIdentity() throws Exception {
        Fixture f = fixture();
        f.sub.activeIds = new int[] { 7, -1, 7, 8, 9, 10 };
        f.sub.information.add(new SubscriptionInfo(7, 0, ICCID));
        f.sub.information.add(new SubscriptionInfo(-1, 0, ICCID));
        f.sub.slots.put(8, 8);
        f.sub.slots.put(9, -1);
        f.sub.slots.put(10, 7);
        List<Backend.Subscription> values = f.backend.subscriptions();
        check(values.size() == 2, "duplicate IDs and invalid IDs/slots filtered");
        check(values.get(0).subId == 7 && values.get(0).slot == 0, "valid active ID mapped to slot");
        check(values.get(1).subId == 10 && values.get(1).slot == 7, "supported upper slot boundary");
        check(!f.sub.visibleOnly, "enumeration includes active hidden/opportunistic IDs");
        check(f.sub.lastPackage == null && f.sub.lastFeature == null, "root info uses null attribution");
        check(values.get(0).identity.equals("iccid-sha256:" + sha256(ICCID)), "strong identity hashes ICCID");
        check(!values.get(0).identity.contains(ICCID), "raw ICCID not returned");
        check(values.get(1).identity.startsWith("weak:"), "missing ICCID marked weak");
    }

    private static void testWeakIdentifiers() throws Exception {
        String[] weak = { "", "****0720", "12345678901234567", "00000000000000000000",
                "99999999999999999999" };
        for (String value : weak) {
            Fixture f = fixture();
            f.sub.activeIds = new int[] { 7 };
            f.sub.information.add(new SubscriptionInfo(7, 0, value));
            List<Backend.Subscription> result = f.backend.subscriptions();
            check(result.get(0).identity.startsWith("weak:"), "unreliable ICCID stays weak");
        }
    }

    private static void testDynamicModemFallback() throws Exception {
        Fixture f = fixture();
        ServiceManager.clear();
        ServiceManager.register("carrier_config", ICarrierConfigLoader.Stub.newBinder(f.carrier));
        TelephonyManager.activeModems = 4;
        SubscriptionManager.SLOT_IDS.put(3, 21);
        List<Backend.Subscription> result = f.backend.subscriptions();
        check(result.size() == 1 && result.get(0).slot == 3 && result.get(0).subId == 21,
                "static fallback scans actual modem count, not fixed slots 0 and 1");
        check(result.get(0).identity.startsWith("weak:"), "static fallback does not invent strong identity");
    }

    private static void testMissingConfigAndNotReady() throws Exception {
        Fixture f = fixture();
        f.carrier.configuration.putBoolean(READY, false);
        check(!f.backend.read(7).ready, "unloaded config is distinguishable from ready");
        f.carrier.returnNull = true;
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.read(7); }
        }, "null configuration is an error");
        ServiceManager.clear();
        expect(IllegalStateException.class, new Action() {
            @Override public void run() throws Exception { f.backend.read(7); }
        }, "missing Binder service is an error");
        expect(IllegalArgumentException.class, new Action() {
            @Override public void run() throws Exception { f.backend.simCountry(-1); }
        }, "invalid SIM country ID rejected");
    }

    private static void testUidAndSdkGuards() throws Exception {
        fixture();
        Process.uid = 2000;
        try {
            expect(SecurityException.class, new Action() {
                @Override public void run() throws Exception { AndroidBackend.checkRootAndSdk(); }
            }, "shell UID is not root");
            expect(SecurityException.class, new Action() {
                @Override public void run() throws Exception { new AndroidBackend(); }
            }, "constructor also checks root UID");
        } finally {
            Process.uid = 0;
        }
        Build.VERSION.SDK_INT = 28;
        try {
            expect(UnsupportedOperationException.class, new Action() {
                @Override public void run() throws Exception { AndroidBackend.checkRootAndSdk(); }
            }, "pre-Android-10 API unsupported");
        } finally {
            Build.VERSION.SDK_INT = 36;
        }
        AndroidBackend.checkRootAndSdk();
        assertions++;
    }

    private static void testOriginalSameBootShadow() throws Exception {
        Fixture f = fixture();
        f.sub.activeIds = new int[] {7, 8, 9};
        f.sub.information.add(new SubscriptionInfo(7, 0, ICCID));
        f.sub.information.add(new SubscriptionInfo(8, 1, "89014103211118510721"));
        f.sub.information.add(new SubscriptionInfo(9, 2, "89014103211118510722"));
        TelephonyManager.SIM_COUNTRIES.put(7, "us");
        TelephonyManager.SIM_COUNTRIES.put(8, "de");
        TelephonyManager.SIM_COUNTRIES.put(9, "gb");
        Path dir = Files.createTempDirectory("haaru-original-country-shadow-");
        final Path path = dir.resolve("global-shadow.json");
        final AndroidBackend backend = new AndroidBackend(path, "fixture-boot");
        check("us".equals(backend.simCountry(7)), "missing shadow falls back to original reflection getter");
        Files.write(path, "{\"bootId\":\"fixture-boot\",\"countryList\":\"TW,jP,\"}".getBytes(StandardCharsets.UTF_8));
        check("tw".equals(backend.simCountry(7)) && "jp".equals(backend.simCountry(8)), "current-boot shadow recovers original dual-SIM values instead of overlaid getters");
        check("".equals(backend.simCountry(9)), "trailing empty slot is an original blank country rather than overlay fallback");
        check("us".equals(f.backend.simCountry(7)), "default constructor keeps existing reflection-only behavior");
        Files.write(path, "{\"bootId\":\"old-boot\",\"countryList\":123}".getBytes(StandardCharsets.UTF_8));
        check("us".equals(backend.simCountry(7)), "stale shadow falls back before inspecting stale country tokens");
        for (String invalid : new String[] {
                "{\"bootId\":\"fixture-boot\"}", "{\"bootId\":\"fixture-boot\",\"countryList\":null}",
                "{\"bootId\":\"fixture-boot\",\"countryList\":123}", "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,USA\"}",
                "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,j1\"}", "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,ＪＰ\"}",
                "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,jp,,,,,,,\"}",
                "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,\\\\,\"}",
                "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,jp\\\\\"}", "{}", "[]", "{malformed}"}) {
            Files.write(path, invalid.getBytes(StandardCharsets.UTF_8));
            expect(IOException.class, new Action() {
                @Override public void run() throws Exception { backend.simCountry(7); }
            }, "malformed original shadow cannot be mistaken for a configured country baseline");
        }
        Files.write(path, new byte[] {(byte) 0xc0, (byte) 0xaf});
        expect(IOException.class, new Action() {
            @Override public void run() throws Exception { backend.simCountry(7); }
        }, "invalid shadow UTF-8 fails closed");
        Files.write(path, "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw\"}".getBytes(StandardCharsets.UTF_8));
        expect(IOException.class, new Action() {
            @Override public void run() throws Exception { backend.simCountry(8); }
        }, "missing current-boot slot does not use overlaid getter");
        f.sub.information.add(new SubscriptionInfo(11, 1, "89014103211118510724"));
        Files.write(path, "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,jp,\"}".getBytes(StandardCharsets.UTF_8));
        expect(IOException.class, new Action() {
            @Override public void run() throws Exception { backend.simCountry(8); }
        }, "ambiguous SIM slot cannot be assigned an original country");
        f.sub.information.remove(f.sub.information.size() - 1);
        expect(IOException.class, new Action() {
            @Override public void run() throws Exception { backend.simCountry(42); }
        }, "unknown subscription cannot be assigned a country from another SIM slot");
        Files.write(path, new byte[65537]);
        check("us".equals(backend.simCountry(7)), "oversized unsafe shadow is ignored");
        Files.delete(path); Files.createDirectory(path);
        check("us".equals(backend.simCountry(7)), "nonregular shadow is ignored");
        Files.delete(path);
        Path outside = Files.createTempDirectory("haaru-shadow-outside-");
        Files.write(outside.resolve("global-shadow.json"), "{\"bootId\":\"fixture-boot\",\"countryList\":\"tw,jp,\"}".getBytes(StandardCharsets.UTF_8));
        Path link = dir.resolve("unsafe");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            java.lang.Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
            try (java.io.InputStream in = process.getInputStream()) { while (in.read() != -1) { } }
            if (process.waitFor() != 0) throw new AssertionError("Could not create shadow junction fixture");
        }
        AndroidBackend redirected = new AndroidBackend(link.resolve("global-shadow.json"), "fixture-boot");
        check("us".equals(redirected.simCountry(7)), "symlink/junction shadow cannot override the reflection baseline");
        check(Files.isRegularFile(outside.resolve("global-shadow.json"), LinkOption.NOFOLLOW_LINKS), "reading an unsafe shadow makes no external mutations");
        check(f.carrier.writes == 0 && "us".equals(TelephonyManager.SIM_COUNTRIES.get(7)), "shadow recovery makes no platform setter or fixture property change");
    }

    private static Fixture fixture() throws Exception {
        Process.uid = 0;
        Build.VERSION.SDK_INT = 36;
        ServiceManager.clear();
        SubscriptionManager.SLOT_IDS.clear();
        SubscriptionManager.defaultSubscriptionId = -1;
        SubscriptionManager.invalidPhoneCache = false;
        TelephonyManager.reset();
        ActivityThread.reset(); Looper.reset(); Context.resetCounters();
        ICarrierConfigLoader.State carrier = new ICarrierConfigLoader.State();
        carrier.configuration.putString(COUNTRY, "us");
        carrier.configuration.putBoolean(FLAG, true);
        carrier.configuration.putString(NAME, "T-Mobile");
        carrier.configuration.putBoolean(READY, true);
        ISub.State sub = new ISub.State();
        ServiceManager.register("carrier_config", ICarrierConfigLoader.Stub.newBinder(carrier));
        ServiceManager.register("isub", ISub.Stub.newBinder(sub));
        return new Fixture(new AndroidBackend(), carrier, sub);
    }

    private static void mapPhone(Fixture fixture, int id, int slot) {
        SubscriptionManager.SLOT_IDS.put(slot, id);
        fixture.sub.information.add(new SubscriptionInfo(id, slot, ICCID));
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : bytes) {
            int number = item & 255;
            output.append(Character.forDigit(number >>> 4, 16));
            output.append(Character.forDigit(number & 15, 16));
        }
        return output.toString();
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static Exception expect(Class<? extends Exception> type, Action action, String description)
            throws Exception {
        assertions++;
        try {
            action.run();
        } catch (Exception error) {
            if (!type.isInstance(error)) throw new AssertionError(description + ": " + error, error);
            return error;
        }
        throw new AssertionError(description + ": no exception");
    }

    private interface Action { void run() throws Exception; }
    private static final class Fixture {
        final AndroidBackend backend;
        final ICarrierConfigLoader.State carrier;
        final ISub.State sub;
        Fixture(AndroidBackend b, ICarrierConfigLoader.State c, ISub.State s) {
            backend = b; carrier = c; sub = s;
        }
    }
}

