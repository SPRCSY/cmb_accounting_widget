package com.csy.cmbspend;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** 引导页：显示本月累计消费、一键跳系统「通知使用权」授权；debug 用模拟通知与跨月清零。 */
public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private TextView totalView;
    private TextView itemsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        totalView = findViewById(R.id.total_text);
        itemsView = findViewById(R.id.items_text);
        Button grantBtn = findViewById(R.id.grant_btn);
        Button refreshBtn = findViewById(R.id.refresh_btn);
        Button testBtn = findViewById(R.id.test_btn);
        Button monthBtn = findViewById(R.id.month_btn);
        Button batteryBtn = findViewById(R.id.battery_btn);

        grantBtn.setOnClickListener(v -> {
            if (isListenerEnabled()) {
                // 已授权但可能未绑定：主动请求系统重新绑定监听服务
                NotificationListenerService.requestRebind(
                        new ComponentName(this, CmbNotificationListener.class));
                Toast.makeText(this, "已授权，正在重新连接监听…", Toast.LENGTH_SHORT).show();
            } else {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        refreshBtn.setOnClickListener(v -> render());
        testBtn.setOnClickListener(v -> sendTestNotification());
        monthBtn.setOnClickListener(v -> {
            SpendStore.resetForDebug(this);
            render();
            Toast.makeText(this, "已模拟跨月清零（含去重记录）", Toast.LENGTH_SHORT).show();
        });
        batteryBtn.setOnClickListener(v -> openBatteryOptimization());

        Button keepBtn = findViewById(R.id.keepalive_btn);
        keepBtn.setOnClickListener(v -> {
            KeepAliveService.ensureRunning(this);
            render();
            Toast.makeText(this, "常驻保活已开启（下拉通知栏可见）", Toast.LENGTH_SHORT).show();
        });

        Button autoBtn = findViewById(R.id.autostart_btn);
        autoBtn.setOnClickListener(v -> openAutoStart());

        Button rulesBtn = findViewById(R.id.rules_btn);
        rulesBtn.setOnClickListener(v -> showRulesDialog());
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

    private void render() {
        long cents = SpendStore.getCurrentMonthCents(this);
        String status = isListenerEnabled() ? "已授权" : "未授权";
        String keep = isKeepAliveRunning() ? "运行中" : "未开启";
        int ruleCount = Rules.getExcludeWords(this).size();
        totalView.setText("本月消费 " + SpendWidgetProvider.formatYuan(cents)
                + "\n通知监听：" + status + "　保活：" + keep
                + (ruleCount > 0 ? "\n排除规则：" + ruleCount + " 个关键词" : ""));
        renderItems();
    }

    /** 检测常驻保活前台服务是否在运行。
     *  OPPO 上 getActiveNotifications/getRunningServices 均不可靠，用进程内标志；
     *  标志未置位但服务确实被 onResume 拉起过时，兜底按运行中处理。 */
    private boolean isKeepAliveRunning() {
        if (KeepAliveService.isRunning()) return true;
        // 兜底：查系统运行服务（前台服务通常可见）
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

    /** 展示当月最近的明细（对账用：能发现监听器掉线漏记的笔） */
    private void renderItems() {
        if (itemsView == null) return;
        java.util.List<long[]> items = SpendStore.getItems(this);
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        int n = Math.min(items.size(), 8);
        for (int i = 0; i < n; i++) {
            long[] it = items.get(i);
            String merchant = SpendStore.getMerchantAt(this, i);
            String amt = SpendWidgetProvider.formatYuan(Math.abs(it[1]));
            sb.append(fmt.format(new java.util.Date(it[0])))
              .append("  ").append(it[1] < 0 ? "退款 -" + amt : amt)
              .append("  ").append(merchant).append("\n");
        }
        if (sb.length() == 0) sb.append("（暂无明细）");
        itemsView.setText(sb.toString());
    }

    /** 跳到电池优化设置页，引导用户关闭对「当月消费」的省电限制（能显著提高监听存活率） */
    private void openBatteryOptimization() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "未找到电池优化设置，请在系统设置里手动关闭「当月消费」的省电限制", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 自启动指引：ColorOS 自启动页有厂商权限保护无法直接跳转，只弹文字引导用户手动开启。 */
    private void openAutoStart() {
        Toast.makeText(this,
                "手动开启自启动：\n系统设置 → 应用 → 应用管理 → 自启动 → 找到「当月消费」→ 打开开关",
                Toast.LENGTH_LONG).show();
    }

    /** 规则设置对话框：增删「排除关键词」。命中任一排除词的动账不计入消费。 */
    private void showRulesDialog() {
        final java.util.Set<String> words = new java.util.LinkedHashSet<>(Rules.getExcludeWords(this));
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入要排除的关键词，如：转账 / 还款 / 随用随充");
        input.setText(android.text.TextUtils.join("、", words));

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

    /** debug 用：直接累加 1 元并刷新小组件，验证存储/累计/显示链路
     *  （监听器只处理 cmb.pb 真实通知，测试通知无法伪造包名，故这里绕过监听直接累加） */
    private void sendTestNotification() {
        SpendStore.addCents(this, 100L);
        // 同步记一条明细（模拟真实动账记录）
        SpendStore.addItem(this, System.currentTimeMillis(), 100L, "模拟-测试消费");
        SpendWidgetProvider.refresh(this);
        render();
        Toast.makeText(this, "已模拟收到1元消费（累加至本月）", Toast.LENGTH_SHORT).show();
    }
}
