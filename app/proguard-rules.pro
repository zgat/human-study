# Filament's JNI looks up these Java classes and members by their original names.
-keep class com.google.android.filament.** { *; }
-keep class com.zga.meridianatlas.data.AtlasDatabase_Impl { *; }
# Keep application entry points available for same-signature device instrumentation.
-keep class com.zga.meridianatlas.** { *; }
