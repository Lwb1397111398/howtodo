package com.howtodo.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * FTS5 虚拟表管理。
 *
 * Room 的 @Fts4 注解只能建 FTS4，而我们用的是 FTS5（bigram 方案需要 bm25 打分），
 * 所以这里绕过 Room，直接用原生 SQL 建表与读写。
 *
 * 部分国产 ROM 的 SQLite 可能裁掉 FTS5，因此启动时探测并降级到 LIKE 检索。
 */
object FtsStore {

    enum class Mode { UNKNOWN, FTS5, LIKE }

    @Volatile
    var mode: Mode = Mode.UNKNOWN
        private set

    private const val DDL_CHUNK_FTS5 =
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(bi)"

    /** 幂等：每次数据库打开都调用，失败则降级 */
    fun ensure(db: SupportSQLiteDatabase) {
        if (mode == Mode.FTS5) return
        try {
            db.execSQL(DDL_CHUNK_FTS5)
            mode = Mode.FTS5
        } catch (_: Throwable) {
            mode = Mode.LIKE
        }
    }

    fun ensure(db: SQLiteDatabase) {
        if (mode == Mode.FTS5) return
        try {
            db.execSQL(DDL_CHUNK_FTS5)
            mode = Mode.FTS5
        } catch (_: Throwable) {
            mode = Mode.LIKE
        }
    }

    fun indexChunk(db: SupportSQLiteDatabase, rowid: Long, bigramText: String) {
        if (mode != Mode.FTS5) return
        try {
            db.execSQL("INSERT INTO chunk_fts(rowid, bi) VALUES (?, ?)", arrayOf(rowid, bigramText))
        } catch (_: Throwable) {
            // 忽略单条失败，避免影响主流程
        }
    }

    fun deleteChunk(db: SupportSQLiteDatabase, rowid: Long) {
        if (mode != Mode.FTS5) return
        try {
            db.execSQL("DELETE FROM chunk_fts WHERE rowid = ?", arrayOf(rowid))
        } catch (_: Throwable) {
        }
    }

    fun deleteBySource(db: SupportSQLiteDatabase, sourceId: String) {
        if (mode != Mode.FTS5) return
        try {
            db.execSQL(
                "DELETE FROM chunk_fts WHERE rowid IN (SELECT id FROM chunk WHERE sourceId = ?)",
                arrayOf(sourceId)
            )
        } catch (_: Throwable) {
        }
    }

    /** 返回按 bm25 升序（越靠前越相关）的 chunk rowid */
    fun search(db: SupportSQLiteDatabase, matchExpr: String, limit: Int): List<Long> {
        if (mode != Mode.FTS5) return emptyList()
        val out = ArrayList<Long>(limit)
        try {
            db.query(
                "SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH ? ORDER BY bm25(chunk_fts) LIMIT ?",
                arrayOf(matchExpr, limit)
            ).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0))
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return out
    }
}

/** 供 Room 回调使用：建表 + 探测 */
class FtsCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        FtsStore.ensure(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        FtsStore.ensure(db)
    }
}
