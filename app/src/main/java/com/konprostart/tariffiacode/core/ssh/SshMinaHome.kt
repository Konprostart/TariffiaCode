package com.konprostart.tariffiacode.core.ssh

import org.apache.sshd.common.util.io.PathUtils
import java.io.File

/**
 * Points Apache MINA SSHD at an app-private "user home".
 *
 * MINA resolves a home directory through [PathUtils.getUserHomeFolder], which falls back to the
 * `user.home` system property. Android does not set that property, so the first touch of
 * `org.apache.sshd.common.config.keys.PublicKeyEntry` - reached from [FingerprintServerKeyVerifier]
 * whenever a host key is rendered during the handshake - throws
 * `IllegalArgumentException: No user home folder available` and the SSH connection never completes.
 *
 * Installing a resolver makes that path resolvable. The app never stores keys there (key material is
 * carried in the profile's credential and handed to MINA directly), so the directory only needs to be
 * a writable, app-private location; it does not need to contain dotfiles.
 */
internal fun installSshUserHomeFolder(home: File) {
    PathUtils.setUserHomeFolderResolver { home.toPath() }
}
