package com.github.haaru.ksu;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Real installation state machine with a fake Android PackageManager; no phone is contacted. */
public final class CompanionInstallationTest {
    private static int assertions;
    private static final String CERT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("haaru-auto-app-");
        Path apk = root.resolve("app/Haaru.apk"); Files.createDirectories(apk.getParent()); Files.write(apk, "signed APK fixture".getBytes(StandardCharsets.UTF_8));
        CompanionInstallation.Plan plan = new CompanionInstallation.Plan(apk, CompanionProtocol.PACKAGE, "0.7.0", 11, CERT, 1,
                CompanionInstallation.hash(apk), Files.size(apk));
        Fake pm = new Fake(plan);
        check("installed".equals(run(plan, pm).get("state")) && pm.installs == 1, "new installation installs and reads back exact APK");
        pm = new Fake(plan); pm.current = pm.bundle;
        check("ready".equals(run(plan, pm).get("state")) && pm.installs == 0, "matching APK is never installed repeatedly");
        pm = new Fake(plan); pm.current = info(plan, "0.6.2", 10, CERT, 1, "older");
        check("installed".equals(run(plan, pm).get("state")) && pm.installs == 1, "same signer old APK is upgraded");
        pm = new Fake(plan); pm.current = info(plan, plan.version, plan.versionCode, CERT, 1, "another-build");
        check("installed".equals(run(plan, pm).get("state")), "same version stale APK is replaced with ZIP's exact artifact");
        pm = new Fake(plan); pm.current = info(plan, "0.8.0", 12, CERT, 1, "newer");
        check("newer-kept".equals(run(plan, pm).get("state")) && pm.installs == 0, "old ZIP keeps explicitly compatible newer APK without downgrade");
        pm = new Fake(plan); pm.current = info(plan, "0.8.0", 12, CERT, 2, "newer");
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0, "incompatible newer APK is kept and pairing error is explicit");
        pm = new Fake(plan); pm.current = info(plan, "0.8.0", 12, CERT, 0, "newer");
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0, "newer APK with no declared protocol is never guessed compatible");
        pm = new Fake(plan); pm.current = info(plan, "0.6.2", 10, "different-cert", 1, "older");
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0, "installed signer mismatch never reaches PackageManager install");
        pm = new Fake(plan); pm.bundle = info(plan, plan.version, plan.versionCode, "different-cert", 1, plan.apkSha256);
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0, "bundled APK signer is checked before writes");
        pm = new Fake(plan); pm.bundle = info(plan, plan.version, plan.versionCode, CERT, 0, plan.apkSha256);
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0, "bundle must declare IPC protocol");
        pm = new Fake(plan); pm.unavailable = true;
        check("pending".equals(run(plan, pm).get("state")) && pm.installs == 0, "PackageManager unavailable defers to boot without pretending success");
        pm = new Fake(plan); pm.failInstall = true;
        check("error".equals(run(plan, pm).get("state")), "PackageManager failure is not successful installation");
        pm = new Fake(plan); pm.after = info(plan, plan.version, plan.versionCode, CERT, 1, "wrong-bytes");
        check("error".equals(run(plan, pm).get("state")), "success response still requires actual package hash readback");
        pm = new Fake(plan); pm.afterMissing = true;
        check("pending".equals(run(plan, pm).get("state")), "unconfirmed package remains pending after install attempt");
        Files.write(apk, "corrupt APK fixture".getBytes(StandardCharsets.UTF_8)); pm = new Fake(plan);
        check("error".equals(run(plan, pm).get("state")) && pm.installs == 0 && pm.bundleReads == 0,
                "corrupt ZIP APK cannot cause a platform operation");
        Path status = root.resolve("data/companion-install.json"); Files.createDirectories(status.getParent());
        check("pending".equals(CompanionInstallation.readStatus(root).get("state")), "missing installation status is explicit pending");
        Files.write(status, "{\"state\":\"ready\",\"message\":\"ok\",\"verified\":true}".getBytes(StandardCharsets.UTF_8));
        check("ready".equals(CompanionInstallation.readStatus(root).get("state")), "installation status reads without changing SIM state");
        Files.write(status, "{broken}".getBytes(StandardCharsets.UTF_8));
        check("error".equals(CompanionInstallation.readStatus(root).get("state")), "corrupt status is not installed success");
        System.out.println("Companion automatic install lifecycle: " + assertions + " checks passed (host only; no Android install)");
    }
    private static Map<String, Object> run(CompanionInstallation.Plan plan, Fake pm) { return CompanionInstallation.run(plan, pm); }
    private static CompanionInstallation.Info info(CompanionInstallation.Plan plan, String version, long code, String cert, int protocol, String hash) {
        return new CompanionInstallation.Info(plan.packageName, version, code, cert, protocol, hash);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static final class Fake implements CompanionInstallation.Platform {
        CompanionInstallation.Info bundle, current, after;
        boolean unavailable, failInstall, afterMissing; int installs, bundleReads;
        Fake(CompanionInstallation.Plan plan) { bundle = info(plan, plan.version, plan.versionCode, plan.certificate, plan.protocol, plan.apkSha256); }
        public CompanionInstallation.Info bundled(Path apk) throws Exception { bundleReads++; if (unavailable) throw new CompanionInstallation.Pending("PM not ready"); return bundle; }
        public CompanionInstallation.Info installed(String packageName) { return current; }
        public void install(Path apk, long bytes) throws Exception { installs++; if (failInstall) throw new java.io.IOException("PM rejected APK"); current = afterMissing ? null : after == null ? bundle : after; }
    }
}
