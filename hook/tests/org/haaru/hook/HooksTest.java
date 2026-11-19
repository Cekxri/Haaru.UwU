/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Host-JVM callback dispatch tests. These do not exercise ART, JNI or an Android hook. */
public final class HooksTest {
    private static final String COUNTRY_KEY = "sim_country_iso_override_string";
    private static final String NAME_KEY = "carrier_name_string";
    private static final String FLAG_KEY = "carrier_name_override_bool";
    private static int checks;

    public static final class FakeTelephony {
        final int subId;
        int calls;
        Object[] forwarded;
        Throwable failure;
        Object configuration;
        static int staticCalls;
        static Object[] staticForwarded;
        FakeTelephony(int subId) { this.subId = subId; }
        public String country() { calls++; return "original-country:" + subId; }
        public String name() { calls++; return "original-name:" + subId; }
        public String countryForId(int id, String feature) {
            calls++; forwarded = new Object[] { id, feature }; return "instance-country:" + subId + ":" + id + ":" + feature;
        }
        public String nameForId(int id) { calls++; forwarded = new Object[] { id }; return "instance-name:" + subId + ":" + id; }
        public static String staticCountry(int id, String attribution) {
            staticCalls++; staticForwarded = new Object[] { id, attribution }; return "static-country:" + id + ":" + attribution;
        }
        public static String staticName(int id) { staticCalls++; return "static-name:" + id; }
        public static String slotCountry(int slot) { staticCalls++; return "original-slot:" + slot; }
        public static String longCountry(long id) { staticCalls++; return "original-long:" + id; }
        public Object configForId(int id, String attribution) {
            calls++; forwarded = new Object[] { id, attribution }; return configuration;
        }
        public String throwingCountry() throws Throwable { calls++; throw failure; }
    }

    public static final class FakeInfo {
        final int subId;
        int calls;
        FakeInfo(int subId) { this.subId = subId; }
        public String country() { calls++; return "info-original:" + subId; }
        public String name() { calls++; return "info-name:" + subId; }
    }

    /** All put methods affect only the explicitly returned in-memory object. */
    public static final class FakePersistableBundle {
        final Map<String, Object> contents = new LinkedHashMap<String, Object>();
        int stringPuts, booleanPuts;
        public FakePersistableBundle() {}
        public FakePersistableBundle(FakePersistableBundle original) { contents.putAll(original.contents); }
        public void putString(String key, String value) { stringPuts++; contents.put(key, value); }
        public void putBoolean(String key, boolean value) { booleanPuts++; contents.put(key, value); }
    }

    public static final class UncopyableBundle {
        final String value = "untouched";
        public UncopyableBundle() {}
    }

    private static final class FakeScopeResolver implements Hooks.ScopeResolver {
        int defaultId = 20, calls, lastKind;
        Object lastReceiver;
        @Override public int resolve(Object object, int kind) {
            calls++; lastKind = kind; lastReceiver = object;
            if (kind == Hooks.MANAGER && object instanceof FakeTelephony) {
                int id = ((FakeTelephony) object).subId; return id < 0 ? defaultId : id;
            }
            if (kind == Hooks.INFO && object instanceof FakeInfo) return ((FakeInfo) object).subId;
            return -1;
        }
    }

    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
    private static void equal(Object expected, Object actual, String description) {
        check(Objects.equals(expected, actual), description + ": expected " + expected + ", got " + actual);
    }
    private static Hooks.Callback callback(int kind, int scope, boolean isStatic,
            Class<?> type, String method, Class<?>... parameters) throws Exception {
        Hooks.Callback out = new Hooks.Callback(kind, scope, isStatic);
        out.backup = type.getMethod(method, parameters); return out;
    }
    private static Object invoke(Hooks.Callback callback, Object... arguments) throws Throwable { return callback.callback(arguments); }
    private static void twoProfiles() {
        Hooks.configure(new int[] {10, 20}, new int[] {0, 1}, new String[] {"US", "jp"}, new String[] {"US carrier", "JP carrier"});
    }

    private static void explicitIdsAndSlots(FakeScopeResolver resolver) throws Throwable {
        twoProfiles(); int resolutions = resolver.calls;
        Hooks.Callback staticCountry = callback(Hooks.COUNTRY, Hooks.SUB_ID, true,
                FakeTelephony.class, "staticCountry", int.class, String.class);
        Hooks.Callback staticName = callback(Hooks.NAME, Hooks.SUB_ID, true,
                FakeTelephony.class, "staticName", int.class);
        equal("us", invoke(staticCountry, 10, "caller"), "static first argument is subscription ID");
        equal("jp", invoke(staticCountry, 20, "other"), "static second SIM has its own country");
        equal("US carrier", invoke(staticName, 10), "first SIM name");
        equal("JP carrier", invoke(staticName, 20), "second SIM name");
        equal("static-country:30:attribution", invoke(staticCountry, 30, "attribution"), "unmatched static getter uses backup");
        equal(30, FakeTelephony.staticForwarded[0], "backup receives unmodified static ID");
        equal("attribution", FakeTelephony.staticForwarded[1], "backup preserves static attribution argument");
        FakeTelephony receiver = new FakeTelephony(99);
        Hooks.Callback instance = callback(Hooks.COUNTRY, Hooks.SUB_ID, false,
                FakeTelephony.class, "countryForId", int.class, String.class);
        equal("jp", invoke(instance, receiver, 20, "feature"), "instance receiver is not mistaken for first argument");
        equal(0, receiver.calls, "matched country does not invoke original getter");
        equal("instance-country:99:30:feature", invoke(instance, receiver, 30, "feature"), "unmatched instance getter receives original receiver");
        equal(30, receiver.forwarded[0], "backup forwards instance subscription argument");
        equal("feature", receiver.forwarded[1], "backup forwards instance feature argument");
        Hooks.Callback instanceName = callback(Hooks.NAME, Hooks.SUB_ID, false,
                FakeTelephony.class, "nameForId", int.class);
        equal("US carrier", invoke(instanceName, receiver, 10), "name lookup uses explicit ID rather than receiver scope");
        Hooks.Callback slot = callback(Hooks.COUNTRY, Hooks.SLOT, true, FakeTelephony.class, "slotCountry", int.class);
        equal("us", invoke(slot, 0), "slot zero maps to subscription ten");
        equal("jp", invoke(slot, 1), "slot one maps to subscription twenty");
        equal("original-slot:10", invoke(slot, 10), "subscription ID cannot be treated as a matching slot");
        equal("static-country:0:caller", invoke(staticCountry, 0, "caller"), "slot number cannot be treated as a matching subscription");
        Hooks.Callback longArgument = callback(Hooks.COUNTRY, Hooks.SUB_ID, true,
                FakeTelephony.class, "longCountry", long.class);
        equal("original-long:10", invoke(longArgument, Long.valueOf(10)), "only Integer arguments select an explicit subscription");
        equal(resolutions, resolver.calls, "explicit static and instance arguments never call native scope resolver");
    }

    private static void scopedReceivers(FakeScopeResolver resolver) throws Throwable {
        twoProfiles();
        Hooks.Callback country = callback(Hooks.COUNTRY, Hooks.MANAGER, false, FakeTelephony.class, "country");
        Hooks.Callback name = callback(Hooks.NAME, Hooks.MANAGER, false, FakeTelephony.class, "name");
        FakeTelephony first = new FakeTelephony(10), second = new FakeTelephony(20), defaultManager = new FakeTelephony(-1);
        equal("us", invoke(country, first), "scoped first manager country");
        equal("jp", invoke(country, second), "scoped second manager country");
        equal("US carrier", invoke(name, first), "scoped first manager name");
        equal("JP carrier", invoke(name, second), "scoped second manager name");
        equal("jp", invoke(country, defaultManager), "unscoped manager uses resolved current default");
        check(resolver.lastReceiver == defaultManager && resolver.lastKind == Hooks.MANAGER, "resolver gets actual receiver and manager kind");
        resolver.defaultId = 10;
        equal("us", invoke(country, defaultManager), "default is resolved per callback and not cached from another SIM");
        resolver.defaultId = -1;
        equal("original-country:-1", invoke(country, defaultManager), "unknown default preserves original getter");
        equal("original-name:30", invoke(name, new FakeTelephony(30)), "unmatched manager profile preserves actual name");
        FakeInfo info = new FakeInfo(20);
        Hooks.Callback infoCountry = callback(Hooks.COUNTRY, Hooks.INFO, false, FakeInfo.class, "country");
        Hooks.Callback infoName = callback(Hooks.NAME, Hooks.INFO, false, FakeInfo.class, "name");
        equal("jp", invoke(infoCountry, info), "SubscriptionInfo resolves its own subscription");
        equal("JP carrier", invoke(infoName, info), "SubscriptionInfo name follows its own SIM");
        check(resolver.lastReceiver == info && resolver.lastKind == Hooks.INFO, "info resolver dispatch kind preserved");
        equal("info-original:30", invoke(infoCountry, new FakeInfo(30)), "unmatched info getter uses backup");
        equal(0, info.calls, "matched info getters bypass backup");
        resolver.defaultId = 20;
    }

    private static FakePersistableBundle originalBundle() {
        FakePersistableBundle out = new FakePersistableBundle();
        out.contents.put(COUNTRY_KEY, "tw"); out.contents.put(FLAG_KEY, false); out.contents.put(NAME_KEY, "Original");
        out.contents.put("carrier_volte_available_bool", true); out.contents.put("unrelated_string", "keep"); return out;
    }
    private static void blankFieldsAndPrivateConfig() throws Throwable {
        Hooks.configure(new int[] {10, 20}, new int[] {0, 1}, new String[] {"US", ""}, new String[] {"", "NTT"});
        Hooks.Callback country = callback(Hooks.COUNTRY, Hooks.MANAGER, false, FakeTelephony.class, "country");
        Hooks.Callback name = callback(Hooks.NAME, Hooks.MANAGER, false, FakeTelephony.class, "name");
        FakeTelephony first = new FakeTelephony(10), second = new FakeTelephony(20);
        equal("us", invoke(country, first), "country-only overrides country");
        equal("original-name:10", invoke(name, first), "country-only returns actual carrier name");
        equal("original-country:20", invoke(country, second), "name-only returns actual country");
        equal("NTT", invoke(name, second), "name-only overrides name");
        FakePersistableBundle original = originalBundle(); Map<String, Object> baseline = new LinkedHashMap<String, Object>(original.contents);
        first.configuration = original;
        Hooks.Callback config = callback(Hooks.CONFIG, Hooks.SUB_ID, false,
                FakeTelephony.class, "configForId", int.class, String.class);
        FakePersistableBundle countryCopy = (FakePersistableBundle) invoke(config, first, 10, "package");
        check(countryCopy != original, "CONFIG returns a private copy");
        equal("us", countryCopy.contents.get(COUNTRY_KEY), "country-only CONFIG changes private country key");
        equal("Original", countryCopy.contents.get(NAME_KEY), "country-only CONFIG keeps original name");
        equal(false, countryCopy.contents.get(FLAG_KEY), "country-only CONFIG keeps original name flag");
        equal(1, countryCopy.stringPuts, "country-only performs one private string put");
        equal(0, countryCopy.booleanPuts, "country-only performs no private flag put");
        FakePersistableBundle nameCopy = (FakePersistableBundle) invoke(config, first, 20, "package");
        check(nameCopy != original && nameCopy != countryCopy, "each SIM receives its own private copy");
        equal("tw", nameCopy.contents.get(COUNTRY_KEY), "name-only CONFIG keeps original country");
        equal("NTT", nameCopy.contents.get(NAME_KEY), "name-only CONFIG changes private name");
        equal(true, nameCopy.contents.get(FLAG_KEY), "name-only CONFIG enables name on the private copy");
        equal(1, nameCopy.stringPuts, "name-only performs one private string put");
        equal(1, nameCopy.booleanPuts, "name-only performs one private flag put");
        equal(baseline, original.contents, "original service result is never mutated");
        equal(0, original.stringPuts + original.booleanPuts, "zero writes on original bundle");
        equal(true, countryCopy.contents.get("carrier_volte_available_bool"), "private copy retains IMS value");
        equal("keep", nameCopy.contents.get("unrelated_string"), "private copy retains unrelated string");
        equal(20, first.forwarded[0], "CONFIG backup receives explicit SIM ID");
        equal("package", first.forwarded[1], "CONFIG backup keeps attribution");
        check(invoke(config, first, 30, "package") == original, "unmatched CONFIG returns original object directly");
        Hooks.configure(new int[] {10}, new int[] {0}, new String[] {"JP"}, new String[] {"DOCOMO"});
        FakePersistableBundle both = (FakePersistableBundle) invoke(config, first, 10, "package");
        equal(2, both.stringPuts, "both fields use exactly the two private string keys");
        equal(1, both.booleanPuts, "both fields use exactly the private carrier flag");
        equal("jp", both.contents.get(COUNTRY_KEY), "combined private country");
        equal("DOCOMO", both.contents.get(NAME_KEY), "combined private name");
        equal(baseline, original.contents, "combined profile leaves original bundle intact");
        Hooks.configure(new int[] {10}, new int[] {0}, new String[] {null}, new String[] {null});
        equal("original-country:10", invoke(country, first), "null country is a blank override");
        equal("original-name:10", invoke(name, first), "null name is a blank override");
        FakePersistableBundle emptyCopy = (FakePersistableBundle) invoke(config, first, 10, "package");
        equal(baseline, emptyCopy.contents, "blank CONFIG preserves every original value");
        equal(0, emptyCopy.stringPuts + emptyCopy.booleanPuts, "blank profile performs no private puts");
        first.configuration = null; check(invoke(config, first, 10, "package") == null, "null backup config stays null");
        UncopyableBundle uncopyable = new UncopyableBundle(); first.configuration = uncopyable;
        check(invoke(config, first, 10, "package") == uncopyable, "unsupported copy constructor safely returns original");
        equal("untouched", uncopyable.value, "copy fallback leaves original object untouched");
    }

    private static void originalThrowables() throws Throwable {
        Hooks.configure(new int[0], new int[0], new String[0], new String[0]);
        FakeTelephony receiver = new FakeTelephony(10);
        Hooks.Callback country = callback(Hooks.COUNTRY, Hooks.MANAGER, false, FakeTelephony.class, "throwingCountry");
        SecurityException cause = new SecurityException("original cause");
        for (Throwable original : new Throwable[] { new IOException("original getter", cause), new AssertionError("original error") }) {
            receiver.failure = original;
            try { invoke(country, receiver); throw new AssertionError("Getter failure must propagate"); }
            catch (Throwable actual) {
                check(actual == original, "backup Throwable is unwrapped without replacing its identity");
                check(actual.getCause() == original.getCause(), "backup Throwable retains original cause");
            }
        }
        Hooks.Callback unpublished = new Hooks.Callback(Hooks.COUNTRY, Hooks.MANAGER, false);
        try { invoke(unpublished, receiver); throw new AssertionError("Missing backup must fail"); }
        catch (IllegalStateException expected) { equal("Hook backup is not ready", expected.getMessage(), "unpublished backup fails explicitly"); }
    }

    private static void rejects(int[] ids, int[] positions, String[] countries, String[] names) {
        try { Hooks.configure(ids, positions, countries, names); throw new AssertionError("Invalid profiles accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        finally { Hooks.configure(new int[0], new int[0], new String[0], new String[0]); }
    }
    private static void invalidInputAndReload() throws Throwable {
        rejects(null, new int[0], new String[0], new String[0]);
        rejects(new int[0], null, new String[0], new String[0]);
        rejects(new int[0], new int[0], null, new String[0]);
        rejects(new int[0], new int[0], new String[0], null);
        rejects(new int[] {10}, new int[0], new String[] {"US"}, new String[] {"Name"});
        rejects(new int[] {10}, new int[] {0}, new String[0], new String[] {"Name"});
        rejects(new int[] {10}, new int[] {0}, new String[] {"US"}, new String[0]);
        rejects(new int[9], new int[9], new String[9], new String[9]);
        rejects(new int[] {-1}, new int[] {0}, new String[] {"US"}, new String[] {"Name"});
        rejects(new int[] {10}, new int[] {-1}, new String[] {"US"}, new String[] {"Name"});
        rejects(new int[] {10}, new int[] {8}, new String[] {"US"}, new String[] {"Name"});
        for (String country : new String[] {"U", "USA", "U1", "ＵＳ", "US;id", "U\n"})
            rejects(new int[] {10}, new int[] {0}, new String[] {country}, new String[] {"Name"});
        for (String name : new String[] {"line\nbreak", "a\u0000b", "a\u007fb", new String(new char[81]).replace('\0', 'x')})
            rejects(new int[] {10}, new int[] {0}, new String[] {"US"}, new String[] {name});
        twoProfiles();
        Hooks.Callback id = callback(Hooks.COUNTRY, Hooks.SUB_ID, true, FakeTelephony.class, "staticCountry", int.class, String.class);
        Hooks.Callback slot = callback(Hooks.COUNTRY, Hooks.SLOT, true, FakeTelephony.class, "slotCountry", int.class);
        Hooks.configure(new int[] {33}, new int[] {2}, new String[] {"De"}, new String[] {"日本電信"});
        equal("static-country:10:caller", invoke(id, 10, "caller"), "reload removes old first subscription");
        equal("static-country:20:caller", invoke(id, 20, "caller"), "reload removes old second subscription");
        equal("original-slot:0", invoke(slot, 0), "reload removes old first slot");
        equal("original-slot:1", invoke(slot, 1), "reload removes old second slot");
        equal("de", invoke(id, 33, "caller"), "reload installs only the new profile");
        equal("de", invoke(slot, 2), "reload installs new slot and normalises ISO case");
        Hooks.configure(new int[0], new int[0], new String[0], new String[0]);
        equal("static-country:33:caller", invoke(id, 33, "caller"), "empty configure disables all subscription overrides");
        equal("original-slot:2", invoke(slot, 2), "empty configure disables all slot overrides");
        int[] ids = {0}; int[] positions = {7}; String[] countries = {"GB"}; String[] names = {"Valid"};
        Hooks.configure(ids, positions, countries, names);
        ids[0] = 99; positions[0] = 0; countries[0] = "DE"; names[0] = "Changed";
        equal("gb", invoke(id, 0, "caller"), "configuration does not retain mutable input arrays");
        equal("gb", invoke(slot, 7), "upper slot boundary remains correctly mapped");
    }

    public static void main(String[] args) throws Throwable {
        Hooks.ScopeResolver original = Hooks.resolver; FakeScopeResolver fake = new FakeScopeResolver(); Hooks.resolver = fake;
        try {
            explicitIdsAndSlots(fake); scopedReceivers(fake); blankFieldsAndPrivateConfig();
            originalThrowables(); invalidInputAndReload();
            System.out.println("Hooks host-JVM dispatch: " + checks + " checks passed (no ART/JNI/device compatibility claim)");
        } finally {
            Hooks.resolver = original; Hooks.configure(new int[0], new int[0], new String[0], new String[0]);
        }
    }
}
