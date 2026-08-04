package com.apexcare.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native bridge: app inventory, memory stats, local heuristic security scan, safe close. */
public class DeviceBridge {
    private final Context context;

    private static final String[] SENSITIVE_PERMS = new String[]{
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.CALL_PHONE",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.QUERY_ALL_PACKAGES",
    };

    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>();
    static {
        PROTECTED_PACKAGES.add("android");
        PROTECTED_PACKAGES.add("com.android.systemui");
        PROTECTED_PACKAGES.add("com.android.settings");
        PROTECTED_PACKAGES.add("com.android.phone");
        PROTECTED_PACKAGES.add("com.android.server.telecom");
        PROTECTED_PACKAGES.add("com.android.providers.settings");
        PROTECTED_PACKAGES.add("com.android.providers.media");
        PROTECTED_PACKAGES.add("com.android.providers.contacts");
        PROTECTED_PACKAGES.add("com.android.providers.telephony");
        PROTECTED_PACKAGES.add("com.android.providers.downloads");
        PROTECTED_PACKAGES.add("com.android.inputmethod.latin");
        PROTECTED_PACKAGES.add("com.google.android.inputmethod.latin");
        PROTECTED_PACKAGES.add("com.google.android.gms");
        PROTECTED_PACKAGES.add("com.google.android.gsf");
        PROTECTED_PACKAGES.add("com.android.permissioncontroller");
        PROTECTED_PACKAGES.add("com.google.android.permissioncontroller");
        PROTECTED_PACKAGES.add("com.android.networkstack");
        PROTECTED_PACKAGES.add("com.android.bluetooth");
        PROTECTED_PACKAGES.add("com.android.nfc");
        PROTECTED_PACKAGES.add("com.android.keychain");
        PROTECTED_PACKAGES.add("com.android.shell");
    }

    public DeviceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            o.put("model", Build.MODEL != null ? Build.MODEL : "");
            o.put("android", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("native", true);
            return o.toString();
        } catch (Exception e) {
            return "{\"native\":true}";
        }
    }

    @JavascriptInterface
    public String getMemoryStats() {
        try {
            JSONObject o = new JSONObject();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("availBytes", mi.availMem);
                o.put("totalBytes", mi.totalMem);
                o.put("lowMemory", mi.lowMemory);
                o.put("freeRamGb", mi.availMem / (1024.0 * 1024.0 * 1024.0));
                o.put("totalRamGb", mi.totalMem / (1024.0 * 1024.0 * 1024.0));
            }
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            long used = total - avail;
            o.put("storageTotalBytes", total);
            o.put("storageAvailBytes", avail);
            o.put("storageUsedBytes", used);
            o.put("storageUsedGb", used / (1024.0 * 1024.0 * 1024.0));
            o.put("storageTotalGb", total / (1024.0 * 1024.0 * 1024.0));
            o.put("storageUsedPct", total > 0 ? (used * 100.0) / total : 0);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String scanInstalledApps() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<JSONObject> list = new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                try { list.add(appToJson(pm, ai)); } catch (Exception ignored) {}
            }
            Collections.sort(list, (a, b) -> {
                try { return a.getString("name").compareToIgnoreCase(b.getString("name")); }
                catch (Exception e) { return 0; }
            });
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", arr.length());
            result.put("scannedAt", System.currentTimeMillis());
            result.put("apps", arr);
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    /**
     * Best-effort background kill. Third-party apps cannot force-stop like OEM Device Care;
     * killBackgroundProcesses only affects own-UID processes on stock Android, but we still
     * refuse protected packages so we never target core OS / telephony.
     */
    @JavascriptInterface
    public String closeBackgroundApp(String packageName) {
        try {
            JSONObject result = new JSONObject();
            result.put("packageName", packageName != null ? packageName : "");
            if (packageName == null || packageName.isEmpty()) {
                result.put("ok", false);
                result.put("reason", "Missing package");
                return result.toString();
            }
            if (packageName.equals(context.getPackageName())) {
                result.put("ok", false);
                result.put("reason", "This app");
                return result.toString();
            }
            if (PROTECTED_PACKAGES.contains(packageName)
                    || packageName.startsWith("com.android.providers.")
                    || packageName.toLowerCase().contains("telecom")
                    || packageName.toLowerCase().contains("telephony")) {
                result.put("ok", false);
                result.put("reason", "Protected · core OS");
                return result.toString();
            }
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
            }
            result.put("ok", true);
            result.put("reason", "killBackgroundProcesses requested");
            return result.toString();
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("packageName", packageName != null ? packageName : "");
                err.put("reason", e.getMessage() != null ? e.getMessage() : "close failed");
                return err.toString();
            } catch (Exception e2) {
                return "{\"ok\":false}";
            }
        }
    }

    @JavascriptInterface
    public String runHeuristicScan() {
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            JSONArray findings = new JSONArray();
            int packages = 0, userApps = 0, systemApps = 0, disabled = 0;
            int highRiskPermHits = 0, sideloaded = 0, debuggable = 0, outdatedTarget = 0;
            int malwareSignals = 0, puaSignals = 0;
            long now = System.currentTimeMillis();
            long recentWindow = 72L * 60L * 60L * 1000L;

            Set<String> trusted = new HashSet<>();
            trusted.add("com.android.vending");
            trusted.add("com.google.android.packageinstaller");
            trusted.add("com.samsung.android.packageinstaller");
            trusted.add("com.android.packageinstaller");
            trusted.add("com.amazon.venezia");
            trusted.add("com.sec.android.app.samsungapps");
            trusted.add(context.getPackageName());

            for (ApplicationInfo ai : apps) {
                packages++;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system) systemApps++; else userApps++;
                if (!ai.enabled) disabled++;

                String label = safeLabel(pm, ai);
                PackageInfo pi = null;
                try {
                    int flags = PackageManager.GET_PERMISSIONS;
                    pi = pm.getPackageInfo(ai.packageName, flags);
                } catch (Exception ignored) {}

                if (!system && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    debuggable++;
                    malwareSignals++;
                    findings.put(finding("high", "Debuggable app",
                            label + " is marked debuggable.", ai.packageName, 12, "malware"));
                }

                boolean unknown = false;
                boolean untrusted = false;
                if (!system) {
                    String installer = null;
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            installer = pm.getInstallSourceInfo(ai.packageName).getInstallingPackageName();
                        } else {
                            installer = pm.getInstallerPackageName(ai.packageName);
                        }
                    } catch (Exception ignored) {}
                    unknown = installer == null || installer.isEmpty();
                    untrusted = !unknown && !trusted.contains(installer);
                    if (unknown || untrusted) {
                        sideloaded++;
                        puaSignals++;
                        findings.put(finding(unknown ? "high" : "medium",
                                unknown ? "Unknown installer" : "Non-store installer",
                                label + (unknown ? " has no recorded installer."
                                        : " installed by " + installer + "."),
                                ai.packageName, unknown ? 10 : 5, "pua"));
                    }

                    String lowerName = label.toLowerCase();
                    String lowerPkg = ai.packageName.toLowerCase();
                    if (lowerName.contains("cleaner") || lowerName.contains("booster")
                            || lowerName.contains("speed up") || lowerName.contains("ram booster")
                            || lowerPkg.contains(".cleaner") || lowerPkg.contains(".booster")
                            || lowerPkg.contains("sideload")) {
                        puaSignals++;
                        findings.put(finding("high", "Potentially unwanted app pattern",
                                label + " matches known PUA cleaner/booster naming.",
                                ai.packageName, 12, "pua"));
                    }
                }

                if (!system && ai.targetSdkVersion > 0 && ai.targetSdkVersion < 26) {
                    outdatedTarget++;
                    findings.put(finding("medium", "Outdated target SDK",
                            label + " targets API " + ai.targetSdkVersion + ".",
                            ai.packageName, 5, "risk"));
                }

                if (!system && pi != null && pi.firstInstallTime > 0
                        && (now - pi.firstInstallTime) < recentWindow
                        && (unknown || untrusted)) {
                    findings.put(finding("info", "Recently sideloaded",
                            label + " installed within 72 hours from non-store source.",
                            ai.packageName, 2, "info"));
                }

                if (!system && pi != null && pi.requestedPermissions != null) {
                    List<String> hits = new ArrayList<>();
                    for (String perm : pi.requestedPermissions) {
                        for (String s : SENSITIVE_PERMS) {
                            if (s.equals(perm)) { hits.add(shortPerm(perm)); break; }
                        }
                    }
                    boolean hasInstall = hits.contains("REQUEST_INSTALL_PACKAGES");
                    boolean hasAdmin = hits.contains("BIND_DEVICE_ADMIN");
                    boolean hasA11y = hits.contains("BIND_ACCESSIBILITY_SERVICE");
                    boolean hasOverlay = hits.contains("SYSTEM_ALERT_WINDOW");
                    int critical = 0;
                    if (hasInstall) critical++;
                    if (hasAdmin) critical++;
                    if (hasA11y) critical++;
                    if (hasOverlay) critical++;

                    if (critical >= 2 || (critical >= 1 && unknown)) {
                        highRiskPermHits++;
                        malwareSignals++;
                        findings.put(finding("high", "Malware-capable permission set",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, 13, "malware"));
                    } else if (hits.size() >= 3) {
                        highRiskPermHits++;
                        puaSignals++;
                        findings.put(finding("medium", "Elevated permission set",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, Math.min(10, 2 + hits.size()), "risk"));
                    } else if (hasInstall || hasAdmin || hasA11y) {
                        highRiskPermHits++;
                        findings.put(finding("high", "High-impact permission",
                                label + " requests: " + join(hits, ", "),
                                ai.packageName, 10, "risk"));
                    }
                }
            }

            int score = 100;
            for (int i = 0; i < findings.length(); i++) {
                score -= findings.getJSONObject(i).optInt("weight", 0);
            }
            if (disabled > 15) {
                score -= 4;
                findings.put(finding("info", "Many disabled packages",
                        disabled + " packages are disabled.", null, 0, "info"));
            }
            score = Math.max(12, Math.min(99, score));

            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < findings.length(); i++) sorted.add(findings.getJSONObject(i));
            Collections.sort(sorted, (a, b) ->
                    severityRank(b.optString("severity")) - severityRank(a.optString("severity")));
            JSONArray ordered = new JSONArray();
            for (JSONObject o : sorted) ordered.put(o);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("score", score);
            result.put("scannedAt", now);
            result.put("packages", packages);
            result.put("userApps", userApps);
            result.put("systemApps", systemApps);
            result.put("disabled", disabled);
            result.put("sideloaded", sideloaded);
            result.put("debuggable", debuggable);
            result.put("highRiskPermHits", highRiskPermHits);
            result.put("outdatedTarget", outdatedTarget);
            result.put("malwareSignals", malwareSignals);
            result.put("puaSignals", puaSignals);
            result.put("findingCount", ordered.length());
            result.put("findings", ordered);
            result.put("method", "local-heuristic-v2");
            return result.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    private static int severityRank(String s) {
        if ("high".equals(s)) return 4;
        if ("medium".equals(s)) return 3;
        if ("low".equals(s)) return 2;
        return 1;
    }

    private static String shortPerm(String perm) {
        int idx = perm.lastIndexOf('.');
        return idx >= 0 ? perm.substring(idx + 1) : perm;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static JSONObject finding(String severity, String title, String detail,
                                     String packageName, int weight, String kind) throws Exception {
        JSONObject o = new JSONObject();
        o.put("severity", severity);
        o.put("title", title);
        o.put("detail", detail);
        o.put("packageName", packageName != null ? packageName : JSONObject.NULL);
        o.put("weight", weight);
        o.put("kind", kind != null ? kind : "risk");
        return o;
    }

    private static String safeLabel(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence labelCs = pm.getApplicationLabel(ai);
            return labelCs != null ? labelCs.toString() : ai.packageName;
        } catch (Exception e) {
            return ai.packageName;
        }
    }

    private static JSONObject appToJson(PackageManager pm, ApplicationInfo ai) throws Exception {
        String label = safeLabel(pm, ai);
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        String versionName = "";
        long versionCode = 0, firstInstall = 0, lastUpdate = 0;
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            versionName = pi.versionName != null ? pi.versionName : "";
            versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            firstInstall = pi.firstInstallTime;
            lastUpdate = pi.lastUpdateTime;
        } catch (Exception ignored) {}
        JSONObject row = new JSONObject();
        row.put("name", label);
        row.put("packageName", ai.packageName);
        row.put("system", system);
        row.put("updatedSystem", (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        row.put("enabled", ai.enabled);
        row.put("versionName", versionName);
        row.put("versionCode", versionCode);
        row.put("firstInstallTime", firstInstall);
        row.put("lastUpdateTime", lastUpdate);
        row.put("uid", ai.uid);
        row.put("targetSdk", ai.targetSdkVersion);
        boolean closeable = !PROTECTED_PACKAGES.contains(ai.packageName)
                && !ai.packageName.startsWith("com.android.providers.");
        row.put("closeable", closeable);
        row.put("protectReason", closeable ? "none" : "core_os");
        return row;
    }

    private static String errorJson(Exception e) {
        try {
            JSONObject err = new JSONObject();
            err.put("ok", false);
            err.put("error", e.getMessage() != null ? e.getMessage() : "scan failed");
            err.put("apps", new JSONArray());
            err.put("findings", new JSONArray());
            err.put("count", 0);
            return err.toString();
        } catch (Exception e2) {
            return "{\"ok\":false,\"apps\":[],\"findings\":[],\"count\":0}";
        }
    }
}
