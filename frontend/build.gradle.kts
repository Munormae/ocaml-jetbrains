dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }

    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}
