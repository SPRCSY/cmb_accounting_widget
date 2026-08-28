package com.csy.cmbspend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 当月消费累计的持久化：SharedPreferences 存「月份 + 累计分」。
 *  每次读写先校验月份，跨月自动清零（每月 1 号重置）。
 *  金额一律以「分」为单位存储，避免浮点误差。
 *  另存「当月每笔明细」，供用户对账、发现监听器掉线漏记的笔。 */
public final class SpendStore {

    private static final String PREFS = "spend_widget";
    private static final String KEY_MONTH = "month_key";          // yyyy-MM
    private static final String KEY_TOTAL_CENTS = "month_total_cents";
    private static final String KEY_DUP_MONTH = "dup_month";      // 去重集合所属月份 yyyy-MM
    private static final String KEY_PROCESSED = "processed_keys"; // 当月已处理通知 id 集合
    private static final String KEY_ITEMS = "month_items";        // 当月明细 JSON 数组
    private static final int MAX_PROCESSED = 2000;
    private static final int MAX_ITEMS = 500;

    private SpendStore() {}

    /** 当前月份 key，如 2026-08 */
    public static String currentMonthKey() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    /** 读取本月累计消费（分）。存储月份不是本月时自动清零并返回 0。 */
    public static long getCurrentMonthCents(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String now = currentMonthKey();
        if (!now.equals(p.getString(KEY_MONTH, ""))) {
            p.edit().putString(KEY_MONTH, now).putLong(KEY_TOTAL_CENTS, 0L).apply();
            return 0L;
        }
        return p.getLong(KEY_TOTAL_CENTS, 0L);
    }

    /** 累加一笔金额（分，可为负=退款），返回累加后的本月总额（钳制到 ≥0，避免退款超消费变负数）。 */
    public static long addCents(Context ctx, long cents) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String now = currentMonthKey();
        String storedMonth = p.getString(KEY_MONTH, "");
        long total;
        if (!now.equals(storedMonth)) {
            total = Math.max(0L, cents);
        } else {
            total = Math.max(0L, p.getLong(KEY_TOTAL_CENTS, 0L) + cents);
        }
        p.edit().putString(KEY_MONTH, now).putLong(KEY_TOTAL_CENTS, total).apply();
        return total;
    }

    /** 记录一笔明细（毫秒时间戳、金额分、商户名）。跨月自动换新数组。 */
    public static void addItem(Context ctx, long timestampMs, long cents, String merchant) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String now = currentMonthKey();
        String dupMonth = p.getString(KEY_DUP_MONTH, "");
        JSONArray arr;
        if (now.equals(dupMonth)) {
            arr = readItems(p);
        } else {
            arr = new JSONArray();
        }
        try {
            JSONObject o = new JSONObject();
            o.put("t", timestampMs);
            o.put("c", cents);
            o.put("m", merchant == null ? "" : merchant);
            arr.put(o);
            while (arr.length() > MAX_ITEMS) arr.remove(0);
            p.edit().putString(KEY_DUP_MONTH, now).putString(KEY_ITEMS, arr.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** 修改后的当月明细：返回「时间戳、金额分、商户」列表，时间从新到旧。 */
    public static List<long[]> getItems(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readItems(p);
        List<long[]> out = new ArrayList<>();
        for (int i = arr.length() - 1; i >= 0; i--) {
            try {
                JSONObject o = arr.getJSONObject(i);
                out.add(new long[]{ o.optLong("t", 0), o.optLong("c", 0) });
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** 提取第 i 条明细的商户名（getItems 返回不含商户，单独取用） */
    public static String getMerchantAt(Context ctx, int indexFromNewest) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readItems(p);
        int pos = arr.length() - 1 - indexFromNewest;
        if (pos < 0 || pos >= arr.length()) return "";
        try {
            return arr.getJSONObject(pos).optString("m", "");
        } catch (JSONException e) {
            return "";
        }
    }

    private static JSONArray readItems(SharedPreferences p) {
        String s = p.getString(KEY_ITEMS, "");
        if (s.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** 持久化去重：已处理过的通知 id 按当月记录，跨月自动清空。
     *  返回 true 表示这条通知本月已处理过，应跳过（防止通知栏过期后重连扫描重复累加）。 */
    public static boolean isDuplicate(Context ctx, String key) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String now = currentMonthKey();
        String dupMonth = p.getString(KEY_DUP_MONTH, "");
        java.util.LinkedHashSet<String> processed;
        if (now.equals(dupMonth)) {
            processed = new java.util.LinkedHashSet<>(
                    p.getStringSet(KEY_PROCESSED, new java.util.HashSet<>()));
        } else {
            // 跨月：清空去重集合（与月累计同步归零）
            processed = new java.util.LinkedHashSet<>();
        }
        if (processed.contains(key)) return true;
        processed.add(key);
        // 限制容量，丢弃最旧的
        while (processed.size() > MAX_PROCESSED) {
            java.util.Iterator<String> it = processed.iterator();
            it.next();
            it.remove();
        }
        p.edit().putString(KEY_DUP_MONTH, now)
                .putStringSet(KEY_PROCESSED, processed)
                .apply();
        return false;
    }

    /** 调试用：清空当月累计与明细（等价于手动清零）。 */
    public static void resetForDebug(Context ctx) {
        String now = currentMonthKey();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MONTH, now).putLong(KEY_TOTAL_CENTS, 0L)
                .putString(KEY_DUP_MONTH, now).putString(KEY_ITEMS, "")
                .putStringSet(KEY_PROCESSED, new java.util.HashSet<>())
                .apply();
    }
}
