# ONNX Runtime: le classi Java sono chiamate dal codice nativo (JNI), non vanno rinominate né rimosse
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Classi dell'app: nessuna riflessione, ma si tengono i nomi per log e stack trace leggibili
-keepnames class com.marco.magictouchremove.** { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn ai.onnxruntime.**
