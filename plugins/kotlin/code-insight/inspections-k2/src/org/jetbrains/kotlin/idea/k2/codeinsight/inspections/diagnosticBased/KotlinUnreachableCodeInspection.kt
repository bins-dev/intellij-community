// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.startOffset
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.cfg.UnreachableCode
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtVisitor
import org.jetbrains.kotlin.psi.KtVisitorVoid
import kotlin.reflect.KClass

class KotlinUnreachableCodeInspection : KotlinKtDiagnosticBasedInspectionBase<KtElement, KaFirDiagnostic.UnreachableCode, KotlinUnreachableCodeInspection.Context>() {
    data class Context(
        val unreachableElementPointers: Collection<SmartPsiElementPointer<*>>
    )

    override val diagnosticType: KClass<KaFirDiagnostic.UnreachableCode>
        get() = KaFirDiagnostic.UnreachableCode::class

    override fun KaSession.prepareContextByDiagnostic(
        element: KtElement,
        diagnostic: KaFirDiagnostic.UnreachableCode
    ): Context? {
        val reachable = diagnostic.reachable.mapNotNullTo(HashSet()) { it as? KtElement }
        val unreachable = diagnostic.unreachable.mapNotNullTo(HashSet()) { it as? KtElement }

        val unreachableTextRanges =
            UnreachableCode
                .getUnreachableTextRanges(element, reachable, unreachable)
                .ifEmpty { return null }

        val unreachableElementPointers = buildList {
            val startOffset = element.startOffset
            for (textRange in unreachableTextRanges) {
                var offset = textRange.startOffset - startOffset
                while (offset < textRange.endOffset - startOffset) {
                    val findElementAt = element.findElementAt(offset) ?: break
                    offset += findElementAt.textLength
                    this += findElementAt.createSmartPointer()
                }
            }
        }

        return Context(unreachableElementPointers)
    }

    override fun getProblemDescription(
        element: KtElement,
        context: Context
    ): @InspectionMessage String = KotlinBundle.message("inspection.unreachable.code")

    override fun createQuickFix(
        element: KtElement,
        context: Context
    ): KotlinModCommandQuickFix<KtElement> = object : KotlinModCommandQuickFix<KtElement>() {

        override fun getFamilyName(): String = KotlinBundle.message("inspection.unreachable.code.remove.unreachable.code")

        override fun applyFix(
            project: Project,
            element: KtElement,
            updater: ModPsiUpdater,
        ) {
            context.unreachableElementPointers
                .mapNotNull {
                    it.element?.takeUnless { e -> e is PsiWhiteSpace }?.let(updater::getWritable)
                }.forEach(PsiElement::delete)
        }
    }

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): KtVisitor<*, *> = object : KtVisitorVoid() {
        override fun visitKtElement(element: KtElement) {
            visitTargetElement(element, holder, isOnTheFly)
        }
    }

    override fun registerProblem(
        ranges: List<TextRange>,
        holder: ProblemsHolder,
        element: KtElement,
        context: Context,
        isOnTheFly: Boolean
    ) {
        context.unreachableElementPointers.forEach { pointer ->
            val e = pointer.element ?: return@forEach
            val problemDescriptor = holder.manager.createProblemDescriptor(
                element,
                context,
                e.textRange.shiftLeft(element.startOffset),
                isOnTheFly,
            )
            holder.registerProblem(problemDescriptor)
        }
    }
}