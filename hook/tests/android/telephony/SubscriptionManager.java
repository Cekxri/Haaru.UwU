/* SPDX-License-Identifier: Apache-2.0 */
package android.telephony;
import java.util.Collections;
import java.util.List;
public final class SubscriptionManager {
    public List<SubscriptionInfo> active = Collections.emptyList();
    public boolean fail;
    public int calls;
    public Runnable onRead;
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        calls++;
        if (onRead != null) onRead.run();
        if (fail) throw new IllegalStateException("active mapping unavailable");
        return active;
    }
}
