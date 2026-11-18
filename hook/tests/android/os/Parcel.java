package android.os;
/** Serialization fixture; resetting the cursor is required before its saved data can be read. */
public final class Parcel {
    public Object[] data;
    private boolean positioned;
    public static int recycled;
    public static Parcel obtain() { return new Parcel(); }
    public void writeValues(Object... values) { data = values.clone(); positioned = false; }
    public Object[] readValues() { if (!positioned) throw new IllegalStateException("Parcel cursor was not reset"); return data.clone(); }
    public void setDataPosition(int position) { if (position != 0) throw new IllegalArgumentException(); positioned = true; }
    public void recycle() { recycled++; data = null; }
}
