package com.orailnoor.droiddesk.runtime.control

import com.orailnoor.droiddesk.runtime.control.TokenAuthority.PairConfirm
import com.orailnoor.droiddesk.runtime.control.TokenAuthority.PairStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAuthorityTest {
    private var now = 1_000_000L
    private val storage = InMemorySecretStorage()
    private val authority = TokenAuthority(storage, clock = { now })

    private fun startCode(): String = (authority.startPairing() as PairStart.Started).code

    private fun pair(): String {
        val code = startCode()
        return (authority.confirmPairing(code) as PairConfirm.Paired).token
    }

    @Test fun pairingIssuesA256BitTokenAndStoresOnlyItsHash() {
        assertFalse(authority.isPaired())
        val token = pair()
        assertTrue(Regex("[0-9a-f]{64}").matches(token))
        assertTrue(authority.isPaired())
        val stored = storage.get(TokenAuthority.KEY_TOKEN_HASH)!!
        assertNotEquals(token, stored)
        assertEquals(TokenAuthority.tokenHash(token).joinToString("") { "%02x".format(it) }, stored)
    }

    @Test fun verifiesOnlyTheIssuedToken() {
        val token = pair()
        assertTrue(authority.verify(token))
        assertFalse(authority.verify(null))
        assertFalse(authority.verify(""))
        assertFalse(authority.verify("0".repeat(64)))
        assertFalse(authority.verify(token.uppercase()))
        assertFalse(authority.verify(token + "0"))
    }

    @Test fun rejectsTokensBeforePairing() {
        assertFalse(authority.verify("a".repeat(64)))
    }

    @Test fun wrongCodeFailsAndCodeDiesAfterMaxAttempts() {
        val code = startCode()
        val wrong = if (code == "123456") "654321" else "123456"
        repeat(TokenAuthority.MAX_CODE_ATTEMPTS) {
            assertEquals(PairConfirm.Failed("wrong_code"), authority.confirmPairing(wrong))
        }
        assertEquals(PairConfirm.Failed("no_pending_pairing"), authority.confirmPairing(code))
        assertFalse(authority.isPaired())
    }

    @Test fun codesExpire() {
        val code = startCode()
        now += TokenAuthority.CODE_TTL_MS + 1
        assertEquals(PairConfirm.Failed("expired"), authority.confirmPairing(code))
    }

    @Test fun codeIsSingleUse() {
        val code = startCode()
        assertTrue(authority.confirmPairing(code) is PairConfirm.Paired)
        assertEquals(PairConfirm.Failed("no_pending_pairing"), authority.confirmPairing(code))
    }

    @Test fun pairingRequestsAreRateLimited() {
        repeat(TokenAuthority.MAX_PAIR_REQUESTS) { startCode() }
        val refused = authority.startPairing()
        assertTrue(refused is PairStart.Refused && refused.reason == "rate_limited")
        now += TokenAuthority.PAIR_REQUEST_WINDOW_MS + 1
        assertTrue(authority.startPairing() is PairStart.Started)
    }

    @Test fun repeatedFailuresLockPairing() {
        var failures = 0
        while (failures < TokenAuthority.MAX_FAILURES_BEFORE_LOCK) {
            val code = startCode()
            val wrong = if (code == "111111") "222222" else "111111"
            repeat(minOf(TokenAuthority.MAX_CODE_ATTEMPTS, TokenAuthority.MAX_FAILURES_BEFORE_LOCK - failures)) {
                authority.confirmPairing(wrong)
                failures++
            }
            now += TokenAuthority.PAIR_REQUEST_WINDOW_MS + 1
        }
        val locked = authority.startPairing()
        assertTrue(locked is PairStart.Refused && locked.reason == "locked")
        now += TokenAuthority.LOCKOUT_MS + 1
        assertTrue(authority.startPairing() is PairStart.Started)
    }

    @Test fun revokeInvalidatesTheToken() {
        val token = pair()
        authority.revoke()
        assertFalse(authority.isPaired())
        assertFalse(authority.verify(token))
        assertNull(authority.serverProof("ab".repeat(16), "status"))
    }

    @Test fun pairingAgainReplacesTheOldToken() {
        val first = pair()
        now += 1
        val second = pair()
        assertFalse(authority.verify(first))
        assertTrue(authority.verify(second))
    }

    @Test fun serverProofMatchesWhatTheClientComputes() {
        val token = pair()
        val nonce = "cd".repeat(16)
        val proof = authority.serverProof(nonce, "status")
        assertEquals(TokenAuthority.proof(TokenAuthority.tokenHash(token), nonce, "status"), proof)
        assertNotEquals(proof, authority.serverProof(nonce, "exec"))
        assertNotEquals(proof, authority.serverProof("ef".repeat(16), "status"))
    }

    @Test fun throttlesTokenGuessing() {
        val token = pair()
        repeat(TokenAuthority.MAX_TOKEN_FAILURES) { authority.verify("0".repeat(64)) }
        assertFalse(authority.verify(token))
        now += TokenAuthority.TOKEN_FAILURE_WINDOW_MS + 1
        assertTrue(authority.verify(token))
    }
}
