# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep ECJ classes
-keep class org.eclipse.jdt.** { *; }

# Keep R8/D8 classes
-keep class com.android.tools.r8.** { *; }

# Keep Gson classes
-keep class com.google.gson.** { *; }

# Keep our model classes
-keep class com.larv.ide.model.** { *; }

# Keep compiler classes
-keep class com.larv.ide.compiler.** { *; }

# Keep completion classes
-keep class com.larv.ide.completion.** { *; }

# Keep project classes
-keep class com.larv.ide.project.** { *; }

# Keep UI classes
-keep class com.larv.ide.ui.** { *; }

# Keep WebView JavaScript interface
-keepclassmembers class com.larv.ide.ui.fragment.EditorFragment$EditorBridge {
    @android.webkit.JavascriptInterface <methods>;
}