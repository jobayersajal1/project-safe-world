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
