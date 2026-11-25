package io.github.luoyu1337.edgefluiddl;

import android.app.Notification;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 拦截 Edge 进程里的 NotificationManager.notify(...)，把下载通知换成改写后的版本。
 * 其它通知原样放行。
 */
final class NotifyHook extends XC_MethodHook {

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        Object[] args = param.args;
        if (args == null) {
            return;
        }

        // notify(int id, Notification) / notify(String tag, int id, Notification)
        int id = -1;
        String tag = null;
        for (Object arg : args) {
            if (arg instanceof Integer) {
                id = (Integer) arg;
            } else if (arg instanceof String) {
                tag = (String) arg;
            }
        }

        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Notification)) {
                continue;
            }
            Notification rewritten = NotificationRewriter.rewrite(
                    (Notification) args[i],
                    android.app.AndroidAppHelper.currentApplication(),
                    id,
                    tag);
            if (rewritten != null) {
                args[i] = rewritten;
            }
            return;
        }
    }
}
