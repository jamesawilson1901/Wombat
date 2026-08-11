# Debug builds are what get sideloaded; release rules kept minimal.
-keepattributes *Annotation*, InnerClasses
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
# kotlinx.serialization
-keepclassmembers class com.magpie.** {
    *** Companion;
}
-keepclasseswithmembers class com.magpie.** {
    kotlinx.serialization.KSerializer serializer(...);
}
