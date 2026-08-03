# The instrumentation test drives the app through this single entry point; R8
# must keep it (and, transitively, whatever the library's consumer rules keep).
-keep class ai.botisan.tantivy.minifiedsmoke.MinifiedSmoke { *; }

# The test APK shares this APK's classpath and androidx.test needs Kotlin
# stdlib surface R8 would otherwise strip from the app. Keeping the stdlib does
# not weaken the check this app exists for: the library facade, UniFFI
# bindings and JNA still shrink under the library's consumer rules.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
