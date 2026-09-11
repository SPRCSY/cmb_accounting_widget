package com.csy.cmbspend;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** 高级 / 保活 + 调试：从主页面挪出来的低频与调试操作。
 *  保活、电池白名单、自启动指引属于「装好之后基本不动」的设置；
 *  模拟通知、跨月清零属于调试手段，都不该占用主页面。 */
public class AdvancedActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);
        statusView = findViewById(R.id.adv_status);

        Button keepBtn = findViewById(R.id.keepalive_btn);
        keepBtn.setOnClickListener(v -> {
            KeepAliveService.ensureRunning(this);
            render();
            Toast.makeText(this, "常驻保活已开启（下拉通知栏可见）", Toast.LENGTH_SHORT).show();
        });

        Button batteryBtn = findViewById(R.id.battery_btn);
        batteryBtn.setOnClickListener(v -> openBatteryOptimization());

        Button autoBtn = findViewById(R.id.autostart_btn);
        autoBtn.setOnClickListener(v -> openAutoStart());

        Button exportBtn = findViewById(R.id.export_btn);
        exportBtn.setOnClickListener(v -> {
            String path = SpendStore.exportCsv(this);
            if (path != null) {
                Toast.makeText(this, "已导出：\n" + path, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "导出失败（存储不可写）", Toast.LENGTH_SHORT).show();
            }
        });

        Button importBtn = findViewById(R.id.import_btn);
        importBtn.setOnClickListener(v -> pickBackupFile());

        Button storageBtn = findViewById(R.id.storage_btn);
        storageBtn.setOnClickListener(v -> requestAllFilesAccess());

        Button restoreBtn = findViewById(R.id.restore_btn);
        restoreBtn.setOnClickListener(v -> restoreFromDir());

        Button testBtn = findViewById(R.id.test_btn);
        testBtn.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            String raw = "模拟测试：快捷支付扣款人民币1.00";
            SpendStore.addItem(this, now, 100L, "模拟-测试消费",
                    SpendStore.KIND_SPEND, raw);
            SpendWidgetProvider.refresh(this);
            render();
            Toast.makeText(this, "已模拟收到 1 元消费（累加至本月）", Toast.LENGTH_SHORT).show();
        });

        Button testIncomeBtn = findViewById(R.id.test_income_btn);
        testIncomeBtn.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            String raw = "模拟测试：他人转入人民币88.00";
            SpendStore.addItem(this, now, 8800L, "模拟-他人转入",
                    SpendStore.KIND_INCOME, raw);
            SpendStore.addIncome(this, "test|" + now, now, 8800L, "模拟-他人转入", raw);
            SpendWidgetProvider.refresh(this);
            render();
            Toast.makeText(this, "已模拟一笔收入（¥88.00），去「待决收入」定性", Toast.LENGTH_SHORT).show();
        });

        Button monthBtn = findViewById(R.id.month_btn);
        monthBtn.setOnClickListener(v -> {
            SpendStore.resetForDebug(this);
            render();
            Toast.makeText(this, "已清空本月累计、明细与收入台账", Toast.LENGTH_SHORT).show();
        });

        Button backBtn = findViewById(R.id.back_btn);
        backBtn.setOnClickListener(v -> finish());
        applyWindowInsets();
    }

    /** targetSdk 35 强制 edge-to-edge：给内容加系统栏内边距，避免顶到状态栏。 */
    private void applyWindowInsets() {
        View root = findViewById(R.id.adv_root);
        if (root == null) return;
        final int baseL = root.getPaddingLeft(), baseT = root.getPaddingTop();
        final int baseR = root.getPaddingRight(), baseB = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        long cents = SpendStore.getCurrentMonthCents(this);
        int pending = SpendStore.getPendingIncomeCount(this);
        int rules = Rules.getExcludeWords(this).size();
        boolean keep = KeepAliveService.isRunning();
        statusView.setText("本月消费：" + SpendWidgetProvider.formatYuan(cents)
                + "\n保活服务：" + (keep ? "运行中" : "未开启")
                + "\n排除规则：" + rules + " 个关键词"
                + "\n待决收入：" + pending + " 笔"
                + "\n文件访问权限：" + (PublicBackup.hasAllFilesAccess() ? "已授予（可自动恢复）" : "未授予")
                + "\n备份目录：" + SpendStore.publicBackupPath());
    }

    private static final int REQ_PICK_BACKUP = 2001;

    /** 申请「所有文件访问权限」：到系统设置页让用户给本 App 开「所有文件访问」。
     *  授权后 App 可直接读写内部存储根目录下的自建目录，重装后也能自动恢复。 */
    private void requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    Toast.makeText(this, "请在系统设置 → 应用 → 特殊权限里开启「所有文件访问」", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            Toast.makeText(this, "当前系统版本无需该权限，备份已可写入公共目录", Toast.LENGTH_SHORT).show();
        }
    }

    /** 直接从自建目录 `内部存储/当月消费/台账备份.json` 恢复（授权后重装即可一键恢复）。 */
    private void restoreFromDir() {
        String json = SpendStore.readPublicBackup(this);
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "未在 " + SpendStore.publicBackupPath() + " 找到备份\n"
                    + "（请先授予「所有文件访问权限」，或在有数据时导出一次）", Toast.LENGTH_LONG).show();
            return;
        }
        int added = SpendStore.restoreFromJson(this, json);
        if (added >= 0) {
            SpendWidgetProvider.refresh(this);
            render();
            Toast.makeText(this, "已从自建目录恢复，新增 " + added + " 条明细", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "备份文件无法解析", Toast.LENGTH_LONG).show();
        }
    }

    /** 用系统文件选择器挑备份 JSON（SAF 可跨重装/跨 uid 读取，是重装后恢复的可靠入口）。 */
    private void pickBackupFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BACKUP || resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String json = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            int added = SpendStore.restoreFromJson(this, json);
            if (added >= 0) {
                SpendWidgetProvider.refresh(this);
                render();
                Toast.makeText(this, "导入完成，新增 " + added + " 条明细", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "文件不是有效的备份（无法解析）", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
}
