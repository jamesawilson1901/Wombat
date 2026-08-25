# Magpie ships as a debug APK today; these rules exist so a release build is
# not a surprise later. Nothing Magpie itself writes is reflective, so the
# defaults suffice for its own code.
-dontwarn org.jetbrains.annotations.**

# ---- Anthropic SDK ---------------------------------------------------------
# The SDK serialises its request and response models with Jackson, which reads
# them reflectively, so the shrinker cannot see those uses and would rename the
# fields out from under it. These rules are carried untested: CI builds the
# debug APK, so no release build has yet exercised them.
-keep class com.anthropic.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**

# OkHttp and Okio reference JVM-only and optional pieces that are simply absent
# on Android; naming them here stops the shrinker warning about each one.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
