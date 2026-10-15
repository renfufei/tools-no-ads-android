# 刘海屏 short-edge mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 API 28+ 刘海屏设备上让刘海区域不显示应用内容（toolbar 与 nav host 均位于刘海安全区下方）。

**Architecture:** 仅新增 `values-v28/themes.xml` 资源文件，覆盖 `Theme.ToolsCNC` 追加 `android:windowLayoutInDisplayCutoutMode = shortEdges`。配合既有 `activity_main.xml` 根布局 `fitsSystemWindows="true"`，系统将刘海安全区作为 top inset 应用到根布局，`MaterialToolbar` 经 `fitsSystemWindows` 链吃到 top inset 被推到刘海下方。

**Tech Stack:** Android resources (XML theme override), no code changes. minSdk 24, targetSdk 35, AGP 8.3.0.

## Global Constraints

- 纯 Java，无 Kotlin（本次无 Java 改动）
- minSdk 24，刘海相关属性只在 API 28+ 生效，故使用 `values-v28` 限定
- 不改 `MainActivity.java`、`activity_main.xml`、`AndroidManifest.xml`、`values-night/themes.xml`
- 输出路径与 ProGuard 等既有约束不受本改动影响

---

### Task 1: 新增 values-v28 themes 覆盖

**Files:**
- Create: `app/src/main/res/values-v28/themes.xml`

**Interfaces:**
- Consumes: 既有 `values/themes.xml` 中的 `Theme.ToolsCNC` style
- Produces: API 28+ 设备上 `Theme.ToolsCNC` 增加 `windowLayoutInDisplayCutoutMode = shortEdges` 属性

- [ ] **Step 1: 创建资源目录与文件**

创建 `app/src/main/res/values-v28/themes.xml`，内容：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ToolsCNC" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryVariant">@color/primary_variant</item>
        <item name="colorOnPrimary">@color/on_primary</item>
        <item name="colorSecondary">@color/secondary</item>
        <item name="colorSecondaryVariant">@color/secondary_variant</item>
        <item name="colorOnSecondary">@color/on_secondary</item>
        <item name="android:colorBackground">@color/background</item>
        <item name="colorSurface">@color/surface</item>
        <item name="colorOnBackground">@color/on_background</item>
        <item name="colorOnSurface">@color/on_surface</item>
        <item name="colorError">@color/error</item>
        <item name="colorOnError">@color/on_error</item>
        <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
    </style>
</resources>
```

说明：完整复制 `values/themes.xml` 的全部属性再追加 cutout 属性，因为 Android 资源覆盖机制是按 style 整体替换而非合并——只写新增属性会丢失基色配置。

- [ ] **Step 2: 验证资源合法性**

Run: `./gradlew processDebugResources` 或 `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，无资源合并冲突。若报 `windowLayoutInDisplayCutoutMode` 找不到，检查是否落在 `values-v28` 而非 `values`。

- [ ] **Step 3: 构建验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，产出 debug APK。

- [ ] **Step 4: 提交（如适用）**

本环境非 git 仓库（`Is a git repository: false`），跳过 commit。若在 git 仓库中：

```bash
git add app/src/main/res/values-v28/themes.xml
git commit -m "feat: 支持 API 28+ 刘海屏 short-edge 模式"
```

---

## 验证（手动，无自动化测试）

项目当前无测试套件（CLAUDE.md 明确："No tests exist yet"）。此改动为纯资源声明，靠手动验证：

- **API 28+ 刘海屏设备竖屏**：装上 debug APK，确认 toolbar 位于刘海下方，刘海区域不显示 toolbar 文字或 nav host 内容。
- **API 24/27 无刘海设备**：确认行为与改动前一致。
- **横屏（API 28+）**：确认 `shortEdges` 生效，内容可延伸但 toolbar 仍位于 top inset 下方。

## Self-Review

- **Spec coverage**: spec 中"新增 `values-v28/themes.xml`"→ Task 1 Step 1；"验证"→ Step 2/3 + 手动验证节；"不改 ..."→ 仅 Create 一个文件，符合。
- **Placeholder scan**: 无 TBD/TODO；步骤含完整 XML。
- **Type consistency**: 无跨任务类型引用，单一任务。
