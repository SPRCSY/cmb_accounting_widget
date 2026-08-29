package com.csy.cmbspend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 事件唤醒保活：监听「屏幕点亮 / 用户解锁」这类系统事件，
 *  在这些时刻检查本 App 的保活前台服务是否被杀，不在则重新拉起。
 *  这是事件驱动（非轮询/非多进程互拉），零持续功耗，
 *  且屏幕亮起本身已退出 Doze，系统此时允许启动前台服务。 */
public class AutoStartReceiver extends BroadcastReceiver {

    private static final String TAG = "CmbSpend";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.d(TAG, "AutoStartReceiver 收到: " + action);
        // 只要进程被系统拉起，onReceive 就会执行；这里顺手确保保活服务在跑
        // （KeepAliveService.ensureRunning 是幂等的，已在运行则无副作用）
        KeepAliveService.ensureRunning(context);
    }
}
