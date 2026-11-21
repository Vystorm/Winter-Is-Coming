plugins { java }
group = "at.vystorm"
version = "1.25.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
val coreJar = providers.gradleProperty("vystormCoreJar").orElse("libs/Vystorm_Core.jar")
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly(files(coreJar))
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation(files(coreJar))
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
// The compiler/test JVM can read the current API while our own bytecode stays on Java 21.
configurations.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 21 }

val paper1206Libraries = fileTree(providers.gradleProperty("paper1206Libraries")
    .orElse((System.getProperty("user.home") + "/source/tools/smoke-1206/libraries"))) { include("**/*.jar") }
val compat1206 by tasks.registering(JavaCompile::class) {
    description = "Compiles main sources against the offline Paper 1.20.6 libraries."
    group = "verification"
    source = sourceSets.main.get().allJava
    classpath = paper1206Libraries + files(coreJar)
    destinationDirectory = layout.buildDirectory.dir("classes/compat1206")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
}
tasks.check { dependsOn(compat1206) }

val compat1206Test by tasks.registering(Test::class) {
    description = "Runs compatibility tests with the release classes and Paper 1.20.6."
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output +
        paper1206Libraries + files(coreJar) + configurations.testRuntimeClasspath.get().filter {
            !it.name.startsWith("paper-api-") && !it.name.startsWith("adventure-")
        }
    useJUnitPlatform()
    include("**/WinterCompatibilityTest.class")
}
tasks.check { dependsOn(compat1206Test) }
tasks.test { useJUnitPlatform() }
tasks.processResources { inputs.property("version", project.version); filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar { archiveFileName = "WinterIsComing-${project.version}.jar"; from("LICENSE") }
val integration by sourceSets.creating {
    java.setSrcDirs(listOf("test-harness/src"))
    resources { setSrcDirs(listOf("test-harness")); include("plugin.yml") }
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("integrationJar") { dependsOn(tasks.named(integration.classesTaskName)); from(integration.output); archiveFileName = "WinterAcceptance.jar" }
val visual by sourceSets.creating {
    java.setSrcDirs(listOf("test-harness/visual-src"))
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("visualJar") {
    dependsOn(tasks.named(visual.classesTaskName))
    from(visual.output)
    from("test-harness/visual-plugin.yml") { rename { "plugin.yml" } }
    archiveFileName = "WinterVisual.jar"
}

tasks.register<Jar>("naturalVisualJar") {
    dependsOn(tasks.named(visual.classesTaskName))
    from(visual.output)
    from("test-harness/natural-plugin.yml") { rename { "plugin.yml" } }
    archiveFileName = "WinterNaturalVisual.jar"
}

val stackAcceptance by sourceSets.creating {
    java.setSrcDirs(listOf("test-harness/stack-src"))
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("stackAcceptanceJar") {
    dependsOn(tasks.named(stackAcceptance.classesTaskName))
    from(stackAcceptance.output)
    from("test-harness/stack-plugin.yml") { rename { "plugin.yml" } }
    archiveFileName = "WinterStackAcceptance.jar"
}

tasks.register<Jar>("packetVisualJar") {
    dependsOn(tasks.named(visual.classesTaskName))
    from(visual.output)
    from("test-harness/packet-plugin.yml") { rename { "plugin.yml" } }
    archiveFileName = "WinterPacketVisual.jar"
}
