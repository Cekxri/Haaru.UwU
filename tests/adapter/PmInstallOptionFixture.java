package com.github.haaru.ksu;

import com.android.modules.utils.BasicShellCommandHandler;
import java.util.Arrays;

/**
 * Independently written, restricted command fixture, not copied framework code.
 * Models makeInstallParams and sized stdin semantics checked in AOSP revisions
 * android-10.0.0_r1, android-13.0.0_r1 and android-16.0.0_r1:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
 */
final class PmInstallOptionFixture {
    final int exitCode, user;
    final long size;
    final boolean stdin;
    final String error;
    private PmInstallOptionFixture(int code, int user, long size, boolean stdin, String error) {
        this.exitCode = code; this.user = user; this.size = size; this.stdin = stdin; this.error = error;
    }
    static PmInstallOptionFixture parse(String[] command) {
        if (command.length < 2 || !command[0].equals("/system/bin/pm") || !command[1].equals("install"))
            throw new AssertionError("Unexpected production command");
        BasicShellCommandHandler shell = new BasicShellCommandHandler(Arrays.copyOfRange(command, 1, command.length), 1);
        int user = 0; long size = -1;
        try {
            for (String option; (option = shell.getNextOption()) != null;) {
                if (option.equals("-r")) continue;
                if (option.equals("--user")) user = Integer.parseInt(shell.getNextArgRequired());
                else if (option.equals("-S")) {
                    size = Long.parseLong(shell.getNextArgRequired());
                    if (size <= 0) throw new IllegalArgumentException("Size must be positive");
                } else throw new IllegalArgumentException("Unknown option " + option);
            }
            String[] paths = shell.getRemainingArgs();
            boolean stdin = paths.length == 0 || paths.length == 1 && paths[0].equals("-");
            if (!stdin || size <= 0) throw new IllegalArgumentException("Explicit stdin size required");
            return new PmInstallOptionFixture(0, user, size, true, "");
        } catch (IllegalArgumentException error) {
            // BasicShell exec returns -1 for this exception; the process status is 255.
            return new PmInstallOptionFixture(255, user, size, false, error.getMessage());
        }
    }
}
