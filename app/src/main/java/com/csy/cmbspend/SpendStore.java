package com.csy.cmbspend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 动账台账的持久化。
 *
 *  设计原则（吸取教训）：**台账只追加，永不整体重写；「本月」由每笔自带的时间戳推导，
 *  不依赖任何「当前月份键」**。这样就不存在「月份键写错 → 整段明细被覆盖」的可能——
 *  2026-09-11 曾因明细数组按月份键换月，误判月份导致整月明细被覆盖，此设计从根上杜绝。
 *
 *  金额一律以「分」(Long) 存储，避免浮点误差。
 *  明细 kind：s=支出、r=退款、i=收入（收入默认不影响消费，进待决台账由用户定性）。
 *  收入台账与明细分开存，决策（性质 + 抵扣额）记在收入记录上。
 *
 *  双写：SharedPreferences（主）+ filesDir 下的 JSON 备份（防主存被清/损坏后无法找回）。
 *  读时若主存为空而备份非空，自动从备份恢复。 */
public final class SpendStore {

    private static final String PREFS = "spend_widget";
    private static final String KEY_LEDGER = "ledger";             // 全部明细，只追加
    private static final String KEY_LEDGER_INCOME = "ledger_incomes"; // 全部收入，只追加
    private static final String KEY_DUP_MONTH = "dup_month";       // 去重集合所属月份 yyyy-MM
    private static final String KEY_PROCESSED = "processed_keys";  // 当月已处理通知 id 集合
    // 已废弃（2026-09-14）：曾用「调整值」增量累加通知金额，与明细重复计数。
    // 现在净额完全由台账推导，这两个键仅供一次性清理，不再读写。
    private static final String KEY_ADJUST_MONTH = "month_key";
    private static final String KEY_ADJUST_CENTS = "month_adjust_cents";

    // 旧版本的键，仅用于一次性迁移，迁移后不再写入
    private static final String KEY_OLD_ITEMS = "month_items";
    private static final String KEY_OLD_INCOMES = "month_incomes";

    private static final int MAX_LEDGER = 5000;
    private static final int MAX_PROCESSED = 2000;
    private static final String BACKUP_FILE = "ledger_backup.json";

    /** 明细类型 */
    public static final String KIND_SPEND = "s";
    public static final String KIND_REFUND = "r";
    public static final String KIND_INCOME = "i";

    /** 收入性质 */
    public static final String NATURE_UNDECIDED = "";
    public static final String NATURE_REFUND = "refund";
    public static final String NATURE_AA = "aa";
    public static final String NATURE_IGNORE = "ignore";

    private SpendStore() {}

    /** 当前月份 key，如 2026-08 */
    public static String currentMonthKey() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    private static String monthKeyOf(long timestampMs) {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(timestampMs));
    }

    // ------------------------------------------------------------------
    // 明细账（只追加）
    // ------------------------------------------------------------------

    /** 记录一笔明细。kind 见 KIND_*；cents 一律传绝对值（正数）。
     *  兼容旧调用：addItem(ctx, t, signedCents, merchant) 会按正负推断类型。 */
    public static void addItem(Context ctx, long timestampMs, long cents, String merchant) {
        addItem(ctx, timestampMs, cents, merchant, "", "");
    }

    public static void addItem(Context ctx, long timestampMs, long cents, String merchant,
                               String kind, String detail) {
        String k = kind;
        if (k == null || k.isEmpty()) {
            // 旧调用：按符号推断（正=支出、负=退款）
            k = cents < 0 ? KIND_REFUND : KIND_SPEND;
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readLedger(ctx);   // 已含迁移
        try {
            JSONObject o = new JSONObject();
            o.put("t", timestampMs);
            o.put("c", Math.abs(cents));
            o.put("k", k);
            o.put("m", merchant == null ? "" : merchant);
            o.put("d", detail == null ? "" : detail);
            arr.put(o);
            while (arr.length() > MAX_LEDGER) arr.remove(0);
            p.edit().putString(KEY_LEDGER, arr.toString()).apply();
            backup(ctx, arr, readIncomeLedger(ctx));  // 双写备份
        } catch (JSONException ignored) {
        }
    }

    /** 一条明细。 */
    public static final class Item {
        public final long timeMs;
        public final long cents;   // 绝对值（分）
        public final String kind;
        public final String merchant;
        public final String detail;
        Item(long timeMs, long cents, String kind, String merchant, String detail) {
            this.timeMs = timeMs; this.cents = cents; this.kind = kind;
            this.merchant = merchant; this.detail = detail;
        }
        public boolean isSpend()  { return KIND_SPEND.equals(kind); }
        public boolean isRefund() { return KIND_REFUND.equals(kind); }
        public boolean isIncome() { return KIND_INCOME.equals(kind); }
    }

    /** 全部明细（不限月份），时间从新到旧。 */
    public static List<Item> getAllItems(Context ctx) {
        JSONArray arr = readLedger(ctx);
        List<Item> out = new ArrayList<>();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(parseItem(o));
        }
        return out;
    }

    /** 本月明细（在读取时按每笔时间戳过滤，无月份状态可被写坏）。 */
    public static List<Item> getItems(Context ctx) {
        String now = currentMonthKey();
        List<Item> out = new ArrayList<>();
        for (Item it : getAllItems(ctx)) {
            if (now.equals(monthKeyOf(it.timeMs))) out.add(it);
        }
        return out;
    }

    private static Item parseItem(JSONObject o) {
        long c = o.optLong("c", 0);
        String k = o.optString("k", "");
        if (k.isEmpty()) k = c < 0 ? KIND_REFUND : KIND_SPEND;
        return new Item(o.optLong("t", 0), Math.abs(c), k,
                o.optString("m", ""), o.optString("d", ""));
    }

    /** 读取明细账；首次读取时把旧版本的 month_items 迁移进来（只做一次，之后清掉旧键）。 */
    private static JSONArray readLedger(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY_LEDGER, "");
        JSONArray arr;
        if (s.isEmpty()) {
            // 从旧的 month_items 迁移
            String old = p.getString(KEY_OLD_ITEMS, "");
            arr = parseArray(old);
            if (arr.length() == 0) {
                // 主存为空：尝试从文件备份恢复
                String bak = readBackup(ctx);
                if (!bak.isEmpty()) {
                    JSONObject root = tryParseObject(bak);
                    if (root != null) arr = root.optJSONArray("items") == null
                            ? new JSONArray() : root.optJSONArray("items");
                }
            }
            if (arr.length() > 0) {
                p.edit().putString(KEY_LEDGER, arr.toString()).remove(KEY_OLD_ITEMS).apply();
            }
        } else {
            arr = parseArray(s);
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // 收入台账（只追加）
    // ------------------------------------------------------------------

    /** 一笔收入。 */
    public static final class Income {
        public final String id;
        public final long timeMs;
        public final long cents;
        public final String merchant;
        public final String raw;
        public final String nature;
        public final long deductCents;
        Income(String id, long timeMs, long cents, String merchant, String raw,
               String nature, long deductCents) {
            this.id = id; this.timeMs = timeMs; this.cents = cents;
            this.merchant = merchant; this.raw = raw;
            this.nature = nature == null ? NATURE_UNDECIDED : nature;
            this.deductCents = deductCents;
        }
        public boolean isUndecided() { return nature.isEmpty(); }
        public String natureLabel() {
            if (NATURE_REFUND.equals(nature)) return "退款";
            if (NATURE_AA.equals(nature)) return "他人AA";
            if (NATURE_IGNORE.equals(nature)) return "忽略";
            return "待决策";
        }
    }

    /** 新增一笔收入到台账（只追加，同 id 不重复登记）。 */
    public static void addIncome(Context ctx, String id, long timestampMs, long cents,
                                String merchant, String raw) {
        if (cents <= 0) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readIncomeLedger(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id", ""))) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("t", timestampMs);
            o.put("c", Math.abs(cents));
            o.put("m", merchant == null ? "" : merchant);
            o.put("r", raw == null ? "" : raw);
            o.put("n", NATURE_UNDECIDED);
            o.put("x", 0L);
            arr.put(o);
            while (arr.length() > MAX_LEDGER) arr.remove(0);
            p.edit().putString(KEY_LEDGER_INCOME, arr.toString()).apply();
            backup(ctx, readLedger(ctx), arr);
        } catch (JSONException ignored) {
        }
    }

    /** 全部收入（不限月份），时间从新到旧。 */
    public static List<Income> getAllIncomes(Context ctx) {
        JSONArray arr = readIncomeLedger(ctx);
        List<Income> out = new ArrayList<>();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Income(o.optString("id", ""), o.optLong("t", 0), o.optLong("c", 0),
                    o.optString("m", ""), o.optString("r", ""),
                    o.optString("n", ""), o.optLong("x", 0)));
        }
        return out;
    }

    /** 本月收入台账，时间从新到旧。 */
    public static List<Income> getIncomes(Context ctx) {
        String now = currentMonthKey();
        List<Income> out = new ArrayList<>();
        for (Income i : getAllIncomes(ctx)) {
            if (now.equals(monthKeyOf(i.timeMs))) out.add(i);
        }
        return out;
    }

    /** 待决策（性质未定）的收入笔数（当月）。 */
    public static int getPendingIncomeCount(Context ctx) {
        int n = 0;
        for (Income i : getIncomes(ctx)) if (i.isUndecided()) n++;
        return n;
    }

    /** 设置某笔收入的性质与抵扣额（分）。总额由台账推导，改主意只需改台账。 */
    public static void setIncomeDecision(Context ctx, String id, String nature, long deductCents) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readIncomeLedger(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id", ""))) continue;
            long next = NATURE_IGNORE.equals(nature) ? 0L : Math.max(0L, deductCents);
            try {
                o.put("n", nature == null ? NATURE_UNDECIDED : nature);
                o.put("x", next);
            } catch (JSONException ignored) {
            }
            p.edit().putString(KEY_LEDGER_INCOME, arr.toString()).apply();
            backup(ctx, readLedger(ctx), arr);
            return;
        }
    }

    /** 读取收入台账；首次读取时迁移旧版本 month_incomes。 */
    private static JSONArray readIncomeLedger(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY_LEDGER_INCOME, "");
        if (!s.isEmpty()) return parseArray(s);
        String old = p.getString(KEY_OLD_INCOMES, "");
        JSONArray arr = parseArray(old);
        if (arr.length() == 0) {
            String bak = readBackup(ctx);
            if (!bak.isEmpty()) {
                JSONObject root = tryParseObject(bak);
                if (root != null && root.optJSONArray("incomes") != null) {
                    arr = root.optJSONArray("incomes");
                }
            }
        }
        if (arr.length() > 0) {
            p.edit().putString(KEY_LEDGER_INCOME, arr.toString()).remove(KEY_OLD_INCOMES).apply();
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------

    /** 本月账目汇总。顶部大数与表格页脚都从这里取，保证两者永远一致。
     *  净额 = Σ支出 − Σ退款 − Σ收入抵扣；**没有独立的「调整值」可漂移**。
     *  （2026-09-14 移除 adjustCents：监听器曾对每笔通知额外累加一份，
     *   而明细本身已含该笔，导致同一笔被算两次、顶部大数比明细多出一截。） */
    public static final class Summary {
        public long spendCents;
        public long refundCents;
        public long deductCents;
        public long netCents() { return spendCents - refundCents - deductCents; }
    }

    public static Summary getSummary(Context ctx) {
        Summary s = new Summary();
        for (Item it : getItems(ctx)) {
            if (it.isSpend()) s.spendCents += it.cents;
            else if (it.isRefund()) s.refundCents += it.cents;
        }
        for (Income inc : getIncomes(ctx)) s.deductCents += inc.deductCents;
        return s;
    }

    /** 读取本月累计消费（分）。总额完全由台账推导，无计数器可漂移。 */
    public static long getCurrentMonthCents(Context ctx) {
        long v = getSummary(ctx).netCents();
        return v < 0 ? 0 : v;
    }

    // ------------------------------------------------------------------
    // 去重
    // ------------------------------------------------------------------

    /** 持久化去重：已处理过的通知 id 按当月记录，跨月自动清空。
     *  返回 true 表示已处理过，应跳过。 */
    public static boolean isDuplicate(Context ctx, String key) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String now = currentMonthKey();
        String dupMonth = p.getString(KEY_DUP_MONTH, "");
        java.util.LinkedHashSet<String> processed;
        if (now.equals(dupMonth)) {
            processed = new java.util.LinkedHashSet<>(
                    p.getStringSet(KEY_PROCESSED, new HashSet<>()));
        } else {
            processed = new java.util.LinkedHashSet<>();
        }
        if (processed.contains(key)) return true;
        processed.add(key);
        while (processed.size() > MAX_PROCESSED) {
            java.util.Iterator<String> it = processed.iterator();
            it.next();
            it.remove();
        }
        p.edit().putString(KEY_DUP_MONTH, now).putStringSet(KEY_PROCESSED, processed).apply();
        return false;
    }

    /** 调试用：清空全部台账（明细 + 收入 + 去重 + 调整值）。 */
    public static void resetForDebug(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_LEDGER, "[]").putString(KEY_LEDGER_INCOME, "[]")
                .putString(KEY_DUP_MONTH, currentMonthKey())
                .putStringSet(KEY_PROCESSED, new HashSet<>())
                .remove(KEY_OLD_ITEMS).remove(KEY_OLD_INCOMES)
                // 旧版的「调整值」键：已废弃，顺手清掉，避免残留数据再被误读
                .remove(KEY_ADJUST_MONTH).remove(KEY_ADJUST_CENTS)
                .apply();
        backup(ctx, new JSONArray(), new JSONArray());
    }

    // ------------------------------------------------------------------
    // 备份（防主存丢失后无法找回）
    // ------------------------------------------------------------------

    private static File backupFile(Context ctx) {
        return new File(ctx.getFilesDir(), BACKUP_FILE);
    }

    private static void backup(Context ctx, JSONArray items, JSONArray incomes) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("saved", System.currentTimeMillis());
            root.put("items", items == null ? new JSONArray() : items);
            root.put("incomes", incomes == null ? new JSONArray() : incomes);
            String json = root.toString();
            // 内部备份（filesDir）
            try (FileOutputStream fos = new FileOutputStream(backupFile(ctx))) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            // 公共目录备份：卸载不删，用于重装后自动恢复
            PublicBackup.write(ctx, json);
        } catch (Exception ignored) {
            // 备份失败不能影响主流程
        }
    }

    /** 读备份：优先读公共目录（重装后仍存在），再退回内部备份。 */
    private static String readBackup(Context ctx) {
        String pub = PublicBackup.read(ctx);
        if (pub != null && !pub.isEmpty()) return pub;
        try {
            File f = backupFile(ctx);
            if (!f.exists()) return "";
            byte[] b = Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONArray parseArray(String s) {
        if (s == null || s.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static JSONObject tryParseObject(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 导出（保证数据永远拿得出来，不被困在 App 里）
    // ------------------------------------------------------------------

    /** 导出全部台账为 CSV。
     *  主写公共目录 Download/当月消费/（卸载不删，用户可直接查看/取走），
     *  同时写一份到 App 外部目录便于 adb pull。返回可读的路径说明；失败返回 null。 */
    public static String exportCsv(Context ctx) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("时间,类型,金额元,商户,详情\n");
            for (Item it : getAllItems(ctx)) {
                String type = it.isSpend() ? "支出" : it.isRefund() ? "退款" : "收入";
                sb.append(csv(fmt.format(new Date(it.timeMs)))).append(',')
                        .append(csv(type)).append(',')
                        .append(it.isSpend() ? it.cents / 100.0
                                : it.isRefund() ? -it.cents / 100.0 : it.cents / 100.0).append(',')
                        .append(csv(it.merchant)).append(',')
                        .append(csv(it.detail)).append('\n');
            }
            sb.append('\n').append("收入待决台账\n");
            sb.append("时间,金额元,商户,性质,已抵扣元,原文\n");
            for (Income inc : getAllIncomes(ctx)) {
                sb.append(csv(fmt.format(new Date(inc.timeMs)))).append(',')
                        .append(inc.cents / 100.0).append(',')
                        .append(csv(inc.merchant)).append(',')
                        .append(csv(inc.natureLabel())).append(',')
                        .append(inc.deductCents / 100.0).append(',')
                        .append(csv(inc.raw)).append('\n');
            }
            String content = sb.toString();

            boolean pub = PublicBackup.writeExport(ctx, content);
            File f = null;
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    f = new File(dir, "消费明细导出.csv");
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
            if (pub) return "Download/当月消费/消费明细导出.csv（卸载也不丢）";
            return f == null ? null : f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\n", " ").replace("\r", " ");
        if (v.contains(",") || v.contains("\"")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** 自建目录备份路径（供 UI 提示）。 */
    public static String publicBackupPath() {
        return PublicBackup.dirPath() + "/台账备份.json";
    }

    /** 从备份 JSON 恢复台账（合并式：按内容去重追加，不覆盖现有数据）。
     *  返回新增明细条数；失败返回 -1。用于重装后手动导入公共目录中幸存的备份。 */
    public static int restoreFromJson(Context ctx, String json) {
        if (json == null || json.isEmpty()) return -1;
        JSONObject root = tryParseObject(json);
        if (root == null) return -1;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray curItems = readLedger(ctx);
            JSONArray curInc = readIncomeLedger(ctx);
            int added = 0;

            JSONArray inItems = root.optJSONArray("items");
            if (inItems != null) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < curItems.length(); i++) {
                    JSONObject o = curItems.optJSONObject(i);
                    if (o != null) seen.add(itemSig(o));
                }
                for (int i = 0; i < inItems.length(); i++) {
                    JSONObject o = inItems.optJSONObject(i);
                    if (o == null) continue;
                    if (seen.add(itemSig(o))) {
                        curItems.put(o);
                        added++;
                    }
                }
                p.edit().putString(KEY_LEDGER, curItems.toString()).apply();
            }

            JSONArray inInc = root.optJSONArray("incomes");
            if (inInc != null) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < curInc.length(); i++) {
                    JSONObject o = curInc.optJSONObject(i);
                    if (o != null) seen.add(o.optString("id", "") + "|" + o.optLong("t", 0));
                }
                for (int i = 0; i < inInc.length(); i++) {
                    JSONObject o = inInc.optJSONObject(i);
                    if (o == null) continue;
                    if (seen.add(o.optString("id", "") + "|" + o.optLong("t", 0))) {
                        curInc.put(o);
                    }
                }
                p.edit().putString(KEY_LEDGER_INCOME, curInc.toString()).apply();
            }
            backup(ctx, curItems, curInc);
            return added;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 明细去重指纹：时间 + 金额 + 类型 + 商户。 */
    private static String itemSig(JSONObject o) {
        return o.optLong("t", 0) + "|" + o.optLong("c", 0) + "|" + o.optString("k", "")
                + "|" + o.optString("m", "");
    }

    /** 读取公共目录备份用于导入；读不到返回空串。 */
    public static String readPublicBackup(Context ctx) {
        return PublicBackup.read(ctx);
    }
}
