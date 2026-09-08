// Root build script for ShimejiHarness.
//
// Version set (chosen 2024-era, mutually compatible, verified against official docs):
//   - Android Gradle Plugin         8.2.0  (requires Gradle >= 8.2, JDK 17+)
//   - Gradle wrapper                8.4    (supports JDK 17 and 21)
//   - Kotlin                        1.9.22
//   - Compose compiler              1.5.8  (matches Kotlin 1.9.22)
//   - Compose BOM                   2024.01.00
//   - kotlinx.coroutines            1.7.3
//   - kotlinx.serialization         1.6.2
//
// compileSdk 34 / targetSdk 34 / minSdk 26.
//  - Target SDK 34 matches current Play/Google requirements for foreground-service
//    types and POST_NOTIFICATIONS runtime permission.
//  - minSdk 26 gives TYPE_APPLICATION_OVERLAY (API 26+) and NotificationChannel
//    without legacy fallback code.

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

// Shared version catalog values used by all modules.
ext {
    set("minSdk", 26)
    set("compileSdk", 34)
    set("targetSdk", 34)
    set("coroutines", "1.7.3")
    set("serialization", "1.6.2")
}