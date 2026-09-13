package com.bgmarif.minichat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

class MiniChatViewModel(app: Application) : AndroidViewModel(app) {
    enum class Tab { CHATS, PEOPLE, SETTINGS }

    private val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    private val crypto = CryptoEngine(app)
    private val cache = LocalCache(app)
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionPrefs = app.getSharedPreferences("session_v2", Context.MODE_PRIVATE)
    private val localPrefs = app.getSharedPreferences("local_ui_v1", Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()

    var session by mutableStateOf(loadSession())
        private set
    var ownProfile by mutableStateOf<Profile?>(null)
        private set
    var needsHandle by mutableStateOf(false)
        private set
    var contact by mutableStateOf<Profile?>(null)
        private set
    var messages by mutableStateOf<List<DecryptedMessage>>(emptyList())
        private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var peopleResults by mutableStateOf<List<Profile>>(emptyList())
        private set
    var blockedProfiles by mutableStateOf<List<Profile>>(emptyList())
        private set
    var blockedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var replyingTo by mutableStateOf<DecryptedMessage?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var tab by mutableStateOf(Tab.CHATS)
        private set

    init {
        bootstrap()
    }

    /**
     * Hydrate from app-private SQLite first so the UI can paint immediately, then sync.
     */
    private fun bootstrap() {
        val loaded = session ?: return
        runCatching {
            activateAccount(loaded.userId)
            hydrateCachedState(loaded.userId)
        }.onFailure {
            clearSession()
            status = "Local security state could not be opened. Sign in again."
            return
        }

        viewModelScope.launch {
            busy = conversations.isEmpty() && ownProfile == null
            if (busy) status = "Opening secure session…"
            runCatching {
                val fresh = ensureFreshSession(loaded)
                val profile = api.profileByUserId(fresh.accessToken, fresh.userId)
                ownProfile = profile
                needsHandle = profile == null
                if (profile != null) {
                    cache.upsertProfiles(listOf(profile))
                    publishCurrentKeys(profile.handle, fresh)
                    refreshBlockedInternal(fresh)
                    refreshConversationsInternal(fresh)
                    status = ""
                } else {
                    status = "Choose a MiniChat handle to finish setup"
                }
            }.onFailure {
                // Local data remains available until the user explicitly logs out.
                status = if (conversations.isNotEmpty() || ownProfile != null) {
                    "Offline or sync unavailable — showing local data"
                } else {
                    friendlyError(it)
                }
            }
            busy = false
        }
    }

    private fun activateAccount(userId: String) {
        crypto.activateUser(userId)
        val pendingHandle = localPrefs.getString("pending_handle", null)
        val changedAccount = cache.prepareForAccount(userId)
        if (changedAccount) {
            // Never carry per-login UI state or decrypted temp files across accounts.
            localPrefs.edit().clear().apply()
            runCatching {
                File(getApplication<Application>().cacheDir, "decrypted").deleteRecursively()
            }
            if (!pendingHandle.isNullOrBlank()) {
                localPrefs.edit().putString("pending_handle", pendingHandle).apply()
            }
        }
    }

    private fun hydrateCachedState(userId: String) {
        ownProfile = cache.loadProfile(userId)
        blockedIds = cache.loadBlockedIds()
        val rows = cache.loadInboxRows(userId).filterNot { it.id in hiddenIds() }
        val profiles = cache.loadProfiles().associateBy { it.userId }
        conversations = buildConversations(rows, profiles, userId)
    }

    private fun loadSession(): Session? {
        val access = sessionPrefs.getString("access", null) ?: return null
        val uid = sessionPrefs.getString("uid", null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = sessionPrefs.getString("refresh", null),
            userId = uid,
            expiresAtEpochSeconds = sessionPrefs.getLong("expires", 0L)
        )
    }

    private fun saveSession(value: Session) {
        session = value
        sessionPrefs.edit()
            .putString("access", value.accessToken)
            .putString("refresh", value.refreshToken)
            .putString("uid", value.userId)
            .putLong("expires", value.expiresAtEpochSeconds)
            .apply()
    }

    private fun clearSession() {
        sessionPrefs.edit().clear().apply()
        cache.wipe()
        localPrefs.edit().clear().apply()
        runCatching {
            File(getApplication<Application>().cacheDir, "decrypted").deleteRecursively()
        }
        crypto.deactivate()
        session = null
        ownProfile = null
        needsHandle = false
        contact = null
        messages = emptyList()
        conversations = emptyList()
        peopleResults = emptyList()
        blockedIds = emptySet()
        blockedProfiles = emptyList()
        replyingTo = null
    }

    private suspend fun ensureFreshSession(input: Session = requireNotNull(session)): Session =
        refreshMutex.withLock {
            val latest = session ?: input
            val now = Instant.now().epochSecond
            if (latest.expiresAtEpochSeconds > now + 90) return@withLock latest
            val refresh = latest.refreshToken ?: return@withLock latest
            val renewed = api.refreshSession(refresh)
            saveSession(renewed)
            renewed
        }

    fun selectTab(value: Tab) {
        tab = value
        if (value == Tab.CHATS) {
            session?.userId?.let(::hydrateCachedState)
            refreshConversations()
        }
        if (value == Tab.SETTINGS) refreshBlockedProfiles()
    }

    fun signUp(email: String, password: String, handle: String) = viewModelScope.launch {
        val cleanEmail = email.trim()
        val cleanHandle = cleanHandle(handle) ?: run {
            status = "Handle must be 3–24 letters, numbers or underscores"
            return@launch
        }
        if (password.length < 6) {
            status = "Password must be at least 6 characters"
            return@launch
        }
        busy = true
        status = "Creating account…"
        localPrefs.edit().putString("pending_handle", cleanHandle).apply()
        runCatching {
            val created = api.signUp(cleanEmail, password)
            if (created == null) {
                status = "Account created. Confirm the email, then sign in. Your @$cleanHandle handle is saved on this phone."
            } else {
                activateAccount(created.userId)
                saveSession(created)
                finishProfile(created, cleanHandle)
                status = "Welcome to MiniChat"
            }
        }.onFailure { status = friendlyError(it) }
        busy = false
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        busy = true
        status = "Signing in…"
        runCatching {
            val loggedIn = api.login(email.trim(), password)
            activateAccount(loggedIn.userId)
            saveSession(loggedIn)
            hydrateCachedState(loggedIn.userId)
            val existing = api.profileByUserId(loggedIn.accessToken, loggedIn.userId)
            if (existing != null) {
                ownProfile = existing
                cache.upsertProfiles(listOf(existing))
                needsHandle = false
                publishCurrentKeys(existing.handle)
                localPrefs.edit().remove("pending_handle").apply()
                refreshBlockedInternal(loggedIn)
                refreshConversationsInternal(loggedIn)
                status = ""
            } else {
                val pending = cleanHandle(localPrefs.getString("pending_handle", null).orEmpty())
                if (pending != null && isHandleAvailable(loggedIn, pending)) {
                    finishProfile(loggedIn, pending)
                    status = "MiniChat profile ready"
                } else {
                    needsHandle = true
                    status = "Choose a MiniChat handle to finish setup"
                }
            }
        }.onFailure {
            clearSession()
            status = friendlyError(it)
        }
        busy = false
    }

    fun completeHandle(handle: String) = viewModelScope.launch {
        val clean = cleanHandle(handle) ?: run {
            status = "Handle must be 3–24 letters, numbers or underscores"
            return@launch
        }
        busy = true
        status = "Securing @$clean…"
        runCatching {
            val s = ensureFreshSession()
            if (!isHandleAvailable(s, clean)) error("@$clean is already taken")
            finishProfile(s, clean)
            status = "Profile ready"
        }.onFailure { status = friendlyError(it) }
        busy = false
    }

    private suspend fun finishProfile(s: Session, handle: String) {
        publishCurrentKeys(handle, s)
        needsHandle = false
        localPrefs.edit().remove("pending_handle").apply()
        refreshBlockedInternal(s)
        refreshConversationsInternal(s)
    }

    private suspend fun isHandleAvailable(s: Session, handle: String): Boolean {
        val found = api.profileByHandle(s.accessToken, handle)
        return found == null || found.userId == s.userId
    }

    private suspend fun publishCurrentKeys(handle: String, s: Session = requireNotNull(session)) {
        val profile = Profile(
            userId = s.userId,
            handle = handle,
            hpkePublicKeyset = crypto.publicHpkeKeyset(),
            signingPublicKey = crypto.signingPublicKey(),
            updatedAt = Instant.now().toString()
        )
        api.upsertProfile(s.accessToken, profile)
        cache.upsertProfiles(listOf(profile))
        ownProfile = profile
    }

    fun updateHandle(handle: String) = viewModelScope.launch {
        val clean = cleanHandle(handle) ?: run {
            status = "Invalid handle"
            return@launch
        }
        busy = true
        runCatching {
            val s = ensureFreshSession()
            if (!isHandleAvailable(s, clean)) error("@$clean is already taken")
            publishCurrentKeys(clean, s)
            status = "Handle updated to @$clean"
        }.onFailure { status = friendlyError(it) }
        busy = false
    }

    fun requestPasswordReset(email: String) = viewModelScope.launch {
        if (email.isBlank()) {
            status = "Enter your email first"
            return@launch
        }
        busy = true
        runCatching {
            api.requestPasswordReset(email.trim())
            status = "Password reset email sent"
        }.onFailure { status = friendlyError(it) }
        busy = false
    }

    fun logout() {
        clearSession()
        status = ""
        tab = Tab.CHATS
    }

    fun searchPeople(query: String) = viewModelScope.launch {
        if (query.trim().length < 2) {
            peopleResults = emptyList()
            return@launch
        }
        runCatching {
            val s = ensureFreshSession()
            peopleResults = api.searchProfiles(s.accessToken, query)
                .filter { it.userId != s.userId && it.userId !in blockedIds }
            cache.upsertProfiles(peopleResults)
            status = ""
        }.onFailure { status = friendlyError(it) }
    }

    fun openChat(profile: Profile) {
        if (profile.userId in blockedIds) {
            status = "Unblock @${profile.handle} before chatting"
            return
        }
        val s = session ?: return
        cache.upsertProfiles(listOf(profile))
        contact = cache.loadProfile(profile.userId) ?: profile
        // Instant path: render local ciphertext after on-device decrypt, no network wait.
        messages = cache.loadThread(s.userId, profile.userId)
            .filterNot { it.id in hiddenIds() }
            .map { decryptRow(it, contact ?: profile) }
        replyingTo = null
        status = ""
        refreshMessages()
    }

    fun closeChat() {
        contact = null
        messages = emptyList()
        replyingTo = null
        status = ""
        session?.userId?.let(::hydrateCachedState)
        refreshConversations()
    }

    fun setReply(message: DecryptedMessage?) {
        replyingTo = message?.takeUnless { it.securityBlocked }
    }

    fun deleteForMe(messageId: String) {
        val hidden = hiddenIds().toMutableSet()
        hidden += messageId
        localPrefs.edit().putStringSet("hidden_messages", hidden).apply()
        cache.deleteMessage(messageId)
        messages = messages.filterNot { it.db.id == messageId }
    }

    private fun hiddenIds(): Set<String> =
        localPrefs.getStringSet("hidden_messages", emptySet())?.toSet().orEmpty()

    fun refreshConversations() = viewModelScope.launch {
        val s = session ?: return@launch
        runCatching {
            refreshConversationsInternal(ensureFreshSession(s))
        }.onFailure { status = friendlyError(it) }
    }

    private suspend fun refreshConversationsInternal(s: Session) {
        var rows = api.fetchInboxRows(s.accessToken, s.userId)
            .filterNot { it.id in hiddenIds() }
        val incomingUndelivered = rows.filter {
            it.recipientId == s.userId && it.deliveredAt == null
        }.map { it.id }.toSet()
        if (incomingUndelivered.isNotEmpty()) {
            runCatching { api.markDelivered(s.accessToken, s.userId, incomingUndelivered.toList()) }
            val now = Instant.now().toString()
            rows = rows.map { row ->
                if (row.id in incomingUndelivered) row.copy(deliveredAt = now) else row
            }
        }

        val otherIds = rows.map {
            if (it.senderId == s.userId) it.recipientId else it.senderId
        }.filterNot { it in blockedIds }.distinct()
        val profiles = api.profilesByUserIds(s.accessToken, otherIds)
        cache.upsertMessages(rows)
        cache.upsertProfiles(profiles)
        val mergedRows = cache.loadInboxRows(s.userId).filterNot { it.id in hiddenIds() }
        val profileMap = cache.loadProfiles().associateBy { it.userId }.toMutableMap()
        ownProfile?.let { profileMap[it.userId] = it }
        conversations = buildConversations(mergedRows, profileMap, s.userId)
    }

    private fun buildConversations(
        rows: List<DbMessage>,
        profiles: Map<String, Profile>,
        me: String
    ): List<Conversation> {
        val grouped = rows.groupBy {
            if (it.senderId == me) it.recipientId else it.senderId
        }
        return grouped.mapNotNull { (otherId, threadRows) ->
            if (otherId in blockedIds) return@mapNotNull null
            val profile = profiles[otherId] ?: return@mapNotNull null
            val latest = threadRows.maxByOrNull { it.createdAt.orEmpty() }
            val decrypted = latest?.let { decryptRow(it, profile) }
            Conversation(
                contact = profile,
                lastMessage = decrypted,
                lastAt = latest?.createdAt,
                unreadCount = threadRows.count {
                    it.senderId == otherId && it.recipientId == me && it.readAt == null
                }
            )
        }.sortedByDescending { it.lastAt.orEmpty() }
    }

    fun refreshMessages() = viewModelScope.launch {
        val c = contact ?: return@launch
        runCatching {
            val s = ensureFreshSession()
            if (c.userId in blockedIds) return@runCatching
            val freshContact = api.profileByUserId(s.accessToken, c.userId) ?: c
            contact = freshContact
            cache.upsertProfiles(listOf(freshContact))
            var rows = api.fetchThread(s.accessToken, s.userId, freshContact.userId)
                .filterNot { it.id in hiddenIds() }
            val unread = rows.filter {
                it.recipientId == s.userId && it.readAt == null
            }.map { it.id }.toSet()
            if (unread.isNotEmpty()) {
                runCatching { api.markRead(s.accessToken, s.userId, unread.toList()) }
                val now = Instant.now().toString()
                rows = rows.map { row ->
                    if (row.id in unread) row.copy(
                        deliveredAt = row.deliveredAt ?: now,
                        readAt = now
                    ) else row
                }
            }
            cache.upsertMessages(rows)
            messages = cache.loadThread(s.userId, freshContact.userId)
                .filterNot { it.id in hiddenIds() }
                .map { decryptRow(it, freshContact) }
        }.onFailure { status = friendlyError(it) }
    }

    private fun decryptRow(row: DbMessage, other: Profile): DecryptedMessage {
        val me = session?.userId.orEmpty()
        val fromMe = row.senderId == me
        return runCatching {
            val senderKey = if (fromMe) {
                ownProfile?.signingPublicKey ?: crypto.signingPublicKey()
            } else {
                other.signingPublicKey
            }
            val signatureOk = crypto.verify(
                senderKey,
                signedBytes(row),
                CryptoEngine.unb64(row.signature)
            )
            if (!signatureOk) {
                return@runCatching DecryptedMessage(
                    row, null, "⚠ Invalid message signature", fromMe, securityBlocked = true
                )
            }

            if (!fromMe) {
                val fp = crypto.fingerprint(other.hpkePublicKeyset, other.signingPublicKey)
                if (crypto.checkAndPin(other.userId, fp) == CryptoEngine.Trust.CHANGED) {
                    return@runCatching DecryptedMessage(
                        row, null, "⚠ Security key changed — verify this contact", false, securityBlocked = true
                    )
                }
            }

            val selected = if (fromMe) row.ciphertextToSender else row.ciphertextToRecipient
            val clear = crypto.decrypt(
                CryptoEngine.unb64(selected),
                contextFor(row.id, row.senderId, row.recipientId)
            )
            val payload = json.decodeFromString<MessagePayload>(clear.decodeToString())
            val body = when (payload.type) {
                "text" -> payload.text.orEmpty()
                "file" -> "📎 ${payload.fileName ?: "File"} · ${formatSize(payload.fileSize ?: 0)}"
                else -> "Encrypted message"
            }
            DecryptedMessage(row, payload, body, fromMe)
        }.getOrElse {
            DecryptedMessage(
                row,
                null,
                "🔒 Unable to decrypt on this device",
                fromMe,
                securityBlocked = true
            )
        }
    }

    fun sendText(text: String) = viewModelScope.launch {
        val clean = text.trim()
        if (clean.isEmpty()) return@launch
        val c = contact ?: return@launch
        if (c.userId in blockedIds) return@launch
        runCatching {
            val reply = replyingTo
            val payload = MessagePayload(
                type = "text",
                text = clean,
                replyToId = reply?.db?.id,
                replyPreview = reply?.body?.take(96)
            )
            sendPayload(payload, "text", null)
            replyingTo = null
        }.onFailure { status = friendlyError(it) }
    }

    fun sendFile(uri: Uri) = viewModelScope.launch {
        val c = contact ?: return@launch
        if (c.userId in blockedIds) return@launch
        busy = true
        status = "Encrypting attachment…"
        runCatching {
            val s = ensureFreshSession()
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            } ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read file")
            require(raw.size <= MAX_FILE_BYTES) { "File is larger than the 25 MB encrypted attachment limit" }

            val id = UUID.randomUUID().toString()
            val aad = "$id|$name|$mime".toByteArray()
            val encrypted = crypto.encryptAttachment(raw, aad)
            val filePath = "${s.userId}/$id.bin"
            api.uploadFile(s.accessToken, filePath, encrypted.ciphertext)

            val reply = replyingTo
            val payload = MessagePayload(
                type = "file",
                fileName = name,
                mimeType = mime,
                fileSize = raw.size.toLong(),
                fileKeyset = encrypted.keysetJson,
                replyToId = reply?.db?.id,
                replyPreview = reply?.body?.take(96)
            )
            sendPayload(payload, "file", filePath, forcedId = id)
            replyingTo = null
            status = "Encrypted file sent"
        }.onFailure { status = friendlyError(it) }
        busy = false
    }

    private suspend fun sendPayload(
        payload: MessagePayload,
        kind: String,
        filePath: String?,
        forcedId: String? = null
    ) {
        val s = ensureFreshSession()
        val c = requireNotNull(contact)
        val me = requireNotNull(ownProfile)
        val id = forcedId ?: UUID.randomUUID().toString()
        val bytes = json.encodeToString(payload).toByteArray()
        val context = contextFor(id, s.userId, c.userId)
        val toRecipient = CryptoEngine.b64(crypto.encryptFor(c.hpkePublicKeyset, bytes, context))
        val toSender = CryptoEngine.b64(crypto.encryptFor(me.hpkePublicKeyset, bytes, context))
        var message = DbMessage(
            id = id,
            senderId = s.userId,
            recipientId = c.userId,
            kind = kind,
            ciphertextToRecipient = toRecipient,
            ciphertextToSender = toSender,
            signature = "",
            filePath = filePath
        )
        message = message.copy(signature = CryptoEngine.b64(crypto.sign(signedBytes(message))))
        val localMessage = message.copy(createdAt = Instant.now().toString())

        // Optimistic local commit: the bubble appears before the network round-trip.
        cache.upsertMessages(listOf(localMessage))
        val optimistic = decryptRow(localMessage, c)
        messages = (messages.filterNot { it.db.id == localMessage.id } + optimistic)
            .sortedBy { it.db.createdAt.orEmpty() }

        try {
            // Keep server ordering authoritative: created_at is omitted and filled by Postgres.
            api.sendMessage(s.accessToken, message)
        } catch (error: Throwable) {
            cache.deleteMessage(localMessage.id)
            messages = messages.filterNot { it.db.id == localMessage.id }
            throw error
        }
    }

    private fun signedBytes(message: DbMessage): ByteArray = listOf(
        message.id,
        message.senderId,
        message.recipientId,
        message.kind,
        message.ciphertextToRecipient,
        message.ciphertextToSender,
        message.filePath.orEmpty()
    ).joinToString("|").toByteArray()

    private fun contextFor(id: String, sender: String, recipient: String): ByteArray =
        "MiniChat/v1|$id|$sender|$recipient".toByteArray()

    fun acceptContactKeyChange() {
        val c = contact ?: return
        val fp = crypto.fingerprint(c.hpkePublicKeyset, c.signingPublicKey)
        crypto.acceptChangedKey(c.userId, fp)
        status = "New security key trusted"
        refreshMessages()
    }

    fun contactFingerprint(): String? = contact?.let {
        crypto.fingerprint(it.hpkePublicKeyset, it.signingPublicKey)
    }

    fun ownFingerprint(): String = crypto.fingerprint(
        crypto.publicHpkeKeyset(),
        crypto.signingPublicKey()
    )

    fun blockCurrentContact() = viewModelScope.launch {
        val c = contact ?: return@launch
        runCatching {
            val s = ensureFreshSession()
            api.blockUser(s.accessToken, s.userId, c.userId)
            blockedIds = blockedIds + c.userId
            cache.addBlockedId(c.userId)
            status = "@${c.handle} blocked"
            closeChat()
        }.onFailure { status = friendlyError(it) }
    }

    fun unblock(profile: Profile) = viewModelScope.launch {
        runCatching {
            val s = ensureFreshSession()
            api.unblockUser(s.accessToken, s.userId, profile.userId)
            blockedIds = blockedIds - profile.userId
            cache.removeBlockedId(profile.userId)
            blockedProfiles = blockedProfiles.filterNot { it.userId == profile.userId }
            status = "@${profile.handle} unblocked"
        }.onFailure { status = friendlyError(it) }
    }

    private suspend fun refreshBlockedInternal(s: Session) {
        blockedIds = api.fetchBlockedIds(s.accessToken, s.userId)
        cache.replaceBlockedIds(blockedIds)
    }

    fun refreshBlockedProfiles() = viewModelScope.launch {
        runCatching {
            val s = ensureFreshSession()
            refreshBlockedInternal(s)
            blockedProfiles = api.profilesByUserIds(s.accessToken, blockedIds)
            cache.upsertProfiles(blockedProfiles)
        }.onFailure { status = friendlyError(it) }
    }

    fun openFile(message: DecryptedMessage) = viewModelScope.launch {
        decryptFile(message) { uri, mime ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { getApplication<Application>().startActivity(intent) }
                .onFailure { status = "No installed app can open this file type" }
        }
    }

    fun shareFile(message: DecryptedMessage) = viewModelScope.launch {
        decryptFile(message) { uri, mime ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(
                Intent.createChooser(intent, "Share decrypted file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private suspend fun decryptFile(
        message: DecryptedMessage,
        action: (Uri, String) -> Unit
    ) {
        val payload = message.payload ?: return
        val path = message.db.filePath ?: return
        val keyset = payload.fileKeyset ?: return
        busy = true
        status = "Decrypting attachment…"
        runCatching {
            val s = ensureFreshSession()
            val encrypted = api.downloadFile(s.accessToken, path)
            val aad = "${message.db.id}|${payload.fileName}|${payload.mimeType}".toByteArray()
            val clear = crypto.decryptAttachment(encrypted, keyset, aad)
            val dir = File(getApplication<Application>().cacheDir, "decrypted").apply { mkdirs() }
            val safeName = (payload.fileName ?: "file").replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val out = File(dir, safeName).apply { writeBytes(clear) }
            val uri = FileProvider.getUriForFile(
                getApplication(), "${BuildConfig.APPLICATION_ID}.files", out
            )
            action(uri, payload.mimeType ?: "application/octet-stream")
            status = ""
        }.onFailure { status = "Could not decrypt file: ${friendlyError(it)}" }
        busy = false
    }

    private fun cleanHandle(value: String): String? {
        val clean = value.trim().removePrefix("@").lowercase()
        return clean.takeIf { it.matches(Regex("[a-z0-9_]{3,24}")) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            "Invalid login credentials" in raw -> "Wrong email or password"
            "Email not confirmed" in raw -> "Confirm your email before signing in"
            "User already registered" in raw -> "That email already has an account — sign in instead"
            "rate limit" in raw.lowercase() -> "Too many attempts. Try again shortly."
            "Failed to connect" in raw || "timeout" in raw.lowercase() -> "Network connection failed"
            raw.isBlank() -> "Something went wrong"
            else -> raw.take(240)
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
    }
}
