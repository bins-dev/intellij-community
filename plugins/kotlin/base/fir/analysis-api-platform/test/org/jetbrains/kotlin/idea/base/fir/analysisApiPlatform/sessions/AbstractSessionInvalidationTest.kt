// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.fir.analysisApiPlatform.sessions

import com.intellij.openapi.module.ModuleManager
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModuleForProduction
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModules
import org.jetbrains.kotlin.idea.base.test.KotlinRoot
import org.jetbrains.kotlin.idea.test.projectStructureTest.AbstractProjectStructureTest
import java.io.File

sealed class AbstractSessionInvalidationTest : AbstractProjectStructureTest<SessionInvalidationTestProjectStructure>(
    SessionInvalidationTestProjectStructureParser,
) {

    override fun getTestDataDirectory(): File =
        KotlinRoot.DIR.resolve("base").resolve("fir").resolve("analysis-api-platform").resolve("testData").resolve("sessionInvalidation")

    protected abstract fun publishModificationEvents()

    protected abstract fun checkSessions(
        sessionsBeforeModification: List<LLFirSession>,
        sessionsAfterModification: List<LLFirSession>,
    )

    protected fun checkSessionsMarkedInvalid(
        sessionsBeforeModification: List<LLFirSession>,
        sessionsAfterModification: List<LLFirSession>,
    ) {
        val invalidSessions = buildSet {
            addAll(sessionsBeforeModification)
            removeAll(sessionsAfterModification)
        }

        invalidSessions.forEach { session ->
            assertFalse("The invalidated session `${session}` should have been marked invalid.", session.isValid)
        }
    }

    override fun doTestWithProjectStructure(testDirectory: String) {
        val rootIdeaModule = modulesByName.getValue(testProjectStructure.rootModule)
        val rootModule = rootIdeaModule.toKaSourceModuleForProduction()!!

        val sessionsBeforeModification = getAllModuleSessions(rootModule)

        publishModificationEvents()

        val sessionsAfterModification = getAllModuleSessions(rootModule)

        checkSessions(sessionsBeforeModification, sessionsAfterModification)
    }

    @OptIn(LLFirInternals::class)
    private fun getAllModuleSessions(mainModule: KaModule): List<LLFirSession> {
        val projectModules = ModuleManager.getInstance(mainModule.project).modules.flatMap { it.toKaSourceModules() }

        val resolutionFacade = LLResolutionFacadeService.getInstance(mainModule.project).getResolutionFacade(mainModule)
        return projectModules.map(resolutionFacade::getSessionFor)
    }
}
