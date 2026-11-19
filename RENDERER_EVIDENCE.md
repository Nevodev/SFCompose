# 实现与验证边界

用户此前复测报告 `airplay.audio` 和 `cellularbars` 的 Draw 端盖错误。
本轮按用户提供的网站源码修正天然端盖的旋转基准与 On 模板选择；这是明确的视觉适应，
不是宣称该网站或新端盖等同系统实现。尚未进行本轮真机验收。
当前基准已改为系统 CoreGlyphs BVG → CoreUI 插值 → RenderBox 调用链。
这两个生产资产已经使用系统编译关键帧，实际字号容差和生产画笔中间帧已加入回归。
原有 SF Symbols 应用内核的同输入比较不能证明系统整体一致；仍需系统窗口与设备验收。
全目录现已采用系统资源；按原生 named selector 重建的三模式图层数据已通过全量验证，
不能将旧应用资源测试描述为全目录系统渲染一致性测试。

公开入口为 `SFSymbol`、`SFSymbolReplace` 和 `SymbolAssetStore`，源码位于
`sfcompose/renderer`。资源的原始身份和哈希以
`app/src/main/assets/symbols/index.json` 及原版函数夹具的 provenance 为准。

| 功能 | 实际调用链 | 主要验证 |
| --- | --- | --- |
| 九字重、三种光学尺寸、完整目录 | 系统 BVG / CSI metrics / 原 CoreUI 插值 → 离线导入 → Android 按需解码 | 8,647 名称、10,467 变体、282,609 命名字重尺寸样本的离线结构与哈希验证 |
| 字号和布局 | 名义字号 / units-per-em 缩放 → 系统 metric center 或字体 metrics 定位 → advance 自然宽度 | 三种尺寸的真实墨迹位置；窄画布不缩小字形 |
| 单色、层级、Palette、多色、双色 | 原 layerTree → 模式选择 → 语义色 / 层级材质 → Canvas | 全目录四种模式绘制；颜色、层级、镂空隔离测试 |
| Shape group | 原 shapeGroupBehavior → Path UNION / DIFFERENCE → 父层材质 | 原复合符号、替换整体路径及布尔路径极值测试 |
| 渐变 | 原角度和对齐注解 → Lab 端色 → 原矩形端点算法 → Shader | 原色表和端点数值、实际 Canvas 渐变 |
| Variable Color | 原 eligible 层 / 顺序 → 阈值或逐层动画轨道 → 材质插值 | 阈值、累计 / 依次 / 往返 / 循环轨道及 Canvas |
| Variable Draw | 系统明确 offset / length，旧资产才使用作者注解顺序 → 未激活底图和激活路径 | 原启用层、半透明像素及未启用符号保持静态 |
| Draw On / Off | 系统编译关键帧直接输入；旧资源仍从三 master 作者注解构造 → 填充边界裁切 / 端盖 / 分叉 / 附件 | 系统实际 13 / 32 / 160 字号输出、生产画笔 On / Off 回归及原合组输出 |
| Draw 调度 | 系统每层自适应时长 → 组内最长层控制延迟 → 原系统时间曲线；旧资源使用应用调度 | 原 RenderBox ARM64 时长、组内 max、ByLayer 起点、On / Off / reverse 和 preset 求值 |
| 运动效果 | 原 motion group / pivot / direction → 原离散或连续轨道 → Canvas | 数值轨道与实际绘制的透明度、质心、覆盖面积 |
| Magic Replace | 原语义 tags → badge / slash / enclosure / draw admission → 每层状态 → 画笔 | 真实复合资产分类、中间 pivot、斜线、颜色描述符及目标像素端点 |

以下范围必须分别理解：

- 系统 `Assets.car` 的 BOM 叶链遍历读出 159,833 条记录，其中 31,401 条为 BVG，
  覆盖 8,647 个名称。全部 BVG 解析成功仅说明格式已覆盖；分组、附件、语言及方向变体
  还必须由导入器与 Android 消费端验证，不能用解析数量代替渲染功能完成度。
- named style 才是系统图层边界。同一路径可以同时属于同模式的多个 selector，
  每个 selector 分别创建图层并读取自己的样式；不能将这些声明作为 CSS 累积覆盖。
  非图层 selector 的路径属性（例如 opacity=0）也不能泄漏为正常填充层的材质。
  新资源以 `systemRenderingMode` 分开保存三种原生模式，Palette 复用 hierarchical。
- 系统图片填充 `_gradient.highlegibility` 的两份原 PNG 已从同一 CAR 提取，
  SHA 和 rendition key 记录于 `symbols/fill-images/index.json`。RenderBox 使用每层路径的
  ink bounds 铺图；Android 使用相同范围，并转换 PNG 向下的行方向和字体向上的 Y。
  `SystemFillImageTest` 验证无需开启自动渐变也能显示原图，并保持同一字形轮廓。
- 部署使用 `.json.sfgz` 保存原 gzip 字节，加载器按 magic 解码。Android 资源合并器会
  自动展开 `.gz` 后缀，导致无谓的空间和堆占用；改用不参与自动展开的后缀保留按需读取。
  `tools/install_system_catalog.py` 核对清单 SHA，记录逻辑路径与部署路径，最后才更新索引。
- 系统 RenderBox 的容差是 `0.5 / sceneScale`，`sceneScale` 来自字号与资源参考字号。
  Android 生产画笔现将实际字号传入裁切；字体单位容差为 `0.5 * unitsPerEm / pointSize`。
  `/draw-system-canvas13-point32-in-font-coordinates.json` 与对应 point160 夹具执行原始
  RenderBox 指令，分别采用真实字号容差；不是将 13 字号图片放大作为参考。
  `SystemRendererPipelineTest` 通过 `AndroidSymbolPainter(adaptiveCaps = false)` 保留 112 个
  原生端盖中间帧对照，覆盖翻转、字号传入和静态同级轮廓。默认画笔的天然端盖采用下述
  网站参考的适应策略，这一策略与原生基线分开验证。两者都不是 macOS 窗口录屏验证。
- 系统 Draw 时长使用原 RenderBox 连续二次函数，组延迟采用组内最大值，层仍使用自己的时长。
  On / Off 延迟倍率分别为 0.18 / 0.38，系统 Bezier 求解误差阈值为原始 float 0.001。
  `system-draw-timing-native.json` 保存机器码 duration、分组与 preset 求值结果；
  其中几何长度由明确 stub 提供，不能称为完整 Glyph 运行结果。
- 系统非 Draw 轨道使用独立的 `SymbolMotionTiming` / `SymbolSystemTiming`，
  对照原 RenderBox emitted tracks 与 Keyframes.eval，保留原 Float 常量、绝对时间减法、
  reciprocal estimate 两次修正和 preset 求解精度；这些都会影响关键帧边界。
  单独的 emitter 结果与完整 Animation::time 调度分开验证，不能把局部轨道当作全时域行为。
- `effectRepeatCount` 为可选正整数，Continuous 模式下执行原 intro、指定次数的 loop 和 outro，
  默认 `null` 保持无限播放。有限收尾按系统时域完成最后一圈，不是捕获任意中间帧立即回零。
  有限运动 360 组和变量色 320 组原 Time / end_time 夹具独立验证，生产画笔还检查有限播放后
  所有运动效果恢复原始像素。此前仅检查 emitter 时跳过的 phase 2 已纳入这些有限播放测试。
  动画到期后撤销 Variable Color 材质覆盖，恢复调用方的静态 variableValue；这对应原
  Animator 移除已结束动画后重新应用 Model 材质，不是永久保持 raw track 的最后一个值。
- 有限循环接口加入前的完整基线：`:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` 成功，
  31 个测试类共 131 项测试，失败、错误、跳过均为零。原始 XML 和构建日志保存在
  `D:/MACOS/analysis/system/final-full-test-baseline`，与后续有限循环接口的增量验证分开记录。
  全目录 10,743 个有编译 Draw 数据的逻辑层均可准备，`remaining` 为空；
  额外空白模式检查仅保留三个原始 iPhone landscape slash 多色模式。
- 最终完整验证再次运行相同三个 Gradle 任务成功：33 个测试类共 135 项，失败、错误、跳过
  均为零，包含有限循环和到期后材质恢复。原始 XML、日志和覆盖摘要在
  `D:/MACOS/analysis/system/final-verification`。Python 工具链 38 项测试通过，明确跳过的
  两个子测试是旧备份目录中已替换为系统资源的 AirPlay / cellularbars 记录，系统测试另行覆盖。
  `git diff --check` 因当前目录无 Git 元数据无法执行；逐文件 no-index whitespace 检查通过。
  JDK 25 的 native-access 警告来自 Gradle / Conscrypt，未作为源代码错误或设备验证通过处理。
  最终 APK 的全部 10,468 个系统文件与导入清单逐一核对 SHA，关键实现类与有限循环参数
  已确认存在 DEX，记录于 `D:/MACOS/analysis/system/final-apk-catalog-verification.json`。
- 五处系统附件数据引用不存在的动态锚点时，已执行原 CoreUI 失败分支和 `_setupEmpty`，
  证实它清空整组附件，仍保留 Draw 关键帧。Android 保留全部原数据并复现此拒绝行为，
  没有将这些情况伪装成正常附件，也没有忽略其它未知解析失败。
- 普通系统渐变范围取原 alignmentRect 的像素向上取整尺寸，随后逆映射回字体坐标。
  16 组原指令夹具验证 CoreUI alignment block、RenderBox Float 转换与 `fill_rect`；
  最后的字体坐标逆转换是主机计算，未声称完整 Glyph 构造已执行。
  动画旋转时系统填充变换跳过角度，路径仍旋转；`SymbolFillTransform` 单独追踪两种变换，
  测试覆盖组合 pivot、非等比缩放、位移、像素渐变方向及共享 Shader 状态恢复。
- 附件几何夹具覆盖两种简单箭头、双箭头分叉与非零出现阈值、同原生图层的分散路径，
  共 160 帧；另有整数下标边界 42 帧及通过真实 Store 载入三模式的 48 帧。
  全目录附件字段扫描不等于 556 个附件名称每个字重的所有帧均已做原生图像对照。

- 完整目录的读取、模式绘制和中间帧不崩溃检查覆盖所有名称；这不等于每个名称、
  每个字重的所有动画帧都与系统逐像素一致。
- 密集裁切夹具执行原始 ARM64 函数，并用同一 Android Canvas 绘制原版命令与移植命令。
  早期应用源夹具覆盖 Regular 全部三种尺寸；当前系统源夹具覆盖 Small / Medium Regular，
  On / Off、多个进度和实际 13 / 32 / 160 字号。两种来源不得混作同一基准。
  允许的 alpha 总误差为参考覆盖量的 1.5% 加四个像素；来源和可重跑输入保留在夹具说明中。
- 三字重 Draw 分组不兼容时，原版本身拒绝导出裁切几何；移植使用原版的淡入和缩放后备行为。
  `draw-coverage.json` 将这些情况记为 `nativeFallbacks`，与未实现或失败记录分开。
- 应用包的 Draw 动画使用 removeOtherSubpaths；系统 RenderBox 的实际调用使用 flags=0。
  系统 compiled 数据绘制按系统调用保留同层其他轮廓；应用包兼容数据仍保留其原调用方式。
- 三个 `iphone.gen*.landscape.slash` 的原始复合多色模式只有隐藏填充和擦除层。
  原版接受这个非空操作列表，输出透明；测试保留这一源数据行为。
- 本地测试使用 Robolectric 原生图形后端；未安装或替换手机应用。
  真实 Android GPU、macOS 应用完整调用链的录屏对照，不能由本地测试替代。
- 系统镜像采用只读 APFS、按需解压资源块和指定虚拟地址区间的提取方式。
  系统实现与应用内实现的对照证据保留在本机 `D:/MACOS/analysis`，不依赖全量展开系统缓存。
- 已独立执行 macOS 27.2.1（26B131）CoreUI 的天然端盖构造和裁切机器码。
  同一批 924 个路径段输出、264 帧与应用内框架逐命令、逐浮点值一致。
  这验证了该测试范围内的系统裁切内核同源；没有把指针认证环境适配等同于修改几何算法。
  上游注解构造、RenderBox 调度与真实窗口输出仍是另外的验证范围。
- 当前目录未触发的通用自定义输入仍有边界：三个以上轮廓的特殊合组、部分跨轮廓 corner
  配对没有通用实现；手动 Draw 进度限制在 0 到 1。完整目录的通过不代表这些额外输入已支持。
- 系统 Symbols → RenderBox 的未指定选项已追到解析端：Draw Off 默认采用 ByLayer、
  reverse 关闭；按符号 defaults 指定的 motion-group 反转是独立字段，未指定时也为关闭。
  调用者可通过 `drawReversed` 与 `drawReversesMotionGroups` 分别控制路径反向和分组反向，
  这两个选项独立传入裁切与时间调度。
- OffUp 的 Draw 替换使用独立分支：旧符号所有层用原 preset 1 在 `.083f` 内淡出，
  不调用旧符号 Draw Off；新符号从零延迟开始 Draw On。此前将无 Draw 注解的旧层交给
  Standard OffUp（立即隐藏），同时让有注解的旧层擦除，导致静态层在第一帧丢失。
  `system-replace-offup-native.json` 的八个透明度采样来自原 RenderBox `0x196b1994c`
  emitter 与原 Keyframes.eval，测试逐 Float 比较，并检查三模式和 Palette 的完整起始像素。
  模型读取和 Draw admission 使用明确 stub；这是原轨道验证，仍不是系统窗口录屏。
  EnclosureMatch 的 OffUp 内部层采用同一淡出分支；fill/stroke 改变时新旧外圈从零时刻
  互补淡入淡出，仍是 `.083f`，不把旧符号 Draw 时长作为新符号延迟。
- 后续端盖核对改用原 BVG 解码和独立路径解析作为输入，不再依赖生产 diagnostic plans：
  216 组源输入的关键帧字节一致，648 组原 JoinStroke 构造均无待连接轮廓，70 组原
  `Layer::append_path` 输出与直接 ClipStroke 一致。后续 RBShape 设置只保存路径和矩阵。
  这些有界几何结果保存在 `D:/MACOS/analysis/system/dyld/RenderBox/system-cap-caller-closure.md`，
  该轮仅闭合几何调用链，未完成真实系统 GPU 画面对照；当前端盖适应策略另见下述验证。
- OffUp 修复后重新运行 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`，
  全部成功；新增回归曾在修复前以旧静态层起始透明度为 0 失败，修复后通过。
  新 APK 已检查 DEX 中包含 `offUpDraw` 分支；未执行手机安装或系统实屏验收。
- Variable Draw 的能力查询现会按需重建准备数据。此前实际字号改变会清空 Draw 缓存，
  新画笔将 `hasGeometry` 的缓存缺失结果永久记为不支持，导致滑块失效；
  不能把字号相关缓存是否命中当作符号是否支持 Draw 的判据。
  回归覆盖共享实例、字号改变后从 Store 缓存重新加载、四种渲染模式的零值/中间值/终值像素，
  新用例在修复前因缓存失效后缺少范围而失败。预览选择手动 Variable Draw 会退出自动
  Draw 和变量色动画，选择 Draw On/Off 会退出手动 Variable Draw，避免自动进度覆盖滑块。
- 后续用户复测仍报告滑块无效，进一步实际浏览器界面回归区分了两种情况：
  `cellularbars` 有原生 Variable Draw 范围，`rainbow` 和 `airplay.audio` 没有。
  原 CoreUI 缺省范围为正无穷，participates 与 RenderBox 模型门控拒绝此情形，
  不会只因具备 Draw 关键帧而自动生成范围。
  用户明确选择后，示例浏览器对这类符号提供标注为“Draw 进度”的手动 Draw On 控制；
  原生 `variableDrawValue` 的无范围行为保持不变。不能将这个预览功能称为原生 Variable Draw。
  Compose UI 回归通过真实 MainActivity 输入名称、选择控件、设置滑块并截图比较，
  覆盖信号条、默认彩虹、AirPlay 和自动 Draw 切回手动；截图在
  `app/build/reports/symbols/browser-variable`。本机 Robolectric 界面验证仍不代替真机验收。
- 往返、依次、连续变量动画：示例浏览器原先将重复播放同时映射为原生 `willLoop` 首尾
  衔接选项。原系统该组合增加一个映射到首组的虚拟末组，导致到达末端时首组额外亮起。
  现在往返模式保留 Continuous 重复播放，但不启用首尾衔接，并同步修正有限播放预览时长。
  库仍保留两个选项的独立语义。实际浏览器在三个转向/循环时刻的截图对照曾在修复前失败，
  修复后与不插入虚拟组的原生时序画笔逐像素一致。
- 天然端盖适应仅针对系统编译 Draw 数据：天然模板直接按未投影的当前裁切弦做旋转缩放，
  Draw On 取实际终点模板，Off 保留起点模板；无天然模板的切线投影与圆帽分支未替换。
  原路径、编译关键帧、附件与时间轨道保持原有消费方式。`SymbolAdaptiveCapTest` 用用户网站
  原函数的 49 个 Draw On 样本在局部端盖范围比较默认画笔，另在三个尺寸、三个字重上检查
  信号条 On / Off 端盖冠部居中；该居中回归在旧投影策略下失败。
  网站本身没有 Draw Off，不能将 Off 几何回归称作网站原生 Off 对照。原生端盖模式和夹具
  仍保留；默认适应端盖不再宣称逐像素复刻原生 cap。网站来源哈希及样本边界保存在
  `app/src/test/resources/symbols/site-adaptive-cap-on.json`。
  用户随后明确要求保留注解定义的端盖形状，包括 cellularbars 中间帧较扁的局部圆弧；
  临时的完整半圆补全实现及其深度断言已撤除，旋转基准修正仍保留。
- Browser 采用原系统分类、符号顺序、别名与关键词，补充应用原版中英分类标题和英文增强词。
  动态 Draw 分类来自原认证解码的作者 ties 可用性；不能称为执行原版侧栏过滤函数。
  搜索的精确名称优先、前缀优先、多词匹配为 Android 消费端策略，未复刻原版排序算法。
  原分类表缺失的名称仍保留于全部，不按名称猜分类。来源、覆盖和导入策略在
  `tools/BROWSER_METADATA.md` 与 browser-catalog 的 source / coverage 中记录。
  实际界面回归覆盖搜索、分类、详情、双选 Replace、交换、拖动时间轴，以及返回时网格位置。
- 动画预览切换 Draw 时的 elapsed 现随符号和效果组合键同步创建，不再依赖异步 effect 归零，
  避免新 Draw 效果在归零前消费旧动画时钟。On / Off 来回切换后的起始像素回归已加入。
- 动画预览默认不指定变量颜色值。此前始终传入 `variableValue=1`，会将 shareplay 等具有
  变量颜色阈值的 secondary 层激活为不透明；启用手动 Draw 后撤掉该值才显得恢复正常。
  现在通过“变量颜色”控件或主动调整颜色滑块启用该值，关闭控件或选择静态恢复源层级。
  渲染器的显式变量颜色语义不变。真实 Compose 界面回归在旧默认值下失败，修复后检查
  shareplay 默认像素、完整手动 Draw、返回静态与颜色开关恢复，截图在 browser-variable 下。
- `SymbolDisplayMode` 独立控制占位与绘制大小：SquareOverflow 保留行高并允许越过方形占位，
  IntrinsicWidth 保持原有自然 advance 宽度，SquareFitWidth 仅在左右轮廓范围放不下时等比缩小。
  缩小后以占位中心定位，实际字号传给 Draw、渐变及 Replace 调度；Replace 使用两端共同范围，
  不随动画帧改变占位和比例。组件本身不添加裁切，祖先的裁切仍适用。
  Compose 原生图形测试检查实测占位、溢出像素、宽符号缩小、窄符号不放大以及 Replace 两端。

- Replace 现在门控尚未开始的目标 Draw 层，并向路径绘制传递完整的原生附件时间状态，
  包括被引用层的进度、时长和 Draw Off 尾段缩放；此前只传标量进度，可能提前漏出静态箭头。
  `person.fill.and.arrow.left.and.arrow.right.outward` 的系统源引用不存在的第二个动态锚点，
  原 CoreUI 会拒绝整组附件但保留 Draw 关键帧。底层原生解析继续保留此行为；默认 Android
  画笔对五处坏数据另建兼容附件 store，从唯一的箭杆终端裁切弦恢复锚点，沿用一致的
  原 sourceParameters。只跳过无 Draw 关键帧且可确认同层互指的 head→shaft 反向记录。
  头部重新进入原 TimedAttachment 求值，包含出现阈值、反向和 Off 尾段；不再对这些符号
  使用整层入退场。未知或歧义输入仍拒绝恢复，资源字节不变。
  该恢复策略是 Android 兼容行为，不是原 CoreUI 对坏数据的行为；`adaptiveCaps=false` 的
  原生基线默认同时关闭附件恢复，底层 pathFor 也能独立指定恢复开关。
  回归覆盖报告中的两个人形符号、四种渲染模式、三个方向、交换 A/B 及首尾静态像素；
  有效附件 `homepod.mini.arrow.forward` 的 Replace 入退场与独立 Draw 同时刻逐像素比较。
  `SymbolAttachmentRecoveryTest` 另检查五符号的三命名字重、三个中间轴位置、三光学尺寸、
  三原模式、实际 13/32/160 字号、原出现阈值与头部运动方向，以及 raw store 缓存隔离。
  序列图位于 `app/build/reports/symbols/replace-attachments`，未执行设备或系统实屏对照。
- 连续字重加载接口读取 catalog 的原始轴范围和命名坐标，路径、metrics、编译关键帧、
  动态锚点和采样参数一起插值，精确命名字重继续使用原样本。缓存的源文档只读共享，
  每个实例的 layerTree 独立绑定；文档缓存 8 项、实例缓存 48 项，不按拖动次数无限保留。
  `SymbolContinuousWeightTest` 对中间轴、精确命名位置、Draw 附件与缓存边界做回归；
  真实预览滑块三个非命名位置也与对应画笔逐像素一致。此处未执行 CoreUI 任意字重的
  完整运行时对照，不能将插值可用等同于所有中间字重均已逐像素验收。
- 自动资源选择读取 Android locale/night 与 Compose LayoutDirection，保留显式 variantId
  最高优先级。方向 token 4/5 的原表含义是 Left/Right；CSI header +8 的 bit6 经原构造函数
  映射为 rendition.isFlippable。新增独立 sidecar 保留全部 10,467 变体的原头部核查结果，
  含 708 个可镜像变体和 135 对实际存在的 `.rtl` 资源，原 gzip 矢量文件未重写。
  普通加载不读取 sidecar，自动选择时才惰性加载并校验源 CAR 身份。实例缓存区分实际
  资源名、变体和镜像状态。`arrow.forward` 在 RTL 镜像，`arrow.right` 保持字面方向；
  `airtag.forward.fill` 使用真实 RTL 资源，避免双重翻转。测试另覆盖 locale 回退、深色、
  显式覆盖和 Compose 环境变化。locale/script/language 的回退与键优先级为 Android
  消费策略，尚未执行完整 CoreUI matcher。原头部 offset/SHA 与原函数证据分别保存在
  `D:/MACOS/analysis/system/resources/CoreGlyphs/system-resource-selection-header-audit.json` 和
  `D:/MACOS/analysis/system/dyld/CoreUI/system-resource-selection-evidence.json`。
- `SymbolReplaceController` 在统一绝对时钟上接收目标变化。返回前一目标时倒走同一对
  原 recipe 时域；改向第三目标时捕获当前逐层矢量路径、材质和变换，并按原 Standard
  入场分支显示新目标。中断后的出场保留已有 deadline，完成帧自动清理旧呈现组合。
  不使用截图交叉淡化，也不保留嵌套历史树；SquareFitWidth 尺寸变化补偿既有呈现尺度。
  回归检查瞬间像素连续、快速三目标、返回/续行、暂停时重画、尺寸变化和完成清理。
  该控制器保证已测位置、形状与透明度连续，未建立系统中断时速度导数和全部 recipe 的一致性。
- 本轮四项功能验证：完整运行 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`，
  编译与 APK 打包成功，47 类 182 项用例中 180 项通过。镜像的逐像素严格断言与空界面配置
  等待用例失败后，仅修正测试的抗锯齿误差检查和异步观察方式，生产代码未改变；两个测试类
  定向复测 5 项全部通过。全量与复测结果分开保留，不将后者写成整套再次运行。
  更早的交互界面测试因冻结假时钟时等待滚动触发 OOM，该次运行已取消；预览完成后停止
  驱帧、测试在暂停状态恢复滚动时钟后，完整回归中的交互用例已通过。Python 元数据导入
  2 项测试通过。保留 Gradle/Conscrypt 的 JDK native-access 与 Git 换行提示；未安装手机应用。

- Cutout 已按原 RenderBox presentation 拆分 drawing、erasing、common 三通道。
  绘制透明度为 `drawing * common * materialOpacity`，颜色自身 alpha 只影响填充。
  无 clearBehind 时擦除为零；材质 opacity 正值时擦除为 common；透明材质时为
  `erasing * common`。完整擦除使用 COPY，部分擦除先 DST_OUT 再填充；不把 tint、
  普通 hierarchy 或填充 Shader 的 alpha 乘入镂空覆盖。层级与材质透明度也分别插值后相乘。
  `system-layer-opacity-native.json` 的 216 组 getter 与四模式 1,024 组 Pulse 结果执行原 ARM64
  有界指令，测试逐 Float 位比较。Pulse 只有显式注解且 ByLayer 时调制 erasing，
  WholeSymbol 或无显式注解时保留擦除覆盖；Replace / Appear / Draw fallback 的 drawing
  淡出仍按原零值门控处理，不将其误当 common。四模式实际 Canvas 测试检查半透明 tint、
  hierarchy、正材质、透明材质、通道组合和 Pulse 注解。
- Variable Color 主段使用原 property 8 的 SetBoth 与可选 property 9 的 max；连续 intro/outro
  使用 property 10，分别将既有 drawing / erasing 插值向 inactive。此前统一用“活动比例”
  混合基线会错误保留主段 hierarchy。`system-variable-opacity-native.json` 含 1,656 组原
  emitter / Animation::time / Keyframes.eval / apply 有界结果及五种通道基线，共 8,280 组。
  存活轨道比较原输出；夹具额外求值的 end_time 之后样本检查撤销覆盖、恢复静态模型，
  不把已结束 raw outro 的数值当作系统仍在显示的动画。生产画笔另检查主段不受旧 hierarchy
  限制。模型的 inactive getter、内存与绘图后端使用明确 stub，此结果不代表系统窗口验证。
- Cutout 几何走自身的原 `Layer::clip` → pathParams / matrix → set_shape，沿用其 Draw 与附件。
  `arrow.up.right.circle.fill` 的透明层包含动态附件，镂空箭头随之移动；
  `homepod.mini.arrow.forward`、`rectangle.portrait.and.arrow.forward` 的独立镂空层没有这些
  数据，保持静止。不能只因同属一个 motion group 就强行跟随前景箭头。
  `system-draw-cutout.json.gz` 保存 16 帧原始路径命令；测试在 On/Off、reverse、三种 tint alpha
  下比较生产画笔，并检查上述两类独立静态 mask。原 clip 参数传递另以 12 组有界调用验证。
- Enclosure Replace 按原 subtype 区分：fill/stroke 标记不同不创建材质 interpolation 记录；
  同标记先按 clearBehind、material index、命名颜色检查配对。透明材质 index 10，
  mono/hier 正材质 index 0，Palette 的 primary/secondary/tertiary 为 0/5/6，Multicolor
  tintColor 为 0、其它命名色为 9 且要求同名。不匹配时保留两侧描述符，以 common 互补淡入淡出；
  配对时分别插值材质与 presentation 通道，不先乘合成 alpha。材质记录直接采用原
  Animation::time 的归一化线性进度，不套用运动效果的 Keyframes 缓动。原 LCS 前后缀筛选已确认外圈
  标志 bit3 参与匹配；8,648 份资产扫描确认当前 enclosure 无 image/gradient/custom 材质，
  不对未覆盖的外部自定义描述符推测配对。原 104 组 dispatch / pair / interpolation 采样和扫描证据
  位于 `D:/MACOS/analysis/system/dyld/RenderBox/system-common-opacity-interpolation-native.json`
  与 `system-enclosure-material-index-findings.md`；记录中明确保留 stub 与已执行分支范围。
  Replace 测试覆盖 None 与正材质、Palette slot、不同命名色、common 淡入淡出及实际符号端点。
- Cutout 最终验证完整运行 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`
  成功，51 类 200 项测试全部通过，失败、错误、跳过均为零；包含最后的线性材质插值修正。
  早一轮完整运行的两项 Palette Pulse 旧断言预期为不透明绿色，按四模式原 property 12 的
  指令结果修正为 alpha 76，定向复测及最终完整回归均通过。各轮结果分别归档，不混合 XML。
  最终证据为 `D:/MACOS/analysis/system/cutout-final-20261118-083654`，APK 的 10,473 个
  symbols 资源与工作区逐一校验一致，DEX 包含新通道及材质匹配实现；SHA-256 为
  `af15d89420dae1f7049c808627bea2a6c5e432a3eb14d31f499f1e02af319bbf`。
  `git diff --check` 通过；保留已有 JDK native-access 与 Git 换行提示，未安装手机应用，
  未进行系统窗口或真机 GPU 逐像素验收。

输出报告位于 `app/build/reports/symbols`，包括完整目录 Draw 覆盖、意外空白模式检查、
端盖回归图和原版裁切对照图。Python 资源导入检查位于 `tools/test_import_symbols.py`，
Android 运行路径检查位于 `app/src/test/java/com/nevoit/sfcompose/symbols`。
