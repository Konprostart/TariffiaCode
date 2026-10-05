package com.konprostart.tariffiacode.core.ssh

import java.security.MessageDigest

/** Computes the SHA-256 fingerprint of a host key exactly the way OpenSSH prints it (lowercase hex). */
object SshFingerprint {
    /**
     * @param publicKeyBlob the SSH wire-format public key blob (as MINA exposes it).
     * @return lowercase hex SHA-256 digest with no separators, matching [SshHostKey.sha256Fingerprint].
     */
    fun sha256(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
