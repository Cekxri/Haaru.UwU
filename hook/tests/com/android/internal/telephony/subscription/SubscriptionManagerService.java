/* SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.telephony.subscription;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import java.util.List;
/** Host fixture: exposes the ten Android 16 ISub SubscriptionInfo return methods. */
public class SubscriptionManagerService {
    public SubscriptionInfo info;
    public List<SubscriptionInfo> list;
    public Throwable failure;
    public Runnable onCall;
    public int calls;
    public Object[] forwarded;
    private void read(Object... args) throws Throwable {
        calls++; forwarded = args;
        if (onCall != null) onCall.run();
        if (failure != null) throw failure;
    }
    public List<SubscriptionInfo> getAllSubInfoList(String pkg, String feature) throws Throwable { read(pkg, feature); return list; }
    public SubscriptionInfo getActiveSubscriptionInfo(int id, String pkg, String feature) throws Throwable { read(id, pkg, feature); return info; }
    public SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccid, String pkg, String feature) throws Throwable { read(iccid, pkg, feature); return info; }
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slot, String pkg, String feature) throws Throwable { read(slot, pkg, feature); return info; }
    public List<SubscriptionInfo> getActiveSubscriptionInfoList(String pkg, String feature, boolean all) throws Throwable { read(pkg, feature, all); return list; }
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList(String pkg, String feature) throws Throwable { read(pkg, feature); return list; }
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList(String pkg) throws Throwable { read(pkg); return list; }
    public List<SubscriptionInfo> getOpportunisticSubscriptions(String pkg, String feature) throws Throwable { read(pkg, feature); return list; }
    public List<SubscriptionInfo> getSubscriptionsInGroup(ParcelUuid group, String pkg, String feature) throws Throwable { read(group, pkg, feature); return list; }
    public List<SubscriptionInfo> getSubscriptionInfoListAssociatedWithUser(UserHandle user) throws Throwable { read(user); return list; }
}
