package com.github.haaru.ksu;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class UpgradeTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Path module(Path root, String parent) throws Exception {
        Path module = root.resolve(parent).resolve("haaru"); Files.createDirectories(module);
        Files.write(module.resolve("module.prop"), "id=haaru\nversion=0.5.1\nversionCode=7\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(module.resolve("webroot")); return module;
    }
    private static Properties properties(String schema) {
        Properties p = new Properties(); if (schema != null) p.setProperty("schema", schema);
        p.setProperty("boot", "old-boot"); p.setProperty("enabled", "true");
        p.setProperty("profile.0.country", "us"); p.setProperty("profile.0.carrier", "台灣電信");
        p.setProperty("profile.0.enabled", "true"); p.setProperty("profile.0.subId", "10");
        p.setProperty("profile.0.identity", "iccid-sha256:original"); p.setProperty("profile.0.confirmedBoot", "old-boot");
        p.setProperty("profile.1.country", ""); p.setProperty("profile.1.carrier", "Name only");
        p.setProperty("profile.1.enabled", "false"); p.setProperty("profile.1.subId", "20");
        p.setProperty("profile.1.identity", "weak:slot=1;subId=20"); p.setProperty("profile.1.confirmedBoot", "old-boot");
        return p;
    }
    private static void save(Path module, Properties p) throws Exception {
        Files.createDirectories(module.resolve("data"));
        try (OutputStream out = Files.newOutputStream(module.resolve("data/state.properties"))) { p.store(out, "fixture"); }
    }
    private static Properties load(Path module) throws Exception {
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(module.resolve("data/state.properties"))) { p.load(in); } return p;
    }
    private static void rejects(Path source, Path destination) throws Exception {
        try { Upgrade.migrate(source, destination); throw new AssertionError("Expected rejected migration"); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    private static void preservationAndRepeatedUpdate() throws Exception {
        Path root = Files.createTempDirectory("haaru-upgrade-test-"); Path source = module(root, "live"), dest = module(root, "stage");
        Properties original = properties("5"); original.setProperty("targetPackages", "malformed obsolete value");
        original.setProperty("baseline.0.country", "stale"); save(source, original);
        byte[] sourceBytes = Files.readAllBytes(source.resolve("data/state.properties"));
        byte[] appearance = "{\"style\":\"MATERIAL3\",\"appearance\":\"DARK\",\"palette\":\"BLUE\",\"glass\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("webroot/appearance.json"), appearance);
        for (String transientFile : Arrays.asList("runtime.json", "global-status.json", "global-shadow.json", "global-transaction.json", "left.tmp"))
            Files.write(source.resolve("data").resolve(transientFile), "old-boot-data".getBytes(StandardCharsets.UTF_8));
        Files.write(source.resolve("update"), new byte[0]);
        byte[] metadata = Files.readAllBytes(dest.resolve("module.prop"));
        Map<String, Object> result = Upgrade.migrate(source, dest); Properties copied = load(dest);
        check(Boolean.TRUE.equals(result.get("verified")) && Boolean.TRUE.equals(result.get("settingsPreserved")), "Migration confirms staged settings file readback");
        check(Boolean.TRUE.equals(result.get("appearancePreserved")) && Boolean.TRUE.equals(result.get("rebootRequired")), "Migration reports preserved appearance and pending reboot");
        for (String key : original.stringPropertyNames()) if (!key.equals("targetPackages") && !key.startsWith("baseline."))
            check(original.getProperty(key).equals(copied.getProperty(key)), "Durable field preserved: " + key);
        check(!copied.containsKey("targetPackages") && !copied.containsKey("baseline.0.country"), "Obsolete routing and snapshot fields are excluded");
        check(Arrays.equals(sourceBytes, Files.readAllBytes(source.resolve("data/state.properties"))), "Installed settings remain byte identical");
        check(Arrays.equals(appearance, Files.readAllBytes(dest.resolve("webroot/appearance.json"))), "Appearance snapshot preserved byte exactly");
        check(Arrays.equals(metadata, Files.readAllBytes(dest.resolve("module.prop"))), "New module metadata is not replaced");
        for (String name : Arrays.asList("runtime.json", "global-status.json", "global-shadow.json", "global-transaction.json", "lock", "left.tmp"))
            check(!Files.exists(dest.resolve("data").resolve(name)), "Boot and process state excluded: " + name);
        check(!Files.exists(dest.resolve("update")) && !Files.exists(dest.resolve("remove")), "No installed lifecycle flags copied except disabled");
        original.setProperty("profile.0.country", "jp"); original.setProperty("profile.0.enabled", "false"); save(source, original);
        Files.write(source.resolve("disable"), new byte[0]); result = Upgrade.migrate(source, dest);
        check("jp".equals(load(dest).getProperty("profile.0.country")), "Repeated migration captures newest saved SIM settings");
        check("false".equals(load(dest).getProperty("profile.0.enabled")), "Repeated migration retains disabled SIM plan");
        check(Files.exists(dest.resolve("disable")) && Boolean.TRUE.equals(result.get("disabledPreserved")), "Disabled module stays disabled in staging");
        Files.delete(source.resolve("disable")); Upgrade.migrate(source, dest);
        check(!Files.exists(dest.resolve("disable")), "Repeated migration removes stale staged disable when source is active");
    }
    private static void invalidSourceAndAtomicValidation() throws Exception {
        Path root = Files.createTempDirectory("haaru-upgrade-invalid-"); Path source = module(root, "live"), dest = module(root, "stage");
        save(source, properties("5")); save(dest, properties("5"));
        byte[] staged = Files.readAllBytes(dest.resolve("data/state.properties"));
        for (String schema : Arrays.asList("6", "future", "05", "3", "-1")) {
            save(source, properties(schema)); byte[] prior = Files.readAllBytes(source.resolve("data/state.properties")); rejects(source, dest);
            check(Arrays.equals(prior, Files.readAllBytes(source.resolve("data/state.properties"))), "Rejected schema never rewrites installed settings");
            check(Arrays.equals(staged, Files.readAllBytes(dest.resolve("data/state.properties"))), "Rejected schema does not partially change staged settings");
        }
        for (String[] invalid : new String[][] {{"profile.0.country", "ZZ"}, {"profile.0.carrier", "Bad\nName"},
                {"profile.0.enabled", "yes"}, {"profile.0.subId", "2147483648"}, {"profile.0.identity", "bad\nidentity"}, {"profile.8.country", "us"}}) {
            Properties p = properties("5"); p.setProperty(invalid[0], invalid[1]); save(source, p); rejects(source, dest);
            check(Arrays.equals(staged, Files.readAllBytes(dest.resolve("data/state.properties"))), "Invalid durable field leaves staged state unchanged");
        }
        save(source, properties("5"));
        for (byte[] bad : new byte[][] {"{\"style\":\"other\"}".getBytes(StandardCharsets.UTF_8), new byte[] {(byte)0xc0, (byte)0xaf}, new byte[2049]}) {
            Files.write(source.resolve("webroot/appearance.json"), bad); rejects(source, dest);
            check(Arrays.equals(staged, Files.readAllBytes(dest.resolve("data/state.properties"))), "All source files validated before staged state commit");
        }
        Files.delete(source.resolve("webroot/appearance.json")); Files.createDirectory(dest.resolve("webroot/appearance.json"));
        rejects(source, dest); check(Arrays.equals(staged, Files.readAllBytes(dest.resolve("data/state.properties"))), "Unsafe second destination cannot partially commit the first");
        Files.delete(dest.resolve("webroot/appearance.json")); Files.write(source.resolve("remove"), new byte[0]);
        rejects(source, dest); check(Arrays.equals(staged, Files.readAllBytes(dest.resolve("data/state.properties"))), "Removed module is never resurrected into staging");
        Files.delete(source.resolve("remove")); rejects(source, source);
        Files.write(dest.resolve("module.prop"), "id=other\n".getBytes(StandardCharsets.UTF_8)); rejects(source, dest);
    }
    private static void locksLegacyAndAppearanceOnly() throws Exception {
        Path root = Files.createTempDirectory("haaru-upgrade-lock-"); Path source = module(root, "live"), dest = module(root, "stage");
        save(source, properties("5"));
        try (FileChannel channel = FileChannel.open(source.resolve("data/lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            rejects(source, dest); check(!Files.exists(dest.resolve("data/state.properties")), "Lock contention aborts before staged writes");
        }
        Properties legacy = properties("4"); legacy.remove("profile.0.enabled"); legacy.remove("profile.1.subId"); save(source, legacy);
        Upgrade.migrate(source, dest);
        check("5".equals(load(dest).getProperty("schema")) && "true".equals(load(dest).getProperty("profile.0.enabled")), "Known schema four converts global flag to per SIM flag");
        check("false".equals(load(dest).getProperty("profile.1.enabled")), "Legacy profile missing subscription identity is kept inactive");
        legacy.remove("schema"); save(source, legacy); Upgrade.migrate(source, dest);
        check("5".equals(load(dest).getProperty("schema")), "Known schema-less legacy properties are migrated");
        Path prefsSource = module(root, "prefs-only"); Files.write(prefsSource.resolve("webroot/appearance.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = Upgrade.migrate(prefsSource, dest);
        check(!Files.exists(prefsSource.resolve("data")), "Appearance-only migration does not create installed data");
        check(!Files.exists(dest.resolve("data/state.properties")) && !Boolean.TRUE.equals(result.get("settingsPreserved")), "Absent source settings cannot revive stale staged profiles");
        check(Boolean.TRUE.equals(result.get("appearancePreserved")), "Appearance-only installation migrates without telephony access");
    }
    private static void unsafeLinks() throws Exception {
        Path root = Files.createTempDirectory("haaru-upgrade-links-"); Path source = module(root, "live"), dest = module(root, "stage");
        Path outside = root.resolve("outside"); Files.createDirectories(outside);
        try { Files.createSymbolicLink(source.resolve("data"), outside); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", source.resolve("data").toString(), outside.toString()).redirectErrorStream(true).start();
            while (process.getInputStream().read() != -1) { }
            if (process.waitFor() != 0) throw new AssertionError("Unable to create junction fixture");
        }
        rejects(source, dest); check(!Files.exists(outside.resolve("lock")), "Source link cannot redirect lock or settings access");
        check(!Files.exists(dest.resolve("data/state.properties")), "Source link aborts without staged writes");
    }
    private static void interruptedCommitRollback() throws Exception {
        Path root = Files.createTempDirectory("haaru-upgrade-rollback-"); Path source = module(root, "live"), dest = module(root, "stage");
        Properties sourceProperties = properties("5"); sourceProperties.setProperty("profile.0.country", "jp"); save(source, sourceProperties);
        save(dest, properties("5"));
        Files.write(source.resolve("webroot/appearance.json"), "{\"style\":\"MATERIAL3\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(dest.resolve("webroot/appearance.json"), "{\"style\":\"MIUIX\"}".getBytes(StandardCharsets.UTF_8));
        byte[] beforeState = Files.readAllBytes(dest.resolve("data/state.properties"));
        byte[] beforeAppearance = Files.readAllBytes(dest.resolve("webroot/appearance.json"));
        byte[] original = Files.readAllBytes(source.resolve("data/state.properties"));
        for (final String interruptedFile : Arrays.asList("state.properties", "appearance.json", "disable")) {
            try {
                Upgrade.migrate(source, dest, new Upgrade.CommitObserver() {
                    @Override public void afterWrite(Path target) throws IOException {
                        if (target.getFileName().toString().equals(interruptedFile)) throw new IOException("Simulated interrupted staged commit");
                    }
                });
                throw new AssertionError("Expected interruption");
            } catch (IOException expected) { checks++; }
            check(Arrays.equals(beforeState, Files.readAllBytes(dest.resolve("data/state.properties"))), "Interrupted commit rolls staged state back byte exactly");
            check(Arrays.equals(beforeAppearance, Files.readAllBytes(dest.resolve("webroot/appearance.json"))), "Interrupted commit rolls staged appearance back byte exactly");
            check(Arrays.equals(original, Files.readAllBytes(source.resolve("data/state.properties"))), "Interrupted commit never changes installed settings");
            check(!Files.exists(dest.resolve("disable")), "Rollback leaves lifecycle marker as it was");
        }
    }
    public static void main(String[] args) throws Exception {
        preservationAndRepeatedUpdate(); invalidSourceAndAtomicValidation(); locksLegacyAndAppearanceOnly(); unsafeLinks(); interruptedCommitRollback();
        System.out.println("Upgrade migration: " + checks + " preservation and rejection checks passed (no Android device claim)");
    }
}
