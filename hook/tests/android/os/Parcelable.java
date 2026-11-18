package android.os;
public interface Parcelable {
    void writeToParcel(Parcel parcel, int flags);
    interface Creator<T> { T createFromParcel(Parcel parcel); T[] newArray(int count); }
}
