// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption; the wrapping key stays in AndroidKeyStore. */
internal object CredentialEncryption {
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(sealed: ByteArray, key: SecretKey): ByteArray {
        require(sealed.size >= 29 && sealed[0] == 1.toByte()) { "Invalid credential file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }
}

/** Device-entered keys, encrypted at rest and excluded from cloud backup and device transfer. */
internal class Credentials(ctx: Context, private val legacy: SharedPreferences) {
    data class State(val deepseek: String = "", val mimo: String = "", val loaded: Boolean = false, val error: String? = null)

    @Volatile var state = State()
        private set
    private val file = AtomicFile(File(ctx.noBackupFilesDir, "api-keys.bin"))
    private val lock = Mutex()

    /** Import keys saved by versions before 2.4, only after the encrypted write succeeds. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (state.loaded) return@withLock false
            try {
                val bytes = try { file.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { null }
                val keys = if (bytes != null) {
                    val o = JSONObject(String(CredentialEncryption.decrypt(bytes, wrappingKey()), Charsets.UTF_8))
                    State(o.optString("deepseek"), o.optString("mimo"))
                } else {
                    State(legacy.getString("deepseekKey", "").orEmpty().trim(), legacy.getString("mimoKey", "").orEmpty().trim())
                        .also { if (it.deepseek.isNotBlank() || it.mimo.isNotBlank()) write(it) }
                }
                removeLegacyKeys()
                state = keys.copy(loaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Do not silently overwrite an unreadable credential file or fall back to plaintext storage.
                state = State(loaded = true, error = "Couldn't unlock your API keys. Enter them again and tap Save keys.")
            }
            true
        }
    }

    suspend fun save(deepseek: String, mimo: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val keys = State(deepseek.trim(), mimo.trim(), loaded = true)
            write(keys)
            removeLegacyKeys()
            state = keys
        }
    }

    private fun write(keys: State) {
        val plain = JSONObject().put("deepseek", keys.deepseek).put("mimo", keys.mimo).toString().toByteArray(Charsets.UTF_8)
        val sealed = CredentialEncryption.encrypt(plain, wrappingKey())
        val out = file.startWrite()
        try { out.write(sealed); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }

    private fun removeLegacyKeys() {
        if (legacy.contains("deepseekKey") || legacy.contains("mimoKey")) {
            check(legacy.edit().remove("deepseekKey").remove("mimoKey").commit()) { "Couldn't migrate old keys" }
        }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Background alerts must work with the screen locked.
                .build())
        }.generateKey()
    }

    private companion object { const val ALIAS = "AINewsHot.ApiKeys.v1" }
}
