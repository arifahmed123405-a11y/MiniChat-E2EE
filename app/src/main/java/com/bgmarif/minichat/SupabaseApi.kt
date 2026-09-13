package com.bgmarif.minichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SupabaseApi(
    private val baseUrl: String,
    private val anonKey: String
) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json".toMediaType()

    private fun configured() {
        require(baseUrl.isNotBlank() && anonKey.isNotBlank()) {
            "Missing SUPABASE_URL / SUPABASE_ANON_KEY build config."
        }
    }

    private fun builder(url: String, token: String? = null): Request.Builder {
        configured()
        return Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
    }

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $body")
            body
        }
    }

    suspend fun signUp(email: String, password: String): Session {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val body = execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/signup")
                .post(payload.toRequestBody(jsonType)).build()
        )
        val auth = json.decodeFromString<AuthResponse>(body)
        val token = auth.accessToken ?: error(
            "Signup created, but no session was returned. For testing, confirm the email or disable Confirm Email in Supabase Auth."
        )
        val user = auth.user ?: error("Signup did not return a user")
        return Session(token, auth.refreshToken, user.id)
    }

    suspend fun login(email: String, password: String): Session {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val body = execute(
            builder("${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=password")
                .post(payload.toRequestBody(jsonType)).build()
        )
        val auth = json.decodeFromString<AuthResponse>(body)
        val token = auth.accessToken ?: error("Login did not return an access token")
        val user = auth.user ?: error("Login did not return a user")
        return Session(token, auth.refreshToken, user.id)
    }

    suspend fun upsertProfile(token: String, profile: Profile) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/profiles"
        execute(
            builder(url, token)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.encodeToString(profile).toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun profileByUserId(token: String, userId: String): Profile? {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/profiles".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("select", "*")
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString<List<Profile>>(body).firstOrNull()
    }

    suspend fun profileByHandle(token: String, handle: String): Profile? {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/profiles".toHttpUrl().newBuilder()
            .addQueryParameter("handle", "eq.${handle.trim().lowercase()}")
            .addQueryParameter("select", "*")
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString<List<Profile>>(body).firstOrNull()
    }

    suspend fun sendMessage(token: String, message: DbMessage) {
        val url = "${baseUrl.trimEnd('/')}/rest/v1/messages"
        execute(
            builder(url, token)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .post(json.encodeToString(message).toRequestBody(jsonType))
                .build()
        )
    }

    suspend fun fetchThread(token: String, me: String, other: String): List<DbMessage> {
        val filter = "(and(sender_id.eq.$me,recipient_id.eq.$other),and(sender_id.eq.$other,recipient_id.eq.$me))"
        val url = "${baseUrl.trimEnd('/')}/rest/v1/messages".toHttpUrl().newBuilder()
            .addQueryParameter("or", filter)
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "created_at.asc")
            .build()
        val body = execute(builder(url.toString(), token).get().build())
        return json.decodeFromString(body)
    }

    suspend fun uploadFile(token: String, path: String, ciphertext: ByteArray) {
        val url = "${baseUrl.trimEnd('/')}/storage/v1/object/chat-files/$path"
        execute(
            builder(url, token)
                .header("Content-Type", "application/octet-stream")
                .header("x-upsert", "false")
                .post(ciphertext.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        )
    }

    suspend fun downloadFile(token: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/storage/v1/object/authenticated/chat-files/$path"
        val req = builder(url, token).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("File download failed: HTTP ${response.code}")
            response.body?.bytes() ?: error("Empty file response")
        }
    }
}
