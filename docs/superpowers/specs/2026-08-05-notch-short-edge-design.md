# 刘海屏 short-edge mode 设计

**日期**: 2026-08-05
**状态**: 已批准

## 背景

当前 `activity_main.xml` 根布局带 `fitsSystemWindows="true"`，但 theme 未声明 `windowLayoutInDisplayCutoutMode`。在 API 28+ 刘海屏设备上，系统默认模式（`default`）行为依赖 OEM 实现，横屏表现不一致，且未显式声明意味着依赖默认——不可靠。

用户需求："刘海屏部分不要显示内容"，即 toolbar 与所有内容不应被刘海遮挡。

## 目标

在 API 28+ 刘海屏设备上，刘海区域不显示应用内容（toolbar 与 nav host 均位于刘海安全区下方）。

## 方案

新增 `app/src/main/res/values-v28/themes.xml`，覆盖 `Theme.ToolsCNC`，追加：

```xml
<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
```

`shortEdges` 允许窗口在所有边延伸到刘海区，但配合现有 `fitsSystemWindows="true"`，系统将状态栏/刘海安全区作为 top inset 应用到根布局；`MaterialToolbar` 通过 `fitsSystemWindows` 链吃到 top inset，被推到刘海下方，刘海区域不显示 toolbar 内容。Nav host 位于 toolbar 下方（`app:layout_constraintTop_toBottomOf="@id/toolbar"`），同样不进入刘海区。

## 为什么是 `values-v28`

`windowLayoutInDisplayCutoutMode` 是 API 28 引入的属性。`values-v28` 限定只在 API 28+ 生效；API 24/25/27 无刘海概念，使用基础 `values/themes.xml` 即可。`values-night/themes.xml` 与 `values-v28` 正交，系统会合并资源——夜间模式下 short-edge 属性同样生效。

## 不变之处

- 不改 `MainActivity.java`
- 不改 `activity_main.xml`（`fitsSystemWindows="true"` 保留）
- 不改 `AndroidManifest.xml`
- 不改 `values-night/themes.xml`

## 验证

- API 28+ 刘海屏设备竖屏：toolbar 位于刘海下方，刘海区域不显示内容。
- API 24/27 无刘海设备：行为与现状一致。
- 横屏：`shortEdges` 允许内容延伸，但 toolbar 仍吃 top inset——符合短边模式语义。

## 风险

极低。唯一新增是一个资源文件、一个属性。`shortEdges` 在竖屏下与系统默认 `default` 行为相近，但显式声明消除不确定性，并为横屏提供一致行为。

## 实施步骤

1. 创建 `app/src/main/res/values-v28/themes.xml`，内容为覆盖 `Theme.ToolsCNC` 并追加 `windowLayoutInDisplayCutoutMode = shortEdges` 的 style。
2. 构建 `./gradlew assembleDebug` 验证编译通过。
