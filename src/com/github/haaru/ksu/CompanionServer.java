package com.github.haaru.ksu;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root belongs to the module service. The authenticated companion App never invokes su. */
public final class CompanionServer {
    private final Path module;
    private final String version, certificate;
    private final Object packageManager;
    private final Class<?> pmType, socketType;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Object listener;
    private String pid;

    private CompanionServer(Path directory) throws Exception {
        module = Haaru.validateModule(directory);
        version = moduleVersion();
        certificate = certificate();
        Class<?> looper = Class.forName("android.os.Looper");
        if (call(looper.getMethod("myLooper"), null) == null) {
            boolean main = call(looper.getMethod("getMainLooper"), null) == null;
            call(looper.getMethod(main ? "prepareMainLooper" : "prepare"), null);
        }
        Object thread = call(Class.forName("android.app.ActivityThread").getMethod("systemMain"), null);
        Object context = call(Class.forName("android.app.ActivityThread").getMethod("getSystemContext"), thread);
        packageManager = call(Class.forName("android.content.Context").getMethod("getPackageManager"), context);
        if (packageManager == null) throw new IOException("Android PackageManager is unavailable");
        pmType = Class.forName("android.content.pm.PackageManager");
        socketType = Class.forName("android.net.LocalSocket");
    }

    public static void main(String[] args) {
        CompanionServer server = null;
        try {
            AndroidBackend.checkRootAndSdk();
            if (args.length != 2 || !"--module".equals(args[0])) throw new IllegalArgumentException("Expected --module DIRECTORY");
            server = new CompanionServer(Paths.get(args[1]));
            server.serve();
        } catch (Exception error) {
            System.err.println("Haaru companion: " + message(error));
        } finally { if (server != null) server.close(); }
    }

    private void serve() throws Exception {
        if (!active()) throw new IOException("Haaru module is inactive or updating");
        Class<?> serverType = Class.forName("android.net.LocalServerSocket");
        listener = serverType.getConstructor(String.class).newInstance(CompanionProtocol.SOCKET_NAME);
        pid = String.valueOf(call(Class.forName("android.os.Process").getMethod("myPid"), null));
        Path data = module.resolve("data"); Controller.rejectLinks(data); Files.createDirectories(data);
        Path pidFile = data.resolve("companion.pid"); Controller.rejectLinks(pidFile);
        Files.write(pidFile, (pid + "\n").getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        System.out.println("Haaru companion listening (protocol 1)");
        Thread lifecycle = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get()) {
                    try { if (!active()) { close(); break; } Thread.sleep(2000); }
                    catch (Exception error) { close(); break; }
                }
            }
        }, "haaru-module-lifecycle");
        lifecycle.setDaemon(true); lifecycle.start();
        while (running.get()) {
            Object socket;
            try { socket = call(serverType.getMethod("accept"), listener); }
            catch (Exception error) { if (!running.get()) break; throw error; }
            try {
                call(socketType.getMethod("setSoTimeout", int.class), socket, 5000);
                OutputStream output = (OutputStream) call(socketType.getMethod("getOutputStream"), socket);
                if (!active()) throw new IOException("Haaru module is inactive or updating");
                if (!authenticate(socket)) throw new SecurityException("The App does not match this Haaru module");
                InputStream input = (InputStream) call(socketType.getMethod("getInputStream"), socket);
                String[] args = CompanionProtocol.request(input);
                if (!active()) throw new IOException("Haaru module is inactive or updating");
                String boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), StandardCharsets.US_ASCII).trim();
                Controller controller = new Controller(module.resolve("data"), new AndroidBackend(module.resolve("data/global-shadow.json"), boot), boot, 0);
                Map<String, Object> result;
                try { result = controller.run(args); }
                catch (Exception error) { result = controller.failure(error); }
                CompanionProtocol.response(output, result);
            } catch (Exception error) {
                try {
                    Map<String, Object> result = new LinkedHashMap<String, Object>();
                    result.put("ok", false); result.put("verified", false); result.put("error", message(error));
                    CompanionProtocol.response((OutputStream) call(socketType.getMethod("getOutputStream"), socket), result);
                } catch (Exception ignored) {}
            } finally { try { call(socketType.getMethod("close"), socket); } catch (Exception ignored) {} }
        }
    }

    private boolean authenticate(Object socket) throws Exception {
        Object credentials = call(socketType.getMethod("getPeerCredentials"), socket);
        int uid = ((Number) call(Class.forName("android.net.Credentials").getMethod("getUid"), credentials)).intValue();
        if (uid < 10000) return false;
        String[] packages = (String[]) call(pmType.getMethod("getPackagesForUid", int.class), packageManager, uid);
        if (packages == null || packages.length != 1 || !CompanionProtocol.PACKAGE.equals(packages[0])) return false;
        Object info = call(pmType.getMethod("getPackageInfo", String.class, int.class), packageManager, CompanionProtocol.PACKAGE, 0x08000000);
        Class<?> infoType = Class.forName("android.content.pm.PackageInfo");
        Object application = infoType.getField("applicationInfo").get(info);
        int installedUid = Class.forName("android.content.pm.ApplicationInfo").getField("uid").getInt(application);
        Object signing = infoType.getField("signingInfo").get(info);
        if (signing == null) return false;
        Object[] signatures = (Object[]) call(Class.forName("android.content.pm.SigningInfo").getMethod("getApkContentsSigners"), signing);
        if (signatures == null) return false;
        String[] digests = new String[signatures.length];
        Method bytes = Class.forName("android.content.pm.Signature").getMethod("toByteArray");
        for (int i = 0; i < signatures.length; i++) digests[i] = hex(MessageDigest.getInstance("SHA-256").digest((byte[]) call(bytes, signatures[i])));
        return CompanionProtocol.authorized(uid, installedUid, packages, digests, certificate);
    }

    private boolean active() throws Exception {
        Controller.rejectLinks(module);
        return Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(module.resolve("disable"), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(module.resolve("remove"), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(module.resolve("update"), LinkOption.NOFOLLOW_LINKS)
                && version.equals(moduleVersion()) && certificate.equals(certificate());
    }

    private String moduleVersion() throws Exception {
        Properties value = properties(module.resolve("module.prop"));
        if (!"haaru".equals(value.getProperty("id"))) throw new IOException("Haaru module is absent");
        return value.getProperty("version", "") + ":" + value.getProperty("versionCode", "");
    }
    private String certificate() throws Exception {
        Properties value = properties(module.resolve("companion.prop"));
        String pin = value.getProperty("certificateSha256", "");
        if (!"1".equals(value.getProperty("protocol")) || !CompanionProtocol.PACKAGE.equals(value.getProperty("package")) || !pin.matches("[0-9a-f]{64}"))
            throw new IOException("Haaru companion identity is unavailable");
        return pin;
    }
    private static Properties properties(Path path) throws Exception {
        Controller.rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) throw new IOException("Haaru module metadata is unavailable");
        Properties value = new Properties();
        value.load(new StringReader(CompanionProtocol.decode(Files.readAllBytes(path)))); return value;
    }
    private void close() {
        if (!running.getAndSet(false)) return;
        if (listener != null) try { call(Class.forName("android.net.LocalServerSocket").getMethod("close"), listener); } catch (Exception ignored) {}
        if (pid != null) try {
            Path path = module.resolve("data/companion.pid"); Controller.rejectLinks(path);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && pid.equals(new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim())) Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) { out.append(Character.forDigit((value & 255) >>> 4, 16)); out.append(Character.forDigit(value & 15, 16)); }
        return out.toString();
    }
    private static String message(Exception error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return text.substring(0, Math.min(text.length(), 1200));
    }
    private static Object call(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Android module IPC failed", cause);
        }
    }
}
