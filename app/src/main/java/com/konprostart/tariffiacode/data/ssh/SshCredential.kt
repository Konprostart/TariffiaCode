package com.konprostart.tariffiacode.data.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The secret half of an SSH profile, stored separately from [SshProfile] and keyed by its
 * `credentialRef`. Only ever written to and read from the encrypted credential store.
 *
 * [toString] never reveals any field, so an accidental log of a credential cannot leak it.
 */
@Serializable
sealed interface SshCredential {
    /** [password] is the secret. */
    @Serializable
    @SerialName("password")
    data class Password(
        val password: String,
    ) : SshCredential {
        override fun toString(): String = "SshCredential.Password(password=<redacted>)"
    }

    /**
     * @param keyPem private key in PEM form; secret.
     * @param passphrase optional passphrase; secret.
     */
    @Serializable
    @SerialName("privateKey")
    data class PrivateKey(
        val keyPem: String,
        val passphrase: String? = null,
    ) : SshCredential {
        override fun toString(): String = "SshCredential.PrivateKey(keyPem=<redacted>, passphrase=<redacted>)"
    }

    /** True when the credential actually carries usable material. */
    val isUsable: Boolean
        get() =
            when (this) {
                is Password -> password.isNotBlank()
                is PrivateKey -> keyPem.isNotBlank()
            }
}

object SshCredentialCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encode(credential: SshCredential): String = json.encodeToString(credential)

    fun decode(jsonString: String?): SshCredential? {
        if (jsonString.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<SshCredential>(jsonString) }.getOrNull()
    }
}
