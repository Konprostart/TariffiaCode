# Project-specific R8 rules.
-keepattributes *Annotation*,Signature,SourceFile,LineNumberTable

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Compile-time-only Error Prone annotations referenced by Tink.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# kotlinx.serialization.
#
# Replaces the Gson rules left behind by the migration (Gson is no longer a dependency). R8 cannot
# see the reflective link from a @Serializable class to its Companion and generated $serializer, so
# lookups that are not resolved statically at the call site — generic and polymorphic types in
# particular — need these keeps to survive minification. Rules are the ones published with the
# library.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# TariffiaCode REST payloads are matched by @SerialName, and persisted JSON must keep stable field names
# across app updates, so these must not be renamed.
-keep class com.konprostart.tariffiacode.core.api.** { *; }
-keep class com.konprostart.tariffiacode.data.connection.ConnectionProfile { *; }
-keep class com.konprostart.tariffiacode.data.settings.Draft { *; }
-keep class com.konprostart.tariffiacode.runtime.local.LocalRuntimeMetadata { *; }

# Vosk wake word spotting, and the JNA bridge it reaches the native library through.
#
# Neither artifact ships consumer rules, so without these R8 renames the fields JNA's own native
# code resolves by name from Native.initIDs() — "Can't obtain peer field ID for class
# com.sun.jna.Pointer" — which fails com.sun.jna.Native's static initializer and leaves every
# org.vosk class permanently unloadable in a release build. Members have to be kept, not just the
# class names. Callbacks and Structure subclasses are read reflectively from native code too, so
# anything deriving from a JNA type keeps its members as well.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# These Android-side replacements must remain separate and keep their binary names: MINA SSHD uses
# FailedLoginException and CredentialException for different key-loading failure paths. Without these
# keeps R8 can merge/rename the two classes and rewrite SSHD's references to a single obfuscated type.
-keep class javax.security.auth.login.FailedLoginException { *; }
-keep class javax.security.auth.login.CredentialException { *; }

# Apache MINA SSHD + BouncyCastle (SSH/VPS transport). They reference optional JDK/OSGi/slf4j classes
# that do not exist on Android; warn-only is enough to let R8 finish, and keeping their classes intact
# preserves the reflection-based key/cipher loading the SSH client relies on.
-dontwarn javax.security.auth.login.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.sshd.**
-dontwarn org.bouncycastle.**
-keep class org.apache.sshd.** { *; }
-keep class org.bouncycastle.** { *; }
