#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>
#include "../vendor/json/single_include/nlohmann/json.hpp"

extern "C" {
uint8_t haaru_json_valid(const uint8_t*, size_t);
uint8_t haaru_validate_runtime(const uint8_t*, size_t, const uint8_t*, size_t);
uint8_t haaru_validate_shadow(const uint8_t*, size_t, const uint8_t*, size_t);
uint64_t haaru_fnv1a64(const uint8_t*, size_t);
uint8_t haaru_cache_allowed(int32_t, uint8_t, uint8_t, uint8_t);
uint8_t haaru_uid_allowed(uint32_t);
int32_t haaru_property_overlay(const uint8_t*, size_t, const uint8_t*, size_t, uint8_t, uint8_t*, size_t);
int32_t haaru_property_split(const uint8_t*, size_t, uint8_t*, size_t);
int32_t haaru_property_join(const uint8_t*, size_t, uint8_t*, size_t);
}

namespace haaru {
inline const uint8_t* rustBytes(const std::string& value) { return reinterpret_cast<const uint8_t*>(value.data()); }
inline std::string rustResult(int32_t length, const std::vector<uint8_t>& bytes) {
    if (length == -2) throw std::invalid_argument("Invalid logical SIM slot");
    if (length == -3) throw std::length_error("Global SIM operator list exceeds Android's 91-byte property limit");
    if (length < 0 || static_cast<size_t>(length) > bytes.size()) throw std::invalid_argument("Rust core rejected invalid or oversized input");
    return std::string(reinterpret_cast<const char*>(bytes.data()), static_cast<size_t>(length));
}
// JSON serialization can reject malformed Java-derived UTF-8. Never let that
// exception escape a JNI callback before the Rust validator can reject input.
inline bool rustValidRuntime(const nlohmann::json& value, const std::string& boot) noexcept {
    try {
        auto bytes = value.dump();
        return haaru_validate_runtime(rustBytes(bytes), bytes.size(), rustBytes(boot), boot.size()) != 0;
    } catch (...) { return false; }
}
inline bool rustValidShadow(const nlohmann::json& value, const std::string& boot) noexcept {
    try {
        auto bytes = value.dump();
        return haaru_validate_shadow(rustBytes(bytes), bytes.size(), rustBytes(boot), boot.size()) != 0;
    } catch (...) { return false; }
}
}
