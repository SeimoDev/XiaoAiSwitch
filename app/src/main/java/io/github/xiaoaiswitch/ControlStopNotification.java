package io.github.xiaoaiswitch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.RequiresApi;
import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.ControlIslandProtocol;
import java.util.UUID;

/** A stop surface owned by this control task, not by the OEM's global UIAgent session. */
@RequiresApi(33)
final class ControlStopNotification implements AutoCloseable {
    private static final String CHANNEL = "xiaoaiswitch_control";
    private static final int ID = 0x584153;
    private final String tag = "xiaoaiswitch.control." + UUID.randomUUID();
    private final String action = "io.github.xiaoaiswitch.CONTROL_STOP." + tag;
    private final Context context;
    private final NotificationManager manager;
    private final BroadcastReceiver receiver;
    private final boolean focus;
    private final Icon icon;
    private PendingIntent intent;
    private boolean registered;

    static ControlStopNotification start(Context context, ClassLoader loader, Runnable stop) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT < 33 || manager == null || !manager.areNotificationsEnabled()) return null;
        ControlStopNotification card = null;
        try {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL, "小爱 Switch 手机控制", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                channel.enableVibration(false);
                manager.createNotificationChannel(channel);
            }
            if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return null;
            card = new ControlStopNotification(context, manager, supportsFocus(context, loader), stop);
            context.registerReceiver(card.receiver, new IntentFilter(card.action), Context.RECEIVER_NOT_EXPORTED);
            card.registered = true;
            card.intent = PendingIntent.getBroadcast(context, 0, new Intent(card.action).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            card.update("正在准备");
            return card;
        } catch (Exception error) {
            Log.w("XiaoAiSwitch", "Phone stop notification unavailable: " + error.getClass().getSimpleName());
        }
        if (card != null) card.close();
        return null;
    }

    boolean isPosted() {
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == ID && tag.equals(active.getTag())) return true;
        }
        return false;
    }

    private ControlStopNotification(Context context, NotificationManager manager, boolean focus, Runnable stop) {
        this.context = context;
        this.manager = manager;
        this.focus = focus;
        int logo = context.getResources().getIdentifier("logo_smaill", "drawable", context.getPackageName());
        icon = logo == 0 ? Icon.createWithResource("android", android.R.drawable.ic_media_pause) : Icon.createWithResource(context, logo);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent incoming) {
                if (registered && action.equals(incoming.getAction())) stop.run();
            }
        };
    }

    @SuppressLint("NotificationPermission") // The installed host's manifest is external to this build; the actual permission is checked below.
    void update(String status) throws Exception {
        if (intent == null) return;
        // This object runs in the real assistant process; check that package's actual grant before posting.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("小爱通知权限未开启");
        }
        Notification.Action stop = new Notification.Action.Builder(
                Icon.createWithResource("android", android.R.drawable.ic_menu_close_clear_cancel),
                ControlIslandProtocol.STOP_LABEL, intent).build();
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon).setContentTitle("小爱 Switch · 正在控制手机").setContentText(status)
                .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true).setTimeoutAfter(300_000)
                .setVisibility(Notification.VISIBILITY_PRIVATE).addAction(stop).setDeleteIntent(intent);
        if (focus) {
            Bundle pictures = new Bundle();
            pictures.putParcelable(ControlIslandProtocol.ICON_KEY, icon);
            Bundle actions = new Bundle();
            actions.putParcelable(ControlIslandProtocol.STOP_KEY, stop);
            Bundle extras = new Bundle();
            extras.putString("miui.focus.param", ControlIslandProtocol.params(status).toString());
            extras.putBundle("miui.focus.pics", pictures);
            extras.putBundle("miui.focus.actions", actions);
            builder.addExtras(extras);
        }
        manager.notify(tag, ID, builder.build());
    }

    private static boolean supportsFocus(Context context, ClassLoader loader) {
        try {
            if (Settings.System.getInt(context.getContentResolver(), "notification_focus_protocol", 0) < 2) return false;
            Class<?> type = XposedHelpers.findClass("com.xiaomi.voiceassist.baselibrary.uiagent.common.b", loader);
            Object helper = XposedHelpers.getStaticObjectField(type, "a");
            return Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "supportIsLand", context))
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "hasFocusPermission", context));
        } catch (XposedHelpers.ClassNotFoundError | XposedHelpers.InvocationTargetError | RuntimeException | LinkageError error) {
            return false;
        }
    }

    @Override public void close() {
        PendingIntent pending = intent;
        intent = null;
        try {
            if (pending != null) {
                manager.cancel(tag, ID);
            }
        } finally {
            try {
                if (pending != null) pending.cancel();
            } finally {
                if (registered) {
                    registered = false;
                    context.unregisterReceiver(receiver);
                }
            }
        }
    }
}
