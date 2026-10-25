package com.tvinfo.collector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only diagnostic collector for an Android TV with a missing OEM power/standby setting.
 *
 * It only READS: build info, system properties, kernel/partition info, installed system
 * packages, settings values and exported settings-like activities. It changes nothing, needs
 * no permissions and has no network access. The report is shown on screen and saved as a
 * text file into the app's folder on every storage volume Android exposes (internal and,
 * if the TV mounts it, the USB drive).
 *
 * Values that look personal (serial numbers, MAC/IMEI-style addresses, device names, tokens)
 * are replaced with "<redacted>". Read the report before sharing it.
 */
public class MainActivity extends Activity {

    private static final String TOOL_VERSION = "2.0";

    /** Keys whose values are replaced with <redacted>. */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(serial|mac|imei|meid|address|token|password|passwd|secret|uuid|android_id|deviceid|account|ssid|device_name).*|.*_name$");

    /** Property names worth highlighting for the standby / boot-speed question. */
    private static final Pattern PROP_INTEREST = Pattern.compile(
            "(?i)(standby|suspend|sleep|quick|fast|boot|power|deep|wake|eco|hiber|cec|platform|chip|soc|board|hardware|oem|brand|region|sku|project|customer|flavor|variant|vendor|odm|product)");

    /** System packages worth listing activities for. */
    private static final Pattern PKG_INTEREST = Pattern.compile(
            "(?i)(setting|power|standby|factory|engineer|moto|skyworth|mediatek|mtk|mstar|amlogic|realtek|tvsetting|\\.tv|menu|cec|hdmi|eco|quick|boot|oem|ota|upgrade|update|config)");

    /** Activity class names worth listing. */
    private static final Pattern ACT_INTEREST = Pattern.compile(
            "(?i)(setting|power|standby|factory|engineer|menu|boot|eco|quick|config|test|debug|oem|sleep|suspend|ota|upgrade)");

    private ScrollView scrollView;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF101010);
        scrollView.setFocusable(true);
        scrollView.setFocusableInTouchMode(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 32, 48, 32);
        scrollView.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
        scrollView.requestFocus();

        showText("Collecting information... this takes a few seconds.\n"
                + "Use UP/DOWN on the remote to scroll. BACK exits.");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = safeCollect();
                final List<String> saved = save(report);
                final StringBuilder shown = new StringBuilder(report);
                shown.append("\n\n== Saved report ==\n");
                if (saved.isEmpty()) {
                    shown.append("Could not write a file. Photograph this screen instead.\n");
                } else {
                    for (String s : saved) {
                        shown.append(s).append('\n');
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        content.removeAllViews();
                        showText(shown.toString());
                        scrollView.scrollTo(0, 0);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ UI

    /** Splits long text over several TextViews so no single view becomes enormous. */
    private void showText(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder chunk = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            chunk.append(line).append('\n');
            if (++count >= 120) {
                addChunk(chunk.toString());
                chunk.setLength(0);
                count = 0;
            }
        }
        if (chunk.length() > 0) {
            addChunk(chunk.toString());
        }
    }

    private void addChunk(String s) {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xFFEEEEEE);
        tv.setText(s);
        content.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int step = 500;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scrollView.smoothScrollBy(0, step);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                scrollView.smoothScrollBy(0, -step);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                scrollView.smoothScrollBy(0, step * 4);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_PAGE_UP:
                scrollView.smoothScrollBy(0, -step * 4);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ------------------------------------------------------------------ collection

    private String safeCollect() {
        try {
            return collect();
        } catch (Throwable t) {
            return "Collection failed: " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private String collect() {
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
        sb.append("TV Info Collector ").append(TOOL_VERSION).append('\n');
        sb.append("Created: ").append(now).append('\n');
        sb.append("Read-only. Serial numbers, MAC-style addresses, device names and tokens are redacted.\n");

        section(sb, "Build");
        kv(sb, "MANUFACTURER", Build.MANUFACTURER);
        kv(sb, "BRAND", Build.BRAND);
        kv(sb, "MODEL", Build.MODEL);
        kv(sb, "DEVICE", Build.DEVICE);
        kv(sb, "PRODUCT", Build.PRODUCT);
        kv(sb, "BOARD", Build.BOARD);
        kv(sb, "HARDWARE", Build.HARDWARE);
        kv(sb, "BOOTLOADER", Build.BOOTLOADER);
        kv(sb, "FINGERPRINT", Build.FINGERPRINT);
        kv(sb, "DISPLAY", Build.DISPLAY);
        kv(sb, "ID", Build.ID);
        kv(sb, "TYPE", Build.TYPE);
        kv(sb, "TAGS", Build.TAGS);
        kv(sb, "HOST", Build.HOST);
        kv(sb, "USER", Build.USER);
        kv(sb, "BUILD TIME", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(Build.TIME)));
        kv(sb, "RELEASE", Build.VERSION.RELEASE);
        kv(sb, "SDK_INT", String.valueOf(Build.VERSION.SDK_INT));
        kv(sb, "SECURITY_PATCH", Build.VERSION.SECURITY_PATCH);
        kv(sb, "INCREMENTAL", Build.VERSION.INCREMENTAL);
        kv(sb, "CODENAME", Build.VERSION.CODENAME);
        kv(sb, "SUPPORTED_ABIS", Arrays.toString(Build.SUPPORTED_ABIS));

        section(sb, "Power state and uptime");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                kv(sb, "isInteractive", String.valueOf(pm.isInteractive()));
                kv(sb, "isPowerSaveMode", String.valueOf(pm.isPowerSaveMode()));
                kv(sb, "isDeviceIdleMode", String.valueOf(pm.isDeviceIdleMode()));
            }
        } catch (Throwable t) {
            kv(sb, "PowerManager", "(unreadable: " + t.getClass().getSimpleName() + ")");
        }
        long elapsed = SystemClock.elapsedRealtime();
        long up = SystemClock.uptimeMillis();
        kv(sb, "since boot (incl. deep sleep)", duration(elapsed));
        kv(sb, "awake time since boot", duration(up));
        kv(sb, "deep-sleep time since boot", duration(elapsed - up));
        sb.append("(Deep-sleep time stays at 0 on a TV that never really suspends.)\n");

        // System properties
        TreeMap<String, String> props = readProps();
        section(sb, "Properties matching standby/boot/power/platform keywords (" + countInterest(props) + ")");
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (PROP_INTEREST.matcher(e.getKey()).find()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        section(sb, "All system properties (" + props.size() + ")");
        for (Map.Entry<String, String> e : props.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }

        section(sb, "Kernel and hardware files");
        fileBlock(sb, "/proc/version", 2000);
        fileBlock(sb, "/proc/cmdline", 4000, true);
        fileBlock(sb, "/proc/device-tree/model", 500);
        fileBlock(sb, "/proc/device-tree/compatible", 500);
        fileBlock(sb, "/proc/cpuinfo", 2500);
        fileBlock(sb, "/proc/meminfo", 300);
        fileBlock(sb, "/proc/partitions", 4000);
        fileBlock(sb, "/proc/mtd", 3000);
        fileBlock(sb, "/sys/power/state", 200);
        fileBlock(sb, "/sys/power/mem_sleep", 200);
        fileBlock(sb, "/sys/power/autosleep", 200);
        listDir(sb, "/dev/block/by-name");
        listDir(sb, "/dev/block/platform");

        section(sb, "Mounted block devices");
        String mounts = readFile("/proc/mounts", 60000);
        for (String line : mounts.split("\n")) {
            if (line.startsWith("/dev/")) {
                sb.append(line).append('\n');
            }
        }

        section(sb, "Storage folders visible to this app");
        try {
            sb.append("Primary: ").append(Environment.getExternalStorageDirectory()).append('\n');
            File[] dirs = getExternalFilesDirs(null);
            for (File d : dirs) {
                sb.append("App folder: ").append(d == null ? "(unavailable)" : d.getAbsolutePath()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        }

        dumpSettings(sb, "Settings.Global", Settings.Global.CONTENT_URI);
        dumpSettings(sb, "Settings.System", Settings.System.CONTENT_URI);
        dumpSettings(sb, "Settings.Secure", Settings.Secure.CONTENT_URI);

        section(sb, "Active TCP listening ports (/proc/net/tcp)");
        sb.append(dumpListeningPorts("/proc/net/tcp"));
        sb.append(dumpListeningPorts("/proc/net/tcp6"));

        PackageManager pkgm = getPackageManager();
        section(sb, "System features");
        try {
            List<String> feats = new ArrayList<String>();
            FeatureInfo[] fi = pkgm.getSystemAvailableFeatures();
            if (fi != null) {
                for (FeatureInfo f : fi) {
                    if (f.name != null) {
                        feats.add(f.name);
                    }
                }
            }
            java.util.Collections.sort(feats);
            for (String f : feats) {
                sb.append(f).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        }

        TreeMap<String, String> system = new TreeMap<String, String>();
        int userApps = 0;
        try {
            List<ApplicationInfo> apps = pkgm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (!sys) {
                    userApps++;
                    continue;
                }
                String ver = "?";
                try {
                    PackageInfo pi = pkgm.getPackageInfo(ai.packageName, 0);
                    ver = String.valueOf(pi.versionName);
                } catch (Throwable ignored) {
                    // keep "?"
                }
                system.put(ai.packageName, ver + "  " + ai.sourceDir);
            }
        } catch (Throwable t) {
            sb.append("(package list unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        }
        section(sb, "System packages (" + system.size() + "), user-installed apps: " + userApps);
        for (Map.Entry<String, String> e : system.entrySet()) {
            sb.append(e.getKey()).append("  ").append(e.getValue()).append('\n');
        }

        section(sb, "Settings-style screens the system offers");
        String[] actions = new String[]{
                "android.settings.SETTINGS",
                "android.settings.DISPLAY_SETTINGS",
                "android.settings.DATE_SETTINGS",
                "android.settings.DEVICE_INFO_SETTINGS",
                "android.settings.INTERNAL_STORAGE_SETTINGS",
                "android.settings.BATTERY_SAVER_SETTINGS",
                "android.intent.action.POWER_USAGE_SUMMARY"
        };
        for (String action : actions) {
            listResolved(sb, new Intent(action), action);
        }
        Intent leanback = new Intent(Intent.ACTION_MAIN);
        leanback.addCategory("android.intent.category.LEANBACK_SETTINGS");
        listResolved(sb, leanback, "MAIN + LEANBACK_SETTINGS");
        Intent pref = new Intent(Intent.ACTION_MAIN);
        pref.addCategory(Intent.CATEGORY_PREFERENCE);
        listResolved(sb, pref, "MAIN + PREFERENCE");

        section(sb, "Exported activities in settings/power/factory-like system packages (listed only, nothing is launched)");
        int shown = 0;
        for (String pkg : system.keySet()) {
            if (!PKG_INTEREST.matcher(pkg).find()) {
                continue;
            }
            try {
                PackageInfo pi = pkgm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                if (pi.activities == null) {
                    continue;
                }
                for (ActivityInfo a : pi.activities) {
                    if (a.exported && ACT_INTEREST.matcher(a.name).find()) {
                        if (shown++ < 400) {
                            sb.append(pkg).append('/').append(a.name).append('\n');
                        }
                    }
                }
            } catch (Throwable ignored) {
                // some packages cannot be inspected; skip them
            }
        }
        if (shown > 400) {
            sb.append("... ").append(shown - 400).append(" more not shown\n");
        }
        if (shown == 0) {
            sb.append("(none found)\n");
        }

        section(sb, "Deep inspection of Factory and Agent Packages");
        dumpPackageComponents(sb, pkgm, "com.smartdevice.factory");
        dumpPackageComponents(sb, pkgm, "com.smartdevice.dtv.factorywritekey");
        dumpPackageComponents(sb, pkgm, "com.mediatek.agent");
        dumpPackageComponents(sb, pkgm, "com.mediatek.dmagent");
        dumpPackageComponents(sb, pkgm, "mtktvapi.agent");

        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static void section(StringBuilder sb, String title) {
        sb.append("\n== ").append(title).append(" ==\n");
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v).append('\n');
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE.matcher(key).matches();
    }

    private static TreeMap<String, String> readProps() {
        TreeMap<String, String> map = new TreeMap<String, String>();
        String out = run("getprop");
        Pattern kv = Pattern.compile("^\\[(.*?)\\]: \\[(.*)\\]$");
        for (String line : out.split("\n")) {
            Matcher m = kv.matcher(line.trim());
            if (m.matches()) {
                String k = m.group(1);
                map.put(k, isSensitive(k) ? "<redacted>" : m.group(2));
            }
        }
        return map;
    }

    private static int countInterest(Map<String, String> props) {
        int n = 0;
        for (String k : props.keySet()) {
            if (PROP_INTEREST.matcher(k).find()) {
                n++;
            }
        }
        return n;
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = r.readLine()) != null && count++ < 5000) {
                sb.append(line).append('\n');
            }
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return "(failed: " + t.getClass().getSimpleName() + ")\n";
        }
    }

    private static String readFile(String path, int max) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while (total < max && (n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (Throwable t) {
            return "(unreadable: " + t.getClass().getSimpleName() + ")";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static void fileBlock(StringBuilder sb, String path, int max) {
        fileBlock(sb, path, max, false);
    }

    private static void fileBlock(StringBuilder sb, String path, int max, boolean redactCmdline) {
        String text = readFile(path, max);
        if (redactCmdline) {
            text = text.replaceAll("(?i)((?:serialno|[a-z]*mac|[a-z]*address)=)\\S+", "$1<redacted>");
        }
        sb.append("--- ").append(path).append('\n').append(text).append('\n');
    }

    private static void listDir(StringBuilder sb, String path) {
        sb.append("--- ls ").append(path).append('\n');
        try {
            String[] names = new File(path).list();
            if (names == null) {
                sb.append("(not listable)\n");
                return;
            }
            Arrays.sort(names);
            for (String n : names) {
                sb.append(n).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        }
    }

    private void dumpSettings(StringBuilder sb, String title, Uri uri) {
        section(sb, title);
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"name", "value"}, null, null, "name");
            if (c == null) {
                sb.append("(no data)\n");
                return;
            }
            while (c.moveToNext()) {
                String k = c.getString(0);
                String v = c.getString(1);
                sb.append(k).append('=').append(isSensitive(k) ? "<redacted>" : v).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private void listResolved(StringBuilder sb, Intent intent, String label) {
        sb.append("--- ").append(label).append('\n');
        try {
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(intent, 0);
            if (list == null || list.isEmpty()) {
                sb.append("(nothing handles this)\n");
                return;
            }
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) {
                    sb.append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(")\n");
        }
    }

    private static void dumpPackageComponents(StringBuilder sb, PackageManager pm, String pkgName) {
        sb.append("\n--- Components of ").append(pkgName).append(" ---\n");
        try {
            PackageInfo pi = pm.getPackageInfo(pkgName,
                    PackageManager.GET_ACTIVITIES |
                    PackageManager.GET_RECEIVERS |
                    PackageManager.GET_SERVICES |
                    PackageManager.GET_PROVIDERS |
                    PackageManager.GET_PERMISSIONS);

            if (pi.activities != null) {
                sb.append("Activities (").append(pi.activities.length).append("):\n");
                for (ActivityInfo a : pi.activities) {
                    sb.append("  [Activity] ").append(a.name)
                      .append(" (exported=").append(a.exported)
                      .append(a.permission != null ? ", perm=" + a.permission : "")
                      .append(")\n");
                }
            }
            if (pi.receivers != null) {
                sb.append("Receivers (").append(pi.receivers.length).append("):\n");
                for (ActivityInfo r : pi.receivers) {
                    sb.append("  [Receiver] ").append(r.name)
                      .append(" (exported=").append(r.exported)
                      .append(r.permission != null ? ", perm=" + r.permission : "")
                      .append(")\n");
                }
            }
            if (pi.services != null) {
                sb.append("Services (").append(pi.services.length).append("):\n");
                for (ServiceInfo s : pi.services) {
                    sb.append("  [Service] ").append(s.name)
                      .append(" (exported=").append(s.exported)
                      .append(s.permission != null ? ", perm=" + s.permission : "")
                      .append(")\n");
                }
            }
            if (pi.providers != null) {
                sb.append("Providers (").append(pi.providers.length).append("):\n");
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    sb.append("  [Provider] ").append(p.name)
                      .append(" (auth=").append(p.authority)
                      .append(", exported=").append(p.exported).append(")\n");
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("(Package not installed)\n");
        } catch (Throwable t) {
            sb.append("(Error: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(")\n");
        }
    }

    private static String dumpListeningPorts(String procPath) {
        StringBuilder sb = new StringBuilder();
        String content = readFile(procPath, 64000);
        for (String line : content.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            // state 0A is TCP_LISTEN in /proc/net/tcp
            if (parts.length > 3 && "0A".equalsIgnoreCase(parts[3])) {
                String[] addr = parts[1].split(":");
                if (addr.length == 2) {
                    try {
                        int port = Integer.parseInt(addr[1], 16);
                        sb.append(procPath).append(" listening on port: ").append(port);
                        if (port == 5555) {
                            sb.append(" <--- ADB PORT IS OPEN!");
                        }
                        sb.append("\n");
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (sb.length() == 0) {
            sb.append(procPath).append(": (no listening ports found or unreadable)\n");
        }
        return sb.toString();
    }

    private static void copyApk(String srcPath, File destDir, String outName, List<String> saved) {
        File src = new File(srcPath);
        if (!src.exists() || !src.canRead()) {
            return;
        }
        File dest = new File(destDir, outName);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            saved.add("Extracted APK: " + dest.getAbsolutePath());
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private List<String> save(String report) {
        List<String> saved = new ArrayList<String>();
        String name = "tv-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        File[] dirs;
        try {
            dirs = getExternalFilesDirs(null);
        } catch (Throwable t) {
            return saved;
        }
        if (dirs == null) {
            return saved;
        }
        for (File d : dirs) {
            if (d == null) {
                continue;
            }
            FileOutputStream fo = null;
            try {
                d.mkdirs();
                File f = new File(d, name);
                fo = new FileOutputStream(f);
                fo.write(report.getBytes(StandardCharsets.UTF_8));
                fo.flush();
                saved.add(f.getAbsolutePath());

                // Copy key factory APKs to the same directory
                copyApk("/system/app/SkyFactory/SkyFactory.apk", d, "SkyFactory.apk", saved);
                copyApk("/system/product/app/FactoryWriteKey/FactoryWriteKey.apk", d, "FactoryWriteKey.apk", saved);
                copyApk("/system/product/app/TVRemoteserviceagent/TVRemoteserviceagent.apk", d, "TVRemoteserviceagent.apk", saved);
                copyApk("/system/product/app/DMRemoteserviceagent/DMRemoteserviceagent.apk", d, "DMRemoteserviceagent.apk", saved);
                copyApk("/system/product/app/mtktvapi_agent/mtktvapi_agent.apk", d, "mtktvapi_agent.apk", saved);
            } catch (Throwable ignored) {
                // that volume is not writable; try the next one
            } finally {
                if (fo != null) {
                    try {
                        fo.close();
                    } catch (Throwable ignored) {
                        // nothing to do
                    }
                }
            }
        }
        // Also attempt writing directly to public Downloads folder if accessible
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
                FileOutputStream fo = null;
                try {
                    File f = new File(downloadDir, name);
                    fo = new FileOutputStream(f);
                    fo.write(report.getBytes(StandardCharsets.UTF_8));
                    fo.flush();
                    saved.add(f.getAbsolutePath());

                    copyApk("/system/app/SkyFactory/SkyFactory.apk", downloadDir, "SkyFactory.apk", saved);
                    copyApk("/system/product/app/FactoryWriteKey/FactoryWriteKey.apk", downloadDir, "FactoryWriteKey.apk", saved);
                } catch (Throwable ignored) {
                    // Falls back to app-specific dirs if permission is restricted
                } finally {
                    if (fo != null) {
                        try { fo.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        return saved;
    }
}
