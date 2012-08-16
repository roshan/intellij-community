package com.jetbrains.python.psi.impl;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.reference.SoftReference;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.indexing.IndexingDataKeys;
import com.jetbrains.python.*;
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache;
import com.jetbrains.python.inspections.PythonVisitorFilter;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import com.jetbrains.python.psi.resolve.VariantsProcessor;
import com.jetbrains.python.psi.stubs.PyFileStub;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class PyFileImpl extends PsiFileBase implements PyFile, PyExpression {
  protected PyType myType;
  private ThreadLocal<List<String>> myFindExportedNameStack = new ArrayListThreadLocal();

  //private volatile Boolean myAbsoluteImportEnabled;
  private final Map<FutureFeature, Boolean> myFutureFeatures;
  private List<String> myDunderAll;
  private boolean myDunderAllCalculated;
  private SoftReference<ExportedNameCache> myExportedNameCache = new SoftReference<ExportedNameCache>(null);
  private final Object myENCLock = new Object();
  private final PsiModificationTracker myModificationTracker;

  private class ExportedNameCache {
    private final Map<String, PsiElement> myLocalDeclarations = new HashMap<String, PsiElement>();
    private final MultiMap<String, PsiElement> myLocalAmbiguousDeclarations = new MultiMap<String, PsiElement>();
    private final Map<String, PsiElement> myExceptPartDeclarations = new HashMap<String, PsiElement>();
    private final MultiMap<String, PsiElement> myExceptPartAmbiguousDeclarations = new MultiMap<String, PsiElement>();
    private final List<PsiElement> myNameDefiners = new ArrayList<PsiElement>();
    private final List<String> myNameDefinerNegativeCache = new ArrayList<String>();
    private long myNameDefinerOOCBModCount = -1;

    private ExportedNameCache() {
      final List<PsiElement> children = PyPsiUtils.collectAllStubChildren(PyFileImpl.this, getStub());
      final List<PyExceptPart> exceptParts = new ArrayList<PyExceptPart>();
      for (PsiElement child : children) {
        if (child instanceof PyExceptPart) {
          exceptParts.add((PyExceptPart) child);
        }
        else {
          addDeclaration(child, myLocalDeclarations, myLocalAmbiguousDeclarations, myNameDefiners);
        }
      }
      if (!exceptParts.isEmpty()) {
        for (PyExceptPart part : exceptParts) {
          final List<PsiElement> exceptChildren = PyPsiUtils.collectAllStubChildren(part, part.getStub());
          for (PsiElement child : exceptChildren) {
            addDeclaration(child, myExceptPartDeclarations, myExceptPartAmbiguousDeclarations, myNameDefiners);
          }
        }
      }
    }

    private void addDeclaration(PsiElement child, 
                                Map<String, PsiElement> localDeclarations,
                                MultiMap<String, PsiElement> ambiguousDeclarations,
                                List<PsiElement> nameDefiners) {
      if (child instanceof PsiNamedElement) {
        final String name = ((PsiNamedElement)child).getName();
        localDeclarations.put(name, child);
      }
      else if (child instanceof PyFromImportStatement) {
        final PyFromImportStatement fromImportStatement = (PyFromImportStatement)child;
        if (fromImportStatement.isStarImport()) {
          nameDefiners.add(fromImportStatement);
        }
        else {
          for (PyImportElement importElement : fromImportStatement.getImportElements()) {
            addImportElementDeclaration(importElement, localDeclarations, ambiguousDeclarations);
          }
        }
        if (PyNames.INIT_DOT_PY.equals(getName())) {
          final PyQualifiedName qName = fromImportStatement.getImportSourceQName();
          if (qName != null && !localDeclarations.containsKey(qName.toString())) {
            localDeclarations.put(qName.getLastComponent(), fromImportStatement);
          }
        }
      }
      else if (child instanceof PyImportStatement) {
        final PyImportStatement importStatement = (PyImportStatement)child;
        for (PyImportElement importElement : importStatement.getImportElements()) {
          addImportElementDeclaration(importElement, localDeclarations, ambiguousDeclarations);
          if (PyNames.INIT_DOT_PY.equals(getName())) {
            final PyQualifiedName qName = importElement.getImportedQName();
            final PsiDirectory containingDirectory = getContainingDirectory();
            if (qName != null && qName.getComponentCount() > 1 && containingDirectory != null) {
              String parentPackage = containingDirectory.getName();
              final List<String> components = qName.getComponents();
              if (components.get(components.size() - 2).equals(parentPackage)) {
                localDeclarations.put(components.get(components.size()-1), importElement);
              }
            }
          }
        }
      }
      else if (child instanceof NameDefiner) {
        nameDefiners.add(child);
      }
    }

    private void addImportElementDeclaration(PyImportElement importElement,
                                             Map<String, PsiElement> localDeclarations,
                                             MultiMap<String, PsiElement> ambiguousDeclarations) {
      final String visibleName = importElement.getVisibleName();
      if (visibleName != null) {
        if (ambiguousDeclarations.containsKey(visibleName)) {
          ambiguousDeclarations.putValue(visibleName, importElement);
        }
        else if (localDeclarations.containsKey(visibleName)) {
          final PsiElement oldElement = localDeclarations.get(visibleName);
          ambiguousDeclarations.putValue(visibleName, oldElement);
          ambiguousDeclarations.putValue(visibleName, importElement);
        }
        else {
          localDeclarations.put(visibleName, importElement);
        }
      }
    }

    @Nullable
    private PsiElement resolve(String name) {
      final PsiElement named = resolveNamed(name, myLocalDeclarations, myLocalAmbiguousDeclarations);
      if (named != null) {
        return named;
      }
      if (!myNameDefiners.isEmpty()) {
        final PsiElement result = findNameInNameDefiners(name);
        if (result != null) {
          return result;
        }
      }
      return resolveNamed(name, myExceptPartDeclarations, myExceptPartAmbiguousDeclarations);
    }

    @Nullable
    private PsiElement resolveNamed(String name,
                                    final Map<String, PsiElement> declarations,
                                    final MultiMap<String, PsiElement> ambiguousDeclarations) {
      if (ambiguousDeclarations.containsKey(name)) {
        final List<PsiElement> localAmbiguous = new ArrayList<PsiElement>(myLocalAmbiguousDeclarations.get(name));
        for (int i = localAmbiguous.size()-1; i >= 0; i--) {
          PsiElement ambiguous = localAmbiguous.get(i);
          final PsiElement result = resolveDeclaration(name, ambiguous);
          if (result != null) {
            return result;
          }
        }
      }
      else {
        final PsiElement result = declarations.get(name);
        if (result != null) {
          return resolveDeclaration(name, result);
        }
      }
      return null;
    }

    @Nullable
    private PsiElement resolveDeclaration(String name, PsiElement result) {
      if (result instanceof PyImportElement) {
        final PyImportElement importElement = (PyImportElement)result;
        return findNameInImportElement(name, importElement, importElement.getContainingImportStatement() instanceof PyFromImportStatement);
      }
      else if (result instanceof PyFromImportStatement) {
        return ((PyFromImportStatement) result).resolveImportSource();
      }
      return result;
    }

    @Nullable
    private PsiElement findNameInNameDefiners(String name) {
      synchronized (myNameDefinerNegativeCache) {
        long oocbModCount = myModificationTracker.getOutOfCodeBlockModificationCount();
        if (oocbModCount != myNameDefinerOOCBModCount) {
          myNameDefinerNegativeCache.clear();
          myNameDefinerOOCBModCount = oocbModCount;
        }
        else {
          if (myNameDefinerNegativeCache.contains(name)) {
            return null;
          }
        }
      }
      for (PsiElement definer : myNameDefiners) {
        final PsiElement result;
        if (definer instanceof PyFromImportStatement) {
          result = findNameInStarImport(name, (PyFromImportStatement)definer);
        }
        else {
          result = ((NameDefiner)definer).getElementNamed(name);
        }
        if (result != null) {
          return result;
        }
      }
      synchronized (myNameDefinerNegativeCache) {
        myNameDefinerNegativeCache.add(name);
      }
      return null;
    }

  }

  public PyFileImpl(FileViewProvider viewProvider) {
    this(viewProvider, PythonLanguage.getInstance());
  }

  public PyFileImpl(FileViewProvider viewProvider, Language language) {
    super(viewProvider, language);
    myFutureFeatures = new HashMap<FutureFeature, Boolean>();
    myModificationTracker = PsiModificationTracker.SERVICE.getInstance(getProject());
  }

  @NotNull
  public FileType getFileType() {
    return PythonFileType.INSTANCE;
  }

  public String toString() {
    return "PyFile:" + getName();
  }

  public PyFunction findTopLevelFunction(String name) {
    return findByName(name, getTopLevelFunctions());
  }

  public PyClass findTopLevelClass(String name) {
    return findByName(name, getTopLevelClasses());
  }

  public PyTargetExpression findTopLevelAttribute(String name) {
    return findByName(name, getTopLevelAttributes());
  }

  @Nullable
  private static <T extends PsiNamedElement> T findByName(String name, List<T> namedElements) {
    for (T namedElement : namedElements) {
      if (name.equals(namedElement.getName())) {
        return namedElement;
      }
    }
    return null;
  }

  public LanguageLevel getLanguageLevel() {
    if (myOriginalFile != null) {
      return ((PyFileImpl) myOriginalFile).getLanguageLevel();
    }
    VirtualFile virtualFile = getVirtualFile();

    if (virtualFile == null) {
      virtualFile = getUserData(IndexingDataKeys.VIRTUAL_FILE);
    }
    if (virtualFile == null) {
      virtualFile = getViewProvider().getVirtualFile();
    }
    return LanguageLevel.forFile(virtualFile);
  }

  public Icon getIcon(int flags) {
    return PythonFileType.INSTANCE.getIcon();
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (isAcceptedFor(visitor.getClass())) {
      if (visitor instanceof PyElementVisitor) {
        ((PyElementVisitor)visitor).visitPyFile(this);
      }
      else {
        super.accept(visitor);
      }
    }
  }

  public boolean isAcceptedFor(@NotNull Class visitorClass) {
    final FileViewProvider viewProvider = getViewProvider();
    final Language lang;
    if (viewProvider instanceof TemplateLanguageFileViewProvider) {
      lang = viewProvider.getBaseLanguage();
    }
    else {
      lang = getLanguage();
    }
    final PythonVisitorFilter filter = PythonVisitorFilter.INSTANCE.forLanguage(lang);
    return filter == null || filter.isSupported(visitorClass, this);
  }

  private final Key<Set<PyFile>> PROCESSED_FILES = Key.create("PyFileImpl.processDeclarations.processedFiles");

  @Override
  public boolean processDeclarations(@NotNull final PsiScopeProcessor processor,
                                     @NotNull ResolveState resolveState,
                                     PsiElement lastParent,
                                     @NotNull PsiElement place) {
    final List<String> dunderAll = getDunderAll();
    final List<String> remainingDunderAll = dunderAll == null ? null : new ArrayList<String>(dunderAll);
    PsiScopeProcessor wrapper = new PsiScopeProcessor() {
      @Override
      public boolean execute(@NotNull PsiElement element, ResolveState state) {
        if (!processor.execute(element, state)) return false;
        if (remainingDunderAll != null && element instanceof PyElement) {
          remainingDunderAll.remove(((PyElement) element).getName());
        }
        return true;
      }

      @Override
      public <T> T getHint(@NotNull Key<T> hintKey) {
        return processor.getHint(hintKey);
      }

      @Override
      public void handleEvent(Event event, @Nullable Object associated) {
        processor.handleEvent(event, associated);
      }
    };

    Set<PyFile> pyFiles = resolveState.get(PROCESSED_FILES);
    if (pyFiles == null) {
      pyFiles = new HashSet<PyFile>();
      resolveState = resolveState.put(PROCESSED_FILES, pyFiles);
    }
    if (pyFiles.contains(this)) return true;
    pyFiles.add(this);
    for(PyClass c: getTopLevelClasses()) {
      if (c == lastParent) continue;
      if (!wrapper.execute(c, resolveState)) return false;
    }
    for(PyFunction f: getTopLevelFunctions()) {
      if (f == lastParent) continue;
      if (!wrapper.execute(f, resolveState)) return false;
    }
    for(PyTargetExpression e: getTopLevelAttributes()) {
      if (e == lastParent) continue;
      if (!wrapper.execute(e, resolveState)) return false;
    }

    for(PyImportElement e: getImportTargets()) {
      if (e == lastParent) continue;
      if (!wrapper.execute(e, resolveState)) return false;
    }

    for(PyFromImportStatement e: getFromImports()) {
      if (e == lastParent) continue;
      if (!e.processDeclarations(wrapper, resolveState, null, this)) return false;
    }

    if (remainingDunderAll != null) {
      for (String s: remainingDunderAll) {
        if (!PyNames.isIdentifier(s)) {
          continue;
        }
        if (!processor.execute(new LightNamedElement(myManager, PythonLanguage.getInstance(), s), resolveState)) return false;
      }
    }
    return true;
  }

  public List<PyStatement> getStatements() {
    List<PyStatement> stmts = new ArrayList<PyStatement>();
    for (PsiElement child : getChildren()) {
      if (child instanceof PyStatement) {
        PyStatement statement = (PyStatement)child;
        stmts.add(statement);
      }
    }
    return stmts;
  }

  public List<PyClass> getTopLevelClasses() {
    return PyPsiUtils.collectStubChildren(this, this.getStub(), PyElementTypes.CLASS_DECLARATION, PyClass.class);
  }

  public List<PyFunction> getTopLevelFunctions() {
    return PyPsiUtils.collectStubChildren(this, this.getStub(), PyElementTypes.FUNCTION_DECLARATION, PyFunction.class);
  }

  public List<PyTargetExpression> getTopLevelAttributes() {
    return PyPsiUtils.collectStubChildren(this, this.getStub(), PyElementTypes.TARGET_EXPRESSION, PyTargetExpression.class);
  }

  @Nullable
  public PsiElement findExportedName(String name) {
    final List<String> stack = myFindExportedNameStack.get();
    if (stack.contains(name)) {
      return null;
    }
    stack.add(name);
    try {
      PsiElement result = getExportedNameCache().resolve(name);
      if (result != null) {
        return result;
      }
      List<String> allNames = getDunderAll();
      if (allNames != null && allNames.contains(name)) {
        return findExportedName(PyNames.ALL);
      }
      return null;
    }
    finally {
      stack.remove(name);
    }
  }

  private ExportedNameCache getExportedNameCache() {
    ExportedNameCache cache;
    synchronized (myENCLock) {
      cache = myExportedNameCache != null ? myExportedNameCache.get() : null;
      if (cache == null) {
        cache = new ExportedNameCache();
        myExportedNameCache = new SoftReference<ExportedNameCache>(cache);
      }
    }
    return cache;
  }

  @Nullable
  private PsiElement findNameInStarImport(String name, PyFromImportStatement statement) {
    if (PyUtil.isClassPrivateName(name)) {
      return null;
    }
    PsiElement starImportSource = statement.resolveImportSource();
    if (starImportSource != null) {
      starImportSource = PyUtil.turnDirIntoInit(starImportSource);
      if (starImportSource instanceof PyFile) {
        final PsiElement result = ((PyFile)starImportSource).getElementNamed(name);
        if (result != null) {
          return result;
        }
      }
    }
    // http://stackoverflow.com/questions/6048786/from-module-import-in-init-py-makes-module-name-visible
    if (PyNames.INIT_DOT_PY.equals(getName())) {
      final PyQualifiedName qName = statement.getImportSourceQName();
      if (qName != null && qName.endsWith(name)) {
        final PsiElement element = PyUtil.turnInitIntoDir(statement.resolveImportSource());
        if (element != null && element.getParent() == getContainingDirectory()) {
          return element;
        }
      }
    }
    return null;
  }

  @Nullable
  private PsiElement findNameInImportElement(String name, PyImportElement importElement, final boolean resolveImportElement) {
    final PsiElement result = importElement.getElementNamed(name, resolveImportElement);
    if (result != null) {
      return result;
    }
    final PyQualifiedName qName = importElement.getImportedQName();
    // http://stackoverflow.com/questions/6048786/from-module-import-in-init-py-makes-module-name-visible
    if (qName != null && qName.getComponentCount() > 1 && name.equals(qName.getLastComponent()) && PyNames.INIT_DOT_PY.equals(getName())) {
      final List<? extends RatedResolveResult> elements = ResolveImportUtil.resolveNameInImportStatement(importElement, qName.removeLastComponent());
      for (RatedResolveResult element: elements) {
        if (PyUtil.turnDirIntoInit(element.getElement()) == this) {
          return importElement;
        }
      }
    }
    return null;
  }

  @Nullable
  public PsiElement getElementNamed(String name) {
    PsiElement exportedName = findExportedName(name);
    if (exportedName instanceof PyImportElement) {
      return ((PyImportElement) exportedName).getElementNamed(name);
    }
    return exportedName;
  }

  @NotNull
  public Iterable<PyElement> iterateNames() {
    final List<PyElement> result = new ArrayList<PyElement>();
    VariantsProcessor processor = new VariantsProcessor(this) {
      @Override
      protected void addElement(String name, PsiElement element) {
        element = PyUtil.turnDirIntoInit(element);
        if (element instanceof PyElement) {
          result.add((PyElement) element);
        }
      }
    };
    processor.setAllowedNames(getDunderAll());
    processDeclarations(processor, ResolveState.initial(), null, this);
    return result;
  }

  public boolean mustResolveOutside() {
    return false;
  }

  @NotNull
  public List<PyImportElement> getImportTargets() {
    List<PyImportElement> ret = new ArrayList<PyImportElement>();
    List<PyImportStatement> imports = PyPsiUtils.collectStubChildren(this, this.getStub(), PyElementTypes.IMPORT_STATEMENT, PyImportStatement.class);
    for (PyImportStatement one: imports) {
      ContainerUtil.addAll(ret, one.getImportElements());
    }
    return ret;
  }

  @NotNull
  public List<PyFromImportStatement> getFromImports() {
    return PyPsiUtils.collectStubChildren(this, getStub(), PyElementTypes.FROM_IMPORT_STATEMENT, PyFromImportStatement.class);
  }

  @Override
  public List<String> getDunderAll() {
    final StubElement stubElement = getStub();
    if (stubElement instanceof PyFileStub) {
      return ((PyFileStub) stubElement).getDunderAll();
    }
    if (!myDunderAllCalculated) {
      final List<String> dunderAll = calculateDunderAll();
      myDunderAll = dunderAll == null ? null : Collections.unmodifiableList(dunderAll);
      myDunderAllCalculated = true;
    }
    return myDunderAll;
  }

  @Nullable
  public List<String> calculateDunderAll() {
    final DunderAllBuilder builder = new DunderAllBuilder();
    accept(builder);
    return builder.result();
  }

  private static class DunderAllBuilder extends PyRecursiveElementVisitor {
    private List<String> myResult = null;
    private boolean myDynamic = false;
    private boolean myFoundDunderAll = false;

    // hashlib builds __all__ by concatenating multiple lists of strings, and we want to understand this
    private Map<String, List<String>> myDunderLike = new HashMap<String, List<String>>();

    @Override
    public void visitPyTargetExpression(PyTargetExpression node) {
      if (PyNames.ALL.equals(node.getName())) {
        myFoundDunderAll = true;
        PyExpression value = node.findAssignedValue();
        if (value instanceof PyBinaryExpression) {
          PyBinaryExpression binaryExpression = (PyBinaryExpression)value;
          if (binaryExpression.isOperator("+")) {
            List<String> lhs = getStringListFromValue(binaryExpression.getLeftExpression());
            List<String> rhs = getStringListFromValue(binaryExpression.getRightExpression());
            if (lhs != null && rhs != null) {
              myResult = new ArrayList<String>(lhs);
              myResult.addAll(rhs);
            }
          }
        }
        else {
          myResult = PyUtil.getStringListFromTargetExpression(node);
        }
      }
      if (!myFoundDunderAll) {
        List<String> names = PyUtil.getStringListFromTargetExpression(node);
        if (names != null) {
          myDunderLike.put(node.getName(), names);
        }
      }
    }

    @Nullable
    private List<String> getStringListFromValue(PyExpression expression) {
      if (expression instanceof PyReferenceExpression && ((PyReferenceExpression)expression).getQualifier() == null) {
        return myDunderLike.get(((PyReferenceExpression)expression).getReferencedName());
      }
      return PyUtil.strListValue(expression);
    }

    @Override
    public void visitPyAugAssignmentStatement(PyAugAssignmentStatement node) {
      if (PyNames.ALL.equals(node.getTarget().getName())) {
        myDynamic = true;
      }
    }

    @Override
    public void visitPyCallExpression(PyCallExpression node) {
      final PyExpression callee = node.getCallee();
      if (callee instanceof PyQualifiedExpression) {
        final PyExpression qualifier = ((PyQualifiedExpression)callee).getQualifier();
        if (qualifier != null && PyNames.ALL.equals(qualifier.getText())) {
          // TODO handle append and extend with constant arguments here
          myDynamic = true;
        }
      }
    }

    @Nullable
    List<String> result() {
      return myDynamic ? null : myResult;
    }
  }

  @Nullable
  public static List<String> getStringListFromTargetExpression(final String name, List<PyTargetExpression> attrs) {
    for (PyTargetExpression attr : attrs) {
      if (name.equals(attr.getName())) {
        return PyUtil.getStringListFromTargetExpression(attr);
      }
    }
    return null;
  }

  public boolean hasImportFromFuture(FutureFeature feature) {
    final StubElement stub = getStub();
    if (stub instanceof PyFileStub) {
      return ((PyFileStub) stub).getFutureFeatures().get(feature.ordinal());
    }
    Boolean enabled = myFutureFeatures.get(feature);
    if (enabled == null) {
      enabled = calculateImportFromFuture(feature);
      myFutureFeatures.put(feature, enabled);
      // NOTE: ^^^ not synchronized. if two threads will try to modify this, both can only be expected to set the same value.
    }
    return enabled;
  }

  @Override
  public String getDeprecationMessage() {
    final StubElement stub = getStub();
    if (stub instanceof PyFileStub) {
      return ((PyFileStub) stub).getDeprecationMessage();
    }
    return extractDeprecationMessage();
  }

  public String extractDeprecationMessage() {
    return PyFunctionImpl.extractDeprecationMessage(getStatements());
  }

  public boolean calculateImportFromFuture(FutureFeature feature) {
    final List<PyFromImportStatement> fromImports = getFromImports();
    for (PyFromImportStatement fromImport : fromImports) {
      if (fromImport.isFromFuture()) {
        final PyImportElement[] pyImportElements = fromImport.getImportElements();
        for (PyImportElement element : pyImportElements) {
          final PyQualifiedName qName = element.getImportedQName();
          if (qName != null && qName.matches(feature.toString())) {
            return true;
          }
        }
      }
    }
    return false;
  }


  public PyType getType(@NotNull TypeEvalContext context) {
    if (myType == null) myType = new PyModuleType(this);
    return myType;
  }

  public PyStringLiteralExpression getDocStringExpression() {
    return PythonDocStringFinder.find(this);
  }

  public void subtreeChanged() {
    super.subtreeChanged();
    ControlFlowCache.clear(this);
    myDunderAllCalculated = false;
    myFutureFeatures.clear(); // probably no need to synchronize
    synchronized (myENCLock) {
      myExportedNameCache.clear();
    }
  }

  private static class ArrayListThreadLocal extends ThreadLocal<List<String>> {
    @Override
    protected List<String> initialValue() {
      return new ArrayList<String>();
    }
  }
}
