package com.github.haaru.ksu;

import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Installs only the exact signed, offline companion carried by this module. */
public final class CompanionInstallation {
    static final String PROTOCOL_KEY = "dev.cekxri.haaru.IPC_PROTOCOL";
    public static final String STATUS_FILE = "companion-install.json";
    private static final long MAX_APK_BYTES = 134217728;
    private CompanionInstallation() {}

    interface Platform {
        Info bundled(Path apk) throws Exception;
        Info installed(String packageName) throws Exception;
        void install(Path apk, long bytes) throws Exception;
    }
    interface ProcessStarter { java.lang.Process start(String[] command) throws IOException; }
    static final class Pending extends IOException { Pending(String message) { super(message); } }
    static final class Info {
        final String packageName, version, certificate, apkSha256;
        final long versionCode;
        final int protocol;
        Info(String packageName, String version, long versionCode, String certificate, int protocol, String apkSha256) {
            this.packageName = packageName; this.version = version; this.versionCode = versionCode;
            this.certificate = certificate; this.protocol = protocol; this.apkSha256 = apkSha256;
        }
    }
    static final class Plan {
        final Path apk;
        final String packageName, version, certificate, apkSha256;
        final long versionCode, bytes;
        final int protocol;
        Plan(Path apk, String packageName, String version, long versionCode, String certificate,
             int protocol, String apkSha256, long bytes) {
            this.apk = apk; this.packageName = packageName; this.version = version; this.versionCode = versionCode;
            this.certificate = certificate; this.protocol = protocol; this.apkSha256 = apkSha256; this.bytes = bytes;
        }
    }

    public static void main(String[] args) {
        Path module = null;
        String stage = "android-runtime";
        Map<String, Object> result;
        try {
            AndroidBackend.checkRootAndSdk();
            if (args.length != 2 || !"--module".equals(args[0])) throw new IOException("Expected --module DIRECTORY");
            module = Haaru.validateModule(Paths.get(args[1]));
            stage = "metadata";
            if (Files.exists(module.resolve("remove"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Module is being removed");
            Plan plan = plan(module);
            Path data = module.resolve("data"); Controller.rejectLinks(data); Files.createDirectories(data);
            Path lock = data.resolve("companion-install.lock"); Controller.rejectLinks(lock);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock acquired = channel.tryLock();
                if (acquired == null) result = result("pending", "App installation is already running", false, plan, null);
                else try { stage = "platform-initialize"; result = run(plan, new AndroidPlatform()); } finally { acquired.release(); }
            }
        } catch (Pending pending) { result = stagedResult("pending", pending.getMessage(), false, null, null, stage); }
        catch (Exception | LinkageError error) {
            result = stagedResult("error", message(error), false, null, null, stage);
            if (module != null) savePrivateDiagnostic(module, stage, error, "", "");
        }
        if (module != null) try { saveStatus(module, result); } catch (Exception failure) {
            result = result("error", "Cannot save App installation status: " + message(failure), false, null, null);
        }
        System.out.println(Json.encode(result));
        if ("error".equals(result.get("state"))) System.exit(2);
    }

    static Map<String, Object> run(Plan plan, Platform platform) {
        Info installed = null;
        String stage = "bundle-hash";
        try {
            Controller.rejectLinks(plan.apk);
            if (!Files.isRegularFile(plan.apk, LinkOption.NOFOLLOW_LINKS) || Files.size(plan.apk) != plan.bytes
                    || !plan.apkSha256.equals(hash(plan.apk))) throw new IOException("Bundled App hash or size differs");
            stage = "archive-parse";
            Info bundled = platform.bundled(plan.apk);
            if (!exact(plan, bundled)) throw new IOException("Bundled App package, version, signer or protocol differs");
            stage = "installed-query";
            installed = platform.installed(plan.packageName);
            if (installed != null) {
                if (!plan.packageName.equals(installed.packageName) || !plan.certificate.equals(installed.certificate))
                    return result("error", "Installed App signer differs; the existing App was kept", false, plan, installed);
                if (installed.versionCode > plan.versionCode) {
                    if (installed.protocol == plan.protocol)
                        return result("newer-kept", "A newer compatible App is installed; no downgrade was attempted", true, plan, installed);
                    return result("error", "Newer App requires its matching module; no downgrade was attempted", false, plan, installed);
                }
                if (exact(plan, installed)) return result("ready", "Matching App is already installed", true, plan, installed);
            }
            stage = "package-install";
            platform.install(plan.apk, plan.bytes);
            stage = "installed-verify";
            Info after = platform.installed(plan.packageName);
            if (after == null) throw new Pending("PackageManager has not confirmed App installation yet");
            if (!exact(plan, after)) throw new IOException("Installed App verification differs after PackageManager finished");
            return result("installed", "Matching App was installed automatically", true, plan, after);
        } catch (Pending unavailable) { return stagedResult("pending", unavailable.getMessage(), false, plan, installed, stage); }
        catch (Exception | LinkageError failure) {
            savePrivateDiagnostic(plan.apk.getParent().getParent(), stage, failure, "", "");
            return stagedResult("error", message(failure), false, plan, installed, stage);
        }
    }

    private static boolean exact(Plan expected, Info info) {
        return info != null && expected.packageName.equals(info.packageName) && expected.version.equals(info.version)
                && expected.versionCode == info.versionCode && expected.certificate.equals(info.certificate)
                && expected.protocol == info.protocol && expected.apkSha256.equals(info.apkSha256);
    }

    static Plan plan(Path module) throws Exception {
        Properties mod = properties(module.resolve("module.prop"));
        Properties auth = properties(module.resolve("companion.prop"));
        Properties app = properties(module.resolve("app/install.prop"));
        String packageName = app.getProperty("package", ""), version = app.getProperty("version", "");
        String certificate = app.getProperty("certificateSha256", ""), hash = app.getProperty("apkSha256", "");
        long code = positive(app.getProperty("versionCode")), bytes = positive(app.getProperty("apkBytes"));
        if (!CompanionProtocol.PACKAGE.equals(packageName) || !"1".equals(app.getProperty("protocol"))
                || !version.equals(mod.getProperty("version")) || code != positive(mod.getProperty("versionCode"))
                || !packageName.equals(auth.getProperty("package")) || !certificate.equals(auth.getProperty("certificateSha256"))
                || !"1".equals(auth.getProperty("protocol")) || !certificate.matches("[0-9a-f]{64}")
                || !hash.matches("[0-9a-f]{64}") || bytes > MAX_APK_BYTES)
            throw new IOException("Bundled App metadata does not match this module");
        return new Plan(module.resolve("app/Haaru.apk"), packageName, version, code, certificate, 1, hash, bytes);
    }

    private static long positive(String value) throws IOException {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) throw new IOException("Invalid App installation metadata integer");
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new IOException("Invalid App installation metadata integer"); }
    }
    private static Properties properties(Path path) throws Exception {
        Controller.rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) throw new IOException("App installation metadata is unavailable");
        Properties value = new Properties(); value.load(new StringReader(CompanionProtocol.decode(Files.readAllBytes(path)))); return value;
    }

    public static Map<String, Object> readStatus(Path module) {
        try {
            Path file = module.resolve("data/" + STATUS_FILE); Controller.rejectLinks(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return result("pending", "App installation will continue when Android is ready", false, null, null);
            if (Files.size(file) > 16384) throw new IOException("App installation status is too large");
            Object decoded = Json.decode(CompanionProtocol.decode(Files.readAllBytes(file)));
            if (!(decoded instanceof Map)) throw new IOException("Invalid App installation status");
            Map<?, ?> raw = (Map<?, ?>) decoded;
            String state = String.valueOf(raw.get("state"));
            if (!Arrays.asList("pending", "error", "ready", "installed", "newer-kept").contains(state)
                    || !(raw.get("message") instanceof String) || !(raw.get("verified") instanceof Boolean))
                throw new IOException("Invalid App installation status");
            boolean expectedVerified = Arrays.asList("ready", "installed", "newer-kept").contains(state);
            if (!Boolean.valueOf(expectedVerified).equals(raw.get("verified"))) throw new IOException("Inconsistent App installation status");
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() instanceof String) out.put((String) entry.getKey(), entry.getValue());
            return out;
        } catch (Exception error) { return result("error", "App installation status is unavailable", false, null, null); }
    }
    private static Map<String, Object> result(String state, String message, boolean verified, Plan plan, Info installed) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("state", state); value.put("message", message); value.put("verified", verified);
        if (plan != null) { value.put("expectedVersion", plan.version); value.put("expectedVersionCode", plan.versionCode); }
        if (installed != null) { value.put("installedVersion", installed.version); value.put("installedVersionCode", installed.versionCode); }
        return value;
    }
    private static Map<String, Object> stagedResult(String state, String message, boolean verified, Plan plan, Info installed, String stage) {
        Map<String, Object> value = result(state, message, verified, plan, installed); value.put("stage", stage); return value;
    }
    private static void savePrivateDiagnostic(Path directory, String stage, Throwable error, String stdout, String stderr) {
        try {
            Path module = Haaru.validateModule(directory), data = module.resolve("data");
            Controller.rejectLinks(data); Files.createDirectories(data);
            Path target = data.resolve("companion-install-debug.log"); Controller.rejectLinks(target);
            StringWriter trace = new StringWriter(); if (error != null) error.printStackTrace(new PrintWriter(trace));
            String prior = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) <= 32768
                    ? new String(Files.readAllBytes(target), StandardCharsets.UTF_8) : "";
            String text = prior + "\nstage=" + stage + "\n" + trace.toString() + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); if (bytes.length > 32768) bytes = Arrays.copyOfRange(bytes, bytes.length - 32768, bytes.length);
            try (FileChannel file = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
        } catch (Exception ignored) { /* Never let private diagnostics replace the installation outcome. */ }
    }
    private static void saveStatus(Path module, Map<String, Object> value) throws Exception {
        Path data = module.resolve("data"); Controller.rejectLinks(data); Files.createDirectories(data);
        Path target = data.resolve(STATUS_FILE); Controller.rejectLinks(target);
        byte[] bytes = (Json.encode(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(bytes, Files.readAllBytes(target))) return;
        Path temp = Files.createTempFile(data, "companion-install-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static String hash(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[65536]; for (int count; (count = source.read(bytes)) >= 0;) if (count > 0) hash.update(bytes, 0, count);
        }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte value : bytes) { out.append(Character.forDigit((value & 255) >>> 4, 16)); out.append(Character.forDigit(value & 15, 16)); } return out.toString();
    }
    private static String message(Throwable error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); return text.substring(0, Math.min(text.length(), 1200));
    }

    static final class AndroidPlatform implements Platform {
        final Object pm; final Class<?> pmType, infoType, appType;
        final ProcessStarter starter;
        AndroidPlatform() throws Exception {
            this(new ProcessStarter() {
                @Override public java.lang.Process start(String[] command) throws IOException { return new ProcessBuilder(command).start(); }
            });
        }
        AndroidPlatform(ProcessStarter starter) throws Exception {
            this.starter = starter;
            Object binder = call(Class.forName("android.os.ServiceManager").getMethod("getService", String.class), null, "package");
            if (binder == null) throw new Pending("Android PackageManager is not ready; App installation will continue after boot");
            Class<?> looper = Class.forName("android.os.Looper");
            if (call(looper.getMethod("myLooper"), null) == null)
                call(looper.getMethod(call(looper.getMethod("getMainLooper"), null) == null ? "prepareMainLooper" : "prepare"), null);
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object current = call(thread.getMethod("systemMain"), null);
            Object context = call(thread.getMethod("getSystemContext"), current);
            pm = call(Class.forName("android.content.Context").getMethod("getPackageManager"), context);
            if (pm == null) throw new Pending("Android PackageManager is not ready");
            pmType = Class.forName("android.content.pm.PackageManager"); infoType = Class.forName("android.content.pm.PackageInfo");
            appType = Class.forName("android.content.pm.ApplicationInfo");
        }
        @Override public Info bundled(Path apk) throws Exception {
            Object info = call(pmType.getMethod("getPackageArchiveInfo", String.class, int.class), pm, apk.toString(), 0x08000080);
            if (info == null) throw new IOException("PackageManager cannot parse bundled App");
            return info(info, hash(apk));
        }
        @Override public Info installed(String packageName) throws Exception {
            Object info;
            try { info = call(pmType.getMethod("getPackageInfo", String.class, int.class), pm, packageName, 0x08000080); }
            catch (Exception error) { if (error.getClass().getName().endsWith("NameNotFoundException")) return null; throw new Pending("PackageManager cannot read installed App yet"); }
            Object application = infoType.getField("applicationInfo").get(info);
            String source = (String) appType.getField("sourceDir").get(application);
            if (source == null) throw new Pending("Installed App source is not ready");
            return info(info, hash(Paths.get(source)));
        }
        private Info info(Object info, String apkHash) throws Exception {
            String packageName = (String) infoType.getField("packageName").get(info);
            String version = (String) infoType.getField("versionName").get(info);
            long code = ((Number) call(infoType.getMethod("getLongVersionCode"), info)).longValue();
            Object signing = infoType.getField("signingInfo").get(info);
            Object[] signatures = signing == null ? null : (Object[]) call(Class.forName("android.content.pm.SigningInfo").getMethod("getApkContentsSigners"), signing);
            if (signatures == null || signatures.length != 1) throw new IOException("App must have the pinned signer certificate");
            byte[] signer = (byte[]) call(Class.forName("android.content.pm.Signature").getMethod("toByteArray"), signatures[0]);
            String certificate = hex(MessageDigest.getInstance("SHA-256").digest(signer));
            Object application = infoType.getField("applicationInfo").get(info);
            Object metadata = appType.getField("metaData").get(application);
            int protocol = metadata == null ? 0 : ((Number) call(Class.forName("android.os.Bundle").getMethod("getInt", String.class, int.class), metadata, PROTOCOL_KEY, 0)).intValue();
            return new Info(packageName, version == null ? "" : version, code, certificate, protocol, apkHash);
        }
        @Override public void install(Path apk, long bytes) throws Exception {
            final java.lang.Process process = starter.start(new String[] {"/system/bin/pm", "install", "-r", "--user", "0", "-S", Long.toString(bytes), "-"});
            ExecutorService io = Executors.newFixedThreadPool(3);
            try {
                Future<String> output = io.submit(new Callable<String>() {
                    @Override public String call() throws IOException { return read(process.getInputStream()); }
                });
                Future<String> errors = io.submit(new Callable<String>() {
                    @Override public String call() throws IOException { return read(process.getErrorStream()); }
                });
                Future<Void> input = io.submit(new Callable<Void>() {
                    @Override public Void call() throws IOException {
                        try (InputStream source = Files.newInputStream(apk, LinkOption.NOFOLLOW_LINKS); OutputStream target = process.getOutputStream()) {
                            byte[] buffer = new byte[65536]; for (int count; (count = source.read(buffer)) >= 0;) if (count > 0) target.write(buffer, 0, count);
                        }
                        return null;
                    }
                });
                Throwable inputFailure = null;
                try { input.get(120, TimeUnit.SECONDS); }
                catch (ExecutionException failedInput) { inputFailure = failedInput.getCause(); }
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw new Pending("Android App installation timed out; it will be retried after boot");
                String text = output.get(5, TimeUnit.SECONDS), errorText = errors.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0 || !text.contains("Success")) {
                    java.util.regex.Matcher reason = java.util.regex.Pattern.compile("INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+").matcher(text + "\n" + errorText);
                    String code = reason.find() ? reason.group() : "exit=" + process.exitValue();
                    savePrivateDiagnostic(apk.getParent().getParent(), "package-install", null, text, errorText);
                    throw new IOException("Android PackageManager did not accept the App (" + code + "); the existing App was kept");
                }
                if (inputFailure != null) {
                    savePrivateDiagnostic(apk.getParent().getParent(), "package-install", inputFailure, text, errorText);
                    throw new IOException("The App APK stream did not finish", inputFailure);
                }
            } catch (TimeoutException timeout) { throw new Pending("Android App installation is waiting for PackageManager"); }
            finally { process.destroyForcibly(); io.shutdownNow(); }
        }
        private static String read(InputStream input) throws IOException {
            try (InputStream source = input) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
                for (int count; (count = source.read(buffer)) >= 0;) {
                    int kept = Math.min(count, 16384 - out.size());
                    if (kept > 0) out.write(buffer, 0, kept);
                    // Drain the rest so stderr cannot block PM while only a bounded prefix is kept.
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
    private static Object call(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause(); if (cause instanceof Exception) throw (Exception) cause; if (cause instanceof Error) throw (Error) cause; throw new IOException("Android package operation failed", cause);
        }
    }
}
