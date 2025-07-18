// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.refactoring;

import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.JavaFeature;
import com.intellij.psi.JavaCodeFragment;
import com.intellij.psi.JavaCodeFragmentFactory;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import com.intellij.refactoring.extractMethodObject.ExtractLightMethodObjectHandler;
import com.intellij.refactoring.extractMethodObject.LightMethodObjectExtractedData;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.UsefulTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * @author Vitaliy.Bibaev
 */
public class ExtractMethodObject4DebuggerReflectionTest extends LightRefactoringTestCase {

  @Override
  protected Sdk getProjectJDK() {
    return IdeaTestUtil.getMockJdk21();
  }

  public void testAccessField() throws PrepareFailedException {
    doTest("System.out.println(instance.field)");
  }

  public void testUpdateField() throws PrepareFailedException {
    doTest("instance.field = 50");
  }

  public void testAccessConstructor() throws PrepareFailedException {
    doTest("new WithReflectionAccess(50)");
  }

  public void testAccessMethod() throws PrepareFailedException {
    doTest("method()");
  }

  public void testAccessMethodReference() throws PrepareFailedException {
    doTest("apply(WithReflectionAccess::method)");
  }

  public void testTwiceAccessToTheSameField() throws PrepareFailedException {
    doTest("instance.field + instance.field");
  }

  public void testMethodWithParameter() throws PrepareFailedException {
    doTest("instance.method(instance)");
  }

  public void testMethodWithPrimitiveParameter() throws PrepareFailedException {
    doTest("instance.method(42)");
  }

  public void testCallDefaultConstructor() throws PrepareFailedException {
    doTest("new Inner()");
  }

  public void testLanguageLevelImplicitClasses() {
    IdeaTestUtil.withLevel(getModule(), JavaFeature.PACKAGE_IMPORTS_SHADOW_MODULE_IMPORTS.getStandardLevel(), () -> {
      String testName = getTestName(false);
      String pathToSource = "/" + testName + ".java";
      try {
        doTest("reader", pathToSource);
      }
      catch (PrepareFailedException e) {
        throw new RuntimeException(e);
      }
    });
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return super.getTestDataPath() + "/refactoring/extractMethodObject4Debugger";
  }

  private void doTest(@NotNull String evaluatedText) throws PrepareFailedException {
    String path = "/WithReflectionAccess.java";
    doTest(evaluatedText, path);
  }

  private void doTest(@NotNull String evaluatedText, @NotNull String pathToSource) throws PrepareFailedException {
    String testName = getTestName(true);
    configureByFile(pathToSource);
    final int offset = getEditor().getCaretModel().getOffset();
    final PsiElement context = getFile().findElementAt(offset);
    final JavaCodeFragmentFactory fragmentFactory = JavaCodeFragmentFactory.getInstance(getProject());
    final JavaCodeFragment fragment = fragmentFactory.createExpressionCodeFragment(evaluatedText, context, null, false);
    final LightMethodObjectExtractedData extractedData =
      ExtractLightMethodObjectHandler.extractLightMethodObject(getProject(), context, fragment, "test", JavaSdkVersion.JDK_1_9);
    assertNotNull(extractedData);
    assertFalse(extractedData.useMagicAccessor());
    String actualText = "call text: " + extractedData.getGeneratedCallText() + "\n" +
                        "class: " + "\n" +
                        extractedData.getGeneratedInnerClass().getText();
    UsefulTestCase
      .assertSameLinesWithFile(getTestDataPath() + "/outs/" + testName + ".out", actualText, true);
  }
}
