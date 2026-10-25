/* SPDX-License-Identifier: GPL-3.0-or-later */
package rikka.sui.server;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Binder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import rikka.hidden.compat.PackageManagerApis;

/** A full-UID identity. A package name or a UID's continued existence is not an install identity. */
final class UidIdentity {
    private static final int GET_SIGNING_CERTIFICATES = 0x08000000;
    final int uid;
    final List<PackageInfo> packages;
    final List<List<String>> certificates;

    private UidIdentity(int uid, List<PackageInfo> packages, List<List<String>> certificates) {
        this.uid = uid;
        this.packages = packages;
        this.certificates = certificates;
    }

    private static List<String> names(int uid) {
        List<String> names = new ArrayList<>(new TreeSet<>(PackageManagerApis.getPackagesForUidNoThrow(uid)));
        if (names.isEmpty() || names.size() > 256) throw new SecurityException("Unverified UID " + uid);
        return names;
    }

    static UidIdentity capture(int uid) {
        if (uid < 0) throw new SecurityException("Invalid application UID");
        long identity = Binder.clearCallingIdentity();
        try {
            List<String> names = names(uid);
            List<PackageInfo> packages = new ArrayList<>();
            List<List<String>> certificates = new ArrayList<>();
            for (String name : names) {
                PackageInfo info = PackageManagerApis.getPackageInfoNoThrow(name, GET_SIGNING_CERTIFICATES, uid / 100000);
                if (info == null || !name.equals(info.packageName) || info.applicationInfo == null
                        || info.applicationInfo.uid != uid || info.firstInstallTime <= 0
                        || info.lastUpdateTime < info.firstInstallTime || info.signingInfo == null) {
                    throw new SecurityException("Incomplete package identity for " + name);
                }
                Signature[] signers = info.signingInfo.getApkContentsSigners();
                if (signers == null || signers.length == 0 || signers.length > 32) {
                    throw new SecurityException("Invalid package signers");
                }
                TreeSet<String> digests = new TreeSet<>();
                for (Signature signer : signers) {
                    if (signer == null) throw new SecurityException("Missing package signer");
                    byte[] certificate = signer.toByteArray();
                    if (certificate == null || certificate.length == 0) throw new SecurityException("Empty package signer");
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
                    StringBuilder hex = new StringBuilder(64);
                    for (byte value : digest) {
                        hex.append(Character.forDigit((value >>> 4) & 15, 16));
                        hex.append(Character.forDigit(value & 15, 16));
                    }
                    digests.add(hex.toString());
                }
                packages.add(info);
                certificates.add(new ArrayList<>(digests));
            }
            if (!names.equals(names(uid))) throw new SecurityException("Shared UID membership changed");
            return new UidIdentity(uid, packages, certificates);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    static String bindingOrNull(int uid) {
        try { return capture(uid).binding(); }
        catch (RuntimeException e) { return null; }
    }

    static boolean matches(int uid, String binding) {
        return binding != null && binding.equals(bindingOrNull(uid));
    }

    // Signed in-place updates retain an ACL. Reinstallation, signer changes and any shared-UID
    // membership change do not. Consent additionally covers lastUpdateTime to detect a changed prompt.
    String binding() { return fingerprint(false); }
    String consentFingerprint() { return fingerprint(true); }

    private String fingerprint(boolean updates) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            PackageInfo info = packages.get(i);
            if (i != 0) text.append('\n');
            text.append(info.packageName).append('|').append(uid).append('|').append(info.firstInstallTime).append('|');
            if (updates) text.append(info.lastUpdateTime).append('|');
            text.append(android.text.TextUtils.join(", ", certificates.get(i)));
        }
        return text.toString();
    }

    String toJson() throws JSONException {
        JSONArray members = new JSONArray();
        for (int i = 0; i < packages.size(); i++) {
            PackageInfo info = packages.get(i);
            members.put(new JSONObject().put("package", info.packageName).put("uid", uid)
                    .put("first_install", info.firstInstallTime).put("last_update", info.lastUpdateTime)
                    .put("signers", new JSONArray(certificates.get(i))));
        }
        return new JSONObject().put("schema", 1).put("uid", uid).put("packages", members).toString();
    }
}
