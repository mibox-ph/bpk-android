package com.bpk.staff;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEFAULT_SERVER = "https://mybpk.com";
    private static final String ASSET_PREFIX = "/android_asset/";

    private WebView web;
    private SharedPreferences prefs;
    private volatile String currentUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("bpk", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "BPKNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isTrusted(u)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                currentUrl = url == null ? "" : url;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                currentUrl = url == null ? "" : url;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/test.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        emit("permission", ok, ok ? "Bluetooth permission granted." : "Bluetooth permission was not granted.");
    }

    // ---- helpers --------------------------------------------------------

    private String serverBase() {
        return prefs.getString("server", DEFAULT_SERVER);
    }

    private boolean isTrusted(Uri u) {
        if (u == null) return false;
        if ("file".equals(u.getScheme())) {
            String p = u.getPath();
            return p != null && p.startsWith(ASSET_PREFIX);
        }
        if ("https".equals(u.getScheme())) {
            Uri base = Uri.parse(serverBase());
            return u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost());
        }
        return false;
    }

    private boolean trustedCaller() {
        return isTrusted(Uri.parse(currentUrl));
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void emit(final String type, boolean ok, String message) {
        try {
            final JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("ok", ok);
            o.put("message", message);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript("window.onNative && window.onNative(" + o.toString() + ")", null);
                }
            });
        } catch (Exception ignored) { }
    }

    private void sendToPrinter(String address, byte[] data) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new Exception("This device has no Bluetooth.");
        if (!ad.isEnabled()) throw new Exception("Bluetooth is turned off.");
        BluetoothDevice dev = ad.getRemoteDevice(address);
        ad.cancelDiscovery();
        BluetoothSocket sock = null;
        try {
            sock = dev.createRfcommSocketToServiceRecord(SPP);
            try {
                sock.connect();
            } catch (IOException first) {
                try { sock.close(); } catch (IOException ignored) { }
                sock = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                sock.connect();
            }
            OutputStream os = sock.getOutputStream();
            os.write(data);
            os.flush();
            Thread.sleep(500 + data.length / 10);
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---- bridge exposed to the web pages -------------------------------

    private final class Bridge {
        @JavascriptInterface
        public String getServer() {
            return trustedCaller() ? serverBase() : "";
        }

        @JavascriptInterface
        public String setServer(String url) {
            if (!trustedCaller()) return "Not allowed.";
            String u = url == null ? "" : url.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.matches("^https://[A-Za-z0-9.-]+(:[0-9]+)?$")) return "Use an address like https://mybpk.com";
            prefs.edit().putString("server", u).apply();
            return "ok";
        }

        @JavascriptInterface
        public boolean needsPermission() {
            return trustedCaller() && !hasBtPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            if (!trustedCaller()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 31) {
                        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
                    }
                }
            });
        }

        @JavascriptInterface
        public String listPrinters() {
            try {
                if (!trustedCaller()) return "{\"ok\":false,\"error\":\"Not allowed.\"}";
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) return "{\"ok\":false,\"error\":\"This device has no Bluetooth.\"}";
                if (!ad.isEnabled()) return "{\"ok\":false,\"error\":\"Bluetooth is turned off.\"}";
                if (!hasBtPermission()) return "{\"ok\":false,\"error\":\"permission\"}";
                JSONArray arr = new JSONArray();
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                for (BluetoothDevice d : bonded) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? "(no name)" : d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("printers", arr);
                return res.toString();
            } catch (SecurityException e) {
                return "{\"ok\":false,\"error\":\"permission\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"Could not read the paired devices.\"}";
            }
        }

        @JavascriptInterface
        public void testPrint(final String address, final int paperMm, final String title) {
            if (!trustedCaller()) return;
            if (address == null || !address.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) {
                emit("print", false, "Invalid printer address.");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int mm = paperMm >= 80 ? 80 : 57;
                        EscPos p = new EscPos(mm);
                        p.align(1).bold(true).size(0x01).line(title == null || title.trim().isEmpty() ? "BPK" : title)
                                .size(0).bold(false);
                        p.line("PRINTER TEST");
                        p.line(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
                        p.align(0).rule('=');
                        StringBuilder ruler = new StringBuilder();
                        for (int i = 1; i <= p.cols; i++) ruler.append(i % 10);
                        p.line(ruler.toString());
                        p.line("Paper " + mm + " mm, " + p.cols + " characters per line");
                        p.rule('-');
                        p.lr("1 Pork Igado (Medium)", "PHP 670.00");
                        p.lr("2 Chop Suey (Small)", "PHP 490.00");
                        p.rule('-');
                        p.bold(true).lr("TOTAL", "PHP 1,160.00").bold(false);
                        p.align(1).feed(1).line("NO SERVICE CHARGE").line("Thank you!").feed(4).cut();
                        sendToPrinter(address.toUpperCase(Locale.US), p.bytes());
                        emit("print", true, "Test slip sent. Check the printer.");
                    } catch (SecurityException e) {
                        emit("print", false, "Bluetooth permission is missing.");
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        emit("print", false, "Could not print: " + m);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void checkServer(final String url) {
            if (!trustedCaller()) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        String base = url == null || url.trim().isEmpty() ? serverBase() : url.trim();
                        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                        if (!base.startsWith("https://")) {
                            emit("server", false, "The address must start with https://");
                            return;
                        }
                        c = (HttpURLConnection) new URL(base + "/api/ping").openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        int code = c.getResponseCode();
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        StringBuilder sb = new StringBuilder();
                        if (in != null) {
                            byte[] buf = new byte[512];
                            int n;
                            while ((n = in.read(buf)) > 0 && sb.length() < 2000) sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                        if (code == 200 && sb.toString().contains("\"ok\":true")) emit("server", true, "Connected to the BPK server.");
                        else emit("server", false, "The server answered with code " + code + ". Is the setup finished?");
                    } catch (Exception e) {
                        emit("server", false, "Could not reach the server. Check the internet and the address.");
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }).start();
        }
    }
}
