package com.csy.cmbspend;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 常驻保活前台服务：把本进程提升为前台进程，让通知监听服务尽量不被系统后台清理杀掉。
 *  通知设为低优先级、静默、常驻（不可滑动消除），点通知回到引导页。 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "keepalive";
    private static final int NOTIF_ID = 1001;
    /** 进程内运行标志：onStartCommand 置位，onDestroy 清除，供 UI 检测 */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 是否正在运行（前台服务是否已启动） */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** 启动保活服务（幂等：已在运行则无副作用） */
    public static void ensureRunning(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            // 后台启动限制等情况下静默失败，不崩溃
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        RUNNING.set(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING.set(false);
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "常驻保活",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("当月消费")
                .setContentText("正在监听招行动账，保证桌面小组件实时更新")
                .setOngoing(true)
                .setDefaults(0)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
