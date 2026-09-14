package com.csy.cmbspend;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/** 监听招行 App（cmb.pb）的动账通知，解析出消费金额累加到本月总额，随后刷新桌面小组件。
 *  收入类动账（退款之外的转入/到账等）不直接计入，而是登记到「待决收入」由用户定性。 */
public class CmbNotificationListener extends NotificationListenerService {

    private static final String CMB_PACKAGE = "cmb.pb";
    private static final String TAG = "CmbSpend";

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "监听服务已连接");
        // 重连后扫描当前通知栏，补处理监听器离线期间错过的动账
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    handleNotification(sbn);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "扫描现有通知失败", e);
        }
        SpendWidgetProvider.refresh(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null) {
            handleNotification(sbn);
        }
    }

    /** 处理一条通知：只认 cmb.pb，解析类型后分流（支出累加 / 退款扣减 / 收入进待决表） */
    private void handleNotification(StatusBarNotification sbn) {
        if (sbn == null || !CMB_PACKAGE.equals(sbn.getPackageName())) return;
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;

            CharSequence textCs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence titleCs = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            String title = titleCs == null ? "" : titleCs.toString();
            String text = textCs == null ? "" : textCs.toString();
            String full = title + " " + text;

            CmbNotificationParser.Result r = CmbNotificationParser.parse(
                    full, Rules.getExcludeWords(this));
            if (!r.isValid()) return;

            // 去重：按通知 id（pkg|id），同一笔动账的更新/重发不会重复累加；集合按月保存，跨月清空
            String key = sbn.getPackageName() + "|" + sbn.getId();
            if (SpendStore.isDuplicate(this, key)) return;

            String merchant = CmbNotificationParser.extractMerchant(text);
            if (CmbNotificationParser.Result.TYPE_SPEND.equals(r.type)) {
                // 只写明细台账：本月净额由台账推导（Σ支出−Σ退款−Σ收入抵扣），
                // 这里不再另记「调整值」，否则同一笔会被算两次。
                // 取现没有商户名，用「现金取款」作为来源标签，便于对账时区分
                String label = merchant.isEmpty() ? r.label() : merchant;
                SpendStore.addItem(this, System.currentTimeMillis(), r.cents, label,
                        SpendStore.KIND_SPEND, text);
            } else if (CmbNotificationParser.Result.TYPE_REFUND.equals(r.type)) {
                SpendStore.addItem(this, System.currentTimeMillis(), r.cents, merchant,
                        SpendStore.KIND_REFUND, text);
            } else if (CmbNotificationParser.Result.TYPE_INCOME.equals(r.type)) {
                // 收入不直接抵扣消费，登记进待决台账，等用户在「待决收入」里定性
                SpendStore.addItem(this, System.currentTimeMillis(), r.cents, merchant,
                        SpendStore.KIND_INCOME, text);
                SpendStore.addIncome(this, key, System.currentTimeMillis(), r.cents, merchant, text);
            }
            SpendWidgetProvider.refresh(this);
            Log.d(TAG, "处理 " + r.type + " " + r.cents + " 分，本月累计 "
                    + SpendStore.getCurrentMonthCents(this));
        } catch (Exception e) {
            Log.w(TAG, "处理通知异常", e);
        }
    }
}
