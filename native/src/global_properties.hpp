#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include "rust_core.hpp"
#include "../vendor/json/single_include/nlohmann/json.hpp"
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>
namespace haaru {
struct PropertyProfile { int slot; std::string country, name; };
enum class RestorePhase { Bootstrap, Runtime };

template<typename ClearCentral, typename WriteProperty, typename VerifyProperty>
bool restoreGlobalState(RestorePhase phase, bool baselineReady, ClearCentral clearCentral,
        WriteProperty writeProperty, VerifyProperty verifyProperty) {
    const bool centralCleared = phase == RestorePhase::Bootstrap || clearCentral();
    if (!baselineReady) return false;
    const bool countryWritten = writeProperty(0);
    const bool nameWritten = writeProperty(1);
    const bool countryVerified = verifyProperty(0);
    const bool nameVerified = verifyProperty(1);
    return centralCleared && countryWritten && nameWritten && countryVerified && nameVerified;
}

inline std::vector<std::string> splitProperty(std::string_view text) {
    std::vector<uint8_t> output(65536);
    auto size = haaru_property_split(reinterpret_cast<const uint8_t*>(text.data()), text.size(), output.data(), output.size());
    return nlohmann::json::parse(rustResult(size, output)).get<std::vector<std::string>>();
}
inline std::string joinProperty(const std::vector<std::string>& values) {
    auto input = nlohmann::json(values).dump();
    std::vector<uint8_t> output(65536);
    auto size = haaru_property_join(rustBytes(input), input.size(), output.data(), output.size());
    return rustResult(size, output);
}
inline std::string overlayProperty(std::string_view original, const std::vector<PropertyProfile>& profiles, bool country) {
    auto plan = nlohmann::json::array();
    for (const auto& p : profiles) {
        plan.push_back({{"slot", p.slot}, {"country", p.country}, {"name", p.name}});
    }
    auto input = plan.dump();
    std::vector<uint8_t> output(65536);
    auto size = haaru_property_overlay(reinterpret_cast<const uint8_t*>(original.data()), original.size(),
        rustBytes(input), input.size(), country ? 1 : 0, output.data(), output.size());
    try {
        return rustResult(size, output);
    } catch (const std::length_error&) {
        if (country) throw std::length_error("Combined SIM country list exceeds Android's 91-byte property limit");
        throw std::length_error("Combined SIM operator names exceed Android's 91-byte property limit. Shorten the configured operator names and try again.");
    }
}
}
