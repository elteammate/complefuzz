plugins {
    kotlin("jvm") version "2.1.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    api("org.soot-oss:sootup.core:2.0.0")
    api("org.soot-oss:sootup.java.core:2.0.0")
    api("org.soot-oss:sootup.java.sourcecode.frontend:2.0.0")
    api("org.soot-oss:sootup.java.bytecode.frontend:2.0.0")
    api("org.soot-oss:sootup.jimple.frontend:2.0.0")
    api("org.soot-oss:sootup.apk.frontend:2.0.0")
    api("org.soot-oss:sootup.callgraph:2.0.0")
    api("org.soot-oss:sootup.analysis.intraprocedural:2.0.0")
    api("org.soot-oss:sootup.analysis.interprocedural:2.0.0")
    api("org.soot-oss:sootup.qilin:2.0.0")
    api("org.soot-oss:sootup.codepropertygraph:2.0.0")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.3")
    implementation("com.palantir.javapoet:javapoet:0.6.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}