package com.bpk.staff;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEFAULT_SERVER = "https://mybpk.com";
    private static final String ASSET_PREFIX = "/android_asset/";

    static volatile boolean inFront = false;
    private WebView web;
    private SharedPreferences prefs;
    private volatile String currentUrl = "";
    private PermissionRequest pendingMic;
    private static final int REQ_MIC = 77;
    private static final int REQ_SMS = 78;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        prefs = getSharedPreferences("bpk", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "BPKNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (!isTrusted(Uri.parse(req.getOrigin().toString()))) { req.deny(); return; }
                    java.util.ArrayList<String> need = new java.util.ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
                    }
                    if (need.isEmpty()) { req.grant(req.getResources()); return; }
                    pendingMic = req;
                    requestPermissions(need.toArray(new String[0]), REQ_MIC);
                });
            }
        });
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
        else if (prefs.contains("server")) web.loadUrl(serverBase().replaceAll("/+$", "") + "/staff/");
        else web.loadUrl("file:///android_asset/test.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() { super.onResume(); inFront = true; }

    @Override
    protected void onPause() { inFront = false; super.onPause(); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_SMS) {
            emit("sms_permission", results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED, "");
            return;
        }
        if (code == REQ_MIC) {
            boolean granted = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            if (pendingMic != null) {
                if (granted) pendingMic.grant(pendingMic.getResources()); else pendingMic.deny();
                pendingMic = null;
            }
            return;
        }
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

    private void askBt() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            }
        });
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
        try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }   // needs the extra "scan" permission on Android 12+; not needed to print
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

    // Sends one text message. The result comes back to the page as an "sms" event: message = id|error
    private void sendSmsNow(final String id, String phone, String body) {
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(body);
            final int total = parts.size();
            final String action = "com.bpk.staff.SMS_SENT_" + id + "_" + System.nanoTime();
            final int[] done = {0};
            final boolean[] bad = {false};
            final BroadcastReceiver rec = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (getResultCode() != Activity.RESULT_OK) bad[0] = true;
                    done[0]++;
                    if (done[0] >= total) {
                        try { unregisterReceiver(this); } catch (Exception ignored) { }
                        emit("sms", !bad[0], id + "|" + (bad[0] ? "the phone could not send it" : ""));
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rec, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(rec, new IntentFilter(action));
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                Intent in = new Intent(action);
                in.setPackage(getPackageName());
                sent.add(PendingIntent.getBroadcast(this, i, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            sm.sendMultipartTextMessage(phone, null, parts, sent, null);
        } catch (SecurityException e) {
            emit("sms", false, id + "|text permission missing");
        } catch (Exception e) {
            emit("sms", false, id + "|" + (e.getMessage() == null ? "error" : e.getMessage()));
        }
    }

    // ---- bridge exposed to the web pages -------------------------------

    private final class Bridge {
        @JavascriptInterface
        public void startAlerts(String token) {
            if (!trustedCaller() || token == null || token.length() < 20) return;
            prefs.edit().putString("alert_token", token).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 79);
                    }
                    try {
                        Intent i = new Intent(MainActivity.this, OrderAlertService.class);
                        startForegroundService(i);
                    } catch (Exception ignored) { }
                    try {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("asked_battery", false)) {
                            prefs.edit().putBoolean("asked_battery", true).apply();
                            Intent b = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                            startActivity(b);
                        }
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void testAlarm() {
            if (!trustedCaller()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() { try { Intent i = new Intent(MainActivity.this, OrderAlertService.class); i.setAction("TEST"); startForegroundService(i); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public void stopTestAlarm() {
            if (!trustedCaller()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() { try { Intent i = new Intent(MainActivity.this, OrderAlertService.class); i.setAction("STOPTEST"); startForegroundService(i); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public void stopAlerts() {
            if (!trustedCaller()) return;
            prefs.edit().remove("alert_token").apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() { try { stopService(new Intent(MainActivity.this, OrderAlertService.class)); } catch (Exception ignored) { } }
            });
        }

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
        public boolean smsReady() {
            return trustedCaller() && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void smsRequest() {
            if (!trustedCaller()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() { requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS); }
            });
        }

        @JavascriptInterface
        public void sendSms(final String id, final String phone, final String body) {
            if (!trustedCaller()) return;
            if (phone == null || !phone.matches("^\\+?[0-9]{10,13}$") || body == null || body.length() == 0 || body.length() > 600 || id == null || !id.matches("^[0-9]+$")) {
                emit("sms", false, id + "|invalid message");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() { sendSmsNow(id, phone, body); }
            }).start();
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
                        askBt(); emit("print", false, "Bluetooth permission is missing. Tap Allow on the Nearby devices question, then try again. If no question appears: Android Settings > Apps > BPK Staff > Permissions > Nearby devices > Allow.");
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        emit("print", false, "Could not print: " + m);
                    }
                }
            }).start();
        }

        // Prints a document made by the server. The result goes back to the page as a "printjob" event: message = id|error
        @JavascriptInterface
        public void printDoc(final String jobId, final String address, final int paperMm, final String json) {
            if (!trustedCaller()) return;
            if (jobId == null || !jobId.matches("^[0-9]+$")) return;
            if (address == null || !address.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) {
                emit("printjob", false, jobId + "|invalid printer address");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        EscPos p = new EscPos(paperMm >= 80 ? 80 : 57);
                        JSONArray ops = new JSONArray(json);
                        for (int i = 0; i < ops.length(); i++) {
                            JSONObject o = ops.getJSONObject(i);
                            String t = o.optString("t");
                            if (t.equals("c") || t.equals("l")) {
                                int size = o.optInt("z", 0);
                                int cols = (size == 0x11 || size == 0x10) ? p.cols / 2 : p.cols;
                                p.align(t.equals("c") ? 1 : 0).bold(o.optInt("b", 0) == 1).size(size);
                                for (String ln : EscPos.wrap(o.optString("s"), cols)) p.line(ln);
                                p.size(0).bold(false).align(0);
                            } else if (t.equals("lr")) {
                                p.align(0).bold(o.optInt("b", 0) == 1).lr(o.optString("l"), o.optString("r")).bold(false);
                            } else if (t.equals("rule")) {
                                String c = o.optString("c", "-");
                                p.align(0).rule(c.length() > 0 ? c.charAt(0) : '-');
                            } else if (t.equals("feed")) {
                                p.feed(Math.min(10, o.optInt("n", 1)));
                            } else if (t.equals("img")) {
                                try { p.rawBytes(android.util.Base64.decode(o.optString("d"), android.util.Base64.DEFAULT)); } catch (Exception ignored) { }
                            } else if (t.equals("cut")) {
                                p.cut();
                            }
                        }
                        sendToPrinter(address.toUpperCase(Locale.US), p.bytes());
                        emit("printjob", true, jobId + "|");
                    } catch (SecurityException e) {
                        askBt(); emit("printjob", false, jobId + "|Bluetooth permission is missing");
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        emit("printjob", false, jobId + "|Printer not reachable: " + m.replace('|', ' '));
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
