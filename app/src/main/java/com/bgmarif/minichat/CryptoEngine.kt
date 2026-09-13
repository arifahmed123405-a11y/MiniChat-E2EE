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

class CryptoEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("crypto_identity_v1", Context.MODE_PRIVATE)
    private val trustPrefs = context.getSharedPreferences("trusted_keys_v1", Context.MODE_PRIVATE)

    private val hpkePref = "hpke_private_keyset"
    private val masterAlias = "minichat_hpke_keyset_kek_v1"
    private val signAlias = "minichat_signing_key_v1"
    private val localAad = "MiniChat/local-keyset/v1".toByteArray()

    init {
        TinkConfig.register()
        ensureSigningKey()
        ensureHpkeKeyset()
    }

    private fun newHpkeKeyset(): KeysetHandle = KeysetHandle.newBuilder()
        .addEntry(
            KeysetHandle.generateEntryFromParametersName(
                "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
            ).withRandomId().makePrimary()
        )
        .build()

    private fun ensureHpkeKeyset() {
        val stored = prefs.getString(hpkePref, null)
        val hasKek = AndroidKeystore.hasKey(masterAlias)
        if (stored != null && !hasKek) {
            error("Encrypted identity exists but Android Keystore key is missing. Clear app data to create a new identity.")
        }
        if (stored == null) {
            if (!hasKek) AndroidKeystore.generateNewAes256GcmKey(masterAlias)
            val encoded = TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(
                newHpkeKeyset(), AndroidKeystore.getAead(masterAlias), localAad
            )
            prefs.edit().putString(hpkePref, encoded).apply()
        }
    }

    private fun privateHpke(): KeysetHandle {
        val stored = requireNotNull(prefs.getString(hpkePref, null))
        return TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
            stored, AndroidKeystore.getAead(masterAlias), localAad
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

    private fun ensureSigningKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(signAlias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                signAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    fun signingPublicKey(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = requireNotNull(ks.getCertificate(signAlias))
        return b64(cert.publicKey.encoded)
    }

    fun sign(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = requireNotNull(ks.getKey(signAlias, null)) as java.security.PrivateKey
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
        val key = "fp_$userId"
        val old = trustPrefs.getString(key, null)
        return when {
            old == null -> {
                trustPrefs.edit().putString(key, fingerprint).apply()
                Trust.FIRST_SEEN
            }
            old == fingerprint -> Trust.TRUSTED
            else -> Trust.CHANGED
        }
    }

    fun acceptChangedKey(userId: String, fingerprint: String) {
        trustPrefs.edit().putString("fp_$userId", fingerprint).apply()
    }

    companion object {
        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}
