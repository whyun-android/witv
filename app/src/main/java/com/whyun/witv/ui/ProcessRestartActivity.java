package com.whyun.witv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * 重启 WiTV 主进程用的跳板，运行在独立的 {@code :restart} 进程里（见 AndroidManifest）。
 *
 * <p>ExoPlayer 的播放线程卡死在厂商硬解调用里（Amlogic 上 {@code MediaCodec.flush()} 永不返回）时，
 * 进程内怎么重建播放器都救不回来，只有杀掉进程才行。直接在主进程里定个闹钟再自杀，
 * Android 10+ 会拦截后台启动 Activity；所以先启动这个跳板（此刻 WiTV 还在前台，允许启动），
 * 主进程随即自杀，跳板确认主进程已死后再把播放页拉起来，最后自己退出。
 */
public class ProcessRestartActivity extends Activity {

    private static final String TAG = "ProcessRestart";
    private static final String EXTRA_TARGET = "restart_target";
    private static final String EXTRA_MAIN_PID = "restart_main_pid";

    /** 从主进程调用：重启进程后打开 {@code target}。不会返回。 */
    public static void restart(@NonNull Activity from, @NonNull Intent target) {
        Log.e(TAG, "Restarting the app process to recover the playback engine");
        Intent intent = new Intent(from, ProcessRestartActivity.class)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_MAIN_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(intent);
        from.finishAffinity();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int mainPid = getIntent().getIntExtra(EXTRA_MAIN_PID, -1);
        if (mainPid > 0 && mainPid != Process.myPid()) {
            // 主进程一般已经自杀了；万一还活着（被卡住的线程拖着），在这里补一刀，同 uid 允许
            Process.killProcess(mainPid);
        }
        Intent target = getIntent().getParcelableExtra(EXTRA_TARGET);
        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(target);
        }
        finish();
        Process.killProcess(Process.myPid());
    }
}
