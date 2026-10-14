# JNA and the UniFFI bindings are reached through reflection and native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class app.yacana.engine.ffi.** { *; }
-dontwarn java.awt.**
