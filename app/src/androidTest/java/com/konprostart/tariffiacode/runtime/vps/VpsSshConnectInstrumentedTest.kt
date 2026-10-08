package com.konprostart.tariffiacode.runtime.vps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.konprostart.tariffiacode.core.ssh.MinaSshPortForwarder
import com.konprostart.tariffiacode.core.ssh.SshAuth
import com.konprostart.tariffiacode.core.ssh.SshHostKey
import com.konprostart.tariffiacode.core.ssh.SshHostKeyDecision
import com.konprostart.tariffiacode.core.ssh.SshHostKeyVerifier
import com.konprostart.tariffiacode.core.ssh.SshPortForwardResult
import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository
import com.konprostart.tariffiacode.data.remote.RemoteProject
import com.konprostart.tariffiacode.data.ssh.SshAuthType
import com.konprostart.tariffiacode.data.ssh.SshCredential
import com.konprostart.tariffiacode.data.ssh.SshCredentialStore
import com.konprostart.tariffiacode.data.ssh.SshProfile
import com.konprostart.tariffiacode.data.ssh.SshProfileStore
import com.konprostart.tariffiacode.runtime.RuntimeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Executes the Remote Project SSH/password/forward path on Android ART against a CI-local sshd. */
@RunWith(AndroidJUnit4::class)
class VpsSshConnectInstrumentedTest {
    @Test
    fun persistedPasswordAuthenticatesAndForwardsToLoopbackOpenCodeHealth() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val host = arguments.getString(ARG_HOST) ?: error("SSH test host argument is missing")
            val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: error("SSH test port argument is missing")
            val username = arguments.getString(ARG_USERNAME) ?: error("SSH test username argument is missing")
            val password = arguments.getString(ARG_PASSWORD) ?: error("SSH test password argument is missing")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Android ART does not define user.home; MINA's PathUtils reads it during static init.
            System.setProperty("user.home", context.filesDir.absolutePath)
            val settings = SecureSettingsRepository(context)
            val profiles = SshProfileStore(settings)
            val credentials = SshCredentialStore(settings)
            val profileId = "instrumented-ssh-${UUID.randomUUID()}"
            val credentialRef = "instrumented-credential-${UUID.randomUUID()}"
            val profile =
                SshProfile(
                    id = profileId,
                    name = "Instrumented VPS",
                    host = host,
                    port = port,
                    username = username,
                    authType = SshAuthType.PASSWORD,
                    credentialRef = credentialRef,
                )
            var target: VpsRuntimeTarget? = null

            try {
                credentials.setCredential(credentialRef, SshCredential.Password(password))
                profiles.upsert(profile)

                // Recreate the encrypted repository/store before connecting, as happens after app restart.
                val reopenedSettings = SecureSettingsRepository(context)
                val reopenedProfiles = SshProfileStore(reopenedSettings)
                val reopenedCredentials = SshCredentialStore(reopenedSettings)
                val reopenedProfile = reopenedProfiles.profile(profileId) ?: error("saved SSH profile did not reload")
                assertEquals(profile, reopenedProfile)
                val storedCredential = reopenedCredentials.credential(credentialRef) as? SshCredential.Password
                assertTrue("password must persist in encrypted settings", storedCredential?.password == password)

                val activeTarget =
                    VpsRuntimeTarget(
                        connector = VpsRuntimeConnector(MinaSshPortForwarder(), reopenedCredentials),
                        remotePort = TEST_OPENCODE_PORT,
                        profiles = reopenedProfiles,
                    )
                target = activeTarget
                activeTarget.selectProfile(reopenedProfile)
                activeTarget.selectRemoteProject(
                    RemoteProject(
                        id = "instrumented-project-${UUID.randomUUID()}",
                        projectRef = "test/ssh-connect",
                        sshProfileId = profileId,
                        remotePath = "/tmp/ssh-connect-test",
                    ),
                )

                // First handshake is intentionally stopped until the user trusts the ephemeral host key.
                val untrusted = withTimeout(CONNECT_TIMEOUT_MILLIS) { activeTarget.connect() }
                assertTrue("first handshake must request host-key trust", untrusted.isFailure)
                val presentedHostKey =
                    activeTarget.pendingHostKey.value
                        ?: error("No host-key prompt; state=${activeTarget.state.value}")
                val trustedProfile = activeTarget.trustHostKey(reopenedProfile, presentedHostKey)
                reopenedProfiles.upsert(trustedProfile)
                activeTarget.selectProfile(trustedProfile)

                val connected = withTimeout(CONNECT_TIMEOUT_MILLIS) { activeTarget.connect() }

                assertTrue("persisted password must authenticate and establish the forward", connected.isSuccess)
                assertEquals(TEST_OPENCODE_VERSION, connected.getOrThrow().version)
                assertTrue(activeTarget.state.value is RuntimeState.Connected)
                assertTrue(activeTarget.isForwardOpen)
            } finally {
                target?.disconnect()
                profiles.delete(profileId)
                credentials.clearCredential(credentialRef)
            }
        }

    /**
     * Diagnostic: captures the exact failure of the password step and passes only when it does.
     *
     * The first handshake must stop at HostKeyUntrusted, which yields the server host key; the verifier refuses it,
     * as the connect flow does. The second handshake pins exactly that key so that authentication runs. The test
     * passes only when that handshake fails in the password step ("auth()") with a captured cause, and the printed
     * report has the password redacted. Any other outcome fails.
     */
    @Test
    fun diagnosePasswordHandshakeFailure(): Unit =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val host = arguments.getString(ARG_HOST) ?: error("SSH test host argument is missing")
            val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: error("SSH test port argument is missing")
            val username = arguments.getString(ARG_USERNAME) ?: error("SSH test username argument is missing")
            val password = arguments.getString(ARG_PASSWORD) ?: error("SSH test password argument is missing")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            System.setProperty("user.home", context.filesDir.absolutePath)

            val forwarder = MinaSshPortForwarder()
            val probe =
                forwarder.openLocalForward(
                    host = host,
                    port = port,
                    auth = SshAuth.Password(username, password),
                    verifier =
                        object : SshHostKeyVerifier {
                            override fun verify(
                                host: String,
                                port: Int,
                                hostKey: SshHostKey,
                            ): SshHostKeyDecision = SshHostKeyDecision.Unknown(hostKey)
                        },
                    remoteHost = "127.0.0.1",
                    remotePort = TEST_OPENCODE_PORT,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                )
            val presented =
                when (probe) {
                    is SshPortForwardResult.HostKeyUntrusted -> probe.hostKey
                    is SshPortForwardResult.Failure ->
                        throw AssertionError(
                            "Stopped before the password step, so no host key was presented:\n" +
                                describeFailure(probe.message, probe.cause, password),
                        )
                    is SshPortForwardResult.Listening -> {
                        probe.forward.close()
                        throw AssertionError("DIAGNOSTIC: password handshake succeeded, expected a failure")
                    }
                }
            val result =
                forwarder.openLocalForward(
                    host = host,
                    port = port,
                    auth = SshAuth.Password(username, password),
                    verifier =
                        object : SshHostKeyVerifier {
                            override fun verify(
                                host: String,
                                port: Int,
                                hostKey: SshHostKey,
                            ): SshHostKeyDecision =
                                if (hostKey.sha256Fingerprint == presented.sha256Fingerprint) {
                                    SshHostKeyDecision.Trusted
                                } else {
                                    SshHostKeyDecision.Unknown(hostKey)
                                }
                        },
                    remoteHost = "127.0.0.1",
                    remotePort = TEST_OPENCODE_PORT,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                )

            when (result) {
                is SshPortForwardResult.Failure -> {
                    val report = describeFailure(result.message, result.cause, password)
                    assertTrue("diagnostic report must not contain the password", !report.contains(password))
                    assertTrue(
                        "expected a failure in the password step, got:\n$report",
                        result.message.startsWith("auth() failed"),
                    )
                    assertTrue("diagnostic must capture the failure cause", result.cause != null)
                    println(report)
                }
                is SshPortForwardResult.Listening -> {
                    result.forward.close()
                    throw AssertionError("DIAGNOSTIC: password handshake succeeded, expected a failure")
                }
                is SshPortForwardResult.HostKeyUntrusted ->
                    throw AssertionError("DIAGNOSTIC: host key changed between handshakes (${result.hostKey.keyType})")
            }
        }

    private fun describeFailure(
        message: String,
        cause: Throwable?,
        password: String,
    ): String {
        val redact = { text: String? -> text?.replace(password, "***") }
        val chain =
            generateSequence(cause) { it.cause }
                .take(12)
                .joinToString("\n") { "  ${it::class.java.name}: ${redact(it.message)}" }
        val stack =
            java.io.StringWriter().also { writer ->
                cause?.printStackTrace(java.io.PrintWriter(writer))
            }.toString().replace(password, "***")
        return "DIAGNOSTIC: ${message.replace(password, "***")}\nCause chain:\n$chain\nStack:\n$stack"
    }

    private companion object {
        const val ARG_HOST = "sshTestHost"
        const val ARG_PORT = "sshTestPort"
        const val ARG_USERNAME = "sshTestUsername"
        const val ARG_PASSWORD = "sshTestPassword"
        const val TEST_OPENCODE_PORT = 4096
        const val TEST_OPENCODE_VERSION = "android-art-test"
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
    }
}
