package com.github.haaru.ksu;
import java.nio.file.*;
import java.util.*;
public final class NativeRuntimeFixture {
    public static void main(String[] args) throws Exception {
        Path module = Paths.get("build/native-contract/module");
        Files.createDirectories(module);
        Files.deleteIfExists(module.resolve("remove"));
        Backend phone = new Backend() {
            public List<Subscription> subscriptions() { return Arrays.asList(new Subscription(0, 21, "strong:fixture")); }
            public Values read(int id) { return new Values("", false, "Real SIM", true); }
            public String simCountry(int id) { return "tw"; }
            public int defaultSubId() { return 21; }
        };
        Controller controller = new Controller(module.resolve("data"), phone, "fixture-boot", 1000);
        controller.run("set", "0", "US", "542d4d6f62696c6520555341");
        Map<String,Object> active = controller.run("runtime");
        Files.write(module.resolve("remove"), new byte[0]);
        Map<String,Object> removed = controller.run("runtime");
        System.out.println(Json.encode(Arrays.asList(active, removed)));
    }
}
