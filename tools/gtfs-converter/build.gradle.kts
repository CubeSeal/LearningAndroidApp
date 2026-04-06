plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}

application {
    mainClass.set("com.example.gtfsconverter.MainKt")
}

// Fat jar for easy CI execution
tasks.jar {
    manifest { attributes["Main-Class"] = "com.example.gtfsconverter.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

