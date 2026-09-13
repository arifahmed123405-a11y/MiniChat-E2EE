package com.bgmarif.minichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small app-private ciphertext cache used only for the currently signed-in account.
 *
 * Important privacy rule: this database is wiped on logout and whenever a different
 * account becomes active. It stores server ciphertext/public profiles, not decrypted
 * message bodies or attachment bytes.
 */
class LocalCache(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "minichat_active_cache.db",
    null,
    1
) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE profiles (
                user_id TEXT PRIMARY KEY NOT NULL,
                json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                sender_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                created_at TEXT,
                json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX messages_sender_created_idx ON messages(sender_id, created_at)")
        db.execSQL("CREATE INDEX messages_recipient_created_idx ON messages(recipient_id, created_at)")
        db.execSQL(
            """
            CREATE TABLE blocks (
                blocked_id TEXT PRIMARY KEY NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Activates one account. Returns true when a different/empty account was activated.
     * Any previous account cache is destroyed before the new owner is recorded.
     */
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
        db.query(
            "meta",
            arrayOf("value"),
            "key = ?",
            arrayOf(META_ACTIVE_ACCOUNT),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
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
    fun loadProfile(userId: String): Profile? = readableDatabase.query(
        "profiles",
        arrayOf("json"),
        "user_id = ?",
        arrayOf(userId),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        runCatching { json.decodeFromString<Profile>(cursor.getString(0)) }.getOrNull()
    }

    @Synchronized
    fun loadProfiles(): List<Profile> = readableDatabase.query(
        "profiles",
        arrayOf("json"),
        null,
        null,
        null,
        null,
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching { json.decodeFromString<Profile>(cursor.getString(0)) }
                    .getOrNull()
                    ?.let { add(it) }
            }
        }
    }

    @Synchronized
    fun upsertMessages(messages: Collection<DbMessage>) {
        if (messages.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { message ->
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

    @Synchronized
    fun deleteMessage(messageId: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(messageId))
    }

    @Synchronized
    fun loadThread(me: String, other: String): List<DbMessage> = loadMessages(
        selection = "(sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)",
        args = arrayOf(me, other, other, me),
        order = "created_at ASC"
    )

    @Synchronized
    fun loadInboxRows(me: String): List<DbMessage> = loadMessages(
        selection = "sender_id = ? OR recipient_id = ?",
        args = arrayOf(me, me),
        order = "created_at ASC"
    )

    private fun loadMessages(
        selection: String,
        args: Array<String>,
        order: String
    ): List<DbMessage> = readableDatabase.query(
        "messages",
        arrayOf("json"),
        selection,
        args,
        null,
        null,
        order
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching { json.decodeFromString<DbMessage>(cursor.getString(0)) }
                    .getOrNull()
                    ?.let { add(it) }
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
    fun loadBlockedIds(): Set<String> = readableDatabase.query(
        "blocks",
        arrayOf("blocked_id"),
        null,
        null,
        null,
        null,
        null
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    /** Wipes every cached profile/message/block and removes its account owner marker. */
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
    }

    companion object {
        private const val META_ACTIVE_ACCOUNT = "active_account"
    }
}
