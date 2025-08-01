// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.k2.refactoring.introduceTypeAlias

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.CopyablePsiUserDataProperty
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType


internal var KtTypeReference.originalReference: KtTypeReference? by CopyablePsiUserDataProperty(Key.create("RESOLVE_INFO"))

class IntroduceTypeAliasData(
    val originalTypeElement: KtElement,
    val targetSibling: KtElement,
    val extractTypeConstructor: Boolean = false
) : Disposable {

    init {
        markReferences()
    }

    private fun markReferences() {
        val visitor = object : KtTreeVisitorVoid() {
            override fun visitTypeReference(typeReference: KtTypeReference) {
                val typeElement = typeReference.typeElement ?: return

                typeReference.originalReference = typeReference

                typeElement.typeArgumentsAsTypes.forEach { it?.accept(this) }
            }
        }
        (originalTypeElement.parent as? KtTypeReference ?: originalTypeElement).accept(visitor)
    }

    override fun dispose() {
        if (!originalTypeElement.isValid) return
        originalTypeElement.forEachDescendantOfType<KtTypeReference> { it.originalReference = null }
    }
}

data class TypeParameter(val name: String, val typeReferenceInfos: Collection<KtTypeReference>)

data class IntroduceTypeAliasDescriptor(
    val originalData: IntroduceTypeAliasData,
    val name: String,
    val visibility: KtModifierKeywordToken?,
    val typeParameters: List<TypeParameter>
)

data class IntroduceTypeAliasDescriptorWithConflicts(
    val descriptor: IntroduceTypeAliasDescriptor,
    val conflicts: MultiMap<PsiElement, String>
)