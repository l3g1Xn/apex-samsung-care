package com.apexcare.app;

import android.content.Context;

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
                "com.apexcare.app"
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
        String lower = packageName.toLowerCase(Locale.US);
        if (lower.startsWith("com.android.providers.")) return true;
        if (lower.contains("telecom") || lower.contains("telephony")) return true;
        if (lower.contains("inputmethod") || lower.contains("honeyboard")) return true;
        if (lower.contains("permissioncontroller")) return true;
        if (lower.startsWith("com.samsung.android.biometrics")) return true;
        if (lower.startsWith("com.samsung.android.knox")) return true;
        // Never force-stop Magisk itself mid-grant
        if (lower.contains("magisk")) return true;
        return false;
    }

    public static Set<String> coreSnapshot() {
        return CORE;
    }
}
