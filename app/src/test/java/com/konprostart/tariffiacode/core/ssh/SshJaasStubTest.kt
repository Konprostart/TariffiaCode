package com.konprostart.tariffiacode.core.ssh

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Regression for the Android-only SSH Connect failure.
 *
 * `SshClient.setUpDefaultClient()` runs `org.apache.sshd.client.ClientBuilder`'s static initializer,
 * which loads `org.apache.sshd.common.config.keys.FilePasswordProvider`; that class references the
 * JAAS classes `javax.security.auth.login.FailedLoginException` and `CredentialException`. Android
 * ships only a subset of `javax.security.auth.login` and omits both, so on device the load failed
 * with `NoClassDefFoundError`. The app now provides stubs for them (see
 * `app/src/main/java/javax/security/auth/login/`).
 *
 * The desktop JVM *does* have those JDK classes, so a plain `setUpDefaultClient()` call cannot catch
 * a regression. This test simulates Android: it loads the MINA SSHD client through a class loader
 * that never consults the JDK for those two names, only the app's own classes. If the stubs are ever
 * removed the load fails and this test fails.
 */
class SshJaasStubTest {
    /** Loads the app's own JAAS stubs instead of the JDK's for the Android-absent names. */
    private class AndroidLikeLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> =
            findLoadedClass(name)
                ?: if (name in ANDROID_ABSENT_JAAS) {
                    findClass(name).also { if (resolve) resolveClass(it) }
                } else {
                    super.loadClass(name, resolve)
                }

        private companion object {
            val ANDROID_ABSENT_JAAS =
                setOf(
                    "javax.security.auth.login.FailedLoginException",
                    "javax.security.auth.login.CredentialException",
                )
        }
    }

    @Test
    fun `ssh client setup resolves the Android-absent JAAS classes from the app, not the JDK`() {
        val loader = AndroidLikeLoader(classpathUrls(), jdkClassesOnlyParent())

        // The real regression: ClientBuilder's static initializer must not throw NoClassDefFoundError
        // for the Android-absent JAAS classes. MINA SSHD resolves providers via ServiceLoader, which
        // consults the thread context class loader, so point it at the isolated loader for the
        // duration to keep sshd's own classes single-sourced. Without the app's stubs this call fails
        // with NoClassDefFoundError for `FailedLoginException`, exactly as it did on device.
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            val sshClientClass = loader.loadClass("org.apache.sshd.client.SshClient")
            val client = sshClientClass.getMethod("setUpDefaultClient").invoke(null)
            assertNotNull("SshClient.setUpDefaultClient() must not fail on Android-absent JAAS classes", client)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }

        // The app's stubs (not the JDK's) satisfied those references, and both must be JAAS
        // LoginExceptions so MINA SSHD's catch/throw signatures type-check at runtime.
        val failedLogin = loader.loadClass("javax.security.auth.login.FailedLoginException")
        val credential = loader.loadClass("javax.security.auth.login.CredentialException")
        val loginException = loader.loadClass("javax.security.auth.login.LoginException")
        assertTrue(loginException.isAssignableFrom(failedLogin))
        assertTrue(loginException.isAssignableFrom(credential))
    }

    /**
     * The classes MINA SSHD needs must be loadable by the isolated loader, so collect the test
     * classpath from `java.class.path` and from any [URLClassLoader] in the current loader chain
     * (Gradle loads tests through one and does not always populate `java.class.path`).
     */
    private fun classpathUrls(): Array<URL> {
        val urls = LinkedHashSet<URL>()
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.forEach { urls.add(File(it).toURI().toURL()) }
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                urls.addAll(loader.getURLs())
            }
            loader = loader.parent
        }
        return urls.toTypedArray()
    }

    /**
     * A parent that exposes the JDK's own `java.*`/`javax.*` classes but not the app classpath, so
     * MINA SSHD and the JAAS stubs are loaded by [AndroidLikeLoader] instead. On the JVM the system
     * class loader's parent is the platform class loader, which is exactly that (the JDK 9
     * `getPlatformClassLoader()` API is unavailable to Android unit-test compilation).
     */
    private fun jdkClassesOnlyParent(): ClassLoader = ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
}
