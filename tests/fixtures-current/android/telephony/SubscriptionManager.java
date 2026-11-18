package android.telephony;

import java.util.HashMap;
import java.util.Map;

public final class SubscriptionManager {
    public static boolean invalidPhoneCache;
    public static int getPhoneId(int id) { return getSlotIndex(id); }
    public static final Map<Integer, Integer> SLOT_IDS = new HashMap<Integer, Integer>();
    public static int defaultSubscriptionId = -1;
    private SubscriptionManager() {}
    public static int getDefaultSubscriptionId() { return defaultSubscriptionId; }
    public static int getSubId(int slot) {
        Integer id = SLOT_IDS.get(slot);
        return id == null ? -1 : id.intValue();
    }
    public static int getSlotIndex(int subId) {
        if (invalidPhoneCache) return -1;
        for (Map.Entry<Integer, Integer> item : SLOT_IDS.entrySet()) {
            if (item.getValue().intValue() == subId) return item.getKey().intValue();
        }
        return -1;
    }
}

