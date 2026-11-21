#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include "rust_core.hpp"

namespace haaru {
// Boot-time absence or rejection of a plan is an idle engine, not an IPC
// failure. Always emit an object for config, including the empty case.
inline nlohmann::json companionRuntimeReply(bool moduleActive, const nlohmann::json& candidate,
        const std::string& boot, const std::string& hash, bool saved, bool prepared, bool recovering) {
    const bool available = moduleActive && rustValidRuntime(candidate, boot);
    return {{"active", available}, {"config", available ? candidate : nlohmann::json::object()},
        {"hash", hash}, {"shadowSaved", saved}, {"shadowPrepared", prepared}, {"recoveryRequired", recovering}};
}

inline bool validCompanionRuntimeReply(const nlohmann::json& reply, const std::string& boot) {
    if (!reply.is_object()) return false;
    for (const char* key : {"active", "shadowSaved", "shadowPrepared", "recoveryRequired"})
        if (!reply.contains(key) || !reply[key].is_boolean()) return false;
    if (!reply.contains("hash") || !reply["hash"].is_string() ||
            !reply.contains("config") || !reply["config"].is_object()) return false;
    return reply["active"].get<bool>() ? rustValidRuntime(reply["config"], boot) : reply["config"].empty();
}
} // namespace haaru
