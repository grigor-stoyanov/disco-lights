package com.grigor.disco;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks the GitHub release when the app opens and installs newer builds. */
class Updater {
    private static final String BASE =
            "https://github.com/grigor-stoyanov/disco-lights/releases/download/latest/";

    private final MainActivity activity;
    private long lastCheck;
    private int askedFor;
    private boolean waitingForPermission;
    private boolean downloading;

    Updater(MainActivity activity) {
        this.activity = activity;
    }

    /** Called from onResume. */
    void onResume() {
        if (waitingForPermission && canInstall()) {
            waitingForPermission = false;
            download();
            return;
        }
        // Check every time the app comes to the front, at most once a minute.
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastCheck != 0 && now - lastCheck < 60_000) return;
        lastCheck = now;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(new String(fetch(BASE + "version.json"), "UTF-8"));
                int latest = o.getInt("versionCode");
                String notes = o.optString("notes", "");
                if (latest > currentVersion() && latest != askedFor) {
                    activity.runOnUiThread(() -> ask(latest, notes));
                }
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: try again next launch.
            }
        }, "disco-update-check").start();
    }

    private void ask(int latest, String notes) {
        if (activity.isFinishing() || latest == askedFor) return;
        askedFor = latest;
        String msg = "Version 1." + latest + " is ready (you have 1." + currentVersion() + ").";
        if (!notes.isEmpty()) msg += "\n\nWhat's new: " + notes;
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Update available")
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> startInstall())
                .setNegativeButton("Later", null)
                .show();
    }

    private void startInstall() {
        if (!canInstall()) {
            waitingForPermission = true;
            toast("Allow Disco Lights to install updates, then go back.");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        download();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void download() {
        if (downloading) return;
        downloading = true;
        toast("Downloading update…");
        new Thread(() -> {
            try {
                byte[] apk = fetch(BASE + "DiscoLights.apk");
                PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setSize(apk.length);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (OutputStream out = session.openWrite("DiscoLights.apk", 0, apk.length)) {
                        out.write(apk);
                        session.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent done = PendingIntent.getBroadcast(activity, id,
                            new Intent(activity, InstallReceiver.class), flags);
                    session.commit(done.getIntentSender());
                }
            } catch (Exception e) {
                activity.runOnUiThread(() -> toast("Update failed: " + e.getMessage()));
            } finally {
                downloading = false;
            }
        }, "disco-update-download").start();
    }

    String versionName() {
        return "1." + currentVersion();
    }

    @SuppressWarnings("deprecation")
    private int currentVersion() {
        try {
            PackageInfo p = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "DiscoLights");
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }
}
