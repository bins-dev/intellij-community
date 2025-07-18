// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForProject
import org.jetbrains.kotlin.caches.resolve.PlatformAnalysisSettings
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfoOrNull
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo.IdeaModuleInfo
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments

private class ResolutionFacadeWithDebugInfo(
    private val delegate: ResolutionFacade,
    private val creationPlace: CreationPlace
) : ResolutionFacade, ResolutionFacadeModuleDescriptorProvider {
    override fun findModuleDescriptor(ideaModuleInfo: IdeaModuleInfo): ModuleDescriptor {
        return delegate.findModuleDescriptor(ideaModuleInfo)
    }

    override val project: Project
        get() = delegate.project

    override fun analyze(element: KtElement, bodyResolveMode: BodyResolveMode): BindingContext {
        return wrapExceptions({ ResolvingWhat(element, bodyResolveMode = bodyResolveMode) }) {
            delegate.analyze(element, bodyResolveMode)
        }
    }

    override fun analyze(elements: Collection<KtElement>, bodyResolveMode: BodyResolveMode): BindingContext {
        return wrapExceptions({ ResolvingWhat(elements = elements, bodyResolveMode = bodyResolveMode) }) {
            delegate.analyze(elements, bodyResolveMode)
        }
    }

    override fun analyzeWithAllCompilerChecks(
        element: KtElement,
        callback: DiagnosticSink.DiagnosticsCallback?
    ): AnalysisResult {
        return wrapExceptions({ ResolvingWhat(element) }) {
            delegate.analyzeWithAllCompilerChecks(element, callback)
        }
    }

    override fun analyzeWithAllCompilerChecks(
        elements: Collection<KtElement>,
        callback: DiagnosticSink.DiagnosticsCallback?
    ): AnalysisResult {
        return wrapExceptions({ ResolvingWhat(elements = elements) }) {
            delegate.analyzeWithAllCompilerChecks(elements, callback)
        }
    }

    override fun fetchWithAllCompilerChecks(element: KtElement): AnalysisResult? {
        return wrapExceptions({ ResolvingWhat(element = element) }) {
            delegate.fetchWithAllCompilerChecks(element)
        }
    }

    override fun resolveToDescriptor(declaration: KtDeclaration, bodyResolveMode: BodyResolveMode): DeclarationDescriptor {
        return wrapExceptions({ ResolvingWhat(declaration, bodyResolveMode = bodyResolveMode) }) {
            delegate.resolveToDescriptor(declaration, bodyResolveMode)
        }
    }

    override val moduleDescriptor: ModuleDescriptor
        get() = delegate.moduleDescriptor

    @FrontendInternals
    override fun <T : Any> getFrontendService(serviceClass: Class<T>): T {
        return wrapExceptions({ ResolvingWhat(serviceClass = serviceClass) }) {
            delegate.getFrontendService(serviceClass)
        }
    }

    override fun <T : Any> getIdeService(serviceClass: Class<T>): T {
        return wrapExceptions({ ResolvingWhat(serviceClass = serviceClass) }) {
            delegate.getIdeService(serviceClass)
        }
    }

    @FrontendInternals
    override fun <T : Any> getFrontendService(element: PsiElement, serviceClass: Class<T>): T {
        return wrapExceptions({ ResolvingWhat(element, serviceClass = serviceClass) }) {
            delegate.getFrontendService(element, serviceClass)
        }
    }

    @FrontendInternals
    override fun <T : Any> tryGetFrontendService(element: PsiElement, serviceClass: Class<T>): T? {
        return wrapExceptions({ ResolvingWhat(element, serviceClass = serviceClass) }) {
            delegate.tryGetFrontendService(element, serviceClass)
        }
    }

    override fun getResolverForProject(): ResolverForProject<out ModuleInfo> {
        return delegate.getResolverForProject()
    }

    private inline fun <R> wrapExceptions(resolvingWhat: () -> ResolvingWhat, body: () -> R): R {
        try {
            return body()
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is IndexNotReadyException) {
                throw e
            }
            throw KotlinIdeaResolutionException(e, resolvingWhat(), creationPlace)
        }
    }
}

private class KotlinIdeaResolutionException(
    cause: Throwable,
    resolvingWhat: ResolvingWhat,
    creationPlace: CreationPlace
) : KotlinExceptionWithAttachments(
    "Kotlin resolution encountered a problem while ${resolvingWhat.shortDescription()}${cause.message?.let { ":\n$it" } ?: ""}",
    cause
) {
    init {
        withAttachment("info.txt", buildString {
            append(resolvingWhat.description())
            appendLine("---------------------------------------------")
            append(creationPlace.description())
        })
        for (element in resolvingWhat.elements.withIndex()) {
            withPsiAttachment("element${element.index}.kt", element.value)
            withPsiAttachment(
                "file${element.index}.kt",
                element.value.containingFile
            )
        }
    }
}


private class CreationPlace(
    private val elements: Collection<KtElement>,
    private val moduleInfo: ModuleInfo?,
    private val settings: PlatformAnalysisSettings?
) {
    fun description() = buildString {
        appendLine("Resolver created for:")
        for (element in elements) {
            appendElement(element)
        }
        if (moduleInfo != null) {
            appendLine("Provided module info: $moduleInfo")
        }
        if (settings != null) {
            appendLine("Provided settings: $settings")
        }
    }
}

private class ResolvingWhat(
    val element: PsiElement? = null,
    val elements: Collection<PsiElement> = emptyList(),
    private val bodyResolveMode: BodyResolveMode? = null,
    private val serviceClass: Class<*>? = null,
    private val moduleDescriptor: ModuleDescriptor? = null
) {
    fun shortDescription() = serviceClass?.let { "getting service ${serviceClass.simpleName}" }
        ?: "analyzing ${(element ?: elements.firstOrNull())?.javaClass?.simpleName ?: ""}"

    fun description(): String {
        return buildString {
            appendLine("Failed performing task:")
            if (serviceClass != null) {
                appendLine("Getting service: ${serviceClass.name}")
            } else {
                append("Analyzing code")
                if (bodyResolveMode != null) {
                    append(" in BodyResolveMode.$bodyResolveMode")
                }
                appendLine()
            }
            appendLine("Elements:")
            element?.let { appendElement(it) }
            for (element in elements) {
                appendElement(element)
            }
            if (moduleDescriptor != null) {
                appendLine("Provided module descriptor for module ${moduleDescriptor.getCapability(ModuleInfo.Capability)}")
            }
        }
    }
}

private fun StringBuilder.appendElement(element: PsiElement) {
    fun info(key: String, value: String?) {
        appendLine("  $key = $value")
    }

    appendLine("Element of type: ${element.javaClass.simpleName}:")
    if (element is PsiNamedElement) {
        info("name", element.name)
    }
    info("isValid", element.isValid.toString())
    info("isPhysical", element.isPhysical.toString())
    if (element is PsiFile) {
        info("containingFile.name", element.containingFile.name)
    }
    val moduleInfoResult = ifIndexReady { element.moduleInfoOrNull }
    info("moduleInfo", moduleInfoResult?.let { it.result?.toString() ?: "null" } ?: "<index not ready>")

    val moduleInfo = moduleInfoResult?.result
    if (moduleInfo != null) {
        info("moduleInfo.platform", moduleInfo.platform.toString())
    }

    val virtualFile = element.containingFile?.virtualFile
    info("virtualFile", virtualFile?.name)

    if (virtualFile != null) {
        val moduleName = ifIndexReady { ModuleUtil.findModuleForFile(virtualFile, element.project)?.name ?: "null" }?.result
        info(
            "ideaModule",
            moduleName ?: "<index not ready>"
        )
    }
}

private class IndexResult<T>(val result: T)

private fun <T> ifIndexReady(body: () -> T): IndexResult<T>? = try {
    IndexResult(body())
} catch (e: IndexNotReadyException) {
    null
}

internal fun ResolutionFacade.createdFor(
    files: Collection<KtFile>,
    moduleInfo: ModuleInfo?,
    settings: PlatformAnalysisSettings
): ResolutionFacade {
    return ResolutionFacadeWithDebugInfo(this, CreationPlace(files, moduleInfo, settings))
}
