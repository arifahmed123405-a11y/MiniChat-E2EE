package com.bgmarif.minichat

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class MiniChatViewModel(app: Application) : AndroidViewModel(app) {
    private val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    private val crypto = CryptoEngine(app)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionPrefs = app.getSharedPreferences("session_v1", android.content.Context.MODE_PRIVATE)

    var session: Session? = loadSession()
        private set
    var ownProfile: Profile? = null
        private set
    var contact: Profile? = null
        private set
    var messages: List<DecryptedMessage> = emptyList()
        private set
    var status: String = ""
        private set
    var busy: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
    private fun changed() = listeners.toList().forEach { it() }

    init {
        session?.let { s ->
            viewModelScope.launch {
                runCatching {
                    val existing = api.profileByUserId(s.accessToken, s.userId)
                    if (existing != null) {
                        ownProfile = existing
                    } else {
                        publishCurrentKeys(fallbackHandle(null, s.userId))
                        status = "MiniChat profile created"
                    }
                }.onFailure {
                    clearStoredSession()
                    session = null
                    ownProfile = null
                    status = "Please sign in again"
                }
                changed()
            }
        }
    }

    private fun loadSession(): Session? {
        val token = sessionPrefs.getString("access", null) ?: return null
        val userId = sessionPrefs.getString("uid", null) ?: return null
        return Session(token, sessionPrefs.getString("refresh", null), userId)
    }

    private fun saveSession(s: Session) {
        sessionPrefs.edit()
            .putString("access", s.accessToken)
            .putString("refresh", s.refreshToken)
            .putString("uid", s.userId)
            .apply()
    }

    private fun clearStoredSession() {
        sessionPrefs.edit()
            .remove("access")
            .remove("refresh")
            .remove("uid")
            .apply()
    }

    private fun validHandle(value: String?): String? {
        val clean = value?.trim()?.lowercase() ?: return null
        return clean.takeIf { it.matches(Regex("[a-z0-9_]{3,24}")) }
    }

    private fun fallbackHandle(email: String?, userId: String): String {
        var base = email
            ?.substringBefore("@")
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9_]"), "_")
            ?.trim('_')
            .orEmpty()

        if (base.length < 3) base = "user"
        base = base.take(15)

        val suffix = userId
            .filter { it.isLetterOrDigit() }
            .take(6)
            .lowercase()

        return "${base}_${suffix}".take(24)
    }

    private fun setBusy(value: Boolean, text: String = status) {
        busy = value
        status = text
        changed()
    }

    fun logout() {
        sessionPrefs.edit().clear().apply()
        session = null
        ownProfile = null
        contact = null
        messages = emptyList()
        status = ""
        changed()
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        setBusy(true, "Signing in…")
        runCatching {
            val cleanEmail = email.trim()
            val s = api.login(cleanEmail, password)
            session = s

            val existing = api.profileByUserId(s.accessToken, s.userId)
            var handle = existing?.handle
                ?: validHandle(sessionPrefs.getString("pending_handle", null))
                ?: fallbackHandle(cleanEmail, s.userId)

            if (existing == null) {
                val taken = api.profileByHandle(s.accessToken, handle)
                if (taken != null && taken.userId != s.userId) {
                    handle = fallbackHandle(cleanEmail, s.userId)
                }
            }

            publishCurrentKeys(handle)
            saveSession(s)
            sessionPrefs.edit().remove("pending_handle").apply()
            status = "Signed in as @$handle"
        }.onFailure {
            clearStoredSession()
            session = null
            ownProfile = null
            status = it.message ?: "Login failed"
        }
        busy = false
        changed()
    }

    fun signUp(email: String, password: String, handle: String) = viewModelScope.launch {
        val cleanHandle = handle.trim().lowercase()
        if (!cleanHandle.matches(Regex("[a-z0-9_]{3,24}"))) {
            status = "Handle: 3–24 chars, letters/numbers/_ only"
            changed(); return@launch
        }

        val cleanEmail = email.trim()
        sessionPrefs.edit().putString("pending_handle", cleanHandle).apply()
        setBusy(true, "Creating encrypted identity…")

        runCatching {
            val s = api.signUp(cleanEmail, password)

            if (s == null) {
                session = null
                status = "Check your email to confirm the account, then tap Already have an account and sign in. If this email already belongs to EarnWall, just sign in with its password."
            } else {
                session = s
                publishCurrentKeys(cleanHandle)
                saveSession(s)
                sessionPrefs.edit().remove("pending_handle").apply()
                status = "Account ready as @$cleanHandle"
            }
        }.onFailure {
            clearStoredSession()
            session = null
            ownProfile = null
            status = it.message ?: "Signup failed"
        }

        busy = false
        changed()
    }

    private suspend fun publishCurrentKeys(handle: String) {
        val s = requireNotNull(session)
        val profile = Profile(
            userId = s.userId,
            handle = handle,
            hpkePublicKeyset = crypto.publicHpkeKeyset(),
            signingPublicKey = crypto.signingPublicKey()
        )
        api.upsertProfile(s.accessToken, profile)
        ownProfile = profile
    }

    fun searchHandle(handle: String) = viewModelScope.launch {
        val s = session ?: return@launch
        setBusy(true, "Searching…")
        runCatching {
            val found = api.profileByHandle(s.accessToken, handle)
                ?: error("No user @$handle")
            if (found.userId == s.userId) error("That is your own account")
            contact = found
            messages = emptyList()
            val fp = crypto.fingerprint(found.hpkePublicKeyset, found.signingPublicKey)
            status = when (crypto.checkAndPin(found.userId, fp)) {
                CryptoEngine.Trust.FIRST_SEEN -> "Security key pinned: $fp"
                CryptoEngine.Trust.TRUSTED -> "Secure contact · $fp"
                CryptoEngine.Trust.CHANGED -> "⚠ Security key changed. Messages are blocked until you trust the new key."
            }
            refreshMessages()
        }.onFailure { status = it.message ?: "Search failed" }
        busy = false
        changed()
    }

    fun closeChat() {
        contact = null
        messages = emptyList()
        status = ""
        changed()
    }

    fun acceptContactKeyChange() {
        val c = contact ?: return
        val fp = crypto.fingerprint(c.hpkePublicKeyset, c.signingPublicKey)
        crypto.acceptChangedKey(c.userId, fp)
        status = "New security key trusted: $fp"
        changed()
        refreshMessages()
    }

    private fun contextFor(id: String, sender: String, recipient: String) =
        "MiniChat/v1|$id|$sender|$recipient".toByteArray()

    private fun signedBytes(m: DbMessage): ByteArray = listOf(
        m.id, m.senderId, m.recipientId, m.kind,
        m.ciphertextToRecipient, m.ciphertextToSender, m.filePath.orEmpty()
    ).joinToString("|").toByteArray()

    fun sendText(text: String) = viewModelScope.launch {
        val s = session ?: return@launch
        val c = contact ?: return@launch
        val me = ownProfile ?: return@launch
        val clean = text.trim()
        if (clean.isEmpty()) return@launch
        runCatching {
            val id = UUID.randomUUID().toString()
            val payloadBytes = json.encodeToString(MessagePayload(type = "text", text = clean)).toByteArray()
            val ctx = contextFor(id, s.userId, c.userId)
            val toRecipient = CryptoEngine.b64(crypto.encryptFor(c.hpkePublicKeyset, payloadBytes, ctx))
            val toSender = CryptoEngine.b64(crypto.encryptFor(me.hpkePublicKeyset, payloadBytes, ctx))
            var msg = DbMessage(
                id = id,
                senderId = s.userId,
                recipientId = c.userId,
                kind = "text",
                ciphertextToRecipient = toRecipient,
                ciphertextToSender = toSender,
                signature = ""
            )
            msg = msg.copy(signature = CryptoEngine.b64(crypto.sign(signedBytes(msg))))
            api.sendMessage(s.accessToken, msg)
            refreshMessages()
        }.onFailure { status = it.message ?: "Send failed"; changed() }
    }

    fun sendFile(uri: Uri) = viewModelScope.launch {
        val s = session ?: return@launch
        val c = contact ?: return@launch
        val me = ownProfile ?: return@launch
        setBusy(true, "Encrypting file…")
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            } ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read file")
            require(raw.size <= MAX_FILE_BYTES) { "MVP file limit is 25 MB" }

            val id = UUID.randomUUID().toString()
            val fileAad = "$id|$name|$mime".toByteArray()
            val encrypted = crypto.encryptAttachment(raw, fileAad)
            val filePath = "${s.userId}/$id.bin"
            api.uploadFile(s.accessToken, filePath, encrypted.ciphertext)

            val payload = MessagePayload(
                type = "file",
                fileName = name,
                mimeType = mime,
                fileSize = raw.size.toLong(),
                fileKeyset = encrypted.keysetJson
            )
            val bytes = json.encodeToString(payload).toByteArray()
            val ctx = contextFor(id, s.userId, c.userId)
            val toRecipient = CryptoEngine.b64(crypto.encryptFor(c.hpkePublicKeyset, bytes, ctx))
            val toSender = CryptoEngine.b64(crypto.encryptFor(me.hpkePublicKeyset, bytes, ctx))
            var msg = DbMessage(
                id, s.userId, c.userId, "file", toRecipient, toSender, "", filePath
            )
            msg = msg.copy(signature = CryptoEngine.b64(crypto.sign(signedBytes(msg))))
            api.sendMessage(s.accessToken, msg)
            status = "Encrypted file sent"
            refreshMessages()
        }.onFailure { status = it.message ?: "File send failed" }
        busy = false
        changed()
    }

    fun refreshMessages() = viewModelScope.launch {
        val s = session ?: return@launch
        val c = contact ?: return@launch
        runCatching {
            val freshContact = api.profileByUserId(s.accessToken, c.userId) ?: c
            contact = freshContact
            val fp = crypto.fingerprint(freshContact.hpkePublicKeyset, freshContact.signingPublicKey)
            val trust = crypto.checkAndPin(freshContact.userId, fp)
            val rows = api.fetchThread(s.accessToken, s.userId, freshContact.userId)
            messages = rows.map { row ->
                val fromMe = row.senderId == s.userId
                val senderKey = if (fromMe) crypto.signingPublicKey() else freshContact.signingPublicKey
                val sigOk = crypto.verify(senderKey, signedBytes(row), CryptoEngine.unb64(row.signature))
                if (!sigOk) {
                    DecryptedMessage(row, null, "⚠ Invalid signature", fromMe, true)
                } else if (!fromMe && trust == CryptoEngine.Trust.CHANGED) {
                    DecryptedMessage(row, null, "⚠ Security key changed — message blocked", false, true)
                } else {
                    val selected = if (fromMe) row.ciphertextToSender else row.ciphertextToRecipient
                    val plain = crypto.decrypt(
                        CryptoEngine.unb64(selected),
                        contextFor(row.id, row.senderId, row.recipientId)
                    )
                    val payload = json.decodeFromString<MessagePayload>(plain.decodeToString())
                    val body = if (payload.type == "text") payload.text.orEmpty()
                    else "📎 ${payload.fileName ?: "file"} (${formatSize(payload.fileSize ?: 0)})"
                    DecryptedMessage(row, payload, body, fromMe)
                }
            }
        }.onFailure { status = it.message ?: "Refresh failed" }
        changed()
    }

    fun openFile(message: DecryptedMessage) = viewModelScope.launch {
        val s = session ?: return@launch
        val payload = message.payload ?: return@launch
        val path = message.db.filePath ?: return@launch
        val keyset = payload.fileKeyset ?: return@launch
        setBusy(true, "Decrypting file…")
        runCatching {
            val encrypted = api.downloadFile(s.accessToken, path)
            val aad = "${message.db.id}|${payload.fileName}|${payload.mimeType}".toByteArray()
            val clear = crypto.decryptAttachment(encrypted, keyset, aad)
            val dir = File(getApplication<Application>().cacheDir, "decrypted").apply { mkdirs() }
            val safeName = (payload.fileName ?: "file").replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val out = File(dir, safeName).apply { writeBytes(clear) }
            val uri = FileProvider.getUriForFile(
                getApplication(), "${BuildConfig.APPLICATION_ID}.files", out
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, payload.mimeType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }.onFailure { status = "Could not open: ${it.message}" }
        busy = false
        changed()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object { const val MAX_FILE_BYTES = 25 * 1024 * 1024 }
}
