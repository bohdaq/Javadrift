Javadrift 0.2.0 extends the JVM CLI preview with Kotlin source and Git-history indexing, deeper documentation validation and registry publishing preparation. Requires JDK 17+.

- Kotlin declarations can be checked without building a project: classes, interfaces, objects, companions, properties, functions, type aliases, project extensions, defaults and trailing varargs. Kotlin parsing is syntax-based; full type resolution, compiler plugins and Kotlin snippet compilation remain outside this preview.
- Antora module page references resolve correctly. Omitted arguments written as `...` do not produce signature warnings.
- The expanded 20-repository stress scan covers 477 documents without input errors. The fixes remove 1,356 reports from the original pass; 580 remaining findings are not fully triaged, so broader accuracy is still unproven.
- Two naturally stale upstream revisions are detected and cleared by their actual later documentation fixes, supplementing five reconstructed refactoring replays.
- Javadrift runs its released Action in its own CI with warn-only reporting and a saved SARIF artifact.
- Maven Central and Plugin Portal publication configuration is prepared and tested locally. Registry uploads still need account credentials and a signing key; see docs/publishing.adoc.

Download `javadrift.jar` and `javadrift.jar.sha256`, verify the checksum, then run `java -jar javadrift.jar check --root /path/to/project --warn-only`. The Kotlin parser increases the download by roughly 60 MB. Review findings before enforcing failures. JD008 and JD009 remain opt-in.
