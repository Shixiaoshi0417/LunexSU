/*
 * LunexSU adaptation of Sui. Copyright (c) 2026 LunexSU contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package rikka.sui.server;

import java.security.MessageDigest;
import rikka.sui.BuildConfig;

/** Pure identity predicate shared by the root server and its system_server snapshot. */
public final class ManagerTrust {
    private ManagerTrust() {}

    public static boolean matchesRecord(String name, int uid, String certificate) {
        return BuildConfig.LUNEXSU_MANAGER_CERT_SIZE > 1
                && (BuildConfig.LUNEXSU_MANAGER_PACKAGE.equals("com.shixiaoshi0417.lunexsu")
                    || BuildConfig.LUNEXSU_MANAGER_PACKAGE.equals("com.shixiaoshi0417.lunexsu.pr"))
                && BuildConfig.LUNEXSU_MANAGER_PACKAGE.equals(name)
                && uid >= 10000 && uid <= 19999
                && BuildConfig.LUNEXSU_MANAGER_CERT_SHA256.equalsIgnoreCase(certificate);
    }

    public static boolean accepts(String name, int uid, String certificate,
                                  int actualUid, byte[][] signers) {
        if (!matchesRecord(name, uid, certificate) || uid != actualUid
                || signers == null || signers.length != 1 || signers[0] == null
                || signers[0].length != BuildConfig.LUNEXSU_MANAGER_CERT_SIZE) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0]);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return BuildConfig.LUNEXSU_MANAGER_CERT_SHA256.equals(hex.toString());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return false;
        }
    }
}
