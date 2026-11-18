# Java4Shell — CVE-2026-114938

This Android repository builds using **Java 17 / OpenJDK** in GitHub Actions.
The Android application itself runs on **ART**, not the affected OpenJDK HotSpot
runtime; this hardening applies to Gradle, Java-based Android SDK tools, JVM
unit tests and release build pipelines, rather than to the APK runtime.

The CI environment temporarily sets
`JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics`, and a dedicated verification
workflow asserts that the JVM has disabled the affected Base64 intrinsic.

**For local builds and self-hosted CI**, install a JDK/JRE release that your
vendor confirms contains the CVE-2026-114938 fix. Until it is patched, set
`JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics` for Java tools and restart
any existing Java/Gradle daemons. Check via
`java -XX:+PrintFlagsFinal -version` and `UseBASE64Intrinsics = false`.
PowerShell: `$env:JAVA_TOOL_OPTIONS = '-XX:-UseBASE64Intrinsics'`.

See [CERT-EU](https://www.cert.europa.eu/publications/security-advisories/2026-021/)
and [Adoptium](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/).
