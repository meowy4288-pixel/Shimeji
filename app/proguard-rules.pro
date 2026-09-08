# App Proguard (no minify in this milestone).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.delpa.shimeji.** { *** Companion; }
-keepclasseswithmembers class dev.delpa.shimeji.** {
    kotlinx.serialization.KSerializer serializer(...);
}