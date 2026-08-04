# androidx.test references compile-only annotation libraries; the test APK's R8
# pass must not treat those absent classes as errors.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
