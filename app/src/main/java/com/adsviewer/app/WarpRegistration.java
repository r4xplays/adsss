package com.adsviewer.app;

import android.content.SharedPreferences;
import android.util.Log;

import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles Cloudflare WARP device registration.
 *
 * Uses the public consumer endpoint (same one wgcf and the official 1.1.1.1 app hit):
 *   POST https://api.cloudflareclient.com/v0a2405/reg
 *
 * Each call returns a fresh free-tier WARP identity (WireGuard server pubkey, endpoint,
 * client v4/v6 addresses, and a base64 client_id used as WireGuard "reserved" bytes).
 *
 * We persist the resulting config in SharedPreferences so the app reuses the same device
 * unless we explicitly request rotation (forceFreshRegistration).
 */
public class WarpRegistration {
    private static final String TAG = "AdsViewer/WARP-REG";

    private static final String REG_URL = "https://api.cloudflareclient.com/v0a2405/reg";
    private static final String UA = "okhttp/3.12.1";
    private static final String CF_CLIENT_VERSION = "a-6.30-3596";

    // SharedPreferences keys
    private static final String K_PRIV = "warp_priv";
    private static final String K_PUB = "warp_pub";
    private static final String K_SRV_PUB = "warp_srv_pub";
    private static final String K_ENDPOINT = "warp_endpoint";
    private static final String K_V4 = "warp_v4";
    private static final String K_V6 = "warp_v6";
    private static final String K_CLIENT_ID = "warp_client_id";

    public static class WarpConfig {
        public String privateKeyBase64;     // our WireGuard private key
        public String publicKeyBase64;      // our WireGuard public key
        public String serverPublicKey;      // Cloudflare's WireGuard pubkey
        public String endpoint;             // host:port
        public String v4Address;            // 172.16.0.x
        public String v6Address;            // 2606:...
        public String clientId;             // base64, used as 'reserved' bytes
    }

    /**
     * Loads cached config or registers a new device with Cloudflare.
     * @param forceFreshRegistration if true, ignores cached state and registers anew (rotation).
     */
    public static WarpConfig getOrRegister(SharedPreferences prefs, boolean forceFreshRegistration)
            throws Exception {

        if (!forceFreshRegistration && prefs.contains(K_PRIV) && prefs.contains(K_SRV_PUB)) {
            WarpConfig c = new WarpConfig();
            c.privateKeyBase64 = prefs.getString(K_PRIV, null);
            c.publicKeyBase64 = prefs.getString(K_PUB, null);
            c.serverPublicKey = prefs.getString(K_SRV_PUB, null);
            c.endpoint = prefs.getString(K_ENDPOINT, null);
            c.v4Address = prefs.getString(K_V4, null);
            c.v6Address = prefs.getString(K_V6, null);
            c.clientId = prefs.getString(K_CLIENT_ID, null);
            Log.d(TAG, "Reusing cached WARP registration: v4=" + c.v4Address);
            return c;
        }

        Log.d(TAG, "Registering new WARP device with Cloudflare...");

        // Generate fresh WireGuard keypair locally
        KeyPair kp = new KeyPair();
        String privB64 = kp.getPrivateKey().toBase64();
        String pubB64 = kp.getPublicKey().toBase64();

        JSONObject body = new JSONObject();
        body.put("key", pubB64);
        body.put("install_id", "");
        body.put("fcm_token", "");
        body.put("tos", iso8601Now());
        body.put("model", "PC");
        body.put("type", "Android");
        body.put("locale", "en_US");

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder()
                .url(REG_URL)
                .header("User-Agent", UA)
                .header("CF-Client-Version", CF_CLIENT_VERSION)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        String respText;
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            respText = rb != null ? rb.string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("WARP reg HTTP " + resp.code() + ": " + respText);
            }
        }

        Log.d(TAG, "WARP reg response received (" + respText.length() + " bytes)");

        JSONObject json = new JSONObject(respText);
        JSONObject config = json.getJSONObject("config");

        // peers[0]
        JSONArray peers = config.getJSONArray("peers");
        JSONObject peer0 = peers.getJSONObject(0);
        String srvPub = peer0.getString("public_key");
        JSONObject ep = peer0.getJSONObject("endpoint");
        String epHost = ep.getString("host"); // e.g. "engage.cloudflareclient.com:2408"

        // interface.addresses
        JSONObject iface = config.getJSONObject("interface");
        JSONObject addrs = iface.getJSONObject("addresses");
        String v4 = addrs.getString("v4");
        String v6 = addrs.getString("v6");

        String clientId = config.optString("client_id", "");

        WarpConfig c = new WarpConfig();
        c.privateKeyBase64 = privB64;
        c.publicKeyBase64 = pubB64;
        c.serverPublicKey = srvPub;
        c.endpoint = epHost;
        c.v4Address = v4;
        c.v6Address = v6;
        c.clientId = clientId;

        // Validate keys parse
        Key.fromBase64(c.privateKeyBase64);
        Key.fromBase64(c.serverPublicKey);

        prefs.edit()
                .putString(K_PRIV, c.privateKeyBase64)
                .putString(K_PUB, c.publicKeyBase64)
                .putString(K_SRV_PUB, c.serverPublicKey)
                .putString(K_ENDPOINT, c.endpoint)
                .putString(K_V4, c.v4Address)
                .putString(K_V6, c.v6Address)
                .putString(K_CLIENT_ID, c.clientId)
                .apply();

        Log.d(TAG, "[WARP] Registered with Cloudflare: v4=" + c.v4Address
                + " endpoint=" + c.endpoint);
        return c;
    }

    public static void clearCache(SharedPreferences prefs) {
        prefs.edit()
                .remove(K_PRIV).remove(K_PUB).remove(K_SRV_PUB)
                .remove(K_ENDPOINT).remove(K_V4).remove(K_V6).remove(K_CLIENT_ID)
                .apply();
    }

    private static String iso8601Now() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }
}
