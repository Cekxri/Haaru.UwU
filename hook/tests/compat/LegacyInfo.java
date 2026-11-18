package compat;
import android.os.Parcel;
import android.os.Parcelable;
/** Older framework shape: Parcelable plus private storage, without a Builder or public setters. */
public final class LegacyInfo implements Parcelable {
    private final int mId, mSlot, mNameSource;
    private String mCountryIso;
    private CharSequence mCarrierName, mDisplayName;
    private final String mIccId, mMcc, mMnc;
    private final boolean mEmbedded;
    public static LegacyInfo returnOriginal;
    public LegacyInfo(int id, int slot, String country, CharSequence carrier, CharSequence display,
            int nameSource, String iccid, boolean embedded, String mcc, String mnc) {
        mId=id; mSlot=slot; mCountryIso=country; mCarrierName=carrier; mDisplayName=display;
        mNameSource=nameSource; mIccId=iccid; mEmbedded=embedded; mMcc=mcc; mMnc=mnc;
    }
    public int getSubscriptionId() { return mId; }
    public int getSimSlotIndex() { return mSlot; }
    public String getCountryIso() { return mCountryIso; }
    public CharSequence getCarrierName() { return mCarrierName; }
    public CharSequence getDisplayName() { return mDisplayName; }
    public int getNameSource() { return mNameSource; }
    public String getIccId() { return mIccId; }
    public String getMcc() { return mMcc; }
    public String getMnc() { return mMnc; }
    public boolean isEmbedded() { return mEmbedded; }
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeValues(mId,mSlot,mCountryIso,mCarrierName.toString(),mDisplayName.toString(),mNameSource,mIccId,mEmbedded,mMcc,mMnc);
    }
    public static final Parcelable.Creator<LegacyInfo> CREATOR = new Parcelable.Creator<LegacyInfo>() {
        public LegacyInfo createFromParcel(Parcel parcel) {
            if (returnOriginal != null) return returnOriginal;
            Object[] d=parcel.readValues();
            return new LegacyInfo((Integer)d[0],(Integer)d[1],(String)d[2],(String)d[3],(String)d[4],(Integer)d[5],(String)d[6],(Boolean)d[7],(String)d[8],(String)d[9]);
        }
        public LegacyInfo[] newArray(int count) { return new LegacyInfo[count]; }
    };
}
