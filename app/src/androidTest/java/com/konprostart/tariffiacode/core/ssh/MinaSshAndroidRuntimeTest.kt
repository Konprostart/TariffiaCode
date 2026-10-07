package com.konprostart.tariffiacode.core.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.sshd.client.SshClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.security.auth.login.LoginException

/** Exercises the actual Android class loader and MINA client initialization, not the host JDK. */
@RunWith(AndroidJUnit4::class)
class MinaSshAndroidRuntimeTest {
    @Test
    fun jaasCompatibilityTypesRemainDistinctAndMinaClientStarts() {
        val appLoader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        val failedLogin = Class.forName("javax.security.auth.login.FailedLoginException", false, appLoader)
        val credential = Class.forName("javax.security.auth.login.CredentialException", false, appLoader)

        assertEquals("javax.security.auth.login.FailedLoginException", failedLogin.name)
        assertEquals("javax.security.auth.login.CredentialException", credential.name)
        assertNotSame("R8 must not merge MINA's distinct exception types", failedLogin, credential)
        assertTrue(LoginException::class.java.isAssignableFrom(failedLogin))
        assertTrue(LoginException::class.java.isAssignableFrom(credential))

        val client = SshClient.setUpDefaultClient()
        try {
            client.start()
        } finally {
            client.stop()
        }
    }
}
