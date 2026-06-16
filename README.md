# Usage Time Compare

一个最小 Android 测试应用，用来对比同一时间窗口内：

- `UsageStatsManager.queryUsageStats()` 返回的系统汇总前台时长
- `UsageStatsManager.queryEvents()` 事件流水自行累加的前台时长

## 本地打包

本机需要 Android SDK、JDK 17、Gradle：

```bash
gradle assembleDebug
```

生成的 APK 在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions 打包

把项目推到 GitHub 后，进入仓库的 `Actions` 页面，运行 `Build APK` workflow。

构建完成后，在该次 workflow 的 `Artifacts` 里下载：

```text
usage-compare-debug-apk
```

## 使用方式

1. 安装 debug APK。
2. 打开应用，按提示授予“使用情况访问权限”。
3. 输入目标应用包名，例如 `com.tencent.tmgp.sgame`。
4. 点“重新对比”查看两种 API 的统计差异和事件诊断信息。
