package com.github.haaru.ksu;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.github.haaru.ksu.Backend.Subscription;
import com.github.haaru.ksu.Backend.Values;

/** Module-local configuration for the system-global volatile engine. No platform setters. */
public final class Controller {
    private static final String STALE_SIM_SELECTION = "SIM changed since status was read";
    private final Path dir, module;
    private final Backend backend;
    private final String boot;
    private Properties state = new Properties();
    private final List<String> warnings = new ArrayList<String>();
    private final Map<Integer, String> issues = new TreeMap<Integer, String>();
    private final Map<Integer, Values> values = new HashMap<Integer, Values>();
    private final Map<Integer, String> countries = new HashMap<Integer, String>();
    private final Map<Integer, String> originalCarriers = new HashMap<Integer, String>();
    private List<Subscription> subs = new ArrayList<Subscription>();
    private int defaultSubId = -1;
    private boolean readOnly;
    private boolean upgradePending;
    private String moduleVersion = "";
    private int moduleVersionCode;

    // timeoutMs retained for source compatibility; verification is local file readback.
    public Controller(Path dir, Backend backend, String boot, long timeoutMs) {
        this.dir = dir.toAbsolutePath().normalize(); this.module = this.dir.getParent();
        if (module == null) throw new IllegalArgumentException("Missing module directory");
        this.backend = backend; this.boot = boot;
    }

    public Map<String, Object> run(String... args) throws Exception {
        String op = args.length == 0 ? "status" : args[0]; validateArguments(op, args);
        warnings.clear(); issues.clear(); values.clear(); countries.clear(); originalCarriers.clear();
        subs = new ArrayList<Subscription>(); defaultSubId = -1;
        safePath(module);
        if (!Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Module directory is absent");
        safePath(dir);
        readLifecycle(); readModuleVersion(); checkMutableOperation(op);
        // Preferences use one atomic file, without state or Binder access.
        if ("prefs".equals(op) || "saveprefs".equals(op)) return preferences(op, args);
        if (readOnly && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            load();
            if ("runtime".equals(op)) return runtime();
            if ("status".equals(op)) { readPhone(); runtime(); return status(); }
            throw new IOException("Inactive module has no data directory");
        }
        Files.createDirectories(dir); safePath(dir);
        Path lockPath = safePath(dir.resolve("lock"));
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Another operation is in progress"); }
            if (lock == null) throw new IOException("Another operation is in progress");
            try {
                readLifecycle(); checkMutableOperation(op);
                load();
                if ("set".equals(op)) { readPhone(); configure(args); }
                else if ("reset".equals(op) || "forget".equals(op)) {
                    int slot = parseSlot(args[1]);
                    if ("forget".equals(op)) removePrefix("profile." + slot + ".");
                    else setProfileEnabled(slot, false);
                } else if ("disable".equals(op)) setAllEnabled(false);
                else if ("clear".equals(op)) removePrefix("profile.");
                else if ("enable".equals(op)) {
                    if (profileSlots().isEmpty()) throw new IllegalStateException("No saved profiles");
                    setAllEnabled(true);
                } else if ("refresh".equals(op) && !enabled())
                    throw new IllegalStateException("No enabled profiles to refresh");
                if (!"set".equals(op) && !("runtime".equals(op) && readOnly)) readPhone();
                Map<String, Object> runtime = runtime();
                if ("runtime".equals(op)) return runtime;
                boolean mutation = !"status".equals(op);
                if (mutation) save(runtime);
                else publishStatusRuntime(runtime);
                Map<String, Object> out = status(); out.put("verified", mutation);
                out.put("verification", mutation ? "module-files" : "read-only"); return out;
            } finally { lock.release(); }
        }
    }

    private void readLifecycle() throws IOException {
        upgradePending = marked("update");
        readOnly = marked("remove") || marked("disable") || upgradePending;
        if (upgradePending && !warnings.contains("A module update is pending; restart the phone to finish updating"))
            warnings.add("A module update is pending; restart the phone to finish updating");
    }
    private void checkMutableOperation(String op) throws IOException {
        if (upgradePending && !Arrays.asList("status", "runtime", "prefs").contains(op))
            throw new IOException("A module update is pending; restart before changing settings");
        if (readOnly && Arrays.asList("set", "enable", "refresh", "saveprefs").contains(op))
            throw new IOException(upgradePending ? "A module update is pending; restart before changing settings"
                    : "Module is disabled or scheduled for removal; configuration is read-only");
    }
    private void readModuleVersion() {
        moduleVersion = ""; moduleVersionCode = 0;
        try {
            Path path = safePath(module.resolve("module.prop"));
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            Properties metadata = new Properties();
            metadata.load(new ByteArrayInputStream(readLimited(path, 16384)));
            if (!"haaru".equals(metadata.getProperty("id"))) return;
            String version = metadata.getProperty("version", "");
            if (version.length() <= 128 && !version.matches("(?s).*[\\p{Cntrl}].*")) moduleVersion = version;
            String code = metadata.getProperty("versionCode", "");
            if (code.matches("[0-9]{1,10}")) moduleVersionCode = Integer.parseInt(code);
        } catch (Exception ignored) { moduleVersionCode = 0; }
    }

    private static void validateArguments(String op, String[] args) {
        if (!Arrays.asList("status", "runtime", "set", "reset", "forget", "enable", "disable",
                "refresh", "clear", "prefs", "saveprefs").contains(op))
            throw new IllegalArgumentException("Unknown operation");
        int length = args.length == 0 ? 1 : args.length;
        if ("set".equals(op)) {
            if (length != 4 && length != 5)
                throw new IllegalArgumentException("Usage: set SLOT ISO_OR_DASH CARRIER_UTF8_HEX_OR_DASH [SELECTION_TOKEN]");
            parseSlot(args[1]);
            if (length == 5 && !CompanionProtocol.validSelectionToken(args[4]))
                throw new IllegalArgumentException("Invalid SIM selection token");
        } else if ("reset".equals(op) || "forget".equals(op)) {
            if (length != 2) throw new IllegalArgumentException("Usage: " + op + " SLOT"); parseSlot(args[1]);
        } else if ("saveprefs".equals(op)) {
            if (length != 2) throw new IllegalArgumentException("Usage: saveprefs UTF8_JSON_HEX");
        } else if (length != 1) throw new IllegalArgumentException("Unexpected arguments");
    }

    private void configure(String[] args) throws Exception {
        int slot = parseSlot(args[1]);
        String country = "-".equals(args[2]) ? "" : args[2].toUpperCase(Locale.ROOT);
        if (!country.isEmpty() && (!country.matches("[A-Z]{2}")
                || !Arrays.asList(Locale.getISOCountries()).contains(country)))
            throw new IllegalArgumentException("Use a valid two-letter ISO country or '-'");
        String carrier = decodeCarrier(args[3]);
        if (country.isEmpty() && carrier.isEmpty()) throw new IllegalArgumentException("Specify a country or carrier name");
        Subscription sub = find(slot);
        if (args.length == 5 && (sub == null || !selectionToken(sub).equals(args[4])))
            throw new IOException(STALE_SIM_SELECTION);
        if (sub == null) throw new IllegalStateException("No unambiguous active SIM in selected slot");
        Values current = values.get(sub.subId);
        if (current == null || !current.ready || issues.containsKey(slot))
            throw new IllegalStateException("Carrier configuration is not loaded for selected SIM");
        for (Subscription active : validSubscriptions(backend.subscriptions())) {
            if (active.slot == sub.slot && active.subId == sub.subId && active.identity.equals(sub.identity)) {
                String p = "profile." + slot + ".";
                // Omitted fields merge only into an active profile belonging to this same SIM.
                if (profileEnabled(slot) && matchesCurrentSim(sub)) {
                    if (country.isEmpty()) country = state.getProperty(p + "country", "");
                    if (carrier.isEmpty()) carrier = state.getProperty(p + "carrier", "");
                }
                state.setProperty(p + "country", country.toLowerCase(Locale.ROOT));
                state.setProperty(p + "carrier", carrier); state.setProperty(p + "subId", String.valueOf(sub.subId));
                state.setProperty(p + "identity", sub.identity); state.setProperty(p + "confirmedBoot", boot);
                state.setProperty(p + "enabled", "true"); state.remove(p + "error"); return;
            }
        }
        throw new IOException(args.length == 5 ? STALE_SIM_SELECTION : "SIM changed during operation");
    }

    private String selectionToken(Subscription sub) {
        // Frame the fields as a JSON array before hashing. The underlying identity stays in the module.
        String selection = Json.encode(Arrays.asList("haaru-sim-selection-v1", boot, sub.slot, sub.subId, sub.identity));
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(selection.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        StringBuilder token = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int value = item & 0xff;
            token.append(Character.forDigit(value >>> 4, 16));
            token.append(Character.forDigit(value & 15, 16));
        }
        return token.toString();
    }

    public static String decodeCarrier(String encoded) throws Exception {
        if ("-".equals(encoded)) return "";
        String carrier = decodeHex(encoded, 320);
        if (carrier.isEmpty() || carrier.codePointCount(0, carrier.length()) > 80
                || carrier.matches("(?s).*[\\p{Cntrl}].*"))
            throw new IllegalArgumentException("Carrier name must be at most 80 characters without control characters");
        return carrier;
    }
    private static String decodeHex(String encoded, int maxBytes) throws Exception {
        if (encoded == null || encoded.length() > maxBytes * 2 || encoded.length() % 2 != 0
                || !encoded.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("Invalid UTF-8 hex encoding");
        byte[] data = new byte[encoded.length() / 2];
        for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(encoded.substring(i * 2, i * 2 + 2), 16);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
    }

    private void readPhone() {
        try { subs = validSubscriptions(backend.subscriptions()); }
        catch (Exception e) { warnings.add("Subscription read unavailable: " + message(e)); return; }
        try { defaultSubId = backend.defaultSubId(); }
        catch (Exception e) { warnings.add("Default subscription unavailable; unscoped getters remain unchanged"); }
        for (Subscription sub : subs) {
            try { originalCarriers.put(sub.subId, backend.originalCarrierName(sub.subId)); }
            catch (Exception e) { warnings.add("Slot " + sub.slot + ": original carrier label unavailable: " + message(e)); }
            try {
                Values v = backend.read(sub.subId);
                if (v == null) throw new IOException("No carrier configuration returned");
                values.put(sub.subId, v); countries.put(sub.subId, normalize(backend.simCountry(sub.subId)));
            } catch (Exception e) { issues.put(sub.slot, message(e)); }
        }
    }
    private List<Subscription> validSubscriptions(List<Subscription> input) throws IOException {
        if (input == null) throw new IOException("Subscriptions not ready");
        Map<Integer, Integer> slotCounts = new HashMap<Integer, Integer>(), idCounts = new HashMap<Integer, Integer>();
        List<Subscription> valid = new ArrayList<Subscription>();
        for (Subscription sub : input) {
            if (sub == null || sub.slot < 0 || sub.slot > 7 || sub.subId < 0 || sub.identity == null || sub.identity.isEmpty()) continue;
            valid.add(sub);
            slotCounts.put(sub.slot, slotCounts.containsKey(sub.slot) ? slotCounts.get(sub.slot) + 1 : 1);
            idCounts.put(sub.subId, idCounts.containsKey(sub.subId) ? idCounts.get(sub.subId) + 1 : 1);
        }
        List<Subscription> result = new ArrayList<Subscription>();
        for (Subscription sub : valid) {
            if (slotCounts.get(sub.slot) != 1 || idCounts.get(sub.subId) != 1) {
                String warning = "Slot " + sub.slot + ": ambiguous subscription mapping; skipped";
                if (!warnings.contains(warning)) warnings.add(warning);
            } else result.add(sub);
        }
        Collections.sort(result, new Comparator<Subscription>() {
            public int compare(Subscription a, Subscription b) { return Integer.compare(a.slot, b.slot); }
        }); return result;
    }

    private Map<String, Object> runtime() throws Exception {
        Map<String, Object> out = base(true, false);
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
        if (readOnly) warnings.add("Module disabled or scheduled for removal; global profiles are inactive");
        else for (int slot : profileSlots()) {
            if (!profileEnabled(slot)) continue;
            Subscription sub = find(slot); String error = null;
            if (sub == null) error = "Saved SIM is absent or ambiguous";
            else if (!matches(sub)) error = "Subscription ID or SIM identity changed; configure this SIM explicitly";
            else if (sub.identity.startsWith("weak:") && !boot.equals(state.getProperty("profile." + slot + ".confirmedBoot")))
                error = "Weak SIM identity requires confirmation after reboot";
            else if (issues.containsKey(slot)) error = issues.get(slot);
            else if (!values.containsKey(sub.subId) || !values.get(sub.subId).ready) error = "Carrier configuration is not loaded";
            if (error != null) { issues.put(slot, error); warnings.add("Slot " + slot + ": " + error); continue; }
            String p = "profile." + slot + ".";
            Map<String, Object> profile = new LinkedHashMap<String, Object>();
            profile.put("slot", slot); profile.put("subId", sub.subId);
            profile.put("country", state.getProperty(p + "country", ""));
            profile.put("carrierName", state.getProperty(p + "carrier", ""));
            profile.put("weakIdentity", sub.identity.startsWith("weak:"));
            profile.put("confirmedBoot", state.getProperty(p + "confirmedBoot", "")); profiles.add(profile);
        }
        out.put("protocol", 2); out.put("engine", "system-global-volatile"); out.put("global", true);
        out.put("bootId", boot); out.put("enabled", !profiles.isEmpty());
        out.put("profiles", profiles); out.put("defaultSubId", defaultSubId);
        out.put("warnings", new ArrayList<String>(warnings)); out.put("readOnly", readOnly); return out;
    }

    private Map<String, Object> status() throws Exception {
        Map<String, Object> out = base(true, false);
        out.put("enabled", !readOnly && enabled()); out.put("bootId", boot); out.put("readOnly", readOnly);
        out.put("engine", "system-global-volatile"); out.put("global", true); out.put("planned", true);
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        for (Subscription sub : subs) {
            Map<String, Object> card = new LinkedHashMap<String, Object>();
            card.put("slot", sub.slot); card.put("subId", sub.subId);
            card.put("selectionToken", selectionToken(sub));
            Values v = values.get(sub.subId);
            if (v != null) {
                card.put("configCountry", v.country); card.put("carrierName", v.carrierName);
                card.put("carrierOverride", v.carrierOverride); card.put("ready", v.ready);
            } else card.put("ready", false);
            card.put("country", countries.get(sub.subId));
            card.put("originalCarrierName", originalCarriers.get(sub.subId));
            if (countries.get(sub.subId) == null && issues.containsKey(sub.slot)) card.put("error", issues.get(sub.slot));
            cards.add(card);
        }
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
        for (int slot : profileSlots()) {
            String p = "profile." + slot + ".";
            String country = state.getProperty(p + "country", ""), carrier = state.getProperty(p + "carrier", "");
            Map<String, Object> profile = new LinkedHashMap<String, Object>();
            profile.put("slot", slot); profile.put("country", country); profile.put("carrierName", carrier);
            profile.put("enabled", profileEnabled(slot)); profile.put("countryOverride", !country.isEmpty());
            profile.put("carrierOverride", !carrier.isEmpty());
            Subscription current = find(slot);
            profile.put("matchesCurrentSim", current != null && matchesCurrentSim(current));
            if (issues.containsKey(slot)) profile.put("error", issues.get(slot)); profiles.add(profile);
        }
        out.put("subscriptions", cards); out.put("profiles", profiles);
        out.put("currentConfigHash", currentConfigHash()); out.put("globalEngine", globalEngine());
        out.put("companionInstall", CompanionInstallation.readStatus(module));
        out.put("warnings", new ArrayList<String>(warnings)); return out;
    }

    private Map<String, Object> preferences(String op, String[] args) throws Exception {
        Path path = safePath(dir.resolve("appearance.json"));
        Path legacy = safePath(module.resolve("webroot/appearance.json"));
        byte[] current = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? readLimited(path, 2048) : null;
        byte[] previous = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) ? readLimited(legacy, 2048) : null;
        if (current != null && previous != null && !Arrays.equals(current, previous))
            throw new IOException("Conflicting appearance files; settings were not changed");
        byte[] selected = current != null ? current : previous;
        Object appearance = selected == null ? null : validateAppearance(Json.decode(decodeAppearance(selected)));
        if ("saveprefs".equals(op)) {
            appearance = validateAppearance(Json.decode(decodeHex(args[1], 2048)));
            safePath(path.getParent()); Files.createDirectories(path.getParent());
            byte[] saved = Json.encode(appearance).getBytes(StandardCharsets.UTF_8);
            atomicWrite(path, saved);
            try {
                if (!Arrays.equals(saved, readLimited(path, 2048))) throw new IOException("Appearance save was not verified");
                if (previous != null) {
                    if (!Arrays.equals(previous, readLimited(legacy, 2048))) throw new IOException("Legacy appearance changed during migration");
                    Files.delete(legacy);
                }
            } catch (Exception failure) {
                // Retire the old file only after a verified save; preserve the prior choice on failure.
                if (Arrays.equals(saved, readLimited(path, 2048))) {
                    if (current == null) Files.deleteIfExists(path); else atomicWrite(path, current);
                }
                throw failure;
            }
        }
        Map<String, Object> out = base(true, "saveprefs".equals(op));
        out.put("appearance", appearance); out.put("readOnly", readOnly);
        if ("saveprefs".equals(op)) out.put("verification", "module-files"); return out;
    }
    static Map<?, ?> validateAppearance(Object input) {
        if (!(input instanceof Map)) throw new IllegalArgumentException("Appearance must be a JSON object");
        Map<?, ?> appearance = (Map<?, ?>) input;
        for (Map.Entry<?, ?> entry : appearance.entrySet()) {
            String key = String.valueOf(entry.getKey()); Object value = entry.getValue(); boolean valid;
            if ("style".equals(key)) valid = Arrays.asList("MIUIX", "MATERIAL3").contains(value);
            else if ("appearance".equals(key)) valid = Arrays.asList("SYSTEM", "LIGHT", "DARK").contains(value);
            else if ("palette".equals(key)) valid = Arrays.asList("VIOLET", "LAVENDER", "WISTERIA", "IRIS", "PLUM", "LILAC", "ORCHID", "MAUVE", "PERIWINKLE", "MINT", "BLUE", "ROSE", "SAGE", "CAFE", "SLATE").contains(value);
            else if ("colourStyle".equals(key)) valid = Arrays.asList("EXPRESSIVE", "VIBRANT", "TONAL_SPOT", "NEUTRAL", "CMF").contains(value);
            else if ("glass".equals(key) || "reduceMotion".equals(key) || "dynamicColour".equals(key)) valid = value instanceof Boolean;
            else if ("customColour".equals(key)) valid = value instanceof String && ((String) value).matches("|#[0-9A-Fa-f]{6}");
            else valid = false;
            if (!valid) throw new IllegalArgumentException("Unknown or invalid appearance field: " + key);
        }
        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : appearance.entrySet()) {
            Object value = entry.getValue();
            if ("customColour".equals(entry.getKey())) value = ((String) value).toUpperCase(Locale.ROOT);
            canonical.put((String) entry.getKey(), value);
        }
        return canonical;
    }
    private static String decodeAppearance(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** A malformed/stale engine ACK is a local status issue, never a controller failure. */
    private Map<String, Object> globalEngine() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false); out.put("initialized", false); out.put("applied", false);
        out.put("hooks", 0); out.put("sdk", 0); out.put("bootId", ""); out.put("configHash", "");
        out.put("requiredHooks", 0); out.put("coverageComplete", false);
        out.put("supportsCountry", false); out.put("supportsCarrierName", false);
        out.put("timestampMs", 0L); out.put("phonePid", 0);
        out.put("error", "Global engine has not acknowledged a configuration");
        out.put("stage", ""); out.put("validationError", "");
        try {
            Path path = safePath(dir.resolve("global-status.json"));
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return out;
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(readLimited(path, 65536))).toString();
            Object parsed = Json.decode(text);
            if (!(parsed instanceof Map)) throw new IOException("Global engine ACK must be an object");
            Map<?, ?> ack = (Map<?, ?>) parsed;
            // Preserve the native failure before checking generation metadata. Older
            // disconnect ACKs omit SDK/time/PID; that must not hide the actual cause.
            String error = ack.get("error") == null ? "" : ackString(ack, "error", 500);
            out.put("error", error);
            if (ack.get("stage") != null) out.put("stage", ackString(ack, "stage", 96));
            boolean ok = ackBoolean(ack, "ok"), initialized = ackBoolean(ack, "initialized"), applied = ackBoolean(ack, "applied");
            int hooks = ackInteger(ack, "hooks"), sdk = ackInteger(ack, "sdk");
            int phonePid = ackInteger(ack, "phonePid");
            int requiredHooks = ackInteger(ack, "requiredHooks");
            boolean coverage = ackBoolean(ack, "coverageComplete"), countrySupport = ackBoolean(ack, "supportsCountry"), nameSupport = ackBoolean(ack, "supportsCarrierName");
            long timestamp = ackLong(ack, "timestampMs"), now = System.currentTimeMillis();
            String ackBoot = ackString(ack, "bootId", 128), hash = ackString(ack, "configHash", 128);
            out.put("ok", ok); out.put("initialized", initialized); out.put("applied", applied);
            out.put("hooks", hooks); out.put("sdk", sdk); out.put("bootId", ackBoot); out.put("configHash", hash); out.put("error", error);
            out.put("timestampMs", timestamp); out.put("phonePid", phonePid);
            out.put("requiredHooks", requiredHooks); out.put("coverageComplete", coverage);
            out.put("supportsCountry", countrySupport); out.put("supportsCarrierName", nameSupport);
            out.put("updatedAtMs", Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
            if (phonePid <= 0 || timestamp <= 0 || timestamp < now - 10000L || timestamp > now + 1000L) {
                out.put("ok", false); out.put("initialized", false); out.put("applied", false);
                ackFailure(out, "Global engine ACK is stale or has an invalid phone generation");
            } else if (!boot.equals(ackBoot)) {
                out.put("ok", false); out.put("initialized", false); out.put("applied", false);
                ackFailure(out, "Global engine ACK belongs to a different boot");
            } else if (initialized && (!coverage || requiredHooks < 3 || hooks != requiredHooks || sdk < 29)) {
                out.put("ok", false); out.put("initialized", false); out.put("applied", false);
                ackFailure(out, "Not every discovered carrier/subscription reader is covered");
            } else if (!hash.equals(currentConfigHash()) || hash.isEmpty()) {
                out.put("applied", false);
                ackFailure(out, "Global engine has not acknowledged the current module configuration");
            } else if (applied && (!ok || !initialized || !runtimeFieldsSupported(countrySupport, nameSupport))) {
                out.put("ok", false); out.put("applied", false);
                ackFailure(out, "Global application is not confirmed by an initialised service engine");
            }
        } catch (Exception e) {
            String error = "Global engine ACK unavailable: " + message(e);
            out.put("ok", false); out.put("initialized", false); out.put("applied", false); ackFailure(out, error);
            warnings.add(error);
        }
        return out;
    }
    private boolean runtimeFieldsSupported(boolean country, boolean name) throws Exception {
        Object decoded = Json.decode(new String(readLimited(safePath(dir.resolve("runtime.json")), 65536), StandardCharsets.UTF_8));
        if (!(decoded instanceof Map) || !(((Map<?, ?>) decoded).get("profiles") instanceof List)) return false;
        for (Object item : (List<?>) ((Map<?, ?>) decoded).get("profiles")) {
            if (!(item instanceof Map)) return false;
            Object iso = ((Map<?, ?>) item).get("country"), carrier = ((Map<?, ?>) item).get("carrierName");
            if (!(iso instanceof String) || !(carrier instanceof String)) return false;
            if ((!((String) iso).isEmpty() && !country) || (!((String) carrier).isEmpty() && !name)) return false;
        }
        return true;
    }
    private static void ackFailure(Map<String, Object> out, String validation) {
        out.put("validationError", validation);
        Object original = out.get("error");
        if (!(original instanceof String) || ((String) original).isEmpty()
                || "Global engine has not acknowledged a configuration".equals(original)) out.put("error", validation);
    }
    private String currentConfigHash() {
        try {
            Path path = safePath(dir.resolve("runtime.json"));
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "";
            long hash = 0xcbf29ce484222325L;
            for (byte value : readLimited(path, 65536)) { hash ^= value & 255; hash *= 0x100000001b3L; }
            return Long.toUnsignedString(hash);
        } catch (Exception e) { return ""; }
    }
    private static boolean ackBoolean(Map<?, ?> ack, String key) throws IOException {
        Object value = ack.get(key);
        if (!(value instanceof Boolean)) throw new IOException("Invalid global engine " + key);
        return Boolean.TRUE.equals(value);
    }
    private static int ackInteger(Map<?, ?> ack, String key) throws IOException {
        Object value = ack.get(key);
        if (!(value instanceof Number)) throw new IOException("Invalid global engine " + key);
        try {
            int number = new java.math.BigDecimal(value.toString()).intValueExact();
            if (number < 0) throw new ArithmeticException(); return number;
        } catch (ArithmeticException | NumberFormatException e) { throw new IOException("Invalid global engine " + key); }
    }
    private static long ackLong(Map<?, ?> ack, String key) throws IOException {
        Object value = ack.get(key);
        if (!(value instanceof Number)) throw new IOException("Invalid global engine " + key);
        try {
            long number = new java.math.BigDecimal(value.toString()).longValueExact();
            if (number < 0) throw new ArithmeticException(); return number;
        } catch (ArithmeticException | NumberFormatException e) { throw new IOException("Invalid global engine " + key); }
    }
    private static String ackString(Map<?, ?> ack, String key, int limit) throws IOException {
        Object value = ack.get(key);
        if (!(value instanceof String) || ((String) value).length() > limit) throw new IOException("Invalid global engine " + key);
        return (String) value;
    }

    private boolean matches(Subscription sub) {
        String p = "profile." + sub.slot + ".";
        return String.valueOf(sub.subId).equals(state.getProperty(p + "subId"))
                && sub.identity.equals(state.getProperty(p + "identity"));
    }
    private boolean matchesCurrentSim(Subscription sub) {
        return matches(sub) && (!sub.identity.startsWith("weak:")
                || boot.equals(state.getProperty("profile." + sub.slot + ".confirmedBoot")));
    }
    private Subscription find(int slot) { for (Subscription sub : subs) if (sub.slot == slot) return sub; return null; }
    private static int parseSlot(String value) {
        if (value == null || !value.matches("[0-7]")) throw new IllegalArgumentException("Slot must be 0 through 7");
        return Integer.parseInt(value);
    }
    private SortedSet<Integer> profileSlots() {
        SortedSet<Integer> slots = new TreeSet<Integer>();
        for (String key : state.stringPropertyNames())
            if (key.matches("profile\\.[0-7]\\.country")) slots.add(Integer.parseInt(key.substring(8, 9))); return slots;
    }
    private boolean profileEnabled(int slot) { return Boolean.parseBoolean(state.getProperty("profile." + slot + ".enabled", "false")); }
    private boolean enabled() { for (int slot : profileSlots()) if (profileEnabled(slot)) return true; return false; }
    private void setProfileEnabled(int slot, boolean value) {
        if (profileSlots().contains(slot)) state.setProperty("profile." + slot + ".enabled", String.valueOf(value));
    }
    private void setAllEnabled(boolean value) { for (int slot : profileSlots()) setProfileEnabled(slot, value); }
    private void removePrefix(String prefix) {
        for (String key : new ArrayList<String>(state.stringPropertyNames())) if (key.startsWith(prefix)) state.remove(key);
    }

    private void load() throws Exception {
        state = new Properties(); Path path = safePath(dir.resolve("state.properties"));
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            try (InputStream in = new ByteArrayInputStream(readLimited(path, 65536))) { state.load(in); }
        validateStateSchema(state);
        boolean legacyEnabled = Boolean.parseBoolean(state.getProperty("enabled", "false"));
        for (int slot : profileSlots()) {
            String p = "profile." + slot + ".";
            if (!state.containsKey(p + "enabled")) state.setProperty(p + "enabled", String.valueOf(legacyEnabled));
            if (!state.containsKey(p + "subId")) state.setProperty(p + "enabled", "false");
        }
        removePrefix("baseline."); removePrefix("pending."); removePrefix("last.");
        state.remove("targetPackages");
        state.setProperty("schema", "5"); state.setProperty("boot", boot);
    }
    static void validateStateSchema(Properties input) throws IOException {
        String schema = input.getProperty("schema");
        if (schema != null && !"4".equals(schema) && !"5".equals(schema))
            throw new IOException("Unsupported saved settings schema; use a compatible module version");
    }
    private void publishStatusRuntime(Map<String, Object> runtime) throws IOException {
        Path cache = safePath(dir.resolve("runtime.json"));
        byte[] next = Json.encode(runtime).getBytes(StandardCharsets.UTF_8);
        // A foreground status poll should not rewrite an already verified cache.
        if (Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS) && Files.size(cache) == next.length
                && Arrays.equals(readLimited(cache, 65536), next)) return;
        atomicWrite(cache, next);
    }
    private void save(Map<String, Object> runtime) throws Exception {
        state.setProperty("enabled", String.valueOf(enabled()));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); state.store(buffer, "Haaru system-global volatile profiles; module-local only");
        Path saved = safePath(dir.resolve("state.properties")), cache = safePath(dir.resolve("runtime.json"));
        // Both destinations must be safe before changing either. Backups stay in memory.
        byte[] priorState = Files.exists(saved, LinkOption.NOFOLLOW_LINKS) ? readLimited(saved, 65536) : null;
        byte[] priorRuntime = Files.exists(cache, LinkOption.NOFOLLOW_LINKS) ? readLimited(cache, 65536) : null;
        try {
            atomicWrite(saved, buffer.toByteArray());
            atomicWrite(cache, Json.encode(runtime).getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            try {
                restoreFile(saved, priorState); restoreFile(cache, priorRuntime);
            } catch (Exception rollback) { warnings.add("Module-file rollback failed: " + message(rollback)); }
            throw failure;
        }
    }
    private void restoreFile(Path target, byte[] prior) throws IOException {
        if (prior == null) Files.deleteIfExists(safePath(target)); else atomicWrite(target, prior);
    }
    private boolean marked(String name) throws IOException { return Files.exists(safePath(module.resolve(name)), LinkOption.NOFOLLOW_LINKS); }
    private Path safePath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(module)) throw new IOException("Path escapes module directory"); rejectLinks(normalized); return normalized;
    }
    static void rejectLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not permitted in module paths");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Module path resolves outside its declared location");
        }
    }
    private byte[] readLimited(Path path, int limit) throws IOException {
        safePath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("Invalid or oversized module configuration file");
        byte[] data = Files.readAllBytes(path);
        if (data.length > limit) throw new IOException("Module configuration is too large"); return data;
    }
    private void atomicWrite(Path target, byte[] data) throws IOException {
        if (data.length > 65536) throw new IOException("Module configuration is too large");
        safePath(target); safePath(target.getParent());
        Path temporary = safePath(target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp"));
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(data); while (bytes.hasRemaining()) out.write(bytes); out.force(true);
            }
            safePath(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            if (!Arrays.equals(data, readLimited(target, 65536))) throw new IOException("Module-file readback failed");
        } finally { Files.deleteIfExists(temporary); }
    }
    public Map<String, Object> failure(Exception e) {
        Map<String, Object> out = base(false, false); out.put("error", message(e));
        out.put("enabled", !readOnly && enabled()); out.put("readOnly", readOnly);
        out.put("engine", "system-global-volatile"); out.put("global", true);
        out.put("currentConfigHash", currentConfigHash()); out.put("globalEngine", globalEngine());
        out.put("subscriptions", Collections.emptyList()); out.put("profiles", Collections.emptyList());
        out.put("warnings", new ArrayList<String>(warnings)); return out;
    }
    private Map<String, Object> base(boolean ok, boolean verified) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(); out.put("ok", ok); out.put("verified", verified);
        out.put("moduleVersion", moduleVersion); out.put("moduleVersionCode", moduleVersionCode);
        out.put("upgradePending", upgradePending); return out;
    }
    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String message(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
