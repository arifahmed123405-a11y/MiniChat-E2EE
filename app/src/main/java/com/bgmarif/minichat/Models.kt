package com.bgmarif.minichat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(val id: String, val email: String? = null)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: AuthUser? = null
)

data class Session(val accessToken: String, val refreshToken: String?, val userId: String)

@Serializable
data class Profile(
    @SerialName("user_id") val userId: String,
    val handle: String,
    @SerialName("hpke_public_keyset") val hpkePublicKeyset: String,
    @SerialName("signing_public_key") val signingPublicKey: String,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class DbMessage(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val kind: String,
    @SerialName("ciphertext_to_recipient") val ciphertextToRecipient: String,
    @SerialName("ciphertext_to_sender") val ciphertextToSender: String,
    val signature: String,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class MessagePayload(
    val type: String,
    val text: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val fileKeyset: String? = null
)

data class DecryptedMessage(
    val db: DbMessage,
    val payload: MessagePayload?,
    val body: String,
    val fromMe: Boolean,
    val securityBlocked: Boolean = false
)

data class EncryptedAttachment(
    val ciphertext: ByteArray,
    val keysetJson: String
)
