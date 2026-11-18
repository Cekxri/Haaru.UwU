/* SPDX-License-Identifier: Apache-2.0 */
#include "../src/global_properties.hpp"
#include "../src/engine_diagnostics.hpp"
#include <iostream>
#include <stdexcept>

namespace {
int checks = 0;
void check(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
template <typename Exception, typename Function>
void rejects(Function run, const char* message) {
    bool rejected = false;
    try { run(); } catch (const Exception&) { rejected = true; }
    check(rejected, message);
}
}
int main() {
    using haaru::PropertyProfile;
    using haaru::overlayProperty;
    using haaru::splitProperty;
    using haaru::joinProperty;
    check(splitProperty("") == std::vector<std::string>{""}, "empty string retains empty slot zero");
    check(splitProperty("tw,,jp,") == std::vector<std::string>{"tw", "", "jp", ""}, "empty and trailing slots retained");
    check(splitProperty(R"(A\,B,C\\D)") == std::vector<std::string>{"A,B", "C\\D"}, "escaped comma and backslash decoded");
    check(joinProperty({"A,B", "C\\D", ""}) == R"(A\,B,C\\D,)", "commas and backslashes encoded without losing trailing slot");
    const std::vector<std::string> decoded{"", "A,B", "C\\D", "\xe5\x8f\xb0\xe7\x81\xa3", ""};
    check(splitProperty(joinProperty(decoded)) == decoded, "Unicode and empty slot codec round trip");
    check(overlayProperty("tw,jp,kr", {{0, "US", ""}}, true) == "us,jp,kr", "only selected country changes and lowercase is stable");
    check(overlayProperty("tw,jp,kr", {{2, "JP", ""}, {0, "US", ""}}, true) == "us,jp,jp", "out of order multi SIM configuration uses logical slots");
    check(overlayProperty("tw,,kr,", {{2, "US", ""}}, true) == "tw,,us,", "unselected empty slots retained");
    check(overlayProperty("tw,jp", {{1, "", "Carrier"}}, true) == "tw,jp", "omitted country preserves natural list");
    check(overlayProperty("Original0,Original1", {{1, "JP", ""}}, false) == "Original0,Original1", "omitted name preserves natural list");
    check(overlayProperty("Original0,Original1,Original2", {{1, "", "U,S\\Carrier"}}, false) == R"(Original0,U\,S\\Carrier,Original2)", "configured operator punctuation cannot shift SIM positions");
    check(overlayProperty(R"(A\,B,C\\D,)", {}, false) == R"(A\,B,C\\D,)", "canonical untouched property stays byte identical");
    check(overlayProperty(R"(Legacy\Q,tail\)", {}, false) == R"(Legacy\Q,tail\)", "disabled overlay preserves even noncanonical original bytes");
    check(overlayProperty("tw", {{7, "JP", ""}}, true) == "tw,,,,,,,jp", "sparse high slot retains intermediate empty positions");
    check(overlayProperty("tw", {{7, "", ""}}, true) == "tw", "empty omitted fields do not fabricate slots");
    const std::string maximum(91, 'A');
    check(overlayProperty("", {{0, "", maximum}}, false) == maximum, "91 byte single property value accepted");
    rejects<std::length_error>([] { overlayProperty("", {{0, "", std::string(92, 'A')}}, false); }, "92 byte value rejected");
    check(overlayProperty("", {{0, "", std::string(45, ',')}}, false).size() == 90, "escaped punctuation counts encoded bytes");
    rejects<std::length_error>([] { overlayProperty("", {{0, "", std::string(46, ',')}}, false); }, "escape expansion cannot overflow property");
    check(overlayProperty(std::string(89, 'A') + ",X", {{1, "", "B"}}, false).size() == 91, "whole multi SIM list may use exactly 91 bytes");
    rejects<std::length_error>([] { overlayProperty(std::string(90, 'A') + ",X", {{1, "", "B"}}, false); }, "whole list separator included in 92 byte rejection");
    std::string unicode;
    for (int i = 0; i < 30; ++i) unicode += "\xe5\x8f\xb0";
    check(overlayProperty("", {{0, "", unicode}}, false).size() == 90, "Unicode property bound uses UTF8 bytes");
    rejects<std::length_error>([&unicode] { overlayProperty("", {{0, "", unicode + "\xe5\x8f\xb0"}}, false); }, "31 three-byte characters exceed property bound");
    rejects<std::invalid_argument>([] { overlayProperty("tw", {{-1, "US", ""}}, true); }, "negative logical slot rejected");
    rejects<std::invalid_argument>([] { overlayProperty("tw", {{8, "US", ""}}, true); }, "logical slot above supported bound rejected");
    const std::string malformedUtf8("\xed\xa0\x80", 3); // Lone UTF-16 surrogate encoded by a JNI converter.
    nlohmann::json badShadow{{"bootId", "fixture-boot"}, {"countryList", "tw"}, {"nameList", malformedUtf8}};
    check(!haaru::rustValidShadow(badShadow, "fixture-boot"), "invalid UTF8 serialization is contained before JNI shadow validation");
    nlohmann::json badRuntime{{"protocol", 2}, {"global", true}, {"engine", "system-global-volatile"},
        {"bootId", "fixture-boot"}, {"profiles", nlohmann::json::array({{{"slot", 0}, {"subId", 1}, {"country", "us"}, {"carrierName", malformedUtf8}}})}};
    check(!haaru::rustValidRuntime(badRuntime, "fixture-boot"), "invalid UTF8 runtime serialization cannot escape the native boundary");
    badShadow["nameList"] = "Original";
    check(haaru::rustValidShadow(badShadow, "fixture-boot"), "valid JNI shadow reaches and passes the Rust validator");
    check(haaru::resolveDeviceSdk(36, "35") == 36, "positive actual API result wins over property fallback");
    check(haaru::resolveDeviceSdk(-1, "36") == 36, "unavailable NDK SDK uses the actual Android property");
    check(haaru::resolveDeviceSdk(0, "35") == 35, "zero NDK SDK can use a positive actual property");
    for (const std::string invalid : {"", "-1", "0", " 36", "+36", "36x", "999999999999999999999"})
        check(haaru::resolveDeviceSdk(-1, invalid) == 0, "invalid SDK property cannot manufacture Android compatibility");
    auto receipt = haaru::engineAck(false, false, false, 1, 36, "fixture-boot", "hash-7", 123456, 2870,
        "dex-cookie-access", "DexFile.mCookie unavailable: java.lang.NoSuchFieldError");
    check(receipt["sdk"] == 36 && receipt["phonePid"] == 2870 && receipt["timestampMs"] == 123456,
        "bootstrap failure contains the real SDK, phone PID and timestamp");
    auto disconnected = haaru::disconnectedAck(receipt, 36, "fixture-boot", 123457, 2870);
    check(disconnected["error"] == receipt["error"] && disconnected["stage"] == "dex-cookie-access",
        "companion EOF retains the precise first bootstrap error and stage");
    check(disconnected["configHash"] == "hash-7" && disconnected["hooks"] == 1,
        "disconnect retains last observed config hash and partial hook count");
    check(!disconnected["ok"].get<bool>() && !disconnected["initialized"].get<bool>() && !disconnected["applied"].get<bool>(),
        "complete disconnect metadata never claims engine success");
    auto healthy = haaru::engineAck(true, true, true, 14, 36, "fixture-boot", "hash-8", 123458, 2870, "runtime", "", 14, true, true, true);
    for (int discovered : {3, 7, 19}) {
        auto adaptive = haaru::engineAck(true, true, true, discovered, 29, "fixture-boot", "hash-8", 123458, 2870, "runtime", "", discovered, true, true, true);
        check(adaptive["ok"].get<bool>(), "complete discovered coverage is independent of a fixed Android-version hook count");
    }
    check(!haaru::engineAck(true, true, true, 14, 36, "fixture-boot", "hash", 123458, 2870, "runtime", "", 13, true, true, true)["ok"].get<bool>(),
        "installed hook count must exactly match discovered reader coverage");
    check(!haaru::engineAck(true, true, true, 14, 36, "fixture-boot", "hash", 123458, 2870, "runtime", "", 14, false, true, true)["ok"].get<bool>(),
        "a count alone cannot prove the reader coverage contract");
    check(!haaru::engineAck(true, true, true, 2, 36, "fixture-boot", "hash", 123458, 2870, "runtime", "", 2, true, true, true)["ok"].get<bool>(),
        "attach plus both reader categories require at least three hooks");
    disconnected = haaru::disconnectedAck(healthy, 36, "fixture-boot", 123459, 2870);
    check(disconnected["error"] == "Phone engine disconnected" && !disconnected["ok"].get<bool>(),
        "EOF invalidates a previously healthy receipt");
    auto noPid = haaru::engineAck(true, true, true, 14, 36, "fixture-boot", "hash-8", 123459, 0, "companion", "");
    check(!noPid["ok"].get<bool>() && !noPid["initialized"].get<bool>() && noPid["phonePid"] == 0,
        "unknown phone identity cannot be replaced by the companion PID or report success");
    auto noSdk = haaru::engineAck(true, true, true, 14, 0, "fixture-boot", "hash-8", 123459, 2870,
        "companion", "Cannot determine Android SDK: NDK API=-1, ro.build.version.sdk=");
    check(noSdk["sdk"] == 0 && !noSdk["ok"].get<bool>() && noSdk["error"].get<std::string>().find("NDK API=-1") != std::string::npos,
        "unknown actual SDK remains an explicit failure");
    check(haaru::boundedDiagnostic("A\xe5\x8f\xb0" "B", 3) == "A", "diagnostic byte bound never splits UTF8");
    check(haaru::boundedDiagnostic(std::string_view("line\nzero\0tail", 14)) == "line zero tail", "diagnostic controls cannot inject multiline status entries");
    check(haaru::boundedDiagnostic(std::string(4096, 'x')).size() == 2048, "oversized bootstrap reason stays inside the companion frame limit");
    std::cout << "Global property host contract: " << checks << " checks passed (no Android property service/device claim)\n";
}
