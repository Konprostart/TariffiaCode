package com.konprostart.tariffiacode.core.ssh

import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.IOException
import java.security.KeyPair

/**
 * Loads a private key from the PEM material carried by [SshAuth.PrivateKey]. Shared by the shell transport
 * and the port-forward transport so both parse keys identically. Never logs the key or passphrase.
 */
internal object SshKeyPairs {
    fun load(auth: SshAuth.PrivateKey): KeyPair {
        val parser: KeyPairResourceParser = SecurityUtils.getKeyPairResourceParser()
        val provider =
            auth.passphrase
                ?.let { FilePasswordProvider.of(it) }
                ?: FilePasswordProvider.EMPTY
        val pairs = parser.loadKeyPairs(null, null, provider, auth.keyPem.lineSequence().toList())
        return pairs.firstOrNull() ?: throw IOException("No private key found in the provided key material")
    }
}
