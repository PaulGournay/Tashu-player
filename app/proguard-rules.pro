-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.tashu.player.**$$serializer { *; }
-keepclassmembers class com.tashu.player.** { *** Companion; }
-keepclasseswithmembers class com.tashu.player.** { kotlinx.serialization.KSerializer serializer(...); }
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Filament / gltfio / SceneView : classes appelées depuis le code natif (JNI), à ne pas renommer ni supprimer.
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn com.google.android.filament.**
