package com.mirrortap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机自启（可选）：只有用户主动打开开关、且权限都在时才恢复。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.bootStart(context)) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        if (!TapInjectorService.isEnabledInSettings(context)) {
            return;
        }
        try {
            Intent service = new Intent(context, MirrorOverlayService.class)
                    .setAction(MirrorOverlayService.ACTION_START);
            context.startForegroundService(service);
        } catch (Throwable ignored) {
        }
    }
}
