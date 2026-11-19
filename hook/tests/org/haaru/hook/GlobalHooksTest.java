/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import com.android.phone.CarrierConfigLoader;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host JVM coverage only: no ART, JNI, Binder transport or device compatibility claim. */
public final class GlobalHooksTest {
    private static final String COUNTRY = "sim_country_iso_override_string";
    private static final String NAME = "carrier_name_string";
    private static final String FLAG = "carrier_name_override_bool";
    private static int checks;
    public static final class UnsupportedConfig {
        public boolean isEmpty() { return false; }
    }
    public static final class UnsupportedService {
        public final Object result = new UnsupportedConfig();
        public Object getConfig(int id) { return result; }
    }
    public static final class AttachFixture {
        int calls;
        Throwable failure;
        public Object attach(Object context) throws Throwable {
            calls++;
            if (failure != null) throw failure;
            return context;
        }
    }
    private static final class FakeScope implements GlobalHooks.OriginalScope {
        int depth, entries, exits;
        Throwable enterFailure, exitFailure;
        Runnable afterEnter;
        @Override public void enter(boolean active) throws Throwable {
            if (active) {
                if (enterFailure != null) throw enterFailure;
                entries++; depth++;
                if (afterEnter != null) afterEnter.run();
            } else {
                exits++; depth--;
                if (exitFailure != null) throw exitFailure;
            }
        }
    }
    private static final class FakeCaller implements GlobalHooks.Caller {
        int uid = 10234, calls;
        boolean trusted;
        Throwable failure;
        @Override public GlobalHooks.Identity capture() throws Throwable {
            calls++; if (failure != null) throw failure; return new GlobalHooks.Identity(uid, trusted);
        }
    }
    private static final class FakeInstaller implements GlobalHooks.Installer {
        final Map<String, GlobalHooks.Callback> installed = new LinkedHashMap<String, GlobalHooks.Callback>();
        int attempts, failAt = -1;
        Method failedTarget;
        @Override public boolean install(Method target, GlobalHooks.Callback callback) {
            attempts++;
            if (attempts == failAt) { failedTarget = target; return false; }
            callback.backup = target;
            installed.put(target.getName(), callback);
            return true;
        }
        GlobalHooks.Callback get(String name) { return installed.get(name); }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static Object invoke(GlobalHooks.Callback hook, Object... arguments) throws Throwable { return hook.callback(arguments); }
    private static void configure() {
        GlobalHooks.configure(new int[] {10, 20}, new int[] {0, 1}, new String[] {"US", "jp"}, new String[] {"US Carrier", "JP Carrier"});
    }
    private static FakeInstaller initialize(FakeCaller caller) throws Throwable {
        GlobalHooks.resetForTests();
        FakeInstaller installer = new FakeInstaller();
        GlobalHooks.installer = installer; GlobalHooks.caller = caller;
        int count = GlobalHooks.initialize(new Context(GlobalHooksTest.class.getClassLoader()));
        equal(13, count, "every required Android 16 service exit is installed");
        equal(13, installer.installed.size(), "three config and ten ISub hooks");
        equal(13, GlobalHooks.initialize(new Context(GlobalHooksTest.class.getClassLoader())), "successful initialisation is idempotent");
        equal(13, installer.attempts, "idempotent initialisation avoids double hooking");
        configure(); return installer;
    }
    private static PersistableBundle bundle() {
        PersistableBundle result = new PersistableBundle();
        result.values.put(COUNTRY, "tw"); result.values.put(NAME, "Original"); result.values.put(FLAG, false);
        result.values.put("carrier_volte_available_bool", true); return result;
    }
    private static SubscriptionInfo info(int id, int slot) {
        return new SubscriptionInfo(id, slot, "tw", "Original Carrier", "User Name", 4, "redacted-iccid", true, "466", "01");
    }
    private static void configsAndIdentity(FakeCaller caller, FakeInstaller installer) throws Throwable {
        CarrierConfigLoader service = new CarrierConfigLoader(); service.result = bundle();
        GlobalHooks.Callback config = installer.get("getConfigForSubIdWithFeature");
        Map<String, Object> baseline = new LinkedHashMap<String, Object>(service.result.values);
        PersistableBundle copy = (PersistableBundle) invoke(config, service, 10, "package", "feature");
        check(copy != service.result, "external configuration is copied");
        equal("us", copy.values.get(COUNTRY), "country normalised");
        equal("US Carrier", copy.values.get(NAME), "first SIM name copied");
        equal(true, copy.values.get(FLAG), "carrier preference on returned copy");
        equal(true, copy.values.get("carrier_volte_available_bool"), "unrelated IMS config retained");
        equal(baseline, service.result.values, "shared service bundle remains unchanged");
        equal(0, service.result.puts, "no put performed on shared config");
        equal(1, service.calls, "original permission-checking method always called");
        check(Arrays.equals(new Object[] {10, "package", "feature"}, service.forwarded), "arguments forwarded unchanged");
        caller.uid = 0; check(invoke(config, service, 10, "package", "feature") == service.result, "root sees original");
        caller.uid = 1001; check(invoke(config, service, 10, "package", "feature") == service.result, "phone UID sees original");
        caller.uid = 10234; caller.trusted = true;
        check(invoke(config, service, 10, "package", "feature") == service.result, "trusted internal call sees original");
        caller.trusted = false; caller.failure = new IllegalStateException("identity unavailable");
        check(invoke(config, service, 10, "package", "feature") == service.result, "identity failure preserves original");
        caller.failure = null; caller.uid = 10234;
        service.onCall = new Runnable() { @Override public void run() { caller.uid = 1001; caller.trusted = true; } };
        PersistableBundle entryCopy = (PersistableBundle) invoke(config, service, 20, "package", "feature");
        equal("JP Carrier", entryCopy.values.get(NAME), "eligibility uses identity captured before clearCallingIdentity");
        caller.uid = 10234; caller.trusted = false; service.onCall = null;
        check(invoke(config, service, 30, "package", "feature") == service.result, "unconfigured subId preserves original");
        PersistableBundle original = service.result; service.result = new PersistableBundle();
        check(invoke(config, service, 10, "package", "feature") == service.result, "permission-denied empty bundle stays empty");
        service.result = null; check(invoke(config, service, 10, "package", "feature") == null, "null remains null");
        service.result = original;
        GlobalHooks.Callback subset = installer.get("getConfigSubsetForSubIdWithFeature");
        PersistableBundle subsetCopy = (PersistableBundle) invoke(subset, service, 10, "package", "feature", new String[] {NAME});
        equal("US Carrier", subsetCopy.values.get(NAME), "requested subset name changed");
        equal("tw", subsetCopy.values.get(COUNTRY), "unrequested country not changed");
        equal(false, subsetCopy.values.get(FLAG), "unrequested flag not changed");
        equal(1, subsetCopy.puts, "subset changes exactly requested key");
        check(invoke(subset, service, 10, "package", "feature", new String[] {"carrier_volte_available_bool"}) == original,
                "unrelated subset returns original without adding keys");
        GlobalHooks.configure(new int[] {10}, new int[] {0}, new String[] {"US"}, new String[] {""});
        PersistableBundle country = (PersistableBundle) invoke(config, service, 10, "package", "feature");
        equal("us", country.values.get(COUNTRY), "country-only changes ISO");
        equal("Original", country.values.get(NAME), "country-only preserves name");
        equal(false, country.values.get(FLAG), "country-only preserves flag");
        configure();
    }
    private static void infoCopies(FakeInstaller installer) throws Throwable {
        SubscriptionManagerService service = new SubscriptionManagerService(); service.info = info(10, 0);
        GlobalHooks.Callback single = installer.get("getActiveSubscriptionInfo");
        SubscriptionInfo copy = (SubscriptionInfo) invoke(single, service, 10, "package", "feature");
        check(copy != service.info, "SubscriptionInfo copy differs from shared object");
        equal("us", copy.country, "copy country"); equal("US Carrier", copy.carrier, "copy carrier"); equal("US Carrier", copy.display, "copy display");
        equal(10, copy.id, "subscription identity retained"); equal(0, copy.slot, "slot retained");
        equal(4, copy.nameSource, "source priority retained"); equal("redacted-iccid", copy.iccid, "redacted identifier retained");
        equal(true, copy.embedded, "embedded flag retained"); equal("466", copy.mcc, "MCC retained"); equal("01", copy.mnc, "leading-zero MNC retained");
        equal("tw", service.info.country, "original country intact"); equal("User Name", service.info.display, "original user name intact");
        service.info = info(20, 1);
        SubscriptionInfo returnedId = (SubscriptionInfo) invoke(single, service, 10, "package", "feature");
        equal("jp", returnedId.country, "copy uses returned info identity, not first integer argument");
        service.info = info(30, 2); check(invoke(single, service, 30, "package", "feature") == service.info, "unmatched info returned directly");
        SubscriptionInfo first = info(10, 0), second = info(20, 1), other = info(30, 2);
        service.list = Collections.unmodifiableList(Arrays.asList(first, second, other, null));
        GlobalHooks.Callback list = installer.get("getAllSubInfoList");
        List<?> copied = (List<?>) invoke(list, service, "package", "feature");
        check(copied != service.list, "list copied without mutating immutable source");
        equal("us", ((SubscriptionInfo) copied.get(0)).country, "first SIM independent");
        equal("jp", ((SubscriptionInfo) copied.get(1)).country, "second SIM independent");
        check(copied.get(2) == other && copied.get(3) == null, "unmatched and null entries preserved");
        check(service.list.get(0) == first && service.list.get(1) == second, "source list entries preserved");
        equal("tw", first.country, "first shared info never mutated");
        service.list = Collections.emptyList(); check(invoke(list, service, "package", "feature") == service.list, "empty list stays original");
        service.list = null; check(invoke(list, service, "package", "feature") == null, "null list stays null");
        GlobalHooks.configure(new int[] {10}, new int[] {0}, new String[] {""}, new String[] {"Name Only"});
        service.info = first;
        SubscriptionInfo nameOnly = (SubscriptionInfo) invoke(single, service, 10, "package", "feature");
        equal("tw", nameOnly.country, "name-only keeps actual ISO"); equal("Name Only", nameOnly.display, "name-only copied display");
        configure();
    }
    private static void failuresAndNested(FakeCaller caller, FakeInstaller installer) throws Throwable {
        CarrierConfigLoader service = new CarrierConfigLoader(); service.result = bundle();
        GlobalHooks.Callback config = installer.get("getConfigForSubId");
        for (Throwable failure : new Throwable[] {new SecurityException("denied"), new AssertionError("original error")}) {
            service.failure = failure;
            try { invoke(config, service, 10, "package"); throw new AssertionError("original Throwable should propagate"); }
            catch (Throwable actual) { check(actual == failure, "original Throwable is unwrapped with identity"); }
        }
        service.failure = null;
        final Object[] nested = new Object[1];
        final CarrierConfigLoader inner = new CarrierConfigLoader(); inner.result = bundle();
        service.onCall = new Runnable() {
            @Override public void run() {
                try { nested[0] = invoke(config, inner, 10, "package"); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            }
        };
        PersistableBundle outer = (PersistableBundle) invoke(config, service, 10, "package");
        check(nested[0] == inner.result, "nested internal service query remains original");
        equal("us", outer.values.get(COUNTRY), "outer service response copied after original");
        service.onCall = new Runnable() {
            @Override public void run() { GlobalHooks.configure(new int[0], new int[0], new String[0], new String[0]); }
        };
        check(invoke(config, service, 10, "package") == service.result, "disable during original call cannot publish stale copy");
        service.onCall = null;
        GlobalHooks.configure(new int[0], new int[0], new String[0], new String[0]);
        int identities = caller.calls;
        check(invoke(config, service, 10, "package") == service.result, "empty snapshot disables all clones");
        equal(identities, caller.calls, "disabled callback needs no identity native call");
        configure();
        GlobalHooks.Callback absent = new GlobalHooks.Callback(GlobalHooks.CONFIG);
        try { invoke(absent, service, 10, "package"); throw new AssertionError("missing backup should fail"); }
        catch (IllegalStateException expected) { equal("Hook backup is not ready", expected.getMessage(), "missing backup explicit"); }
    }
    private static void invalidConfiguration(FakeInstaller installer) throws Throwable {
        int[][] ids = {null, new int[] {10}, new int[] {10,10}, new int[] {10,20}, new int[] {-1}};
        int[][] slots = {new int[0], new int[] {8}, new int[] {0,1}, new int[] {0,0}, new int[] {0}};
        String[][] countries = {new String[0], new String[] {"USA"}, new String[] {"US","JP"}, new String[] {"US","JP"}, new String[] {"US"}};
        String[][] names = {new String[0], new String[] {"Name"}, new String[] {"A","B"}, new String[] {"A","B"}, new String[] {"A"}};
        for (int i = 0; i < ids.length; i++) {
            try { GlobalHooks.configure(ids[i], slots[i], countries[i], names[i]); throw new AssertionError("invalid accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        CarrierConfigLoader service = new CarrierConfigLoader(); service.result = bundle();
        PersistableBundle retained = (PersistableBundle) invoke(installer.get("getConfigForSubId"), service, 10, "pkg");
        equal("us", retained.values.get(COUNTRY), "rejected config preserves complete previous snapshot");
    }
    private static void initializationFailure(FakeCaller caller) throws Throwable {
        GlobalHooks.resetForTests(); FakeInstaller failed = new FakeInstaller(); failed.failAt = 4;
        GlobalHooks.installer = failed; GlobalHooks.caller = caller;
        try { GlobalHooks.initialize(new Context(GlobalHooksTest.class.getClassLoader())); throw new AssertionError("partial hooks accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains(failed.failedTarget.getName()), "required-hook failure names the actual failing exit"); }
        String firstFailure = GlobalHooks.getError();
        check(firstFailure.contains(failed.failedTarget.getDeclaringClass().getName() + "." + failed.failedTarget.getName()) && firstFailure.contains("java.lang.String"),
                "bootstrap diagnostic retains the exact class, method and parameter types");
        equal(4, failed.attempts, "initialisation stops at first missing hook");
        try { GlobalHooks.initialize(new Context(GlobalHooksTest.class.getClassLoader())); throw new AssertionError("partial retry accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains(firstFailure), "partial retry retains the original failure"); }
        equal(firstFailure, GlobalHooks.getError(), "partial retry cannot overwrite original bootstrap evidence");
        equal(4, failed.attempts, "partial initialisation never double-hooks previous targets");
    }
    private static void missingBootstrapDependencies(FakeCaller caller) throws Throwable {
        GlobalHooks.resetForTests(); FakeInstaller installer = new FakeInstaller();
        GlobalHooks.installer = installer; GlobalHooks.caller = caller;
        try { GlobalHooks.initialize(null); throw new AssertionError("missing context accepted"); }
        catch (IllegalArgumentException expected) { check(GlobalHooks.getError().contains("phone Context"), "missing attach Context is named precisely"); }
        check(!GlobalHooks.isHealthy(), "missing attach Context cannot report healthy");
        GlobalHooks.resetForTests();
        ClassLoader missingClass = new ClassLoader(GlobalHooksTest.class.getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.android.phone.CarrierConfigLoader")) throw new ClassNotFoundException(name);
                return super.loadClass(name);
            }
        };
        try { GlobalHooks.initialize(new Context(missingClass)); throw new AssertionError("missing service class accepted"); }
        catch (ClassNotFoundException expected) {
            check(GlobalHooks.getError().contains("com.android.phone.CarrierConfigLoader"), "missing OEM service class survives bootstrap reporting");
        }
        equal(0, installer.attempts, "missing service class prevents partial hooks");
        GlobalHooks.resetForTests();
        ClassLoader missingMethod = new ClassLoader(GlobalHooksTest.class.getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.android.phone.CarrierConfigLoader")) return UnsupportedConfig.class;
                return super.loadClass(name);
            }
        };
        try { GlobalHooks.initialize(new Context(missingMethod)); throw new AssertionError("missing service method accepted"); }
        catch (IllegalStateException expected) {
            check(GlobalHooks.getError().contains("No exposed CarrierConfig SIM readers"),
                    "absent reader category prevents incomplete coverage");
        }
        equal(0, installer.attempts, "missing required overload fails before installing hooks");
    }
    private static void healthAndNotifications() throws Throwable {
        configure(); check(GlobalHooks.isHealthy(), "valid configure marks healthy");
        UnsupportedService service = new UnsupportedService();
        GlobalHooks.Callback callback = new GlobalHooks.Callback(GlobalHooks.CONFIG);
        callback.backup = UnsupportedService.class.getMethod("getConfig", int.class);
        check(invoke(callback, service, 10) == service.result, "copy reflection failure returns original");
        check(!GlobalHooks.isHealthy(), "copy failure is visible to native health polling");
        check(GlobalHooks.getError().contains("UnsupportedConfig") && GlobalHooks.getError().contains("NoSuchMethodException"),
                "clone failure publishes the specific reflection error for native polling");
        configure(); check(GlobalHooks.isHealthy(), "new valid configuration clears copy failure");
        equal("", GlobalHooks.getError(), "successful configuration clears a recoverable clone diagnostic");
        Context context = new Context(GlobalHooksTest.class.getClassLoader());
        check(!GlobalHooks.notifyChanged(context), "missing notification service is optional");
        TelephonyRegistryManager registry = new TelephonyRegistryManager(); context.service = registry;
        check(GlobalHooks.notifyChanged(context), "subscription notification supported");
        equal(1, registry.notifications, "one subscription notification dispatched");
        registry.fail = true;
        check(!GlobalHooks.notifyChanged(context), "notification exception never escapes phone callback");
        check(GlobalHooks.isHealthy(), "optional notification failure does not affect copy health");
    }
    private static void originalScope(FakeCaller caller, FakeInstaller installer, final FakeScope scope) throws Throwable {
        configure(); caller.uid = 10234; caller.trusted = false;
        final CarrierConfigLoader service = new CarrierConfigLoader(); service.result = bundle();
        GlobalHooks.Callback callback = installer.get("getConfigForSubId");
        scope.afterEnter = new Runnable() {
            @Override public void run() { caller.uid = 1001; caller.trusted = true; }
        };
        service.onCall = new Runnable() {
            @Override public void run() { check(scope.depth == 1, "original runs inside native true-value scope"); }
        };
        int entries = scope.entries, exits = scope.exits;
        PersistableBundle copied = (PersistableBundle) invoke(callback, service, 10, "package");
        equal("us", copied.values.get(COUNTRY), "caller eligibility is captured before original scope changes identity");
        equal(entries + 1, scope.entries, "one original scope entered");
        equal(exits + 1, scope.exits, "one original scope exited");
        equal(0, scope.depth, "successful original scope cleaned up");
        scope.afterEnter = null; caller.uid = 10234; caller.trusted = false;
        Throwable originalFailure = new SecurityException("original permission denied");
        service.failure = originalFailure;
        try { invoke(callback, service, 10, "package"); throw new AssertionError("original error expected"); }
        catch (Throwable actual) { check(actual == originalFailure, "original exception remains unchanged after scope cleanup"); }
        equal(0, scope.depth, "throwing original scope cleaned up");
        service.failure = null;
        scope.enterFailure = new IllegalStateException("native guard unavailable");
        int calls = service.calls;
        try { invoke(callback, service, 10, "package"); throw new AssertionError("guard error expected"); }
        catch (Throwable actual) { check(actual == scope.enterFailure, "guard entry failure is preserved"); }
        equal(calls, service.calls, "unguarded original cannot consume fake global properties");
        check(!GlobalHooks.isHealthy(), "guard entry failure disables clone health");
        check(GlobalHooks.getError().contains("native guard unavailable"), "original guard failure retains its actual cause");
        equal(0, scope.depth, "failed guard entry leaves no scope");
        scope.enterFailure = null; configure();
        copied = (PersistableBundle) invoke(callback, service, 10, "package");
        equal("us", copied.values.get(COUNTRY), "guard failure still cleans Java nesting depth");
        Throwable cleanupFailure = new IllegalStateException("native guard exit failed");
        scope.exitFailure = cleanupFailure; service.failure = originalFailure;
        try { invoke(callback, service, 10, "package"); throw new AssertionError("original error expected"); }
        catch (Throwable actual) {
            check(actual == originalFailure, "cleanup failure does not replace original permission exception");
            check(Arrays.asList(actual.getSuppressed()).contains(cleanupFailure), "cleanup failure retained as suppressed evidence");
        }
        equal(0, scope.depth, "failing exit still executes scope cleanup");
        check(!GlobalHooks.isHealthy(), "guard exit failure disables clone health");
        scope.exitFailure = null; service.failure = null; service.onCall = null; configure();
    }
    private static void attachmentFailure() throws Throwable {
        configure();
        AttachFixture service = new AttachFixture();
        GlobalHooks.AttachCallback callback = new GlobalHooks.AttachCallback();
        callback.backup = AttachFixture.class.getMethod("attach", Object.class);
        Object context = new Object();
        check(callback.callback(new Object[] {service, context}) == context, "original attach result retained when native bootstrap is unavailable");
        equal(1, service.calls, "original attach runs before native bootstrap failure");
        check(!GlobalHooks.isHealthy(), "bootstrap failure is visible without crashing original attach");
        check(GlobalHooks.getError().contains("UnsatisfiedLinkError") && GlobalHooks.getError().contains("nativeAttached"),
                "attach bootstrap failure retains native linkage evidence");
        configure();
        Throwable failure = new SecurityException("original attach failed"); service.failure = failure;
        try { callback.callback(new Object[] {service, context}); throw new AssertionError("original attach error expected"); }
        catch (Throwable actual) { check(actual == failure, "original attach exception is preserved"); }
        check(GlobalHooks.isHealthy(), "failed original attach never reaches native bootstrap");
    }
    private static void profileValidation(final FakeScope scope) {
        check(GlobalHooks.validateProfiles(null, new int[0], new int[0]), "empty setting requires no live SIM service");
        check(!GlobalHooks.validateProfiles(null, new int[] {10}, new int[] {0}), "nonempty setting requires context");
        check(!GlobalHooks.validateProfiles(null, null, new int[0]), "null identity array rejected");
        check(!GlobalHooks.validateProfiles(null, new int[] {10}, new int[0]), "mismatched mapping arrays rejected");
        Context context = new Context(GlobalHooksTest.class.getClassLoader());
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "missing live services rejected");
        final SubscriptionManager subscriptions = new SubscriptionManager();
        TelephonyManager telephony = new TelephonyManager();
        context.services.put(SubscriptionManager.class, subscriptions); context.services.put(TelephonyManager.class, telephony);
        final List<SubscriptionInfo> active = Arrays.asList(info(10, 0), info(20, 1));
        subscriptions.active = active; telephony.states[0] = 5; telephony.states[1] = 10;
        subscriptions.onRead = new Runnable() {
            @Override public void run() { check(scope.depth == 1, "active mapping reads run in original native scope"); }
        };
        check(GlobalHooks.validateProfiles(context, new int[] {20, 10}, new int[] {1, 0}), "dual SIM mapping accepts ready and loaded states independently of order");
        check(GlobalHooks.validateProfiles(context, new int[] {20}, new int[] {1}), "selected live SIM subset accepted");
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {1}), "logical slot reassignment rejected");
        check(!GlobalHooks.validateProfiles(context, new int[] {30}, new int[] {0}), "removed or stale subId rejected");
        check(!GlobalHooks.validateProfiles(context, new int[] {10, 10}, new int[] {0, 0}), "duplicate requests rejected");
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {8}), "out of range logical slot rejected");
        for (int state : new int[] {0, 1, 2, 3, 4, 6, 7, 8, 9}) {
            telephony.states[0] = state;
            check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "unready SIM state " + state + " rejected");
        }
        telephony.states[0] = 5;
        subscriptions.active = null;
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "null active list rejected");
        subscriptions.active = Collections.emptyList();
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "empty active list rejected");
        subscriptions.active = Arrays.asList(info(10, 0), info(10, 1));
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "ambiguous active identity rejected");
        subscriptions.active = active; subscriptions.fail = true;
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "subscription service exception fails closed");
        equal(0, scope.depth, "subscription service failure cleans native scope");
        subscriptions.fail = false; telephony.fail = true;
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "SIM state exception fails closed");
        equal(0, scope.depth, "SIM state failure cleans native scope");
        telephony.fail = false; scope.enterFailure = new IllegalStateException("guard unavailable");
        int calls = subscriptions.calls;
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "validation guard failure fails closed");
        equal(calls, subscriptions.calls, "guard failure prevents unprotected live mapping read");
        scope.enterFailure = null; scope.exitFailure = new IllegalStateException("guard cleanup failed");
        check(!GlobalHooks.validateProfiles(context, new int[] {10}, new int[] {0}), "validation exit failure rejects apparently valid mapping");
        equal(0, scope.depth, "validation exit cleanup attempted");
        scope.exitFailure = null; configure();
    }
    public static void main(String[] args) throws Throwable {
        GlobalHooks.Installer originalInstaller = GlobalHooks.installer; GlobalHooks.Caller originalCaller = GlobalHooks.caller;
        GlobalHooks.OriginalScope originalScope = GlobalHooks.originalScope;
        try {
            FakeScope scope = new FakeScope(); GlobalHooks.originalScope = scope;
            FakeCaller caller = new FakeCaller(); FakeInstaller installer = initialize(caller);
            configsAndIdentity(caller, installer); infoCopies(installer); failuresAndNested(caller, installer);
            invalidConfiguration(installer); originalScope(caller, installer, scope); attachmentFailure();
            profileValidation(scope); healthAndNotifications(); initializationFailure(caller); missingBootstrapDependencies(caller);
            System.out.println("GlobalHooks host-JVM: " + checks + " checks passed (no ART/JNI/device compatibility claim)");
        } finally {
            GlobalHooks.resetForTests(); GlobalHooks.installer = originalInstaller;
            GlobalHooks.caller = originalCaller; GlobalHooks.originalScope = originalScope;
        }
    }
}
