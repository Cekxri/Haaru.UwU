package android.content.pm;
public final class PackageInfo {
    public String packageName, versionName;
    public int versionCode, versionCodeMajor;
    public ApplicationInfo applicationInfo;
    public SigningInfo signingInfo;
    public long getLongVersionCode() { return ((long) versionCodeMajor << 32) | (versionCode & 0xffffffffL); }
}
