package com.example.usagetimecompare

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var targetInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var resultView: TextView
    private lateinit var appListGroup: RadioGroup
    private var installedApps: List<SelectableApp> = emptyList()
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
        val pagePadding = 28
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pagePadding, pagePadding, pagePadding, pagePadding)
            setBackgroundColor(Color.rgb(246, 248, 250))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = "使用时长 API 对比"
            textSize = 22f
            setTextColor(Color.rgb(24, 32, 41))
        }

        val subtitleView = TextView(this).apply {
            text = "选择应用和时间范围，对比系统汇总与事件流水。"
            textSize = 14f
            setTextColor(Color.rgb(91, 103, 112))
            setPadding(0, 8, 0, 18)
        }

        val rangeLabel = label("统计范围")
        daysInput = EditText(this).apply {
            hint = "最近几天，例如 10"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(prefs.getInt("days", 1).toString())
        }

        val packageLabel = label("目标应用")
        targetInput = EditText(this).apply {
            hint = "包名，例如 com.tencent.tmgp.sgame"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("target_package", defaultTargetPackageName))
        }

        val searchLabel = label("搜索手机上的应用")
        searchInput = EditText(this).apply {
            hint = "输入应用名或包名"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderAppList(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        val compareButton = Button(this).apply {
            text = "重新对比"
            setOnClickListener { runComparison() }
        }

        val loadAppsButton = Button(this).apply {
            text = "加载应用列表"
            setOnClickListener { loadInstalledApps() }
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
            setTextColor(Color.rgb(24, 32, 41))
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
            background = roundedBackground(Color.WHITE)
        }

        root.addView(titleView)
        root.addView(subtitleView)
        root.addView(rangeLabel)
        root.addView(daysInput)
        root.addView(packageLabel)
        root.addView(targetInput)
        root.addView(compareButton)
        root.addView(settingsButton)
        root.addView(resultView)
        root.addView(searchLabel)
        root.addView(searchInput)
        root.addView(loadAppsButton)
        root.addView(appListGroup)

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

        loadInstalledApps()
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(76, 88, 99))
            setPadding(0, 18, 0, 6)
        }
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 8f
        }
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
        val days = parseDays()
        prefs.edit().putString("target_package", targetPackageName).apply()
        prefs.edit().putInt("days", days).apply()

        val (beginTime, endTime) = recentDaysWindow(days)

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
            统计范围: 最近 $days 天
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

    private fun loadInstalledApps() {
        installedApps = packageManager
            .getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.enabled }
            .map { appInfo ->
                SelectableApp(
                    packageName = appInfo.packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString(),
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            }
            .sortedWith(compareBy<SelectableApp> { it.isSystemApp }.thenBy { it.label.lowercase(Locale.getDefault()) })

        renderAppList(searchInput.text?.toString().orEmpty())
    }

    private fun renderAppList(query: String) {
        if (installedApps.isEmpty()) {
            appListGroup.removeAllViews()
            return
        }

        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        val filteredApps = installedApps
            .asSequence()
            .filter {
                normalizedQuery.isEmpty() ||
                    it.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(normalizedQuery)
            }
            .take(50)
            .toList()

        appListGroup.removeAllViews()
        filteredApps.forEach { app ->
            val radioButton = RadioButton(this).apply {
                text = "${app.label}\n${app.packageName}${if (app.isSystemApp) "\n系统应用" else ""}"
                textSize = 14f
                setTextColor(Color.rgb(24, 32, 41))
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    targetInput.setText(app.packageName)
                    runComparison()
                }
            }
            appListGroup.addView(radioButton)
        }

        resultView.text = when {
            filteredApps.isEmpty() -> "没有匹配的应用。换个应用名或包名搜索。"
            normalizedQuery.isEmpty() -> "已加载 ${installedApps.size} 个应用，当前显示前 ${filteredApps.size} 个。输入关键词可搜索。"
            else -> "找到 ${filteredApps.size} 个匹配应用。点选应用后会自动对比。"
        }
    }

    private fun recentDaysWindow(days: Int): Pair<Long, Long> {
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - days * 24L * 60L * 60L * 1000L
        return beginTime to endTime
    }

    private fun parseDays(): Int {
        val rawValue = daysInput.text.toString().trim().toIntOrNull() ?: 1
        return max(1, rawValue)
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

    private data class SelectableApp(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean
    )
}
