# Consumer rules shipped inside the AAR and applied to the host app's R8 run.

# Public API used from Java/Kotlin host code.
-keep public class com.codemax.adsanalytics.** { public protected *; }
-keep interface com.codemax.adsanalytics.** { *; }

# In-house ad screen is started by class name from the merged manifest.
-keep class com.codemax.adsanalytics.ads.InHouseAdActivity { *; }

# Optional integrations are reached only after a Class.forName() presence check.
# If the host does not ship them, R8 must not fail on the missing references.
-dontwarn com.google.firebase.**
-dontwarn com.facebook.**

# Google Mobile Ads / UMP / Install Referrer ship their own consumer rules.
