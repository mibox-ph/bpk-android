package com.bpk.staff;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds plain ESC/POS bytes that generic Bluetooth thermal printers understand. */
final class EscPos {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final int cols;

    EscPos(int paperMm) {
        cols = paperMm >= 80 ? 48 : 32;
        raw(0x1B, 0x40); // initialize
    }

    EscPos raw(int... bytes) {
        for (int b : bytes) out.write(b);
        return this;
    }

    EscPos align(int a) { return raw(0x1B, 0x61, a); }          // 0 left, 1 center, 2 right
    EscPos bold(boolean on) { return raw(0x1B, 0x45, on ? 1 : 0); }
    EscPos size(int n) { return raw(0x1D, 0x21, n); }           // 0 normal, 0x01 double height, 0x11 double both

    EscPos text(String s) {
        byte[] b = ascii(s).getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
        return this;
    }

    EscPos line(String s) { return text(s).raw(0x0A); }

    EscPos feed(int n) {
        for (int i = 0; i < n; i++) raw(0x0A);
        return this;
    }

    EscPos cut() { return raw(0x1D, 0x56, 0x42, 0x00); }        // partial cut (ignored by printers without a cutter)

    EscPos rule(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols; i++) sb.append(c);
        return line(sb.toString());
    }

    /** Left text and right text on one line, padded to the paper width. */
    EscPos lr(String left, String right) {
        left = ascii(left);
        right = ascii(right);
        int room = cols - right.length() - 1;
        if (room < 1) room = 1;
        if (left.length() > room) left = left.substring(0, room);
        StringBuilder sb = new StringBuilder(left);
        while (sb.length() < cols - right.length()) sb.append(' ');
        sb.append(right);
        return line(sb.toString());
    }

    /** Splits text into lines that fit the paper width. */
    static java.util.List<String> wrap(String s, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        s = ascii(s);
        for (String para : s.split("\n", -1)) {
            while (para.length() > width) {
                int cut = para.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                lines.add(para.substring(0, cut));
                para = para.substring(cut).trim();
            }
            lines.add(para);
        }
        return lines;
    }

    byte[] bytes() { return out.toByteArray(); }

    /** Printers print basic ASCII reliably, so other characters are replaced. */
    static String ascii(String s) {
        if (s == null) return "";
        s = s.replace("₱", "PHP ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : (c == '\n' ? '\n' : '?'));
        }
        return sb.toString();
    }
}
