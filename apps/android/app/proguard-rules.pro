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
