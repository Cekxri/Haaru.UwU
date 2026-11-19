package com.github.haaru.ksu;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Test-only 0.5.1 Java oracle for the Rust migration compatibility contract. */
public final class Upgrade {
    private Upgrade() {}
    interface CommitObserver { void afterWrite(Path target) throws IOException; }

    public static Map<String, Object> migrate(Path sourceDirectory, Path destinationDirectory) throws Exception {
        return migrate(sourceDirectory, destinationDirectory, null);
    }
    // A per-call observer lets the host contract exercise interrupted multi-file commits.
    static Map<String, Object> migrate(Path sourceDirectory, Path destinationDirectory, CommitObserver observer) throws Exception {
        Path source = Haaru.validateModule(sourceDirectory), destination = Haaru.validateModule(destinationDirectory);
        if (source.equals(destination) || !"haaru".equals(source.getFileName().toString())
                || !"haaru".equals(destination.getFileName().toString()))
            throw new IOException("Upgrade requires distinct Haaru module directories");
        refuseRemoval(source);
        Path data = checked(source.resolve("data"));
        if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) return snapshot(source, destination, observer);
        if (!Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid installed settings directory");
        Path lockPath = checked(data.resolve("lock"));
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid installed settings lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Another settings operation is in progress"); }
            if (lock == null) throw new IOException("Another settings operation is in progress");
            try { return snapshot(source, destination, observer); }
            finally { lock.release(); }
        }
    }

    private static Map<String, Object> snapshot(Path source, Path destination, CommitObserver observer) throws Exception {
        refuseRemoval(source);
        byte[] stateBytes = optional(source.resolve("data/state.properties"), 65536);
        byte[] appearanceBytes = optional(source.resolve("data/appearance.json"), 2048);
        byte[] legacyAppearance = optional(source.resolve("webroot/appearance.json"), 2048);
        if (appearanceBytes != null && legacyAppearance != null && !Arrays.equals(appearanceBytes, legacyAppearance))
            throw new IOException("Conflicting appearance files; settings were not changed");
        if (appearanceBytes == null) appearanceBytes = legacyAppearance;
        if (stateBytes != null) stateBytes = validatedState(stateBytes);
        if (appearanceBytes != null) {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(appearanceBytes)).toString();
            Controller.validateAppearance(Json.decode(text));
        }
        Path disabled = checked(source.resolve("disable"));
        boolean preserveDisabled = Files.exists(disabled, LinkOption.NOFOLLOW_LINKS);
        if (preserveDisabled && !Files.isRegularFile(disabled, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid installed disable marker");

        Map<Path, byte[]> changes = new LinkedHashMap<Path, byte[]>();
        changes.put(destination.resolve("data/state.properties"), stateBytes);
        changes.put(destination.resolve("data/appearance.json"), appearanceBytes);
        changes.put(destination.resolve("disable"), preserveDisabled ? new byte[0] : null);
        Map<Path, byte[]> backups = new LinkedHashMap<Path, byte[]>();
        // Validate every destination and preserve prior staged settings before the first write.
        for (Path target : changes.keySet()) {
            checked(target); Path parent = checked(target.getParent());
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid staged settings directory");
            backups.put(target, optional(target, 65536));
        }
        refuseRemoval(source);
        List<Path> committed = new ArrayList<Path>();
        try {
            for (Map.Entry<Path, byte[]> change : changes.entrySet()) {
                committed.add(change.getKey());
                write(change.getKey(), change.getValue());
                if (observer != null) observer.afterWrite(change.getKey());
            }
        } catch (Exception failure) {
            for (int i = committed.size() - 1; i >= 0; --i) {
                Path target = committed.get(i);
                try { write(target, backups.get(target)); }
                catch (Exception rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true); out.put("verified", true); out.put("verification", "module-files");
        out.put("settingsPreserved", stateBytes != null); out.put("appearancePreserved", appearanceBytes != null);
        out.put("disabledPreserved", preserveDisabled); out.put("rebootRequired", true);
        return out;
    }

    private static byte[] validatedState(byte[] bytes) throws Exception {
        Properties input = new Properties();
        try { input.load(new ByteArrayInputStream(bytes)); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed installed settings", malformed); }
        Controller.validateStateSchema(input);
        for (String key : input.stringPropertyNames())
            if (key.startsWith("profile.") && !key.matches("profile\\.[0-7]\\..+"))
                throw new IOException("Invalid saved SIM slot");
        Properties output = new Properties();
        output.setProperty("schema", "5");
        String enabled = booleanValue(input.getProperty("enabled", "false"));
        output.setProperty("enabled", enabled);
        copyText(input, output, "boot", 128);
        for (int slot = 0; slot < 8; ++slot) {
            String prefix = "profile." + slot + ".";
            if (!input.containsKey(prefix + "country") && !input.containsKey(prefix + "carrier")) continue;
            String country = input.getProperty(prefix + "country", "");
            if (!country.isEmpty() && (!country.matches("[A-Za-z]{2}")
                    || !Arrays.asList(Locale.getISOCountries()).contains(country.toUpperCase(Locale.ROOT))))
                throw new IOException("Invalid saved country in SIM slot " + slot);
            String carrier = input.getProperty(prefix + "carrier", "");
            if (carrier.codePointCount(0, carrier.length()) > 80 || carrier.matches("(?s).*[\\p{Cntrl}].*"))
                throw new IOException("Invalid saved operator name in SIM slot " + slot);
            // Properties Unicode escapes must form actual Unicode scalar values.
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(carrier));
            output.setProperty(prefix + "country", country.toLowerCase(Locale.ROOT));
            output.setProperty(prefix + "carrier", carrier);
            String active = booleanValue(input.getProperty(prefix + "enabled", enabled));
            String id = input.getProperty(prefix + "subId");
            if (id != null) {
                if (!id.matches("[0-9]{1,10}")) throw new IOException("Invalid saved subscription ID");
                try { Integer.parseInt(id); } catch (NumberFormatException invalid) { throw new IOException("Invalid saved subscription ID", invalid); }
                output.setProperty(prefix + "subId", id);
            } else active = "false";
            copyText(input, output, prefix + "identity", 256);
            if (input.getProperty(prefix + "identity", "").isEmpty()) active = "false";
            copyText(input, output, prefix + "confirmedBoot", 128);
            output.setProperty(prefix + "enabled", active);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        output.store(out, "Haaru durable settings migrated into KernelSU staging");
        if (out.size() > 65536) throw new IOException("Migrated settings are too large");
        return out.toByteArray();
    }
    private static String booleanValue(String value) throws IOException {
        if (!"true".equals(value) && !"false".equals(value)) throw new IOException("Invalid saved enabled flag");
        return value;
    }
    private static void copyText(Properties input, Properties output, String key, int limit) throws IOException {
        String value = input.getProperty(key);
        if (value == null) return;
        if (value.length() > limit || value.matches("(?s).*[\\p{Cntrl}].*")) throw new IOException("Invalid saved field: " + key);
        output.setProperty(key, value);
    }
    private static void refuseRemoval(Path source) throws IOException {
        if (Files.exists(checked(source.resolve("remove")), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installed module is scheduled for removal; settings will not be restored");
    }
    private static Path checked(Path path) throws IOException { Controller.rejectLinks(path); return path; }
    private static byte[] optional(Path path, int limit) throws IOException {
        checked(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("Invalid or oversized settings file: " + path.getFileName());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            if (buffer.position() > limit) throw new IOException("Settings file exceeds size limit");
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }
    private static void write(Path target, byte[] bytes) throws IOException {
        checked(target); checked(target.getParent());
        if (bytes == null) { Files.deleteIfExists(target); return; }
        Files.createDirectories(target.getParent()); checked(target.getParent());
        Path temporary = checked(target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp"));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            checked(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            if (!Arrays.equals(bytes, optional(target, 65536))) throw new IOException("Staged settings verification failed");
        } finally { Files.deleteIfExists(temporary); }
    }
}
