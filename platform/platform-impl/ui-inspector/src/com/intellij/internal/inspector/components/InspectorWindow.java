// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.inspector.components;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.actions.BaseNavigateToSourceAction;
import com.intellij.ide.ui.laf.darcula.ui.DarculaSeparatorUI;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.idea.ActionsBundle;
import com.intellij.internal.InternalActionsBundle;
import com.intellij.internal.inspector.PropertyBean;
import com.intellij.internal.inspector.UiInspectorAction;
import com.intellij.internal.inspector.UiInspectorCustomComponentChildProvider;
import com.intellij.internal.inspector.UiInspectorImpl;
import com.intellij.internal.inspector.accessibilityAudit.UiInspectorAccessibilityInspection;
import com.intellij.internal.inspector.themePicker.UiThemeColorPicker;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.configuration.actions.IconWithTextAction;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.DimensionService;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.pom.Navigatable;
import com.intellij.ui.*;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBThinOverlappingScrollBar;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.ui.paint.RectanglePainter;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.util.MethodInvocator;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.Accessible;
import javax.swing.*;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.intellij.openapi.actionSystem.impl.ActionButtonWithText.SHORTCUT_SHOULD_SHOWN;
import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED;
import static javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER;

public final class InspectorWindow extends JDialog implements Disposable {
  private InspectorTable myInspectorTable;
  private final @NotNull java.util.List<Component> myComponents = new ArrayList<>();
  private java.util.List<? extends PropertyBean> myInfo;
  private final @NotNull Component myInitialComponent;
  private final @NotNull java.util.List<HighlightComponent> myHighlightComponents = new ArrayList<>();
  private boolean myIsHighlighted = true;
  private final @NotNull HierarchyTree myHierarchyTree;
  private final @NotNull ComponentsNavBarPanel myNavBarPanel;
  private final @NotNull Wrapper myWrapperPanel;
  private final @Nullable Project myProject;
  private final UiInspectorAction.UiInspector myInspector;
  private final ToggleShowAccessibilityIssuesAction myShowAccessibilityIssuesAction;

  public InspectorWindow(@Nullable Project project,
                         @NotNull Component component,
                         @NotNull UiInspectorAction.UiInspector inspector,
                         @Nullable MouseEvent event) throws HeadlessException {
    super(findOwnerDialog(component));
    myProject = project;
    myInspector = inspector;
    Window ownerWindow = findOwnerDialog(component);
    setModal(ownerWindow instanceof JDialog && ((JDialog)ownerWindow).isModal());
    myComponents.add(component);
    myInitialComponent = component;
    getRootPane().setBorder(JBUI.Borders.empty(5));

    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

    setLayout(new BorderLayout());
    setTitle(component.getClass().getName());
    Dimension size = DimensionService.getInstance().getSize(getDimensionServiceKey(), null);
    Point location = DimensionService.getInstance().getLocation(getDimensionServiceKey(), null);
    if (size != null) setSize(size);
    if (location != null) setLocation(location);

    myWrapperPanel = new Wrapper();
    myInspectorTable = new InspectorTable(component, myProject);
    myWrapperPanel.setContent(myInspectorTable);
    myHierarchyTree = new HierarchyTree(component) {
      @Override
      public void onComponentsChanged(java.util.List<? extends Component> components) {
        switchComponentsInfo(components);
        updateHighlighting();
      }

      @Override
      public void onAccessibleChanged(Accessible a) {
        switchAccessibleInfo(a);
        updateHighlighting();
      }

      @Override
      public void onClickInfoChanged(java.util.List<? extends PropertyBean> info) {
        switchClickInfo(info);
        updateHighlighting();
      }

      @Override
      public void onCustomComponentChanged(UiInspectorCustomComponentChildProvider provider) {
        switchCustomComponentInfo(provider);
        updateHighlighting();
      }
    };
    Splitter splitPane = new JBSplitter(false, "UiInspector.splitter.proportion", 0.5f);
    splitPane.setSecondComponent(myWrapperPanel);
    splitPane.setFirstComponent(new JBScrollPane(myHierarchyTree));
    add(splitPane, BorderLayout.CENTER);

    DefaultActionGroup actions = new DefaultActionGroup();
    actions.addAction(new ToggleHighlightAction());
    actions.addSeparator();
    actions.add(new RefreshAction());
    actions.addSeparator();
    actions.add(new ToggleAccessibleAction());
    actions.addSeparator();
    actions.addAction(new ToggleThemeColorPickerAction());
    actions.addSeparator();
    actions.add(new ShowDataContextAction());
    actions.addSeparator();
    actions.add(new MyNavigateAction());
    actions.addSeparator();
    myShowAccessibilityIssuesAction = new ToggleShowAccessibilityIssuesAction();
    actions.add(myShowAccessibilityIssuesAction);

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.CONTEXT_TOOLBAR, actions, true);
    toolbar.setTargetComponent(getRootPane());

    Consumer<Component> selectionHandler = selectedComponent -> {
      TreePath pathToSelect = TreeUtil.visitVisibleRows(myHierarchyTree, path -> {
        Object node = path.getLastPathComponent();
        Component curComponent = ((HierarchyTree.ComponentNode)node).getComponent();
        return curComponent == selectedComponent ? TreeVisitor.Action.INTERRUPT : TreeVisitor.Action.CONTINUE;
      });
      if (pathToSelect != null) {
        myHierarchyTree.setSelectionPath(pathToSelect);
        myHierarchyTree.scrollPathToVisible(pathToSelect);
      }
    };
    myNavBarPanel = new ComponentsNavBarPanel(component, selectionHandler);
    JBScrollPane navBarScroll = new JBScrollPane(myNavBarPanel, VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_AS_NEEDED);
    navBarScroll.setHorizontalScrollBar(new JBThinOverlappingScrollBar(Adjustable.HORIZONTAL));
    navBarScroll.setOverlappingScrollBar(true);
    navBarScroll.setBorder(BorderFactory.createEmptyBorder());
    navBarScroll.putClientProperty(JBScrollPane.Flip.class, JBScrollPane.Flip.VERTICAL);

    JPanel topPanel = new JPanel();
    topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
    topPanel.add(toolbar.getComponent());
    topPanel.add(new JSeparator(SwingConstants.HORIZONTAL) {
      @Override
      public void updateUI() {
        setUI(new DarculaSeparatorUI() {
          @Override
          protected int getStripeIndent() {
            return 0;
          }

          @Override
          public Dimension getPreferredSize(JComponent c) {
            return JBUI.size(0, 1);
          }
        });
      }
    });

    if (isDoubleBufferingDisabled(component)) {
      String message = "Double buffering is disabled for this window. See 'com.intellij.util.ui.GraphicsUtil.safelyGetGraphics' JavaDoc.";
      if (SystemProperties.getBooleanProperty("idea.debug.mode", false)) {
        message += "<br>To find the cause, you may pass a '-Dswing.logDoubleBufferingDisable=true' option " +
                   "or put a breakpoint into 'javax.swing.JRootPane.disableTrueDoubleBuffering', " +
                   "and restart IDE. Please report an issue in YouTrack.";
      }
      InlineBanner banner = new InlineBanner(message, EditorNotificationPanel.Status.Error);
      topPanel.add(banner);
    }

    topPanel.add(navBarScroll);
    add(topPanel, BorderLayout.NORTH);

    TreeUtil.expandAll(myHierarchyTree);
    myHierarchyTree.selectPath(component, event);

    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        close();
      }
    });

    getRootPane().getActionMap().put("CLOSE", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        close();
      }
    });
    updateHighlighting();
    getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "CLOSE");

    if (myShowAccessibilityIssuesAction.showAccessibilityIssues) {
      myShowAccessibilityIssuesAction.updateTreeWithAccessibilityAuditStatus();
    }
  }

  @Override
  protected JRootPane createRootPane() {
    JRootPane rp = new MyRootPane();
    rp.setOpaque(true);
    return rp;
  }

  public static String getDimensionServiceKey() {
    return "UiInspectorWindow";
  }

  private static Window findOwnerDialog(Component component) {
    DialogWrapper dialogWrapper = DialogWrapper.findInstance(component);
    if (dialogWrapper != null) {
      return dialogWrapper.getPeer().getWindow();
    }
    return null;
  }

  private InspectorTable getCurrentTable() {
    return myInspectorTable;
  }

  private void switchComponentsInfo(@NotNull java.util.List<? extends Component> components) {
    if (components.isEmpty()) return;
    myComponents.clear();
    myComponents.addAll(components);
    myInfo = null;
    Component showingComponent = components.get(0);
    setTitle(showingComponent.getClass().getName());
    Disposer.dispose(myInspectorTable);
    myInspectorTable = new InspectorTable(showingComponent, myProject, getSelectedNodeFailedAccessibilityInspections());
    myWrapperPanel.setContent(myInspectorTable);
    myNavBarPanel.setSelectedComponent(showingComponent);
  }

  private void switchClickInfo(@NotNull List<? extends PropertyBean> clickInfo) {
    myComponents.clear();
    myInfo = clickInfo;
    setTitle("Click Info");
    Disposer.dispose(myInspectorTable);
    myInspectorTable = new InspectorTable(clickInfo, myProject, getSelectedNodeFailedAccessibilityInspections());
    myWrapperPanel.setContent(myInspectorTable);
  }

  private void switchCustomComponentInfo(@NotNull UiInspectorCustomComponentChildProvider provider) {
    Rectangle bounds = provider.getHighlightingBounds();
    if (bounds == null) {
      myInfo = null;
    }
    else {
      myInfo = Collections.singletonList(new PropertyBean(UiInspectorAction.RENDERER_BOUNDS, bounds));
    }

    myComponents.clear();

    setTitle(provider.getTreeName());

    Disposer.dispose(myInspectorTable);
    myInspectorTable = new InspectorTable(provider, myProject);
    myWrapperPanel.setContent(myInspectorTable);
  }

  private void switchAccessibleInfo(@NotNull Accessible accessible) {
    myComponents.clear();
    myInfo = null;
    setTitle(accessible.getClass().getName());
    Disposer.dispose(myInspectorTable);
    myInspectorTable = new InspectorTable(accessible, myProject);
    myWrapperPanel.setContent(myInspectorTable);
  }

  @Override
  public void dispose() {
    DimensionService.getInstance().setSize(getDimensionServiceKey(), getSize(), null);
    DimensionService.getInstance().setLocation(getDimensionServiceKey(), getLocation(), null);
    Disposer.dispose(myInspectorTable);
    super.dispose();
    // remove this object from the Disposer hierarchy manually here because this method could be called from Swing when it e.g., hides the popup and calls Window.dispose()
    Disposer.dispose(this);
    DialogWrapper.cleanupRootPane(rootPane);
    DialogWrapper.cleanupWindowListeners(this);
  }

  public void close() {
    if (myInitialComponent instanceof JComponent) {
      ((JComponent)myInitialComponent).putClientProperty(UiInspectorAction.CLICK_INFO, null);
    }
    myIsHighlighted = false;
    myInfo = null;
    myComponents.clear();
    updateHighlighting();
    setVisible(false);
    Disposer.dispose(this);
  }

  public UiInspectorAction.UiInspector getInspector() {
    return myInspector;
  }

  private void updateHighlighting() {
    for (HighlightComponent component : myHighlightComponents) {
      JComponent glassPane = getGlassPane(component);
      if (glassPane != null) {
        glassPane.remove(component);
        glassPane.revalidate();
        glassPane.repaint();
      }
    }
    myHighlightComponents.clear();

    if (myIsHighlighted) {
      for (Component component : myComponents) {
        ContainerUtil.addIfNotNull(myHighlightComponents, createHighlighter(component, null));
      }
      if (myInfo != null) {
        Rectangle bounds = null;
        for (PropertyBean bean : myInfo) {
          if (UiInspectorAction.RENDERER_BOUNDS.equals(bean.propertyName)) {
            bounds = (Rectangle)bean.propertyValue;
            break;
          }
        }
        ContainerUtil.addIfNotNull(myHighlightComponents, createHighlighter(myInitialComponent, bounds));
      }
    }
  }

  private static @Nullable HighlightComponent createHighlighter(@NotNull Component component, @Nullable Rectangle bounds) {
    JComponent glassPane = getGlassPane(component);
    if (glassPane == null) return null;

    if (bounds != null) {
      bounds = SwingUtilities.convertRectangle(component, bounds, glassPane);
    }
    else {
      Point pt = SwingUtilities.convertPoint(component, new Point(0, 0), glassPane);
      bounds = new Rectangle(pt.x, pt.y, component.getWidth(), component.getHeight());
    }

    JBColor color = new JBColor(JBColor.GREEN, JBColor.RED);
    if (bounds.width == 0 || bounds.height == 0) {
      bounds.width = Math.max(bounds.width, 1);
      bounds.height = Math.max(bounds.height, 1);
      color = JBColor.BLUE;
    }

    Insets insets = component instanceof JComponent ? ((JComponent)component).getInsets() : JBInsets.emptyInsets();
    HighlightComponent highlightComponent = new HighlightComponent(color, insets);
    highlightComponent.setBounds(bounds);

    glassPane.add(highlightComponent);
    glassPane.revalidate();
    glassPane.repaint();

    return highlightComponent;
  }

  private static @Nullable JComponent getGlassPane(@NotNull Component component) {
    JRootPane rootPane = SwingUtilities.getRootPane(component);
    return rootPane == null ? null : (JComponent)rootPane.getGlassPane();
  }

  private static final class HighlightComponent extends JComponent {
    private final @NotNull Color myColor;
    private final @NotNull Insets myInsets;

    private HighlightComponent(@NotNull Color c, @NotNull Insets insets) {
      myColor = c;
      myInsets = insets;
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2d = (Graphics2D)g;

      Color oldColor = g2d.getColor();
      Composite old = g2d.getComposite();
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));

      Rectangle r = getBounds();
      RectanglePainter.paint(g2d, 0, 0, r.width, r.height, 0, myColor, null);

      ((Graphics2D)g).setPaint(myColor.darker());
      for (int i = 0; i < myInsets.left; i++) {
        LinePainter2D.paint(g2d, i, myInsets.top, i, r.height - myInsets.bottom - 1);
      }
      for (int i = 0; i < myInsets.right; i++) {
        LinePainter2D.paint(g2d, r.width - i - 1, myInsets.top, r.width - i - 1, r.height - myInsets.bottom - 1);
      }
      for (int i = 0; i < myInsets.top; i++) {
        LinePainter2D.paint(g2d, 0, i, r.width, i);
      }
      for (int i = 0; i < myInsets.bottom; i++) {
        LinePainter2D.paint(g2d, 0, r.height - i - 1, r.width, r.height - i - 1);
      }

      g2d.setComposite(old);
      g2d.setColor(oldColor);
    }
  }

  private String findSelectedClassName() {
    if (myHierarchyTree.hasFocus()) {
      if (!myComponents.isEmpty()) {
        return myComponents.get(0).getClass().getName();
      }
      else {
        TreePath path = myHierarchyTree.getSelectionPath();
        if (path != null) {
          Object obj = path.getLastPathComponent();
          if (obj instanceof HierarchyTree.ComponentNode) {
            Component comp = ((HierarchyTree.ComponentNode)obj).getComponent();
            if (comp != null) {
              return comp.getClass().getName();
            }
          }
        }
      }
    }
    else if (myInspectorTable.getTable().hasFocus()) {
      int row = myInspectorTable.getTable().getSelectedRow();
      String value = myInspectorTable.getCellTextValue(row, 1);
      // remove hashcode, properties description, take first from enumeration
      String[] parts = value.split("[@,\\[]");
      return parts[0];
    }
    return null;
  }


  private static boolean isDoubleBufferingDisabled(Component component) {
    // we do not want to get the Popup.HeavyWeightWindow
    Component window = ComponentUtil.findParentByCondition(component, it -> {
      return it instanceof JFrame || it instanceof JDialog;
    });
    if (window instanceof RootPaneContainer) {
      JRootPane rootPane = ((RootPaneContainer)window).getRootPane();
      try {
        MethodInvocator invocator = new MethodInvocator(JRootPane.class, "getUseTrueDoubleBuffering");
        if (invocator.isAvailable()) {
          boolean useTrueDoubleBuffering = (boolean)invocator.invoke(rootPane);
          return !useTrueDoubleBuffering;
        }
      }
      catch (Throwable e) {
        Logger.getInstance(InspectorWindow.class).warn(e);
        return false;
      }
    }
    return false;
  }

  private @NotNull List<UiInspectorAccessibilityInspection> getSelectedNodeFailedAccessibilityInspections() {
    List<UiInspectorAccessibilityInspection> failedInspections = Collections.emptyList();
    TreePath path = myHierarchyTree.getSelectionPath();
    if (path != null && path.getLastPathComponent() instanceof HierarchyTree.ComponentNode node) {
      failedInspections = node.getFailedAccessibilityInspections();
    }
    return failedInspections;
  }

  private class MyRootPane extends JRootPane implements UiDataProvider {
    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
      String selectedClassName = findSelectedClassName();
      if (selectedClassName == null) return;
      sink.set(CommonDataKeys.NAVIGATABLE, new Navigatable() {
        @Override
        public void navigate(boolean requestFocus) {
          UiInspectorImpl.openClassByFqn(myProject, selectedClassName, requestFocus);
        }

        @Override
        public boolean canNavigate() {
          return true;
        }

        @Override
        public boolean canNavigateToSource() {
          return true;
        }
      });
    }
  }

  private final class ToggleHighlightAction extends MyTextAction implements Toggleable {
    private ToggleHighlightAction() {
      super(IdeBundle.messagePointer("action.Anonymous.text.highlight"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      myIsHighlighted = !myIsHighlighted;
      updateHighlighting();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(myInfo != null || !myComponents.isEmpty());
      Toggleable.setSelected(e.getPresentation(), myIsHighlighted);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  private final class ToggleShowAccessibilityIssuesAction extends MyTextAction implements Toggleable {
    private final boolean isAccessibilityAuditEnabled = Registry.is("ui.inspector.accessibility.audit", true);
    public static final String SHOW_ACCESSIBILITY_ISSUES_KEY = "ui.inspector.show.accessibility.issues.key";
    private boolean showAccessibilityIssues;

    private ToggleShowAccessibilityIssuesAction() {
      super(InternalActionsBundle.messagePointer("action.Anonymous.text.ShowAccessibilityIssues"));
      showAccessibilityIssues =
        isAccessibilityAuditEnabled && PropertiesComponent.getInstance().getBoolean(SHOW_ACCESSIBILITY_ISSUES_KEY, false);
      getTemplatePresentation().setDescription(
        InternalActionsBundle.messagePointer("action.Anonymous.description.ShowAccessibilityIssues"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      showAccessibilityIssues = !showAccessibilityIssues;
      PropertiesComponent.getInstance().setValue(SHOW_ACCESSIBILITY_ISSUES_KEY, showAccessibilityIssues);
      updateTreeWithAccessibilityAuditStatus();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(isAccessibilityAuditEnabled);
      Toggleable.setSelected(e.getPresentation(), showAccessibilityIssues);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    private void updateTreeWithAccessibilityAuditStatus() {
      TreeUtil.visitVisibleRows(myHierarchyTree, path -> {
        Object node = path.getLastPathComponent();
        if (node instanceof HierarchyTree.ComponentNode componentNode) {
          if (showAccessibilityIssues) {
            componentNode.runAccessibilityAudit();
          } else {
            componentNode.clearAccessibilityAuditResult();
          }
        }

        return TreeVisitor.Action.CONTINUE;
      });

      myHierarchyTree.repaint();
    }
  }

  private final class RefreshAction extends MyTextAction {
    private RefreshAction() {
      super(InternalActionsBundle.messagePointer("action.Anonymous.text.refresh"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      getCurrentTable().refresh();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(!myComponents.isEmpty());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  private final class ToggleAccessibleAction extends MyTextAction implements Toggleable {
    private boolean isAccessibleEnable = false;

    private ToggleAccessibleAction() {
      super(InternalActionsBundle.messagePointer("action.Anonymous.text.Accessible"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      switchHierarchy();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Toggleable.setSelected(e.getPresentation(), isAccessibleEnable);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    private void switchHierarchy() {
      TreePath path = myHierarchyTree.getLeadSelectionPath();
      if (path == null) return;
      HierarchyTree.ComponentNode node = ObjectUtils.tryCast(path.getLastPathComponent(), HierarchyTree.ComponentNode.class);
      if (node == null) return;
      Component c = node.getComponent();
      if (c == null) return;

      Component selected = ContainerUtil.getFirstItem(myComponents);
      isAccessibleEnable = !isAccessibleEnable;
      myNavBarPanel.setAccessibleEnabled(isAccessibleEnable);
      myHierarchyTree.resetModel(c, isAccessibleEnable);
      TreeUtil.expandAll(myHierarchyTree);
      if (selected != null) {
        myHierarchyTree.selectPath(selected, isAccessibleEnable);
      }

      if (myShowAccessibilityIssuesAction.showAccessibilityIssues) {
        myShowAccessibilityIssuesAction.updateTreeWithAccessibilityAuditStatus();
      }
    }
  }

  private final class ToggleThemeColorPickerAction extends MyTextAction implements Toggleable {
    private ToggleThemeColorPickerAction() {
      super(InternalActionsBundle.messagePointer("action.Anonymous.text.colorPicker"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      UiThemeColorPicker.getInstance().setEnabled(!UiThemeColorPicker.getInstance().isEnabled());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Toggleable.setSelected(e.getPresentation(), UiThemeColorPicker.getInstance().isEnabled());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }

  private final class ShowDataContextAction extends MyTextAction {
    private ShowDataContextAction() {
      super(InternalActionsBundle.messagePointer("action.Anonymous.text.DataContext"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      TreePath path = myHierarchyTree.getLeadSelectionPath();
      if (path == null) return;
      HierarchyTree.ComponentNode node = ObjectUtils.tryCast(path.getLastPathComponent(), HierarchyTree.ComponentNode.class);
      if (node == null) return;
      JComponent c = UIUtil.getParentOfType(JComponent.class, node.getComponent());
      if (c == null) return;
      var components = JBIterable.<TreeNode>generate(node, o -> o.getParent())
        .filter(HierarchyTree.ComponentNode.class)
        .filterMap(o -> o.getComponent())
        .toList();

      new DataContextDialog(myProject, components).show();
    }
  }

  private abstract static class MyTextAction extends IconWithTextAction implements DumbAware {
    private MyTextAction(Supplier<String> text) {
      super(text);
    }
  }

  private static final class MyNavigateAction extends BaseNavigateToSourceAction implements CustomComponentAction {
    private MyNavigateAction() {
      super(true);
      Presentation presentation = getTemplatePresentation();
      presentation.setText(ActionsBundle.messagePointer("action.EditSource.text"));
      presentation.setDescription(InternalActionsBundle.messagePointer("action.Anonymous.description.open.definition"));
      presentation.putClientProperty(SHORTCUT_SHOULD_SHOWN, true);
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
      return new ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
        @Override
        protected @Nullable String getShortcutText() {
          Shortcut shortcut = ActionManager.getInstance().getKeyboardShortcut("EditSource");
          if (shortcut != null) {
            return KeymapUtil.getShortcutText(shortcut);
          }
          return null;
        }
      };
    }

    @Override
    public void updateCustomComponent(@NotNull JComponent component, @NotNull Presentation presentation) {
      component.setEnabled(presentation.isEnabled());
    }
  }
}