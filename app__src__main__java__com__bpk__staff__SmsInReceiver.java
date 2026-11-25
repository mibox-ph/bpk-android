package com.bpk.staff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

// Keeps every text this tablet receives until the staff page has passed it to the server (which sends the automatic reply).
public class SmsInReceiver extends BroadcastReceiver {
    private static final Object LOCK = new Object();

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;
            Map<String, StringBuilder> bySender = new LinkedHashMap<>();
            long when = System.currentTimeMillis();
            for (SmsMessage p : parts) {
                if (p == null || p.getOriginatingAddress() == null) continue;
                StringBuilder sb = bySender.get(p.getOriginatingAddress());
                if (sb == null) { sb = new StringBuilder(); bySender.put(p.getOriginatingAddress(), sb); }
                if (p.getMessageBody() != null) sb.append(p.getMessageBody());
                when = p.getTimestampMillis() > 0 ? p.getTimestampMillis() : when;
            }
            synchronized (LOCK) {
                SharedPreferences sp = ctx.getSharedPreferences("bpk_smsin", Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray(sp.getString("q", "[]"));
                for (Map.Entry<String, StringBuilder> e : bySender.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("from", e.getKey());
                    o.put("body", e.getValue().toString());
                    o.put("at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(when)));
                    o.put("uid", Integer.toHexString((e.getKey() + "|" + when + "|" + e.getValue()).hashCode()) + "-" + when);
                    arr.put(o);
                }
                while (arr.length() > 200) arr.remove(0);
                sp.edit().putString("q", arr.toString()).apply();
            }
        } catch (Exception ignored) { }
    }

    // The waiting texts, oldest first (at most 20 at a time).
    public static String peek(Context ctx) {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray(ctx.getSharedPreferences("bpk_smsin", Context.MODE_PRIVATE).getString("q", "[]"));
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length() && i < 20; i++) out.put(arr.get(i));
                return out.toString();
            } catch (Exception e) { return "[]"; }
        }
    }

    // Removes the first n texts once the server has them.
    public static void drop(Context ctx, int n) {
        synchronized (LOCK) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences("bpk_smsin", Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray(sp.getString("q", "[]"));
                JSONArray keep = new JSONArray();
                for (int i = n; i < arr.length(); i++) keep.put(arr.get(i));
                sp.edit().putString("q", keep.toString()).apply();
            } catch (Exception ignored) { }
        }
    }
}
