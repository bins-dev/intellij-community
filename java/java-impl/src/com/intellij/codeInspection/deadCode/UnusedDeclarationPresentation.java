// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.deadCode;

import com.intellij.analysis.AnalysisBundle;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.*;
import com.intellij.codeInspection.reference.*;
import com.intellij.codeInspection.ui.*;
import com.intellij.codeInspection.unusedSymbol.UnusedSymbolLocalInspection;
import com.intellij.codeInspection.util.RefFilter;
import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.client.ClientSystemInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.pom.Navigatable;
import com.intellij.profile.codeInspection.ui.DescriptionEditorPane;
import com.intellij.profile.codeInspection.ui.DescriptionEditorPaneKt;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.refactoring.safeDelete.SafeDeleteHandler;
import com.intellij.ui.HyperlinkAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ArrayUtil;
import com.intellij.util.VisibilityUtil;
import com.intellij.util.concurrency.SynchronizedClearableLazy;
import com.intellij.util.text.CharArrayUtil;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.StartupUiUtil;
import com.intellij.xml.util.XmlStringUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import kotlin.collections.CollectionsKt;
import org.jdom.Element;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApiStatus.Internal
public class UnusedDeclarationPresentation extends DefaultInspectionToolPresentation {
  private static final String[] SUPPRESSIONS = {UnusedDeclarationInspectionBase.SHORT_NAME, UnusedDeclarationInspectionBase.ALTERNATIVE_ID};
  private final Map<RefEntity, UnusedDeclarationHint> myFixedElements = ConcurrentCollectionFactory.createConcurrentIdentityMap();
  private final Set<RefEntity> myExcludedElements = ConcurrentCollectionFactory.createConcurrentIdentitySet();

  private final WeakUnreferencedFilter myFilter;
  private final boolean myIsShallowAnalysis;
  private DeadHTMLComposer myComposer;
  private final Supplier<InspectionToolWrapper<?, ?>> myDummyWrapper = new SynchronizedClearableLazy<>(() -> {
    InspectionToolWrapper<?, ?> toolWrapper = new GlobalInspectionToolWrapper(new DummyEntryPointsEP());
    toolWrapper.initialize(myContext);
    return toolWrapper;
  });

  private static final @NonNls String DELETE = "delete";
  private static final @NonNls String COMMENT = "comment";

  private enum UnusedDeclarationHint {
    COMMENT("inspection.dead.code.commented.hint"),
    DELETE("inspection.dead.code.deleted.hint");

    private final Supplier<@Nls String> myDescription;

    UnusedDeclarationHint(String descriptionKey) {
      myDescription = AnalysisBundle.messagePointer(descriptionKey);
    }

    public @Nls String getDescription() {
      return myDescription.get();
    }
  }

  public UnusedDeclarationPresentation(@NotNull InspectionToolWrapper toolWrapper, @NotNull GlobalInspectionContextImpl context) {
    super(toolWrapper, context);
    myQuickFixActions = createQuickFixes(toolWrapper);
    myFilter = new WeakUnreferencedFilter(getTool(), getContext());
    ((EntryPointsManagerBase)getEntryPointsManager()).setAddNonJavaEntries(getTool().ADD_NONJAVA_TO_ENTRIES);
    RefManagerImpl refManager = (RefManagerImpl)context.getRefManager();
    myIsShallowAnalysis = !refManager.isDeclarationsFound() && !refManager.isOfflineView();
  }

  public @NotNull RefFilter getFilter() {
    return myFilter;
  }

  private static final class WeakUnreferencedFilter extends UnreferencedFilter {
    private WeakUnreferencedFilter(@NotNull UnusedDeclarationInspectionBase tool, @NotNull GlobalInspectionContextImpl context) {
      super(tool, context);
    }

    @Override
    public int getElementProblemCount(final @NotNull RefJavaElement refElement) {
      final int problemCount = super.getElementProblemCount(refElement);
      if (problemCount > - 1) return problemCount;
      if (!((RefElementImpl)refElement).hasSuspiciousCallers() || ((RefJavaElementImpl)refElement).isSuspiciousRecursive()) return 1;

      for (RefElement element : refElement.getInReferences()) {
        if (((UnusedDeclarationInspectionBase)myTool).isEntryPoint(element)) return 1;
      }

      return 0;
    }
  }

  private @NotNull UnusedDeclarationInspectionBase getTool() {
    return (UnusedDeclarationInspectionBase)getToolWrapper().getTool();
  }

  @Override
  public @NotNull HTMLComposerImpl getComposer() {
    if (myIsShallowAnalysis) return super.getComposer();
    if (myComposer == null) {
      myComposer = new DeadHTMLComposer(this);
    }
    return myComposer;
  }

  @Override
  public boolean isExcluded(@NotNull RefEntity entity) {
    return myExcludedElements.contains(entity);
  }

  @Override
  public void amnesty(@NotNull RefEntity element) {
    myExcludedElements.remove(element);
  }

  @Override
  public void exclude(@NotNull RefEntity element) {
    myExcludedElements.add(element);
  }

  @Override
  public void exportResults(@NotNull Consumer<? super Element> resultConsumer,
                            @NotNull RefEntity refEntity,
                            @NotNull Predicate<? super CommonProblemDescriptor> excludedDescriptions) {
    RefJavaElement refinedElement = refineElement(refEntity);
    if (refinedElement != null) {
      Element element = refinedElement.getRefManager().export(refinedElement);
      if (element == null) return;

      @NonNls Element problemClassElement = new Element(INSPECTION_RESULTS_PROBLEM_CLASS_ELEMENT);
      problemClassElement.setAttribute(INSPECTION_RESULTS_ID_ATTRIBUTE, myToolWrapper.getShortName());

      final HighlightSeverity severity = getSeverity(refinedElement);
      final String attributeKey = HighlightInfoType.UNUSED_SYMBOL.getAttributesKey().getExternalName();
      problemClassElement.setAttribute(INSPECTION_RESULTS_SEVERITY_ATTRIBUTE, severity.myName);
      problemClassElement.setAttribute(INSPECTION_RESULTS_ATTRIBUTE_KEY_ATTRIBUTE, attributeKey);

      problemClassElement.addContent(AnalysisBundle.message("inspection.export.results.dead.code"));
      element.addContent(problemClassElement);

      @NonNls Element hintsElement = new Element(INSPECTION_RESULTS_HINTS_ELEMENT);

      for (UnusedDeclarationHint hint : UnusedDeclarationHint.values()) {
        @NonNls Element hintElement = new Element(INSPECTION_RESULTS_HINT_ELEMENT);
        hintElement.setAttribute(INSPECTION_RESULTS_VALUE_ATTRIBUTE, StringUtil.toLowerCase(hint.toString()));
        hintsElement.addContent(hintElement);
      }
      element.addContent(hintsElement);

      Element descriptionElement = new Element(INSPECTION_RESULTS_DESCRIPTION_ELEMENT);
      StringBuilder buf = new StringBuilder();
      DeadHTMLComposer.appendProblemSynopsis(refinedElement, buf);
      descriptionElement.addContent(buf.toString());
      element.addContent(descriptionElement);
      resultConsumer.accept(element);
    }
    super.exportResults(resultConsumer, refEntity, excludedDescriptions);
  }

  @Override
  public QuickFixAction @NotNull [] getQuickFixes(RefEntity @NotNull ... refEntities) {
    if (myIsShallowAnalysis) return QuickFixAction.EMPTY;
    for (RefEntity entity : refEntities) {
      if (entity instanceof RefJavaElement javaElement &&
          getFilter().accepts(javaElement) &&
          !myFixedElements.containsKey(entity) &&
          entity.isValid()) {
        return myQuickFixActions;
      }
    }
    return QuickFixAction.EMPTY;
  }

  private final QuickFixAction[] myQuickFixActions;

  private QuickFixAction @NotNull [] createQuickFixes(@NotNull InspectionToolWrapper toolWrapper) {
    return new QuickFixAction[]{new PermanentDeleteAction(toolWrapper), new CommentOutBin(toolWrapper), new MoveToEntries(toolWrapper)};
  }

  class PermanentDeleteAction extends QuickFixAction {
    PermanentDeleteAction(@NotNull InspectionToolWrapper toolWrapper) {
      super(AnalysisBundle.message("inspection.dead.code.safe.delete.quickfix"), AllIcons.Actions.Cancel, null, toolWrapper);
      copyShortcutFrom(ActionManager.getInstance().getAction("SafeDelete"));
    }

    @Override
    protected boolean applyFix(final RefEntity @NotNull [] refElements) {
      if (!super.applyFix(refElements)) return false;

      //filter only elements applicable to be deleted (exclude entry points)
      List<RefElement> list = new ArrayList<>();
      for (RefEntity entry : refElements) {
        if (entry instanceof RefJavaElement && getFilter().accepts((RefJavaElement)entry)) {
          list.add((RefElement)entry);
        }
      }
      RefElement[] filteredRefElements = list.toArray(new RefElement[0]);

      ApplicationManager.getApplication().invokeLater(() -> {
        final Project project = getContext().getProject();
        if (isDisposed() || project.isDisposed()) return;
        Set<RefEntity> classes = Arrays.stream(filteredRefElements)
          .filter(refElement -> refElement instanceof RefClass)
          .collect(Collectors.toSet());

        //filter out elements inside classes to be deleted
        PsiElement[] elements = Arrays.stream(filteredRefElements).filter(e -> {
          RefEntity owner = e.getOwner();
          return owner == null || !classes.contains(owner);
        }).map(e -> e.getPsiElement())
          .filter(e -> e != null)
          .toArray(PsiElement[]::new);
        SafeDeleteHandler.invoke(project, elements, false,
                                 () -> {
                                   removeElements(filteredRefElements, project, myToolWrapper);
                                   for (RefEntity ref : filteredRefElements) {
                                     myFixedElements.put(ref, UnusedDeclarationHint.DELETE);
                                   }
                                 });
      });

      return false; //refresh after safe delete dialog is closed
    }
  }

  private EntryPointsManager getEntryPointsManager() {
    return getContext().getExtension(GlobalJavaInspectionContext.CONTEXT).getEntryPointsManager(getContext().getRefManager());
  }

  class MoveToEntries extends QuickFixAction {
    MoveToEntries(@NotNull InspectionToolWrapper toolWrapper) {
      super(AnalysisBundle.message("inspection.dead.code.entry.point.quickfix"), AllIcons.Nodes.EntryPoints, null, toolWrapper);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      if (e.getPresentation().isEnabledAndVisible()) {
        final RefEntity[] elements = InspectionTree.getSelectedRefElements(e);
        for (RefEntity element : elements) {
          if (!((RefElement) element).isEntry()) {
            return;
          }
        }
        e.getPresentation().setEnabled(false);
      }
    }

    @Override
    protected boolean applyFix(RefEntity @NotNull [] refElements) {
      final EntryPointsManager entryPointsManager = getEntryPointsManager();
      for (RefEntity refElement : refElements) {
        if (refElement instanceof RefElement) {
          entryPointsManager.addEntryPoint((RefElement)refElement, true);
        }
      }

      return true;
    }
  }

  final class CommentOutBin extends QuickFixAction {
    CommentOutBin(@NotNull InspectionToolWrapper toolWrapper) {
      super(AnalysisBundle.message("inspection.dead.code.comment.quickfix"), null,
            KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, ClientSystemInfo.isMac() ? InputEvent.META_MASK : InputEvent.CTRL_MASK),
            toolWrapper);
    }

    @Override
    protected boolean applyFix(RefEntity @NotNull [] refElements) {
      if (!super.applyFix(refElements)) return false;
      List<RefElement> deletedRefs = new ArrayList<>(1);
      final RefFilter filter = getFilter();
      for (RefEntity refElement : refElements) {
        PsiElement psiElement = refElement instanceof RefElement ? ((RefElement)refElement).getPsiElement() : null;
        if (psiElement == null) continue;
        if (filter.getElementProblemCount((RefJavaElement)refElement) == 0) continue;

        final RefEntity owner = refElement.getOwner();
        if (!(owner instanceof RefJavaElement) || filter.getElementProblemCount((RefJavaElement)owner) == 0 || !(ArrayUtil.find(refElements, owner) > -1)) {
          commentOutDead(psiElement);
        }

        refElement.getRefManager().removeRefElement((RefElement)refElement, deletedRefs);
      }

      EntryPointsManager entryPointsManager = getEntryPointsManager();
      for (RefElement refElement : deletedRefs) {
        entryPointsManager.removeEntryPoint(refElement);
      }

      for (RefElement ref : deletedRefs) {
        myFixedElements.put(ref, UnusedDeclarationHint.COMMENT);
      }
      return true;
    }
  }

  private static final class CommentOutFix implements QuickFix {
    private final RefElement myElement;

    private CommentOutFix(RefElement element) {
      myElement = element;
    }

    @Override
    public @NotNull String getFamilyName() {
      return AnalysisBundle.message("inspection.dead.code.comment.quickfix");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull CommonProblemDescriptor descriptor) {
      if (myElement != null) {
        PsiElement element = myElement.getPsiElement();
        if (element != null) {
          commentOutDead(element);
        }
      }
    }
  }

  private static void commentOutDead(PsiElement psiElement) {
    PsiFile psiFile = psiElement.getContainingFile();

    if (psiFile != null) {
      Document doc = PsiDocumentManager.getInstance(psiElement.getProject()).getDocument(psiFile);
      if (doc != null) {
        TextRange textRange = psiElement.getTextRange();
        String date = DateFormatUtil.formatDateTime(new Date())
          .replaceAll("\\P{Graph}", " "); // replace all non-visible characters (including space) with spaces, e.g. NNBSP

        int startOffset = textRange.getStartOffset();
        CharSequence chars = doc.getCharsSequence();
        while (CharArrayUtil.regionMatches(chars, startOffset, AnalysisBundle.message("inspection.dead.code.comment"))) {
          int line = doc.getLineNumber(startOffset) + 1;
          if (line < doc.getLineCount()) {
            startOffset = doc.getLineStartOffset(line);
            startOffset = CharArrayUtil.shiftForward(chars, startOffset, " \t");
          }
        }

        int endOffset = textRange.getEndOffset();

        int line1 = doc.getLineNumber(startOffset);
        int line2 = doc.getLineNumber(endOffset - 1);

        if (line1 == line2) {
          doc.insertString(startOffset, AnalysisBundle.message("inspection.dead.code.date.comment", date));
        }
        else {
          for (int i = line1; i <= line2; i++) {
            doc.insertString(doc.getLineStartOffset(i), "//");
          }

          doc.insertString(doc.getLineStartOffset(Math.min(line2 + 1, doc.getLineCount() - 1)),
                           AnalysisBundle.message("inspection.dead.code.stop.comment", date));
          doc.insertString(doc.getLineStartOffset(line1), AnalysisBundle.message("inspection.dead.code.start.comment", date));
        }
      }
    }
  }

  /**
   * Adds the Entry Points node to the Unused Declaration inspection's results.
   */
  @Override
  public void patchToolNode(@NotNull InspectionTreeNode node,
                            @NotNull InspectionRVContentProvider provider,
                            boolean showStructure,
                            boolean groupByStructure) {
    if (myIsShallowAnalysis) return;
    InspectionTreeModel model = myContext.getView().getTree().getInspectionTreeModel();
    EntryPointsNode epNode = model.createCustomNode(myDummyWrapper.get(), () -> new EntryPointsNode(myDummyWrapper.get(), myContext, node), node);
    InspectionToolPresentation presentation = myContext.getPresentation(myDummyWrapper.get());
    presentation.updateContent();
    provider.appendToolNodeContent(myContext, myDummyWrapper.get(), epNode, showStructure, groupByStructure);
  }

  @Override
  public @NotNull RefElementNode createRefNode(@Nullable RefEntity entity,
                                               @NotNull InspectionTreeModel model,
                                               @NotNull InspectionTreeNode parent) {
    if (myIsShallowAnalysis) return super.createRefNode(entity, model, parent);
    return new UnusedDeclarationRefElementNode(entity, this, parent);
  }

  @Override
  public boolean isProblemResolved(@Nullable RefEntity entity) {
    return entity != null && myFixedElements.containsKey(entity);
  }

  @Override
  public synchronized void updateContent() {
    if (myIsShallowAnalysis) {
      super.updateContent();
      return;
    }
    clearContents();
    getContext().getRefManager().iterate(new RefJavaVisitor() {
      @Override public void visitElement(@NotNull RefEntity refEntity) {
        refEntity = refineElement(refEntity);
        if (refEntity == null) return;
        registerContentEntry(refEntity, RefJavaUtil.getInstance().getPackageName(refEntity));
      }
    });
    updateProblemElements();
  }

  private @Nullable RefJavaElement refineElement(RefEntity refEntity) {
    if (myIsShallowAnalysis) return null;
    if (!(refEntity instanceof RefJavaElement refElement)) return null; // dead code doesn't work with refModule | refPackage
    RefFilter filter = getFilter();
    if (!filter.accepts(refElement)) return null;
    RefJavaElement refinedElement = (RefJavaElement)getRefManager().getRefinedElement(refEntity);
    if (refinedElement != refEntity && filter.accepts(refinedElement)) return null; // prevent duplicate reporting
    if (!refinedElement.isValid()) return null;
    if (!compareVisibilities(refinedElement, getTool().getSharedLocalInspectionTool())) return null;
    if (isSuppressed(refinedElement) || refinedElement.isSuppressed(SUPPRESSIONS)) return null;
    if (!ApplicationManager.getApplication().isHeadlessEnvironment() & getContext().getUIOptions().FILTER_RESOLVED_ITEMS &&
        (myFixedElements.containsKey(refinedElement) || isExcluded(refinedElement))) {
      return null;
    }
    if (skipEntryPoints(refinedElement)) return null;
    if (!isProblemElementMatchesToolLanguage(refinedElement)) return null;
    return refinedElement;
  }

  private boolean isProblemElementMatchesToolLanguage(RefJavaElement refinedElement) {
    var toolLanguageId = getToolWrapper().getLanguage();
    if (toolLanguageId == null) return true;

    var elementLanguageDialects = InspectionEngine.calcElementDialectIds(CollectionsKt.listOfNotNull(refinedElement.getPsiElement()));
    return ToolLanguageUtil.isToolLanguageOneOf(elementLanguageDialects, toolLanguageId, getToolWrapper().applyToDialects());
  }

  protected boolean skipEntryPoints(RefJavaElement refElement) {
    return getTool().isEntryPoint(refElement);
  }

  @PsiModifier.ModifierConstant
  private static String getAcceptedVisibility(UnusedSymbolLocalInspection tool, RefJavaElement element) {
    if (element instanceof RefImplicitConstructor) {
      element = ((RefImplicitConstructor)element).getOwnerClass();
    }
    if (element instanceof RefClass) {
      return element.getOwner() instanceof RefClass ? tool.getInnerClassVisibility() : tool.getClassVisibility();
    }
    if (element instanceof RefField) {
      return tool.getFieldVisibility();
    }
    if (element instanceof RefMethod) {
      final String methodVisibility = tool.getMethodVisibility();
      if (methodVisibility != null &&
          //todo store in the graph
          tool.isIgnoreAccessors()) {
        final PsiElement psi = element.getPsiElement();
        if (psi instanceof PsiMethod && PropertyUtilBase.isSimplePropertyAccessor((PsiMethod)psi)) {
          return null;
        }
      }
      return methodVisibility;
    }
    if (element instanceof RefParameter) {
      return tool.getParameterVisibility();
    }
    return PsiModifier.PUBLIC;
  }

  private static boolean compareVisibilities(RefJavaElement listOwner, UnusedSymbolLocalInspection localInspectionTool) {
    return compareVisibilities(listOwner, getAcceptedVisibility(localInspectionTool, listOwner));
  }

  static boolean compareVisibilities(RefJavaElement listOwner, final String acceptedVisibility) {
    if (acceptedVisibility != null) {
      while (listOwner != null) {
        if (VisibilityUtil.compare(listOwner.getAccessModifier(), acceptedVisibility) >= 0) {
          return true;
        }
        final RefEntity parent = listOwner.getOwner();
        if (parent instanceof RefJavaElement) {
          listOwner = (RefJavaElement)parent;
        }
        else {
          break;
        }
      }
    }
    return false;
  }

  @Override
  public void ignoreElement(@NotNull RefEntity refEntity) {
    RefEntity owner = refEntity;
    if (refEntity instanceof RefParameter) {
      owner = refEntity.getOwner();
    }

    final CommonProblemDescriptor[] descriptors = getProblemElements().get(owner);
    if (descriptors != null) {
      final PsiElement psiElement = ReadAction.compute(() -> ((RefElement)refEntity).getPsiElement());
      List<CommonProblemDescriptor> foreignDescriptors = new ArrayList<>();
      for (CommonProblemDescriptor descriptor : descriptors) {
        if (descriptor instanceof ProblemDescriptor) {
          PsiElement problemElement = ReadAction.compute(() -> {
            PsiElement element = ((ProblemDescriptor)descriptor).getPsiElement();
            if (element instanceof PsiIdentifier) element = element.getParent();
            return element;
          });
          if (problemElement == psiElement) continue;
        }
        foreignDescriptors.add(descriptor);
      }
      if (foreignDescriptors.size() == descriptors.length) return;
    }
    super.ignoreElement(owner);
  }

  @Override
  public void cleanup() {
    super.cleanup();
    myFixedElements.clear();
  }

  @Override
  public @Nullable QuickFix<?> findQuickFixes(final @NotNull CommonProblemDescriptor descriptor,
                                              RefEntity entity,
                                              String hint) {
    if (entity instanceof RefElement) {
      if (DELETE.equals(hint)) {
        return new PermanentDeleteFix((RefElement)entity);
      }
      if (COMMENT.equals(hint)) {
        return new CommentOutFix((RefElement)entity);
      }
      if (entity instanceof RefParameter) {
        return super.findQuickFixes(descriptor, entity, hint);
      }
    }
    return null;
  }

  private static final class PermanentDeleteFix implements QuickFix {
    private final RefElement myElement;

    private PermanentDeleteFix(@Nullable RefElement element) {
      myElement = element;
    }

    @Override
    public @NotNull String getFamilyName() {
      return AnalysisBundle.message("inspection.dead.code.safe.delete.quickfix");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull CommonProblemDescriptor descriptor) {
      if (myElement != null && myElement.isValid()) {
        SafeDeleteHandler.invoke(project, new PsiElement[]{myElement.getPsiElement()}, false);
      }
    }

    @Override
    public boolean startInWriteAction() {
      return false;
    }
  }

  @Override
  public JComponent getCustomPreviewPanel(@NotNull RefEntity entity) {
    if (myIsShallowAnalysis) return null;
    final Project project = entity.getRefManager().getProject();
    DescriptionEditorPane htmlView = new DescriptionEditorPane() {
      @Override
      public String getToolTipText(MouseEvent evt) {
        int pos = viewToModel(evt.getPoint());
        if (pos >= 0) {
          HTMLDocument hdoc = (HTMLDocument) getDocument();
          javax.swing.text.Element e = hdoc.getCharacterElement(pos);
          AttributeSet a = e.getAttributes();

          SimpleAttributeSet value = (SimpleAttributeSet) a.getAttribute(HTML.Tag.A);
          if (value != null) {
            String objectPackage = (String) value.getAttribute("qualifiedname");
            if (objectPackage != null) {
              return XmlStringUtil.escapeString(objectPackage);
            }
          }
        }
        return null;
      }
    };
    htmlView.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      protected void hyperlinkActivated(@NotNull HyperlinkEvent e) {
        URL url = e.getURL();
        if (url == null) {
          return;
        }
        @NonNls String ref = url.getRef();

        int offset = Integer.parseInt(ref);
        String fileURL = url.toExternalForm();
        fileURL = fileURL.substring(0, fileURL.indexOf('#'));
        VirtualFile vFile = VirtualFileManager.getInstance().findFileByUrl(fileURL);
        if (vFile == null) {
          vFile = VfsUtil.findFileByURL(url);
        }
        if (vFile != null) {
          Navigatable descriptor = PsiNavigationSupport.getInstance().createNavigatable(project, vFile, offset);
          descriptor.navigate(true);
        }
      }
    });
    final StyleSheet css = ((HTMLEditorKit)htmlView.getEditorKit()).getStyleSheet();
    css.addRule("p.problem-description-group {text-indent: " + JBUIScale.scale(9) + "px;font-weight:bold;}");
    css.addRule("div.problem-description {margin-left: " + JBUIScale.scale(9) + "px;}");
    css.addRule("ul {margin-left:" + JBUIScale.scale(19) + "px;text-indent: 0}");
    css.addRule("code {font-family:" + StartupUiUtil.getLabelFont().getFamily() + "}");
    final @Nls StringBuilder buf = new StringBuilder();
    ((DeadHTMLComposer)getComposer()).compose(buf, entity, false);
    final String text = buf.toString();
    DescriptionEditorPaneKt.readHTML(htmlView, text);
    return ScrollPaneFactory.createScrollPane(htmlView, true);
  }

  @ApiStatus.Internal
  protected static class UnusedDeclarationRefElementNode extends RefElementNode {
    UnusedDeclarationRefElementNode(@Nullable RefEntity entity,
                                    @NotNull UnusedDeclarationPresentation presentation,
                                    @NotNull InspectionTreeNode parent) {
      super(entity, presentation, parent);
    }

    @Override
    public @Nullable String getTailText() {
      RefEntity element = getElement();
      final UnusedDeclarationHint hint = element == null ? null : ((UnusedDeclarationPresentation)getPresentation()).myFixedElements.get(element);
      if (hint != null) {
        return hint.getDescription();
      }
      return super.getTailText();
    }

    @Override
    public boolean isQuickFixAppliedFromView() {
      RefEntity element = getElement();
      return element != null && ((UnusedDeclarationPresentation)getPresentation()).myFixedElements.containsKey(element);
    }

    @Override
    protected void visitProblemSeverities(@NotNull Object2IntMap<HighlightDisplayLevel> counter) {
      if (!isExcluded() && isLeaf() && !getPresentation().isProblemResolved(getElement()) &&
          !getPresentation().isSuppressed(getElement())) {
        HighlightSeverity severity = InspectionToolResultExporter.getSeverity(getElement(), null, getPresentation());
        HighlightDisplayLevel level = HighlightDisplayLevel.find(severity);
        counter.mergeInt(level, 1, Math::addExact);
        return;
      }
      super.visitProblemSeverities(counter);
    }
  }
}
