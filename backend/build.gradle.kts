dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.execution")
        bundledModule("intellij.platform.ide.impl")
        bundledModule("intellij.platform.lsp")
    }

    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}
