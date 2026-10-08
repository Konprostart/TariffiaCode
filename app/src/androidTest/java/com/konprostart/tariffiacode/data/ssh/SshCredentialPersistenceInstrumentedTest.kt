package com.konprostart.tariffiacode.data.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.konprostart.tariffiacode.data.connection.SecureSettingsRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Verifies SSH password persistence through Android's Keystore-backed encrypted preferences. */
@RunWith(AndroidJUnit4::class)
class SshCredentialPersistenceInstrumentedTest {
    @Test
    fun passwordSurvivesSecureRepositoryRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SecureSettingsRepository(context)
        val profileStore = SshProfileStore(settings)
        val credentialStore = SshCredentialStore(settings)
        val profileId = "android-test-${UUID.randomUUID()}"
        val credentialRef = "android-test-credential-${UUID.randomUUID()}"
        val testPassword = "android-instrumentation-only-secret"
        val profile =
            SshProfile(
                id = profileId,
                name = "instrumented SSH profile",
                host = "127.0.0.1",
                username = "test-user",
                credentialRef = credentialRef,
            )

        try {
            credentialStore.setCredential(credentialRef, SshCredential.Password(testPassword))
            profileStore.upsert(profile)

            val reopenedSettings = SecureSettingsRepository(context)
            val reopenedProfile = SshProfileStore(reopenedSettings).profile(profileId)
            val reopenedCredential =
                reopenedProfile?.let { SshCredentialStore(reopenedSettings).credential(it.credentialRef) }

            assertTrue("profile metadata must survive repository recreation", reopenedProfile == profile)
            assertTrue(
                "encrypted SSH credential must survive repository recreation",
                reopenedCredential is SshCredential.Password && reopenedCredential.password == testPassword,
            )
        } finally {
            profileStore.delete(profileId)
            credentialStore.clearCredential(credentialRef)
        }
    }
}
