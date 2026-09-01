-keepattributes *Annotation*, InnerClasses, Signature

# --- kotlinx.serialization ---
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class stream.cliamp.mobile.**$$serializer { *; }
-keepclassmembers class stream.cliamp.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class stream.cliamp.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room ---
# Glance uses WorkManager for widget updates, WorkManager stores its queue in a
# Room database, and Room resolves its generated implementation by NAME:
#   Class.forName("androidx.work.impl.WorkDatabase_Impl")
# R8 full mode (the AGP 9 default) renames that class, the lookup throws, and
# the app dies inside androidx.startup before Application.onCreate runs. Keeping
# the members is not enough here - the class name itself has to survive.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepnames class * extends androidx.room.RoomDatabase
-keep class **_Impl extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- WorkManager ---
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class androidx.work.impl.** { <init>(...); }
-keepnames class androidx.work.impl.WorkDatabase_Impl

# --- Glance ---
-keep class androidx.glance.appwidget.protobuf.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
-keep class * extends androidx.glance.appwidget.action.ActionCallback
-dontwarn androidx.glance.**

# --- Media3 ---
-dontwarn androidx.media3.**
