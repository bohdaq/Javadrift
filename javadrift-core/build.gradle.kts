plugins { `java-library` }
dependencies {
    api("org.commonmark:commonmark:0.24.0")
    implementation("com.github.javaparser:javaparser-core:3.28.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
