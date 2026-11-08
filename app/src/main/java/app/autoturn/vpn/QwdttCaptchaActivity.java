package app.autoturn.vpn;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** User completes VK's challenge. No credentials are requested or extracted by AutoTurn. */
public final class QwdttCaptchaActivity extends Activity {
    private WebView web;
    private String session, request, profileId;
    private boolean completed;
    private android.window.OnBackInvokedCallback backCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expired = new Runnable() {
        @Override public void run() {
            if (!valid()) finish(); else handler.postDelayed(this, 1000);
        }
    };

    static boolean allowedUrl(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : new String[]{"vk.com", "vk.ru", "vk.me", "vkcdn.ru"})
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }

    private boolean valid() {
        JSONObject state = QwdttState.read(this);
        return session != null && session.equals(state.optString("session")) &&
                request.equals(state.optString("captchaRequest")) && QwdttState.pending(this, profileId);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        JSONObject state = QwdttState.read(this);
        session = getIntent().getStringExtra("session");
        request = getIntent().getStringExtra("request");
        profileId = state.optString("profileId");
        if (session == null || request == null || !valid() || !allowedUrl(state.optString("captchaUrl"))) {
            finish(); return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> { deliver("error:cancelled"); finish(); };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Подтверждение VK для qWDTT"); title.setPadding(24, 24, 24, 16);
        layout.addView(title);
        Button cancel = new Button(this);
        cancel.setText("Отменить проверку");
        cancel.setOnClickListener(v -> { deliver("error:cancelled"); finish(); });
        layout.addView(cancel);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void success(String token) {
                handler.post(() -> { if (valid()) { deliver(token); finish(); } });
            }
        }, "AutoTurnCaptcha");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest navigation) {
                String url = navigation.getUrl().toString();
                if (!allowedUrl(url)) return true;
                String token = navigation.getUrl().getQueryParameter("success_token");
                if (token != null) { deliver(token); finish(); return true; }
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (allowedUrl(url) && valid()) view.evaluateJavascript(INTERCEPTOR, null);
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        web.loadUrl(state.optString("captchaUrl"));
        handler.postDelayed(expired, 1000);
    }

    private void deliver(String token) {
        if (completed || !valid() || token == null || token.isEmpty() || token.length() > 16384 ||
                token.contains("\n") || token.contains("\r")) return;
        completed = true;
        startService(new Intent(this, XrayVpnService.class).setAction(XrayVpnService.ACTION_CAPTCHA)
                .putExtra("session", session).putExtra("request", request).putExtra("token", token));
    }

    // API 29–32 use this callback; modern Android uses the registered callback above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { deliver("error:cancelled"); super.onBackPressed(); }

    @Override public void onDestroy() {
        handler.removeCallbacks(expired);
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (web != null) { web.removeJavascriptInterface("AutoTurnCaptcha"); web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }

    // Observe VK's success response; the user performs the challenge in the visible WebView.
    private static final String INTERCEPTOR = "(function(){if(window.__autoTurnCaptcha)return;window.__autoTurnCaptcha=true;" +
            "function done(d){if(d&&d.response&&d.response.success_token)AutoTurnCaptcha.success(d.response.success_token);}" +
            "var f=window.fetch;window.fetch=function(){return f.apply(this,arguments).then(function(r){" +
            "if(r.url.indexOf('captchaNotRobot.check')>=0)r.clone().json().then(done).catch(function(){});return r;});};" +
            "var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){" +
            "if(String(u).indexOf('captchaNotRobot.check')>=0)this.addEventListener('load',function(){" +
            "try{done(JSON.parse(this.responseText));}catch(e){}});return o.apply(this,arguments);};})();";
}
