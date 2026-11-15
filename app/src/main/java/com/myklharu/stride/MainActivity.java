package com.myklharu.stride;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.io.File;

/**
 * Stride: the web app in app/src/main/assets/index.html, shown full screen in a WebView.
 * The page is served from https://appassets.androidplatform.net so it gets a proper origin
 * (localStorage keeps your data, and it can call Open Food Facts).
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final int FILE_REQUEST = 41;
    private static final int HEALTH_REQUEST = 42;
    private static final int NOTIFY_REQUEST = 43;
    private String pendingOpen;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(night ? "#0E1412" : "#F1F3F0"));
        applyBars(night);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingOpen != null) {
                    callJs("onNativeOpen", pendingOpen);
                    pendingOpen = null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true; // links like YouTube open in the phone's browser or app
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return openFileChooser(callback, params);
            }
        });

        web.addJavascriptInterface(new Bridge(), "StrideNative");
        pendingOpen = getIntent() != null ? getIntent().getStringExtra("open") : null;

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    /** Photo picker with the camera offered alongside the gallery. */
    private boolean openFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;

        Intent camera = null;
        cameraUri = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File photo = File.createTempFile("photo", ".jpg", dir);
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
            camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            camera = null;
            cameraUri = null;
        }

        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        Intent chooser = Intent.createChooser(pick, "Add a photo");
        if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});

        try {
            startActivityForResult(chooser, FILE_REQUEST);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            callback.onReceiveValue(null);
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String open = intent.getStringExtra("open");
        if (open != null) callJs("onNativeOpen", open);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == NOTIFY_REQUEST) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            callJs("onNotifyPermission", ok ? "granted" : "denied");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == HEALTH_REQUEST) {
            boolean ok;
            try {
                ok = HealthBridge.wasGranted(resultCode, data);
            } catch (Exception e) {
                ok = false;
            }
            callJs("onHealthPermission", ok ? "granted" : "denied");
            return;
        }
        if (requestCode != FILE_REQUEST || fileCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                result = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    /** Status and navigation bars follow the app's light or dark look. */
    @SuppressWarnings("deprecation")
    private void applyBars(boolean dark) {
        getWindow().setStatusBarColor(Color.parseColor(dark ? "#0E1412" : "#F1F3F0"));
        getWindow().setNavigationBarColor(Color.parseColor(dark ? "#172019" : "#FFFFFF"));
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        if (web != null) web.setBackgroundColor(Color.parseColor(dark ? "#0E1412" : "#F1F3F0"));
    }

    private void callJs(String fn, String value) {
        final String js = "window." + fn + " && window." + fn + "(" + JSONObject.quote(value) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void startScan() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String raw = barcode.getRawValue();
                    if (raw != null) callJs("onNativeBarcode", raw);
                    else callJs("onNativeBarcodeError", "Couldn't read that barcode");
                })
                .addOnCanceledListener(() -> callJs("onNativeBarcodeError", "cancelled"))
                .addOnFailureListener(e -> callJs("onNativeBarcodeError", e.getMessage() == null ? "Scanner unavailable" : e.getMessage()));
    }

    /** Functions the web app can call as window.StrideNative.* */
    private class Bridge {
        @JavascriptInterface
        public void scanBarcode() {
            runOnUiThread(MainActivity.this::startScan);
        }

        @JavascriptInterface
        public void setDark(boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }

        @JavascriptInterface
        public String healthStatus() {
            try {
                return HealthBridge.status(MainActivity.this);
            } catch (Throwable t) {
                return "unavailable";
            }
        }

        @JavascriptInterface
        public void healthCheck() {
            try {
                HealthBridge.hasPermission(MainActivity.this, result -> callJs("onHealthCheck", result));
            } catch (Throwable t) {
                callJs("onHealthCheck", "false");
            }
        }

        @JavascriptInterface
        public void requestHealth() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(HealthBridge.permissionIntent(MainActivity.this), HEALTH_REQUEST);
                } catch (Throwable t) {
                    callJs("onHealthPermission", "denied");
                }
            });
        }

        @JavascriptInterface
        public void readSteps(String isoDate) {
            try {
                HealthBridge.readSteps(MainActivity.this, isoDate, result -> callJs("onNativeSteps", isoDate + "|" + result));
            } catch (Throwable t) {
                callJs("onNativeSteps", isoDate + "|error:unavailable");
            }
        }

        @JavascriptInterface
        public void openHealthConnect() {
            runOnUiThread(() -> {
                String status = HealthBridge.status(MainActivity.this);
                Intent i;
                if ("available".equals(status)) {
                    i = new Intent(Build.VERSION.SDK_INT >= 34 ? "android.health.connect.action.HEALTH_HOME_SETTINGS" : "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS");
                } else {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"));
                    i.setPackage("com.android.vending");
                }
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean perm = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            return perm && nm != null && nm.areNotificationsEnabled();
        }

        @JavascriptInterface
        public void requestNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_REQUEST);
                } else {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null && !nm.areNotificationsEnabled()) {
                        Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                        try {
                            startActivity(i);
                        } catch (ActivityNotFoundException ignored) {
                        }
                        callJs("onNotifyPermission", "settings");
                    } else {
                        callJs("onNotifyPermission", "granted");
                    }
                }
            });
        }

        @JavascriptInterface
        public void setReminder(String id, int hour, int minute, boolean on, String title, String text) {
            Reminders.set(MainActivity.this, id, hour, minute, on, title, text);
        }

        @JavascriptInterface
        public String getReminders() {
            return Reminders.all(MainActivity.this);
        }

        @JavascriptInterface
        public void share(String text, String subject) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(send, "Save your backup"));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.strideBack && window.strideBack()) ? 'y' : 'n'", value -> {
            if (!"\"y\"".equals(value)) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        callJs("onNativeResume", "");
    }
}
