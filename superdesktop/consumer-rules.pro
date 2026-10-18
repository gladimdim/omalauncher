# Tink (under EncryptedSharedPreferences) refers to Error Prone's annotations, which are compile-time only.
-dontwarn com.google.errorprone.annotations.**
# Tink's keysets are protobuf-lite messages, read by reflection over their fields.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
