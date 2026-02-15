# Bridge ProGuard Rules

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all model classes
-keep class com.bridge.model.** { *; }

# Keep AccessibilityService
-keep class com.bridge.BridgeAccessibilityService { *; }
