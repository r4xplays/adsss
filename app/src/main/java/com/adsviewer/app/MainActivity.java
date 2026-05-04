package com.adsviewer.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdsViewer";
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String currentLanguage = "en-US,en;q=0.9";
    private String currentReferer = "https://www.google.com/";
    private int rotationCount = 0;
    private String currentExitIp = "unknown";

    // WARP / WireGuard state
    private Backend wgBackend;
    private final WarpTunnel warpTunnel = new WarpTunnel();
    private boolean warpUp = false;
    // Re-register a new WARP device every N rotations (for fresh exit IP).
    // Other rotations just bounce the tunnel for a lighter refresh.
    private static final int REREGISTER_EVERY_N_ROTATIONS = 5;
    private int rotationsSinceReregister = 0;

    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    private static final String[] DEVICE_MODELS = new String[] {
        "SM-S908B", "SM-S918B", "SM-A546B", "SM-G998U", "SM-N986U", "SM-F946B", "SM-A536E",
        "Pixel 6", "Pixel 6 Pro", "Pixel 7", "Pixel 7 Pro", "Pixel 8", "Pixel 8 Pro", "Pixel 9", "Pixel 9 Pro",
        "Redmi Note 12", "Redmi Note 13", "Redmi Note 11", "M2102J20SG", "M2103K19PG", "23021RAA2Y",
        "OnePlus 11", "OnePlus 12", "OnePlus 9 Pro", "PJF110", "CPH2581",
        "Nokia G50", "Nokia X20", "TA-1456",
        "moto g stylus 5G", "motorola edge 40", "XT2255-1",
        "ASUS_AI2202", "ASUS_I003D",
        "vivo X100", "V2309", "V2241A",
        "POCO X6 Pro", "POCO F5", "23076PC4BG",
        "realme GT 5", "RMX3700",
        "Honor Magic5", "PGT-N19",
        "Infinix HOT 30", "Infinix X6831"
    };

    private static final String[] LANGUAGES = new String[] {
        "en-US,en;q=0.9", "en-GB,en;q=0.8", "es-ES,es;q=0.9", "pt-BR,pt;q=0.9",
        "fr-FR,fr;q=0.9", "de-DE,de;q=0.9", "ar-SA,ar;q=0.9", "hi-IN,hi;q=0.9",
        "ru-RU,ru;q=0.9", "id-ID,id;q=0.9", "tr-TR,tr;q=0.9", "ja-JP,ja;q=0.9",
        "zh-CN,zh;q=0.9", "ko-KR,ko;q=0.9", "it-IT,it;q=0.9", "pl-PL,pl;q=0.9"
    };

    private static final String[] REFERERS = new String[] {
        "https://www.google.com/", "https://m.facebook.com/", "https://www.bing.com/",
        "https://duckduckgo.com/", "https://t.co/abc", "https://www.reddit.com/",
        "https://news.ycombinator.com/", "https://www.youtube.com/", "https://twitter.com/",
        "https://www.instagram.com/", "https://www.tiktok.com/", "https://www.linkedin.com/",
        "https://www.pinterest.com/", "https://www.snapchat.com/"
    };

    /** Tunnel descriptor required by GoBackend. */
    private static class WarpTunnel implements Tunnel {
        @Override public String getName() { return "warp"; }
        @Override public void onStateChange(State newState) {
            Log.d(TAG, "[WARP] Tunnel state: " + newState);
        }
    }

    public class AdCounterBridge {
        @JavascriptInterface
        public int getAdLoadCount() {
            return prefs.getInt("adLoadCount", 0);
        }

        @JavascriptInterface
        public void setAdLoadCount(int count) {
            prefs.edit().putInt("adLoadCount", count).apply();
            Log.d(TAG, "[BRIDGE] Saved ad count: " + count);

            if (count > 0 && count % 500 == 0) {
                Log.d(TAG, "[WARP] Ad load count reached " + count + ", triggering IP rotation");
                rotateWarpIdentity();
            }
        }
    }

    private String randomBuildId() {
        char[] prefixOptions = {'U','T','S','R','Q','P'};
        char p = prefixOptions[random.nextInt(prefixOptions.length)];
        int year = 22 + random.nextInt(4);
        int month = 1 + random.nextInt(12);
        int day = 1 + random.nextInt(28);
        int patch = random.nextInt(40);
        return String.format("%cP%dA.%02d%02d%02d.%03d",
                p, 1 + random.nextInt(2), year, month, day, patch);
    }

    private String randomUserAgent() {
        int kind = random.nextInt(10);
        if (kind == 0) {
            int iosMajor = 14 + random.nextInt(5);
            int iosMinor = random.nextInt(7);
            int chromeMajor = 110 + random.nextInt(25);
            int chromeMinor = random.nextInt(7000);
            int chromePatch = random.nextInt(250);
            return "Mozilla/5.0 (iPhone; CPU iPhone OS " + iosMajor + "_" + iosMinor
                    + " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/"
                    + chromeMajor + ".0." + chromeMinor + "." + chromePatch
                    + " Mobile/15E148 Safari/604.1";
        }
        int androidMajor = 9 + random.nextInt(7);
        int chromeMajor = 100 + random.nextInt(35);
        int chromeBuild = 1000 + random.nextInt(7500);
        int chromePatch = random.nextInt(250);
        String model = DEVICE_MODELS[random.nextInt(DEVICE_MODELS.length)];
        String build = (random.nextInt(2) == 0) ? "" : " Build/" + randomBuildId();
        if (random.nextInt(3) == 0) {
            model = model + "-" + (char) ('A' + random.nextInt(26)) + (100 + random.nextInt(900));
        }
        return "Mozilla/5.0 (Linux; Android " + androidMajor + "; " + model + build
                + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chromeMajor + ".0." + chromeBuild + "." + chromePatch
                + " Mobile Safari/537.36";
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AdsViewerPrefs", Context.MODE_PRIVATE);

        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new AdCounterBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d(TAG, "Page started: " + url);
                String jsCode =
                    "(function(){" +
                    "  var langs = '" + currentLanguage + "'.split(',');" +
                    "  try { Object.defineProperty(navigator, 'language', {get: function(){ return langs[0].split(';')[0]; }, configurable: true}); } catch(e) {}" +
                    "  try { Object.defineProperty(navigator, 'languages', {get: function(){ return langs.map(function(l){ return l.split(';')[0]; }); }, configurable: true}); } catch(e) {}" +
                    "  try {" +
                    "    Object.defineProperty(document, 'referrer', {get: function(){ return '" + currentReferer + "'; }, configurable: true});" +
                    "  } catch(e) {}" +
                    "  console.log('[SPOOF] Language:', navigator.language, 'Referrer:', document.referrer);" +
                    "})();";
                view.evaluateJavascript(jsCode, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished loading: " + url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "?";
                boolean forMain = request != null && request.isForMainFrame();
                Log.e(TAG, "[WV-ERR] " + (forMain ? "MAIN" : "sub") + " code=" + error.getErrorCode()
                        + " desc=" + error.getDescription() + " url=" + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[JS] " + cm.message() + " -- From line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }
        });

        // Register VPN permission launcher BEFORE starting tunnel
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Log.d(TAG, "[WARP] VPN permission granted");
                        startWarpTunnel(false);
                    } else {
                        Log.w(TAG, "[WARP] VPN permission denied; running without VPN");
                        Toast.makeText(this, "VPN permission denied. Running without WARP.", Toast.LENGTH_LONG).show();
                        startWithoutWarp();
                    }
                });

        // Initialize WireGuard backend (Go-based) and request VPN consent
        wgBackend = new GoBackend(getApplicationContext());
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            Log.d(TAG, "[WARP] Requesting VPN permission from user");
            vpnPermissionLauncher.launch(prep);
        } else {
            Log.d(TAG, "[WARP] VPN permission already granted");
            startWarpTunnel(false);
        }
    }

    private void startWarpTunnel(final boolean forceFreshRegistration) {
        executor.execute(() -> {
            int attempt = 0;
            Exception lastErr = null;
            while (attempt < 3) {
                attempt++;
                try {
                    WarpRegistration.WarpConfig wc =
                            WarpRegistration.getOrRegister(prefs, forceFreshRegistration);

                    Interface iface = new Interface.Builder()
                            .parsePrivateKey(wc.privateKeyBase64)
                            .parseAddresses(wc.v4Address + "/32, " + wc.v6Address + "/128")
                            .parseDnsServers("1.1.1.1, 1.0.0.1")
                            .build();

                    Peer peer = new Peer.Builder()
                            .parsePublicKey(wc.serverPublicKey)
                            .parseEndpoint(wc.endpoint)
                            .parseAllowedIPs("0.0.0.0/0, ::/0")
                            .parsePersistentKeepalive("25")
                            .build();

                    Config cfg = new Config.Builder()
                            .setInterface(iface)
                            .addPeer(peer)
                            .build();

                    // Bring tunnel down first if it's up (idempotent reconnect path)
                    try {
                        wgBackend.setState(warpTunnel, Tunnel.State.DOWN, null);
                    } catch (Exception ignored) {}

                    wgBackend.setState(warpTunnel, Tunnel.State.UP, cfg);
                    warpUp = true;
                    Log.d(TAG, "[WARP] Tunnel UP");

                    // Give WireGuard ~3s for handshake before probing exit IP
                    Thread.sleep(3000);
                    checkCurrentIp();

                    handler.post(() -> {
                        Toast.makeText(MainActivity.this,
                                "WARP connected! IP will rotate every 500 ads",
                                Toast.LENGTH_LONG).show();
                        rotateFingerprintAndReload(true);
                        handler.postDelayed(rotateRunnable, 10_000);
                    });
                    return;
                } catch (Exception e) {
                    lastErr = e;
                    Log.e(TAG, "[WARP] Attempt " + attempt + " failed: " + e.getMessage(), e);
                    try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { /* ignore */ }
                }
            }

            Log.e(TAG, "[WARP] All registration attempts failed", lastErr);
            handler.post(() -> {
                Toast.makeText(MainActivity.this,
                        "WARP unavailable - running without VPN", Toast.LENGTH_LONG).show();
                startWithoutWarp();
            });
        });
    }

    private void startWithoutWarp() {
        Log.w(TAG, "[APP] Starting WITHOUT WARP (direct mode)");
        warpUp = false;
        currentExitIp = "direct";
        rotateFingerprintAndReload(true);
        handler.postDelayed(rotateRunnable, 10_000);
    }

    /**
     * Triggered every 10 ad loads. Re-register a new WARP identity every Nth call
     * (gives a fresh 104.x exit IP). Otherwise just bounce the tunnel.
     */
    private void rotateWarpIdentity() {
        if (!warpUp) {
            Log.w(TAG, "[WARP] Tunnel not up, attempting to start fresh");
            startWarpTunnel(true);
            return;
        }
        rotationsSinceReregister++;
        boolean reregister = rotationsSinceReregister >= REREGISTER_EVERY_N_ROTATIONS;
        if (reregister) {
            Log.d(TAG, "[WARP] Re-registering new WARP device for fresh exit IP");
            rotationsSinceReregister = 0;
            // Drop cached creds so getOrRegister hits Cloudflare again
            WarpRegistration.clearCache(prefs);
            startWarpTunnel(true);
        } else {
            Log.d(TAG, "[WARP] Bouncing tunnel for IP refresh (rotation "
                    + rotationsSinceReregister + "/" + REREGISTER_EVERY_N_ROTATIONS + ")");
            startWarpTunnel(false);
        }
    }

    private void checkCurrentIp() {
        try {
            URL url = new URL("https://api.ipify.org?format=text");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String ip = reader.readLine();
            reader.close();
            conn.disconnect();
            currentExitIp = ip;
            Log.d(TAG, "[WARP] Current IP: " + currentExitIp);
        } catch (Exception e) {
            Log.e(TAG, "[WARP] Error checking IP: " + e.getMessage());
            currentExitIp = "unknown";
        }
    }

    private final Runnable rotateRunnable = new Runnable() {
        @Override public void run() {
            rotateFingerprintAndReload(false);
            handler.postDelayed(this, 10_000);
        }
    };

    private void rotateFingerprintAndReload(boolean firstLoad) {
        rotationCount++;

        String ua = randomUserAgent();
        webView.getSettings().setUserAgentString(ua);

        currentLanguage = LANGUAGES[random.nextInt(LANGUAGES.length)];
        currentReferer = REFERERS[random.nextInt(REFERERS.length)];

        Log.d(TAG, "=== ROTATION #" + rotationCount + " ===");
        Log.d(TAG, "User-Agent: " + ua.substring(0, Math.min(60, ua.length())) + "...");
        Log.d(TAG, "Language: " + currentLanguage);
        Log.d(TAG, "Referer: " + currentReferer);
        Log.d(TAG, "Exit IP: " + currentExitIp + (warpUp ? " [WARP ACTIVE]" : " [DIRECT]"));

        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.removeSessionCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        webView.clearSslPreferences();

        String visitorId = UUID.randomUUID().toString().replace("-", "");
        long ts = System.currentTimeMillis();
        String url = "file:///android_asset/index.html?vid=" + visitorId + "&t=" + ts;

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", currentLanguage);
        headers.put("Referer", currentReferer);

        Log.d(TAG, "Loading: " + url);
        webView.loadUrl(url, headers);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (wgBackend != null && warpUp) {
                wgBackend.setState(warpTunnel, Tunnel.State.DOWN, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "[WARP] Error tearing down tunnel: " + e.getMessage());
        }
        executor.shutdown();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
