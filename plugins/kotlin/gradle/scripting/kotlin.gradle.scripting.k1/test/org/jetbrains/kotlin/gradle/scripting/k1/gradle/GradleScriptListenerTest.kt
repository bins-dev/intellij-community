// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.gradle.scripting.k1.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.util.ui.EDT.dispatchAllInvocationEvents
import com.intellij.util.ui.UIUtil
import junit.framework.TestCase
import org.jetbrains.kotlin.gradle.scripting.shared.GradleStandaloneScriptActionsManager
import org.jetbrains.kotlin.gradle.scripting.shared.roots.GradleBuildRootsLocator
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.kotlin.idea.core.script.k1.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.script.AbstractScriptConfigurationLoadingTest
import org.jetbrains.kotlin.idea.test.IDEA_TEST_DATA_DIR
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.runner.RunWith
import java.io.File

@RunWith(JUnit3RunnerWithInners::class)
open class GradleScriptListenerTest : AbstractScriptConfigurationLoadingTest() {

    companion object {
        internal const val outsidePlaceholder = "// OUTSIDE_SECTIONS"
        internal const val insidePlaceholder = "// INSIDE_SECTIONS"
    }

    private lateinit var testFiles: TestFiles

    data class TestFiles(
        val buildKts: KtFile,
        val settings: KtFile,
        val prop: PsiFile,
        val gradleWrapperProperties: VirtualFile
    )

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K1

    override fun setUpTestProject() {
        val rootDir = IDEA_TEST_DATA_DIR.resolve("script/definition/loading/gradle/")

        val settings: KtFile = copyFromTestdataToProject(File(rootDir, GradleConstants.KOTLIN_DSL_SETTINGS_FILE_NAME))
        val prop: PsiFile = copyFromTestdataToProject(File(rootDir, "gradle.properties"))

        val gradleCoreJar = createFileInProject("gradle/lib/gradle-core-1.0.0.jar")
        val gradleWrapperProperties = createFileInProject("gradle/wrapper/gradle-wrapper.properties")

        val buildGradleKts = rootDir.walkTopDown().find { it.name == GradleConstants.KOTLIN_DSL_SCRIPT_NAME }
            ?: error("Couldn't find main script")
        configureScriptFile(rootDir, buildGradleKts)
        val build = (myFile as? KtFile) ?: error("")

        val newProjectSettings = GradleProjectSettings()
        newProjectSettings.gradleHome = gradleCoreJar.parentFile.parent
        newProjectSettings.distributionType = DistributionType.LOCAL
        newProjectSettings.externalProjectPath = settings.virtualFile.parent.path
        ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID).linkProject(newProjectSettings)

        dispatchAllInvocationEvents()

        testFiles = TestFiles(
            build,
            settings,
            prop,
            LocalFileSystem.getInstance().findFileByIoFile(gradleWrapperProperties)!!
        )
    }

    fun testSectionChange() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changeBuildKtsInsideSections()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testSpacesInSectionsChange() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changeBuildKtsInsideSections("// INSIDE PLUGINS\n")

        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testCommentsInSectionsChange() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changeBuildKtsInsideSections("// My test comment\n")

        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testOutsideSectionChange() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changeBuildKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testSectionsInSettingsChange() {
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeSettingsKtsInsideSections()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
    }

    fun testOutsideSectionsInSettingsChange() {
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeSettingsKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.settings)
    }

    fun testChangeOutsideSectionsInvalidatesOtherFiles() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeBuildKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.buildKts)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
    }

    fun testChangeInsideSectionsInvalidatesOtherFiles() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeBuildKtsInsideSections()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
    }

    fun testChangeInsideNonKtsFileInvalidatesOtherFiles() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changePropertiesFile()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testTwoFilesChanged() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        changePropertiesFile()
        changeSettingsKtsOutsideSections()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
    }

    fun testFileAttributes() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        ScriptConfigurationManager.clearCaches(project)

        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testFileAttributesUpToDate() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        ScriptConfigurationManager.clearCaches(project)

        changeBuildKtsInsideSections()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testFileAttributesUpToDateAfterChangeOutsideSections() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        ScriptConfigurationManager.clearCaches(project)

        changeBuildKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testFileAttributesUpdateAfterChangeOutsideSectionsOfOtherFile() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        ScriptConfigurationManager.clearCaches(project)

        changeSettingsKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.settings)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testConfigurationUpdateAfterProjectClosing() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeSettingsKtsOutsideSections()

        assertConfigurationUpToDate(testFiles.settings)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testConfigurationUpdateAfterProjectClosing2() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        changeSettingsKtsOutsideSections()

        val ts = System.currentTimeMillis()
        markFileChanged(testFiles.buildKts.virtualFile, ts)
        markFileChanged(testFiles.settings.virtualFile, ts)

        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    fun testConfigurationUpdateAfterProjectClosing3() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)
        assertAndLoadInitialConfiguration(testFiles.settings)

        val ts = System.currentTimeMillis()
        markFileChanged(testFiles.buildKts.virtualFile, ts)
        markFileChanged(testFiles.settings.virtualFile, ts)

        changePropertiesFile()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.settings)
        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
    }

    private fun markFileChanged(virtualFile: VirtualFile, ts: Long) {
        GradleBuildRootsLocator.getInstance(project).fileChanged(virtualFile.path, ts)
    }

    fun testLoadedConfigurationWhenExternalFileChanged() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        changePropertiesFile()

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
        assertConfigurationUpToDate(testFiles.buildKts)
    }

    fun testChangeGradleWrapperPropertiesFile() {
        assertAndLoadInitialConfiguration(testFiles.buildKts)

        markFileChanged(testFiles.gradleWrapperProperties, System.currentTimeMillis())

        assertConfigurationUpdateWasDoneAfterClick(testFiles.buildKts)
        assertConfigurationUpToDate(testFiles.buildKts)
    }

    override fun assertAndLoadInitialConfiguration(file: KtFile) {
        assertNull(scriptConfigurationManager.getConfiguration(file))
        assertReloadingSuggestedAndDoReload(file)
        assertAndDoAllBackgroundTasks()
        assertSingleLoading()
        assertAppliedConfiguration(file.text, file)

        checkHighlighting(file)
    }

    private fun assertReloadingSuggestedAndDoReload(file: KtFile = myFile as KtFile) {
        ApplicationManager.getApplication().invokeLater {}
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(
            "reloading configuration should be suggested",
            GradleStandaloneScriptActionsManager.getInstance(project).performSuggestedLoading(file.virtualFile)
        )
    }

    private fun assertConfigurationUpToDate(file: KtFile) {
        scriptConfigurationManager.default.ensureUpToDatedConfigurationSuggested(file)
        assertNoBackgroundTasks()
        assertNoLoading()
    }

    private fun assertConfigurationUpdateWasDoneAfterClick(file: KtFile) {
        scriptConfigurationManager.default.ensureUpToDatedConfigurationSuggested(file)
        assertReloadingSuggestedAndDoReload(file)
        assertAndDoAllBackgroundTasks()
        assertSingleLoading()
    }

    private fun changeBuildKtsInsideSections(text: String = "application") {
        changeBuildKts(insidePlaceholder, text)
    }

    private fun changeBuildKtsOutsideSections() {
        changeBuildKts(outsidePlaceholder, "compile(\"\")")
    }

    private fun changeSettingsKtsInsideSections() {
        changeSettingsKts(insidePlaceholder, "mavenCentral()")
    }

    private fun changeSettingsKtsOutsideSections() {
        changeSettingsKts(outsidePlaceholder, "include: 'aaa'")
    }

    private fun changeBuildKts(placeHolder: String, text: String) {
        changeContents(testFiles.buildKts.text.replace(placeHolder, text), testFiles.buildKts)
        testFiles = testFiles.copy(buildKts = myFile as KtFile)
    }

    private fun changeSettingsKts(placeHolder: String, text: String) {
        changeContents(testFiles.settings.text.replace(placeHolder, text), testFiles.settings)
        testFiles = testFiles.copy(settings = myFile as KtFile)
    }

    private fun changePropertiesFile() {
        changeContents(testFiles.prop.text.replace(outsidePlaceholder.replace("//", "#"), "myProp = true"), testFiles.prop)
        testFiles = testFiles.copy(prop = myFile)
    }
}
