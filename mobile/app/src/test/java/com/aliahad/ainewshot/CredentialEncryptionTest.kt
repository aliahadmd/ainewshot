// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class CredentialEncryptionTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesFreshIvAndDoesNotStorePlaintext() {
        val key = key()
        val plain = "sample-deepseek-key".toByteArray()
        val a = CredentialEncryption.encrypt(plain, key)
        val b = CredentialEncryption.encrypt(plain, key)
        assertArrayEquals(plain, CredentialEncryption.decrypt(a, key))
        assertFalse(a.contentEquals(b))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("sample-deepseek-key"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val key = key()
        val sealed = CredentialEncryption.encrypt("key".toByteArray(), key)
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { CredentialEncryption.decrypt(sealed, key) }
    }

    @Test fun anotherDeviceKeyCannotDecryptCredentials() {
        val sealed = CredentialEncryption.encrypt("key".toByteArray(), key())
        assertThrows(AEADBadTagException::class.java) { CredentialEncryption.decrypt(sealed, key()) }
    }

    @Test fun malformedStorageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CredentialEncryption.decrypt(byteArrayOf(1, 2), key()) }
    }
}
