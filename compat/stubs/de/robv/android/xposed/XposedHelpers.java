package de.robv.android.xposed;

/** 编译占位：签名对齐真实 XposedHelpers。 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className,
            ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }
}
