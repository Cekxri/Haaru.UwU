package com.github.haaru.ksu;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes the real migration helper for the KSU installation lifecycle fixture. */
public final class UpgradeFixture {
    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 2) throw new IllegalArgumentException("Expected SOURCE DESTINATION");
            System.out.println(Json.encode(Upgrade.migrate(Paths.get(args[0]), Paths.get(args[1]))));
        } catch (Exception failure) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", false); result.put("verified", false); result.put("error", failure.getMessage());
            System.out.println(Json.encode(result)); System.exit(2);
        }
    }
}
