# ---- JNI ----------------------------------------------------------------------------------------
# Native code resolves classes and methods by name (`Java_..._name` symbols, FindClass for the exception
# types, GetMethodID for listener callbacks), so none of them may be renamed or removed.

# Every engine class (native methods, exceptions thrown from C++, listeners called from C++).
-keep class com.qtekfun.ultimatevideoeditor.engine.** { *; }

# Classes outside `engine` that declare native methods (their JNI symbol names contain the class name).
-keepclasseswithmembers class * {
    native <methods>;
}

# Callback methods that C++ looks up on listener objects by name and signature, wherever they are implemented
# (anonymous classes in ui/ and proxy/ included).
-keepclassmembers class * {
    void onProgress(int);
    void onFinished(int, java.lang.String);
    void onError(int, java.lang.String, long);
    void onWaveformReady(long, int);
    void onThumbnailError(long, int, java.lang.String);
}

# ---- Serialization -----------------------------------------------------------------------------
# kotlinx.serialization ships consumer rules; keep generated serializers and companions of our own
# @Serializable classes explicitly as well, because project.json is read and written through them.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepclassmembers @kotlinx.serialization.Serializable class com.qtekfun.ultimatevideoeditor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
    static ** $serializer;
}
-keep,includedescriptorclasses class com.qtekfun.ultimatevideoeditor.**$$serializer { *; }
-keepclasseswithmembers class com.qtekfun.ultimatevideoeditor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Enum constant names are written to project.json.
-keepclassmembers enum com.qtekfun.ultimatevideoeditor.** { *; }

# Keep stack traces readable in the local crash report.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
