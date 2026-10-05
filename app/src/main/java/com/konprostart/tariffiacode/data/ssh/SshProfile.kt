package com.konprostart.tariffiacode.data.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Authentication method a profile uses. Determines which credential the secret store holds. */
@Serializable
enum class SshAuthType {
    @SerialName("password")
    PASSWORD,

    @SerialName("privateKey")
    PRIVATE_KEY,
}

/**
 * A saved SSH connection profile. Secrets are never part of this model: the profile only references a
 * credential by [credentialRef], and the password / private key / passphrase for that reference live
 * in the encrypted credential store ([SshCredentialStore]). This keeps non-secret metadata serializable
 * and log-safe while the secret stays in the Keystore-backed preference file.
 *
 * @param id stable identity, also used to derive the credential reference.
 * @param name user-facing label.
 * @param host hostname, IP or DNS name of the server.
 * @param port TCP port, 22 by default.
 * @param username login user.
 * @param authType password or private key.
 * @param credentialRef opaque key into [SshCredentialStore]; never a secret itself.
 * @param trustedHostKeyFingerprint SHA-256 hex of a host key the user explicitly trusted, if any.
 */
@Serializable
data class SshProfile(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("name") val name: String,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int = DEFAULT_PORT,
    @SerialName("username") val username: String,
    @SerialName("authType") val authType: SshAuthType = SshAuthType.PASSWORD,
    @SerialName("credentialRef") val credentialRef: String = newCredentialRef(),
    @SerialName("trustedHostKeyFingerprint") val trustedHostKeyFingerprint: String? = null,
) {
    /** A copy of this profile with [fingerprint] recorded as trusted, replacing any previous one. */
    fun trusting(fingerprint: String): SshProfile = copy(trustedHostKeyFingerprint = fingerprint)

    /** A copy of this profile with the stored trust cleared. */
    fun forgettingHostKey(): SshProfile = copy(trustedHostKeyFingerprint = null)

    /**
     * Deliberately excludes nothing secret because the model holds no secret, but keeps the shape
     * consistent with the other redacting models in the app.
     */
    override fun toString(): String =
        "SshProfile(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "authType=$authType, credentialRef=$credentialRef, trustedHostKeyFingerprint=$trustedHostKeyFingerprint)"

    companion object {
        const val DEFAULT_PORT = 22

        fun newCredentialRef(): String = "ssh-cred-${UUID.randomUUID()}"
    }
}

object SshProfileCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encode(profiles: List<SshProfile>): String = json.encodeToString(profiles)

    fun decode(jsonString: String): List<SshProfile> {
        if (jsonString.isBlank()) return emptyList()
        return json.decodeFromString<List<SshProfile>>(jsonString)
    }
}
