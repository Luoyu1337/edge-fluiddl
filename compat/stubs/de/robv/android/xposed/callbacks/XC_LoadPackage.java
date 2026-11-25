package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** 编译占位。 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
