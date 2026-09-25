# org.json and platform classes need no rules. Keep widget/worker entry points (referenced from manifest/WorkManager).
-keep class app.orionmd.marketdata.work.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
