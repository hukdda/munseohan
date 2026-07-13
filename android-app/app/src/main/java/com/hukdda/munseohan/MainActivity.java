package com.hukdda.munseohan;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 3017;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(243, 244, 246));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(243, 244, 246));
        setContentView(webView);
        applySystemBarInsets(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(true);
        }

        webView.addJavascriptInterface(new NativeBridge(), "AndroidApp");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "사진이나 파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("file".equals(uri.getScheme())) return false;
                openExternal(uri);
                return true;
            }

        });

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    0,
                    insets.getSystemWindowInsetTop(),
                    0,
                    insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        fileExecutor.shutdown();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApp");
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class NativeBridge {
        @JavascriptInterface
        public void saveBase64(String dataUrl, String requestedName, String mimeType, boolean share) {
            fileExecutor.execute(() -> {
                try {
                    int comma = dataUrl.indexOf(',');
                    if (comma < 0) throw new IllegalArgumentException("파일 데이터 형식 오류");
                    byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                    String fileName = sanitizeFileName(requestedName);
                    String safeMime = (mimeType == null || mimeType.trim().isEmpty())
                            ? "application/octet-stream" : mimeType;

                    if (share) {
                        shareFile(bytes, fileName, safeMime);
                    } else {
                        saveToDownloads(bytes, fileName, safeMime);
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "파일 처리 중 문제가 생겼습니다.",
                            Toast.LENGTH_LONG
                    ).show());
                }
            });
        }
    }

    private void shareFile(byte[] bytes, String fileName, String mimeType) throws Exception {
        File directory = new File(getCacheDir(), "shared_documents");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("공유 폴더 생성 실패");
        }
        File file = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType(mimeType);
        sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        runOnUiThread(() -> startActivity(Intent.createChooser(sendIntent, "저장하거나 보낼 앱 선택")));
    }

    private void saveToDownloads(byte[] bytes, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/문서한장");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri item = resolver.insert(collection, values);
            if (item == null) throw new IllegalStateException("저장 위치 생성 실패");
            try (OutputStream output = resolver.openOutputStream(item)) {
                if (output == null) throw new IllegalStateException("파일 열기 실패");
                output.write(bytes);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, values, null, null);
        } else {
            File directory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "문서한장");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("저장 폴더 생성 실패");
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, fileName))) {
                output.write(bytes);
            }
        }
        runOnUiThread(() -> Toast.makeText(
                MainActivity.this,
                "다운로드/문서한장에 저장했습니다.",
                Toast.LENGTH_LONG
        ).show());
    }

    private static String sanitizeFileName(String name) {
        String result = name == null ? "문서한장" : name;
        result = result.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (result.isEmpty()) result = "문서한장";
        return result.length() > 120 ? result.substring(0, 120) : result;
    }
}
