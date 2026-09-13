package com.bgmarif.minichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

private fun friendlyApiBody(body: String): String {
    val cleaned = body.replace("\n", " ").trim()
    return if (cleaned.length > 220) cleaned.take(220) + "…" else cleaned
}

class ApiException(val statusCode: Int, val responseBody: String) :
    IllegalStateException("HTTP $statusCode: ${friendlyApiBody(responseBody)}")

@OptIn(ExperimentalSerializationApi::class)
class SupabaseApi(
    private val baseUrl: String,
    private val publishableKey: String
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val jsonType = "application/json".toMediaType()

    private fun configured() {
        require(baseUrl.isNotBlank() && publishableKey.isNotBlank()) {
            "MiniChat backend is not configured."
        }
    }

    private fun builder(url: String, token: String? = null): Request.Builder {
        configured()
        return Request.Builder()
            .url(url)
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
    }

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body)
            body
        }
    }

    private fun sessionFrom(auth: AuthResponse): Session {
        val token = auth.accessToken ?: error("Authentication did not return an access token")
        val user = auth.user ?: error("Authentication did not return a user")
        val expires = Instant.now().epochSecond + (auth.expiresIn ?: 3600L)
        return Session(token, auth.refreshToken, user.id, expires)
    }

    suspend fun signUp(email: String, password: String): Session? {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val body = execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/signup")
                .post(payload.toRequestBody(jsonType))
                .build()
        )
        val auth = json.decodeFromString<AuthResponse>(body)
        return if (auth.accessToken == null) null else sessionFrom(auth)
    }

    suspend fun login(email: String, password: String): Session {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val body = execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=password")
                .post(payload.toRequestBody(jsonType))
                .build()
        )
        return sessionFrom(json.decodeFromString(body))
    }

    suspend fun refreshSession(refreshToken: String): Session {
        val payload = buildJsonObject { put("refresh_token", refreshToken) }.toString()
        val body = execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
                .post(payload.toRequestBody(jsonType))
                .build()
        )
        return sessionFrom(json.decodeFromString(body))
    }

    suspend fun requestPasswordReset(email: String) {
        val payload = buildJsonObject { put("email", email) }.toString()
        execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/recover")
                .post(payload.toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun upsertProfile(token: String, profile: Profile) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_profiles"
        execute(
            builder(url, token)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.encodeToString(profile).toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun profileByUserId(token: String, userId: String): Profile? {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_profiles".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("select", "*")
            .limit(1)
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString<List<Profile>>(body).firstOrNull()
    }

    suspend fun profilesByUserIds(token: String, userIds: Collection<String>): List<Profile> {
        if (userIds.isEmpty()) return emptyList()
        val encodedIds = userIds.distinct().joinToString(",")
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_profiles".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", "in.($encodedIds)")
            .addQueryParameter("select", "*")
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString(body)
    }

    suspend fun profileByHandle(token: String, handle: String): Profile? {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_profiles".toHttpUrl().newBuilder()
            .addQueryParameter("handle", "eq.${handle.trim().lowercase()}")
            .addQueryParameter("select", "*")
            .limit(1)
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString<List<Profile>>(body).firstOrNull()
    }

    suspend fun searchProfiles(token: String, query: String, limit: Int = 20): List<Profile> {
        val clean = query.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (clean.isBlank()) return emptyList()
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_profiles".toHttpUrl().newBuilder()
            .addQueryParameter("handle", "ilike.*$clean*")
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "handle.asc")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString(body)
    }

    suspend fun sendMessage(token: String, message: DbMessage) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_messages"
        execute(
            builder(url, token)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .post(json.encodeToString(message).toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun fetchThread(token: String, me: String, other: String, limit: Int = 500): List<DbMessage> {
        val filter = "(and(sender_id.eq.$me,recipient_id.eq.$other),and(sender_id.eq.$other,recipient_id.eq.$me))"
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_messages".toHttpUrl().newBuilder()
            .addQueryParameter("or", filter)
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "created_at.asc")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString(body)
    }

    suspend fun fetchInboxRows(token: String, me: String, limit: Int = 500): List<DbMessage> {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_messages".toHttpUrl().newBuilder()
            .addQueryParameter("or", "(sender_id.eq.$me,recipient_id.eq.$me)")
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString(body)
    }

    suspend fun markDelivered(token: String, me: String, messageIds: Collection<String>) {
        patchStatuses(token, me, messageIds, read = false)
    }

    suspend fun markRead(token: String, me: String, messageIds: Collection<String>) {
        patchStatuses(token, me, messageIds, read = true)
    }

    private suspend fun patchStatuses(
        token: String,
        me: String,
        messageIds: Collection<String>,
        read: Boolean
    ) {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return
        val now = Instant.now().toString()
        val payload = buildJsonObject {
            put("delivered_at", now)
            if (read) put("read_at", now)
        }.toString()
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_messages".toHttpUrl().newBuilder()
            .addQueryParameter("recipient_id", "eq.$me")
            .addQueryParameter("id", "in.(${ids.joinToString(",")})")
            .build()
        execute(
            builder(url.toString(), token)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .patch(payload.toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun fetchBlockedIds(token: String, me: String): Set<String> {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_blocks".toHttpUrl().newBuilder()
            .addQueryParameter("owner_id", "eq.$me")
            .addQueryParameter("select", "*")
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString<List<BlockRow>>(body).map { it.blockedId }.toSet()
    }

    suspend fun blockUser(token: String, me: String, other: String) {
        val row = BlockRow(ownerId = me, blockedId = other)
        execute(
            builder("${baseUrl.trimEnd('/')}/rest/v1/minichat_blocks", token)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.encodeToString(row).toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun unblockUser(token: String, me: String, other: String) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/minichat_blocks".toHttpUrl().newBuilder()
            .addQueryParameter("owner_id", "eq.$me")
            .addQueryParameter("blocked_id", "eq.$other")
            .build()
        execute(builder(url.toString(), token).delete().build())
    }

    suspend fun uploadFile(token: String, path: String, ciphertext: ByteArray) {
        val url = "${baseUrl.trimEnd('/')}/storage/v1/object/minichat-files/$path"
        execute(
            builder(url, token)
                .header("Content-Type", "application/octet-stream")
                .header("x-upsert", "false")
                .post(ciphertext.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        )
    }

    suspend fun downloadFile(token: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/storage/v1/object/authenticated/minichat-files/$path"
        val req = builder(url, token).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ApiException(response.code, body)
            }
            response.body?.bytes() ?: error("Empty file response")
        }
    }

    private fun okhttp3.HttpUrl.Builder.limit(value: Int): okhttp3.HttpUrl.Builder =
        addQueryParameter("limit", value.toString())
}
