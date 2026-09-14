package com.bgmarif.minichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

class LocalCache(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "minichat_active_cache.db",
    null,
    2
) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)")
        db.execSQL("CREATE TABLE profiles (user_id TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                sender_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                created_at TEXT,
                json TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX messages_sender_created_idx ON messages(sender_id, created_at)")
        db.execSQL("CREATE INDEX messages_recipient_created_idx ON messages(recipient_id, created_at)")
        db.execSQL("CREATE TABLE blocks (blocked_id TEXT PRIMARY KEY NOT NULL)")
        createDeletedThreadsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDeletedThreadsTable(db)
    }

    private fun createDeletedThreadsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS deleted_threads (
                contact_id TEXT PRIMARY KEY NOT NULL,
                deleted_before TEXT NOT NULL
            )""".trimIndent()
        )
    }

    @Synchronized
    fun prepareForAccount(userId: String): Boolean {
        val db = writableDatabase
        val current = activeAccount(db)
        if (current == userId) return false
        db.beginTransaction()
        try {
            clearData(db)
            val values = ContentValues().apply {
                put("key", META_ACTIVE_ACCOUNT)
                put("value", userId)
            }
            db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    @Synchronized
    fun activeAccount(): String? = activeAccount(readableDatabase)

    private fun activeAccount(db: SQLiteDatabase): String? =
        db.query("meta", arrayOf("value"), "key = ?", arrayOf(META_ACTIVE_ACCOUNT),
            null, null, null, "1").use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Synchronized
    fun upsertProfiles(profiles: Collection<Profile>) {
        if (profiles.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            profiles.forEach { profile ->
                val values = ContentValues().apply {
                    put("user_id", profile.userId)
                    put("json", json.encodeToString(profile))
                }
                db.insertWithOnConflict("profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadProfile(userId: String): Profile? =
        readableDatabase.query(
            "profiles", arrayOf("json"), "user_id = ?", arrayOf(userId),
            null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) null
            else runCatching { json.decodeFromString<Profile>(c.getString(0)) }.getOrNull()
        }

    @Synchronized
    fun loadProfiles(): List<Profile> =
        readableDatabase.query("profiles", arrayOf("json"), null, null, null, null, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching { json.decodeFromString<Profile>(c.getString(0)) }
                        .getOrNull()?.let(::add)
                }
            }
        }

    @Synchronized
    fun upsertMessages(messages: Collection<DbMessage>) {
        if (messages.isEmpty()) return
        val db = writableDatabase
        val me = activeAccount(db)
        db.beginTransaction()
        try {
            messages.forEach { message ->
                if (me != null && !visibleAfterDelete(db, me, message)) return@forEach
                val values = ContentValues().apply {
                    put("id", message.id)
                    put("sender_id", message.senderId)
                    put("recipient_id", message.recipientId)
                    put("created_at", message.createdAt)
                    put("json", json.encodeToString(message))
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun visibleAfterDelete(db: SQLiteDatabase, me: String, message: DbMessage): Boolean {
        val other = when {
            message.senderId == me -> message.recipientId
            message.recipientId == me -> message.senderId
            else -> return false
        }
        val cutoff = deletedBefore(db, other) ?: return true
        val created = message.createdAt ?: return true
        return isAfter(created, cutoff)
    }

    private fun isAfter(value: String, cutoff: String): Boolean {
        val a = parseInstant(value)
        val b = parseInstant(cutoff)
        return if (a != null && b != null) a.isAfter(b) else value > cutoff
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

    @Synchronized
    fun deleteMessage(messageId: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(messageId))
    }

    @Synchronized
    fun deleteThread(me: String, other: String) {
        writableDatabase.delete(
            "messages",
            "(sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)",
            arrayOf(me, other, other, me)
        )
    }

    @Synchronized
    fun setThreadDeletedBefore(contactId: String, timestamp: String) {
        val values = ContentValues().apply {
            put("contact_id", contactId)
            put("deleted_before", timestamp)
        }
        writableDatabase.insertWithOnConflict(
            "deleted_threads", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun deletedBefore(db: SQLiteDatabase, contactId: String): String? =
        db.query(
            "deleted_threads", arrayOf("deleted_before"), "contact_id = ?", arrayOf(contactId),
            null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Synchronized
    fun loadThread(me: String, other: String): List<DbMessage> =
        loadMessages(
            "(sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)",
            arrayOf(me, other, other, me),
            "created_at ASC"
        )

    @Synchronized
    fun loadInboxRows(me: String): List<DbMessage> =
        loadMessages("sender_id = ? OR recipient_id = ?", arrayOf(me, me), "created_at ASC")

    private fun loadMessages(selection: String, args: Array<String>, order: String): List<DbMessage> =
        readableDatabase.query(
            "messages", arrayOf("json"), selection, args, null, null, order
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching { json.decodeFromString<DbMessage>(c.getString(0)) }
                        .getOrNull()?.let(::add)
                }
            }
        }

    @Synchronized
    fun replaceBlockedIds(ids: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("blocks", null, null)
            ids.forEach { id ->
                val values = ContentValues().apply { put("blocked_id", id) }
                db.insertWithOnConflict("blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addBlockedId(id: String) {
        val values = ContentValues().apply { put("blocked_id", id) }
        writableDatabase.insertWithOnConflict("blocks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun removeBlockedId(id: String) {
        writableDatabase.delete("blocks", "blocked_id = ?", arrayOf(id))
    }

    @Synchronized
    fun loadBlockedIds(): Set<String> =
        readableDatabase.query("blocks", arrayOf("blocked_id"), null, null, null, null, null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Synchronized
    fun wipe() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearData(db)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun clearData(db: SQLiteDatabase) {
        db.delete("messages", null, null)
        db.delete("profiles", null, null)
        db.delete("blocks", null, null)
        db.delete("deleted_threads", null, null)
    }

    companion object {
        private const val META_ACTIVE_ACCOUNT = "active_account"
    }
}
