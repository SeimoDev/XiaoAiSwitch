package io.github.xiaoaiswitch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.pm.PackageInfoCompat;
import io.github.xiaoaiswitch.core.DialogueHookDiscovery;
import io.github.xiaoaiswitch.core.FeatureHookDiscovery;
import io.github.xiaoaiswitch.core.VerifiedControlPayload;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the installed Xiaomi APK in the module process; the cache is not writable by the host app. */
final class LocalHookDiscovery {
    private static final String PACKAGE = "com.miui.voiceassist";
    private static final String XIAOMI_CERT = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025";
    // Code and resources of the reviewed 8.2.70.8421 native control build.
    private static final String REVIEWED_CONTROL_PAYLOAD = "ce7f20a789d109a6aa30e57e868a5aa62ef3892466f5fdd3ce2855400e10c523";

    record Snapshot(long version, long updatedAt, DialogueHookDiscovery.Layout layout, String symbols, boolean reviewedControl) {}

    private LocalHookDiscovery() {}

    static synchronized Snapshot find(Context context, boolean includeBuiltin) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo packageInfo = manager.getPackageInfo(PACKAGE, 0);
            long version = PackageInfoCompat.getLongVersionCode(packageInfo);
            int family = AssistantVersion.familyOf(packageInfo.versionName);
            if (family == 0
                    || (!includeBuiltin && AssistantVersion.forCode(version) != null)
                    || !isXiaomi(manager)) return null;
            ApplicationInfo app = packageInfo.applicationInfo;
            if (app == null) return null;
            var paths = new ArrayList<File>();
            paths.add(new File(app.sourceDir));
            if (app.splitSourceDirs != null) {
                Arrays.stream(app.splitSourceDirs).sorted().map(File::new).forEach(paths::add);
            }
            String fingerprint = BuildConfig.VERSION_CODE + ":features-2:" + DialogueHookDiscovery.fingerprint(version, packageInfo.lastUpdateTime, paths);
            var prefs = context.getSharedPreferences("dialogue-layout", Context.MODE_PRIVATE);
            if (fingerprint.equals(prefs.getString("fingerprint", ""))) {
                String cached = prefs.getString("layout", "");
                var decoded = DialogueHookDiscovery.Layout.decode(cached);
                if (decoded != null || cached.isEmpty()) {
                    String symbols = prefs.getString("symbols", "{}");
                    return new Snapshot(version, packageInfo.lastUpdateTime, decoded, symbols, prefs.getBoolean("reviewedControl", false));
                }
            }
            var layout = DialogueHookDiscovery.find(paths);
            String symbols = new JSONObject(FeatureHookDiscovery.find(family, paths)).toString();
            boolean reviewedControl = family == 8 && matchesReviewedControl(paths);
            prefs.edit().putString("fingerprint", fingerprint).putString("layout", layout == null ? "" : layout.encode())
                    .putString("symbols", symbols).putBoolean("reviewedControl", reviewedControl).apply();
            return new Snapshot(version, packageInfo.lastUpdateTime, layout, symbols, reviewedControl);
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static boolean matchesReviewedControl(List<File> paths) {
        try {
            return REVIEWED_CONTROL_PAYLOAD.equals(VerifiedControlPayload.identity(paths));
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static Map<String, String> decodeSymbols(String value) {
        if (value == null || value.length() > 16384) return Map.of();
        var symbols = new HashMap<String, String>();
        try {
            var data = new JSONObject(value);
            var keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (data.opt(key) instanceof String name && !name.isEmpty()) symbols.put(key, name);
            }
            return symbols;
        } catch (JSONException error) {
            return Map.of();
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
