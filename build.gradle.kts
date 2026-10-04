plugins {
    java
    id("com.azuredoom.hytale-tools") version "1.+"
}

tasks.withType<Javadoc>().configureEach {
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).addStringOption("Xdoclint:-missing", "-quiet")
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    patchline = property("patchline").toString()
    injectServerJavadocsIntoSources = property("injectServerJavadocsIntoSources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
}

repositories {
    mavenCentral()
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(rootProject.name)
    archiveVersion.set(project.property("version").toString())
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // hytaleTools adds the Server jar to compileClasspath only; tests that touch engine types
    // (org.bson, org.joml, Ref, codecs) need it mirrored onto the test classpath.
    testImplementation(files(configurations.compileClasspath))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
