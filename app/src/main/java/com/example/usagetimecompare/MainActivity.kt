package com.example.usagetimecompare

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var targetInput: EditText
    private lateinit var resultView: TextView
    private lateinit var appListGroup: RadioGroup
    private val defaultTargetPackageName = "com.example.game"
    private val prefs by lazy { getSharedPreferences("usage_compare", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupUi()

        if (!hasUsageStatsPermission()) {
            resultView.text = "请先在设置中授予【使用情况访问权限】，回来后点“重新对比”。"
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        runComparison()
    }

    override fun onResume() {
        super.onResume()
        if (::resultView.isInitialized && hasUsageStatsPermission()) {
            runComparison()
        }
    }

    private fun setupUi() {
        val padding = 32
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        targetInput = EditText(this).apply {
            hint = "目标应用包名，例如 com.tencent.tmgp.sgame"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("target_package", defaultTargetPackageName))
        }

        val compareButton = Button(this).apply {
            text = "重新对比"
            setOnClickListener { runComparison() }
        }

        val loadAppsButton = Button(this).apply {
            text = "列出今天用过的应用"
            setOnClickListener { loadTodayUsedApps() }
        }

        val settingsButton = Button(this).apply {
            text = "打开使用情况访问权限"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

        appListGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        resultView = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }

        root.addView(targetInput)
        root.addView(compareButton)
        root.addView(loadAppsButton)
        root.addView(settingsButton)
        root.addView(appListGroup)
        root.addView(resultView)

        setContentView(ScrollView(this).apply {
            clipToPadding = false
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val systemInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    view.setPadding(
                        systemInsets.left,
                        systemInsets.top,
                        systemInsets.right,
                        systemInsets.bottom
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        })
    }

    private fun runComparison() {
        if (!hasUsageStatsPermission()) {
            resultView.text = "未授予【使用情况访问权限】，无法读取 UsageStats / UsageEvents。"
            return
        }

        val targetPackageName = targetInput.text.toString().trim()
        if (targetPackageName.isEmpty()) {
            resultView.text = "请输入目标应用包名。"
            return
        }
        prefs.edit().putString("target_package", targetPackageName).apply()

        val (beginTime, endTime) = todayWindow()

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val totalTimeAggregateMs = usageStatsManager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            .orEmpty()
            .filter { it.packageName == targetPackageName }
            .sumOf { it.totalTimeInForeground }
        val method1Minutes = totalTimeAggregateMs / 1000 / 60

        val eventResult = calculateFromEvents(usageStatsManager, targetPackageName, beginTime, endTime)
        val totalTimeEventsMs = eventResult.totalTimeMs
        val method2Minutes = totalTimeEventsMs / 1000 / 60
        val diffMinutes = (totalTimeAggregateMs - totalTimeEventsMs) / 1000 / 60
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        val resultText = """
            测试目标: $targetPackageName
            统计窗口: ${formatTime(formatter, beginTime)} - ${formatTime(formatter, endTime)}

            【方法一：queryUsageStats】
            系统汇总前台时长: ${formatDuration(totalTimeAggregateMs)} ($method1Minutes 分钟)

            【方法二：queryEvents】
            事件流水累加时长: ${formatDuration(totalTimeEventsMs)} ($method2Minutes 分钟)

            【差异】
            Stats - Events: ${formatDuration(totalTimeAggregateMs - totalTimeEventsMs)} ($diffMinutes 分钟)

            【Events 诊断】
            目标包事件数: ${eventResult.targetEventCount}
            MOVE_TO_FOREGROUND: ${eventResult.foregroundCount}
            MOVE_TO_BACKGROUND: ${eventResult.backgroundCount}
            无前台起点的后台事件: ${eventResult.backgroundWithoutForegroundCount}
            当前是否疑似仍在前台: ${if (eventResult.openSessionStartedAt > 0L) "是" else "否"}
            首个目标事件: ${eventResult.firstEventTime?.let { formatTime(formatter, it) } ?: "无"}
            最后目标事件: ${eventResult.lastEventTime?.let { formatTime(formatter, it) } ?: "无"}

            判断建议:
            如果 queryUsageStats 有明显时长，但 queryEvents 的目标事件数很少或前后台事件缺失，
            更像是系统保留了聚合统计，但事件流水不完整。
        """.trimIndent()

        resultView.text = resultText
        Log.d("TimeTrackerDemo", resultText)
    }

    private fun loadTodayUsedApps() {
        if (!hasUsageStatsPermission()) {
            resultView.text = "未授予【使用情况访问权限】，无法列出应用。"
            return
        }

        val (beginTime, endTime) = todayWindow()
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usedApps = usageStatsManager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, stats) ->
                UsedApp(
                    packageName = packageName,
                    label = loadAppLabel(packageName),
                    totalTimeMs = stats.sumOf { it.totalTimeInForeground }
                )
            }
            .sortedByDescending { it.totalTimeMs }

        appListGroup.removeAllViews()
        if (usedApps.isEmpty()) {
            resultView.text = "今天的使用记录里没有可选应用。请确认权限已授予，并且目标应用今天打开过。"
            return
        }

        usedApps.take(30).forEach { app ->
            val radioButton = RadioButton(this).apply {
                text = "${app.label}\n${app.packageName}\n${formatDuration(app.totalTimeMs)}"
                textSize = 14f
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    targetInput.setText(app.packageName)
                    runComparison()
                }
            }
            appListGroup.addView(radioButton)
        }

        resultView.text = "已列出今天使用过的前 ${minOf(usedApps.size, 30)} 个应用。点选应用后会自动对比。"
    }

    private fun todayWindow(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis to System.currentTimeMillis()
    }

    private fun calculateFromEvents(
        usageStatsManager: UsageStatsManager,
        targetPackageName: String,
        beginTime: Long,
        endTime: Long
    ): EventResult {
        var totalTimeEventsMs = 0L
        var foregroundStartedAt = 0L
        var targetEventCount = 0
        var foregroundCount = 0
        var backgroundCount = 0
        var backgroundWithoutForegroundCount = 0
        var firstEventTime: Long? = null
        var lastEventTime: Long? = null

        val events = usageStatsManager.queryEvents(beginTime, endTime)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != targetPackageName) continue

            targetEventCount++
            firstEventTime = firstEventTime ?: event.timeStamp
            lastEventTime = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foregroundCount++
                    if (foregroundStartedAt == 0L) {
                        foregroundStartedAt = event.timeStamp
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    backgroundCount++
                    if (foregroundStartedAt > 0L && event.timeStamp >= foregroundStartedAt) {
                        totalTimeEventsMs += event.timeStamp - foregroundStartedAt
                        foregroundStartedAt = 0L
                    } else {
                        backgroundWithoutForegroundCount++
                    }
                }
            }
        }

        if (foregroundStartedAt > 0L) {
            totalTimeEventsMs += endTime - foregroundStartedAt
        }

        return EventResult(
            totalTimeMs = totalTimeEventsMs,
            targetEventCount = targetEventCount,
            foregroundCount = foregroundCount,
            backgroundCount = backgroundCount,
            backgroundWithoutForegroundCount = backgroundWithoutForegroundCount,
            openSessionStartedAt = foregroundStartedAt,
            firstEventTime = firstEventTime,
            lastEventTime = lastEventTime
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun formatDuration(durationMs: Long): String {
        val sign = if (durationMs < 0) "-" else ""
        val totalSeconds = kotlin.math.abs(durationMs) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%s%02d:%02d:%02d".format(Locale.US, sign, hours, minutes, seconds)
    }

    private fun formatTime(formatter: SimpleDateFormat, timeMs: Long): String {
        return formatter.format(Date(timeMs))
    }

    private fun loadAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private data class EventResult(
        val totalTimeMs: Long,
        val targetEventCount: Int,
        val foregroundCount: Int,
        val backgroundCount: Int,
        val backgroundWithoutForegroundCount: Int,
        val openSessionStartedAt: Long,
        val firstEventTime: Long?,
        val lastEventTime: Long?
    )

    private data class UsedApp(
        val packageName: String,
        val label: String,
        val totalTimeMs: Long
    )
}
