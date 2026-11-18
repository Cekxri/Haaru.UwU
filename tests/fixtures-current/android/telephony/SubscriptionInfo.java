package android.telephony;

public final class SubscriptionInfo {
    private final int subscriptionId;
    private final int slot;
    private final String iccid;
    public CharSequence carrierName = "";
    public RuntimeException carrierNameFailure;
    public SubscriptionInfo(int subscriptionId, int slot, String iccid) {
        this.subscriptionId = subscriptionId;
        this.slot = slot;
        this.iccid = iccid;
    }
    public int getSubscriptionId() { return subscriptionId; }
    public int getSimSlotIndex() { return slot; }
    public String getIccId() { return iccid; }
    public CharSequence getCarrierName() {
        if (carrierNameFailure != null) throw carrierNameFailure;
        return carrierName;
    }
}

