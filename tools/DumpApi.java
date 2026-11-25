public class DumpApi {
    public static void main(String[] args) throws Exception {
        String[] classes = {
            "android.app.Notification$ProgressStyle",
            "android.app.Notification$Builder",
            "android.app.Notification"
        };
        for (String cn : classes) {
            System.out.println("==== " + cn);
            try {
                Class<?> c = Class.forName(cn);
                for (java.lang.reflect.Method m : c.getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("progress") || n.contains("promot")
                            || n.contains("critical") || n.contains("styled")) {
                        System.out.println(m.toGenericString());
                    }
                }
            } catch (Throwable t) {
                System.out.println("ERR " + t);
            }
        }
        System.out.println("SDK=" + android.os.Build.VERSION.SDK_INT);
    }
}
