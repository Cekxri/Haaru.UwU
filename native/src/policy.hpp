#pragma once
#include "rust_core.hpp"
// The Android loader uses the same Rust gates as the executable host contract.
namespace haaru {
inline bool cacheAllowed(int protocol, bool configurationReadOnly, bool enabled, bool bootMatches) {
    return haaru_cache_allowed(protocol, configurationReadOnly, enabled, bootMatches) != 0;
}
inline bool uidAllowed(unsigned int uid) {
    return haaru_uid_allowed(uid) != 0;
}
}
