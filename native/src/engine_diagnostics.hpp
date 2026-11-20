#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include "../vendor/json/single_include/nlohmann/json.hpp"
#include <charconv>
#include <cstdint>
#include <string>
#include <string_view>

namespace haaru {
// Keep diagnostics small enough for the companion protocol, without splitting UTF-8.
inline std::string boundedDiagnostic(std::string_view value, size_t limit = 2048) {
    size_t count = value.size() < limit ? value.size() : limit;
    if (count < value.size()) {
        while (count && (static_cast<unsigned char>(value[count]) & 0xc0) == 0x80) --count;
    }
    std::string result(value.substr(0, count));
    for (auto& byte : result) if (static_cast<unsigned char>(byte) < 0x20 || byte == 0x7f) byte = ' ';
    return result;
}

// An unavailable NDK API result is resolved from the actual immutable system property.
inline int resolveDeviceSdk(int apiResult, std::string_view property) {
    if (apiResult > 0) return apiResult;
    if (property.empty()) return 0;
    int sdk = 0;
    auto parsed = std::from_chars(property.data(), property.data() + property.size(), sdk);
    return parsed.ec == std::errc{} && parsed.ptr == property.data() + property.size() && sdk > 0 ? sdk : 0;
}

inline nlohmann::json engineAck(bool ok, bool initialized, bool applied, int hooks, int sdk,
        std::string_view boot, std::string_view hash, int64_t timestamp, int phonePid,
        std::string_view stage, std::string_view error, int requiredHooks = 0, bool coverageComplete = false,
        bool supportsCountry = false, bool supportsCarrierName = false) {
    // Missing identity metadata may describe a failure, but cannot prove application.
    bool identified = sdk > 0 && phonePid > 0 && timestamp > 0 && !boot.empty();
    bool covered = coverageComplete && requiredHooks >= 3 && hooks == requiredHooks;
    std::string reason = boundedDiagnostic(error);
    if (!identified && reason.empty()) reason = "Phone engine identity metadata is unavailable";
    return {{"ok", ok && identified && covered}, {"initialized", initialized && identified && covered},
        {"applied", applied && identified && covered}, {"hooks", hooks < 0 ? 0 : hooks},
        {"requiredHooks", requiredHooks < 0 ? 0 : requiredHooks}, {"coverageComplete", covered},
        {"supportsCountry", supportsCountry}, {"supportsCarrierName", supportsCarrierName},
        {"sdk", sdk > 0 ? sdk : 0}, {"bootId", boot}, {"configHash", hash},
        {"timestampMs", timestamp > 0 ? timestamp : 0}, {"phonePid", phonePid > 0 ? phonePid : 0},
        {"stage", boundedDiagnostic(stage, 96)}, {"error", reason}};
}

inline nlohmann::json disconnectedAck(const nlohmann::json& prior, int sdk, std::string_view boot,
        int64_t timestamp, int phonePid, std::string_view transportError = "Phone engine disconnected") {
    std::string stage = "connection", error = boundedDiagnostic(transportError), hash;
    int hooks = 0;
    if (prior.is_object()) {
        if (prior.contains("configHash") && prior["configHash"].is_string()) hash = prior["configHash"].get<std::string>();
        if (prior.contains("hooks") && prior["hooks"].is_number_integer()) hooks = prior["hooks"].get<int>();
        if (prior.contains("stage") && prior["stage"].is_string()) stage = prior["stage"].get<std::string>();
        if (prior.contains("error") && prior["error"].is_string() && !prior["error"].get_ref<const std::string&>().empty())
            error = prior["error"].get<std::string>();
    }
    if (stage == "specialization" && error == "Phone engine disconnected") {
        const bool exemptionUnavailable = prior.contains("fdExemptionReported") && prior["fdExemptionReported"] == false;
        const bool ignoreListUnavailable = prior.contains("fdIgnoreListAvailable") && prior["fdIgnoreListAvailable"] == false;
        error = exemptionUnavailable && ignoreListUnavailable ?
            "Phone companion disconnected during specialisation without an FD retention path" :
            "Phone companion disconnected before post-specialisation validation";
    }
    auto result = engineAck(false, false, false, hooks, sdk, boot, hash, timestamp, phonePid, stage, error);
    for (const char* key : {"requiredHooks", "supportsCountry", "supportsCarrierName",
            "fdExemptionReported", "fdIgnoreListAvailable"})
        if (prior.is_object() && prior.contains(key)) result[key] = prior[key];
    result["connected"] = false;
    return result;
}
} // namespace haaru
