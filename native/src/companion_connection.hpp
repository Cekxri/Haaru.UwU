#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace haaru {
struct CompanionSocketIdentity {
    uint64_t device = 0, inode = 0;
    int type = 0;
    bool valid = false;

    bool matches(const CompanionSocketIdentity& other) const {
        return valid && other.valid && device == other.device && inode == other.inode && type == other.type;
    }
};

// Preserve every caller-owned entry and add only this module's connected socket.
inline std::vector<int> companionFdList(const std::vector<int>& prior, int ownedFd) {
    if (ownedFd < 0 || prior.size() > 4096) throw std::invalid_argument("Invalid companion FD retention request");
    auto result = prior;
    if (std::find(result.begin(), result.end(), ownedFd) == result.end()) result.push_back(ownedFd);
    return result;
}

// A provider's exemption return value is not proof that the socket survived.
// Never send to or close a descriptor reused for an unrelated object.
template <typename ReadIdentity, typename RoundTrip>
bool validateCompanionConnection(const CompanionSocketIdentity& expected, ReadIdentity readIdentity, RoundTrip roundTrip) {
    if (!expected.matches(readIdentity())) return false;
    return roundTrip();
}

template <typename ReadIdentity, typename Close>
void closeOwnedCompanion(const CompanionSocketIdentity& expected, ReadIdentity readIdentity, Close close) {
    if (expected.matches(readIdentity())) close();
}
} // namespace haaru
