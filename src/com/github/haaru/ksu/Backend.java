package com.github.haaru.ksu;

import java.util.List;
import java.util.Objects;

/** Read-only telephony input for the global volatile engine; never a platform setter. */
public interface Backend {
    List<Subscription> subscriptions() throws Exception;
    Values read(int subId) throws Exception;
    String simCountry(int subId) throws Exception;
    /** Original active subscription label, independent of a CarrierConfig name override. */
    default String originalCarrierName(int subId) throws Exception { return ""; }
    default int defaultSubId() throws Exception { return -1; }

    final class Subscription {
        public final int slot, subId;
        public final String identity;
        public Subscription(int slot, int subId, String identity) {
            this.slot = slot; this.subId = subId; this.identity = identity;
        }
    }
    final class Values {
        public final String country, carrierName;
        public final boolean carrierOverride, ready;
        public Values(String country, boolean carrierOverride, String carrierName, boolean ready) {
            this.country = country == null ? "" : country;
            this.carrierOverride = carrierOverride;
            this.carrierName = carrierName == null ? "" : carrierName;
            this.ready = ready;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Values)) return false;
            Values v = (Values) other;
            return country.equals(v.country) && carrierOverride == v.carrierOverride
                    && carrierName.equals(v.carrierName);
        }
        @Override public int hashCode() { return Objects.hash(country, carrierOverride, carrierName); }
    }
}

