package com.bestaigen.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    // Адрес вашего генератора. Поменяйте здесь, если адрес другой.
    private static final String START_URL = "https://perchance.org/bestaigen";
    private static final int REQ_FILE = 1001;
    private static final int REQ_STORAGE = 1002;

    private WebView webView;
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        setContentView(root);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage / настройки / галерея
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true); // нужно для iframe-ов perchance

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                if (scheme.equals("http") || scheme.equals("https")) {
                    if (host.equals("perchance.org") || host.endsWith(".perchance.org")) return false;
                    openExternal(u);
                    return true;
                }
                if (scheme.equals("blob") || scheme.equals("data") || scheme.equals("about")) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                webView.setVisibility(View.VISIBLE);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimeType));

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (isOnline()) {
            webView.loadUrl(START_URL);
        } else {
            Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_LONG).show();
            webView.loadUrl(START_URL);
        }
    }

    // ---------- скачивание ----------
    private void handleDownload(String url, String ua, String cd, String mime) {
        String filename = URLUtil.guessFileName(url, cd, mime);
        if (url.startsWith("data:")) {
            saveDataUrl(url, filename);
        } else if (url.startsWith("blob:")) {
            final String fn = filename;
            String js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + url + "',true);"
                    + "x.responseType='blob';x.onload=function(){var r=new FileReader();"
                    + "r.onloadend=function(){AndroidBridge.saveDataUrl(r.result,'" + fn.replace("'", "") + "');};"
                    + "r.readAsDataURL(x.response);};x.send();})();";
            webView.evaluateJavascript(js, null);
        } else if (url.startsWith("http")) {
            if (needsLegacyPermission()) return;
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mime);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("cookie", cookies);
                req.addRequestHeader("User-Agent", ua);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, "Загрузка: " + filename, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean needsLegacyPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            Toast.makeText(this, "Разрешите доступ к памяти и повторите", Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private void saveDataUrl(String dataUrl, String filename) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return;
            String header = dataUrl.substring(5, comma);
            String mime = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] bytes = header.contains(";base64")
                    ? Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    : Uri.decode(dataUrl.substring(comma + 1)).getBytes("UTF-8");

            if (filename == null || filename.isEmpty() || filename.endsWith(".bin")) {
                String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                filename = "bestaigen_" + System.currentTimeMillis() + "." + (ext == null ? "bin" : ext);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
            } else {
                if (needsLegacyPermission()) return;
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(new File(dir, filename))) { fos.write(bytes); }
            }
            final String shown = filename;
            runOnUiThread(() -> Toast.makeText(this, "Сохранено в Загрузки: " + shown, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show());
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String filename) {
            MainActivity.this.saveDataUrl(dataUrl, filename);
        }
    }

    // ---------- прочее ----------
    private void openExternal(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new Uri[n];
                        for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
