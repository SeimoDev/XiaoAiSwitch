package io.github.xiaoaiswitch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.pm.PackageInfoCompat;
import io.github.xiaoaiswitch.core.DialogueHookDiscovery;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

/** Reads the installed Xiaomi APK in the module process; the cache is not writable by the host app. */
final class LocalHookDiscovery {
    private static final String PACKAGE = "com.miui.voiceassist";
    private static final String XIAOMI_CERT = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025";

    record Snapshot(long version, long updatedAt, DialogueHookDiscovery.Layout layout) {}

    private LocalHookDiscovery() {}

    static synchronized Snapshot find(Context context, boolean includeBuiltin) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo packageInfo = manager.getPackageInfo(PACKAGE, 0);
            long version = PackageInfoCompat.getLongVersionCode(packageInfo);
            if (AssistantVersion.familyOf(packageInfo.versionName) == 0
                    || (!includeBuiltin && AssistantVersion.forCode(version) != null)
                    || !isXiaomi(manager)) return null;
            ApplicationInfo app = packageInfo.applicationInfo;
            if (app == null) return null;
            var paths = new ArrayList<File>();
            paths.add(new File(app.sourceDir));
            if (app.splitSourceDirs != null) {
                Arrays.stream(app.splitSourceDirs).sorted().map(File::new).forEach(paths::add);
            }
            String fingerprint = BuildConfig.VERSION_CODE + ":" + DialogueHookDiscovery.fingerprint(version, packageInfo.lastUpdateTime, paths);
            var prefs = context.getSharedPreferences("dialogue-layout", Context.MODE_PRIVATE);
            if (fingerprint.equals(prefs.getString("fingerprint", ""))) {
                String cached = prefs.getString("layout", "");
                if (cached.isEmpty()) return null;
                var decoded = DialogueHookDiscovery.Layout.decode(cached);
                if (decoded != null) return new Snapshot(version, packageInfo.lastUpdateTime, decoded);
            }
            var layout = DialogueHookDiscovery.find(paths);
            prefs.edit().putString("fingerprint", fingerprint).putString("layout", layout == null ? "" : layout.encode()).apply();
            return layout == null ? null : new Snapshot(version, packageInfo.lastUpdateTime, layout);
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static boolean isXiaomi(PackageManager manager) throws PackageManager.NameNotFoundException {
        var expected = new byte[32];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(XIAOMI_CERT.substring(i * 2, i * 2 + 2), 16);
        if (Build.VERSION.SDK_INT >= 28) return manager.hasSigningCertificate(PACKAGE, expected, PackageManager.CERT_INPUT_SHA256);
        var signatures = manager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures;
        if (signatures == null || signatures.length != 1) return false;
        try {
            return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
