# The release isn't shrunk yet (app/build.gradle.kts); these rules are in place for when it is.

# Room: Room.databaseBuilder makes the generated <Name>_Impl with Class.newInstance().
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# WorkManager: its default WorkerFactory makes each worker by (Context, WorkerParameters).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * implements androidx.startup.Initializer { <init>(); }
# lib-recur reads its rule parts through enums and converters by name.
-keep class org.dmfs.rfc5545.** { *; }
