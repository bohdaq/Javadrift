Javadrift 0.3.0 improves the JVM CLI and build adapters with reviewed documentation contexts, isolated Gradle analysis, optional-check examples and baseline maintenance. Requires JDK 17+.

- `baseline --check` audits unused accepted fingerprints without changing the file. `baseline --prune` removes them without accepting new findings. All baseline modes support explicit class directories and native dependency classpaths.
- Scoped documentation exceptions and conservative reference resolution reduce false positives. The configured, manually triaged corpus covers 20 pinned repositories and 477 documents with 12 verified errors and no observed false positives; this does not establish accuracy on unseen projects or recall.
- Gradle runs analysis in an isolated worker so the embedded Kotlin parser stays off the consuming build's plugin classpath. Malformed input fails the build even in warn-only mode.
- A Jackson-backed application demonstrates opt-in Java snippet compilation and production property checks through the CLI, Maven and Gradle, with reviewed stale variants and automatic adapter dependency classpaths.
- The packaged CLI is tested on Linux, macOS and Windows with JDK 17 and 21. Kotlin Gradle integration is checked with versions 2.2.21, 2.3.21 and 2.4.20 on the pinned Gradle wrapper.
- CI guards the pinned corpus and real history replays, and compares time and per-JVM peak memory against the reference build on large Java and mixed Java/Kotlin fixtures.

Download `javadrift.jar` and `javadrift.jar.sha256`, verify the checksum, and run `java -jar javadrift.jar check --root /path/to/project --warn-only`. Review findings before enforcing failures. JD008 and JD009 remain opt-in; Kotlin snippet compilation and full Kotlin type resolution remain outside this preview.

Registry publication is tracked separately from this CLI release. See `docs/publishing.adoc` for Maven Central and Gradle Plugin Portal setup; local builds remain supported until registry publication is verified.
