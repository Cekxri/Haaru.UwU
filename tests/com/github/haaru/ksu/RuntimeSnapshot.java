package com.github.haaru.ksu;

import java.nio.file.*;
import java.util.*;

/** Emits actual Controller protocol-2 JSON for host native-contract fixtures, never a device claim. */
public final class RuntimeSnapshot {
    private static final class ReadOnlyFixture implements Backend {
        public List<Subscription> subscriptions() {
            return Arrays.asList(new Subscription(0, 10, "strong:fixture-A"), new Subscription(1, 20, "strong:fixture-B"));
        }
        public Values read(int id) { return new Values("", false, "Original " + id, true); }
        public String simCountry(int id) { return "tw"; }
        public int defaultSubId() { return 10; }
    }
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "active" : args[0];
        if (args.length > 1 || !Arrays.asList("active", "empty", "disabled", "removed").contains(mode))
            throw new IllegalArgumentException("Usage: RuntimeSnapshot [active|empty|disabled|removed]");
        Path module = Files.createTempDirectory("haaru-global-runtime-fixture-");
        Controller controller = new Controller(module.resolve("data"), new ReadOnlyFixture(), "fixture-boot", 0);
        if ("active".equals(mode)) {
            controller.run("set", "0", "US", "41542654");
            controller.run("set", "1", "JP", "4e5454");
        } else if ("disabled".equals(mode) || "removed".equals(mode)) {
            Files.write(module.resolve("disabled".equals(mode) ? "disable" : "remove"), new byte[0]);
        }
        System.out.println(Json.encode(controller.run("runtime")));
    }
}
