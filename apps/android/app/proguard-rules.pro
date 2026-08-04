# kotlinx.serialization keeps its generated serializers in the companion of each
# @Serializable class; R8 would otherwise strip them and settings/blocklist
# parsing would fail only in release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.safeworld.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.safeworld.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The native forwarder's JNI boundary (`cpp/netguard/`). Every name below is resolved from C by
# string — `FindClass` in `JNI_OnLoad`, `GetFieldID` per field, `GetMethodID` per callback — so R8
# sees no reference to any of it and renames or deletes the lot.
#
# The failure that causes is worse than it sounds. `JNI_OnLoad` does not clear the pending
# `ClassNotFoundException`, so ART aborts the process ("No pending exception expected") instead of
# throwing an `UnsatisfiedLinkError` — which means `NativeTunnel`'s `runCatching { loadLibrary }`,
# written precisely so a missing library degrades to the DNS-only tunnel, never gets to run. Debug
# builds are unminified and fine; release builds died the moment protection started with app
# blocking on.
-keep class com.safeworld.app.vpn.Packet { *; }
-keep class com.safeworld.app.vpn.Allowed { *; }
-keep class com.safeworld.app.vpn.ResourceRecord { *; }
-keep class com.safeworld.app.vpn.Usage { *; }

# `isAddressAllowed`, `protect`, `getUidQ`, `isDomainBlocked`, `logPacket`, `dnsResolved`,
# `accountUsage`, `nativeExit` and `nativeError` are private and called only from C, so R8 reads
# them as dead code. The default `native <methods>` rule covers the `jni_*` declarations and
# nothing else.
-keep class com.safeworld.app.vpn.NativeTunnel { *; }
-keep class com.safeworld.app.vpn.SafeWorldRelay { *; }

# MediaPipe Tasks and TFLite, for the blur feature.
#
# Both resolve Java classes from native code by string — MediaPipe's graph
# machinery and TFLite's `Interpreter`/`Delegate` bindings — so R8 sees no
# reference and strips or renames them. The result is a release-only failure
# where the models fail to load, `PersonScanner` logs a warning, and the app
# covers nothing at all while looking perfectly healthy. Debug builds are
# unminified and would never show it.
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.tensorflow.lite.**

# AutoValue-generated MediaPipe option/result types are referenced reflectively
# by the builders above.
-keep class autovalue.** { *; }
-dontwarn autovalue.**

# Flogger, which MediaPipe logs through.
#
# This one is not about reflection. Flogger finds the *call site* of a log
# statement by walking the stack and matching class names; obfuscate it and the
# walk finds nothing, so `Graph`'s static initializer dies with
#
#     java.lang.IllegalStateException: no caller found on the stack for: Z1.d
#
# taking the whole process with it the first time the blur service starts. It is
# release-only — debug builds are unminified — and it happens *before* any of our
# code runs, so nothing we write can catch it.
-keep class com.google.common.flogger.** { *; }
-keepnames class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# Protobuf-lite, which MediaPipe serialises its graph config through.
#
# protobuf-lite does not generate field accessors — it looks fields up *by name*
# at runtime from a schema string. Rename them and it fails with
#
#     Field typeUrl_ for com.google.protobuf.Any not found.
#     Known fields are [... com.google.protobuf.Any.d, ... Any.e]
#
# which is R8 having shortened `typeUrl_` to `d`. Release-only, and it surfaces
# as "detection models unavailable" — the app runs, the switch reads on, and
# nothing is ever covered.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**
