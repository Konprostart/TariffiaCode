package com.konprostart.tariffiacode.feature.ssh

import com.konprostart.tariffiacode.data.ssh.SshProfile

/**
 * Field-level validation for the SSH profile editor. Pure functions so the rules are unit-testable
 * and identical whether the form is validated on save or on connect.
 */
object SshProfileValidator {
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** Returns a map of field name → error key for every invalid field. Empty means valid. */
    fun validate(profile: SshProfile): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (profile.name.isBlank()) errors[FIELD_NAME] = "missing"
        if (profile.host.isBlank()) {
            errors[FIELD_HOST] = "missing"
        } else if (!isValidHost(profile.host.trim())) {
            errors[FIELD_HOST] = "invalid"
        }
        if (profile.port !in MIN_PORT..MAX_PORT) errors[FIELD_PORT] = "invalid"
        if (profile.username.isBlank()) errors[FIELD_USERNAME] = "missing"
        if (profile.credentialRef.isBlank()) errors[FIELD_CREDENTIAL] = "missing"
        return errors
    }

    fun isValid(profile: SshProfile): Boolean = validate(profile).isEmpty()

    /**
     * Accepts a hostname, IPv4 literal, bracketed/unbracketed IPv6 literal, or DNS name. Deliberately
     * permissive on the DNS side; the transport is what ultimately resolves it.
     */
    fun isValidHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]")
        if (host.isBlank() || host.any { it.isWhitespace() }) return false
        if (host.length > MAX_HOST_LENGTH) return false
        // Reject a scheme or a slash: a user pasting a URL should be told the host field is wrong.
        if (host.contains("://") || host.contains('/')) return false
        val allowed = host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '_' }
        return allowed && host.none { it == ':' && host.count { c -> c == ':' } > 7 }
    }

    const val FIELD_NAME = "name"
    const val FIELD_HOST = "host"
    const val FIELD_PORT = "port"
    const val FIELD_USERNAME = "username"
    const val FIELD_CREDENTIAL = "credential"

    private const val MAX_HOST_LENGTH = 253
}
