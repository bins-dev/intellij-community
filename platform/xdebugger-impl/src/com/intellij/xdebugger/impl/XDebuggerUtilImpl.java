// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl;

import com.intellij.CommonBundle;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.debugger.impl.shared.XDebuggerUtilImplShared;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.SimpleColoredText;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.popup.list.ListPopupImpl;
import com.intellij.util.DocumentUtil;
import com.intellij.util.ModalityUiUtil;
import com.intellij.util.Processor;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.breakpoints.ui.XBreakpointGroupingRule;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValueContainer;
import com.intellij.xdebugger.impl.breakpoints.*;
import com.intellij.xdebugger.impl.breakpoints.ui.grouping.XBreakpointFileGroupingRule;
import com.intellij.xdebugger.impl.evaluate.ValueLookupManagerController;
import com.intellij.xdebugger.impl.frame.XDebugManagerProxy;
import com.intellij.xdebugger.impl.frame.XDebugSessionProxy;
import com.intellij.xdebugger.impl.frame.XStackFrameContainerEx;
import com.intellij.xdebugger.impl.settings.XDebuggerSettingManagerImpl;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeState;
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase;
import com.intellij.xdebugger.settings.XDebuggerSettings;
import com.intellij.xdebugger.ui.DebuggerColors;
import kotlin.Unit;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.intellij.xdebugger.impl.breakpoints.XBreakpointTypeProxyKt.asProxy;
import static org.jetbrains.concurrency.Promises.asPromise;
import static org.jetbrains.concurrency.Promises.rejectedPromise;

@ApiStatus.Internal
public class XDebuggerUtilImpl extends XDebuggerUtil {
  private static final Logger LOG = Logger.getInstance(XDebuggerUtilImpl.class);
  
  private static final Ref<Boolean> SHOW_BREAKPOINT_AD = new Ref<>(true);

  public static final DataKey<Integer> LINE_NUMBER = DataKey.create("x.debugger.line.number");
  public static final DataKey<Integer> OFFSET = DataKey.create("x.debugger.offset");

  @Override
  public XLineBreakpointType<?>[] getLineBreakpointTypes() {
    return XBreakpointUtil.breakpointTypes().select(XLineBreakpointType.class).toArray(XLineBreakpointType<?>[]::new);
  }

  @Override
  public void toggleLineBreakpoint(final @NotNull Project project, final @NotNull VirtualFile file, final int line, boolean temporary) {
    toggleAndReturnLineBreakpoint(project, file, line, temporary);
  }

  private @Nullable XLineBreakpointType<?> getBreakpointTypeByPosition(final @NotNull Project project,
                                                                       final @NotNull VirtualFile file,
                                                                       final int line) {
    XLineBreakpointType<?> typeWinner = null;
    for (XLineBreakpointType<?> type : getLineBreakpointTypes()) {
      if (type.canPutAt(file, line, project) && (typeWinner == null || type.getPriority() > typeWinner.getPriority())) {
        typeWinner = type;
      }
    }
    return typeWinner;
  }

  public @NotNull Promise<@Nullable XLineBreakpoint> toggleAndReturnLineBreakpoint(final @NotNull Project project,
                                                                                   final @NotNull VirtualFile file,
                                                                                   final int line,
                                                                                   boolean temporary) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    final XLineBreakpointType<?> typeWinner = getBreakpointTypeByPosition(project, file, line);
    if (typeWinner != null) {
      return toggleAndReturnLineBreakpoint(project, typeWinner, file, line, temporary);
    }
    return rejectedPromise(new RuntimeException("Cannot find appropriate breakpoint type"));
  }

  @Override
  public boolean canPutBreakpointAt(@NotNull Project project, @NotNull VirtualFile file, int line) {
    return ContainerUtil.exists(getLineBreakpointTypes(), type -> type.canPutAt(file, line, project));
  }

  @Override
  public <P extends XBreakpointProperties> void toggleLineBreakpoint(final @NotNull Project project,
                                                                     final @NotNull XLineBreakpointType<P> type,
                                                                     final @NotNull VirtualFile file,
                                                                     final int line,
                                                                     final boolean temporary) {
    toggleAndReturnLineBreakpoint(project, type, file, line, temporary);
  }

  public @NotNull <P extends XBreakpointProperties> Promise<@Nullable XLineBreakpoint> toggleAndReturnLineBreakpoint(final @NotNull Project project,
                                                                                                                     final @NotNull XLineBreakpointType<P> type,
                                                                                                                     final @NotNull VirtualFile file,
                                                                                                                     final int line,
                                                                                                                     final boolean temporary) {
    XSourcePositionImpl position = XSourcePositionImpl.create(file, line);
    return toggleAndReturnLineBreakpoint(project, Collections.singletonList(type), position, false, temporary, null, true);
  }

  /**
   * Get non-empty list of variants assuming that given list of types is non-empty too.
   */
  public static List<? extends XLineBreakpointType.XLineBreakpointVariant>
  getLineBreakpointVariantsSync(final @NotNull Project project,
                                @NotNull List<? extends XLineBreakpointType> types,
                                final @NotNull XSourcePosition position) {
    if (types.isEmpty()) {
      throw new IllegalArgumentException("non-empty types are expected");
    }

    boolean multipleTypes = types.size() > 1;
    List<XLineBreakpointType.XLineBreakpointVariant> allVariants = new SmartList<>();
    for (XLineBreakpointType type : types) {
      var variants = type.computeVariants(project, position);
      if (variants.isEmpty() && multipleTypes) {
        // We have multiple types, but no non-default variants for this type. So we just create one.
        allVariants.add(createDefaultBreakpointVariant(position, type));
      }
      else {
        allVariants.addAll(variants);
      }
    }

    if (allVariants.isEmpty()) {
      assert !multipleTypes;
      return Collections.singletonList(createDefaultBreakpointVariant(position, types.get(0)));
    } else {
      return allVariants;
    }
  }

  /**
   * Get non-empty list of variants assuming that given list of types is non-empty too.
   */
  public static Promise<List<? extends XLineBreakpointType.XLineBreakpointVariant>>
  getLineBreakpointVariants(final @NotNull Project project,
                            @NotNull List<? extends XLineBreakpointType> types,
                            final @NotNull XSourcePosition position) {
    if (types.isEmpty()) {
      return rejectedPromise("non-empty types are expected");
    }

    boolean multipleTypes = types.size() > 1;
    List<Promise<List<? extends XLineBreakpointType.XLineBreakpointVariant>>> promises = new SmartList<>();
    for (XLineBreakpointType type : types) {
      promises.add(type.computeVariantsAsync(project, position).then(o -> {
        if (((List<?>)o).isEmpty() && multipleTypes) {
          // We have multiple types, but no non-default variants for this type. So we just create one.
          return Collections.singletonList(createDefaultBreakpointVariant(position, type));
        }
        else {
          return o;
        }
      }));
    }
    return Promises.collectResults(promises).then(v -> {
      var variants = StreamEx.of(v).toFlatList(l -> l);
      if (variants.isEmpty()) {
        assert !multipleTypes;
        return Collections.singletonList(createDefaultBreakpointVariant(position, types.get(0)));
      } else {
        return variants;
      }
    });
  }

  private static @NotNull XLineBreakpointType.XLineBreakpointAllVariant createDefaultBreakpointVariant(@NotNull XSourcePosition position, XLineBreakpointType type) {
    return type.new XLineBreakpointAllVariant(position) {
      @Override
      public @NotNull String getText() {
        return StringUtil.notNullize(StringUtil.unpluralize(type.getTitle()), type.getTitle());
      }

      @Override
      public @NotNull Icon getIcon() {
        return type.getEnabledIcon();
      }

      @Override
      public boolean isMultiVariant() {
        // TODO[inline-bp]: unfortunatelly it's wrong for default line variant, which is currently "all" by default,
        //                  see IDEA-336373.
        return false;
      }
    };
  }

  private static int getIndexOfBestMatchingInlineVariant(int caretOffset, List<? extends FrontendXLineBreakpointVariant> variants) {
    assert !variants.isEmpty();
    TextRange bestRange = null;
    int bestIndex = -1;
    for (int i = 0; i < variants.size(); i++) {
      var variant = variants.get(i);
      TextRange range = variant.getHighlightRange();
      if (range != null && range.contains(caretOffset)) {
        if (bestRange == null || bestRange.getLength() > range.getLength()) {
          bestRange = range;
          bestIndex = i;
        }
      }
    }
    // Use first variant if nothing interesting is found.
    return bestIndex == -1 ? 0 : bestIndex;
  }

  private static <T> @NotNull T getBestMatchingBreakpoint(int caretOffset, Iterator<@NotNull T> breakpoints, Function<T, @Nullable TextRange> rangeProvider) {
    // Best matching = closest to the insertion point and minimal by range of all breakpoints or breakpoint variants
    T bestBreakpoint = null;
    int bestDistance = Integer.MAX_VALUE;
    int bestRangeLength = Integer.MAX_VALUE;
    while (breakpoints.hasNext()) {
      var b = breakpoints.next();
      TextRange range = rangeProvider.apply(b);
      int rangeLength = range != null ? range.getLength() : Integer.MAX_VALUE;
      // note that range = null means "whole line"
      int distance = range == null ?
                       0 :
                       range.containsOffset(caretOffset) ? //include end offset
                         0 :
                         Math.min(Math.abs(range.getStartOffset() - caretOffset), Math.abs(range.getEndOffset() - caretOffset));
      if (bestBreakpoint == null || distance < bestDistance || (distance == bestDistance && rangeLength < bestRangeLength)) {
          bestBreakpoint = b;
          bestDistance = distance;
          bestRangeLength = rangeLength;
        }
    }
    assert bestBreakpoint != null;
    return bestBreakpoint;
  }

  /**
   * @deprecated use {@link #toggleAndReturnLineBreakpoint(Project, List, XSourcePosition, boolean, boolean, Editor, boolean)}
   */
  @Deprecated(forRemoval = true)
  public static @NotNull Promise<@Nullable XLineBreakpoint> toggleAndReturnLineBreakpoint(final @NotNull Project project,
                                                                                 @NotNull List<? extends XLineBreakpointType> types,
                                                                                 final @NotNull XSourcePosition position,
                                                                                 final boolean temporary,
                                                                                 final @Nullable Editor editor,
                                                                                 boolean canRemove) {
    return toggleAndReturnLineBreakpoint(project, types, position, true, temporary, editor, canRemove);
  }

  /**
   * Use proxy option instead.
   */
  @ApiStatus.Obsolete
  public static @NotNull Promise<@Nullable XLineBreakpoint> toggleAndReturnLineBreakpoint(
    final @NotNull Project project,
    @NotNull List<? extends XLineBreakpointType> types,
    final @NotNull XSourcePosition position,
    boolean selectVariantByPositionColumn,
    final boolean temporary,
    final @Nullable Editor editor,
    boolean canRemove
  ) {
    var proxyTypes = ContainerUtil.map(types, t -> asProxy(t, project));
    var future = toggleAndReturnLineBreakpointProxy(
      project, proxyTypes, position, selectVariantByPositionColumn,
      temporary, editor, canRemove, false, null);
    return asPromise(future)
      .then(b -> b instanceof XLineBreakpointProxy.Monolith monolith ? monolith.getBreakpoint() : null);
  }

  public static @NotNull CompletableFuture<@Nullable XLineBreakpointProxy> toggleAndReturnLineBreakpointProxy(
    final @NotNull Project project,
    @NotNull List<XLineBreakpointTypeProxy> types,
    final @NotNull XSourcePosition position,
    boolean selectVariantByPositionColumn,
    final boolean temporary,
    final @Nullable Editor editor,
    boolean canRemove,
    boolean isLogging,
    @Nullable String logExpression
  ) {
    var breakpointInfo = new XLineBreakpointInstallationInfo(types, position, temporary, isLogging, logExpression, canRemove);
    return toggleAndReturnLineBreakpointProxy(project, editor, breakpointInfo, selectVariantByPositionColumn);
  }

  public static @NotNull CompletableFuture<@Nullable XLineBreakpointProxy> toggleAndReturnLineBreakpointProxy(
    @NotNull Project project,
    @Nullable Editor editor,
    @NotNull XLineBreakpointInstallationInfo breakpointInfo,
    boolean selectVariantByPositionColumn
  ) {
    if (areInlineBreakpointsEnabled(breakpointInfo.getPosition().getFile())) {
      return processInlineBreakpoints(project, editor, breakpointInfo, selectVariantByPositionColumn);
    }
    else {
      return selectBreakpointVariantWithPopup(project, breakpointInfo, editor);
    }
  }

  private static @NotNull CompletableFuture<@Nullable XLineBreakpointProxy> selectBreakpointVariantWithPopup(
    @NotNull Project project,
    @NotNull XLineBreakpointInstallationInfo breakpointInfo,
    @Nullable Editor editor
  ) {
    final VirtualFile file = breakpointInfo.getPosition().getFile();
    final int line = breakpointInfo.getPosition().getLine();
    var breakpointManager = XDebugManagerProxy.getInstance().getBreakpointManagerProxy(project);

    for (XLineBreakpointTypeProxy type : breakpointInfo.getTypes()) {
      XLineBreakpointProxy breakpoint = breakpointManager.findBreakpointAtLine(type, file, line);
      if (breakpoint != null) {
        removeBreakpointIfPossible(project, breakpointInfo, breakpoint);
        return CompletableFuture.completedFuture(null);
      }
    }
    return FrontendXLineBreakpointVariantKt.computeBreakpointProxy(project, editor, breakpointInfo, variantChoice -> {
      assert !variantChoice.getVariants().isEmpty();
      ModalityUiUtil.invokeLaterIfNeeded(ModalityState.defaultModalityState(), () -> {
        for (XLineBreakpointTypeProxy type : breakpointInfo.getTypes()) {
          if (breakpointManager.findBreakpointAtLine(type, file, line) != null) {
            variantChoice.breakpointRemoved();
            return;
          }
        }
        RelativePoint relativePoint = editor != null ? DebuggerUIUtil.getPositionForPopup(editor, line) : null;
        if (variantChoice.getVariants().size() > 1 && relativePoint != null) {
          showBreakpointSelectionPopup(project, breakpointInfo.getPosition(), editor, variantChoice, relativePoint);
        }
        else {
          variantChoice.select(variantChoice.getVariants().get(0));
        }
      });
      return Unit.INSTANCE;
    });
  }

  private static void showBreakpointSelectionPopup(
    @NotNull Project project,
    @NotNull XSourcePosition position,
    @NotNull Editor editor,
    VariantChoiceData choiceData,
    RelativePoint relativePoint
  ) {
    final int line = position.getLine();
    class MySelectionListener implements ListSelectionListener {
      RangeHighlighter myHighlighter = null;

      @Override
      public void valueChanged(ListSelectionEvent e) {
        if (!e.getValueIsAdjusting()) {
          updateHighlighter(((JList<?>)e.getSource()).getSelectedValue());
        }
      }

      public void initialSet(Object value) {
        if (myHighlighter == null) {
          updateHighlighter(value);
        }
      }

      void updateHighlighter(Object value) {
        clearHighlighter();
        if (value instanceof FrontendXLineBreakpointVariant variant) {
          TextRange range = variant.getHighlightRange();
          TextRange lineRange = DocumentUtil.getLineTextRange(editor.getDocument(), line);
          if (range == null) {
            range = lineRange;
          }
          if (!range.isEmpty() && range.intersectsStrict(lineRange)) {
            myHighlighter = editor.getMarkupModel().addRangeHighlighter(
              DebuggerColors.BREAKPOINT_ATTRIBUTES, range.getStartOffset(), range.getEndOffset(),
              DebuggerColors.BREAKPOINT_HIGHLIGHTER_LAYER,
              HighlighterTargetArea.EXACT_RANGE);
          }
        }
      }

      private void clearHighlighter() {
        if (myHighlighter != null) {
          myHighlighter.dispose();
        }
      }
    }

    final int defaultIndex = getIndexOfBestMatchingInlineVariant(position.getOffset(), choiceData.getVariants());

    final MySelectionListener selectionListener = new MySelectionListener();
    BaseListPopupStep<FrontendXLineBreakpointVariant> step =
      new BaseListPopupStep<>(XDebuggerBundle.message("popup.title.set.breakpoint"), choiceData.getVariants()) {
        @Override
        public @NotNull String getTextFor(FrontendXLineBreakpointVariant value) {
          return value.getText();
        }

        @Override
        public Icon getIconFor(FrontendXLineBreakpointVariant value) {
          return value.getIcon();
        }

        @Override
        public void canceled() {
          selectionListener.clearHighlighter();
          choiceData.cancel();
        }

        @Override
        public PopupStep<?> onChosen(FrontendXLineBreakpointVariant selectedValue, boolean finalChoice) {
          selectionListener.clearHighlighter();
          choiceData.select(selectedValue);
          return FINAL_CHOICE;
        }

        @Override
        public int getDefaultOptionIndex() {
          return defaultIndex;
        }
      };
    ListPopupImpl popup = new ListPopupImpl(project, step) {
      @Override
      protected void afterShow() {
        super.afterShow();
        selectionListener.initialSet(getList().getSelectedValue());
      }
    };
    DebuggerUIUtil.registerExtraHandleShortcuts(popup, SHOW_BREAKPOINT_AD, IdeActions.ACTION_TOGGLE_LINE_BREAKPOINT);

    popup.addListSelectionListener(selectionListener);
    popup.show(relativePoint);
  }

  private static @NotNull CompletableFuture<@Nullable XLineBreakpointProxy> processInlineBreakpoints(
    @NotNull Project project,
    @Nullable Editor editor,
    @NotNull XLineBreakpointInstallationInfo breakpointInfo,
    boolean selectVariantByPositionColumn
  ) {
    return FrontendXLineBreakpointVariantKt.computeBreakpointProxy(project, editor, breakpointInfo, variantChoice -> {
      var variants = variantChoice.getVariants().stream().filter(v -> v.getUseAsInlineVariant()).toList();
      if (variants.isEmpty()) {
        LOG.error("Unexpected empty variants");
        variantChoice.cancel();
        return Unit.INSTANCE;
      }

      List<XLineBreakpointProxy> breakpoints = findBreakpointsAtLine(project, breakpointInfo);

      FrontendXLineBreakpointVariant variant;
      if (selectVariantByPositionColumn) {
        Object breakpointOrVariant = getBestMatchingBreakpoint(breakpointInfo.getPosition().getOffset(),
                                                               Stream.concat(breakpoints.stream(), variants.stream()).iterator(),
                                                               o ->
                                                                 o instanceof XLineBreakpointProxy b ? rangeOrNull(b.getHighlightRange())
                                                                 : ((FrontendXLineBreakpointVariant)o).getHighlightRange());

        if (breakpointOrVariant instanceof XLineBreakpointProxy existingBreakpoint) {
          removeBreakpointIfPossible(project, breakpointInfo, existingBreakpoint);
          variantChoice.breakpointRemoved();
          return Unit.INSTANCE;
        }

        variant = (FrontendXLineBreakpointVariant)breakpointOrVariant;
      }
      else {
        if (!breakpoints.isEmpty()) {
          removeBreakpointIfPossible(project, breakpointInfo, breakpoints.toArray(XLineBreakpointProxy[]::new));
          variantChoice.breakpointRemoved();
          return Unit.INSTANCE;
        }

        variant = variants.stream().max(Comparator.comparing(v -> v.getPriority())).get();
      }

      variantChoice.select(variant);
      return Unit.INSTANCE;
    });
  }

  private static TextRange rangeOrNull(XLineBreakpointHighlighterRange range) {
    if (range instanceof XLineBreakpointHighlighterRange.Available available) {
      return available.getRange();
    }
    return null;
  }

  @ApiStatus.Internal
  public static @NotNull List<@NotNull XLineBreakpointProxy> findBreakpointsAtLine(
    @NotNull Project project,
    @NotNull XLineBreakpointInstallationInfo breakpointInfo
  ) {
    var breakpointManager = XDebugManagerProxy.getInstance().getBreakpointManagerProxy(project);
    final VirtualFile file = breakpointInfo.getPosition().getFile();
    final int line = breakpointInfo.getPosition().getLine();
    return breakpointInfo.getTypes().stream().flatMap(t -> breakpointManager.findBreakpointsAtLine(t, file, line).stream()).toList();
  }

  public static <P extends XBreakpointProperties> XLineBreakpoint<P> addLineBreakpoint(XBreakpointManager breakpointManager,
                                                                                       XLineBreakpointType<P>.XLineBreakpointVariant variant,
                                                                                       VirtualFile file,
                                                                                       int line) {
    return addLineBreakpoint(breakpointManager, variant, file, line, false);
  }

  public static <P extends XBreakpointProperties> XLineBreakpoint<P> addLineBreakpoint(XBreakpointManager breakpointManager,
                                                                                       XLineBreakpointType<P>.XLineBreakpointVariant variant,
                                                                                       VirtualFile file,
                                                                                       int line,
                                                                                       Boolean temporary) {
    var properties = variant.createProperties();
    var type = variant.getType();
    var breakpoint = addLineBreakpoint(breakpointManager, type, properties, file, line, temporary);
    if (!type.variantAndBreakpointMatch(breakpoint, variant)) {
      LOG.error("breakpoint doesn't match source variant, " + type + ", " + variant.getClass());
    }
    return breakpoint;
  }

  private static <P extends XBreakpointProperties> XLineBreakpoint<P> addLineBreakpoint(XBreakpointManager breakpointManager,
                                                                                        XLineBreakpointType<P> type,
                                                                                        P properties,
                                                                                        VirtualFile file,
                                                                                        int line,
                                                                                        Boolean temporary) {
    return breakpointManager.addLineBreakpoint(type, file.getUrl(), line, properties, temporary);
  }

  public static boolean removeBreakpointWithConfirmation(final XBreakpointProxy breakpoint) {
    return removeBreakpointWithConfirmation(breakpoint.getProject(), breakpoint);
  }

  @ApiStatus.Internal
  public static <T extends XBreakpointProxy> void removeBreakpointIfPossible(Project project, XLineBreakpointInstallationInfo info, T... breakpoints) {
    if (info.canRemoveBreakpoint()) {
      removeBreakpointsWithConfirmation(project, breakpoints);
    }
  }

  static <T extends XBreakpointProxy> void removeBreakpointsWithConfirmation(final Project project, T... breakpoints) {
    // FIXME[inline-bp]: support multiple breakpoints restore
    // FIXME[inline-bp]: Reconsider this, maybe we should have single confirmation for all breakpoints.
    for (XBreakpointProxy b : breakpoints) {
      removeBreakpointWithConfirmation(project, b);
    }
  }

  public static void removeBreakpointsWithConfirmation(final List<XBreakpointProxy> breakpoints) {
    if (breakpoints.isEmpty()) return;
    var project = breakpoints.get(0).getProject();
    LOG.assertTrue(ContainerUtil.and(breakpoints, b -> b.getProject().equals(project)));
    removeBreakpointsWithConfirmation(project, breakpoints.toArray(XBreakpointProxy[]::new));
  }

  /**
   * @see CoroutineUtilsKt#performDebuggerActionAsync
   */
  public static void performDebuggerAction(@NotNull AnActionEvent e, @NotNull Runnable action) {
    action.run();
    reshowInlayRunToCursor(e);
  }

  public static void reshowInlayRunToCursor(@NotNull AnActionEvent e) {
    if (!(e.getInputEvent() instanceof MouseEvent)) {
      return;
    }

    Project project = e.getProject();
    if (project == null) {
      return;
    }

    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null) {
      return;
    }

    reshowInlayRunToCursor(project, editor);
  }

  @ApiStatus.Internal
  public static void reshowInlayRunToCursor(Project project, Editor editor) {
    if (XDebuggerManager.getInstance(project) instanceof XDebuggerManagerImpl debuggerManagerImpl) {
      debuggerManagerImpl.reshowInlayToolbar(editor);
    }
  }

  /**
   * Remove breakpoint. Show confirmation dialog if breakpoint has non-empty condition or log expression.
   * Returns whether breakpoint was really deleted.
   */
  public static boolean removeBreakpointWithConfirmation(final Project project, final XBreakpointProxy breakpoint) {
    if ((!isEmptyExpression(breakpoint.getConditionExpression()) || !isEmptyExpression(breakpoint.getLogExpressionObject())) &&
        !ApplicationManager.getApplication().isHeadlessEnvironment() &&
        !ApplicationManager.getApplication().isUnitTestMode() &&
        XDebuggerSettingManagerImpl.getInstanceImpl().getGeneralSettings().isConfirmBreakpointRemoval()) {
      StringBuilder message = new StringBuilder("<html>").append(XDebuggerBundle.message("message.confirm.breakpoint.removal.message"));
      if (!isEmptyExpression(breakpoint.getConditionExpression())) {
        message.append("<br>")
          .append(XDebuggerBundle.message("message.confirm.breakpoint.removal.message.condition"))
          .append("<br><pre>")
          .append(StringUtil.escapeXmlEntities(breakpoint.getConditionExpression().getExpression()))
          .append("</pre>");
      }
      if (!isEmptyExpression(breakpoint.getLogExpressionObject())) {
        message.append("<br>")
          .append(XDebuggerBundle.message("message.confirm.breakpoint.removal.message.log"))
          .append("<br><pre>")
          .append(StringUtil.escapeXmlEntities(breakpoint.getLogExpressionObject().getExpression()))
          .append("</pre>");
      }
      //noinspection HardCodedStringLiteral
      if (Messages.showOkCancelDialog(message.toString(),
                                      XDebuggerBundle.message("message.confirm.breakpoint.removal.title"),
                                      CommonBundle.message("button.remove"),
                                      Messages.getCancelButton(),
                                      Messages.getQuestionIcon(),
                                      new DoNotAskOption.Adapter() {
                                        @Override
                                        public void rememberChoice(boolean isSelected, int exitCode) {
                                          if (isSelected) {
                                            XDebuggerSettingManagerImpl.getInstanceImpl().getGeneralSettings()
                                                                       .setConfirmBreakpointRemoval(false);
                                          }
                                        }
                                      }) != Messages.OK) {
        return false;
      }
    }
    XBreakpointManagerProxy breakpointManager = XDebugManagerProxy.getInstance().getBreakpointManagerProxy(project);
    breakpointManager.rememberRemovedBreakpoint(breakpoint);
    breakpointManager.removeBreakpoint(breakpoint);
    return true;
  }

  @Override
  public void removeBreakpoint(Project project, XBreakpoint<?> breakpoint) {
    XDebuggerManager.getInstance(project).getBreakpointManager().removeBreakpoint(breakpoint);
  }

  public static void removeAllBreakpoints(@NotNull Project project) {
    ((XBreakpointManagerImpl)XDebuggerManager.getInstance(project).getBreakpointManager()).removeAllBreakpoints();
  }

  @Override
  public <T extends XBreakpointType> T findBreakpointType(@NotNull Class<T> typeClass) {
    return XBreakpointType.EXTENSION_POINT_NAME.findExtension(typeClass);
  }

  @Override
  public <T extends XDebuggerSettings<?>> T getDebuggerSettings(Class<T> aClass) {
    return XDebuggerSettingManagerImpl.getInstanceImpl().getSettings(aClass);
  }

  @Override
  public XValueContainer getValueContainer(DataContext dataContext) {
    return XDebuggerTreeActionBase.getSelectedValue(dataContext);
  }

  @Override
  public @Nullable XSourcePosition createPosition(@Nullable VirtualFile file, int line) {
    return file == null ? null : XSourcePositionImpl.create(file, line);
  }

  @Override
  public @Nullable XSourcePosition createPosition(@Nullable VirtualFile file, final int line, final int column) {
    return file == null ? null : XSourcePositionImpl.create(file, line, column);
  }

  @Override
  public @Nullable XSourcePosition createPositionByOffset(final VirtualFile file, final int offset) {
    return XSourcePositionImpl.createByOffset(file, offset);
  }

  @Override
  public @Nullable XSourcePosition createPositionByElement(PsiElement element) {
    return XSourcePositionImpl.createByElement(element);
  }

  @Override
  public <B extends XLineBreakpoint<?>> XBreakpointGroupingRule<B, ?> getGroupingByFileRule() {
    return new XBreakpointFileGroupingRule<>();
  }

  public static @Nullable XSourcePosition getCaretPosition(@NotNull Project project, DataContext context) {
    Editor editor = getEditor(project, context);
    if (editor == null) return null;

    Integer lineNumber = LINE_NUMBER.getData(context);
    if (lineNumber != null) {
      return XSourcePositionImpl.create(editor.getVirtualFile(), lineNumber);
    }
    Integer offsetFromDataContext = OFFSET.getData(context);
    if (offsetFromDataContext != null) {
      return XSourcePositionImpl.createByOffset(editor.getVirtualFile(), offsetFromDataContext);
    }

    final Document document = editor.getDocument();
    int offset = editor.getCaretModel().getOffset();
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    return XSourcePositionImpl.createByOffset(file, offset);
  }

  public static @Nullable Editor getEditor(@NotNull Project project, DataContext context) {
    Editor editor = CommonDataKeys.EDITOR.getData(context);
    if (editor == null) {
      @Nullable FileEditor fileEditor = context.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR);
      return fileEditor instanceof TextEditor textEditor ? textEditor.getEditor() : null;
    }
    return editor;
  }

  @Override
  public void iterateLine(@NotNull Project project, @NotNull Document document, int line, @NotNull Processor<? super PsiElement> processor) {
    int lineStart;
    int lineEnd;
    try {
      lineStart = document.getLineStartOffset(line);
      lineEnd = document.getLineEndOffset(line);
    }
    catch (IndexOutOfBoundsException ignored) {
      return;
    }

    iterateOffsetRange(project, document, lineStart, lineEnd, processor);
  }

  public void iterateOffsetRange(@NotNull Project project, @NotNull Document document, int startOffset, int endOffset,
                                 @NotNull Processor<? super PsiElement> processor) {
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
    if (file == null) {
      return;
    }
    PsiElement element;
    int offset = startOffset;
    while (offset < endOffset) {
      element = file.findElementAt(offset);
      if (element != null && element.getTextLength() > 0) {
        if (!processor.process(element)) {
          return;
        }
        else {
          offset = element.getTextRange().getEndOffset();
        }
      }
      else {
        offset++;
      }
    }
  }

  @Override
  public <B extends XLineBreakpoint<?>> List<XBreakpointGroupingRule<B, ?>> getGroupingByFileRuleAsList() {
    return Collections.singletonList(getGroupingByFileRule());
  }

  @Override
  public @Nullable PsiElement findContextElement(@NotNull VirtualFile virtualFile, int offset, @NotNull Project project, boolean checkXml) {
    if (!virtualFile.isValid()) {
      return null;
    }

    Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    PsiFile file = document == null ? null : PsiManager.getInstance(project).findFile(virtualFile);
    if (file == null) {
      return null;
    }

    if (offset < 0) {
      offset = 0;
    }
    if (offset > document.getTextLength()) {
      offset = document.getTextLength();
    }
    int startOffset = offset;

    int lineEndOffset = document.getLineEndOffset(document.getLineNumber(offset));
    PsiElement result = null;
    do {
      PsiElement element = file.findElementAt(offset);
      if (!(element instanceof PsiWhiteSpace) && !(element instanceof PsiComment)) {
        result = element;
        break;
      }

      offset = element.getTextRange().getEndOffset() + 1;
    }
    while (offset < lineEndOffset);

    if (result == null) {
      result = file.findElementAt(startOffset);
    }

    if (checkXml && result != null && StdFileTypes.XML.getLanguage().equals(result.getLanguage())) {
      PsiLanguageInjectionHost parent = PsiTreeUtil.getParentOfType(result, PsiLanguageInjectionHost.class);
      if (parent != null) {
        result = InjectedLanguageUtil.findElementInInjected(parent, offset);
      }
    }
    return result;
  }

  @Override
  public void disableValueLookup(@NotNull Editor editor) {
    ValueLookupManagerController.DISABLE_VALUE_LOOKUP.set(editor, Boolean.TRUE);
  }


  @Override
  public @Nullable Editor openTextEditor(@NotNull OpenFileDescriptor descriptor) {
    return createEditor(descriptor);
  }

  public static Editor createEditor(@NotNull OpenFileDescriptor descriptor) {
    if (descriptor.canNavigate()) {
      FileEditorManager fileEditorManager = FileEditorManager.getInstance(descriptor.getProject());
      boolean isEditorAreaFocused = fileEditorManager.getFocusedEditor() != null;
      return fileEditorManager.openTextEditor(descriptor, isEditorAreaFocused);
    }
    return null;
  }

  /**
   * The returned Navigatable overrides requesting focus based on whether the editor area is focused or not.
   */
  public static @NotNull Navigatable wrapKeepEditorAreaFocusNavigatable(@NotNull Project project, @NotNull Navigatable navigatable) {
    return new Navigatable() {
      @Override
      public void navigate(boolean requestFocus) {
        boolean isEditorAreaFocused = FileEditorManager.getInstance(project).getFocusedEditor() != null;
        navigatable.navigate(requestFocus && isEditorAreaFocused);
      }

      @Override
      public boolean canNavigate() { return navigatable.canNavigate(); }

      @Override
      public boolean canNavigateToSource() { return navigatable.canNavigateToSource(); }
    };
  }

  public static void rebuildAllSessionsViews(@Nullable Project project) {
    if (project == null) return;
    XDebugManagerProxy.getInstance().getSessions(project).stream()
      .filter(XDebugSessionProxy::isSuspended)
      .forEach(XDebugSessionProxy::rebuildViews);
  }

  public static void rebuildTreeAndViews(XDebuggerTree tree) {
    if (tree.isDetached()) {
      tree.rebuildAndRestore(XDebuggerTreeState.saveState(tree));
    }
    rebuildAllSessionsViews(tree.getProject());
  }

  @Override
  public @NotNull XExpression createExpression(@NotNull String text, Language language, String custom, @NotNull EvaluationMode mode) {
    return new XExpressionImpl(text, language, custom, mode);
  }

  public static boolean isEmptyExpression(@Nullable XExpression expression) {
    return expression == null || StringUtil.isEmptyOrSpaces(expression.getExpression());
  }

  @Override
  public void logStack(@NotNull XSuspendContext suspendContext, @NotNull XDebugSession session) {
    XExecutionStack activeExecutionStack = suspendContext.getActiveExecutionStack();
    if (activeExecutionStack != null) {
      activeExecutionStack.computeStackFrames(0, new XStackFrameContainerEx() {
        final List<XStackFrame> myFrames = new ArrayList<>();

        @Override
        public void addStackFrames(@NotNull List<? extends XStackFrame> stackFrames, boolean last) {
          myFrames.addAll(stackFrames);
          if (last) {
            print(null);
          }
        }

        @Override
        public void addStackFrames(@NotNull List<? extends XStackFrame> stackFrames, @Nullable XStackFrame toSelect, boolean last) {
          addStackFrames(stackFrames, last);
        }

        @Override
        public void errorOccurred(@NotNull String errorMessage) {
          print(errorMessage);
        }

        void print(@Nullable String errorMessage) {
          ConsoleView view = session.getConsoleView();
          Project project = session.getProject();
          DebuggerUIUtil.invokeLater(() -> view.print("Stack: ", ConsoleViewContentType.SYSTEM_OUTPUT));
          myFrames.forEach(f -> {
            SimpleColoredText text = new SimpleColoredText();
            ReadAction.run(() -> f.customizeTextPresentation(text));
            XSourcePosition position = f.getSourcePosition();
            Navigatable navigatable = position != null ? position.createNavigatable(project) : null;
            DebuggerUIUtil.invokeLater(() -> {
              view.print("\n\t", ConsoleViewContentType.SYSTEM_OUTPUT);
              view.printHyperlink(text.toString(), p -> {
                if (navigatable != null) {
                  navigatable.navigate(true);
                }
              });
            });
          });
          DebuggerUIUtil.invokeLater(() -> {
            if (errorMessage != null) {
              view.print("\n\t" + errorMessage, ConsoleViewContentType.SYSTEM_OUTPUT);
            }
            view.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT);
          });
        }
      });
    }
  }

  public static Icon getVerifiedIcon(@NotNull XBreakpoint breakpoint) {
    return breakpoint.getSuspendPolicy() == SuspendPolicy.NONE
           ? AllIcons.Debugger.Db_verified_no_suspend_breakpoint
           : AllIcons.Debugger.Db_verified_breakpoint;
  }

  public static Navigatable createNavigatable(Project project, XSourcePosition position) {
    return XDebuggerUtilImplShared.createNavigatable(project, position);
  }

  public static @NotNull OpenFileDescriptor createOpenFileDescriptor(@NotNull Project project, @NotNull XSourcePosition position) {
    return XDebuggerUtilImplShared.createOpenFileDescriptor(project, position);
  }
}
