package com.apexcare.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared protect-list + package validation for DeviceBridge, widget, and root paths.
 * Rejects shell-metacharacter injection before any {@code am force-stop} / kill.
 */
public final class ProtectedPackages {
    private static final Pattern VALID_PKG = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    /** User-added hold list. Copied before mutate — SharedPreferences StringSet is not safe in-place. */
    public static final String USER_PREFS = "apex_user_protect";
    private static final String KEY_USER = "packages";
    private static final int MAX_USER = 80;

    private static final Set<String> CORE;
    static {
        Set<String> s = new HashSet<>();
        String[] pkgs = {
                "android",
                "com.android.systemui",
                "com.android.phone",
                "com.android.server.telecom",
                "com.android.settings",
                "com.android.settings.intelligence",
                "com.android.providers.settings",
                "com.android.providers.telephony",
                "com.android.providers.contacts",
                "com.android.providers.media",
                "com.android.providers.media.module",
                "com.android.providers.downloads",
                "com.android.providers.calendar",
                "com.android.inputmethod.latin",
                "com.google.android.inputmethod.latin",
                "com.samsung.android.honeyboard",
                "com.sec.android.inputmethod",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.google.android.gsf.login",
                "com.google.android.ext.services",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.bluetooth",
                "com.android.nfc",
                "com.android.keychain",
                "com.android.shell",
                "com.android.vending",
                "com.android.networkstack",
                "com.android.networkstack.tethering",
                "com.android.wifi",
                "com.android.se",
                "com.android.mms",
                "com.samsung.android.messaging",
                "com.samsung.android.dialer",
                "com.samsung.android.incallui",
                "com.samsung.android.app.telephonyui",
                "com.sec.android.app.launcher",
                "com.samsung.android.lool",
                "com.samsung.android.sm",
                "com.samsung.android.sm.devicesecurity",
                "com.samsung.android.knox.containercore",
                "com.samsung.android.knox.attestation",
                "com.samsung.android.biometrics.app.setting",
                "com.samsung.android.samsungpass",
                "com.samsung.android.saiv.face",
                "com.android.systemui.accessibility.accessibilitymenu",
                "com.google.android.apps.accessibility.voiceaccess",
                "com.apexcare.app",
                "com.samsung.android.scloud",
                "com.osp.app.signin",
                "com.samsung.android.samsungpassautofill",
                "com.samsung.android.authfw",
                "com.samsung.android.fmm",
                "com.samsung.android.app.find",
                "com.samsung.android.emergency",
                "com.sec.android.app.safetyassurance",
                "com.samsung.android.oneconnect",
                "com.samsung.android.app.watchmanager",
                "com.samsung.android.da.daagent",
                "com.sec.android.app.desktoplauncher",
                "com.samsung.desktopsystemui",
                "com.sec.android.app.dexonpc",
                "com.android.launcher3",
                "com.sec.android.app.samsungapps",
                "com.samsung.android.app.aodservice",
                "com.samsung.android.app.cocktailbarservice",
                "com.samsung.android.app.routines",
                "com.google.android.apps.wellbeing",
                "com.samsung.android.forest",
                "com.sec.android.app.setupwizard",
                "com.samsung.android.knox.containeragent",
                "com.samsung.klmsagent",
                "com.topjohnwu.magisk",
                "io.github.vvb2060.magisk",
                "io.github.huskydg.magisk",
                "io.github.vvb2060.magisk.alpha",
                "me.weishu.kernelsu",
                "com.rifsxd.ksunext",
                "me.bmax.apatch",
                "com.samsung.android.spay",
                "com.samsung.android.spayfw",
                "com.samsung.android.samsungpay.gear",
                "com.sec.android.app.shealth",
                "com.samsung.android.heartplugin",
                "com.samsung.android.mdx",
                "com.samsung.android.rubin.app",
                "com.samsung.android.app.galaxyregistry",
                "com.samsung.android.knox.kpu",
                "com.samsung.android.app.contacts",
                "com.android.contacts",
                "com.samsung.android.calendar",
                "com.sec.android.app.clockpackage",
                "com.google.android.apps.nexuslauncher",
                // v1.0.4 — camera / gallery / account / IMS / Auto / Galaxy AI / Secure Folder
                "com.sec.android.app.camera",
                "com.samsung.android.camera",
                "com.android.camera2",
                "com.sec.android.gallery3d",
                "com.samsung.android.gallery3d",
                "com.samsung.android.samsungaccount",
                "com.google.android.apps.walletnfcrel",
                "com.google.android.apps.messaging",
                "com.samsung.knox.securefolder",
                "com.samsung.android.bixby.agent",
                "com.samsung.android.bixby.wakeup",
                "com.samsung.android.app.sharelive",
                "com.samsung.android.aware.service",
                "com.samsung.android.privateshare",
                "com.google.android.projection.gearhead",
                "com.sec.imsservice",
                "com.samsung.ims",
                "com.samsung.android.intellivoiceservice",
                "com.samsung.android.offline.languagemodel",
                "com.samsung.android.app.interpreter",
                "com.samsung.android.app.smartcapture",
                "com.samsung.android.mcfds",
                "com.samsung.android.mdecservice",
                "com.samsung.android.app.watchmanager2",
                "com.samsung.android.geargplugin",
                // NPU / GPU / thermal — never force-stop (Galaxy AI, GOS, SDHMS)
                "com.samsung.android.game.gos",
                "com.samsung.android.game.gametools",
                "com.samsung.android.gpuwatch",
                "com.samsung.gpuwatchapp",
                "com.samsung.android.visionintelligence",
                "com.samsung.android.npu.service",
                "com.samsung.android.smartface",
                "com.sec.android.sdhms"
        };
        Collections.addAll(s, pkgs);
        CORE = Collections.unmodifiableSet(s);
    }

    private ProtectedPackages() {}

    public static boolean isValidPackage(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.length() > 200) {
            return false;
        }
        // Block shell metacharacters / path tricks even if pattern somehow drifts
        if (packageName.indexOf(' ') >= 0
                || packageName.indexOf(';') >= 0
                || packageName.indexOf('|') >= 0
                || packageName.indexOf('&') >= 0
                || packageName.indexOf('$') >= 0
                || packageName.indexOf('`') >= 0
                || packageName.indexOf('\n') >= 0
                || packageName.indexOf('\r') >= 0
                || packageName.indexOf('<') >= 0
                || packageName.indexOf('>') >= 0
                || packageName.indexOf('(') >= 0
                || packageName.indexOf(')') >= 0
                || packageName.indexOf('{') >= 0
                || packageName.indexOf('}') >= 0
                || packageName.indexOf('[') >= 0
                || packageName.indexOf(']') >= 0
                || packageName.indexOf('"') >= 0
                || packageName.indexOf('\'') >= 0
                || packageName.indexOf('\\') >= 0
                || packageName.indexOf('/') >= 0
                || packageName.indexOf('*') >= 0
                || packageName.indexOf('?') >= 0
                || packageName.indexOf('!') >= 0
                || packageName.indexOf('#') >= 0
                || packageName.indexOf('~') >= 0) {
            return false;
        }
        return VALID_PKG.matcher(packageName).matches();
    }

    public static boolean isProtected(Context context, String packageName) {
        if (!isValidPackage(packageName)) return true;
        if (context != null && packageName.equals(context.getPackageName())) return true;
        if (CORE.contains(packageName)) return true;
        if (context != null && userSnapshot(context).contains(packageName)) return true;
        String lower = packageName.toLowerCase(Locale.US);
        if (lower.startsWith("com.android.providers.")) return true;
        if (lower.contains("telecom") || lower.contains("telephony")) return true;
        if (lower.contains("inputmethod") || lower.contains("honeyboard")) return true;
        if (lower.contains("permissioncontroller")) return true;
        if (lower.startsWith("com.samsung.android.biometrics")) return true;
        if (lower.startsWith("com.samsung.android.knox")) return true;
        if (lower.contains("magisk") || lower.contains("kernelsu") || lower.contains("apatch")) return true;
        if (lower.contains("samsungpass") || lower.startsWith("com.osp.app.signin")) return true;
        if (lower.contains("desktoplauncher") || lower.contains("desktopsystemui")) return true;
        if (lower.startsWith("com.samsung.android.fmm") || lower.contains("safetyassurance")) return true;
        if (lower.startsWith("com.samsung.android.spay") || lower.contains("samsungpay")) return true;
        if (lower.contains("shealth") || lower.contains("heartplugin")) return true;
        if (lower.startsWith("com.samsung.android.mdx")) return true;
        if (lower.contains("galaxyregistry")) return true;
        if (lower.contains("securefolder")) return true;
        if (lower.contains("bixby.agent") || lower.contains("bixby.wakeup")) return true;
        if (lower.contains("walletnfcrel")) return true;
        if (lower.contains("imsservice") || lower.contains(".ims.")) return true;
        if (lower.contains("projection.gearhead")) return true;
        if (lower.contains("samsungaccount")) return true;
        if (lower.contains("gallery3d") || lower.endsWith(".app.camera") || lower.contains("sec.android.app.camera")) {
            return true;
        }
        if (lower.contains("intellivoiceservice") || lower.contains("offline.languagemodel")) return true;
        if (lower.contains("privateshare") || lower.contains("app.sharelive")) return true;
        if (lower.contains(".npu") || lower.contains("neuralnetworks")) return true;
        if (lower.contains("gpuwatch") || lower.contains("game.gos") || lower.contains("gametools")) return true;
        if (lower.contains("visionintelligence") || lower.contains(".eden")) return true;
        if (lower.contains("sdhms") || lower.contains("smartface")) return true;
        return false;
    }

    public static Set<String> coreSnapshot() {
        return CORE;
    }

    public static Set<String> userSnapshot(Context context) {
        if (context == null) return Collections.emptySet();
        SharedPreferences sp = context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE);
        Set<String> raw = sp.getStringSet(KEY_USER, null);
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String p : raw) {
            if (isValidPackage(p)) out.add(p);
        }
        return Collections.unmodifiableSet(out);
    }

    public static boolean addUser(Context context, String packageName) {
        if (context == null || !isValidPackage(packageName)) return false;
        SharedPreferences sp = context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(sp.getStringSet(KEY_USER, Collections.emptySet()));
        if (next.size() >= MAX_USER && !next.contains(packageName)) return false;
        next.add(packageName);
        sp.edit().putStringSet(KEY_USER, next).apply();
        return true;
    }

    public static boolean removeUser(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        SharedPreferences sp = context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(sp.getStringSet(KEY_USER, Collections.emptySet()));
        boolean gone = next.remove(packageName);
        if (gone) sp.edit().putStringSet(KEY_USER, next).apply();
        return gone;
    }
}
