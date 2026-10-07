package javax.security.auth.login

/**
 * Android-compatible stub for the JDK's JAAS `CredentialException`.
 *
 * Android ships only a subset of `javax.security.auth.login` and omits this class. MINA SSHD's
 * OpenSSH/PEM private-key parsers (`OpenSSHKeyPairResourceParser`, `AbstractPEMResourceKeyPairParser`,
 * `PKCS8PEMResourceKeyPairParser`, `BouncyCastleKeyPairResourceParser`) reference it. Declaring the
 * class here — rather than adding a dependency or relaxing the R8 rules — lets the reference resolve
 * on Android; on the desktop JVM the JDK's own class shadows this stub, so it has no effect there.
 */
class CredentialException(message: String? = null) : LoginException(message)
