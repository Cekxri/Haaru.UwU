package compat;
import android.os.Parcel;
import android.os.Parcelable;
/** Framework shape where country is writable on a private copy but display name is immutable. */
public final class PartialInfo implements Parcelable {
    private final int id, slot;
    private String mCountryIso;
    private final String label, metadata;
    public PartialInfo(int id, int slot, String country, String label, String metadata) { this.id=id; this.slot=slot; mCountryIso=country; this.label=label; this.metadata=metadata; }
    public int getSubscriptionId() { return id; }
    public int getSimSlotIndex() { return slot; }
    public String getCountryIso() { return mCountryIso; }
    public CharSequence getCarrierName() { return label; }
    public CharSequence getDisplayName() { return label; }
    public String metadata() { return metadata; }
    public void writeToParcel(Parcel parcel, int flags) { parcel.writeValues(id,slot,mCountryIso,label,metadata); }
    public static final Parcelable.Creator<PartialInfo> CREATOR = new Parcelable.Creator<PartialInfo>() {
        public PartialInfo createFromParcel(Parcel parcel) { Object[] d=parcel.readValues(); return new PartialInfo((Integer)d[0],(Integer)d[1],(String)d[2],(String)d[3],(String)d[4]); }
        public PartialInfo[] newArray(int count) { return new PartialInfo[count]; }
    };
    public interface Aidl { PartialInfo getActiveSubscriptionInfo(int id, String caller); }
    public static class Service implements Aidl {
        public PartialInfo info;
        public PartialInfo getActiveSubscriptionInfo(int id, String caller) { return info; }
    }
}
