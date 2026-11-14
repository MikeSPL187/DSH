# Reflection-based instrumentation and JUnit discovery run in the controller APK.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class com.dsh.benchmark.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.benchmark.** { *; }
-keep class org.junit.** { *; }
