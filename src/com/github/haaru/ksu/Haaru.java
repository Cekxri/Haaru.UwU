package com.github.haaru.ksu;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Haaru {
    /** Root helper owns module configuration only; phone-engine ACK confirms global application. */
    public static void main(String[] args) {
        Controller controller = null;
        try {
            AndroidBackend.checkRootAndSdk();
            if (args.length < 3 || !"--module".equals(args[0])) throw new IllegalArgumentException("Expected --module DIRECTORY OPERATION");
            Path module = validateModule(Paths.get(args[1]));
            String boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), StandardCharsets.US_ASCII).trim();
            controller = new Controller(module.resolve("data"),
                    new AndroidBackend(module.resolve("data/global-shadow.json"), boot), boot, 0L);
            Map<String, Object> result = controller.run(Arrays.copyOfRange(args, 2, args.length));
            System.out.println(Json.encode(result));
            if (!Boolean.TRUE.equals(result.get("ok"))) System.exit(2);
        } catch (Exception e) {
            Map<String, Object> result;
            if (controller != null) result = controller.failure(e);
            else {
                result = new LinkedHashMap<String, Object>(); result.put("ok", false);
                result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            System.out.println(Json.encode(result)); System.exit(2);
        }
    }

    /** A removed module must never be recreated by an old CLI process. */
    static Path validateModule(Path directory) throws Exception {
        Path module = directory.toAbsolutePath().normalize(); Controller.rejectLinks(module);
        if (!Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("Module directory is absent");
        Path metadata = module.resolve("module.prop"); Controller.rejectLinks(metadata);
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 16384)
            throw new java.io.IOException("Missing module metadata");
        Properties properties = new Properties();
        try (java.io.InputStream in = Files.newInputStream(metadata)) { properties.load(in); }
        if (!"haaru".equals(properties.getProperty("id"))) throw new java.io.IOException("Unexpected module id");
        return module;
    }
}

