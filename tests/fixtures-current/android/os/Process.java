package android.os;

public final class Process {
    public static int uid = 0;
    private Process() {}
    public static int myUid() { return uid; }
}

