/* SPDX-License-Identifier: Apache-2.0 */
package android.telephony;
/** Host fixture: notification contains no subscription/config mutation. */
public final class TelephonyRegistryManager {
    public int notifications;
    public boolean fail;
    public void notifySubscriptionInfoChanged() {
        if (fail) throw new IllegalStateException("notification unavailable");
        notifications++;
    }
}
