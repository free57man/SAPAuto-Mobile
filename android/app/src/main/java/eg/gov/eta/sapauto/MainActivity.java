package eg.gov.eta.sapauto;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * MainActivity – Enterprise WebView wrapper for SAPAuto V8.
 *
 * Features:
 *  - Full-screen WebView loading https://sapauto.duckdns.org
 *  - JavaScript + DOM storage + File access enabled
 *  - In-app navigation (back button navigates WebView history)
 *  - PDF + file download via Android DownloadManager
 *  - WebChromeClient: handles browser permission requests (notifications, camera)
 *  - Offline error overlay with retry button
 *  - POST_NOTIFICATIONS permission request on Android 13+
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SAPAuto.MainActivity";
    private static final String SERVER_URL = "https://sapauto.duckdns.org";
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int MAX_BACK_HISTORY_DEPTH = 100; // bounded operation

    private WebView webView;
    private LinearLayout offlineOverlay;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        offlineOverlay = findViewById(R.id.offlineOverlay);
        Button retryButton = findViewById(R.id.retryButton);

        // Validate critical views — fail safe if layout is broken
        if (webView == null) {
            Log.e(TAG, "CRITICAL: webview not found in layout. Cannot continue.");
            Toast.makeText(this, "Layout error — please reinstall the app", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Set up retry button
        if (retryButton != null) {
            retryButton.setOnClickListener(v -> loadApp());
        }

        configureWebView();
        requestNotificationPermission();
        loadApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        // Rule 5: clean up WebView resources
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Back button: navigate WebView history (bounded by MAX_BACK_HISTORY_DEPTH)
    // -------------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            int depth = 0;
            while (webView.canGoBack() && depth < MAX_BACK_HISTORY_DEPTH) {
                webView.goBack();
                depth++;
                // Only go back once per press — break immediately
                break;
            }
        } else {
            super.onBackPressed();
        }
    }

    // -------------------------------------------------------------------------
    // WebView configuration
    // -------------------------------------------------------------------------

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        if (settings == null) {
            Log.e(TAG, "WebSettings is null — cannot configure WebView");
            return;
        }

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);         // security: no local file access
        settings.setAllowContentAccess(false);      // security: no content:// URIs
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // User agent identifies SAPAuto enterprise app
        String baseUA = settings.getUserAgentString();
        if (baseUA == null) baseUA = "Android";
        settings.setUserAgentString(baseUA + " SAPAutoMobile/8.1");

        // Handle navigation — keep everything in-app
        webView.setWebViewClient(new SAPAutoWebViewClient());

        // Handle browser permission requests (notifications, camera, etc.)
        webView.setWebChromeClient(new SAPAutoWebChromeClient());

        // Handle file/PDF downloads via Android DownloadManager
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.isEmpty()) {
                Log.w(TAG, "Download attempted with null/empty URL — skipped");
                return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "sapauto_download_" + System.currentTimeMillis();
                }

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("SAPAuto – " + fileName);
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "SAPAuto/" + fileName);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) {
                    Log.e(TAG, "DownloadManager service unavailable");
                    Toast.makeText(this, "Download service unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }

                long downloadId = dm.enqueue(request);
                if (downloadId == -1L) {
                    Log.e(TAG, "DownloadManager.enqueue failed for URL: " + url);
                    Toast.makeText(this, "Failed to start download", Toast.LENGTH_SHORT).show();
                } else {
                    Log.i(TAG, "Download enqueued id=" + downloadId + " file=" + fileName);
                    Toast.makeText(this, "جاري التنزيل: " + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Download error for URL=" + url + ": " + e.getMessage(), e);
                Toast.makeText(this, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        Log.i(TAG, "WebView configured — UA: " + settings.getUserAgentString());
    }

    // -------------------------------------------------------------------------
    // Load the SAPAuto web app
    // -------------------------------------------------------------------------

    private void loadApp() {
        if (webView == null) {
            Log.e(TAG, "loadApp called but webView is null");
            return;
        }

        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network available — showing offline overlay");
            showOfflineOverlay(true);
            return;
        }

        showOfflineOverlay(false);
        String url = getString(R.string.server_url);
        if (url == null || url.isEmpty()) {
            url = SERVER_URL; // fallback constant
            Log.w(TAG, "server_url string resource empty, using constant: " + SERVER_URL);
        }

        Log.i(TAG, "Loading URL: " + url);
        webView.loadUrl(url);
    }

    private void showOfflineOverlay(boolean show) {
        if (offlineOverlay == null) return;
        offlineOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (webView != null) {
            webView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    // -------------------------------------------------------------------------
    // Network check
    // -------------------------------------------------------------------------

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable — assuming connected");
            return true;
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        boolean available = info != null && info.isConnected();
        Log.d(TAG, "Network available: " + available);
        return available;
    }

    // -------------------------------------------------------------------------
    // Notification permission (Android 13+)
    // -------------------------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted");
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied by user");
                // Not fatal — notifications just won't appear; app continues
            }
        }
    }

    // =========================================================================
    // Inner: WebViewClient — handles page navigation & errors
    // =========================================================================

    private class SAPAutoWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (view == null || request == null) {
                Log.w(TAG, "shouldOverrideUrlLoading: null view or request");
                return false;
            }

            Uri uri = request.getUrl();
            if (uri == null) {
                Log.w(TAG, "shouldOverrideUrlLoading: null URI");
                return false;
            }

            String scheme = uri.getScheme();
            if (scheme == null) return false;

            // Keep http/https inside the WebView
            if (scheme.equals("https") || scheme.equals("http")) {
                view.loadUrl(uri.toString());
                return true;
            }

            // mailto / tel / intent — open in external app
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    Log.w(TAG, "No app found to handle URI scheme: " + scheme);
                    Toast.makeText(MainActivity.this,
                        "No app available for: " + scheme, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to open external URI: " + uri + " — " + e.getMessage(), e);
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (url != null) {
                Log.d(TAG, "Page loaded: " + url);
                showOfflineOverlay(false);
            }
        }

        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceivedError(WebView view, WebResourceRequest request,
                                     WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request == null) return;

            // Only show offline overlay for the main frame, not sub-resources
            if (request.isForMainFrame()) {
                int code = (error != null) ? error.getErrorCode() : -1;
                CharSequence desc = (error != null) ? error.getDescription() : "Unknown";
                Log.e(TAG, "WebView main frame error code=" + code + " desc=" + desc
                    + " url=" + request.getUrl());
                showOfflineOverlay(true);
            }
        }
    }

    // =========================================================================
    // Inner: WebChromeClient — handles browser APIs (notifications, media, etc.)
    // =========================================================================

    private class SAPAutoWebChromeClient extends WebChromeClient {

        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onPermissionRequest(PermissionRequest request) {
            if (request == null) {
                Log.w(TAG, "onPermissionRequest: null request");
                return;
            }

            String[] resources = request.getResources();
            if (resources == null || resources.length == 0) {
                Log.w(TAG, "onPermissionRequest: no resources requested");
                request.deny();
                return;
            }

            // Grant notification and audio/video capture for the enterprise app
            runOnUiThread(() -> {
                try {
                    request.grant(resources);
                    Log.i(TAG, "Granted browser permission request: " + java.util.Arrays.toString(resources));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to grant permission: " + e.getMessage(), e);
                    request.deny();
                }
            });
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
            if (cm == null) return false;
            // Mirror browser console to Android logcat
            String msg = "[WebConsole:" + cm.lineNumber() + "] " + cm.message();
            switch (cm.messageLevel()) {
                case ERROR:   Log.e(TAG, msg); break;
                case WARNING: Log.w(TAG, msg); break;
                default:      Log.d(TAG, msg); break;
            }
            return true;
        }
    }
}
