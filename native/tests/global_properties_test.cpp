/* SPDX-License-Identifier: Apache-2.0 */
#include "../src/global_properties.hpp"
#include "../src/engine_diagnostics.hpp"
#include "../src/companion_connection.hpp"
#include "../src/companion_protocol.hpp"
#include "../src/bootstrap_window.hpp"
#include <array>
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
    haaru::BootstrapWindow startup;
    int heartbeats = 0;
    for (int elapsed = 0; elapsed < 60000; elapsed += 100) {
        if (startup.heartbeatDue(elapsed)) ++heartbeats;

    }
    check(!startup.expired(59999, false), "pending Context is not timed out early");
    check(heartbeats == 60, "missing attach callback does not leave companion idle for 60 seconds");
    check(startup.expired(60000, false) && !startup.expired(60000, true), "only unfinished startup expires");
    // Exercise the actual producer and consumer across JSON serialisation.
    // Previously Json{} emitted null before runtime.json existed at boot.
    const std::string boot = "fixture-boot";
    const nlohmann::json idlePlan{{"protocol", 2}, {"global", true}, {"engine", "system-global-volatile"},
        {"bootId", boot}, {"enabled", false}, {"readOnly", false}, {"profiles", nlohmann::json::array()}};
    const auto missing = haaru::companionRuntimeReply(true, nlohmann::json{}, boot, "", false, false, false);
    check(missing["config"].is_object() && missing["config"].empty() && missing["active"] == false,
        "missing boot plan produces an inactive empty object, never null");
    check(haaru::validCompanionRuntimeReply(nlohmann::json::parse(missing.dump()), boot),
        "missing boot plan survives real wire serialisation and validates the connection");
    auto legacyNull = missing; legacyNull["config"] = nullptr;
    check(!haaru::validCompanionRuntimeReply(legacyNull, boot), "reproduce the original null-schema rejection");
    const auto idle = haaru::companionRuntimeReply(true, idlePlan, boot, "idle-hash", false, false, false);
    check(haaru::validCompanionRuntimeReply(nlohmann::json::parse(idle.dump()), boot) &&
        idle["config"]["enabled"] == false && idle["config"]["profiles"].empty(),
        "a disabled empty plan proves connection health without enabling an override");
    auto activePlan = idlePlan; activePlan["enabled"] = true;
    activePlan["profiles"] = nlohmann::json::array({{{"slot", 0}, {"subId", 1}, {"country", "us"}, {"carrierName", "Fixture"}}});
    const auto configured = haaru::companionRuntimeReply(true, activePlan, boot, "plan-hash", true, false, false);
    check(configured["active"] == true && configured["config"] == activePlan &&
        haaru::validCompanionRuntimeReply(nlohmann::json::parse(configured.dump()), boot),
        "a plan published after idle bootstrap reaches the same connection without restart");
    for (const auto& invalid : {nlohmann::json{}, nlohmann::json::array(), nlohmann::json::object(),
            nlohmann::json::parse(R"({"protocol":2})")}) {
        const auto reply = haaru::companionRuntimeReply(true, invalid, boot, "", false, false, false);
        check(reply["active"] == false && haaru::validCompanionRuntimeReply(reply, boot),
            "rejected runtime data falls back to a healthy inactive response");
    }
    auto wrongBoot = activePlan; wrongBoot["bootId"] = "other-boot";
    check(haaru::companionRuntimeReply(true, wrongBoot, boot, "", false, false, false)["active"] == false,
        "stale boot cannot activate a plan");
    const auto disabledModule = haaru::companionRuntimeReply(false, activePlan, boot, "", false, false, true);
    check(disabledModule["config"].empty() && disabledModule["active"] == false &&
        disabledModule["recoveryRequired"] == true && haaru::validCompanionRuntimeReply(disabledModule, boot),
        "module disable and recovery metadata stay distinct from transport health");
    for (const char* key : {"active", "shadowSaved", "shadowPrepared", "recoveryRequired", "hash", "config"}) {
        auto malformed = missing; malformed.erase(key);
        check(!haaru::validCompanionRuntimeReply(malformed, boot), "missing reply fields cannot prove readiness");
    }
    auto forged = missing; forged["active"] = true;
    check(!haaru::validCompanionRuntimeReply(forged, boot), "active empty configuration is rejected");
    forged = configured; forged["config"]["bootId"] = "other-boot";
    check(!haaru::validCompanionRuntimeReply(forged, boot), "active foreign-boot response is rejected");
    using haaru::CompanionSocketIdentity;
    const CompanionSocketIdentity ownedSocket{7, 101, 1, true};
    int identityReads = 0, probes = 0;
    auto verifySocket = [&](CompanionSocketIdentity actual, bool reply) {
        return haaru::validateCompanionConnection(ownedSocket,
            [&] { ++identityReads; return actual; }, [&] { ++probes; return reply; });
    };
    check(verifySocket(ownedSocket, true) && identityReads == 1 && probes == 1,
        "the same live socket requires an actual successful protocol round trip");
    probes = 0;
    check(!verifySocket({}, true) && probes == 0, "a closed descriptor is rejected without sending or reading");
    check(!verifySocket({7, 102, 1, true}, true) && probes == 0,
        "a reused descriptor for another socket is rejected before protocol IO");
    check(!verifySocket({8, 101, 1, true}, true) && probes == 0,
        "the same inode number on another device does not identify the owned socket");
    check(!verifySocket({7, 101, 2, true}, true) && probes == 0,
        "a replaced socket type cannot pass the captured identity gate");
    check(!verifySocket({7, 101, 1, false}, true) && probes == 0,
        "a reused non-socket never reaches protocol IO even with matching numeric metadata");
    check(!verifySocket(ownedSocket, false) && probes == 1,
        "an intact socket with a closed peer or invalid protocol reply cannot claim connection readiness");
    int closes = 0;
    haaru::closeOwnedCompanion(ownedSocket, [&] { return CompanionSocketIdentity{}; }, [&] { ++closes; });
    haaru::closeOwnedCompanion(ownedSocket, [&] { return CompanionSocketIdentity{7, 102, 1, true}; }, [&] { ++closes; });
    check(closes == 0, "bootstrap and engine cleanup must not close a lost or reused descriptor");
    haaru::closeOwnedCompanion(ownedSocket, [&] { return ownedSocket; }, [&] { ++closes; });
    check(closes == 1, "cleanup closes the confirmed owned socket exactly once");
    auto priorFds = std::vector<int>{3, 6, 9};
    auto retainedFds = haaru::companionFdList(priorFds, 12);
    check(retainedFds == std::vector<int>({3, 6, 9, 12}) && priorFds == std::vector<int>({3, 6, 9}),
        "fallback retains all caller-owned descriptors and appends only the owned companion without mutating the input");
    check(haaru::companionFdList({3, 12, 9, 12}, 12) == std::vector<int>({3, 12, 9, 12}),
        "an already-retained socket is not duplicated and existing entries remain byte-for-byte ordered");
    check(haaru::companionFdList({}, 12) == std::vector<int>({12}), "an exposed null zygote array can retain exactly the companion socket");
    rejects<std::invalid_argument>([] { haaru::companionFdList({3}, -1); }, "invalid owned descriptor cannot enter the zygote list");
    rejects<std::invalid_argument>([] { haaru::companionFdList(std::vector<int>(4097, 3), 12); }, "oversized zygote list fails before replacement allocation");
    // Model provider sanitisation separately from its API result. The API may
    // return false even when the optional list retained the socket, or when a
    // specialise-only path performs no FD sanitation. Readiness always uses
    // the observed post identity and a round trip, never the provider bool.
    for (bool apiReported : {false, true}) {
        for (bool listAvailable : {false, true}) {
            auto fds = listAvailable ? haaru::companionFdList({3, 9}, 12) : std::vector<int>{3, 9};
            bool retainedByList = std::find(fds.begin(), fds.end(), 12) != fds.end();
            auto observed = retainedByList ? ownedSocket : CompanionSocketIdentity{};
            probes = 0;
            check(verifySocket(observed, true) == listAvailable && probes == (listAvailable ? 1 : 0),
                "both true and false exemption reports must pass the actual post-sanitisation socket gate");
            (void)apiReported;
        }
    }
    probes = 0;
    check(verifySocket(ownedSocket, true) && probes == 1,
        "false exemption with no optional list is accepted only when a specialise-only path kept the same socket and replied");
    using haaru::RestorePhase;
    using Outcomes = std::array<bool, 2>;
    const Outcomes bothSucceed{true, true};
    const std::vector<std::string> completeRestore{"central", "write country", "write name", "read country", "read name"};
    auto attemptRestore = [](RestorePhase phase, bool baseline, bool central, Outcomes writes, Outcomes reads) {
        std::vector<std::string> calls;
        const bool restored = haaru::restoreGlobalState(phase, baseline,
            [&] { calls.push_back("central"); return central; },
            [&](size_t index) { calls.push_back(index == 0 ? "write country" : "write name"); return writes.at(index); },
            [&](size_t index) { calls.push_back(index == 0 ? "read country" : "read name"); return reads.at(index); });
        return std::pair{restored, calls};
    };
    check(attemptRestore(RestorePhase::Runtime, true, true, bothSucceed, bothSucceed) == std::pair{true, completeRestore},
        "runtime restore can be confirmed after central clear and both property write/readbacks succeed");
    check(attemptRestore(RestorePhase::Runtime, true, false, bothSucceed, bothSucceed) == std::pair{false, completeRestore},
        "central clear failure blocks confirmation without cancelling either property restore");
    for (size_t index = 0; index < bothSucceed.size(); ++index) {
        Outcomes oneFails = bothSucceed;
        oneFails[index] = false;
        check(attemptRestore(RestorePhase::Runtime, true, true, oneFails, bothSucceed) == std::pair{false, completeRestore},
            "either property write failure blocks confirmation even when both readbacks match");
        check(attemptRestore(RestorePhase::Runtime, true, true, bothSucceed, oneFails) == std::pair{false, completeRestore},
            "either property readback mismatch blocks confirmation");
    }
    check(attemptRestore(RestorePhase::Runtime, false, true, bothSucceed, bothSucceed) == std::pair{false, std::vector<std::string>{"central"}},
        "without a baseline, runtime may clear the central plan but must never write unknown originals");
    const std::vector<std::string> bootstrapRestore(completeRestore.begin() + 1, completeRestore.end());
    check(attemptRestore(RestorePhase::Bootstrap, true, false, bothSucceed, bothSucceed) == std::pair{true, bootstrapRestore},
        "startup restores both properties before the central class is loaded");
    check(attemptRestore(RestorePhase::Bootstrap, true, false, bothSucceed, {true, false}) == std::pair{false, bootstrapRestore},
        "startup still fails closed when a property does not read back correctly");
    check(attemptRestore(RestorePhase::Bootstrap, false, false, bothSucceed, bothSucceed) == std::pair{false, std::vector<std::string>{}},
        "startup must reject restoration when the original values are unavailable");
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
    try {
        overlayProperty(std::string(89, 'A') + ",X", {{1, "", "BC"}}, false);
        check(false, "whole list separator included in 92 byte rejection");
    } catch (const std::length_error& failure) {
        const std::string message = failure.what();
        check(message.find("Combined SIM operator names") != std::string::npos &&
              message.find("91-byte") != std::string::npos && message.find("Shorten") != std::string::npos,
            "aggregate name rejection explains the byte limit and what to change");
    }
    std::string unicode;
    for (int i = 0; i < 30; ++i) unicode += "\xe5\x8f\xb0";
    check(overlayProperty("", {{0, "", unicode}}, false).size() == 90, "Unicode property bound uses UTF8 bytes");
    rejects<std::length_error>([&unicode] { overlayProperty("", {{0, "", unicode + "\xe5\x8f\xb0"}}, false); }, "31 three-byte characters exceed property bound");
    rejects<std::invalid_argument>([] { overlayProperty("tw", {{-1, "US", ""}}, true); }, "negative logical slot rejected");
    rejects<std::invalid_argument>([] { overlayProperty("tw", {{8, "US", ""}}, true); }, "logical slot above supported bound rejected");
    const std::string malformedUtf8("\xed\xa0\x80", 3); // Lone UTF-16 surrogate encoded by a JNI converter.
    nlohmann::json badShadow{{"bootId", "fixture-boot"}, {"countryList", "tw"}, {"nameList", malformedUtf8}};
    check(!haaru::rustValidShadow(badShadow, "fixture-boot"), "invalid UTF8 serialisation is contained before JNI shadow validation");
    nlohmann::json badRuntime{{"protocol", 2}, {"global", true}, {"engine", "system-global-volatile"},
        {"bootId", "fixture-boot"}, {"profiles", nlohmann::json::array({{{"slot", 0}, {"subId", 1}, {"country", "us"}, {"carrierName", malformedUtf8}}})}};
    check(!haaru::rustValidRuntime(badRuntime, "fixture-boot"), "invalid UTF8 runtime serialisation cannot escape the native boundary");
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
    auto transient = receipt; transient["bootstrapFailure"] = false;
    transient["error"] = "Natural SIM update is waiting for publication";
    auto transport = haaru::disconnectedAck(transient, 36, "fixture-boot", 123457, 2870, "Companion response failed");
    check(transport["error"] == "Companion response failed", "transient SIM publication text cannot hide the actual disconnect cause");
    auto pending = haaru::engineAck(false, false, false, 0, 36, "fixture-boot", "", 123458, 2870, "specialization", "");
    pending["fdExemptionReported"] = true;
    pending["fdIgnoreListAvailable"] = false;
    auto earlyDisconnect = haaru::disconnectedAck(pending, 36, "fixture-boot", 123459, 2870);
    check(earlyDisconnect["error"] == "Phone companion disconnected before post-specialisation validation" &&
        earlyDisconnect["fdExemptionReported"] == true && earlyDisconnect["fdIgnoreListAvailable"] == false &&
        earlyDisconnect["initialized"] == false, "early EOF records retention evidence without confirming bootstrap");
    pending["fdExemptionReported"] = false;
    auto noRetention = haaru::disconnectedAck(pending, 36, "fixture-boot", 123459, 2870);
    check(noRetention["error"] == "Phone companion disconnected before post-specialisation validation" &&
        !noRetention["connected"].get<bool>(), "unavailable retention flags do not manufacture a disconnect cause");
    pending.erase("fdIgnoreListAvailable");
    auto unknownRetention = haaru::disconnectedAck(pending, 36, "fixture-boot", 123459, 2870);
    check(unknownRetention["error"] == "Phone companion disconnected before post-specialisation validation",
        "missing retention metadata remains distinct from a measured false");
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
