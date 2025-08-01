// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.completion.test;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("completion/tests-k1")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("../testData/basic/multifile")
public class MultiFileJvmBasicCompletionTestGenerated extends AbstractMultiFileJvmBasicCompletionTest {
    @java.lang.Override
    @org.jetbrains.annotations.NotNull
    public final KotlinPluginMode getPluginMode() {
        return KotlinPluginMode.K1;
    }

    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("CallableReferenceNotImported")
    public void testCallableReferenceNotImported() throws Exception {
        runTest("../testData/basic/multifile/CallableReferenceNotImported/");
    }

    @TestMetadata("CallableReferenceNotImportedExtension")
    public void testCallableReferenceNotImportedExtension() throws Exception {
        runTest("../testData/basic/multifile/CallableReferenceNotImportedExtension/");
    }

    @TestMetadata("CallableReferenceNotImportedExtension2")
    public void testCallableReferenceNotImportedExtension2() throws Exception {
        runTest("../testData/basic/multifile/CallableReferenceNotImportedExtension2/");
    }

    @TestMetadata("CallablesInExcludedPackage")
    public void testCallablesInExcludedPackage() throws Exception {
        runTest("../testData/basic/multifile/CallablesInExcludedPackage/");
    }

    @TestMetadata("ChainCompletionDontDuplicate")
    public void testChainCompletionDontDuplicate() throws Exception {
        runTest("../testData/basic/multifile/ChainCompletionDontDuplicate/");
    }

    @TestMetadata("ClassInExcludedPackage")
    public void testClassInExcludedPackage() throws Exception {
        runTest("../testData/basic/multifile/ClassInExcludedPackage/");
    }

    @TestMetadata("ClassInRootPackage")
    public void testClassInRootPackage() throws Exception {
        runTest("../testData/basic/multifile/ClassInRootPackage/");
    }

    @TestMetadata("CompleteFunctionWithNoSpecifiedType")
    public void testCompleteFunctionWithNoSpecifiedType() throws Exception {
        runTest("../testData/basic/multifile/CompleteFunctionWithNoSpecifiedType/");
    }

    @TestMetadata("CompleteImportedFunction")
    public void testCompleteImportedFunction() throws Exception {
        runTest("../testData/basic/multifile/CompleteImportedFunction/");
    }

    @TestMetadata("CompletionOnImportedFunction")
    public void testCompletionOnImportedFunction() throws Exception {
        runTest("../testData/basic/multifile/CompletionOnImportedFunction/");
    }

    @TestMetadata("ConstructorReferenceNotImported")
    public void testConstructorReferenceNotImported() throws Exception {
        runTest("../testData/basic/multifile/ConstructorReferenceNotImported/");
    }

    @TestMetadata("CovarianceExtensionFunction")
    public void testCovarianceExtensionFunction() throws Exception {
        runTest("../testData/basic/multifile/CovarianceExtensionFunction/");
    }

    @TestMetadata("DoNotCompleteWithConstraints")
    public void testDoNotCompleteWithConstraints() throws Exception {
        runTest("../testData/basic/multifile/DoNotCompleteWithConstraints/");
    }

    @TestMetadata("EnhancementFlexibleTypes")
    public void testEnhancementFlexibleTypes() throws Exception {
        runTest("../testData/basic/multifile/EnhancementFlexibleTypes/");
    }

    @TestMetadata("EntriesOfNotImportedEnumFromKotlin")
    public void testEntriesOfNotImportedEnumFromKotlin() throws Exception {
        runTest("../testData/basic/multifile/EntriesOfNotImportedEnumFromKotlin/");
    }

    @TestMetadata("EnumCompanionMethods")
    public void testEnumCompanionMethods() throws Exception {
        runTest("../testData/basic/multifile/EnumCompanionMethods/");
    }

    @TestMetadata("EnumEntry")
    public void testEnumEntry() throws Exception {
        runTest("../testData/basic/multifile/EnumEntry/");
    }

    @TestMetadata("EnumEntryExpectedPreferredJava")
    public void testEnumEntryExpectedPreferredJava() throws Exception {
        runTest("../testData/basic/multifile/EnumEntryExpectedPreferredJava/");
    }

    @TestMetadata("EnumValuesMethodJavaUsualPriorityWhenFeatureDisabled")
    public void testEnumValuesMethodJavaUsualPriorityWhenFeatureDisabled() throws Exception {
        runTest("../testData/basic/multifile/EnumValuesMethodJavaUsualPriorityWhenFeatureDisabled/");
    }

    @TestMetadata("EnumValuesMethodLowerPriorityJava")
    public void testEnumValuesMethodLowerPriorityJava() throws Exception {
        runTest("../testData/basic/multifile/EnumValuesMethodLowerPriorityJava/");
    }

    @TestMetadata("ExactMatchPreferImported")
    public void testExactMatchPreferImported() throws Exception {
        runTest("../testData/basic/multifile/ExactMatchPreferImported/");
    }

    @TestMetadata("ExcludedClass")
    public void testExcludedClass() throws Exception {
        runTest("../testData/basic/multifile/ExcludedClass/");
    }

    @TestMetadata("ExcludedJavaClass")
    public void testExcludedJavaClass() throws Exception {
        runTest("../testData/basic/multifile/ExcludedJavaClass/");
    }

    @TestMetadata("ExpectedJavaEnumEntryCompletion")
    public void testExpectedJavaEnumEntryCompletion() throws Exception {
        runTest("../testData/basic/multifile/ExpectedJavaEnumEntryCompletion/");
    }

    @TestMetadata("ExtensionFunction")
    public void testExtensionFunction() throws Exception {
        runTest("../testData/basic/multifile/ExtensionFunction/");
    }

    @TestMetadata("ExtensionFunctionOnImportedFunction")
    public void testExtensionFunctionOnImportedFunction() throws Exception {
        runTest("../testData/basic/multifile/ExtensionFunctionOnImportedFunction/");
    }

    @TestMetadata("ExtensionOnIntersectionTypeReceiver")
    public void testExtensionOnIntersectionTypeReceiver() throws Exception {
        runTest("../testData/basic/multifile/ExtensionOnIntersectionTypeReceiver/");
    }

    @TestMetadata("ExtensionOnNullable")
    public void testExtensionOnNullable() throws Exception {
        runTest("../testData/basic/multifile/ExtensionOnNullable/");
    }

    @TestMetadata("ExtensionOnTypeParameterReceiverType")
    public void testExtensionOnTypeParameterReceiverType() throws Exception {
        runTest("../testData/basic/multifile/ExtensionOnTypeParameterReceiverType/");
    }

    @TestMetadata("ExtensionOnTypeParameterReceiverTypeWithBounds")
    public void testExtensionOnTypeParameterReceiverTypeWithBounds() throws Exception {
        runTest("../testData/basic/multifile/ExtensionOnTypeParameterReceiverTypeWithBounds/");
    }

    @TestMetadata("ExtensionsAndGetPrefix")
    public void testExtensionsAndGetPrefix() throws Exception {
        runTest("../testData/basic/multifile/ExtensionsAndGetPrefix/");
    }

    @TestMetadata("ExtensionsForSmartCast")
    public void testExtensionsForSmartCast() throws Exception {
        runTest("../testData/basic/multifile/ExtensionsForSmartCast/");
    }

    @TestMetadata("FileRefInStringLiteral")
    public void testFileRefInStringLiteral() throws Exception {
        runTest("../testData/basic/multifile/FileRefInStringLiteral/");
    }

    @TestMetadata("FileRefInStringLiteralNoPrefix")
    public void testFileRefInStringLiteralNoPrefix() throws Exception {
        runTest("../testData/basic/multifile/FileRefInStringLiteralNoPrefix/");
    }

    @TestMetadata("GroovyClassNameCompletionFromDefaultPackage")
    public void testGroovyClassNameCompletionFromDefaultPackage() throws Exception {
        runTest("../testData/basic/multifile/GroovyClassNameCompletionFromDefaultPackage/");
    }

    @TestMetadata("GroovyClassNameCompletionFromNonDefaultPackage")
    public void testGroovyClassNameCompletionFromNonDefaultPackage() throws Exception {
        runTest("../testData/basic/multifile/GroovyClassNameCompletionFromNonDefaultPackage/");
    }

    @TestMetadata("HiddenDeclarations")
    public void testHiddenDeclarations() throws Exception {
        runTest("../testData/basic/multifile/HiddenDeclarations/");
    }

    @TestMetadata("HiddenDeclarationsInWhenCondition")
    public void testHiddenDeclarationsInWhenCondition() throws Exception {
        runTest("../testData/basic/multifile/HiddenDeclarationsInWhenCondition/");
    }

    @TestMetadata("ImplicitReceiverExposedSuperInterface")
    public void testImplicitReceiverExposedSuperInterface() throws Exception {
        runTest("../testData/basic/multifile/ImplicitReceiverExposedSuperInterface/");
    }

    @TestMetadata("InImportClassifiers")
    public void testInImportClassifiers() throws Exception {
        runTest("../testData/basic/multifile/InImportClassifiers/");
    }

    @TestMetadata("InImportCompanionObjectMembers")
    public void testInImportCompanionObjectMembers() throws Exception {
        runTest("../testData/basic/multifile/InImportCompanionObjectMembers/");
    }

    @TestMetadata("InImportEscaped")
    public void testInImportEscaped() throws Exception {
        runTest("../testData/basic/multifile/InImportEscaped/");
    }

    @TestMetadata("InImportExtension")
    public void testInImportExtension() throws Exception {
        runTest("../testData/basic/multifile/InImportExtension/");
    }

    @TestMetadata("InImportHighOrderTopLevelFun")
    public void testInImportHighOrderTopLevelFun() throws Exception {
        runTest("../testData/basic/multifile/InImportHighOrderTopLevelFun/");
    }

    @TestMetadata("InImportJavaClass")
    public void testInImportJavaClass() throws Exception {
        runTest("../testData/basic/multifile/InImportJavaClass/");
    }

    @TestMetadata("InImportJavaStatic")
    public void testInImportJavaStatic() throws Exception {
        runTest("../testData/basic/multifile/InImportJavaStatic/");
    }

    @TestMetadata("InImportNestedClassifiers")
    public void testInImportNestedClassifiers() throws Exception {
        runTest("../testData/basic/multifile/InImportNestedClassifiers/");
    }

    @TestMetadata("InImportNoClassMembers")
    public void testInImportNoClassMembers() throws Exception {
        runTest("../testData/basic/multifile/InImportNoClassMembers/");
    }

    @TestMetadata("InImportObjectMembers")
    public void testInImportObjectMembers() throws Exception {
        runTest("../testData/basic/multifile/InImportObjectMembers/");
    }

    @TestMetadata("InImportTopLevelVal")
    public void testInImportTopLevelVal() throws Exception {
        runTest("../testData/basic/multifile/InImportTopLevelVal/");
    }

    @TestMetadata("InImportedFunctionLiteralParameter")
    public void testInImportedFunctionLiteralParameter() throws Exception {
        runTest("../testData/basic/multifile/InImportedFunctionLiteralParameter/");
    }

    @TestMetadata("IncorrectGetters")
    public void testIncorrectGetters() throws Exception {
        runTest("../testData/basic/multifile/IncorrectGetters/");
    }

    @TestMetadata("InvisibleEnumEntryCompletion")
    public void testInvisibleEnumEntryCompletion() throws Exception {
        runTest("../testData/basic/multifile/InvisibleEnumEntryCompletion/");
    }

    @TestMetadata("JavaCallableReference")
    public void testJavaCallableReference() throws Exception {
        runTest("../testData/basic/multifile/JavaCallableReference/");
    }

    @TestMetadata("JavaClassQualifierWithTypeArguments")
    public void testJavaClassQualifierWithTypeArguments() throws Exception {
        runTest("../testData/basic/multifile/JavaClassQualifierWithTypeArguments/");
    }

    @TestMetadata("JavaConstructor")
    public void testJavaConstructor() throws Exception {
        runTest("../testData/basic/multifile/JavaConstructor/");
    }

    @TestMetadata("JavaEnum")
    public void testJavaEnum() throws Exception {
        runTest("../testData/basic/multifile/JavaEnum/");
    }

    @TestMetadata("JavaInnerClasses")
    public void testJavaInnerClasses() throws Exception {
        runTest("../testData/basic/multifile/JavaInnerClasses/");
    }

    @TestMetadata("KT12124")
    public void testKT12124() throws Exception {
        runTest("../testData/basic/multifile/KT12124/");
    }

    @TestMetadata("KT9835")
    public void testKT9835() throws Exception {
        runTest("../testData/basic/multifile/KT9835/");
    }

    @TestMetadata("KTIJ_27276")
    public void testKTIJ_27276() throws Exception {
        runTest("../testData/basic/multifile/KTIJ_27276/");
    }

    @TestMetadata("KTIJ_32378")
    public void testKTIJ_32378() throws Exception {
        runTest("../testData/basic/multifile/KTIJ_32378/");
    }

    @TestMetadata("KTIJ_32792")
    public void testKTIJ_32792() throws Exception {
        runTest("../testData/basic/multifile/KTIJ_32792/");
    }

    @TestMetadata("MoreSpecificExtensionGeneric")
    public void testMoreSpecificExtensionGeneric() throws Exception {
        runTest("../testData/basic/multifile/MoreSpecificExtensionGeneric/");
    }

    @TestMetadata("MoreSpecificExtensionInDifferentPackage")
    public void testMoreSpecificExtensionInDifferentPackage() throws Exception {
        runTest("../testData/basic/multifile/MoreSpecificExtensionInDifferentPackage/");
    }

    @TestMetadata("MoreSpecificExtensionIsPrivate")
    public void testMoreSpecificExtensionIsPrivate() throws Exception {
        runTest("../testData/basic/multifile/MoreSpecificExtensionIsPrivate/");
    }

    @TestMetadata("NoAutoInsertionOfNotImported")
    public void testNoAutoInsertionOfNotImported() throws Exception {
        runTest("../testData/basic/multifile/NoAutoInsertionOfNotImported/");
    }

    @TestMetadata("NoExpectedEnumEntryCompletion")
    public void testNoExpectedEnumEntryCompletion() throws Exception {
        runTest("../testData/basic/multifile/NoExpectedEnumEntryCompletion/");
    }

    @TestMetadata("NoExtForOuterFromNested")
    public void testNoExtForOuterFromNested() throws Exception {
        runTest("../testData/basic/multifile/NoExtForOuterFromNested/");
    }

    @TestMetadata("NoExtensionMethodDuplication")
    public void testNoExtensionMethodDuplication() throws Exception {
        runTest("../testData/basic/multifile/NoExtensionMethodDuplication/");
    }

    @TestMetadata("NoGenericFunDuplication")
    public void testNoGenericFunDuplication() throws Exception {
        runTest("../testData/basic/multifile/NoGenericFunDuplication/");
    }

    @TestMetadata("NotImportedClass")
    public void testNotImportedClass() throws Exception {
        runTest("../testData/basic/multifile/NotImportedClass/");
    }

    @TestMetadata("NotImportedExtensionForDefinitelyNotNullableType")
    public void testNotImportedExtensionForDefinitelyNotNullableType() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionForDefinitelyNotNullableType/");
    }

    @TestMetadata("NotImportedExtensionForFlexibleType")
    public void testNotImportedExtensionForFlexibleType() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionForFlexibleType/");
    }

    @TestMetadata("NotImportedExtensionForImplicitReceiver")
    public void testNotImportedExtensionForImplicitReceiver() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionForImplicitReceiver/");
    }

    @TestMetadata("NotImportedExtensionFunction")
    public void testNotImportedExtensionFunction() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionFunction/");
    }

    @TestMetadata("NotImportedExtensionFunction2")
    public void testNotImportedExtensionFunction2() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionFunction2/");
    }

    @TestMetadata("NotImportedExtensionFunction3")
    public void testNotImportedExtensionFunction3() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionFunction3/");
    }

    @TestMetadata("NotImportedExtensionFunctionAndAlias")
    public void testNotImportedExtensionFunctionAndAlias() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionFunctionAndAlias/");
    }

    @TestMetadata("NotImportedExtensionProperty")
    public void testNotImportedExtensionProperty() throws Exception {
        runTest("../testData/basic/multifile/NotImportedExtensionProperty/");
    }

    @TestMetadata("NotImportedFunction")
    public void testNotImportedFunction() throws Exception {
        runTest("../testData/basic/multifile/NotImportedFunction/");
    }

    @TestMetadata("NotImportedGenericReceiverExtension")
    public void testNotImportedGenericReceiverExtension() throws Exception {
        runTest("../testData/basic/multifile/NotImportedGenericReceiverExtension/");
    }

    @TestMetadata("NotImportedInfixExtension")
    public void testNotImportedInfixExtension() throws Exception {
        runTest("../testData/basic/multifile/NotImportedInfixExtension/");
    }

    @TestMetadata("NotImportedJavaClass")
    public void testNotImportedJavaClass() throws Exception {
        runTest("../testData/basic/multifile/NotImportedJavaClass/");
    }

    @TestMetadata("NotImportedNestedClassFromPrivateClass")
    public void testNotImportedNestedClassFromPrivateClass() throws Exception {
        runTest("../testData/basic/multifile/NotImportedNestedClassFromPrivateClass/");
    }

    @TestMetadata("NotImportedObject")
    public void testNotImportedObject() throws Exception {
        runTest("../testData/basic/multifile/NotImportedObject/");
    }

    @TestMetadata("NotImportedProperty")
    public void testNotImportedProperty() throws Exception {
        runTest("../testData/basic/multifile/NotImportedProperty/");
    }

    @TestMetadata("ObjectInTypePosition")
    public void testObjectInTypePosition() throws Exception {
        runTest("../testData/basic/multifile/ObjectInTypePosition/");
    }

    @TestMetadata("ObjectMembers")
    public void testObjectMembers() throws Exception {
        runTest("../testData/basic/multifile/ObjectMembers/");
    }

    @TestMetadata("ParameterNameAndTypeForNotImportedAlias")
    public void testParameterNameAndTypeForNotImportedAlias() throws Exception {
        runTest("../testData/basic/multifile/ParameterNameAndTypeForNotImportedAlias/");
    }

    @TestMetadata("ParameterNameAndTypeNestedClasses")
    public void testParameterNameAndTypeNestedClasses() throws Exception {
        runTest("../testData/basic/multifile/ParameterNameAndTypeNestedClasses/");
    }

    @TestMetadata("PreferKotlinClasses")
    public void testPreferKotlinClasses() throws Exception {
        runTest("../testData/basic/multifile/PreferKotlinClasses/");
    }

    @TestMetadata("PreferKotlinx")
    public void testPreferKotlinx() throws Exception {
        runTest("../testData/basic/multifile/PreferKotlinx/");
    }

    @TestMetadata("PreferKotlinxFlow")
    public void testPreferKotlinxFlow() throws Exception {
        runTest("../testData/basic/multifile/PreferKotlinxFlow/");
    }

    @TestMetadata("PreferMemberToExtension")
    public void testPreferMemberToExtension() throws Exception {
        runTest("../testData/basic/multifile/PreferMemberToExtension/");
    }

    @TestMetadata("PreferMemberToGlobal")
    public void testPreferMemberToGlobal() throws Exception {
        runTest("../testData/basic/multifile/PreferMemberToGlobal/");
    }

    @TestMetadata("PreferMoreSpecificExtension1")
    public void testPreferMoreSpecificExtension1() throws Exception {
        runTest("../testData/basic/multifile/PreferMoreSpecificExtension1/");
    }

    @TestMetadata("PreferMoreSpecificExtension2")
    public void testPreferMoreSpecificExtension2() throws Exception {
        runTest("../testData/basic/multifile/PreferMoreSpecificExtension2/");
    }

    @TestMetadata("PreferMoreSpecificExtension3")
    public void testPreferMoreSpecificExtension3() throws Exception {
        runTest("../testData/basic/multifile/PreferMoreSpecificExtension3/");
    }

    @TestMetadata("PropertyKeysEmptyString")
    public void testPropertyKeysEmptyString() throws Exception {
        runTest("../testData/basic/multifile/PropertyKeysEmptyString/");
    }

    @TestMetadata("PropertyKeysNoPrefix")
    public void testPropertyKeysNoPrefix() throws Exception {
        runTest("../testData/basic/multifile/PropertyKeysNoPrefix/");
    }

    @TestMetadata("PropertyKeysWithPrefix")
    public void testPropertyKeysWithPrefix() throws Exception {
        runTest("../testData/basic/multifile/PropertyKeysWithPrefix/");
    }

    @TestMetadata("StaticMembersOfImportedClassFromJava")
    public void testStaticMembersOfImportedClassFromJava() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfImportedClassFromJava/");
    }

    @TestMetadata("StaticMembersOfImportedInterfaceFromJava")
    public void testStaticMembersOfImportedInterfaceFromJava() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfImportedInterfaceFromJava/");
    }

    @TestMetadata("StaticMembersOfNotImportedClassFromJava")
    public void testStaticMembersOfNotImportedClassFromJava() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfNotImportedClassFromJava/");
    }

    @TestMetadata("StaticMembersOfNotImportedClassFromKotlin")
    public void testStaticMembersOfNotImportedClassFromKotlin() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfNotImportedClassFromKotlin/");
    }

    @TestMetadata("StaticMembersOfNotImportedClassFromKotlinObject")
    public void testStaticMembersOfNotImportedClassFromKotlinObject() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfNotImportedClassFromKotlinObject/");
    }

    @TestMetadata("StaticMembersOfNotImportedClassNameConflict")
    public void testStaticMembersOfNotImportedClassNameConflict() throws Exception {
        runTest("../testData/basic/multifile/StaticMembersOfNotImportedClassNameConflict/");
    }

    @TestMetadata("SuspensionPointInMonitor")
    public void testSuspensionPointInMonitor() throws Exception {
        runTest("../testData/basic/multifile/SuspensionPointInMonitor/");
    }

    @TestMetadata("SyntheticExtensionDeprecated")
    public void testSyntheticExtensionDeprecated() throws Exception {
        runTest("../testData/basic/multifile/SyntheticExtensionDeprecated/");
    }

    @TestMetadata("SyntheticExtensionForGenericClass")
    public void testSyntheticExtensionForGenericClass() throws Exception {
        runTest("../testData/basic/multifile/SyntheticExtensionForGenericClass/");
    }

    @TestMetadata("SyntheticExtensionNonVoidSetter")
    public void testSyntheticExtensionNonVoidSetter() throws Exception {
        runTest("../testData/basic/multifile/SyntheticExtensionNonVoidSetter/");
    }

    @TestMetadata("SyntheticPrimitiveJavaProperty")
    public void testSyntheticPrimitiveJavaProperty() throws Exception {
        runTest("../testData/basic/multifile/SyntheticPrimitiveJavaProperty/");
    }

    @TestMetadata("SyntheticPropertyWithoutJavaOrigin")
    public void testSyntheticPropertyWithoutJavaOrigin() throws Exception {
        runTest("../testData/basic/multifile/SyntheticPropertyWithoutJavaOrigin/");
    }

    @TestMetadata("TopLevelFunction")
    public void testTopLevelFunction() throws Exception {
        runTest("../testData/basic/multifile/TopLevelFunction/");
    }

    @TestMetadata("TopLevelPropertyNameCollision")
    public void testTopLevelPropertyNameCollision() throws Exception {
        runTest("../testData/basic/multifile/TopLevelPropertyNameCollision/");
    }

    @TestMetadata("TypeAliases")
    public void testTypeAliases() throws Exception {
        runTest("../testData/basic/multifile/TypeAliases/");
    }
}
