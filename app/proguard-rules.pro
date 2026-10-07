# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.targetx.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.targetx.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
