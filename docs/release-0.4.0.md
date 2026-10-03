# Javadrift 0.4.0 preview

This release adds `javadrift doctor` for diagnosing Java/compiler availability, configuration, selected docs and production source paths, compiled outputs, dependency inputs, and baselines. It gives actionable advice in text or JSON without analyzing stale references or modifying project files. Exit codes distinguish readiness (0), setup warnings (1), and invalid inputs (2). When snippet compilation is enabled, an in-memory probe checks the configured Java release.

A standalone Maven CI example uses the published plugin without building or installing Javadrift locally. Its isolated walkthrough starts with an empty local repository and tests valid, stale strict/warn-only, invalid-config, and restored cases. The CLI walkthrough now covers 33 scenarios across Linux, macOS, and Windows on JDK 17 and 21.

The CLI and build adapters require Java 17 or newer. The preview retains the nine documentation checks, baseline maintenance, isolated Gradle analysis, and Java/Kotlin source indexing from 0.3.1. It does not claim broad accuracy from the small reviewed corpus.

Maven publication is verified separately from the GitHub release. The initial Gradle Plugin Portal submission remains pending approval; the 0.4.0 plugin update will follow that review. See the publishing documentation for current registry status.
