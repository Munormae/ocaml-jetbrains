package dev.munormae.project

import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import dev.munormae.OCamlBundle
import dev.munormae.icons.OCamlIcons
import dev.munormae.dune.run.DuneCommand
import dev.munormae.dune.run.DuneRunConfigurationSpec
import dev.munormae.toolchain.DEFAULT_DUNE_LANGUAGE_VERSION
import java.util.Locale
import javax.swing.Icon

class OCamlNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name: String = "OCaml"
    override val icon: Icon = OCamlIcons.File
    override val ordinal: Int = 150

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = OCamlEnvironmentProjectStep(parent)
}

internal enum class OCamlProjectTemplate {
    MINIMAL,
    APPLICATION,
    LIBRARY,
    APPLICATION_WITH_LIBRARY;

    override fun toString(): String = when (this) {
        MINIMAL -> OCamlBundle.message("wizard.project.minimal")
        APPLICATION -> OCamlBundle.message("wizard.project.application")
        LIBRARY -> OCamlBundle.message("wizard.project.library")
        APPLICATION_WITH_LIBRARY -> OCamlBundle.message("wizard.project.application.library")
    }

    fun entryFile(projectName: String): String = when (this) {
        MINIMAL -> "main.ml"
        APPLICATION -> "bin/main.ml"
        LIBRARY -> "lib/$projectName.ml"
        APPLICATION_WITH_LIBRARY -> "bin/main.ml"
    }
}

internal fun createProjectFiles(
    projectName: String,
    template: OCamlProjectTemplate,
    addTests: Boolean,
    duneLanguageVersion: String = DEFAULT_DUNE_LANGUAGE_VERSION,
): LinkedHashMap<String, String> {
    val languageVersion = normalizeDuneLanguageVersion(duneLanguageVersion)
    if (template == OCamlProjectTemplate.MINIMAL) {
        return linkedMapOf(
            "dune-project" to "(lang dune $languageVersion)\n(name $projectName)\n",
            "dune" to "(executable\n (name main))\n",
            "main.ml" to "let () = print_endline \"Hello from OCaml!\"\n",
            ".ocamlformat" to "profile = conventional\n",
            ".gitignore" to "_build/\n_opam/\n",
        )
    }

    val files = linkedMapOf(
        "dune-project" to """
            (lang dune $languageVersion)
            (name $projectName)
            (generate_opam_files true)

            (package
             (name $projectName)
             (synopsis "An OCaml project"))
        """.trimIndent() + "\n",
        ".ocamlformat" to "profile = conventional\n",
        ".gitignore" to """
            _build/
            _opam/
            *.install
            *.merlin
        """.trimIndent() + "\n",
        "README.md" to "# $projectName\n\nGenerated OCaml project using Dune.\n",
    )

    when (template) {
        OCamlProjectTemplate.MINIMAL -> error("Minimal projects are generated before this branch")

        OCamlProjectTemplate.APPLICATION -> {
            files["bin/dune"] = """
                (executable
                 (name main)
                 (public_name $projectName))
            """.trimIndent() + "\n"
            files["bin/main.ml"] = "let () = print_endline \"Hello from OCaml!\"\n"
        }

        OCamlProjectTemplate.LIBRARY -> addLibrary(files, projectName)

        OCamlProjectTemplate.APPLICATION_WITH_LIBRARY -> {
            addLibrary(files, projectName)
            files["bin/dune"] = """
                (executable
                 (name main)
                 (public_name $projectName-cli)
                 (libraries $projectName))
            """.trimIndent() + "\n"
            files["bin/main.ml"] =
                "let () = print_endline (${moduleName(projectName)}.greeting ())\n"
        }
    }

    if (addTests) {
        files["test/dune"] = if (template == OCamlProjectTemplate.APPLICATION) {
            """
                (test
                 (name test_main))
            """.trimIndent() + "\n"
        } else {
            """
                (test
                 (name test_main)
                 (libraries $projectName))
            """.trimIndent() + "\n"
        }
        files["test/test_main.ml"] = if (template == OCamlProjectTemplate.APPLICATION) {
            "let () = assert (1 + 1 = 2)\n"
        } else {
            "let () = assert (${moduleName(projectName)}.greeting () <> \"\")\n"
        }
    }

    return files
}

private fun addLibrary(files: MutableMap<String, String>, projectName: String) {
    files["lib/dune"] = """
        (library
         (name $projectName)
         (public_name $projectName))
    """.trimIndent() + "\n"
    files["lib/$projectName.mli"] = "val greeting : unit -> string\n"
    files["lib/$projectName.ml"] = "let greeting () = \"Hello from $projectName!\"\n"
}

internal fun sanitizeProjectName(rawName: String): String {
    var result = rawName.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifEmpty { "ocaml_project" }
    if (result.first().isDigit()) result = "project_$result"
    return result
}

internal fun moduleName(projectName: String): String =
    projectName.replaceFirstChar { it.uppercaseChar() }

internal fun normalizeDuneLanguageVersion(rawVersion: String): String =
    DUNE_LANGUAGE_VERSION.matchEntire(rawVersion.trim())?.value ?: DEFAULT_DUNE_LANGUAGE_VERSION

internal fun generatedProjectRunConfigurations(
    template: OCamlProjectTemplate,
    projectName: String,
    addTests: Boolean,
): List<DuneRunConfigurationSpec> = buildList {
    add(DuneRunConfigurationSpec(DuneCommand.BUILD, "Dune Build"))
    when (template) {
        OCamlProjectTemplate.MINIMAL ->
            add(DuneRunConfigurationSpec(DuneCommand.EXEC, "Dune Run main", "./main.exe"))

        OCamlProjectTemplate.APPLICATION ->
            add(
                DuneRunConfigurationSpec(
                    DuneCommand.EXEC,
                    "Dune Run $projectName",
                    "./bin/main.exe",
                    legacyTarget = projectName,
                ),
            )

        OCamlProjectTemplate.LIBRARY -> Unit

        OCamlProjectTemplate.APPLICATION_WITH_LIBRARY -> {
            val executable = "$projectName-cli"
            add(
                DuneRunConfigurationSpec(
                    DuneCommand.EXEC,
                    "Dune Run $executable",
                    "./bin/main.exe",
                    legacyTarget = executable,
                ),
            )
        }
    }
    if (addTests && template != OCamlProjectTemplate.MINIMAL) {
        add(DuneRunConfigurationSpec(DuneCommand.TEST, "Dune Test"))
    }
}

private val DUNE_LANGUAGE_VERSION = Regex("""\d+\.\d+""")
