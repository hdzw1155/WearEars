package android.content.pm;

public class PackageManager {
    public int version = 16;
    public static class NameNotFoundException extends Exception { }
    public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
        if (version == -1) throw new NameNotFoundException();
        PackageInfo info = new PackageInfo();
        info.versionCode = version;
        return info;
    }
}
