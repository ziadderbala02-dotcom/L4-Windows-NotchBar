# Notch Live: R8 keeps what the manifest names (activities, services, receivers, the provider) by itself.
# The app reads JSON by hand (no reflection), so nothing else needs keeping.

# Missing optional classes in Play services / ML Kit shouldn't stop the build.
-ignorewarnings
-dontwarn com.google.**
