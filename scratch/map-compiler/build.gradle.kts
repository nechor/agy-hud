plugins {
    application
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.graphhopper:graphhopper-core:8.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("com.fasterxml:aalto-xml:1.3.2")
    implementation("javax.xml.stream:stax-api:1.0-2")
}

application {
    mainClass.set("MapCompilerKt")
}
