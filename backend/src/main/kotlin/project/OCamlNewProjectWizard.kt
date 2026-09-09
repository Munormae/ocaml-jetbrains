package dev.munormae.project

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Panel
import dev.munormae.icons.OCamlIcons
import dev.munormae.settings.OCamlProjectSettings
import java.util.Locale
import javax.swing.Icon
import javax.swing.JComboBox

class OCamlNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name: String = "OCaml"
    override val icon: Icon = OCamlIcons.File
    override val ordinal: Int = 150

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = OCamlProjectStep(parent)
}

private class OCamlProjectStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val template = JComboBox(OCamlProjectTemplate.entries.toTypedArray())
    private val addTests = JBCheckBox("Add a sample test", false)
    private val useOpam = JBCheckBox("Run language tools through opam", true)
    private val startDuneWatch = JBCheckBox("Run dune build --watch for richer diagnostics", true)
    private val opamSwitch = JBTextField()

    init {
        template.addActionListener { updateTemplateControls() }
        useOpam.addActionListener { opamSwitch.isEnabled = useOpam.isSelected }
        updateTemplateControls()
    }

    override fun setupUI(builder: Panel) {
        builder.row("Template:") {
            cell(template)
        }
        builder.row {
            cell(addTests)
        }
        builder.row {
            cell(useOpam)
        }
        builder.row {
            cell(startDuneWatch)
        }
        builder.row("opam switch:") {
            cell(opamSwitch)
                .comment("Optional. Leave empty to use the current opam switch.")
        }
    }

    override fun setupProject(project: Project) {
        val baseData = checkNotNull(data.getUserData(NewProjectWizardBaseData.KEY)) {
            "New Project Wizard base data is unavailable"
        }
        val projectName = sanitizeProjectName(baseData.name)
        val selectedTemplate = template.selectedItem as? OCamlProjectTemplate ?: OCamlProjectTemplate.MINIMAL
        val generatedFiles = createProjectFiles(projectName, selectedTemplate, addTests.isSelected)
        var fileToOpen: VirtualFile? = null

        ApplicationManager.getApplication().runWriteAction {
            val root = VfsUtil.createDirectories(baseData.contentEntryPath)
            for ((relativePath, contents) in generatedFiles) {
                val parentPath = relativePath.substringBeforeLast('/', "")
                val fileName = relativePath.substringAfterLast('/')
                val directory = if (parentPath.isEmpty()) root else createRelativeDirectory(root, parentPath)
                val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
                VfsUtil.saveText(file, contents)
                if (relativePath == selectedTemplate.entryFile(projectName)) fileToOpen = file
            }
        }

        OCamlProjectSettings.getInstance(project).state.apply {
            useOpam = this@OCamlProjectStep.useOpam.isSelected
            opamSwitch = this@OCamlProjectStep.opamSwitch.text.trim()
            duneWatchEnabled = this@OCamlProjectStep.startDuneWatch.isSelected
        }
        OCamlProjectSettings.getInstance(project).notifyChanged()

        fileToOpen?.let { file ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && file.isValid) {
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        }
    }

    private fun updateTemplateControls() {
        val isMinimal = template.selectedItem == OCamlProjectTemplate.MINIMAL
        if (isMinimal) addTests.isSelected = false
        addTests.isEnabled = !isMinimal
    }

    private fun createRelativeDirectory(root: VirtualFile, relativePath: String): VirtualFile {
        var current = root
        for (name in relativePath.split('/')) {
            current = current.findChild(name) ?: current.createChildDirectory(this, name)
        }
        return current
    }
}

internal enum class OCamlProjectTemplate(private val label: String) {
    MINIMAL("Minimal"),
    APPLICATION("Executable"),
    LIBRARY("Library"),
    APPLICATION_WITH_LIBRARY("Executable + library");

    override fun toString(): String = label

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
): LinkedHashMap<String, String> {
    if (template == OCamlProjectTemplate.MINIMAL) {
        return linkedMapOf(
            "dune-project" to "(lang dune 3.17)\n(name $projectName)\n",
            "dune" to "(executable\n (name main))\n",
            "main.ml" to "let () = print_endline \"Hello from OCaml!\"\n",
            ".ocamlformat" to "profile = conventional\n",
            ".gitignore" to "_build/\n_opam/\n",
        )
    }

    val files = linkedMapOf(
        "dune-project" to """
            (lang dune 3.17)
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
