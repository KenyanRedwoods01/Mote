# Preserve annotations, signatures, and Kotlin metadata for reflection/serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# Mote application models, AI tool protocols, Room entities/DAOs, control & service components
-keep class com.mote.app.ai.** { *; }
-keep class com.mote.app.model.** { *; }
-keep class com.mote.app.data.db.** { *; }
-keep class com.mote.app.control.** { *; }
-keep class com.mote.app.service.** { *; }

# kotlinx.serialization generated serializers & companion objects
-keep,includedescriptorclasses class com.mote.app.**$$serializer { *; }
-keepclassmembers class com.mote.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mote.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase AI Logic, App Check, Firestore & Auth
-keep class com.google.firebase.ai.** { *; }
-keep class com.google.firebase.appcheck.** { *; }
-keep class com.google.firebase.firestore.** { *; }

# Android AICore / ML Kit GenAI Prompt & Google AI Edge LiteRT-LM native JNI bridges
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.mlkit.genai.**

# Lottie animations
-keep class com.airbnb.lottie.** { *; }
