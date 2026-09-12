dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.execution")
        bundledModule("intellij.platform.externalSystem")
        bundledModule("intellij.platform.externalSystem.impl")
        bundledModule("intellij.platform.ide.impl")
        bundledModule("intellij.platform.lang.impl")
        bundledModule("intellij.platform.lsp")
        bundledModule("intellij.platform.rpc.backend")
    }

    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}
