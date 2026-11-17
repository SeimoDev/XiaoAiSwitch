package io.github.xiaoaiswitch;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.AlarmClock;
import io.github.xiaoaiswitch.core.DialogueLoop;
import io.github.xiaoaiswitch.core.SystemTools;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class SystemActionExecutor {
    private SystemActionExecutor() {}

    static DialogueLoop.Result execute(Context context, SystemTools.Action action) {
        if (context.getSystemService(KeyguardManager.class).isDeviceLocked()) return result(false, "请解锁手机后再操作。");
        if (action instanceof SystemTools.Launch launch) return launch(context, launch.appName());
        if (context.checkSelfPermission(Manifest.permission.SET_ALARM) != PackageManager.PERMISSION_GRANTED) {
            return result(false, "小爱没有系统时钟权限。");
        }
        Intent intent;
        String label;
        if (action instanceof SystemTools.Alarm alarm) {
            intent = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, alarm.hour())
                    .putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute());
            label = alarm.label();
        } else if (action instanceof SystemTools.Timer timer) {
            intent = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, timer.seconds());
            label = timer.label();
        } else {
            throw new IllegalArgumentException("未知系统工具");
        }
        if (!label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        return submit(context, intent, "已提交给本机时钟，实际状态请以时钟页面为准。", "设备上没有可处理该请求的时钟。");
    }

    private static DialogueLoop.Result launch(Context context, String name) {
        PackageManager packages = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> matches = new LinkedHashSet<>();
        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
            if (name.equalsIgnoreCase(info.loadLabel(packages).toString().trim())) matches.add(info.activityInfo.packageName);
        }
        if (matches.isEmpty()) return result(false, "没有找到名为「" + name + "」的可启动应用。");
        if (matches.size() != 1) return result(false, "有多个同名应用，请在手机上选择。");
        Intent intent = packages.getLaunchIntentForPackage(matches.iterator().next());
        if (intent == null) return result(false, "这个应用没有可用的桌面入口。");
        return submit(context, intent, "已请求打开「" + name + "」。", "这个应用的桌面入口已不可用。");
    }

    private static DialogueLoop.Result submit(Context context, Intent intent, String submitted, String missing) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return result(true, submitted);
        } catch (ActivityNotFoundException error) {
            return result(false, missing);
        } catch (SecurityException error) {
            return result(false, "系统拒绝启动该界面。");
        }
    }

    private static DialogueLoop.Result result(boolean ok, String message) {
        return new DialogueLoop.Result(message, Collections.emptyList(), ok);
    }
}
