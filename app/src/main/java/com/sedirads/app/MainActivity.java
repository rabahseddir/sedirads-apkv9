package com.sedirads.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "sedirads_prefs";
    private static final String PREF_NOTIFICATION_ASKED = "notification_asked";

    private final Set<String> appHosts = new HashSet<>(Arrays.asList(
            "sedirads.com",
            "www.sedirads.com"
    ));

    private SwipeRefreshLayout swipeRefreshLayout;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private CredentialManager credentialManager;
    private ExecutorService networkExecutor;

    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        credentialManager = CredentialManager.create(getApplicationContext());
        networkExecutor = Executors.newSingleThreadExecutor();

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        setupLaunchers();
        setupWebView();
        setupBackPressed();

        swipeRefreshLayout.setOnRefreshListener(() -> webView.reload());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(getString(R.string.app_start_url));
        }

        handleAuthReturnIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthReturnIntent(intent);
    }

    private void startCredentialManagerGoogleSignIn() {
        Toast.makeText(this, R.string.google_sign_in_started, Toast.LENGTH_SHORT).show();

        GetSignInWithGoogleOption signInOption = new GetSignInWithGoogleOption.Builder(
                getString(R.string.google_web_client_id)
        ).setNonce(generateSecureRandomNonce()).build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build();

        credentialManager.getCredentialAsync(
                this,
                request,
                null,
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredentialManagerResult(result);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof NoCredentialException) {
                            Toast.makeText(MainActivity.this, R.string.google_sign_in_no_account, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }

    private void handleCredentialManagerResult(@NonNull GetCredentialResponse result) {
        Credential credential = result.getCredential();

        if (!(credential instanceof CustomCredential)) {
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show();
            return;
        }

        CustomCredential customCredential = (CustomCredential) credential;
        String credentialType = customCredential.getType();

        if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credentialType)
                && !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(credentialType)) {
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(customCredential.getData());
            syncGoogleLoginToWebsite(googleCredential.getIdToken());
        } catch (IllegalArgumentException | NullPointerException e) {
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void syncGoogleLoginToWebsite(@NonNull String idToken) {
        progressBar.setVisibility(View.VISIBLE);

        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(getString(R.string.google_native_login_url));
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                connection.setRequestProperty("X-Requested-With", getPackageName());

                String payload = "id_token=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8.name());

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = connection.getResponseCode();
                List<String> cookies = extractSetCookieHeaders(connection);
                String responseBody = readResponseBody(connection);

                NativeLoginResult nativeResult = parseNativeLoginResponse(statusCode, responseBody);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);

                    if (nativeResult.ok) {
                        setCookiesFromHeaders(cookies);

                        if (!nativeResult.userId.isEmpty() && !nativeResult.userSecret.isEmpty()) {
                            applyLoginCookies(nativeResult.userId, nativeResult.userSecret);
                        }

                        Toast.makeText(MainActivity.this, R.string.google_sign_in_success, Toast.LENGTH_LONG).show();
                        webView.loadUrl(getString(R.string.app_post_login_url));
                    } else {
                        Toast.makeText(
                                MainActivity.this,
                                nativeResult.message.isEmpty()
                                        ? getString(R.string.google_sign_in_failed)
                                        : nativeResult.message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void handleAuthReturnIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        Uri data = intent.getData();
        if (data == null) {
            return;
        }

        if (!"sedirads".equalsIgnoreCase(data.getScheme())
                || !"auth-return".equalsIgnoreCase(data.getHost())) {
            return;
        }

        String status = valueOrEmpty(data.getQueryParameter("status"));
        String userId = valueOrEmpty(data.getQueryParameter("user_id"));
        String userSecret = valueOrEmpty(data.getQueryParameter("user_secret"));
        String message = valueOrEmpty(data.getQueryParameter("message"));

        if ("success".equalsIgnoreCase(status) && !userId.isEmpty() && !userSecret.isEmpty()) {
            applyLoginCookies(userId, userSecret);
            Toast.makeText(this, R.string.google_sign_in_success, Toast.LENGTH_LONG).show();

            if (webView != null) {
                webView.loadUrl(getString(R.string.app_post_login_url));
            }
        } else {
            Toast.makeText(
                    this,
                    message.isEmpty() ? getString(R.string.google_sign_in_failed) : message,
                    Toast.LENGTH_LONG
            ).show();

            if (webView != null) {
                webView.loadUrl(getString(R.string.app_start_url));
            }
        }

        intent.setData(null);
    }

    private void setCookiesFromHeaders(@NonNull List<String> cookies) {
        if (cookies.isEmpty()) {
            return;
        }

        CookieManager cookieManager = CookieManager.getInstance();
        String baseUrl = getString(R.string.app_start_url);
        String loginUrl = getString(R.string.google_native_login_url);

        for (String cookie : cookies) {
            cookieManager.setCookie(baseUrl, cookie);
            cookieManager.setCookie(loginUrl, cookie);
        }

        cookieManager.flush();
    }

    private void applyLoginCookies(@NonNull String userId, @NonNull String userSecret) {
        CookieManager cookieManager = CookieManager.getInstance();
        String baseUrl = getString(R.string.app_start_url);
        String host = Uri.parse(baseUrl).getHost();
        String domainPart = (host == null || host.isEmpty()) ? "" : "; Domain=" + host;

        cookieManager.setCookie(baseUrl, "oc_userId=" + userId + "; Path=/" + domainPart + "; Secure");
        cookieManager.setCookie(baseUrl, "oc_userSecret=" + userSecret + "; Path=/" + domainPart + "; Secure");
        cookieManager.flush();
    }

    @NonNull
    private NativeLoginResult parseNativeLoginResponse(int statusCode, @Nullable String responseBody) {
        NativeLoginResult result = new NativeLoginResult();
        result.ok = false;

        if (responseBody == null || responseBody.trim().isEmpty()) {
            if (statusCode < 200 || statusCode >= 400) {
                result.message = "HTTP " + statusCode;
            }
            return result;
        }

        try {
            JSONObject json = new JSONObject(responseBody);
            result.ok = json.optBoolean("ok", false);
            result.message = json.optString("message", "");
            result.userId = json.optString("user_id", "");
            result.userSecret = json.optString("user_secret", "");
        } catch (Exception ignored) {
            result.message = (statusCode >= 200 && statusCode < 400)
                    ? "Invalid server response"
                    : responseBody.trim();
        }

        return result;
    }

    @NonNull
    private List<String> extractSetCookieHeaders(@NonNull HttpURLConnection connection) {
        java.util.ArrayList<String> cookies = new java.util.ArrayList<>();

        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null
                    && "Set-Cookie".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null) {
                cookies.addAll(entry.getValue());
            }
        }

        return cookies;
    }

    @Nullable
    private String readResponseBody(@NonNull HttpURLConnection connection) {
        try {
            InputStream inputStream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            if (inputStream == null) {
                return "";
            }

            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append("\n");
                }
            }

            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private String generateSecureRandomNonce() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.encodeToString(randomBytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private void setupLaunchers() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleFileChooserResult
        );

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                    prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply();

                    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(this, R.string.notification_denied_message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SediradsAndroidApp/1.1 CredentialManager");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.addJavascriptInterface(new AndroidAuthBridge(), "AndroidAuth");
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppWebChromeClient());
        webView.setDownloadListener(new AppDownloadListener());
    }

    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }

        if (webView != null) {
            webView.destroy();
        }

        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }

        super.onDestroy();
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_NOTIFICATION_ASKED, false)) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply();
            return;
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void handleFileChooserResult(ActivityResult result) {
        if (filePathCallback == null) {
            return;
        }

        Uri[] results = null;

        if (result.getResultCode() == RESULT_OK) {
            Intent data = result.getData();
            if (data != null) {
                results = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), data);
            }
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private boolean openExternalIntent(@NonNull Uri uri) {
        Intent intent;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);

        if ("intent".equals(scheme)) {
            try {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } catch (Exception e) {
                return false;
            }
        } else {
            intent = new Intent(Intent.ACTION_VIEW, uri);
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE);

        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean shouldOpenInsideApp(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https")) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            return appHosts.contains(host) || host.endsWith(".sedirads.com");
        }

        return scheme.equals("about") || scheme.equals("data") || scheme.equals("blob");
    }

    private final class AndroidAuthBridge {
        @JavascriptInterface
        public void signInWithGoogle() {
            runOnUiThread(() -> startCredentialManagerGoogleSignIn());
        }

        @JavascriptInterface
        public boolean isInApp() {
            return true;
        }
    }

    @NonNull
    private String valueOrEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static final class NativeLoginResult {
        boolean ok;
        String message = "";
        String userId = "";
        String userSecret = "";
    }

    private final class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (shouldOpenInsideApp(uri)) {
                return false;
            }
            return openExternalIntent(uri);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if (shouldOpenInsideApp(uri)) {
                return false;
            }
            return openExternalIntent(uri);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            swipeRefreshLayout.setRefreshing(false);
            maybeAskNotificationPermission();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                swipeRefreshLayout.setRefreshing(false);
                Toast.makeText(MainActivity.this, R.string.connection_error, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class AppWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);

            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }

            MainActivity.this.filePathCallback = filePathCallback;

            Intent chooserIntent;
            try {
                chooserIntent = fileChooserParams.createIntent();
            } catch (ActivityNotFoundException e) {
                MainActivity.this.filePathCallback = null;
                Toast.makeText(MainActivity.this, R.string.no_file_picker, Toast.LENGTH_SHORT).show();
                return false;
            }

            fileChooserLauncher.launch(chooserIntent);
            return true;
        }
    }

    private final class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimetype,
                long contentLength
        ) {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("cookie", cookies);
            }

            request.addRequestHeader("User-Agent", userAgent);
            request.setDescription(getString(R.string.download_description));

            String guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            String extension = MimeTypeMap.getFileExtensionFromUrl(url);
            if (extension != null && !extension.isEmpty() && !guessedFileName.contains(".")) {
                guessedFileName = guessedFileName + "." + extension;
            }

            request.setTitle(guessedFileName);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    guessedFileName
            );

            DownloadManager downloadManager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

            if (downloadManager != null) {
                downloadManager.enqueue(request);
                Toast.makeText(MainActivity.this, R.string.download_started, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
