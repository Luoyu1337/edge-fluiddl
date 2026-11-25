package io.github.luoyu1337.edgefluiddl;

import java.lang.reflect.Method;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import de.robv.android.xposed.XposedBridge;

/**
 * 把 Edge 的下载通知改写成 ProgressStyle + promoted ongoing。
 * 下载中显示进度，完成后加「打开」和「关闭」两个按钮。
 * 用到的 API 都先探测，缺失就放弃改写、保留原通知。
 */
final class NotificationRewriter {

    /** Edge 的下载通道名。 */
    private static final String CHANNEL_DOWNLOADING = "downloads";
    private static final String CHANNEL_COMPLETED = "completed_downloads";

    /** 「关闭」按钮发回 Edge 进程的广播。 */
    static final String ACTION_CLOSE = "io.github.luoyu1337.edgefluiddl.action.CLOSE_DOWNLOAD";

    /** LSPosed 模块自身，以及「打开」用的中转 Activity。 */
    private static final String MODULE_PACKAGE = "io.github.luoyu1337.edgefluiddl";
    private static final String PROXY_ACTIVITY = MODULE_PACKAGE + ".OpenProxyActivity";

    /** 完成通知最多保留 1 分钟。 */
    private static final long COMPLETED_TIMEOUT_MS = 60_000L;

    /** 已改写标记：同一次 notify 会经过多个重载 hook，避免重复处理。 */
    private static final String EXTRA_REWRITTEN = "io.github.luoyu1337.edgefluiddl.rewritten";

    /** 广播只发给 Edge；作用的包名。 */
    static final String TARGET_PACKAGE = "com.microsoft.emmx";

    /** ProgressStyle / promoted ongoing 从 Android 16（API 36）开始提供。 */
    private static final int MIN_SDK = 36;

    // ---- 反射缓存（缺失即保持 null，调用方据此降级） ----
    private static Class<?> sProgressStyleClass;
    private static Method sStyleSetProgress;
    private static Method sStyleSetIndeterminate;
    private static Method sStyleSetTrackerIcon;
    private static Method sBuilderSetPromoted;
    private static Method sBuilderSetShortCritical;

    private static boolean sCloseReceiverReady;
    private static Icon sFallbackActionIcon;

    private static int sFailLogBudget = 3;
    private static int sLastLoggedPct = -1;

    private NotificationRewriter() {
    }

    /** 返回改写后的通知；不需要或无法改写时返回 null（调用方保留原通知）。 */
    static Notification rewrite(Notification n, Context ctx, int id, String tag) {
        if (n == null || ctx == null || Build.VERSION.SDK_INT < MIN_SDK) {
            return null;
        }
        try {
            Bundle extras = n.extras;
            if (extras != null && extras.getBoolean(EXTRA_REWRITTEN, false)) {
                return null; // 已改写：同一次 notify 会经过多个重载 hook
            }
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return null; // 组摘要不是真实下载，改写会得到错误的卡片
            }
            String channel = channelIdOf(n);
            if (CHANNEL_COMPLETED.equals(channel)) {
                return rebuildCompleted(n, ctx, id, tag);
            }
            if (!CHANNEL_DOWNLOADING.equals(channel)) {
                return null;
            }
            if (extras == null || !extras.containsKey(Notification.EXTRA_PROGRESS)) {
                return null; // 只接带进度的阶段
            }
            return rebuildProgress(n, ctx, extras);
        } catch (Throwable t) {
            logFailure(t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 下载中

    private static Notification rebuildProgress(Notification n, Context ctx, Bundle extras) {
        int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        int progress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if (max <= 0) {
            max = 100;
            indeterminate = true;
        }
        int pct = indeterminate ? 0 : (int) (progress * 100L / max);
        pct = Math.max(0, Math.min(100, pct));

        Object style = newProgressStyle(pct, indeterminate);
        if (!(style instanceof Notification.Style)) {
            return null; // 没有 ProgressStyle，交回 Edge 原通知
        }

        Notification.Builder b = Notification.Builder.recoverBuilder(ctx, n);
        b.setStyle((Notification.Style) style);
        b.setProgress(max, progress, indeterminate);
        b.setOnlyAlertOnce(true);
        b.setOngoing(true);
        setShortCriticalText(b, indeterminate ? "下载中" : "下载 " + pct + "%");
        if (!setRequestPromotedOngoing(b, true)) {
            return null; // 系统不支持 promoted ongoing，改写没有意义
        }

        Notification out = b.build();
        markRewritten(out);
        if (pct != sLastLoggedPct) {
            sLastLoggedPct = pct;
            log("progress " + progress + "/" + max);
        }
        return out;
    }

    // ------------------------------------------------------------------ 下载完成

    private static Notification rebuildCompleted(Notification n, Context ctx, int id, String tag) {
        ensureCloseReceiver(ctx);

        CharSequence titleText = n.extras == null
                ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
        String title = titleText == null ? "" : titleText.toString();
        Uri uri = resolveMediaUri(ctx, title);

        Notification.Action open = buildAction(ctx, "打开", openPendingIntent(ctx, uri, title, id, tag));
        Notification.Action close = buildAction(ctx, "关闭", closePendingIntent(ctx, id, tag));
        if (open == null || close == null) {
            return null;
        }

        Notification.Builder b = Notification.Builder.recoverBuilder(ctx, n);
        b.setOnlyAlertOnce(false);
        b.setAutoCancel(false);
        b.setOngoing(true);

        Object style = newProgressStyle(100, false);
        if (style instanceof Notification.Style) {
            b.setStyle((Notification.Style) style);
            // extras 与 style 必须一致，否则流体云按 0/0 排版，按钮会被挤出卡片
            b.setProgress(100, 100, false);
        }
        setShortCriticalText(b, "下载完成");
        setRequestPromotedOngoing(b, true); // 不支持时退化成普通通知，按钮仍在
        b.setTimeoutAfter(COMPLETED_TIMEOUT_MS); // 60 秒后由系统撤销，不依赖 Edge 进程存活

        Notification out = b.build();
        out.actions = new Notification.Action[] { open, close };
        markRewritten(out);
        log("completed id=" + id + " uri=" + uri);
        return out;
    }

    /** 构造按钮。优先不带图标（ColorOS 不会预留图标位）；个别系统不接受空图标时退回透明图标。 */
    private static Notification.Action buildAction(Context ctx, String title, PendingIntent intent) {
        try {
            return new Notification.Action.Builder(null, title, intent).build();
        } catch (Throwable ignored) {
        }
        Icon icon = fallbackActionIcon();
        if (icon == null) {
            return null;
        }
        try {
            return new Notification.Action.Builder(icon, title, intent).build();
        } catch (Throwable t) {
            logFailure(t);
            return null;
        }
    }

    private static PendingIntent openPendingIntent(Context ctx, Uri uri, String title, int id, String tag) {
        String mime = "*/*";
        if (uri != null) {
            try {
                String type = ctx.getContentResolver().getType(uri);
                if (type != null) {
                    mime = type;
                }
            } catch (Throwable ignored) {
            }
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        if (uri != null) {
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                view.setClipData(ClipData.newRawUri(title, uri));
            } catch (Throwable ignored) {
            }
        }

        Intent chooser = Intent.createChooser(view, "选择打开方式");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // chooser 由系统直接启动，保持原来的打开行为；后面再跟一个不显示界面的中转页，
        // 由它发广播让 Edge 撤掉通知。中转页起不来时，chooser 已经先打开了。
        int tagHash = tag == null ? 0 : tag.hashCode();
        Intent proxy = new Intent();
        proxy.setClassName(MODULE_PACKAGE, PROXY_ACTIVITY);
        proxy.putExtra(OpenProxyActivity.EXTRA_CANCEL, closePendingIntent(ctx, id, tag));
        proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // chooser 的目标放在 extras 里，不参与 PendingIntent 身份比较，requestCode 必须按通知区分。
        int requestCode = 0x51d010 ^ id ^ tagHash;
        return PendingIntent.getActivities(ctx, requestCode, new Intent[]{chooser, proxy},
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent closePendingIntent(Context ctx, int id, String tag) {
        Intent close = new Intent(ACTION_CLOSE).setPackage(TARGET_PACKAGE);
        close.putExtra("id", id);
        if (tag != null) {
            close.putExtra("tag", tag);
        }
        // extras 不参与 PendingIntent 的身份比较：requestCode 必须按通知区分，
        // 否则多个完成通知会共用一个 PendingIntent，点「关闭」会关错通知。
        int requestCode = 0x51d020 ^ id ^ (tag == null ? 0 : tag.hashCode());
        return PendingIntent.getBroadcast(ctx, requestCode, close,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 下载完成后从 MediaStore 找文件的 content:// URI（找不到就返回 null，按钮仍可点开选择器）。 */
    private static Uri resolveMediaUri(Context ctx, String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }
        Cursor c = null;
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
            c = ctx.getContentResolver().query(collection,
                    new String[] { MediaStore.Downloads._ID },
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[] { displayName },
                    MediaStore.Downloads.DATE_MODIFIED + " DESC");
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Throwable t) {
            logFailure(t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 关闭按钮

    /** 在 Edge 进程内注册广播接收器：点「关闭」或中转页撤通知时，由 Edge 自己 cancel 通知。 */
    private static synchronized void ensureCloseReceiver(Context ctx) {
        if (sCloseReceiverReady) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    int id = intent.getIntExtra("id", -1);
                    NotificationManager nm = (NotificationManager)
                            context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (id < 0 || nm == null) {
                        return;
                    }
                    String tag = intent.getStringExtra("tag");
                    if (tag != null) {
                        nm.cancel(tag, id);
                    } else {
                        nm.cancel(id);
                    }
                } catch (Throwable t) {
                    logFailure(t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CLOSE);
        try {
            ctx.registerReceiver(receiver, filter,
                    null, null, Context.RECEIVER_NOT_EXPORTED);
            sCloseReceiverReady = true;
        } catch (Throwable t) {
            try {
                ctx.registerReceiver(receiver, filter);
                sCloseReceiverReady = true;
            } catch (Throwable t2) {
                // 注册失败时不置位，下次完成通知会重试
                logFailure(t2);
            }
        }
    }

    // ------------------------------------------------------------------ ProgressStyle

    private static Object newProgressStyle(int pct, boolean indeterminate) {
        try {
            Class<?> c = sProgressStyleClass;
            if (c == null) {
                c = Class.forName("android.app.Notification$ProgressStyle");
                sProgressStyleClass = c;
            }
            Object style = c.getConstructor().newInstance();

            if (sStyleSetProgress == null) {
                sStyleSetProgress = c.getMethod("setProgress", int.class);
            }
            sStyleSetProgress.invoke(style, pct);

            if (sStyleSetIndeterminate == null) {
                sStyleSetIndeterminate = c.getMethod("setProgressIndeterminate", boolean.class);
            }
            sStyleSetIndeterminate.invoke(style, indeterminate);

            // 去掉进度条上的圆形追踪图标（ColorOS 默认那个小飞机），只留纯进度条。
            // 个别系统没有这个 API，缺了也不影响进度条本身。
            try {
                if (sStyleSetTrackerIcon == null) {
                    sStyleSetTrackerIcon = c.getMethod("setProgressTrackerIcon", Icon.class);
                }
                sStyleSetTrackerIcon.invoke(style, (Object) null);
            } catch (Throwable ignored) {
            }
            return style;
        } catch (Throwable t) {
            logFailure(t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static boolean setRequestPromotedOngoing(Notification.Builder b, boolean value) {
        try {
            if (sBuilderSetPromoted == null) {
                sBuilderSetPromoted = b.getClass().getMethod("setRequestPromotedOngoing", boolean.class);
            }
            sBuilderSetPromoted.invoke(b, value);
            return true;
        } catch (Throwable t) {
            logFailure(t);
            return false;
        }
    }

    private static void setShortCriticalText(Notification.Builder b, String text) {
        try {
            if (sBuilderSetShortCritical == null) {
                sBuilderSetShortCritical = b.getClass().getMethod("setShortCriticalText", String.class);
            }
            sBuilderSetShortCritical.invoke(b, text);
        } catch (Throwable ignored) {
            // 不是所有系统都有这个字段，缺了也不影响进度条
        }
    }

    private static Icon fallbackActionIcon() {
        if (sFallbackActionIcon == null) {
            try {
                Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                sFallbackActionIcon = Icon.createWithBitmap(bmp);
            } catch (Throwable t) {
                logFailure(t);
            }
        }
        return sFallbackActionIcon;
    }

    /** 标记已改写，防止同一次 notify 的多重载 hook 重复处理。 */
    private static void markRewritten(Notification n) {
        if (n.extras != null) {
            n.extras.putBoolean(EXTRA_REWRITTEN, true);
        }
    }

    private static String channelIdOf(Notification n) {
        try {
            return n.getChannelId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String message) {
        XposedBridge.log("[Edgefluiddl] " + message);
    }

    /** 反射/兼容问题只打印有限次数，避免刷日志。 */
    private static void logFailure(Throwable t) {
        if (sFailLogBudget > 0) {
            sFailLogBudget--;
            log("skip: " + t);
        }
    }
}
