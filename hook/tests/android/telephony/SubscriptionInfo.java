/* SPDX-License-Identifier: Apache-2.0 */
package android.telephony;
/** Host fixture copies identifiers and metadata exactly as a framework Builder does. */
public final class SubscriptionInfo {
    public final int id, slot, nameSource;
    public final String country, iccid, mcc, mnc;
    public final CharSequence carrier, display;
    public final boolean embedded;
    public SubscriptionInfo(int id, int slot, String country, CharSequence carrier, CharSequence display,
            int nameSource, String iccid, boolean embedded, String mcc, String mnc) {
        this.id = id; this.slot = slot; this.country = country; this.carrier = carrier; this.display = display;
        this.nameSource = nameSource; this.iccid = iccid; this.embedded = embedded; this.mcc = mcc; this.mnc = mnc;
    }
    public int getSubscriptionId() { return id; }
    public int getSimSlotIndex() { return slot; }
    public static final class Builder {
        private final SubscriptionInfo source;
        private String country;
        private CharSequence carrier, display;
        public Builder(SubscriptionInfo source) {
            this.source = source; country = source.country; carrier = source.carrier; display = source.display;
        }
        public Builder setCountryIso(String value) { country = value; return this; }
        public Builder setCarrierName(CharSequence value) { carrier = value; return this; }
        public Builder setDisplayName(CharSequence value) { display = value; return this; }
        public SubscriptionInfo build() {
            return new SubscriptionInfo(source.id, source.slot, country, carrier, display,
                    source.nameSource, source.iccid, source.embedded, source.mcc, source.mnc);
        }
    }
}
