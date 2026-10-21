package com.example.vrplayer

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Приватное хранилище: файлы лежат во внутренней памяти приложения, проводник и галерея их не видят. */
object Vault {
    private const val PREFS = "vault_prefs"
    private const val ITER = 120_000
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 30_000L

    fun dir(ctx: Context): File = File(ctx.filesDir, "vault").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun hasPin(ctx: Context) = prefs(ctx).contains("hash")

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0).putLong("lock_until", 0L).apply()
    }

    fun lockLeftMs(ctx: Context): Long =
        (prefs(ctx).getLong("lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 0 = верно, 1 = неверно, 2 = временно заблокировано */
    fun check(ctx: Context, pin: String): Int {
        if (lockLeftMs(ctx) > 0) return 2
        val sp = prefs(ctx)
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("hash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pin, salt))) {
            sp.edit().putInt("fails", 0).apply()
            return 0
        }
        val fails = sp.getInt("fails", 0) + 1
        val e = sp.edit()
        if (fails >= MAX_FAILS) e.putInt("fails", 0).putLong("lock_until", System.currentTimeMillis() + LOCK_MS)
        else e.putInt("fails", fails)
        e.apply()
        return 1
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
}
