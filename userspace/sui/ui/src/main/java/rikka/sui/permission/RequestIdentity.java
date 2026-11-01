/* SPDX-License-Identifier: GPL-3.0-or-later */
package rikka.sui.permission;

import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The full-UID identity the root server reports for a request. Sui grants apply to the whole UID,
 * so the dialog lists every member and returns the consent fingerprint of exactly what it showed.
 */
final class RequestIdentity {
    final List<String> packages;
    final String fingerprint;

    private RequestIdentity(List<String> packages, String fingerprint) {
        this.packages = packages;
        this.fingerprint = fingerprint;
    }

    /** Mirrors the server's consent fingerprint; the server denies a grant on any mismatch. */
    static RequestIdentity parse(int uid, String requester, String text) throws JSONException {
        if (text == null || text.length() > 128 * 1024) {
            throw new IllegalArgumentException("Invalid Sui identity");
        }
        JSONObject json = new JSONObject(text);
        if (json.getInt("schema") != 1 || json.getInt("uid") != uid) {
            throw new IllegalArgumentException("Unexpected Sui identity for UID " + uid);
        }
        JSONArray members = json.getJSONArray("packages");
        if (members.length() < 1 || members.length() > 256) {
            throw new IllegalArgumentException("Invalid UID membership");
        }
        List<String> packages = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder();
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.getJSONObject(i);
            String name = member.getString("package");
            long firstInstall = member.getLong("first_install");
            long lastUpdate = member.getLong("last_update");
            JSONArray signers = member.getJSONArray("signers");
            if (name.isEmpty() || packages.contains(name) || member.getInt("uid") != uid
                    || firstInstall <= 0 || lastUpdate < firstInstall
                    || signers.length() < 1 || signers.length() > 32) {
                throw new IllegalArgumentException("Invalid UID member");
            }
            List<String> digests = new ArrayList<>();
            for (int j = 0; j < signers.length(); j++) {
                String digest = signers.getString(j);
                if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid signer digest");
                digests.add(digest);
            }
            if (i != 0) fingerprint.append('\n');
            fingerprint.append(name).append('|').append(uid).append('|').append(firstInstall).append('|')
                    .append(lastUpdate).append('|').append(TextUtils.join(", ", digests));
            packages.add(name);
        }
        if (!packages.contains(requester)) {
            throw new IllegalArgumentException("Requester is not a member of UID " + uid);
        }
        return new RequestIdentity(Collections.unmodifiableList(packages), fingerprint.toString());
    }
}
