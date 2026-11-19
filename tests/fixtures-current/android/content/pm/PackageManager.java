package android.content.pm;

/**
 * Framework-shaped fixture for actual reflection calls. Android 16
 * PackageInfoCommonUtils.generateApplicationInfo only exposes metaData for
 * GET_META_DATA; getPackageArchiveInfo collects certificates only when asked.
 */
public abstract class PackageManager {
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000, GET_META_DATA = 0x80;
    public static PackageInfo archive, installed;
    public static int archiveFlags, installedFlags;
    public static final PackageManager INSTANCE = new PrivateApplicationPackageManager();
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        archiveFlags = flags; return filtered(archive, flags);
    }
    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
    private static PackageInfo filtered(PackageInfo source, int flags) {
        if (source == null) return null;
        PackageInfo out = new PackageInfo(); out.packageName = source.packageName; out.versionName = source.versionName;
        out.versionCode = source.versionCode; out.versionCodeMajor = source.versionCodeMajor;
        out.signingInfo = (flags & GET_SIGNING_CERTIFICATES) != 0 ? source.signingInfo : null;
        if (source.applicationInfo != null) {
            out.applicationInfo = new ApplicationInfo(); out.applicationInfo.sourceDir = source.applicationInfo.sourceDir;
            out.applicationInfo.uid = source.applicationInfo.uid;
            if ((flags & GET_META_DATA) != 0 && source.applicationInfo.metaData != null) out.applicationInfo.metaData = source.applicationInfo.metaData.copy();
        }
        return out;
    }
    private static final class PrivateApplicationPackageManager extends PackageManager {
        @Override public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
            installedFlags = flags;
            if (installed == null || !packageName.equals(installed.packageName)) throw new NameNotFoundException();
            return filtered(installed, flags);
        }
    }
    public static final class NameNotFoundException extends Exception {}
    public static void reset() { archive = null; installed = null; archiveFlags = installedFlags = 0; }
}
