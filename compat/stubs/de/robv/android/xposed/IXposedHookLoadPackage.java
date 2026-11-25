package de.robv.android.xposed;

/** 编译占位：运行时由 LSPosed 提供真实实现。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
