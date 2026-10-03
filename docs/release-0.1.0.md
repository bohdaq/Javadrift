Javadrift 0.1.0 is the first JVM CLI preview for detecting stale Java documentation in Markdown and AsciiDoc. Requires JDK 17+.

Download `javadrift.jar` and `javadrift.jar.sha256`, verify the checksum, then run `java -jar javadrift.jar check --root /path/to/project --warn-only`. Review findings before enforcing failures.

- Nine check IDs, Git history comparisons, baselines, text/JSON/GitHub/SARIF output.
- Maven and Gradle adapters in source; not yet published to package registries.
- GitHub Action: `bohdaq/Javadrift/javadrift-action@v0.1.0`, with `version: v0.1.0` and a JDK 17+ runner.
- Kotlin supported through compiled classes; source-only Kotlin and Kotlin history remain pending.
- Twenty pinned repositories scanned with zero input errors. Three findings manually confirmed, zero observed false positives. Only 22 documents and three positive findings: this does not establish broad precision or recall.
- Five real upstream refactorings replayed with parent documentation restored: old references detected, updated documentation clears them. These are reconstructed stale cases.

JD008 snippet compilation and JD009 configuration checks are opt-in. See README.adoc and docs/validation.adoc for configuration, scope and evidence.
