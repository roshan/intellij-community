/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.util.xmlb;

import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class AttributeBinding extends BasePrimitiveBinding {
  private final Class<?> valueClass;

  public AttributeBinding(@NotNull Accessor accessor, @NotNull Attribute attribute) {
    super(accessor, attribute.value(), attribute.converter());

    valueClass = XmlSerializerImpl.typeToClass(accessor.getGenericType());
  }

  @Override
  @Nullable
  public Object serialize(@NotNull Object o, @Nullable Object context, @NotNull SerializationFilter filter) {
    Object value = myAccessor.read(o);
    if (value == null) {
      return null;
    }

    String stringValue;
    if (myConverter == null) {
      stringValue = TextBinding.convertToString(value);
    }
    else {
      stringValue = myConverter.toString(value);
    }
    return new org.jdom.Attribute(myName, stringValue);
  }

  @Override
  @Nullable
  public Object deserialize(Object context, @NotNull Object node) {
    String value = ((org.jdom.Attribute)node).getValue();
    if (myConverter == null) {
      XmlSerializerImpl.doSet(context, value, myAccessor, valueClass);
    }
    else {
      myAccessor.set(context, myConverter.fromString(value));
    }
    return context;
  }

  @Override
  public boolean isBoundTo(Object node) {
    return node instanceof org.jdom.Attribute && ((org.jdom.Attribute)node).getName().equals(myName);
  }

  @NotNull
  @Override
  public Class getBoundNodeType() {
    return org.jdom.Attribute.class;
  }

  public String toString() {
    return "AttributeBinding[" + myName + "]";
  }
}
