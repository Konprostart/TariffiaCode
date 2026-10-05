package com.konprostart.tariffiacode.data.ssh

import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyDecision
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier

/**
 * Resolves a [SshProfile] plus its stored [SshCredential] into transport [SshAuth].
 *
 * Shared helper so the shell connection manager and the VPS runtime build authentication identically.
 * Returns null when the profile's declared auth type does not match the stored credential, so a caller
 * can report "missing credential" rather than sending the wrong kind of proof. Never logs any secret.
 */
fun SshProfile.resolveAuth(credential: SshCredential): SshAuth? =
    when (credential) {
        is SshCredential.Password ->
            if (authType == SshAuthType.PASSWORD) {
                SshAuth.Password(username = username, password = credential.password)
            } else {
                null
            }
        is SshCredential.PrivateKey ->
            if (authType == SshAuthType.PRIVATE_KEY) {
                SshAuth.PrivateKey(
                    username = username,
                    keyPem = credential.keyPem,
                    passphrase = credential.passphrase,
                )
            } else {
                null
            }
    }

/**
 * Host-key policy for a profile, matching the shell transport: no trusted fingerprint → the presented key
 * is unknown and must be confirmed; a matching fingerprint → trusted; a differing one → unknown so the
 * transport aborts (a changed key is never silently trusted).
 *
 * The last presented key is exposed so a caller can distinguish "unknown, ask the user" from a mismatch.
 */
class SshProfileHostKeyVerifier(
    private val profile: SshProfile,
) : SshHostKeyVerifier {
    @Volatile
    var presentedKey: SshHostKey? = null
        private set

    override fun verify(
        host: String,
        port: Int,
        hostKey: SshHostKey,
    ): SshHostKeyDecision {
        presentedKey = hostKey
        val trusted = profile.trustedHostKeyFingerprint
        return when {
            trusted.isNullOrBlank() -> SshHostKeyDecision.Unknown(hostKey)
            trusted.equals(hostKey.sha256Fingerprint, ignoreCase = true) -> SshHostKeyDecision.Trusted
            else -> SshHostKeyDecision.Unknown(hostKey)
        }
    }

    /** True when the last presented key exists and differs from the profile's trusted fingerprint. */
    fun isMismatch(): Boolean {
        val trusted = profile.trustedHostKeyFingerprint
        val presented = presentedKey ?: return false
        return !trusted.isNullOrBlank() && !trusted.equals(presented.sha256Fingerprint, ignoreCase = true)
    }
}
