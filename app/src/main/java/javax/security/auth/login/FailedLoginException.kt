package javax.security.auth.login

/**
 * Android-compatible stub for the JDK's JAAS `FailedLoginException`.
 *
 * Android ships only a subset of `javax.security.auth.login` (`LoginException` and a few others) and
 * omits this class. MINA SSHD's `org.apache.sshd.common.config.keys.FilePasswordProvider`, which is
 * loaded by `org.apache.sshd.client.ClientBuilder`'s static initializer when
 * `SshClient.setUpDefaultClient()` runs, references it. On Android the missing class made that load
 * fail with `NoClassDefFoundError` (surfaced on SSH Connect). Declaring the class here — rather than
 * adding a dependency or relaxing the R8 rules — lets the reference resolve on Android; on the
 * desktop JVM the JDK's own class shadows this stub, so it has no effect there.
 */
class FailedLoginException(message: String? = null) : LoginException(message)
