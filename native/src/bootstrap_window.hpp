#pragma once
/* SPDX-License-Identifier: Apache-2.0 */
#include <cstdint>
namespace haaru {
// Steady elapsed time only: clock corrections cannot expire startup early.
struct BootstrapWindow {
    int64_t nextHeartbeatMs = 0;
    bool heartbeatDue(int64_t elapsedMs) {
        if (elapsedMs < nextHeartbeatMs) return false;
        nextHeartbeatMs = elapsedMs + 1000;
        return true;
    }
    bool expired(int64_t elapsedMs, bool finished) const { return !finished && elapsedMs >= 60000; }
};
}
