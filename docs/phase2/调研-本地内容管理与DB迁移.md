# 调研 · 二期方向 D：本地内容管理与 DB 迁移安全（WP-D reader-data）

> 调研基线：Git mvp-v0.1.0（MVP 已验收）。证据均为源码实读，路径相对仓库根；测试基线见 §0。
> 依据文档：docs/03-数据模型.md v1.0（重点 §2/§3/§6/§9）、docs/02-接口契约.md v1.0.3（§7/§7.1/§13）。
> 篇幅约束 ≤300 行；每项含：现状结论 / 差距 / 建议方案 / 影响文件 / 风险 / 工作量粗估（S≤2d，M≤5d，L>5d）。

## 0. 基线验证（真实执行）

标准通道执行 `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*" bash scripts/run-unit-tests.sh --tests "com.mvxxcb.reader.data.*"`：
**BUILD SUCCESSFUL，29/29 通过，0 失败 0 跳过**（ContentCacheManagerImplTest 6 + SettingsDataStoreTest 4 + ChapterAppendTest 6 + MergeAlgorithmTest 10 + ProgressAndCascadeTest 3；JUnit XML 位于 D:/reader-jianyue-verify/app/build/test-results/testDebugUnitTest/，时间戳 2026-11-09 23:37）。二期改造以此为回归基线。

## 1. 批量章节缓存 WorkManager 化

- **现状**：批量缓存为纯进程内实现——`data/cache/ContentCacheManagerImpl.kt:60-139` cacheChapters 以 channelFlow 串行抓取，同书互斥靠进程内 `activeBatches: ConcurrentHashMap<Long, Job>`（L57-68），进程死亡即丢批次、无断点续传。prefetchNeighborhood 已被 `presentation/reader/ReaderViewModel.kt:589` 使用（radius=1）；**cacheChapters 目前全工程无任何 UI 调用点**（grep 仅实现/契约/测试命中），功能完整（6 个单测）但未暴露入口。WorkManager 侧为骨架：`data/work/ChapterDownloadWorker.kt:21-25` doWork 无条件 `Result.success()`；`data/work/CacheWorkScheduler.kt:18-29` 仅 enqueueUniqueWork(KEEP)/cancel，无约束、无退避、无前台。依赖 work-runtime-ktx 2.9.1 已就绪（gradle/libs.versions.toml:17、app/build.gradle.kts:94）。
- **差距**：① 进程死亡丢任务（骨架注释自认）；② 无前台/通知权限：AndroidManifest.xml 仅 INTERNET/ACCESS_NETWORK_STATE，无 FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS；③ **Worker 传不进章节清单**——WorkManager Data 官方上限 10KB，万章 LongArray（约 80KB）必炸，buildInputData 现约定不可用；④ 失败清单 CacheBatchReport.failures 仅存活于事件流，无持久化可重试；⑤ Worker 取依赖需经 AppContainer（ReaderApplication.kt 单例容器，可行）。
- **建议方案**：
  1. inputData 只传 bookId（+可选范围），Worker 启动后从 DB 派生待缓存清单（getChapterMetaRows 过滤 isCached=0），彻底绕开 10KB 限制；
  2. 激活 Worker：把 ContentCacheManagerImpl.kt:74-112 的串行循环体抽为内部 suspend 批量核心，进程内 Flow 与 Worker 双入口复用（02 §7.1 接口签名不动）；网络/解析失败 `Result.retry()` + setBackoffCriteria(EXPONENTIAL)；完整失败清单由"未缓存章节"这一 DB 真值天然表达，重试 = 重新入队（APPEND_OR_REPLACE）；outputData 只写摘要（成功/失败数）；
  3. 进度上报零新机制：Worker 经 chapterRepository.saveContent 写库 → observeCacheStats（Impl L170-175）已响应式驱动 UI，无需 WorkManager setProgress；通知栏进度由 Worker 周期 setForeground(ForegroundInfo, foregroundServiceType=dataSync) 更新；
  4. 清单/权限：补 FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS；targetSdk 35 注意 Android 15 对 dataSync 前台型有 6 小时配额，超长批次须按书分段入队；
  5. 取消链：CacheWorkScheduler.cancelBookDownload（已有）+ cancelActiveBatch 兜底；UI"缓存全部/后 N 章"入口属 WP-U 配套工时，data 侧可先行。
- **影响文件**：data/work/ChapterDownloadWorker.kt、data/work/CacheWorkScheduler.kt、data/cache/ContentCacheManagerImpl.kt、di/AppContainer.kt（暴露 scheduler）、AndroidManifest.xml。
- **风险**：中。WorkManager 默认经 ContentProvider 初始化、早于 Application.onCreate，Worker 内须懒取 container；dataSync 配额限制超长任务；KEEP 策略下同书旧任务携带旧范围需文档化。
- **工作量**：M。

## 2. 缓存管理页与正文文件化迁移

- **现状**：数据能力齐备但无任何 UI——observeCacheStats / clearBookCache / clearAllCaches / verifyBookIntegrity / cancelActiveBatch 全部实现并测过（ContentCacheManagerImpl.kt:170-220），grep ui/** 无缓存管理入口；正文全量存 chapters.content TEXT（data/db/entities/ChapterEntity.kt:46），统计走 SUM(LENGTH(content))（ChapterDao.kt:160-171）。备份规则注释已预留口径："正文大字段若二期迁移文件存储，届时再评估纳入范围"（res/xml/backup_rules.xml）。ChapterDao **无"清理失效章节（isMissing）"查询**（03 §9 出路之一）。
- **差距**：① 缓存管理页（全局 + 按书）无 UI；② 03 §9 预留的"迁 files 目录 + DB 存路径/状态"未落地；③ isMissing 清理入口缺失。
- **建议方案**：
  1. UI（WP-U 主工时，data 零改动）：SettingsScreen 加"缓存管理"入口（总量统计、clearAllCaches + 确认、verifyBookIntegrity 损坏清单展示）；BookDetailScreen 加按书缓存条（observeCacheStats 进度 + clearBookCache + "清理失效章节"）；
  2. 清理失效章节：ChapterDao 新增 `DELETE FROM chapters WHERE bookId=:bookId AND isMissing=1`，事务内同步清理其 reading_progress/bookmarks 残留（chapterId 已失锚）；
  3. 文件化触发条件（03 §9 细化）：默认不迁移；单书 totalChars > 10MB 或全库 reader.db > 200MB 时，该书**新缓存**章节写 `files/content/<bookId>/<chapterId>.json`，旧章惰性迁移（下次重缓存时搬家），避免一次性大迁移；
  4. 列语义：迁移加 `chapters.contentPath TEXT NULL`、`chapters.contentSource INTEGER NOT NULL DEFAULT 0`（0=content 列内联，1=文件）；loadContent 读序：contentSource=1 先读文件（文件缺失回退 content 列并记 WARN）→ 旧数据 content 列直读，**旧数据零重写**；verifyBookIntegrity 扩展文件分支（文件 SHA-256 vs contentHash）；
  5. 删除语义：clearBookCache/deleteContent 增加文件删除；删书时整目录清 files/content/<bookId>/。
- **影响文件**：ChapterDao.kt、data/repository/ChapterRepositoryImpl.kt、data/cache/ContentCacheManagerImpl.kt、entities/ChapterEntity.kt、AppDatabase.kt（v2，见 §7c）、ui/settings、ui/bookdetail（WP-U）。
- **风险**：中高。文件与 DB 双状态一致性（写文件成功后 DB 更新失败 → 孤儿文件，以"文件为准重试"收敛）；备份范围随之变化（backup_rules 须纳入 content/ 或明确排除，联动 §5）。
- **工作量**：UI S；文件化 M。

## 3. TXT 本地导入

- **现状**：完全空白——导入链路只有 URL（domain/service/ImportService.kt:30-36 仅 importFromUrl / parseForTemporaryRead）；ImportViewModel/ImportScreen 无文件选择（全工程无 ActivityResultContracts，WP-U 调研同证）；编码探测 EncodingDetector.detectAndDecode(bytes, contentTypeCharset, ruleEncoding)（parser/util/EncodingDetector.kt:38-42）可直接复用（前两参传 null 即走 BOM→字节探测→GBK 兜底链）。
- **差距**：① 无 SAF 文件读取；② 无章节切分；③ **入库结构障碍：books.sourceUrl/normalizedSourceUrl NOT NULL 且 normalizedSourceUrl UNIQUE，sourceDomain NOT NULL（BookEntity.kt:30-32），TXT 无 URL**。
- **建议方案**：
  1. 去重键合成（关键设计，待主 Agent 批准）：`normalizedSourceUrl = "local:txt/<文件 SHA-256 前 32 hex>"`、sourceDomain="local"、sourceEncoding 记探测结果——零迁移复用全部合并逻辑，同文件重复导入自动合并（内容寻址天然幂等）；章节 url 合成 `local:txt/<bookHash>/ch<i>`；
  2. 编码探测改头采样：EncodingDetector 现收全量 ByteArray，数百 MB 文件不可整读；新增 `detectFromHead(stream)` 变体（读前 64KB 判 charset），正文改 InputStreamReader 流式逐行；
  3. 切分策略：逐行扫描，标题行正则 `^\s*(第[0-9零一二三四五六七八九十百千万两]+[章节回卷集部篇]|Chapter\s+\d+|序章|楔子|尾声|番外)`；零命中 → 整本单章 + UI 提供"按大小切分（每 N KB）"兜底；段落 = 非空行（ContentBlock.Paragraph，ContentBlockCodec 序列化，复用 §8 格式与 contentHash）；
  4. 人工干预：导入预览页列切分结果（章数/首末章标题），支持合并相邻/拆分/改名，确认后入库；
  5. 入库路径：构造 ParsedCatalog（ChapterRef.url=合成 URL）走既有 replaceCatalog 合并通道，saveContent 分批事务（约 200 章/批，万章书避免单事务过大）；单章场景用 02 §13.3 appendChapter；
  6. 形态：新建 parser/local/TxtImporter（纯 Kotlin 可单测）+ domain/service LocalImportService；ImportScreen 加"导入本地 TXT"按钮（SAF OpenDocument），与 URL 导入并列而非混入同一 Flow。
- **影响文件**：新 parser/local/**、domain/service/**、presentation/importing/ImportViewModel.kt、ui/importing/ImportScreen.kt、di/AppContainer.kt。
- **风险**：中。大文件流式内存控制；切分启发式误判率需真实语料验证；本地书默认 isTemporary=false 需明确。
- **工作量**：M~L。

## 4. EPUB 导入

- **现状**：空白（§3 全部差距适用）；可用底座：jsoup 已在依赖（app/build.gradle.kts:99，可解析 XHTML）、java.util.zip JDK 自带；无任何 EPUB 代码。
- **差距**：无容器解析（container.xml→OPF→spine/NCX）、无 zip 抽取安全、无元数据/封面提取。
- **建议方案**：
  1. 依赖选型评估：主流 Java 库 epublib（psiegman 维护 fork）为 **LGPL 且上游长期半停滞**，LGPL 打包进 Android APK 合规成本高，不推荐；其余候选多为 GPL/商业授权或停更，**Apache-2.0 无成熟可选库，结论：自研最小解析器**（ZipFile + XmlPullParser 解析 container.xml → OPF metadata/manifest/spine → NCX/nav 目录，约 600-900 行，风险收敛且零许可证负担）；**任何第三方依赖引入仍须主 Agent 批准（纪律）**；
  2. zip 安全：Entry 名 `../` 穿越校验（对齐 security 层风格）+ 解压总大小上限（量级对齐 NetworkLimits）；
  3. 章节→ContentBlock：spine XHTML 逐章经 jsoup 取 body → Paragraph；一期**文字优先**：图片块跳过并计入 warnings（ContentBlock.Image.v 需 URL，内部图片需落文件 + 本地路径方案，可与 §2 files/content 布局共用，列二期后半任务）；
  4. 元数据：dc:title/dc:creator → ParsedBookDetail；封面 manifest properties="cover-image" → 解出文件存 files/covers/<bookHash>.png，Book.coverUrl 记 "local:..."（Coil 加载路径适配为决策点，见 §9.6）；
  5. 去重键：`local:epub/<OPF identifier 优先，缺省用文件 SHA-256>`；
  6. 接口：与 TXT 共用 LocalImportService（format 参数区分），导入页"导入本地书籍"单入口。
- **影响文件**：新 parser/local/epub/**、domain/service/**、ImportViewModel/ImportScreen、AppContainer.kt。
- **风险**：中高。EPUB 包体方言驳杂（畸形 OPF/DRM 残留）需 fail-fast + 明确报错；自研解析无上游测试集，需自备样本书回归。
- **工作量**：L。

## 5. 数据备份与恢复

- **现状**：系统级自动备份已配置——AndroidManifest.xml `allowBackup=true` + res/xml/data_extraction_rules.xml / backup_rules.xml（DB 与 DataStore 默认纳入云备份/设备迁移，仅排除 file cache/）；DataStore 两实例文件位于 files/datastore/ 在备份范围内。**无任何手动导出/恢复能力**（全工程无 SAF/zip 代码）。
- **差距**：① 手动备份（换机/防丢）缺失；② §2 文件化落地后 files/content/ 不在排除清单 → 自动备份体积暴涨，须同步调整规则；③ 无恢复流程与覆盖语义。
- **建议方案**：
  1. 范围：reader.db（连同 -wal/-shm：导出前执行 `PRAGMA wal_checkpoint(TRUNCATE)`，经 DAO @Query 暴露）+ reader_settings / app_preferences 两个 .preferences_pb + files/content/**（若已文件化）+ manifest.json（app 版本/schema 版本/导出时间/条目计数）；
  2. 格式：单 zip——SAF CreateDocument("application/zip") 导出、OpenDocument 恢复；zip 内相对路径固定，恢复按清单校验；
  3. 恢复策略：二期只做**"整体替换"**——确认对话框展示"将覆盖现有 N 本书 / M 条进度 / K 条书签"，恢复后提示重启应用（进程内 Room 连接无法热替换）；规则导入已有独立通道（SourceRuleRepository.importRules），不并入整体备份；"按书合并"冲突语义复杂（normalizedSourceUrl upsert + 书签去重），留三期；
  4. 隐私：备份含完整阅读记录/书签摘录，导出与恢复界面均提示"含个人阅读数据，请妥善保管"（01 隐私最小化）；
  5. 形态：新 data/backup/BackupManager（Context 可注入可单测）+ SettingsScreen 两入口。
- **影响文件**：新 data/backup/**、ui/settings/SettingsScreen.kt、data/db/dao/**（checkpoint 查询）、data_extraction_rules.xml（文件化后调整）。
- **风险**：中。恢复中途失败留半套数据（先解压到临时目录并校验 manifest，再原子替换）；版本回滚（新版导出→旧版恢复）由 manifest 校验拒绝。
- **工作量**：M。

## 6. 阅读历史管理

- **现状**：无独立历史数据——打开书时触达更新 books.currentChapterId/lastReadAt/updatedAt（03 §3 SQL），"继续阅读/临时书清单"按 lastReadAt DESC 查 books；时长/章节流转/阅读节奏完全未记录。03 §9 明示二期出路：reading_sessions 表。
- **差距**：统计需求（每天读了多久/读了几本/某书累计时长）无数据支撑；历史仅"最后位置"粒度。
- **建议方案**：
  1. 新表 `reading_sessions`：id PK、bookId FK CASCADE、chapterId、chapterIndex、openedAt、closedAt、durationMs（前台有效时长，剔除无操作暂停）、INDEX(bookId, openedAt DESC)——随 §7 v2 迁移 CREATE；
  2. 记录口径：进入阅读页 openedAt 起、退出（BACK/切书/进程死亡尽力补记）closedAt；无操作超 5 分钟暂停计时（时间戳比对，不做前台 Service）；章内切换不切断 session（记 startChapterIndex/endChapterIndex）；
  3. 与书架排序解耦：lastReadAt 触达链路**原样保留**，sessions 只增不改任何现有查询；聚合（按天/按书 SUM(durationMs)）走新 DAO；
  4. 量级：单 session 一行，重度用户年约数千行，无需分区；清理入口并入缓存管理页（可选"同时清除阅读统计"）。
- **影响文件**：新 entities/ReadingSessionEntity.kt、dao/ReadingSessionDao.kt、AppDatabase.kt（v2）、presentation/reader/ReaderViewModel.kt（开始/结束挂钩）、di/AppContainer.kt。
- **风险**：低。计时口径（灭屏/切后台）需与 WP-U 对齐；进程死亡丢尾部 session 可接受。
- **工作量**：S~M。

## 7. v1→v2 迁移候选变更清单（方向 E）

基线：AppDatabase version=1（data/db/AppDatabase.kt:26），schemas/1.json 在库；AppContainer.kt:92-94 构建器**未挂任何 addMigrations**（v2 落地必须补）；全工程无 fallbackToDestructiveMigration 调用（仅 AppDatabase.kt:23 注释提及，保持）。

| # | 变更 | 迁移 SQL 形态 | 风险 | 测试要求 |
|---|---|---|---|---|
| a | reading_progress.charOffset（WP-U 锚点，docs/phase2/调研-阅读器体验升级.md §8） | `ALTER TABLE reading_progress ADD COLUMN charOffset INTEGER NOT NULL DEFAULT -1` | 低（default 满足 03 §6 兼容规则） | 旧行数据保全；新行 default -1；upsert 不覆盖 |
| b | reading_sessions 新表（§6） | `CREATE TABLE reading_sessions(…)` + `CREATE INDEX index_reading_sessions_bookId_openedAt` | 低（纯新增） | 建表 SQL 与 Entity 生成一致（helper.validateSchema）；FK CASCADE 生效 |
| c | chapters.contentPath / contentSource（§2 文件化） | `ALTER TABLE chapters ADD COLUMN contentPath TEXT` + `ADD COLUMN contentSource INTEGER NOT NULL DEFAULT 0` | 中（读写路径分叉） | 旧 content 列原样可读；contentSource=0 全链路默认生效 |
| d | SourceRule 字段扩展 | **建议不加列**：ruleJson 为自由 JSON + version 判别 + 读端 ignoreUnknownKeys（同 ContentBlockCodec 向前兼容），规则内演进零迁移 | 低 | 规则 JSON 新字段解析单测；新旧 JSON 互读 |
| e | books.sourceType（WEB/TXT/EPUB，可选） | `ALTER TABLE books ADD COLUMN sourceType INTEGER NOT NULL DEFAULT 0` | 低 | 老数据 default 0（WEB）语义正确 |

- **合并策略取舍**：推荐**"一次 v2 合并迁移"**——a/b/c(/e) 若随各功能分散发版会产生 v2/v3/v4 多组 MigrationTestHelper 排列与多次 schema 评审成本，且二期集成窗口一次齐备；实现上**按变更拆分独立 Migration 对象**再组合为 MIGRATION_1_2（单测可逐个跑），对外只升一个版本号。仅当二期拆多次发布时才值得小步多版本。
- **红线重申（03 §6）**：release 禁 destructive；迁移必跑 MigrationTestHelper；2.json 随仓库提交；DataStore key 只增不删（§4，二期新增 key 同此）。

## 8. 迁移测试基建与二期方案

- **现状**：room-testing 2.6.1 已声明（gradle/libs.versions.toml:47、app/build.gradle.kts:111 testImplementation）但**零使用**（app/src/test grep MigrationTestHelper 无命中）；schemas/1.json 已随库导出；data 单测为 Robolectric + 内存 Room（TestSupport.kt 直接用 RuntimeEnvironment，注明"避免引入 androidx.test:core 依赖"）。
- **差距**：无迁移测试先例；MigrationTestHelper 需 Instrumentation 装载 schemas 资产，KSP schemaLocation 默认只对 androidTest 自动打包 schema，Robolectric 单测须手工挂 assets。
- **建议方案草案**：
  1. app/build.gradle.kts 增加 `sourceSets.getByName("test") { assets.srcDir("$projectDir/schemas") }`；
  2. 新 test 类 data/db/MigrationTest（Robolectric）：`MigrationTestHelper(RuntimeEnvironment.getInstrumentation(), AppDatabase::class.java)` → createDatabase(TEST_DB, 1) 写代表性行（book + chapter 含 content + progress + bookmark）→ runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2) → 断言行保全、新列 default 正确（charOffset=-1、contentSource=0）；
  3. §7 每个候选变更独立 Migration 类 + 至少一条迁移单测；后续 v3 沿用同套路；
  4. 并入标准通道：类名落 com.mvxxcb.reader.data.db 包，`--tests "com.mvxxcb.reader.data.*"` 现有过滤即覆盖，脚本零改动。
- **影响文件**：app/build.gradle.kts、新 app/src/test/java/com/mvxxcb/reader/data/db/MigrationTest.kt、data/db/migrations/（.gitkeep 占位，首批真实 Migration 落位）。
- **风险**：低。Robolectric 4.x 提供 Instrumentation（RuntimeEnvironment.getInstrumentation()），room-testing 2.6.1 单测可行；若资产装载异常回退 androidTest 仪器化（成本更高）。
- **工作量**：S。

## 9. 待主 Agent 决策事项

1. TXT/EPUB 本地书去重键合成（§3.1/§4.5：`local:txt|epub/<hash>`，sourceDomain="local"）——影响 books 表语义，通过后建议回灌 03 §2.1 注释；
2. EPUB 自研 vs 引库（§4.1 评估结论：Apache-2.0 无成熟候选，建议自研；引任何第三方库须主 Agent 批准）；
3. 正文文件化触发阈值（§2.3：单书 10MB / 全库 200MB）与备份规则调整时机（§5 联动）；
4. v2 变更范围确认（§7：a 必含、b 建议含、c 随文件化、e 可裁剪）+ "一次 v2 合并迁移"策略确认；
5. 批量缓存 UI 入口归属（§1.5：data 侧 WorkManager 化不依赖 UI，入口由 WP-U 排期）；
6. 封面本地文件与 Book.coverUrl 的 URI 方案（§4.4，牵 Coil 加载路径）。
