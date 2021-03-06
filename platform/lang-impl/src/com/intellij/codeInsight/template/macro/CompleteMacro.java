/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.template.macro;

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

public class CompleteMacro extends BaseCompleteMacro {
  public CompleteMacro() {
    super("complete");
  }

  @Override
  protected void invokeCompletionHandler(Project project, Editor editor) {
    new CodeCompletionHandlerBase(CompletionType.BASIC, ApplicationManager.getApplication().isUnitTestMode(), false, true)
      .invokeCompletion(project, editor, 1);
  }
}