package io.github.luoyu1337.edgefluiddl;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

/**
 * 「打开」按钮的收尾页：不显示界面，只负责发广播让 Edge 撤掉通知，然后立即结束。
 * chooser 由系统在这之前直接启动，这里不参与打开流程。
 */
public class OpenProxyActivity extends Activity {

    static final String EXTRA_CANCEL = "cancel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PendingIntent cancel = getIntent().getParcelableExtra(EXTRA_CANCEL);
        if (cancel != null) {
            try {
                cancel.send();
            } catch (Throwable ignored) {
            }
        }
        finish();
    }
}
