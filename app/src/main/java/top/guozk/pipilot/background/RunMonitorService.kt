package top.guozk.pipilot.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.guozk.pipilot.MainActivity
import top.guozk.pipilot.PipilotApplication
import top.guozk.pipilot.R

/**
 * opt-in 的前台服务：在用户离开应用期间保持一条隐私安全的「运行中」通知。
 *
 * 边界（继承原计划 014）：
 * - 仅本地尽力而为；force-stop/系统杀进程后不承诺送达。
 * - 「停止监控」只停本地服务与通知，不是「中止运行」。
 * - 通知内容只用通用文案；会话标题/模型等细节默认不展示。
 */
class RunMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    private val appGraph by lazy { (application as PipilotApplication).appGraph }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(phaseText(RunStateObserver.Phase.IDLE)))

        val observer = appGraph.runStateObserver
        observer.start(scope)
        observerJob =
            scope.launch {
                observer.snapshot
                    .map { it.phase }
                    .distinctUntilChanged()
                    .collect { phase ->
                        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, buildNotification(phaseText(phase)))
                        if (phase == RunStateObserver.Phase.IDLE) {
                            // 运行结束（完成或失败不区分，保持通用文案），更新通知后自动退出监控
                            stopSelf()
                        }
                    }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        appGraph.runStateObserver.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun phaseText(phase: RunStateObserver.Phase): String =
        when (phase) {
            RunStateObserver.Phase.STREAMING -> getString(R.string.run_monitor_working)
            RunStateObserver.Phase.RETRYING -> getString(R.string.run_monitor_retrying)
            RunStateObserver.Phase.IDLE -> getString(R.string.run_monitor_finished)
        }

    private fun buildNotification(phaseText: String): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RunMonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.run_monitor_title))
            .setContentText(phaseText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.run_monitor_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.run_monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.run_monitor_channel_desc)
            }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "run_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "top.guozk.pipilot.background.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RunMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RunMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
