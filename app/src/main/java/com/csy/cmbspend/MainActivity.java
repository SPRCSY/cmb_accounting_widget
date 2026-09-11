package com.csy.cmbspend;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主页面：本月累计 + 明细表格（时间 | 金额 | 详细）+ 合计。
 *  常用操作留在本页，调试与保活类操作移到 AdvancedActivity。 */
public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private TextView totalView;
    private TextView subView;
    private LinearLayout headerRow;
    private LinearLayout bodyCol;
    private LinearLayout totalRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        totalView = findViewById(R.id.total_text);
        subView = findViewById(R.id.sub_text);
        headerRow = findViewById(R.id.table_header);
        bodyCol = findViewById(R.id.table_body);
        totalRow = findViewById(R.id.table_total);

        findViewById(R.id.grant_btn).setOnClickListener(v -> onGrantClicked());
        findViewById(R.id.refresh_btn).setOnClickListener(v -> render());
        findViewById(R.id.rules_btn).setOnClickListener(v -> showRulesDialog());
        findViewById(R.id.advanced_btn).setOnClickListener(v ->
                startActivity(new Intent(this, AdvancedActivity.class)));

        Button pendingBtn = findViewById(R.id.pending_btn);
        pendingBtn.setOnClickListener(v ->
                startActivity(new Intent(this, PendingIncomeActivity.class)));

        applyWindowInsets();
    }

    /** targetSdk 35 在 Android 15 上强制 edge-to-edge，标题会顶到状态栏里。
     *  给根布局加系统栏内边距，保证内容避开状态栏/导航栏。 */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.root);
        if (root == null) return;
        final int baseL = root.getPaddingLeft(), baseT = root.getPaddingTop();
        final int baseR = root.getPaddingRight(), baseB = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars());
            v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次进 App 都确保保活服务在跑（幂等，已在运行则无副作用）
        KeepAliveService.ensureRunning(this);
        render();
        // 每次回到页面都请求重新绑定，确保监听服务在线
        if (isListenerEnabled()) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, CmbNotificationListener.class));
        }
        // 服务启动是异步的，延迟刷新一次状态显示
        totalView.postDelayed(this::render, 800);
        // Android 13+ 请求通知权限（App 自己的测试通知需要）
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS && grantResults.length > 0) {
            render();
        }
    }

    private void onGrantClicked() {
        if (isListenerEnabled()) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, CmbNotificationListener.class));
            Toast.makeText(this, "已授权，正在重新连接监听…", Toast.LENGTH_SHORT).show();
        } else {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void render() {
        long cents = SpendStore.getCurrentMonthCents(this);
        String status = isListenerEnabled() ? "已授权" : "未授权";
        String keep = isKeepAliveRunning() ? "运行中" : "未开启";
        int ruleCount = Rules.getExcludeWords(this).size();
        int pending = SpendStore.getPendingIncomeCount(this);
        totalView.setText("本月消费 " + SpendWidgetProvider.formatYuan(cents));

        StringBuilder sub = new StringBuilder();
        sub.append("通知监听：").append(status).append("　保活：").append(keep);
        if (ruleCount > 0) sub.append("\n排除规则：").append(ruleCount).append(" 个关键词");
        if (pending > 0) sub.append("\n待决收入：").append(pending).append(" 笔待你定性");
        subView.setText(sub.toString());

        renderTable();
    }

    /** 渲染明细表格：表头 + 逐行 + 合计页脚；时间/金额列宽按内容智能配置。 */
    private void renderTable() {
        if (headerRow == null || bodyCol == null || totalRow == null) return;

        List<SpendStore.Item> items = SpendStore.getItems(this);
        SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        // 第一遍：准备每行要显示的文本，并测最宽的「时间」「金额」两列
        String[] timeTexts = new String[items.size()];
        String[] amtTexts = new String[items.size()];
        String[] detailTexts = new String[items.size()];
        int[] amountColors = new int[items.size()];
        Paint measure = new Paint();
        measure.setTextSize(spToPx(12));
        measure.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float maxTime = measure.measureText("时间");
        float maxAmt = measure.measureText("金额");

        for (int i = 0; i < items.size(); i++) {
            SpendStore.Item it = items.get(i);
            String time = dayFmt.format(new Date(it.timeMs)) + "\n" + timeFmt.format(new Date(it.timeMs));
            String amt;
            long signed;
            if (it.isSpend()) { signed = it.cents; amt = "+" + SpendWidgetProvider.formatYuan(it.cents); }
            else if (it.isRefund()) { signed = -it.cents; amt = "-" + SpendWidgetProvider.formatYuan(it.cents); }
            else { signed = 0; amt = "收入 " + SpendWidgetProvider.formatYuan(it.cents); }

            String detail = buildDetail(it);
            timeTexts[i] = time;
            amtTexts[i] = amt;
            detailTexts[i] = detail;
            amountColors[i] = it.isSpend() ? 0xFF222222
                    : it.isRefund() ? 0xFF1B7F3B : 0xFF1F5FBF;

            // 时间列是两行文本，取较宽的一行
            float w1 = Math.max(measure.measureText(dayFmt.format(new Date(it.timeMs))),
                    measure.measureText(timeFmt.format(new Date(it.timeMs))));
            maxTime = Math.max(maxTime, w1);
            maxAmt = Math.max(maxAmt, measure.measureText(amt));
        }

        int timeW = Math.round(maxTime) + dpToPx(14);
        int amtW = Math.round(maxAmt) + dpToPx(14);
        int cellGap = dpToPx(8);

        // 表头
        headerRow.removeAllViews();
        headerRow.addView(makeCell("时间", timeW, cellGap, true, Gravity.START, 0xFF333333));
        headerRow.addView(makeCell("金额", amtW, cellGap, true, Gravity.END, 0xFF333333));
        headerRow.addView(makeFlexCell("详细", cellGap, true, Gravity.START, 0xFF333333));

        // 明细行
        bodyCol.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无明细）");
            empty.setTextSize(12);
            empty.setPadding(cellGap, dpToPx(10), cellGap, dpToPx(10));
            empty.setTextColor(0xFF888888);
            bodyCol.addView(empty);
        }
        for (int i = 0; i < items.size(); i++) {
            View row = inflater.inflate(R.layout.table_row, bodyCol, false);
            TextView cTime = row.findViewById(R.id.cell_time);
            TextView cAmt = row.findViewById(R.id.cell_amount);
            TextView cDetail = row.findViewById(R.id.cell_detail);

            cTime.setLayoutParams(fixedParams(timeW, cellGap));
            cTime.setText(timeTexts[i]);
            cAmt.setLayoutParams(fixedParams(amtW, cellGap));
            cAmt.setText(amtTexts[i]);
            cAmt.setTextColor(amountColors[i]);
            cDetail.setText(detailTexts[i]);

            // 隔行底色，便于横向对账
            int bg = (i % 2 == 0) ? 0x00FFFFFF : 0x14000000;
            row.setBackgroundColor(bg);
            bodyCol.addView(row);
        }

        // 合计页脚：与顶部大数同源，保证「支出 − 退款 − 收入抵扣 = 净额」能对上
        SpendStore.Summary sum = SpendStore.getSummary(this);
        long net = Math.max(0, sum.netCents());
        totalRow.removeAllViews();
        totalRow.addView(makeCell("合计", timeW, cellGap, true, Gravity.START, 0xFF222222));
        totalRow.addView(makeCell(SpendWidgetProvider.formatYuan(net), amtW, cellGap, true,
                Gravity.END, 0xFF222222));
        StringBuilder sumDetail = new StringBuilder();
        sumDetail.append("支出 ").append(SpendWidgetProvider.formatYuan(sum.spendCents))
                .append(" − 退款 ").append(SpendWidgetProvider.formatYuan(sum.refundCents));
        if (sum.deductCents > 0) {
            sumDetail.append(" − 收入抵扣 ").append(SpendWidgetProvider.formatYuan(sum.deductCents));
        }
        sumDetail.append(" = 净 ").append(SpendWidgetProvider.formatYuan(net));
        totalRow.addView(makeFlexCell(sumDetail.toString(), cellGap, true, Gravity.START, 0xFF222222));
    }

    /** 详情：商户名 + 原文关键内容（收入标出待决/已定性）。 */
    private String buildDetail(SpendStore.Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.merchant != null && !it.merchant.isEmpty()) {
            sb.append(it.merchant);
        } else {
            sb.append(it.isIncome() ? "（未识别商户）" : "—");
        }
        if (it.isIncome()) sb.append("　[收入]");
        String d = it.detail == null ? "" : it.detail.trim();
        if (!d.isEmpty()) {
            sb.append("\n").append(d);
        }
        return sb.toString();
    }

    private TextView makeCell(String text, int width, int gap, boolean bold, int gravity, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setGravity(gravity);
        tv.setTextColor(color);
        tv.setLayoutParams(fixedParams(width, gap));
        return tv;
    }

    private TextView makeFlexCell(String text, int gap, boolean bold, int gravity, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setGravity(gravity);
        tv.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(gap);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout.LayoutParams fixedParams(int width, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(gap);
        return lp;
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    private float spToPx(int sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }

    /** 检测常驻保活前台服务是否在运行。
     *  OPPO 上 getActiveNotifications/getRunningServices 均不可靠，用进程内标志；
     *  标志未置位但服务确实被 onResume 拉起过时，兜底按运行中处理。 */
    private boolean isKeepAliveRunning() {
        if (KeepAliveService.isRunning()) return true;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            if (am != null) {
                for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(200)) {
                    if (KeepAliveService.class.getName().equals(info.service.getClassName())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 规则设置对话框：增删「排除关键词」。命中任一排除词的动账不计入消费。 */
    private void showRulesDialog() {
        final java.util.Set<String> words = new java.util.LinkedHashSet<>(Rules.getExcludeWords(this));
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入要排除的关键词，如：转账 / 还款 / 随用随充");
        input.setText(TextUtils.join("、", words));

        new android.app.AlertDialog.Builder(this)
                .setTitle("排除关键词")
                .setMessage("正文或商户名包含下列任一关键词的动账将不计入消费（用顿号或换行分隔多个词）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    java.util.Set<String> saved = new java.util.LinkedHashSet<>();
                    String raw = input.getText().toString();
                    for (String part : raw.split("[、，,;；\\s]+")) {
                        String kw = part.trim();
                        if (!kw.isEmpty()) saved.add(kw);
                    }
                    Rules.saveExcludeWords(this, saved);
                    render();
                    Toast.makeText(this, "规则已保存：" + saved.size() + " 个排除词", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 检测「通知使用权」是否已授权（系统开关，只能引导用户去设置页打开） */
    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName cn = new ComponentName(this, CmbNotificationListener.class);
        for (String s : flat.split(":")) {
            if (s.equalsIgnoreCase(cn.flattenToString())) return true;
        }
        return false;
    }
}
