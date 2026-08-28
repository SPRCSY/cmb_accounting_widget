package com.csy.cmbspend;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.NumberFormat;
import java.util.Locale;

/** 桌面小组件：显示本月累计消费。
 *  触发刷新时机：每笔新通知累加后（refresh）、开机（BOOT_COMPLETED）、跨月首次读取。 */
public class SpendWidgetProvider extends AppWidgetProvider {

    /** 通知监听累加后调用：直接刷新所有已添加的小组件（同步重绘，不依赖广播） */
    public static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(
                    new android.content.ComponentName(ctx, SpendWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews views = buildViews(ctx);
            mgr.updateAppWidget(ids, views);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 开机后：拉起保活前台服务 + 刷新小组件（监听器由系统按通知使用权自动绑定）
            KeepAliveService.ensureRunning(context);
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context));
        }
    }

    static RemoteViews buildViews(Context context) {
        long cents = SpendStore.getCurrentMonthCents(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        // 显式设置标签文字，避免 OPPO launcher 复用 RemoteViews 时标签被错刷成金额
        views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_label));
        views.setTextViewText(R.id.widget_amount, formatYuan(cents));
        views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    /** 分 → "¥4,258.58"（千分位，固定两位小数） */
    static String formatYuan(long cents) {
        long yuan = cents / 100;
        long fen = cents % 100;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        return "¥" + nf.format(yuan) + "." + (fen < 10 ? "0" + fen : String.valueOf(fen));
    }
}
