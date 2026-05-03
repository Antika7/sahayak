# RFC 002: Build System Optimization and Gradle Adjustments

## Status
Accepted

## Context
As the project evolved to include MediaPipe and modern Jetpack Compose dependencies, the build times increased and the local development environment became prone to `OutOfMemoryError` during the Gradle build phase. Additionally, the project was running on a newer Gradle version (9.0.0) that caused compatibility friction, and Jetifier was enabled unnecessarily.

## Decision
We have optimized the build system by applying the following changes to the Gradle configuration:

1. **Gradle JVM Arguments (`gradle.properties`)**:
   - Increased the maximum heap size for the Gradle daemon from 2GB to 4GB (`-Xmx4g`).
   - Added a metaspace cap (`-XX:MaxMetaspaceSize=1g`) to prevent aggressive native memory consumption during Kotlin compilation.

2. **Jetifier Disabled (`gradle.properties`)**:
   - Disabled `android.enableJetifier`. The project strictly uses modern AndroidX libraries. Removing the Jetifier overhead significantly speeds up dependency resolution and the overall build process.

3. **Gradle Wrapper Downgrade (`gradle-wrapper.properties`)**:
   - Downgraded the distribution URL from Gradle 9.0.0 to Gradle 8.7 to ensure better compatibility with the current Android Gradle Plugin and MediaPipe tasks-genai dependencies.

4. **Dependency Modernization (`build.gradle.kts`)**:
   - Updated `kotlinCompilerExtensionVersion` from 1.5.1 to 1.5.14 for better Compose compatibility.
   - Added the Material Components dependency (`com.google.android.material:material:1.12.0`) to resolve XML theme requirements.

## Consequences
- **Positive**: Faster build times, zero Gradle OOM crashes, and a more stable local development environment.
- **Negative**: Developers must ensure their local machines have enough RAM to allocate 4GB strictly for the Gradle daemon.
