package com.pickupcode.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeHistoryDao {
    /** 活跃记录：按 code+type 去重取最新 */
    @Query("SELECT * FROM code_history WHERE isActive = 1 AND id IN (SELECT MAX(id) FROM code_history WHERE isActive = 1 GROUP BY code, type) ORDER BY timestamp DESC")
    fun getActiveFlow(): Flow<List<CodeHistory>>

    /** 回收站记录 */
    @Query("SELECT * FROM code_history WHERE isActive = 0 ORDER BY doneAt DESC")
    fun getTrashFlow(): Flow<List<CodeHistory>>

    @Query("SELECT * FROM code_history WHERE id = :id")
    fun getById(id: Long): Flow<CodeHistory?>

    @Query("SELECT * FROM code_history WHERE id = :id")
    suspend fun getByIdSuspend(id: Long): CodeHistory?

    @Query("SELECT * FROM code_history WHERE isActive = 1 ORDER BY timestamp DESC LIMIT 5")
    fun getRecentActive(): Flow<List<CodeHistory>>

    /** 按 code+type 查最新的活跃一条（保存前去重用；需 isActive=1，避免回收站数据误判"已存在"） */
    @Query("SELECT * FROM code_history WHERE code = :code AND type = :type AND isActive = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun findByCodeAndType(code: String, type: String): CodeHistory?

    /** 到期提醒复查：该码是否仍有活跃记录（提醒发出前调用，已取则不再打扰）。 */
    @Query("SELECT COUNT(*) FROM code_history WHERE code = :code AND type = :type AND isActive = 1")
    suspend fun countActiveByCodeAndType(code: String, type: String): Int

    /** 查同 code 不同类型的记录（重复值检测） */
    @Query("SELECT * FROM code_history WHERE code = :code AND type != :type AND isActive = 1 ORDER BY timestamp DESC")
    suspend fun findSameCodeDifferentType(code: String, type: String): List<CodeHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: CodeHistory): Long

    @Update
    suspend fun update(history: CodeHistory)

    /** 标记已取（移入回收站）：isActive=0 + doneAt=now。注：名含 Done 但语义是“归档/移入回收站”，非物理删除。 */
    @Query("UPDATE code_history SET isActive = 0, doneAt = :doneAt WHERE id = :id")
    suspend fun markDone(id: Long, doneAt: Long = System.currentTimeMillis())

    /** M1: 定向只更新 geo 校验字段，避免异步回调用旧快照覆盖用户对 code/source/address 的编辑。 */
    @Query("UPDATE code_history SET geoVerified = :verified, geoConfidence = :confidence, geoFormattedAddress = :formatted WHERE id = :id")
    suspend fun updateGeo(id: Long, verified: Boolean, confidence: Float, formatted: String)

    /** 定向更新 pickupAddress（Kuaidi100 回填，避免整行 update 覆盖中间用户操作）。 */
    @Query("UPDATE code_history SET pickupAddress = :address WHERE id = :id")
    suspend fun updatePickupAddress(id: Long, address: String)

    /** 详情页编辑用定向更新（M20）：只改对应列，避免整行 update 用旧快照覆盖快速连改的其它字段。 */
    @Query("UPDATE code_history SET code = :code WHERE id = :id")
    suspend fun updateCode(id: Long, code: String)

    @Query("UPDATE code_history SET source = :source WHERE id = :id")
    suspend fun updateSource(id: Long, source: String)

    @Query("UPDATE code_history SET cabinetNumber = :cabinet WHERE id = :id")
    suspend fun updateCabinet(id: Long, cabinet: String)

    /** 批量归档：同 code+type 的所有活跃记录标记为已取（一次取件对应多份同码记录全部归档）。 */
    @Query("UPDATE code_history SET isActive = 0, doneAt = :doneAt WHERE code = :code AND type = :type AND isActive = 1")
    suspend fun markDoneByCodeAndType(code: String, type: String, doneAt: Long = System.currentTimeMillis())

    /** 从回收站恢复 */
    @Query("UPDATE code_history SET isActive = 1, doneAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    /** 清除过期回收站记录（超过 retentionMs） */
    @Query("DELETE FROM code_history WHERE isActive = 0 AND doneAt > 0 AND doneAt < :before")
    suspend fun deleteExpiredTrash(before: Long)

    /** 取过期回收站记录的截图路径（用于在删除 DB 行前先清理截图文件）。 */
    @Query("SELECT screenshotPath FROM code_history WHERE isActive = 0 AND doneAt > 0 AND doneAt < :before AND screenshotPath != ''")
    suspend fun getExpiredScreenshots(before: Long): List<String>

    /** 手动删除回收站记录 */
    @Query("DELETE FROM code_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 批量删除多条记录（一次性事务，避免逐条删） */
    @Query("DELETE FROM code_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Medium-2: 清理过期旧记录——仅回收站（isActive=0），绝不删活跃记录；活跃记录需用户标记已取后才可被清理。 */
    @Query("DELETE FROM code_history WHERE isActive = 0 AND timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    /** 查重复码值分组（同 code+type 出现 ≥2 次），每组返回最新一条 */
    @Query("SELECT * FROM code_history WHERE isActive = 1 AND code || ':' || type IN (SELECT code || ':' || type FROM code_history WHERE isActive = 1 GROUP BY code, type HAVING COUNT(*) >= 2) ORDER BY code, timestamp DESC")
    suspend fun getDuplicateEntries(): List<CodeHistory>

    /** 查同 code+type 的所有重复记录 */
    @Query("SELECT * FROM code_history WHERE code = :code AND type = :type AND isActive = 1 ORDER BY timestamp DESC")
    suspend fun getDuplicatesByCodeAndType(code: String, type: String): List<CodeHistory>

    /** 统计活跃的重复组数量 */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM code_history WHERE isActive = 1 GROUP BY code, type HAVING COUNT(*) >= 2)")
    suspend fun countDuplicateGroups(): Int

    /** H6 去重的保存结果：id = 记录 id，existed = 是否命中已存在的活跃记录（用于通知去重提示）。
     *  replacedScreenshotPath：本次更新覆盖掉的旧截图路径（调用方负责删除该孤儿文件）。 */
    class SaveResult(val id: Long, val existed: Boolean, val replacedScreenshotPath: String = "")

    /**
     * H6: 事务内原子化「查询已有 + 插入/更新」，避免多入口(分享/无障碍/手动)并发对同一 code+type
     * 各自 find→insert 产生重复行。已存在则按新信息更新并返回现有 id；不存在则插入返回新 id。
     */
    @Transaction
    suspend fun saveOrUpdate(history: CodeHistory): SaveResult {
        val existing = findByCodeAndType(history.code, history.type)
        return if (existing != null) {
            // 新截图将覆盖旧路径时，把旧路径带出去给调用方删除文件（否则旧截图成 cacheDir 孤儿）
            val replaced = if (history.screenshotPath.isNotBlank() &&
                existing.screenshotPath.isNotBlank() &&
                history.screenshotPath != existing.screenshotPath) existing.screenshotPath else ""
            update(existing.copy(
                source = if (history.source.isNotBlank()) history.source else existing.source,
                pickupAddress = if (history.pickupAddress.isNotBlank()) history.pickupAddress else existing.pickupAddress,
                cabinetNumber = if (history.cabinetNumber.isNotBlank()) history.cabinetNumber else existing.cabinetNumber,
                screenshotPath = if (history.screenshotPath.isNotBlank()) history.screenshotPath else existing.screenshotPath,
                rawTextSnippet = if (history.rawTextSnippet.isNotBlank()) history.rawTextSnippet else existing.rawTextSnippet,
                shareSourcePkg = if (history.shareSourcePkg.isNotBlank()) history.shareSourcePkg else existing.shareSourcePkg,
                shareSourceName = if (history.shareSourceName.isNotBlank()) history.shareSourceName else existing.shareSourceName,
                // 新识别的到期时间优先，否则保留旧值（避免同码再次识别时到期提醒时间丢失）
                expiryTime = if (history.expiryTime > 0) history.expiryTime else existing.expiryTime,
                isActive = true,
                doneAt = 0,
                timestamp = history.timestamp
                // 注意：geoVerified/geoConfidence/geoFormattedAddress 故意不在此合并——
                // 它们由异步地图验证回调经 updateGeo() 定向写入，整行 copy 会把默认值覆盖掉已验证结果。
            ))
            SaveResult(existing.id, true, replaced)
        } else {
            SaveResult(insert(history), false)
        }
    }
}

@Database(entities = [CodeHistory::class], version = 7, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun codeHistoryDao(): CodeHistoryDao
    val repository: CodeRepository by lazy { CodeRepository(codeHistoryDao()) }

    companion object {
        /** 1 → 2：初始表结构调整（基础 code_history 表字段补全）。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN stationName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE code_history ADD COLUMN stationType TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        /** 2 → 3：新增纠错/反馈字段。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN codeConfirmed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE code_history ADD COLUMN sourceConfirmed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 3 → 4：新增分享来源两个字段。用 ALTER 保留既有历史数据（避免升级清空取件记录）。 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN shareSourcePkg TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE code_history ADD COLUMN shareSourceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 4 → 5：新增独立柜号列。ALTER 保留既有数据，默认空串。 */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN cabinetNumber TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 5 → 6：新增到期提醒时刻列。ALTER 保留既有数据，默认 0（不提醒）。 */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_history ADD COLUMN expiryTime INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 6 → 7：清理 1→3 时代遗留、当前实体已不存在的孤儿列
         * （stationName/stationType/codeConfirmed/sourceConfirmed）。
         * 方式：建新表（只含实体字段）→ 拷贝 → 删旧表 → 改名，保留全部数据。
         * 列类型与 Room schema（6.json）一致：Boolean→INTEGER，Float→REAL。
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS code_history_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "code TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "screenshotPath TEXT NOT NULL, " +
                        "rawTextSnippet TEXT NOT NULL, " +
                        "pickupAddress TEXT NOT NULL, " +
                        "geoVerified INTEGER NOT NULL, " +
                        "geoConfidence REAL NOT NULL, " +
                        "geoFormattedAddress TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "isActive INTEGER NOT NULL, " +
                        "doneAt INTEGER NOT NULL, " +
                        "shareSourcePkg TEXT NOT NULL, " +
                        "shareSourceName TEXT NOT NULL, " +
                        "cabinetNumber TEXT NOT NULL, " +
                        "expiryTime INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO code_history_new (id, code, type, source, screenshotPath, rawTextSnippet, " +
                        "pickupAddress, geoVerified, geoConfidence, geoFormattedAddress, timestamp, isActive, doneAt, " +
                        "shareSourcePkg, shareSourceName, cabinetNumber, expiryTime) " +
                        "SELECT id, code, type, source, screenshotPath, rawTextSnippet, " +
                        "pickupAddress, geoVerified, geoConfidence, geoFormattedAddress, timestamp, isActive, doneAt, " +
                        "shareSourcePkg, shareSourceName, cabinetNumber, expiryTime FROM code_history"
                )
                db.execSQL("DROP TABLE code_history")
                db.execSQL("ALTER TABLE code_history_new RENAME TO code_history")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pickup_code_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // 不再使用 fallbackToDestructiveMigration：它会静默删库重建，导致用户取件记录无提示丢失。
                    // 已开 exportSchema=true（schemaLocation 见 build.gradle.kts）让 Room 校验迁移，
                    // 未来迁移写错时应升级失败报错，而不是清空核心数据。
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
