package com.android.modules.utils;

import java.util.Arrays;

/**
 * Independently written minimal option fixture, not copied framework code.
 * Preserves Android 10/13/16 option semantics: a lone '-' is an option, while
 * '--' ends option parsing. No device trace or framework runtime is embedded.
 * Semantics checked against getNextOption/getNextArg in these AOSP revisions:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/os/ShellCommand.java
 * https://android.googlesource.com/platform/frameworks/libs/modules-utils/+/refs/tags/android-13.0.0_r1/java/com/android/modules/utils/BasicShellCommandHandler.java
 * https://android.googlesource.com/platform/frameworks/libs/modules-utils/+/refs/tags/android-16.0.0_r1/java/com/android/modules/utils/BasicShellCommandHandler.java
 */
public final class BasicShellCommandHandler {
    private final String[] args;
    private int position;
    private String currentData;
    public BasicShellCommandHandler(String[] args, int start) { this.args = args.clone(); position = start; }
    public String getNextOption() {
        if (currentData != null) throw new IllegalArgumentException("No argument expected after previous option");
        if (position >= args.length) return null;
        String arg = args[position];
        if (!arg.startsWith("-")) return null;
        position++;
        if (arg.equals("--")) return null;
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) { currentData = arg.substring(2); return arg.substring(0, 2); }
            currentData = null; return arg;
        }
        currentData = null; return arg;
    }
    public String getNextArg() {
        if (currentData != null) { String out = currentData; currentData = null; return out; }
        return position < args.length ? args[position++] : null;
    }
    public String getNextArgRequired() {
        String out = getNextArg(); if (out == null) throw new IllegalArgumentException("Argument required"); return out;
    }
    public String[] getRemainingArgs() { return Arrays.copyOfRange(args, position, args.length); }
}
