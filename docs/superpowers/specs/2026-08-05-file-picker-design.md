# 应用内文件选择器 (FilePickerFragment) 设计

**日期**: 2026-08-05
**状态**: 已批准

## 背景

ZIP 压缩页 `CompressFragment` 用系统 SAF picker（`ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`）选文件。用户反馈：picker 无勾选框、无"确定"按钮、不能直观多选。应用已有完整应用内文件浏览组件（`ArchiveFragment`/`FileAdapter`/`FileItem`/`item_file.xml`，含 checkbox）。

## 目标

新增独立可复用的应用内文件选择器 `FilePickerFragment`，全文件系统浏览，checkbox 多选 + 确定 回传路径列表，替换 `CompressFragment` 的 SAF 调用。

## 架构

新增独立 `FilePickerFragment`（复用 `FileAdapter`/`FileItem`/`item_file.xml`），新增 `FilePickerViewModel`（基于 `ArchiveViewModel` 裁剪：仅保留目录导航 + 多选），新增 `fragment_file_picker.xml`（基于 `fragment_archive.xml` 裁剪：去 FAB 与压缩/解压按钮，加"确定"按钮与已选计数）。`CompressFragment.btnAddFiles` 改为导航到 `FilePickerFragment`；确定后通过 Navigation `SavedStateHandle` 回传 `List<String>` 路径，`CompressFragment` 观察并交给 `CompressViewModel.addEntriesFromPaths`。

## 组件

| 组件 | 职责 |
|---|---|
| `FilePickerFragment` | UI：列表、目录导航、多选、确定回传 |
| `FilePickerViewModel` | 当前目录、文件列表、多选状态、`getSelectedPaths()` |
| `fragment_file_picker.xml` | 路径栏 + RecyclerView + 底部"确定"（含已选计数） |
| `CompressViewModel.addEntriesFromPaths(Context, List<String>)` | 路径→`Uri.fromFile`→调 `addEntries` 去重追加 |
| `CompressFragment` | `btnAddFiles` 改导航；订阅 `SavedStateHandle["picked_paths"]` |

## 数据流

1. `CompressFragment.btnAddFiles` → `navigate(action_compress_to_picker)`
2. `FilePickerFragment` 显示，浏览目录、勾选（checkbox 始终可见，点击即切换，无需长按——`multiSelectMode=true` 并保持）
3. 确定 → `getSelectedPaths()` → `SavedStateHandle["picked_paths"] = paths` → `popBackStack()`
4. `CompressFragment` `onViewCreated` 注册 `currentBackStackEntry.savedStateHandle.getLiveData("picked_paths")` 观察者 → 收到后 `clear()` 防重复 → `addEntriesFromPaths(...)`

## 关键决策

- **回传载体**：`SavedStateHandle` + `List<String>` 路径。`File`→`Uri.fromFile`（`file` scheme），`StorageHelper.copyUriToCache` 用 `ContentResolver.openInputStream` 支持 `file` scheme，无需改 `copyUriToCache`。
- **checkbox 始终可见**：picker 模式 `multiSelectMode=true` 并保持，复用 `FileAdapter` 既有逻辑。
- **权限**：API 30+ 浏览外置存储需 `MANAGE_EXTERNAL_STORAGE`，已声明。CompressFragment 不再依赖 SAF。
- **起始目录**：`Environment.getExternalStorageDirectory()`。
- **去重**：`addEntriesFromPaths` 转 `Uri` 后调既有 `addEntries`，复用其 `existingUris` 去重。
- **移除 SAF launcher**：删 `CompressFragment.filePickerLauncher`/`openFilePicker()`/`handleSelectedFiles()`。

## 边界与错误

- 无读权限目录：`ArchiveViewModel.navigateUp` 已处理 `canRead`。
- 选 0 个：禁用"确定"按钮。
- 返回不点确定：压缩页列表不变。
- 重复添加：去重。
- 进程死亡重建选中丢失：`FileItem.selected` 为实例字段，ViewModel 重建丢——属既有缺陷（图片 Fragment 同类），暂不修。

## 测试（手动）

- 压缩页 → 添加文件 → picker → 勾选多个 → 确定 → 列表更新、计数正确。
- 选 0 个时确定禁用。
- 返回不点确定：列表不变。
- 重复添加同一文件：去重。

## 不变之处

- 不改 `ArchiveFragment`、`FileAdapter`、`FileItem`、`item_file.xml`、`ArchiveViewModel`
- 不改 `StorageHelper`、`ZipHandler`、`CompressEntry`
- 不改图片工具相关代码

## 实施步骤（高层）

1. 新增 `FilePickerViewModel`（裁剪自 `ArchiveViewModel`：去压缩/解压，加 `getSelectedPaths()`，默认 `multiSelectMode=true` 并保持）。
2. 新增 `fragment_file_picker.xml`（裁剪 `fragment_archive.xml`：去 FAB/压缩解压，加确定按钮 + 计数）。
3. 新增 `FilePickerFragment`（复用 `FileAdapter`，确定按钮回传 `SavedStateHandle`）。
4. `nav_graph.xml` 加 `compressFragment → filePickerFragment` action。
5. `strings.xml` 加 `file_picker_confirm`、`file_picker_selected_count`、`file_picker_title`。
6. `CompressViewModel` 加 `addEntriesFromPaths(Context, List<String>)`。
7. `CompressFragment` 改 `btnAddFiles` 为导航；删 SAF launcher；订阅 `SavedStateHandle`。
8. 构建 `./gradlew assembleDebug` 验证。
