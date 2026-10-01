package com.orailnoor.droiddesk.runtime.control

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Key/value persistence for [TokenAuthority]; SharedPreferences on Android, a map in tests. */
interface SecretStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemorySecretStorage : SecretStorage {
    private val values = HashMap<String, String>()
    @Synchronized override fun get(key: String) = values[key]
    @Synchronized override fun put(key: String, value: String) { values[key] = value }
    @Synchronized override fun remove(key: String) { values.remove(key) }
}

/**
 * Issues and verifies the single Termux control token.
 *
 * - The token is 256 random bits, returned exactly once at pairing time.
 *   Only its SHA-256 is persisted (a 256-bit random secret needs no salt or
 *   slow KDF: it cannot be brute-forced from its hash).
 * - Pairing needs a 6-digit code that DroidDesk shows on the phone screen,
 *   so another app on the device that reaches the loopback port cannot pair
 *   without the user typing the code into Termux.
 * - Codes expire, allow a few attempts, and repeated failures lock pairing.
 */
class TokenAuthority(
    private val storage: SecretStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val KEY_TOKEN_HASH = "token_sha256"
        const val KEY_PAIRED_AT = "paired_at"
        const val CODE_TTL_MS = 120_000L
        const val MAX_CODE_ATTEMPTS = 5
        const val MAX_PAIR_REQUESTS = 5
        const val PAIR_REQUEST_WINDOW_MS = 10 * 60_000L
        const val MAX_FAILURES_BEFORE_LOCK = 10
        const val LOCKOUT_MS = 15 * 60_000L
        const val MAX_TOKEN_FAILURES = 20
        const val TOKEN_FAILURE_WINDOW_MS = 60_000L

        fun proofMessage(nonce: String, action: String) = "droiddesk-ctl-server|$nonce|$action"

        /** HMAC-SHA256 keyed with SHA-256(token): what the client recomputes to trust the server. */
        fun proof(tokenHash: ByteArray, nonce: String, action: String): String =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(tokenHash, "HmacSHA256"))
                doFinal(proofMessage(nonce, action).toByteArray(Charsets.UTF_8))
            }.joinToString("") { "%02x".format(it) }

        fun tokenHash(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }

    sealed class PairStart {
        data class Started(val code: String, val expiresInMs: Long) : PairStart()
        data class Refused(val reason: String, val retryAfterMs: Long) : PairStart()
    }

    sealed class PairConfirm {
        data class Paired(val token: String) : PairConfirm()
        data class Failed(val reason: String) : PairConfirm()
    }

    private var pendingCodeHash: ByteArray? = null
    private var pendingExpiresAt = 0L
    private var pendingAttempts = 0
    private val pairRequests = ArrayDeque<Long>()
    private var failures = 0
    private var lockedUntil = 0L
    private val tokenFailures = ArrayDeque<Long>()

    @Synchronized
    fun isPaired(): Boolean = storage.get(KEY_TOKEN_HASH) != null

    @Synchronized
    fun pairedAt(): Long? = storage.get(KEY_PAIRED_AT)?.toLongOrNull()

    @Synchronized
    fun startPairing(): PairStart {
        val now = clock()
        if (now < lockedUntil) return PairStart.Refused("locked", lockedUntil - now)
        while (pairRequests.isNotEmpty() && now - pairRequests.first() > PAIR_REQUEST_WINDOW_MS) {
            pairRequests.removeFirst()
        }
        if (pairRequests.size >= MAX_PAIR_REQUESTS) {
            return PairStart.Refused("rate_limited", PAIR_REQUEST_WINDOW_MS - (now - pairRequests.first()))
        }
        pairRequests.addLast(now)
        val code = (random.nextInt(900_000) + 100_000).toString()
        pendingCodeHash = sha256(code)
        pendingExpiresAt = now + CODE_TTL_MS
        pendingAttempts = 0
        return PairStart.Started(code, CODE_TTL_MS)
    }

    @Synchronized
    fun confirmPairing(code: String): PairConfirm {
        val now = clock()
        if (now < lockedUntil) return PairConfirm.Failed("locked")
        val expected = pendingCodeHash ?: return PairConfirm.Failed("no_pending_pairing")
        if (now > pendingExpiresAt) {
            clearPending()
            return PairConfirm.Failed("expired")
        }
        pendingAttempts++
        if (!MessageDigest.isEqual(expected, sha256(code))) {
            failures++
            if (pendingAttempts >= MAX_CODE_ATTEMPTS) clearPending()
            if (failures >= MAX_FAILURES_BEFORE_LOCK) {
                lockedUntil = now + LOCKOUT_MS
                failures = 0
                clearPending()
            }
            return PairConfirm.Failed("wrong_code")
        }
        clearPending()
        failures = 0
        val token = ByteArray(32).also(random::nextBytes).toHex()
        // Single active token: pairing again replaces (and thereby revokes) the old one.
        storage.put(KEY_TOKEN_HASH, sha256(token).toHex())
        storage.put(KEY_PAIRED_AT, now.toString())
        return PairConfirm.Paired(token)
    }

    /** Constant-time check of a presented token against the stored hash. */
    @Synchronized
    fun verify(token: String?): Boolean {
        val now = clock()
        while (tokenFailures.isNotEmpty() && now - tokenFailures.first() > TOKEN_FAILURE_WINDOW_MS) {
            tokenFailures.removeFirst()
        }
        // Throttle guessing; a 256-bit token cannot be guessed anyway, this
        // just keeps a misbehaving client from spinning the server.
        if (tokenFailures.size >= MAX_TOKEN_FAILURES) return false
        val stored = storage.get(KEY_TOKEN_HASH)?.let(::fromHex)
        val ok = stored != null && ControlProtocol.isWellFormedToken(token) &&
            MessageDigest.isEqual(stored, sha256(token!!))
        if (!ok) tokenFailures.addLast(now)
        return ok
    }

    /**
     * Proves to the client that this server holds the paired token's hash,
     * before the client reveals the token. Null when not paired.
     */
    @Synchronized
    fun serverProof(nonce: String, action: String): String? {
        val stored = storage.get(KEY_TOKEN_HASH)?.let(::fromHex) ?: return null
        return proof(stored, nonce, action)
    }

    @Synchronized
    fun revoke() {
        storage.remove(KEY_TOKEN_HASH)
        storage.remove(KEY_PAIRED_AT)
        clearPending()
    }

    private fun clearPending() {
        pendingCodeHash = null
        pendingExpiresAt = 0L
        pendingAttempts = 0
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun fromHex(value: String): ByteArray? = runCatching {
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
