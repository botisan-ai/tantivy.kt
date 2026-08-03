# The instrumentation test drives the app through this single entry point; R8
# must keep it (and, transitively, whatever the library's consumer rules keep).
-keep class ai.botisan.tantivy.minifiedsmoke.MinifiedSmoke { *; }
