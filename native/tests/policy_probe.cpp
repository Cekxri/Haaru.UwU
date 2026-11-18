#include "../src/policy.hpp"
extern "C" int cache_allowed(int protocol, int readOnly, int enabled, int bootMatches) {
    return haaru::cacheAllowed(protocol, readOnly != 0, enabled != 0, bootMatches != 0);
}
extern "C" int uid_allowed(unsigned int uid) { return haaru::uidAllowed(uid); }
