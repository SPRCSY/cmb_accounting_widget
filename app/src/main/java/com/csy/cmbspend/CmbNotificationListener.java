package com.csy.cmbspend;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/** 监听招行 App（cmb.pb）的动账通知，解析出消费金额累加到本月总额，随后刷新桌面小组件。 */
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

    /** 处理一条通知：只认 cmb.pb，解析消费金额并累加 */
    private void handleNotification(StatusBarNotification sbn) {
        if (sbn == null || !CMB_PACKAGE.equals(sbn.getPackageName())) return;
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;

            CharSequence textCs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence titleCs = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            String title = titleCs == null ? "" : titleCs.toString();
            String text = textCs == null ? "" : textCs.toString();

            // 带符号金额：消费为正、退款为负、无关为 0；应用用户自定义排除关键词
            long cents = CmbNotificationParser.parseSignedCents(
                    title + " " + text, Rules.getExcludeWords(this));
            if (cents == 0) return;

            // 去重：按通知 id（pkg|id），同一笔动账的更新/重发不会重复累加；集合按月保存，跨月清空
            if (SpendStore.isDuplicate(this, sbn.getPackageName() + "|" + sbn.getId())) return;

            SpendStore.addCents(this, cents);
            // 记录明细：日期（毫秒）+ 带符号金额（分，退款为负）+ 商户，供对账发现漏记
            SpendStore.addItem(this, System.currentTimeMillis(), cents,
                    CmbNotificationParser.extractMerchant(text));
            SpendWidgetProvider.refresh(this);
            Log.d(TAG, (cents < 0 ? "退款扣减 " : "计入 ") + cents + " 分，本月累计 "
                    + SpendStore.getCurrentMonthCents(this));
        } catch (Exception e) {
            Log.w(TAG, "处理通知异常", e);
        }
    }
}
