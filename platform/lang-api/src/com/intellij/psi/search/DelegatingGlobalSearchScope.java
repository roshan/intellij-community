package com.intellij.psi.search;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * @author peter
 */
public class DelegatingGlobalSearchScope extends GlobalSearchScope {
  protected final GlobalSearchScope myBaseScope;

  public DelegatingGlobalSearchScope(@NotNull GlobalSearchScope baseScope) {
    super(baseScope.getProject());
    myBaseScope = baseScope;
  }

  @Override
  public boolean contains(VirtualFile file) {
    return myBaseScope.contains(file);
  }

  @Override
  public int compare(VirtualFile file1, VirtualFile file2) {
    return myBaseScope.compare(file1, file2);
  }

  @Override
  public boolean isSearchInModuleContent(@NotNull Module aModule) {
    return myBaseScope.isSearchInModuleContent(aModule);
  }

  @Override
  public boolean isSearchInModuleContent(@NotNull Module aModule, boolean testSources) {
    return myBaseScope.isSearchInModuleContent(aModule, testSources);
  }

  @Override
  public boolean accept(VirtualFile file) {
    return myBaseScope.accept(file);
  }

  @Override
  public boolean isSearchInLibraries() {
    return myBaseScope.isSearchInLibraries();
  }

  @Override
  public boolean isSearchOutsideRootModel() {
    return myBaseScope.isSearchOutsideRootModel();
  }

  @Override
  @NotNull
  public GlobalSearchScope intersectWith(@NotNull GlobalSearchScope scope) {
    return super.intersectWith(scope);
  }

  @Override
  @NotNull
  public SearchScope intersectWith(@NotNull SearchScope scope2) {
    return super.intersectWith(scope2);
  }

  @Override
  public SearchScope intersectWith(LocalSearchScope localScope2) {
    return super.intersectWith(localScope2);
  }

  @Override
  @NotNull
  public GlobalSearchScope union(@NotNull SearchScope scope) {
    return super.union(scope);
  }

  @Override
  @NotNull
  public GlobalSearchScope union(LocalSearchScope scope) {
    return super.union(scope);
  }

  @Override
  public GlobalSearchScope uniteWith(@NotNull GlobalSearchScope scope) {
    return super.uniteWith(scope);
  }

  @Override
  public String getDisplayName() {
    return myBaseScope.getDisplayName();
  }
}
