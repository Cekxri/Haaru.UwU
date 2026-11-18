package com.github.haaru.ksu;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded module IPC. Arguments are passed to Controller, never interpreted by a shell. */
public final class CompanionProtocol {
    public static final String SOCKET_NAME = "haaru.control.v1";
    public static final String PACKAGE = "dev.cekxri.haaru";
    public static final int REQUEST_LIMIT = 8192, RESPONSE_LIMIT = 65536;
    private CompanionProtocol() {}

    public static String[] request(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) >= 0;) {
            if (value == '\n') return arguments(decode(bytes.toByteArray()));
            if (bytes.size() >= REQUEST_LIMIT) throw new IOException("Module request exceeds its size limit");
            bytes.write(value);
        }
        throw new IOException("Incomplete module request");
    }

    static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static String[] arguments(String json) throws IOException {
        Object value;
        try { value = Json.decode(json); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid module request JSON", invalid); }
        if (!(value instanceof Map)) throw new IOException("Module request must be an object");
        Map<?, ?> object = (Map<?, ?>) value;
        Object protocol = object.get("protocol"), raw = object.get("args");
        if (object.size() != 2 || !(protocol instanceof BigDecimal)
                || ((BigDecimal) protocol).compareTo(BigDecimal.ONE) != 0 || !(raw instanceof List))
            throw new IOException("Unsupported module request protocol");
        List<?> items = (List<?>) raw;
        if (items.isEmpty() || items.size() > 4) throw new IOException("Invalid module argument count");
        String[] args = new String[items.size()];
        for (int i = 0; i < args.length; i++) {
            if (!(items.get(i) instanceof String)) throw new IOException("Module arguments must be strings");
            args[i] = (String) items.get(i);
        }
        String op = args[0];
        if (Arrays.asList("status", "enable", "disable", "refresh", "clear", "prefs").contains(op)) {
            if (args.length != 1) throw new IOException("Unexpected module arguments");
        } else if ("reset".equals(op) || "forget".equals(op)) {
            if (args.length != 2 || !args[1].matches("[0-7]")) throw new IOException("Invalid SIM slot");
        } else if ("set".equals(op)) {
            if (args.length != 4 || !args[1].matches("[0-7]") || !args[2].matches("-|[A-Z]{2}")
                    || !("-".equals(args[3]) || args[3].matches("(?:[0-9a-f]{2}){1,320}")))
                throw new IOException("Invalid SIM settings request");
        } else if ("saveprefs".equals(op)) {
            if (args.length != 2 || !args[1].matches("(?:[0-9a-f]{2})+")) throw new IOException("Invalid appearance request");
        } else throw new IOException("Unsupported module operation");
        return args;
    }

    public static void response(OutputStream output, Map<String, Object> value) throws IOException {
        byte[] bytes = Json.encode(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RESPONSE_LIMIT) throw new IOException("Module response exceeds its size limit");
        output.write(bytes); output.write('\n'); output.flush();
    }

    static boolean authorized(int peerUid, int packageUid, String[] packages, String[] signers, String pin) {
        if (peerUid < 10000 || peerUid != packageUid || packages == null || packages.length != 1
                || !PACKAGE.equals(packages[0]) || signers == null || signers.length != 1
                || pin == null || !pin.matches("[0-9a-f]{64}") || !signers[0].matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII), signers[0].getBytes(StandardCharsets.US_ASCII));
    }
}
