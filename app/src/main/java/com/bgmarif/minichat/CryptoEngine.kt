package com.bgmarif.minichat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Device crypto identity manager.
 *
 * v1.1 makes identities account-scoped. The first account upgraded from the old
 * single-identity app keeps the legacy aliases/keyset so existing ciphertext remains
 * decryptable. Other accounts get independent Android Keystore aliases and HPKE keysets.
 */
class CryptoEngine(private val context: Context) {
    private val appContext = context.applicationContext
    private val identityPrefs = appContext.getSharedPreferences("crypto_identity_v2", Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences("crypto_identity_v1", Context.MODE_PRIVATE)
    private val trustPrefs = appContext.getSharedPreferences("trusted_keys_v2", Context.MODE_PRIVATE)
    private val legacyTrustPrefs = appContext.getSharedPreferences("trusted_keys_v1", Context.MODE_PRIVATE)

    @Volatile
    private var active: IdentitySpec? = null

    init {
        TinkConfig.register()
    }

    /** Activate (or create) the persistent device identity for one authenticated user. */
    @Synchronized
    fun activateUser(userId: String) {
        if (active?.userId == userId) return

        val legacyOwner = identityPrefs.getString(LEGACY_OWNER_KEY, null)
        val hasLegacyMaterial = legacyPrefs.contains(LEGACY_HPKE_PREF) ||
            androidKeyStoreContains(LEGACY_SIGN_ALIAS) ||
            AndroidKeystore.hasKey(LEGACY_MASTER_ALIAS)

        val useLegacy = when {
            legacyOwner == userId -> true
            legacyOwner == null && hasLegacyMaterial -> {
                // Bind the old app-wide identity to the first account that upgrades.
                identityPrefs.edit().putString(LEGACY_OWNER_KEY, userId).apply()
                true
            }
            else -> false
        }

        val spec = if (useLegacy) legacySpec(userId) else scopedSpec(userId)
        active = spec
        ensureSigningKey(spec)
        ensureHpkeKeyset(spec)
    }

    /** Forget only which account is active. Persistent private keys remain for future login. */
    @Synchronized
    fun deactivate() {
        active = null
    }

    fun activeUserId(): String? = active?.userId

    private fun requireIdentity(): IdentitySpec =
        active ?: error("No MiniChat crypto identity is active. Sign in first.")

    private data class IdentitySpec(
        val userId: String,
        val tag: String,
        val hpkePrefsName: String,
        val hpkePrefKey: String,
        val masterAlias: String,
        val signAlias: String,
        val localAad: ByteArray,
        val legacy: Boolean
    )

    private fun legacySpec(userId: String) = IdentitySpec(
        userId = userId,
        tag = "legacy",
        hpkePrefsName = "crypto_identity_v1",
        hpkePrefKey = LEGACY_HPKE_PREF,
        masterAlias = LEGACY_MASTER_ALIAS,
        signAlias = LEGACY_SIGN_ALIAS,
        localAad = LEGACY_AAD,
        legacy = true
    )

    private fun scopedSpec(userId: String): IdentitySpec {
        val tag = userTag(userId)
        return IdentitySpec(
            userId = userId,
            tag = tag,
            hpkePrefsName = "crypto_identity_v2",
            hpkePrefKey = "hpke_private_keyset_$tag",
            masterAlias = "minichat_hpke_keyset_kek_v2_$tag",
            signAlias = "minichat_signing_key_v2_$tag",
            localAad = "MiniChat/local-keyset/v2/$tag".toByteArray(),
            legacy = false
        )
    }

    private fun userTag(userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(userId.toByteArray())
        .take(10)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun prefs(spec: IdentitySpec) =
        appContext.getSharedPreferences(spec.hpkePrefsName, Context.MODE_PRIVATE)

    private fun newHpkeKeyset(): KeysetHandle = KeysetHandle.newBuilder()
        .addEntry(
            KeysetHandle.generateEntryFromParametersName(
                "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
            ).withRandomId().makePrimary()
        )
        .build()

    private fun ensureHpkeKeyset(spec: IdentitySpec) {
        val prefs = prefs(spec)
        val stored = prefs.getString(spec.hpkePrefKey, null)
        val hasKek = AndroidKeystore.hasKey(spec.masterAlias)
        if (stored != null && !hasKek) {
            error("Encrypted identity exists but its Android Keystore key is missing.")
        }
        if (stored == null) {
            if (!hasKek) AndroidKeystore.generateNewAes256GcmKey(spec.masterAlias)
            val encoded = TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(
                newHpkeKeyset(), AndroidKeystore.getAead(spec.masterAlias), spec.localAad
            )
            prefs.edit().putString(spec.hpkePrefKey, encoded).apply()
        }
    }

    private fun privateHpke(): KeysetHandle {
        val spec = requireIdentity()
        val stored = requireNotNull(prefs(spec).getString(spec.hpkePrefKey, null))
        return TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
            stored, AndroidKeystore.getAead(spec.masterAlias), spec.localAad
        )
    }

    fun publicHpkeKeyset(): String = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(
        privateHpke().publicKeysetHandle
    )

    fun encryptFor(publicKeysetJson: String, plaintext: ByteArray, contextInfo: ByteArray): ByteArray {
        val handle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(publicKeysetJson)
        return handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
    }

    fun decrypt(ciphertext: ByteArray, contextInfo: ByteArray): ByteArray =
        privateHpke().getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)

    private fun ensureSigningKey(spec: IdentitySpec) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(spec.signAlias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                spec.signAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    fun signingPublicKey(): String {
        val spec = requireIdentity()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = requireNotNull(ks.getCertificate(spec.signAlias))
        return b64(cert.publicKey.encoded)
    }

    fun sign(data: ByteArray): ByteArray {
        val spec = requireIdentity()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = requireNotNull(ks.getKey(spec.signAlias, null)) as java.security.PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    fun verify(signingPublicKeyB64: String, data: ByteArray, signature: ByteArray): Boolean {
        val pub = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(unb64(signingPublicKeyB64))
        )
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(data)
            verify(signature)
        }
    }

    fun encryptAttachment(raw: ByteArray, aad: ByteArray): EncryptedAttachment {
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val aead = handle.getPrimitive(Aead::class.java)
        val cipher = aead.encrypt(raw, aad)
        val keyset = TinkJsonProtoKeysetFormat.serializeKeyset(
            handle, InsecureSecretKeyAccess.get()
        )
        return EncryptedAttachment(cipher, keyset)
    }

    fun decryptAttachment(ciphertext: ByteArray, keysetJson: String, aad: ByteArray): ByteArray {
        val handle = TinkJsonProtoKeysetFormat.parseKeyset(
            keysetJson, InsecureSecretKeyAccess.get()
        )
        return handle.getPrimitive(Aead::class.java).decrypt(ciphertext, aad)
    }

    fun fingerprint(hpkePublic: String, signingPublic: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((hpkePublic + "|" + signingPublic).toByteArray())
        return digest.take(12).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    enum class Trust { FIRST_SEEN, TRUSTED, CHANGED }

    fun checkAndPin(userId: String, fingerprint: String): Trust {
        val spec = requireIdentity()
        val prefs = if (spec.legacy) legacyTrustPrefs else trustPrefs
        val key = if (spec.legacy) "fp_$userId" else "fp_${spec.tag}_$userId"
        val old = prefs.getString(key, null)
        return when {
            old == null -> {
                prefs.edit().putString(key, fingerprint).apply()
                Trust.FIRST_SEEN
            }
            old == fingerprint -> Trust.TRUSTED
            else -> Trust.CHANGED
        }
    }

    fun acceptChangedKey(userId: String, fingerprint: String) {
        val spec = requireIdentity()
        val prefs = if (spec.legacy) legacyTrustPrefs else trustPrefs
        val key = if (spec.legacy) "fp_$userId" else "fp_${spec.tag}_$userId"
        prefs.edit().putString(key, fingerprint).apply()
    }

    private fun androidKeyStoreContains(alias: String): Boolean = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
    }.getOrDefault(false)

    companion object {
        private const val LEGACY_OWNER_KEY = "legacy_owner_user_id"
        private const val LEGACY_HPKE_PREF = "hpke_private_keyset"
        private const val LEGACY_MASTER_ALIAS = "minichat_hpke_keyset_kek_v1"
        private const val LEGACY_SIGN_ALIAS = "minichat_signing_key_v1"
        private val LEGACY_AAD = "MiniChat/local-keyset/v1".toByteArray()

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}
