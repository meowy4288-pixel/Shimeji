// Proguard rules for the overlay library.
# kotlinx.serialization keeps generated serializers; plugins are compiled-in,
# so no reflective lookup of plugin classes is required.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.delpa.shimeji.** {
    *** Companion;
}
-keepclasseswithmembers class dev.delpa.shimeji.** {
    kotlinx.serialization.KSerializer serializer(...);
}