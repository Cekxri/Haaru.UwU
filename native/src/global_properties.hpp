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
    return rustResult(size, output);
}
}
