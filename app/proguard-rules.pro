-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-keepattributes *Annotation*,JavascriptInterface,InnerClasses,EnclosingMethod,Signature

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Preserve MantaNative JNI bridge to libmanta_core.so
-keep class app.manta.browser.core.MantaNative {
    *;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve @JavascriptInterface methods on MantaJsBridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class app.manta.browser.ui.render.EphemeralWebSandbox$MantaJsBridge {
    *;
}

-keep class app.manta.browser.MainActivity {
    *;
}

-keep class app.manta.browser.ui.** {
    *;
}
