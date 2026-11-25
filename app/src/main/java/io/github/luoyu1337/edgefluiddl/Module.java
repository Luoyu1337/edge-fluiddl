package io.github.luoyu1337.edgefluiddl;

import android.app.Notification;
import android.os.Build;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口：只在 Edge 进程里装 hook，把下载通知改成 ProgressStyle。
 * 只改内存对象，不写文件。
 */
public class Module implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !NotificationRewriter.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (Build.VERSION.SDK_INT < 36) {
            XposedBridge.log("[Edgefluiddl] Android 16+ required, skipped");
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        NotifyHook hook = new NotifyHook();
        // 逐个重载安装：某个重载不存在时不影响其它重载。
        hookNotify(cl, "notify", int.class, Notification.class, hook);
        hookNotify(cl, "notify", String.class, int.class, Notification.class, hook);
        hookNotify(cl, "notifyAsPackage",
                String.class, String.class, int.class, Notification.class, hook);
        XposedBridge.log("[Edgefluiddl] hooked " + lpparam.processName);
    }

    private static void hookNotify(ClassLoader cl, String methodName,
            Object... parameterTypesAndCallback) {
        try {
            XposedHelpers.findAndHookMethod("android.app.NotificationManager",
                    cl, methodName, parameterTypesAndCallback);
        } catch (Throwable t) {
            XposedBridge.log("[Edgefluiddl] hook " + methodName + " failed: " + t);
        }
    }
}
