plugins {
    java
}

group = "dev.scalepaper"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.118.Final")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
