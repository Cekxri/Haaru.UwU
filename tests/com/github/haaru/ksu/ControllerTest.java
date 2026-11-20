package com.github.haaru.ksu;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.github.haaru.ksu.Backend.Subscription;
import com.github.haaru.ksu.Backend.Values;

/** JVM contract tests: every platform adapter available here is read-only. */
public final class ControllerTest {
    private static int checks;
    private static final Values ORIGINAL = new Values("", false, "Original", true);
    private static final class Fake implements Backend {
        List<Subscription> cards = new ArrayList<Subscription>();
        Map<Integer, Values> values = new HashMap<Integer, Values>();
        Map<Integer, String> sim = new HashMap<Integer, String>();
        int calls, failId = -1; boolean unavailable, replaceOnSecondRead; int lists;
        Fake() { add(0, 10, "strong:A"); }
        void add(int slot, int id, String identity) {
            cards.add(new Subscription(slot, id, identity)); values.put(id, ORIGINAL); sim.put(id, "tw");
        }
        public List<Subscription> subscriptions() throws Exception {
            calls++; lists++; if (unavailable) throw new IOException("Offline");
            if (replaceOnSecondRead && lists == 2) { cards.clear(); add(0, 11, "strong:new"); }
            return cards;
        }
        public Values read(int id) throws Exception { calls++; if (id == failId) throw new IOException("Read denied"); return values.get(id); }
        public String simCountry(int id) { calls++; return sim.get(id); }
        public int defaultSubId() { calls++; return 10; }
    }
    private static Controller controller(Path dir, Fake f, String boot) { return new Controller(dir, f, boot, 0); }
    private static Path directory() throws Exception { return Files.createTempDirectory("haaru-v4-test-").resolve("data"); }
    private static void check(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
    private static void rejects(Controller controller, String... args) throws Exception {
        try { controller.run(args); throw new AssertionError("Expected failure " + Arrays.toString(args)); }
        catch (IllegalArgumentException | IllegalStateException | IOException expected) { checks++; }
    }
    private static String hex(String value) {
        StringBuilder out = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) out.append(String.format("%02x", item & 255)); return out.toString();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> profiles(Map<String, Object> result) { return (List<Map<String, Object>>) result.get("profiles"); }
    private static Map<String, Object> profile(Map<String, Object> result, int slot) {
        for (Map<String, Object> p : profiles(result)) if (((Number) p.get("slot")).intValue() == slot) return p;
        throw new AssertionError("Missing profile " + slot);
    }
    private static Properties state(Path dir) throws Exception {
        Properties out = new Properties(); try (InputStream in = Files.newInputStream(dir.resolve("state.properties"))) { out.load(in); } return out;
    }
    private static void putState(Path dir, Properties state) throws Exception {
        Files.createDirectories(dir); try (OutputStream out = Files.newOutputStream(dir.resolve("state.properties"))) { state.store(out, "fixture"); }
    }
    private static void unchanged(Fake f) {
        for (Map.Entry<Integer, Values> item : f.values.entrySet()) check(item.getValue().equals(ORIGINAL), "No platform carrier values changed");
        for (String country : f.sim.values()) check("tw".equals(country), "No platform SIM country changed");
    }
    private static void inputsAndMerge() throws Exception {
        Fake f = new Fake(); Path dir = directory(); Controller c = controller(dir, f, "boot1");
        check(Boolean.FALSE.equals(c.run("status").get("verified")), "Status does not claim a platform mutation");
        check(!Files.exists(dir.resolve("state.properties")), "Status does not invent saved profiles");
        rejects(c, "refresh"); rejects(c, "set", "8", "US", "-"); rejects(c, "set", "01", "US", "-");
        rejects(c, "set", "0", "US;id", "-"); rejects(c, "set", "0", "ZZ", "-"); rejects(c, "set", "0", "-", "-");
        rejects(c, "set", "0", "US", "c0af"); rejects(c, "set", "0", "US", "0a");
        rejects(c, "set", "0", "US", hex(new String(new char[81]).replace('\0', 'a')));
        check("日本電信".equals(Controller.decodeCarrier("e697a5e69cace99bbbe4bfa1")), "UTF8 decode");
        Map<String, Object> saved = c.run("set", "0", "-", hex("NTT"));
        check(Boolean.TRUE.equals(saved.get("verified")) && "module-files".equals(saved.get("verification")), "Verified means durable local file readback");
        check("".equals(profile(saved, 0).get("country")) && Boolean.FALSE.equals(profile(saved, 0).get("countryOverride")), "Name-only leaves country original");
        Map<String, Object> runtime = c.run("runtime");
        check(((Number) runtime.get("protocol")).intValue() == 2 && "boot1".equals(runtime.get("bootId")), "Global native protocol version and boot");
        check(Boolean.TRUE.equals(runtime.get("global")) && "system-global-volatile".equals(runtime.get("engine")), "Runtime applies per-SIM plans to all external callers");
        check(!runtime.containsKey("targetPackages"), "Global runtime has no application filter");
        check(((Number) runtime.get("defaultSubId")).intValue() == 10, "Default subscription read");
        check(Boolean.FALSE.equals(profile(runtime, 0).get("weakIdentity")), "Strong identity marker");
        check(Json.encode(runtime).equals(new String(Files.readAllBytes(dir.resolve("runtime.json")), StandardCharsets.UTF_8)), "Persisted runtime matches runtime command");
        saved = c.run("set", "0", "JP", "-");
        check("jp".equals(profile(saved, 0).get("country")) && "NTT".equals(profile(saved, 0).get("carrierName")), "Active country-only merge preserves carrier plan");
        saved = c.run("set", "0", "-", hex("Other"));
        check("jp".equals(profile(saved, 0).get("country")), "Active name-only merge preserves country plan");
        c.run("reset", "0");
        check(!Boolean.TRUE.equals(c.run("runtime").get("enabled")), "Reset stops selected hook");
        check("jp".equals(profile(c.run("status"), 0).get("country")), "Reset retains saved profile");
        saved = c.run("set", "0", "-", hex("New"));
        check("".equals(profile(saved, 0).get("country")), "Set after reset starts from empty country");
        c.run("disable"); saved = c.run("set", "0", "US", "-");
        check("".equals(profile(saved, 0).get("carrierName")), "Set after disable starts from empty carrier");
        c.run("forget", "0"); check(profiles(c.run("status")).isEmpty(), "Forget removes profile");
        rejects(c, "enable"); unchanged(f);
        for (String key : state(dir).stringPropertyNames())
            check(!key.startsWith("baseline.") && !key.startsWith("pending.") && !key.startsWith("last."), "No system snapshot state retained");
        check("system-global-volatile".equals(saved.get("engine")) && Boolean.TRUE.equals(saved.get("planned")), "Status labels global volatile plans");
    }
    private static void isolationAndIdentity() throws Exception {
        Fake f = new Fake(); f.add(1, 20, "strong:B"); Path dir = directory(); Controller c = controller(dir, f, "boot1");
        c.run("set", "0", "US", "-"); c.run("set", "1", "JP", hex("NTT"));
        c.run("reset", "0"); check(profiles(c.run("runtime")).size() == 1 && ((Number) profile(c.run("runtime"), 1).get("subId")).intValue() == 20, "Reset leaves other SIM active");
        f.cards.remove(1); c.run("set", "0", "DE", "-");
        check("jp".equals(profile(c.run("status"), 1).get("country")), "Set ignores another absent saved SIM");
        check(profiles(c.run("runtime")).size() == 1, "Absent SIM excluded from runtime");
        f.add(1, 20, "strong:B"); f.failId = 20;
        check(Boolean.TRUE.equals(c.run("enable").get("verified")), "Global enable confirms files despite unavailable slot");
        check(profiles(c.run("runtime")).size() == 1, "Read failure isolated to bad slot");
        f.failId = -1; f.values.put(20, new Values("", false, "Original", false));
        check(profiles(c.run("runtime")).size() == 1, "Unready slot skipped"); f.values.put(20, ORIGINAL);
        f.cards.set(1, new Subscription(1, 20, "strong:replacement"));
        check(profiles(c.run("runtime")).size() == 1, "Identity change excluded");
        c.run("set", "1", "-", hex("Replacement"));
        check("".equals(profile(c.run("status"), 1).get("country")), "Replacement never merges previous SIM country");
        f.cards.set(1, new Subscription(1, 21, "strong:replacement")); f.values.put(21, ORIGINAL); f.sim.put(21, "tw");
        check(profiles(c.run("runtime")).size() == 1, "Changed subscription ID excluded even with same identity");
        f.cards.add(new Subscription(1, 22, "strong:ambiguous"));
        check(profiles(c.run("runtime")).size() == 1, "Ambiguous slot does not block valid other slot");
        c.run("refresh"); c.run("clear"); check(profiles(c.run("status")).isEmpty(), "Clear removes every plan without restoring phone services");
        unchanged(f);
        f = new Fake(); f.cards.set(0, new Subscription(0, 10, "weak:slot=0;subId=10")); dir = directory();
        controller(dir, f, "boot1").run("set", "0", "US", "-");
        Map<String, Object> weak = controller(dir, f, "boot2").run("runtime");
        check(profiles(weak).isEmpty() && !((List<?>) weak.get("warnings")).isEmpty(), "Weak identity excluded across boot");
        controller(dir, f, "boot2").run("set", "0", "-", hex("Confirmed"));
        check("".equals(profile(controller(dir, f, "boot2").run("status"), 0).get("country")), "Unconfirmed weak profile does not resurrect old country");
        check("boot2".equals(profile(controller(dir, f, "boot2").run("runtime"), 0).get("confirmedBoot")), "Explicit weak identity reconfirmation");
        f = new Fake(); f.replaceOnSecondRead = true; dir = directory();
        rejects(controller(dir, f, "boot1"), "set", "0", "US", "-");
        check(!Files.exists(dir.resolve("state.properties")), "Identity changed before commit prevents saving");
        f = new Fake(); f.unavailable = true; dir = directory(); c = controller(dir, f, "boot1");
        check(Boolean.TRUE.equals(c.run("disable").get("verified")), "Disable works with phone services unavailable");
        check(Boolean.TRUE.equals(c.run("clear").get("verified")), "Clear works offline");
    }
    private static void appearanceWithoutApplicationRouting() throws Exception {
        Fake f = new Fake(); f.unavailable = true; Path dir = directory(); Controller c = controller(dir, f, "boot1");
        check(c.run("prefs").get("appearance") == null && f.calls == 0 && !Files.exists(dir), "Prefs reads only appearance file");
        String valid = "{\"style\":\"MATERIAL3\",\"appearance\":\"DARK\",\"palette\":\"VIOLET\",\"glass\":true,\"reduceMotion\":false}";
        check(Boolean.TRUE.equals(c.run("saveprefs", hex(valid)).get("verified")), "Preferences are atomically verified");
        check(Json.encode(c.run("prefs").get("appearance")).equals(valid) && f.calls == 0 && Files.isRegularFile(dir.resolve("appearance.json"))
                && !Files.exists(dir.resolve("state.properties")) && !Files.exists(dir.getParent().resolve("webroot")), "Appearance persists only in data without state or phone access");
        for (String invalid : Arrays.asList("[]", "null", "{\"style\":1}", "{\"unknown\":true}", "{\"glass\":\"true\"}",
                "{\"palette\":\"$(id)\"}", "{\"glass\":true,\"glass\":false}", "{\"style\":\"MIUIX\",}"))
            rejects(c, "saveprefs", hex(invalid));
        rejects(c, "saveprefs", "c0af"); rejects(c, "saveprefs", hex(valid + new String(new char[2048]).replace('\0', ' ')));
        check(f.calls == 0, "Invalid preferences never contact telephony");
        rejects(c, "targets"); rejects(c, "targets", hex("[\"com.zhiliaoapp.musically\"]"));
        check(f.calls == 0, "Removed application-routing actions fail before any phone access");
    }
    private static void nativeAppearanceMigrationAndMonet() throws Exception {
        Fake f = new Fake(); f.unavailable = true; Path dir = directory(); Controller c = controller(dir, f, "boot1");
        Path legacy = dir.getParent().resolve("webroot/appearance.json"); Files.createDirectories(legacy.getParent());
        byte[] original = "{\"palette\":\"MINT\",\"glass\":true}".getBytes(StandardCharsets.UTF_8); Files.write(legacy, original);
        check(((Map<?, ?>) c.run("prefs").get("appearance")).get("palette").equals("MINT") && !Files.exists(dir), "Legacy appearance read does not create data or contact telephony");
        check(!((Map<?, ?>) c.run("prefs").get("appearance")).containsKey("colourStyle") && Arrays.equals(original, Files.readAllBytes(legacy)), "Reading older preferences does not insert a colour-style default or rewrite the source");
        String monet = "{\"dynamicColour\":false,\"customColour\":\"#a1b2c3\",\"palette\":\"ORCHID\",\"glass\":false}";
        check(Boolean.TRUE.equals(c.run("saveprefs", hex(monet)).get("verified")), "Native appearance save migrates the old file");
        Map<?, ?> appearance = (Map<?, ?>) c.run("prefs").get("appearance");
        check("#A1B2C3".equals(appearance.get("customColour")) && Boolean.FALSE.equals(appearance.get("dynamicColour")), "Monet and manual colour persist with canonical hex");
        check(!Files.exists(legacy) && Files.isRegularFile(dir.resolve("appearance.json")) && f.calls == 0, "Verified save retires only the old appearance file");
        byte[] runtime = "runtime bytes must remain unchanged".getBytes(StandardCharsets.UTF_8); Files.write(dir.resolve("runtime.json"), runtime);
        for (String palette : new String[] {"VIOLET", "LAVENDER", "WISTERIA", "IRIS", "PLUM", "LILAC", "ORCHID", "MAUVE", "PERIWINKLE", "MINT", "BLUE", "ROSE", "SAGE", "CAFE", "SLATE"})
            check(Boolean.TRUE.equals(c.run("saveprefs", hex("{\"palette\":\"" + palette + "\",\"dynamicColour\":true,\"customColour\":\"\"}")).get("verified")), "Manual palette remains valid: " + palette);
        for (String colourStyle : new String[] {"EXPRESSIVE", "VIBRANT", "TONAL_SPOT", "NEUTRAL", "CMF"}) {
            String styled = "{\"style\":\"MIUIX\",\"palette\":\"VIOLET\",\"colourStyle\":\"" + colourStyle + "\",\"dynamicColour\":false,\"customColour\":\"#a1b2c3\"}";
            check(Boolean.TRUE.equals(c.run("saveprefs", hex(styled)).get("verified")), "Colour style can be saved without phone services: " + colourStyle);
            Map<?, ?> styledRead = (Map<?, ?>) c.run("prefs").get("appearance");
            check(colourStyle.equals(styledRead.get("colourStyle")) && "#A1B2C3".equals(styledRead.get("customColour")) && "VIOLET".equals(styledRead.get("palette")), "Colour style and seed choices survive canonical readback together");
        }
        check(Arrays.equals(runtime, Files.readAllBytes(dir.resolve("runtime.json"))) && !Files.exists(dir.resolve("state.properties")) && f.calls == 0, "Appearance writes never replace the runtime or SIM settings");
        byte[] current = Files.readAllBytes(dir.resolve("appearance.json")); Files.write(legacy, current);
        check(c.run("prefs").get("appearance") != null, "Identical duplicate appearance files are unambiguous");
        Files.write(legacy, "{\"glass\":true}".getBytes(StandardCharsets.UTF_8));
        rejects(c, "prefs"); rejects(c, "saveprefs", hex("{}"));
        check(Arrays.equals(current, Files.readAllBytes(dir.resolve("appearance.json"))) && Files.exists(legacy), "Conflicting duplicates are rejected before either file changes");
        Files.delete(legacy);
        for (String invalid : new String[] {"{\"dynamicColour\":1}", "{\"customColour\":\"red\"}", "{\"customColour\":\"#12345\"}", "{\"customColour\":\"#12345z\"}", "{\"palette\":\"OTHER\"}"}) rejects(c, "saveprefs", hex(invalid));
        for (String invalid : new String[] {"{\"colourStyle\":null}", "{\"colourStyle\":true}", "{\"colourStyle\":1}", "{\"colourStyle\":[\"EXPRESSIVE\"]}", "{\"colourStyle\":\"expressive\"}", "{\"colourStyle\":\"FIDELITY\"}", "{\"colourStyle\":\"EXPRESSIVE;$(id)\"}", "{\"colourStyle\":\"EXPRESSIVE\",\"colourStyle\":\"NEUTRAL\"}"}) {
            rejects(c, "saveprefs", hex(invalid));
            check(Arrays.equals(current, Files.readAllBytes(dir.resolve("appearance.json"))), "Invalid colour styles cannot replace the last saved appearance");
        }
        check(Arrays.equals(runtime, Files.readAllBytes(dir.resolve("runtime.json"))) && f.calls == 0, "Rejected colour-style input never reaches telephony or runtime publication");
    }
    private static void pathsLocksAndMigration() throws Exception {
        Fake f = new Fake(); Path dir = directory(); Path module = dir.getParent(); Controller c = controller(dir, f, "boot1");
        Files.write(module.resolve("module.prop"), "id=haaru\n".getBytes(StandardCharsets.UTF_8));
        check(Haaru.validateModule(module).equals(module), "Correct module metadata accepted");
        Path missing = module.resolve("not-installed");
        try { Haaru.validateModule(missing); throw new AssertionError(); } catch (IOException expected) { checks++; }
        check(!Files.exists(missing), "Missing module never reconstructed");
        Files.write(module.resolve("module.prop"), "id=another_module\n".getBytes(StandardCharsets.UTF_8));
        try { Haaru.validateModule(module); throw new AssertionError(); } catch (IOException expected) { checks++; }
        Files.write(module.resolve("remove"), new byte[0]);
        check(!Boolean.TRUE.equals(c.run("runtime").get("enabled")) && f.calls == 0 && !Files.exists(dir), "Removed module runtime stays empty without recreating data");
        check(c.run("prefs").get("appearance") == null && !Files.exists(dir), "Removed module prefs read never recreates data");
        for (String[] args : new String[][] {{"set", "0", "US", "-"}, {"enable"}, {"refresh"}, {"saveprefs", hex("{}")}}) rejects(c, args);
        Files.delete(module.resolve("remove")); c.run("set", "0", "US", "-");
        Files.write(module.resolve("disable"), new byte[0]);
        check(profiles(c.run("runtime")).isEmpty(), "Disabled module has no runtime profiles"); rejects(c, "set", "0", "JP", "-");
        Files.delete(module.resolve("disable"));
        try (FileChannel channel = FileChannel.open(dir.resolve("lock"), StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            rejects(c, "status");
        }
        Properties old = state(dir); old.remove("profile.0.enabled"); old.setProperty("enabled", "true");
        old.setProperty("baseline.0.country", "unsafe-old-snapshot"); old.setProperty("pending.0.country", "old"); old.setProperty("last.0.country", "old"); putState(dir, old);
        old.setProperty("targetPackages", "not even valid old JSON"); putState(dir, old);
        check(Boolean.TRUE.equals(profile(c.run("status"), 0).get("enabled")), "Global enabled migrates to per-card flags");
        c.run("refresh");
        check(!state(dir).containsKey("targetPackages") && "5".equals(state(dir).getProperty("schema")), "Version five removes legacy App filters without parsing them");
        for (String key : state(dir).stringPropertyNames()) check(!key.startsWith("baseline.") && !key.startsWith("pending.") && !key.startsWith("last."), "Legacy platform snapshots removed on save");
        old = state(dir); old.remove("profile.0.subId"); putState(dir, old);
        check(profiles(c.run("runtime")).isEmpty(), "Legacy profile without pinned subscription is not automatically activated");
        byte[] priorState = Files.readAllBytes(dir.resolve("state.properties"));
        Files.delete(dir.resolve("runtime.json")); Files.createDirectory(dir.resolve("runtime.json"));
        rejects(c, "set", "0", "JP", "-");
        check(Arrays.equals(priorState, Files.readAllBytes(dir.resolve("state.properties"))), "Invalid runtime destination cannot partially commit state");
        Path outside = Files.createTempDirectory("haaru-outside-"); Path linkedModule = Files.createTempDirectory("haaru-linked-");
        try {
            Files.createSymbolicLink(linkedModule.resolve("data"), outside);
            rejects(controller(linkedModule.resolve("data"), f, "boot1"), "set", "0", "US", "-");
            check(!Files.exists(outside.resolve("state.properties")), "Symlink cannot redirect module writes");
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", linkedModule.resolve("data").toString(), outside.toString())
                        .redirectErrorStream(true).start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024]; int length;
                try (InputStream in = process.getInputStream()) { while ((length = in.read(chunk)) != -1) output.write(chunk, 0, length); }
                if (process.waitFor() != 0) throw new AssertionError("Could not create Windows junction fixture");
                rejects(controller(linkedModule.resolve("data"), f, "boot1"), "set", "0", "US", "-");
                check(!Files.exists(outside.resolve("state.properties")), "Junction cannot redirect module writes");
            } else throw unavailable;
        }
    }
    private static void strictJson() {
        check(Json.encode(Json.decode("{\"q\":\"\\\"\\\\\\n\",\"a\":[true,false,null,12]}")).equals("{\"q\":\"\\\"\\\\\\n\",\"a\":[true,false,null,12]}"), "JSON escapes roundtrip");
        for (String invalid : Arrays.asList("{}x", "[01]", "[1.]", "[+1]", "[NaN]", "[1,]", "{\"a\":1,\"a\":2}", "\"\\ud800\"", "\"a\nb\"", "[1１]")) {
            try { Json.decode(invalid); throw new AssertionError("Invalid JSON accepted: " + invalid); } catch (IllegalArgumentException expected) { checks++; }
        }
        for (java.lang.reflect.Method method : Backend.class.getMethods())
            check(!method.getName().equals("write") && !method.getName().startsWith("set"), "Backend interface is read-only");
        for (java.lang.reflect.Method method : AndroidBackend.class.getDeclaredMethods())
            check(!method.getName().equals("write"), "Android backend has no setter method");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> engine(Map<String, Object> status) { return (Map<String, Object>) status.get("globalEngine"); }
    private static Map<String, Object> ack(String hash) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true); out.put("initialized", true); out.put("applied", true);
        out.put("hooks", 14); out.put("sdk", 37); out.put("bootId", "boot1"); out.put("configHash", hash); out.put("error", "");
        out.put("requiredHooks", 14); out.put("coverageComplete", true); out.put("supportsCountry", true); out.put("supportsCarrierName", true);
        out.put("timestampMs", System.currentTimeMillis()); out.put("phonePid", 1234);
        return out;
    }
    private static void writeAck(Path dir, Map<String, Object> ack) throws Exception {
        Files.write(dir.resolve("global-status.json"), Json.encode(ack).getBytes(StandardCharsets.UTF_8));
    }
    private static String independentHash(byte[] bytes) {
        java.math.BigInteger hash = new java.math.BigInteger("14695981039346656037");
        java.math.BigInteger prime = new java.math.BigInteger("1099511628211"), mask = java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE);
        for (byte value : bytes) hash = hash.xor(java.math.BigInteger.valueOf(value & 255)).multiply(prime).and(mask);
        return hash.toString();
    }
    private static void globalEngineAcknowledgment() throws Exception {
        Fake f = new Fake(); Path dir = directory(); Controller c = controller(dir, f, "boot1");
        Map<String, Object> saved = c.run("set", "0", "US", "-");
        check(Boolean.TRUE.equals(saved.get("verified")) && !Boolean.TRUE.equals(engine(saved).get("applied")), "File verification never claims global application without engine ACK");
        check(Boolean.TRUE.equals(saved.get("global")) && !saved.containsKey("targetPackages"), "Status is global rather than selected-App scope");
        check(!Files.exists(dir.resolve("global-status.json")), "Controller does not invent a phone-engine ACK");
        String hash = (String) saved.get("currentConfigHash");
        check(independentHash(Files.readAllBytes(dir.resolve("runtime.json"))).equals(hash), "ACK matching hash is unsigned FNV-1a64 of the exact native cache bytes");
        writeAck(dir, ack(hash)); Map<String, Object> status = c.run("status");
        check(Boolean.TRUE.equals(engine(status).get("applied")) && Boolean.TRUE.equals(engine(status).get("initialized")), "Current engine ACK confirms application separately from file saves");
        check(((Number) engine(status).get("hooks")).intValue() == 14 && ((Number) engine(status).get("sdk")).intValue() == 37, "Complete central-plus-bootstrap hook count preserved");
        for (int count : new int[] {3, 9, 17}) {
            Map<String, Object> adaptive = ack(hash); adaptive.put("hooks", count); adaptive.put("requiredHooks", count); writeAck(dir, adaptive);
            check(Boolean.TRUE.equals(engine(c.run("status")).get("applied")), "Discovered complete coverage is independent of a fixed SDK hook count");
        }
        Map<String, Object> twoHooks = ack(hash); twoHooks.put("hooks", 2); twoHooks.put("requiredHooks", 2); writeAck(dir, twoHooks);
        check(Boolean.FALSE.equals(engine(c.run("status")).get("initialized")), "Both reader categories and attach cannot be proven by only two hooks");
        writeAck(dir, ack(hash));
        check("boot1".equals(engine(status).get("bootId")) && hash.equals(engine(status).get("configHash")), "Current boot and configuration metadata preserved");
        check(((Number) engine(status).get("phonePid")).intValue() == 1234
                && ((Number) engine(status).get("timestampMs")).longValue() > 0, "Current phone generation and ACK time preserved");
        status = c.run("set", "0", "JP", "-");
        check(Boolean.TRUE.equals(status.get("verified")) && !Boolean.TRUE.equals(engine(status).get("applied")), "New plan cannot reuse old configuration ACK");
        check(Boolean.TRUE.equals(engine(status).get("initialized")), "Old hash leaves the service engine initialised while new configuration is pending");
        Map<String, Object> previousFailure = ack("old-hash");
        previousFailure.put("ok", false); previousFailure.put("applied", false); previousFailure.put("error", "Original SIM journal recovery failed");
        writeAck(dir, previousFailure);
        check("Original SIM journal recovery failed".equals(engine(c.run("status")).get("error")), "Hash mismatch preserves a specific native recovery error");
        hash = (String) status.get("currentConfigHash");
        for (Map<String, Object> stale : Arrays.asList(
                Collections.<String, Object>singletonMap("timestampMs", System.currentTimeMillis() - 11000L),
                Collections.<String, Object>singletonMap("timestampMs", System.currentTimeMillis() + 5000L),
                Collections.<String, Object>singletonMap("timestampMs", 0L),
                Collections.<String, Object>singletonMap("phonePid", 0),
                Collections.<String, Object>singletonMap("phonePid", -1))) {
            Map<String, Object> staleAck = ack(hash); staleAck.putAll(stale); writeAck(dir, staleAck); status = c.run("status");
            check(Boolean.TRUE.equals(status.get("ok")) && Boolean.FALSE.equals(engine(status).get("applied"))
                    && Boolean.FALSE.equals(engine(status).get("initialized")), "Old, future or invalid phone-generation ACK cannot remain globally active");
        }
        for (String missing : Arrays.asList("timestampMs", "phonePid")) {
            Map<String, Object> missingAck = ack(hash); missingAck.remove(missing); writeAck(dir, missingAck);
            check(!Boolean.TRUE.equals(engine(c.run("status")).get("applied")), "Missing ACK freshness metadata is not current engine confirmation");
        }
        Map<String, Object> disconnected = ack(hash);
        disconnected.remove("sdk"); disconnected.remove("timestampMs"); disconnected.remove("phonePid");
        disconnected.put("error", "Phone engine disconnected"); disconnected.put("stage", "application-attach");
        writeAck(dir, disconnected); Map<String, Object> failed = engine(c.run("status"));
        check("Phone engine disconnected".equals(failed.get("error")), "Incomplete disconnect ACK preserves native failure instead of masking it with missing SDK");
        check("application-attach".equals(failed.get("stage")) && ((String) failed.get("validationError")).contains("sdk"), "Native failure stage and ACK validation failure remain independently readable");
        check(Boolean.FALSE.equals(failed.get("ok")) && Boolean.FALSE.equals(failed.get("initialized"))
                && Boolean.FALSE.equals(failed.get("applied")), "Incomplete failure ACK can never become success");
        disconnected = ack(hash); disconnected.put("bootId", "old-boot"); disconnected.put("error", "DEX trust denied");
        writeAck(dir, disconnected); failed = engine(c.run("status"));
        check("DEX trust denied".equals(failed.get("error")) && ((String) failed.get("validationError")).contains("different boot")
                && Boolean.FALSE.equals(failed.get("initialized")), "Wrong-boot native failure is preserved without accepting the engine generation");
        Map<String, Object> ack = ack(hash); ack.put("bootId", "old-boot"); writeAck(dir, ack);
        check(!Boolean.TRUE.equals(engine(c.run("status")).get("initialized")), "Prior boot ACK is not a current engine");
        ack = ack(hash); ack.put("ok", false); ack.put("initialized", false); ack.put("applied", false); ack.put("error", "service hook denied"); writeAck(dir, ack);
        status = c.run("status");
        check(Boolean.TRUE.equals(status.get("ok")) && "service hook denied".equals(engine(status).get("error")), "Engine error stays local and does not crash controller status");
        for (Map<String, Object> contradiction : Arrays.asList(
                Collections.<String, Object>singletonMap("initialized", false), Collections.<String, Object>singletonMap("ok", false),
                Collections.<String, Object>singletonMap("hooks", 0), Collections.<String, Object>singletonMap("hooks", 13),
                Collections.<String, Object>singletonMap("requiredHooks", 0), Collections.<String, Object>singletonMap("coverageComplete", false),
                Collections.<String, Object>singletonMap("supportsCountry", false),
                Collections.<String, Object>singletonMap("sdk", 28))) {
            ack = ack(hash); ack.putAll(contradiction); writeAck(dir, ack);
            check(!Boolean.TRUE.equals(engine(c.run("status")).get("applied")), "Uninitialised or unsupported service engine cannot confirm global publication");
        }
        for (Map<String, Object> invalid : Arrays.asList(
                Collections.<String, Object>singletonMap("ok", "true"), Collections.<String, Object>singletonMap("hooks", Arrays.asList(1, 2)),
                Collections.<String, Object>singletonMap("sdk", 37.5), Collections.<String, Object>singletonMap("sdk", -1),
                Collections.<String, Object>singletonMap("configHash", 123), Collections.<String, Object>singletonMap("bootId", null))) {
            ack = ack(hash); ack.putAll(invalid); writeAck(dir, ack); status = c.run("status");
            check(Boolean.TRUE.equals(status.get("ok")) && !Boolean.TRUE.equals(engine(status).get("applied")), "Malformed engine field cannot break status or claim global application");
        }
        for (byte[] invalid : new byte[][] {"[]".getBytes(StandardCharsets.UTF_8), "{invalid}".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xc0, (byte) 0xaf}, new byte[65537]}) {
            Files.write(dir.resolve("global-status.json"), invalid); status = c.run("status");
            check(Boolean.TRUE.equals(status.get("ok")) && !Boolean.TRUE.equals(engine(status).get("initialized")), "Invalid UTF-8, JSON or oversized ACK is isolated");
        }
        Files.delete(dir.resolve("global-status.json")); Files.createDirectory(dir.resolve("global-status.json"));
        check(Boolean.TRUE.equals(c.run("status").get("ok")), "Non-file ACK path cannot crash system status");
        Files.delete(dir.resolve("global-status.json"));
        c.run("clear"); Map<String, Object> runtime = c.run("runtime");
        check(Boolean.TRUE.equals(runtime.get("global")) && ((Number) runtime.get("protocol")).intValue() == 2
                && Boolean.FALSE.equals(runtime.get("enabled")) && profiles(runtime).isEmpty(), "Empty plans still publish the global engine protocol for phone bootstrap");
        hash = (String) c.run("status").get("currentConfigHash");
        ack = ack(hash); ack.put("applied", false); writeAck(dir, ack);
        status = c.run("status");
        check(Boolean.TRUE.equals(engine(status).get("initialized")) && Boolean.FALSE.equals(engine(status).get("applied")), "Phone engine can remain initialised with no active profiles");
        Files.write(dir.getParent().resolve("disable"), new byte[0]);
        check(c.run("status").containsKey("globalEngine") && Boolean.TRUE.equals(c.run("runtime").get("readOnly")), "Disabled module still reports engine metadata and inactive global runtime");
        unchanged(f);
    }
    private static void upgradeStatusAndSchemas() throws Exception {
        Fake f = new Fake(); Path dir = directory(); Path module = dir.getParent(); Controller c = controller(dir, f, "boot1");
        Files.write(module.resolve("module.prop"), "id=haaru\nversion=0.5.1\nversionCode=7\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> status = c.run("set", "0", "US", "-");
        check("0.5.1".equals(status.get("moduleVersion")) && ((Number) status.get("moduleVersionCode")).intValue() == 7, "Actual installed module version is returned");
        check(Boolean.FALSE.equals(status.get("upgradePending")), "Normal installed version has no pending update");
        byte[] before = Files.readAllBytes(dir.resolve("state.properties")); Files.write(module.resolve("update"), new byte[0]);
        status = c.run("status");
        check(Boolean.TRUE.equals(status.get("upgradePending")) && Boolean.TRUE.equals(status.get("readOnly")), "Staged update requires restart before mutations");
        check(!((List<?>) status.get("warnings")).isEmpty() && profiles(c.run("runtime")).isEmpty(), "Pending update warns and suspends runtime profiles");
        for (String[] args : new String[][] {{"set", "0", "JP", "-"}, {"reset", "0"}, {"forget", "0"}, {"disable"}, {"clear"}, {"enable"}, {"refresh"}, {"saveprefs", hex("{}")}})
            rejects(c, args);
        check(Arrays.equals(before, Files.readAllBytes(dir.resolve("state.properties"))), "Pending update never changes captured saved settings");
        check(c.run("prefs").get("appearance") == null, "Preferences remain readable pending update"); Files.delete(module.resolve("update"));
        for (String schema : Arrays.asList("6", "future", "05", "3")) {
            Properties p = state(dir); p.setProperty("schema", schema); putState(dir, p);
            byte[] prior = Files.readAllBytes(dir.resolve("state.properties")); rejects(c, "status"); rejects(c, "clear");
            check(Arrays.equals(prior, Files.readAllBytes(dir.resolve("state.properties"))), "Incompatible schema is never rewritten by status or mutation");
        }
        Path missing = directory(); Map<String, Object> fallback = controller(missing, new Fake(), "boot1").run("status");
        check("".equals(fallback.get("moduleVersion")) && ((Number) fallback.get("moduleVersionCode")).intValue() == 0, "Missing optional fixture metadata uses harmless version defaults");
    }
    public static void main(String[] args) throws Exception {
        inputsAndMerge(); isolationAndIdentity(); appearanceWithoutApplicationRouting(); pathsLocksAndMigration(); globalEngineAcknowledgment(); strictJson(); upgradeStatusAndSchemas(); nativeAppearanceMigrationAndMonet();
        System.out.println("Controller v5: " + checks + " global-protocol/module-file checks passed");
    }
}
