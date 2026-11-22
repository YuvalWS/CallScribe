# The Anthropic SDK and Jackson use reflection for (de)serialization of model classes.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
-keep class kotlin.Metadata { *; }

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType