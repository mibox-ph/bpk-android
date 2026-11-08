package com.bpk.staff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/** Runs while the cashier is logged in. Checks the server every few seconds, even with the screen off,
 *  and when a new online order arrives it wakes the tablet, opens the app and rings until someone opens it. */
public class OrderAlertService extends Service {
    private static final String CH_ONGOING = "bpk_ongoing";
    private static final String CH_ORDERS = "bpk_orders_v1";
    private static final long EVERY_MS = 4000;
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock cpu;
    private final Set<Integer> known = new HashSet<>();
    private boolean first = true;
    private Ringtone ring;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean ringing = false;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        makeChannels();
        Notification n = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("BPK: waiting for online orders")
                .setContentText("The tablet will wake up when an order comes in.")
                .setContentIntent(openApp())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(11, n);
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bpkstaff:poll"); cpu.acquire(); }
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try { poll(); } catch (Exception ignored) { }
                        try { Thread.sleep(EVERY_MS); } catch (InterruptedException e) { break; }
                    }
                }
            });
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        stopRing();
        if (cpu != null && cpu.isHeld()) cpu.release();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(12);
        super.onDestroy();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void makeChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_ONGOING, "Waiting for orders", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel oc = new NotificationChannel(CH_ORDERS, "New online order", NotificationManager.IMPORTANCE_HIGH);
        oc.setSound(null, null);   // the service rings by itself so it can repeat
        oc.enableVibration(true);
        oc.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
        oc.setBypassDnd(true);
        oc.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(oc);
    }

    private void newOrder(int count, String title, String text) {
        // 1) turn the screen on
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "bpkstaff:wake");
                w.acquire(15000);
            }
        } catch (Exception ignored) { }
        // 2) notification that opens the app over the lock screen
        PendingIntent open = openApp();
        Notification n = new Notification.Builder(this, CH_ORDERS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title != null ? title : "NEW ONLINE ORDER")
                .setContentText(text != null ? text : count > 1 ? count + " online orders are waiting. Tap to open." : "An online order is waiting. Tap to open.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(12, n);
        // 3) try to open the app straight away (works when the tablet allows "display over other apps")
        try { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP); startActivity(i); } catch (Exception ignored) { }
        startRing();
    }

    private void startRing() {
        if (ringing) return;
        ringing = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    ring = RingtoneManager.getRingtone(OrderAlertService.this, u);
                    if (ring == null) return;
                    ring.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                    if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
                    ring.play();
                } catch (Exception ignored) { }
            }
        });
        // never ring longer than 2 minutes
        ui.postDelayed(new Runnable() { @Override public void run() { stopRing(); } }, 120000);
    }

    private void stopRing() {
        ringing = false;
        ui.post(new Runnable() {
            @Override
            public void run() { try { if (ring != null) { ring.stop(); ring = null; } } catch (Exception ignored) { } }
        });
    }

    private void poll() throws Exception {
        SharedPreferences prefs = getSharedPreferences("bpk", MODE_PRIVATE);
        String token = prefs.getString("alert_token", "");
        if (token.isEmpty()) { stopSelf(); return; }
        String base = prefs.getString("server", "https://mybpk.com");
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/online/ping").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code == 401 || code == 403) { prefs.edit().remove("alert_token").apply(); stopSelf(); return; }
        if (code != 200) return;
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        JSONObject jo = new JSONObject(sb.toString());
        JSONArray arr = jo.getJSONArray("ids");
        String title = jo.optString("title", null);
        String text = jo.optString("text", null);
        Set<Integer> now = new HashSet<>();
        boolean fresh = false;
        for (int i = 0; i < arr.length(); i++) { int id = arr.getInt(i); now.add(id); if (!known.contains(id)) fresh = true; }
        if (fresh && !first && !MainActivity.inFront) newOrder(now.size(), title, text);
        if (now.isEmpty() || MainActivity.inFront) { stopRing(); if (now.isEmpty()) ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(12); }
        known.clear();
        known.addAll(now);
        first = false;
    }
}
