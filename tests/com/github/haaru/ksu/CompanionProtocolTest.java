package com.github.haaru.ksu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host coverage for the actual IPC frame parser and package/signature authorisation rules. */
public final class CompanionProtocolTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static String[] frame(String value) throws IOException {
        return CompanionProtocol.request(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void reject(String value) throws Exception {
        try { frame(value); throw new AssertionError("Invalid frame accepted"); } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(new String[] {"status"}, frame("{\"protocol\":1,\"args\":[\"status\"]}\n")), "status frame reaches Controller arguments");
        check(Arrays.equals(new String[] {"set", "1", "GB", "6769666667616666"}, frame("{\"protocol\":1,\"args\":[\"set\",\"1\",\"GB\",\"6769666667616666\"]}\n")), "carrier hex is literal data");
        String token = String.join("", Collections.nCopies(64, "e"));
        String bound = "{\"protocol\":1,\"args\":[\"set\",\"1\",\"GB\",\"-\",\"" + token + "\"]}\n";
        check(Arrays.equals(new String[] {"set", "1", "GB", "-", token}, frame(bound)),
                "Status token is forwarded verbatim to Controller");
        reject(bound.replace(token, ""));
        reject(bound.replace(token, token.substring(1)));
        reject(bound.replace(token, token.toUpperCase(Locale.ROOT)));
        reject(bound.replace(token, token + "0"));
        reject(bound.replace("\"" + token + "\"", "null"));
        reject(bound.replace("\"" + token + "\"", "\"" + token + "\",\"extra\""));
        reject("{\"protocol\":1,\"args\":[\"reset\",\"1\",\"" + token + "\"]}\n");
        for (String value : new String[] {
                "{\"protocol\":2,\"args\":[\"status\"]}\n", "{\"protocol\":1.5,\"args\":[\"status\"]}\n",
                "{\"protocol\":\"1\",\"args\":[\"status\"]}\n", "{\"protocol\":1,\"args\":[]}\n",
                "{\"protocol\":1,\"args\":[\"status\"],\"module\":\"/other\"}\n", "{\"protocol\":1,\"args\":[\"status;id\"]}\n",
                "{\"protocol\":1,\"args\":[\"runtime\"]}\n", "{\"protocol\":1,\"args\":[\"set\",\"8\",\"GB\",\"-\"]}\n",
                "{\"protocol\":1,\"args\":[\"set\",\"0\",\"GB\",\"hello\"]}\n", "{\"protocol\":1,\"args\":[\"reset\",0]}\n",
                "{\"protocol\":1,\"args\":[\"prefs\",\"/outside\"]}\n", "{\"protocol\":1,\"args\":[\"saveprefs\",\"a\"]}\n",
                "{\"protocol\":1,\"args\":[\"status\"]}", "{invalid}\n"}) reject(value);
        char[] large = new char[CompanionProtocol.REQUEST_LIMIT + 1]; Arrays.fill(large, ' ');
        reject(new String(large) + "\n");
        try { CompanionProtocol.request(new ByteArrayInputStream(new byte[] {(byte) 0xc0, (byte) 0xaf, '\n'})); throw new AssertionError("Malformed UTF8 accepted"); }
        catch (IOException expected) { checks++; }
        String pin = String.join("", Collections.nCopies(64, "a"));
        String wrong = String.join("", Collections.nCopies(64, "b"));
        check(CompanionProtocol.authorized(12345, 12345, new String[] {CompanionProtocol.PACKAGE}, new String[] {pin}, pin), "actual package UID and installed signer authenticate");
        check(!CompanionProtocol.authorized(0, 0, new String[] {CompanionProtocol.PACKAGE}, new String[] {pin}, pin), "root peer is not the companion App");
        check(!CompanionProtocol.authorized(12346, 12345, new String[] {CompanionProtocol.PACKAGE}, new String[] {pin}, pin), "client supplied package name cannot replace SO_PEERCRED UID");
        check(!CompanionProtocol.authorized(12345, 12345, new String[] {"other.app"}, new String[] {pin}, pin), "same signer with another package cannot access the service");
        check(!CompanionProtocol.authorized(12345, 12345, new String[] {CompanionProtocol.PACKAGE, "other.app"}, new String[] {pin}, pin), "shared UID cannot borrow companion authorisation");
        check(!CompanionProtocol.authorized(12345, 12345, new String[] {CompanionProtocol.PACKAGE}, new String[] {wrong}, pin), "replacement APK with a different signer is rejected");
        check(!CompanionProtocol.authorized(12345, 12345, new String[] {CompanionProtocol.PACKAGE}, new String[] {pin, wrong}, pin), "multiple signer APK cannot bypass the release pin");
        check(!CompanionProtocol.authorized(12345, 12345, null, new String[] {pin}, pin), "missing installed UID ownership fails closed");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("ok", true);
        CompanionProtocol.response(output, result);
        check("{\"ok\":true}\n".equals(output.toString("UTF-8")), "response is ordinary bounded Controller JSON plus newline");
        result.put("error", new String(large) + new String(new char[CompanionProtocol.RESPONSE_LIMIT]));
        try { CompanionProtocol.response(output, result); throw new AssertionError("Oversized response accepted"); }
        catch (IOException expected) { checks++; }
        System.out.println("Companion IPC host contract: " + checks + " checks passed (actual Android/SELinux access verified separately)");
    }
}
