# JNA + UniFFI-generated bindings rely on reflection / native dispatch.
-keep class com.sun.jna.** { *; }
-keep class ai.botisan.hnsw.ffi.** { *; }
-dontwarn java.awt.*
