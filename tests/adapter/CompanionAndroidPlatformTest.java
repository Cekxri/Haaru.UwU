package com.github.haaru.ksu;

import android.app.ActivityThread;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Executes production AndroidPlatform reflection and IO; it never starts Android/pm. */
public final class CompanionAndroidPlatformTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path module = Files.createTempDirectory("haaru-framework-install-");
        Files.write(module.resolve("module.prop"), "id=haaru\nname=Haaru\nauthor=Cekxri\nversion=0.7.1\nversionCode=12\n".getBytes(StandardCharsets.UTF_8));
        Path apk = module.resolve("app/Haaru.apk"); Files.createDirectories(apk.getParent());
        byte[] bytes = "synthetic APK content for exact IO".getBytes(StandardCharsets.UTF_8); Files.write(apk, bytes);
        byte[] signer = "synthetic signer DER bytes".getBytes(StandardCharsets.UTF_8);
        String cert = hex(MessageDigest.getInstance("SHA-256").digest(signer));
        PackageInfo info = new PackageInfo(); info.packageName = CompanionProtocol.PACKAGE; info.versionName = "0.7.1"; info.versionCode = 12;
        info.signingInfo = new SigningInfo(new Signature(signer)); info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.sourceDir = apk.toString(); info.applicationInfo.uid = 10123;
        info.applicationInfo.metaData = new Bundle(); info.applicationInfo.metaData.putInt(CompanionInstallation.PROTOCOL_KEY, 1);
        PackageManager.reset(); PackageManager.archive = info; ActivityThread.reset(); Looper.reset(); ServiceManager.clear();
        final StubProcess process = new StubProcess(bytes, "Success\n", "", 0, info);
        final List<String> command = new ArrayList<String>();
        CompanionInstallation.ProcessStarter starter = new CompanionInstallation.ProcessStarter() {
            @Override public java.lang.Process start(String[] args) {
                command.addAll(Arrays.asList(args));
                PmInstallOptionFixture parsed = PmInstallOptionFixture.parse(args);
                if (parsed.exitCode != 0)
                    return new StubProcess(bytes, "", "Synthetic parser rejection: " + parsed.error + "\n", parsed.exitCode, null, true);
                return process;
            }
        };
        expectPending(starter);
        ServiceManager.register("package", new IBinder() {});
        CompanionInstallation.AndroidPlatform platform = new CompanionInstallation.AndroidPlatform(starter);
        CompanionInstallation.Info archive = platform.bundled(apk);
        check(archive.protocol == 1 && archive.certificate.equals(cert), "actual reflected archive getter reads inherited typed metadata and signer DER");
        check(PackageManager.archiveFlags == 0x08000080, "production getter requests real Android signing and metadata flags");
        check(platform.installed(CompanionProtocol.PACKAGE) == null, "actual framework NameNotFound means absent, not permanent error");
        CompanionInstallation.Plan plan = new CompanionInstallation.Plan(apk, CompanionProtocol.PACKAGE, "0.7.1", 12, cert, 1, CompanionInstallation.hash(apk), bytes.length);
        Map<String, Object> result = CompanionInstallation.run(plan, platform);
        check("installed".equals(result.get("state")) && Boolean.TRUE.equals(result.get("verified")), "production install method and reflection readback complete successfully");
        check(command.equals(Arrays.asList("/system/bin/pm", "install", "-r", "--user", "0", "-S", Integer.toString(bytes.length))), "real method passes fixed sized stdin argv without a lone dash or downgrade flag");
        PmInstallOptionFixture parsed = PmInstallOptionFixture.parse(command.toArray(new String[0]));
        check(parsed.exitCode == 0 && parsed.stdin && parsed.size == bytes.length && parsed.user == 0,
                "actual production argv passes Android BasicShell makeInstallParams and selects sized stdin");
        List<String> previousCommand = new ArrayList<String>(command); previousCommand.add("-");
        PmInstallOptionFixture previous = PmInstallOptionFixture.parse(previousCommand.toArray(new String[0]));
        check(previous.exitCode == 255 && "Unknown option -".equals(previous.error),
                "the released lone-dash argv reproduces the framework option-parser rejection");
        List<String> terminatedCommand = new ArrayList<String>(command); terminatedCommand.add("--"); terminatedCommand.add("-");
        PmInstallOptionFixture terminated = PmInstallOptionFixture.parse(terminatedCommand.toArray(new String[0]));
        check(terminated.exitCode == 0 && terminated.stdin && terminated.size == bytes.length,
                "a dash after the actual framework option terminator is a path, not an option");
        check(Arrays.equals(bytes, process.input.toByteArray()), "actual asynchronous task streams all APK bytes before waiting");
        check(PackageManager.installedFlags == 0x08000080 && process.destroyed, "post-install signer/metadata readback and process cleanup execute");
        PackageManager.installed = null;
        final StubProcess[] parserRejected = new StubProcess[1];
        platform = new CompanionInstallation.AndroidPlatform(new CompanionInstallation.ProcessStarter() {
            @Override public java.lang.Process start(String[] args) {
                List<String> oldArgs = new ArrayList<String>(Arrays.asList(args)); oldArgs.add("-");
                PmInstallOptionFixture rejected = PmInstallOptionFixture.parse(oldArgs.toArray(new String[0]));
                parserRejected[0] = new StubProcess(bytes, "", "Synthetic parser rejection: " + rejected.error + "\n", rejected.exitCode, null, true);
                return parserRejected[0];
            }
        });
        result = CompanionInstallation.run(plan, platform);
        check("error".equals(result.get("state")) && "package-install".equals(result.get("stage"))
                && result.get("message").toString().contains("exit=255"),
                "old argv parser failure reaches production package-install diagnostics even after an early Broken pipe");
        Path log = module.resolve("data/companion-install-debug.log");
        String diagnostic = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        check(diagnostic.contains("Synthetic parser rejection: Unknown option -") && Files.size(log) <= 32768
                && PackageManager.installed == null && parserRejected[0].destroyed,
                "parser stderr remains bounded and private, with no installed success or leaked process");
        platform = new CompanionInstallation.AndroidPlatform(starter);
        PackageManager.installed = null; info.applicationInfo.metaData.putString(CompanionInstallation.PROTOCOL_KEY, "1");
        result = CompanionInstallation.run(plan, platform);
        check("error".equals(result.get("state")) && "archive-parse".equals(result.get("stage")), "wrong-typed metadata cannot be silently treated as compatible");
        info.applicationInfo.metaData.putInt(CompanionInstallation.PROTOCOL_KEY, 1);
        final StubProcess failed = new StubProcess(bytes, "", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: synthetic detail]\n", 1, null);
        platform = new CompanionInstallation.AndroidPlatform(new CompanionInstallation.ProcessStarter() {
            @Override public java.lang.Process start(String[] args) { return failed; }
        });
        result = CompanionInstallation.run(plan, platform);
        check("error".equals(result.get("state")) && "package-install".equals(result.get("stage")), "production PM failure retains stage and does not become boot pending");
        check(result.get("message").toString().contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"), "safe public reason is preserved from stderr");
        diagnostic = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        check(diagnostic.contains("synthetic detail") && Files.size(log) <= 32768, "actual stderr survives in bounded module-private diagnostics");
        final StubProcess rejectedInput = new StubProcess(bytes, "", "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: synthetic early rejection]\n", 1, null, true);
        platform = new CompanionInstallation.AndroidPlatform(new CompanionInstallation.ProcessStarter() {
            @Override public java.lang.Process start(String[] args) { return rejectedInput; }
        });
        result = CompanionInstallation.run(plan, platform);
        check(result.get("message").toString().contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"), "early Broken pipe still exposes the actual PackageManager reason");
        diagnostic = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        check(diagnostic.contains("synthetic early rejection") && "package-install".equals(result.get("stage")), "stderr and stage are retained even when stdin Future fails first");
        platform = new CompanionInstallation.AndroidPlatform(new CompanionInstallation.ProcessStarter() {
            @Override public java.lang.Process start(String[] args) { throw new NoClassDefFoundError("synthetic unavailable bootstrap class"); }
        });
        result = CompanionInstallation.run(plan, platform);
        check("error".equals(result.get("state")) && "package-install".equals(result.get("stage")), "LinkageError is an explicit permanent error, not empty output/pending");
        System.out.println("Companion Android framework-shaped reflection and IO: " + checks + " checks passed; no device mutation");
    }
    private static void expectPending(CompanionInstallation.ProcessStarter starter) throws Exception {
        try { new CompanionInstallation.AndroidPlatform(starter); throw new AssertionError("Missing PM must defer"); }
        catch (CompanionInstallation.Pending expected) { checks++; }
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString(); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class StubProcess extends java.lang.Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        final byte[] expected; final InputStream output, errors; final int code; final PackageInfo installed;
        final boolean rejectInput; boolean destroyed;
        StubProcess(byte[] expected, String output, String errors, int code, PackageInfo installed) {
            this(expected, output, errors, code, installed, false);
        }
        StubProcess(byte[] expected, String output, String errors, int code, PackageInfo installed, boolean rejectInput) {
            this.expected = expected; this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.errors = new ByteArrayInputStream(errors.getBytes(StandardCharsets.UTF_8)); this.code = code; this.installed = installed;
            this.rejectInput = rejectInput;
        }
        @Override public OutputStream getOutputStream() {
            if (!rejectInput) return input;
            return new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("Broken pipe"); } };
        }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return errors; }
        @Override public int waitFor() { waitFor(1, TimeUnit.SECONDS); return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { if (code == 0 && !Arrays.equals(expected, input.toByteArray())) throw new AssertionError("pm did not receive exact APK"); if (code == 0) PackageManager.installed = installed; return true; }
        @Override public int exitValue() { return code; }
        @Override public void destroy() { destroyed = true; }
        @Override public java.lang.Process destroyForcibly() { destroyed = true; return this; }
    }
}
