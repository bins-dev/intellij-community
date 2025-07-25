// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.annoPackages;

import com.intellij.codeInsight.ContextNullabilityInfo;
import com.intellij.codeInsight.Nullability;
import com.intellij.codeInsight.NullabilityAnnotationInfo;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public final class JSpecifyAnnotationSupport implements AnnotationPackageSupport {
  private static final String PACKAGE_NAME = "org.jspecify.annotations";
  private static final String NULLABLE = PACKAGE_NAME + "." + "Nullable";
  private static final String NOT_NULL = PACKAGE_NAME + "." + "NonNull";
  private static final String NULLNESS_UNKNOWN = PACKAGE_NAME + "." + "NullnessUnspecified";
  private static final String DEFAULT_NOT_NULL = PACKAGE_NAME + "." + "NullMarked";
  private static final String DEFAULT_NULLNESS_UNKNOWN = PACKAGE_NAME + "." + "NullUnmarked";

  @Override
  public @NotNull ContextNullabilityInfo getNullabilityByContainerAnnotation(@NotNull PsiAnnotation anno,
                                                                             PsiAnnotation.TargetType @NotNull [] types,
                                                                             boolean superPackage) {
    if (superPackage) return ContextNullabilityInfo.EMPTY;
    String name = anno.getQualifiedName();
    if (name == null) return ContextNullabilityInfo.EMPTY;
    if (ArrayUtil.contains(PsiAnnotation.TargetType.LOCAL_VARIABLE, types)) return ContextNullabilityInfo.EMPTY;
    Nullability nullability;
    switch (name) {
      case DEFAULT_NOT_NULL -> nullability = Nullability.NOT_NULL;
      case DEFAULT_NULLNESS_UNKNOWN -> nullability = Nullability.UNKNOWN;
      default -> {
        return ContextNullabilityInfo.EMPTY;
      }
    }
    return ContextNullabilityInfo.constant(new NullabilityAnnotationInfo(anno, nullability, true))
      .disableInCast()
      .filtering(context -> !resolvesToTypeParameter(context));
  }

  static boolean resolvesToTypeParameter(@NotNull PsiElement context) {
    PsiType targetType = context instanceof PsiMethod method ? method.getReturnType() :
                         context instanceof PsiVariable variable ? variable.getType() :
                         context instanceof PsiJavaCodeReferenceElement && context.getParent() instanceof PsiTypeElement typeElement ? typeElement.getType() :
                         null;
    return PsiUtil.resolveClassInClassTypeOnly(targetType) instanceof PsiTypeParameter;
  }

  @Override
  public @NotNull List<String> getNullabilityAnnotations(@NotNull Nullability nullability) {
    return switch (nullability) {
      case NOT_NULL -> Collections.singletonList(NOT_NULL);
      case NULLABLE -> Collections.singletonList(NULLABLE);
      case UNKNOWN -> List.of(NULLNESS_UNKNOWN);
    };
  }

  @Override
  public boolean isTypeUseAnnotationLocationRestricted() {
    return true;
  }

  @Override
  public boolean canAnnotateLocals() {
    return false;
  }
}
