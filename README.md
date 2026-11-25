# Y video

A fast, offline, ad-free video player for Android. Kotlin, Jetpack Compose, Media3. Arabic and English. Violet/magenta on dark.

مشغّل فيديو سريع للأندرويد، بدون إنترنت وبدون إعلانات. عربي وإنجليزي.

## Build / البناء
- Android Studio (recent) or Gradle 8.4+, JDK 17, Android SDK 34.
- Open the folder in Android Studio, or run `gradle assembleRelease`.
- Without `signing.properties` the release build is signed with the debug key. To use your own key, copy `signing.properties.example` to `signing.properties`.
- Optional: `-PshareUrl=https://your.site/y-video` sets the link used by the in-app share button.
- افتح المجلد في Android Studio أو شغّل `gradle assembleRelease`. للتوقيع بمفتاحك انسخ `signing.properties.example` إلى `signing.properties`.

## Contributing / المساهمة
Pull requests are welcome. Keep it smooth: no layout animations on lists, no ads, no trackers, no green in the palette. المساهمات مرحّب بها.

## License / الرخصة
Apache-2.0, Copyright 2026 Yehia Abu Almagd. See `LICENSE` and `NOTICE`. Great Vibes font: SIL OFL 1.1 (`licenses/`).
