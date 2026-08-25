package com.pickupcode.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 统一数据仓库——三条识别路径的唯一数据入口。
 * 替代各路径直接调 DAO 的模式，去重/合并逻辑集中管理。
 */
class CodeRepository(private val dao: CodeHistoryDao) {

    suspend fun save(history: CodeHistory): CodeHistoryDao.SaveResult =
        dao.saveOrUpdate(history)

    suspend fun findByCodeAndType(code: String, type: String): CodeHistory? =
        dao.findByCodeAndType(code, type)

    suspend fun markDoneByCodeAndType(code: String, type: String) =
        dao.markDoneByCodeAndType(code, type)

    suspend fun restore(id: Long) = dao.restore(id)

    fun observeActive(): Flow<List<CodeHistory>> = dao.getActiveFlow()

    fun observeTrash(): Flow<List<CodeHistory>> = dao.getTrashFlow()

    suspend fun countDuplicateGroups(): Int = dao.countDuplicateGroups()

    suspend fun updatePickupAddress(id: Long, address: String) =
        dao.updatePickupAddress(id, address)

    suspend fun updateGeo(id: Long, verified: Boolean, confidence: Float, formatted: String) =
        dao.updateGeo(id, verified, confidence, formatted)

    suspend fun updateCode(id: Long, code: String) = dao.updateCode(id, code)

    suspend fun updateSource(id: Long, source: String) = dao.updateSource(id, source)

    suspend fun updateCabinet(id: Long, cabinet: String) = dao.updateCabinet(id, cabinet)

    suspend fun cleanExpired(before: Long, onScreenshot: (String) -> Unit) {
        dao.getExpiredScreenshots(before).forEach { onScreenshot(it) }
        dao.deleteExpiredTrash(before)
    }

    suspend fun findSameCodeDifferentType(code: String, type: String): List<CodeHistory> =
        dao.findSameCodeDifferentType(code, type)

    suspend fun countActiveByCodeAndType(code: String, type: String): Int =
        dao.countActiveByCodeAndType(code, type)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    fun getById(id: Long): Flow<CodeHistory?> = dao.getById(id)

    suspend fun getByIdSuspend(id: Long): CodeHistory? = dao.getByIdSuspend(id)

    suspend fun getDuplicateEntries(): List<CodeHistory> = dao.getDuplicateEntries()

    suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)

    suspend fun markDone(id: Long, doneAt: Long = System.currentTimeMillis()) =
        dao.markDone(id, doneAt)


}
