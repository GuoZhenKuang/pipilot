package com.ayagmar.pimobile.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator

class TokenCipherTest {
    private val cipher =
        TokenCipher(
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
        )

    @Test
    fun encryptsWithoutPersistingPlaintextAndDecrypts() {
        val token = "bridge-secret-token"
        val encrypted = cipher.encrypt(token)

        assertFalse(encrypted.contains(token))
        assertEquals(token, cipher.decrypt(encrypted))
    }

    @Test
    fun rejectsCorruptedCiphertext() {
        val encrypted = cipher.encrypt("bridge-secret-token")
        val corrupted = encrypted.dropLast(2) + "AA"

        assertThrows(Exception::class.java) {
            cipher.decrypt(corrupted)
        }
    }
}
