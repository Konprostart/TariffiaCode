package com.konprostart.tariffiacode.core.ssh

import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.session.ClientSession

/**
 * Registers a profile's credentials on a freshly connected [ClientSession], identically for every
 * transport (shell, exec, port-forward) so all four authenticate the same way.
 *
 * Password profiles also install a keyboard-interactive responder ([PasswordKeyboardInteractive]).
 * Servers frequently advertise "keyboard-interactive" rather than the plain "password" method - e.g.
 * OpenSSH with `UsePAM yes`, which is the default on many distributions, and the method PAM-backed
 * password prompts go through. MINA answers the canonical `"<user>@<host>'s password: "` prompt on its
 * own from the registered password identity, but that auto-detection only fires for a single,
 * non-echoed prompt that contains the literal `password` followed by the configured delimiter. Any
 * other shape - a localized prompt, an instruction with several questions, or no trailing delimiter -
 * reaches the responder instead, and without one the handshake fails. A password-only profile still
 * never requires, stores, or loads a key.
 */
internal fun ClientSession.applySshAuth(auth: SshAuth) {
    when (auth) {
        is SshAuth.Password -> {
            addPasswordIdentity(auth.password)
            userInteraction = PasswordKeyboardInteractive(auth.password)
        }
        is SshAuth.PrivateKey -> addPublicKeyIdentity(SshKeyPairs.load(auth))
    }
}

/**
 * Answers ssh keyboard-interactive challenges with the profile's stored password: one reply per
 * prompt, as RFC 4256 requires. The secret is never logged or included in [toString].
 */
internal class PasswordKeyboardInteractive(
    private val password: String,
) : UserInteraction {
    override fun interactive(
        session: ClientSession,
        name: String,
        instruction: String,
        lang: String,
        prompt: Array<String>,
        echo: BooleanArray,
    ): Array<String> = repliesFor(prompt.size)

    override fun getUpdatedPassword(
        session: ClientSession,
        prompt: String,
        lang: String,
    ): String = password

    /** One reply per prompt. Separated out so the reply-count contract is testable without a session. */
    internal fun repliesFor(promptCount: Int): Array<String> = Array(promptCount) { password }

    override fun toString(): String = "PasswordKeyboardInteractive(password=<redacted>)"
}
