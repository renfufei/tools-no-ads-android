# zip4j
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# junrar
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-keep class org.apache.commons.vfs2.** { *; }
-dontwarn org.apache.commons.vfs2.**

# slf4j (referenced by junrar)
-dontwarn org.slf4j.**

# zxing (QR code)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
