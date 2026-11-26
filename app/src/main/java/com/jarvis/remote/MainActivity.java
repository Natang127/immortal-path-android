package com.jarvis.remote;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvis Remote: a full-screen app for the Jarvis assistant running on your PC.
 * Paste the link Jarvis texts you once. After that the app finds your PC's new address
 * by itself every time Jarvis restarts (via a private ntfy.sh channel derived from your secret key;
 * the channel only ever holds the address, never the key).
 */
public class MainActivity extends Activity {
    private static final Pattern HOST = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY = Pattern.compile("[?&]k=([A-Za-z0-9_\\-]{16,})");

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private PermissionRequest pendingPerm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean showingSetup = false, onJarvis = false, refreshing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#05090f"));
        getWindow().setNavigationBarColor(Color.parseColor("#070d15"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#05090f"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " JarvisRemoteApp/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "JarvisApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.getHost() != null && u.getHost().endsWith("trycloudflare.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && onJarvis) offline();
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
                int c = resp.getStatusCode();
                if (r.isForMainFrame() && onJarvis && (c == 502 || c == 503 || c == 504 || c == 530 || c == 404 || c == 1033))
                    offline();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                ui.post(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                    }
                });
            }
        });

        tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS) tts.setLanguage(Locale.UK); });

        if (!handleIntent(getIntent())) start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        if (pendingPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && showingSetup) tryClipboard();   // copied the link in Telegram? pick it up automatically
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack() && !showingSetup) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------ link handling

    private boolean handleIntent(Intent i) {
        if (i == null) return false;
        String text = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getData().toString();
        else if (Intent.ACTION_SEND.equals(i.getAction())) text = i.getStringExtra(Intent.EXTRA_TEXT);
        return text != null && useLink(text);
    }

    private boolean useLink(String text) {
        Matcher h = HOST.matcher(text), k = KEY.matcher(text);
        if (!h.find()) return false;
        String base = h.group().toLowerCase(Locale.ROOT);
        SharedPreferences.Editor e = prefs.edit().putString("base", base);
        if (k.find()) e.putString("key", k.group(1));
        e.apply();
        if (prefs.getString("key", "").isEmpty()) return false;
        load();
        return true;
    }

    private void tryClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            if (c != null && c.getItemCount() > 0) {
                CharSequence t = c.getItemAt(0).coerceToText(this);
                if (t != null && KEY.matcher(t).find()) useLink(t.toString());
            }
        } catch (Exception ignored) { }
    }

    private void start() {
        if (prefs.getString("key", "").isEmpty()) { setup(); return; }
        load();
        refresh(false);   // make sure we have the newest address
    }

    private void load() {
        showingSetup = false;
        onJarvis = true;
        web.loadUrl(prefs.getString("base", "") + "/?k=" + prefs.getString("key", ""));
    }

    private String topic() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(prefs.getString("key", "").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return "jarvis-" + sb;
        } catch (Exception e) { return "jarvis-none"; }
    }

    /** Ask the private ntfy channel for the PC's current address. */
    private void refresh(boolean fromOffline) {
        if (refreshing) return;
        refreshing = true;
        new Thread(() -> {
            String newest = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://ntfy.sh/" + topic() + "/json?poll=1&since=48h").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = HOST.matcher(line);
                    if (line.contains("\"message\"") && m.find()) newest = m.group().toLowerCase(Locale.ROOT);
                }
                r.close();
            } catch (Exception ignored) { }
            final String found = newest;
            ui.post(() -> {
                refreshing = false;
                if (found != null && !found.equals(prefs.getString("base", ""))) {
                    prefs.edit().putString("base", found).apply();
                    load();
                } else if (fromOffline) {
                    ui.postDelayed(() -> { if (!showingSetup && !onJarvis) refresh(true); }, 8000);
                }
            });
        }).start();
    }

    private void offline() {
        onJarvis = false;
        web.loadDataWithBaseURL(null, PAGE_OFFLINE, "text/html", "utf-8", null);
        refresh(true);
    }

    private void setup() {
        showingSetup = true;
        onJarvis = false;
        web.loadDataWithBaseURL(null, PAGE_SETUP, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------ JS bridge

    class Bridge {
        @JavascriptInterface public void speak(String t) {
            if (tts != null) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j");
        }
        @JavascriptInterface public void setLink(String t) {
            ui.post(() -> { if (!useLink(t)) web.evaluateJavascript("bad()", null); });
        }
        @JavascriptInterface public void paste() { ui.post(MainActivity.this::tryClipboard); }
        @JavascriptInterface public void retry() { ui.post(() -> { load(); refresh(false); }); }
        @JavascriptInterface public void reset() {
            ui.post(() -> { prefs.edit().clear().apply(); setup(); });
        }
        @JavascriptInterface public void openTelegram() {
            ui.post(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("org.telegram.messenger");
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://"));
                try { startActivity(i); } catch (Exception ignored) { }
            });
        }
    }

    private static final String CSS = "<meta name=viewport content='width=device-width,initial-scale=1'><style>"
            + "body{margin:0;background:#05090f;color:#d8f6ff;font:16px system-ui,sans-serif;display:flex;flex-direction:column;"
            + "align-items:center;justify-content:center;min-height:100vh;padding:24px;box-sizing:border-box;text-align:center}"
            + ".orb{width:90px;height:90px;border-radius:50%;border:6px solid #33d6ff;box-shadow:0 0 30px #33d6ff;margin-bottom:22px;"
            + "animation:p 2s infinite}@keyframes p{50%{box-shadow:0 0 8px #33d6ff;opacity:.7}}"
            + "h2{letter-spacing:4px;margin:0 0 10px}p{color:#8fb3c6;line-height:1.5;margin:6px 0}"
            + "button,input{font:inherit;border-radius:12px;padding:13px;margin:6px 0;width:100%;max-width:340px;box-sizing:border-box}"
            + "button{background:#0c1520;color:#33d6ff;border:1px solid #33d6ff}input{background:#0c1520;color:#d8f6ff;border:1px solid #1d3344}"
            + ".s{color:#ff8080;min-height:20px}</style>";

    private static final String PAGE_SETUP = "<html><head>" + CSS + "</head><body><div class=orb></div><h2>J.A.R.V.I.S</h2>"
            + "<p>One-time setup:</p><p>1. In Telegram, text <b>app</b> to your Jarvis bot.</p>"
            + "<p>2. Long-press the link it sends → <b>Copy</b>.</p><p>3. Come back here. It connects by itself.</p>"
            + "<button onclick='JarvisApp.openTelegram()'>Open Telegram</button>"
            + "<button onclick='JarvisApp.paste()'>Paste copied link</button>"
            + "<input id=l placeholder='…or paste the link here'><button onclick='JarvisApp.setLink(document.getElementById(\"l\").value)'>Connect</button>"
            + "<p class=s id=s></p><script>function bad(){document.getElementById('s').textContent='That is not a Jarvis link - text \"app\" to the bot.'}</script>"
            + "</body></html>";

    private static final String PAGE_OFFLINE = "<html><head>" + CSS + "</head><body><div class=orb></div><h2>RECONNECTING</h2>"
            + "<p>Can't reach your PC right now.</p><p>It reconnects by itself as soon as Jarvis is running. "
            + "If your PC is off or asleep, turn it on and start Jarvis.</p>"
            + "<button onclick='JarvisApp.retry()'>Try again</button><button onclick='JarvisApp.openTelegram()'>Open Telegram</button>"
            + "<button onclick='if(confirm(\"Forget this PC and set up again?\"))JarvisApp.reset()'>Set up again</button>"
            + "<script>setTimeout(function(){JarvisApp.retry()},15000)</script></body></html>";
}
