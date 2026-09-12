package dev.munormae.project

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.util.transform
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.layout.ValidationInfoBuilder
import dev.munormae.OCamlBundle
import dev.munormae.toolchain.DEFAULT_DUNE_LANGUAGE_VERSION
import dev.munormae.toolchain.EnvironmentDiscoverySettings
import dev.munormae.toolchain.OCamlEnvironmentDescriptor
import dev.munormae.toolchain.OCamlEnvironmentKind
import dev.munormae.toolchain.OCamlToolAvailability
import dev.munormae.toolchain.OCamlToolStatus
import dev.munormae.toolchain.discoverOCamlEnvironments
import dev.munormae.toolchain.environmentCandidate
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel

internal val WIZARD_PROJECT_TEMPLATES = listOf(
    OCamlProjectTemplate.APPLICATION,
    OCamlProjectTemplate.LIBRARY,
    OCamlProjectTemplate.APPLICATION_WITH_LIBRARY,
)

internal class OCamlEnvironmentProjectStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val templateProperty = propertyGraph.property(OCamlProjectTemplate.APPLICATION)
    private val addTestsProperty = propertyGraph.property(false)
    private val environmentProperty = propertyGraph.property<OCamlEnvironmentDescriptor?>(null)
    private val createLocalEnvironmentProperty = propertyGraph.property(false)
    private val installMissingToolsProperty = propertyGraph.property(false)
    private val duneLanguageVersionProperty = propertyGraph.property(DEFAULT_DUNE_LANGUAGE_VERSION)
    private val environmentsModel = CollectionComboBoxModel<OCamlEnvironmentDescriptor>()
    private val discoveryGeneration = AtomicLong()

    private var template by templateProperty
    private var addTests by addTestsProperty
    private var environment by environmentProperty
    private var createLocalEnvironment by createLocalEnvironmentProperty
    private var installMissingTools by installMissingToolsProperty
    private var duneLanguageVersion by duneLanguageVersionProperty

    private lateinit var environmentCombo: JComboBox<OCamlEnvironmentDescriptor>
    private lateinit var compilerStatus: JLabel
    private lateinit var duneStatus: JLabel
    private lateinit var lspStatus: JLabel
    private lateinit var formatterStatus: JLabel
    private lateinit var repairStatus: JLabel
    private lateinit var installButton: JButton
    private lateinit var createLocalButton: JButton
    private var opamAvailable: Boolean = false
    private var selectedEnvironmentPrefix: String = ""

    init {
        refreshEnvironments()
    }

    override fun setupUI(builder: Panel) {
        val baseData = checkNotNull(data.getUserData(NewProjectWizardBaseData.KEY))
        val templateRenderer: SegmentedButton.ItemPresentation.(OCamlProjectTemplate) -> Unit = { value ->
            text = value.displayName()
        }

        builder.row(OCamlBundle.message("wizard.project.type")) {
            segmentedButton(WIZARD_PROJECT_TEMPLATES, templateRenderer)
                .bind(templateProperty)
        }
        builder.row {
            label("").bindText(baseData.nameProperty.transform(::dunePackageNamePresentation))
        }
        builder.row(OCamlBundle.message("wizard.environment")) {
            environmentCombo = comboBox(environmentsModel)
                .bindItem(environmentProperty)
                .align(AlignX.FILL)
                .validationOnApply { validateEnvironment() }
                .component
            button(OCamlBundle.message("settings.refresh")) { refreshEnvironments() }
            button(OCamlBundle.message("wizard.environment.select.existing")) {
                selectExistingEnvironment()
            }
        }
        builder.indent {
            row { compilerStatus = label(OCamlBundle.message("wizard.environment.detecting")).component }
            row { duneStatus = label("").component }
            row { lspStatus = label("").component }
            row { formatterStatus = label("").component }
            row {
                createLocalButton = button(OCamlBundle.message("wizard.environment.create.local")) {
                    createLocalEnvironment = true
                    installMissingTools = true
                    updateRepairPresentation()
                }.component
                installButton = button(OCamlBundle.message("wizard.environment.install")) {
                    installMissingTools = true
                    updateRepairPresentation()
                }.component
            }
            row { repairStatus = label("").component }
        }
        builder.row {
            checkBox(OCamlBundle.message("wizard.add.tests"))
                .bindSelected(addTestsProperty)
        }
        builder.collapsibleGroup(OCamlBundle.message("wizard.advanced")) {
            row(OCamlBundle.message("wizard.dune.language")) {
                textField()
                    .bindText(duneLanguageVersionProperty)
                    .validationOnApply {
                        if (DUNE_LANGUAGE_VERSION_PATTERN.matches(duneLanguageVersion.trim())) null
                        else error(OCamlBundle.message("wizard.dune.language.invalid"))
                    }
            }
        }.apply { expanded = false }

        environmentCombo.addActionListener {
            val selected = environmentCombo.selectedItem as? OCamlEnvironmentDescriptor
            createLocalEnvironment = false
            installMissingTools = false
            selectedEnvironmentPrefix = selected
                ?.takeIf { it.kind == OCamlEnvironmentKind.CUSTOM }
                ?.prefix
                .orEmpty()
            updateEnvironmentPresentation(selected)
        }
        createLocalButton.isEnabled = opamAvailable
        updateEnvironmentPresentation(environment)
    }

    override fun setupProject(project: com.intellij.openapi.project.Project) {
        val baseData = checkNotNull(data.getUserData(NewProjectWizardBaseData.KEY))
        OCamlProjectBootstrapper(project).bootstrap(
            OCamlProjectBootstrapRequest(
                rootPath = baseData.contentEntryPath,
                projectName = sanitizeProjectName(baseData.name),
                template = template,
                addTests = addTests,
                duneLanguageVersion = normalizeDuneLanguageVersion(duneLanguageVersion),
                environment = environment,
                createLocalEnvironment = createLocalEnvironment,
                installMissingTools = installMissingTools,
            ),
        )
    }

    private fun refreshEnvironments() {
        val generation = discoveryGeneration.incrementAndGet()
        if (::compilerStatus.isInitialized) showDetecting()
        val basePath = data.getUserData(NewProjectWizardBaseData.KEY)?.contentEntryPath
        val requestedEnvironmentId = if (selectedEnvironmentPrefix.isBlank()) {
            environment?.id.orEmpty()
        } else {
            environmentCandidate(OCamlEnvironmentKind.CUSTOM, "", selectedEnvironmentPrefix).id
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val discovered = runCatching {
                discoverOCamlEnvironments(
                    EnvironmentDiscoverySettings(
                        environmentId = requestedEnvironmentId,
                        environmentPrefixOverride = selectedEnvironmentPrefix,
                    ),
                    basePath,
                )
            }
            ApplicationManager.getApplication().invokeLater {
                if (discoveryGeneration.get() != generation) return@invokeLater
                val result = discovered.getOrElse { exception ->
                    showDiscoveryFailure(exception)
                    return@invokeLater
                }
                environmentsModel.replaceAll(result.environments)
                opamAvailable = result.opamAvailable
                environment = result.environments.firstOrNull { it.id == result.selectedEnvironmentId }
                environmentsModel.selectedItem = environment
                if (::environmentCombo.isInitialized) {
                    environmentCombo.isEnabled = result.environments.isNotEmpty()
                    createLocalButton.isEnabled = result.opamAvailable
                    updateEnvironmentPresentation(environment)
                }
            }
        }
    }

    private fun showDiscoveryFailure(exception: Throwable) {
        environmentsModel.removeAll()
        environment = null
        opamAvailable = false
        if (!::environmentCombo.isInitialized) return
        environmentCombo.isEnabled = false
        createLocalButton.isEnabled = false
        installButton.isEnabled = false
        val message = OCamlBundle.message(
            "wizard.environment.discovery.failed",
            exception.message ?: exception.javaClass.simpleName,
        )
        compilerStatus.text = message
        duneStatus.text = ""
        lspStatus.text = ""
        formatterStatus.text = ""
    }

    private fun selectExistingEnvironment() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(OCamlBundle.message("wizard.environment.select.existing.title"))
        val selected = FileChooser.chooseFile(descriptor, context.project, null) ?: return
        selectedEnvironmentPrefix = selected.path
        createLocalEnvironment = false
        installMissingTools = false
        refreshEnvironments()
    }

    private fun ValidationInfoBuilder.validateEnvironment() =
        if (canCreateOCamlProject(environment, createLocalEnvironment, installMissingTools)) null
        else error(OCamlBundle.message("wizard.environment.invalid"))

    private fun updateEnvironmentPresentation(selected: OCamlEnvironmentDescriptor?) {
        if (!::compilerStatus.isInitialized) return
        compilerStatus.text = OCamlBundle.message("wizard.health.compiler", selected?.compiler.wizardStatus())
        duneStatus.text = OCamlBundle.message("wizard.health.dune", selected?.dune.wizardStatus())
        lspStatus.text = OCamlBundle.message("wizard.health.lsp", selected?.languageServer.wizardStatus())
        formatterStatus.text = OCamlBundle.message("wizard.health.formatter", selected?.formatter.wizardStatus())
        installButton.isEnabled = selected?.compiler?.isAvailable == true &&
            selected.canInstallTools &&
            !selected.hasAllTools
        updateRepairPresentation()
    }

    private fun updateRepairPresentation() {
        if (!::repairStatus.isInitialized) return
        repairStatus.text = when {
            createLocalEnvironment -> OCamlBundle.message("wizard.environment.local.planned")
            installMissingTools -> OCamlBundle.message("wizard.environment.repair.planned")
            else -> ""
        }
    }

    private fun showDetecting() {
        val detecting = OCamlBundle.message("status.detecting")
        compilerStatus.text = detecting
        duneStatus.text = detecting
        lspStatus.text = detecting
        formatterStatus.text = detecting
    }

    private fun OCamlToolStatus?.wizardStatus(): String = when (this?.availability) {
        OCamlToolAvailability.AVAILABLE -> version.ifBlank { OCamlBundle.message("status.available.short") }
        OCamlToolAvailability.MISSING -> OCamlBundle.message("status.missing.short")
        OCamlToolAvailability.ERROR -> detail.ifBlank { OCamlBundle.message("status.unavailable.short") }
        OCamlToolAvailability.BLOCKED -> OCamlBundle.message("status.blocked")
        OCamlToolAvailability.NOT_CHECKED, null -> OCamlBundle.message("status.not.checked")
    }
}

internal fun canCreateOCamlProject(
    environment: OCamlEnvironmentDescriptor?,
    createLocalEnvironment: Boolean,
    installMissingTools: Boolean,
): Boolean = when {
    createLocalEnvironment -> installMissingTools
    environment?.hasAllTools == true -> true
    environment?.compiler?.isAvailable == true && environment.canInstallTools && installMissingTools -> true
    else -> false
}

internal fun dunePackageNamePresentation(projectName: String): String =
    OCamlBundle.message("wizard.package.name", sanitizeProjectName(projectName))

private fun OCamlProjectTemplate.displayName(): String = when (this) {
    OCamlProjectTemplate.MINIMAL -> OCamlBundle.message("wizard.project.minimal")
    OCamlProjectTemplate.APPLICATION -> OCamlBundle.message("wizard.project.application")
    OCamlProjectTemplate.LIBRARY -> OCamlBundle.message("wizard.project.library")
    OCamlProjectTemplate.APPLICATION_WITH_LIBRARY -> OCamlBundle.message("wizard.project.application.library")
}

private val DUNE_LANGUAGE_VERSION_PATTERN = Regex("""\d+\.\d+""")
