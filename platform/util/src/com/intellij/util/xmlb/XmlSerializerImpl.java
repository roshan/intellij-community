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

import com.intellij.openapi.util.JDOMExternalizableStringList;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.xmlb.annotations.CollectionBean;
import org.jdom.*;
import org.jdom.filter.Filter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mike
 */
class XmlSerializerImpl {
  private static final Filter<Content> CONTENT_FILTER = new Filter<Content>() {
    @Override
    public boolean matches(Object object) {
      return !isIgnoredNode(object);
    }
  };

  private static SoftReference<Map<Pair<Type, Accessor>, Binding>> ourBindings;

  @NotNull
  static List<Content> getFilteredContent(@NotNull Element element) {
    List<Content> content = element.getContent();
    if (content.isEmpty()) {
      return content;
    }
    else if (content.size() == 1) {
      return isIgnoredNode(content.get(0)) ? Collections.<Content>emptyList() : content;
    }
    else {
      return element.getContent(CONTENT_FILTER);
    }
  }

  @NotNull
  static Element serialize(@NotNull Object object, @NotNull SerializationFilter filter) throws XmlSerializationException {
    try {
      Class<?> aClass = object.getClass();
      Binding binding = getClassBinding(aClass, aClass, null);
      if (binding instanceof BeanBinding) {
        // top level expects not null (null indicates error, empty element will be omitted)
        return ((BeanBinding)binding).serialize(object, true, filter);
      }
      else {
        //noinspection ConstantConditions
        return (Element)binding.serialize(object, null, filter);
      }
    }
    catch (XmlSerializationException e) {
      throw e;
    }
    catch (Exception e) {
      throw new XmlSerializationException("Can't serialize instance of " + object.getClass(), e);
    }
  }

  @Nullable
  static Element serializeIfNotDefault(@NotNull Object object, @NotNull SerializationFilter filter) {
    Class<?> aClass = object.getClass();
    Binding binding = getClassBinding(aClass, aClass, null);
    assert binding != null;
    return (Element)binding.serialize(object, null, filter);
  }

  @Nullable
  static Binding getBinding(@NotNull Type type) {
    return getClassBinding(typeToClass(type), type, null);
  }

  @Nullable
  static Binding getBinding(@NotNull Accessor accessor) {
    Type type = accessor.getGenericType();
    return getClassBinding(typeToClass(type), type, accessor);
  }

  @NotNull
  static Class<?> typeToClass(@NotNull Type type) {
    if (type instanceof Class) {
      return (Class<?>)type;
    }
    else if (type instanceof TypeVariable) {
      Type bound = ((TypeVariable)type).getBounds()[0];
      return bound instanceof Class ? (Class)bound : (Class<?>)((ParameterizedType)bound).getRawType();
    }
    else {
      return (Class<?>)((ParameterizedType)type).getRawType();
    }
  }

  @Nullable
  static synchronized Binding getClassBinding(@NotNull Class<?> aClass, @NotNull Type originalType, @Nullable Accessor accessor) {
    if (aClass.isPrimitive() ||
        aClass == String.class ||
        aClass == Integer.class ||
        aClass == Long.class ||
        aClass == Boolean.class ||
        aClass == Double.class ||
        aClass == Float.class ||
        aClass.isEnum() ||
        Date.class.isAssignableFrom(aClass)) {
      return null;
    }

    Pair<Type, Accessor> key = Pair.create(originalType, accessor);
    Map<Pair<Type, Accessor>, Binding> map = getBindingCacheMap();
    Binding binding = map.get(key);
    if (binding == null) {
      binding = getNonCachedClassBinding(aClass, accessor, originalType);
      map.put(key, binding);
      try {
        binding.init(originalType);
      }
      catch (XmlSerializationException e) {
        map.remove(key);
        throw e;
      }
    }
    return binding;
  }

  @NotNull
  private static Map<Pair<Type, Accessor>, Binding> getBindingCacheMap() {
    Map<Pair<Type, Accessor>, Binding> map = com.intellij.reference.SoftReference.dereference(ourBindings);
    if (map == null) {
      map = new ConcurrentHashMap<Pair<Type, Accessor>, Binding>();
      ourBindings = new SoftReference<Map<Pair<Type, Accessor>, Binding>>(map);
    }
    return map;
  }

  @NotNull
  private static Binding getNonCachedClassBinding(@NotNull Class<?> aClass, @Nullable Accessor accessor, @NotNull Type originalType) {
    if (aClass.isArray()) {
      if (Element.class.isAssignableFrom(aClass.getComponentType())) {
        assert accessor != null;
        return new JDOMElementBinding(accessor);
      }
      else {
        return new ArrayBinding(aClass, accessor);
      }
    }
    if (Collection.class.isAssignableFrom(aClass) && originalType instanceof ParameterizedType) {
      if (accessor != null) {
        CollectionBean listBean = accessor.getAnnotation(CollectionBean.class);
        if (listBean != null) {
          return new CompactCollectionBinding(accessor);
        }
      }
      return new CollectionBinding((ParameterizedType)originalType, accessor);
    }
    if (accessor != null) {
      if (Map.class.isAssignableFrom(aClass) && originalType instanceof ParameterizedType) {
        return new MapBinding(accessor);
      }
      if (Element.class.isAssignableFrom(aClass)) {
        return new JDOMElementBinding(accessor);
      }
      //noinspection deprecation
      if (JDOMExternalizableStringList.class == aClass) {
        return new CompactCollectionBinding(accessor);
      }
    }
    return new BeanBinding(aClass, accessor);
  }

  @Nullable
  static Object convert(@Nullable String value, @NotNull Class<?> valueClass) {
    if (value == null) {
      return null;
    }
    else if (valueClass == String.class) {
      return value;
    }
    else if (valueClass == int.class || valueClass == Integer.class) {
      return Integer.parseInt(value);
    }
    else if (valueClass == boolean.class || valueClass == Boolean.class) {
      return Boolean.parseBoolean(value);
    }
    else if (valueClass == double.class || valueClass == Double.class) {
      return Double.parseDouble(value);
    }
    else if (valueClass == float.class || valueClass == Float.class) {
      return Float.parseFloat(value);
    }
    else if (valueClass == long.class || valueClass == Long.class) {
      return Long.parseLong(value);
    }
    else if (valueClass.isEnum()) {
      for (Object enumConstant : valueClass.getEnumConstants()) {
        if (enumConstant.toString().equals(value)) {
          return enumConstant;
        }
      }
      return null;
    }
    else if (Date.class.isAssignableFrom(valueClass)) {
      try {
        return new Date(Long.parseLong(value));
      }
      catch (NumberFormatException e) {
        return new Date(0);
      }
    }
    else {
      return value;
    }
  }

  public static boolean isIgnoredNode(final Object child) {
    if (child instanceof Text && StringUtil.isEmptyOrSpaces(((Text)child).getValue())) {
      return true;
    }
    if (child instanceof Comment) {
      return true;
    }
    if (child instanceof Attribute) {
      if (!StringUtil.isEmpty(((Attribute)child).getNamespaceURI())) {
        return true;
      }
    }
    return false;
  }

  static void doSet(@NotNull Object host, @Nullable String value, @NotNull Accessor accessor, @NotNull Class<?> valueClass) {
    if (value == null) {
      accessor.set(host, null);
    }
    else if (valueClass == String.class) {
      accessor.set(host, value);
    }
    else if (valueClass == int.class) {
      accessor.setInt(host, Integer.parseInt(value));
    }
    else if (valueClass == boolean.class) {
      accessor.setBoolean(host, Boolean.parseBoolean(value));
    }
    else if (valueClass == double.class) {
      accessor.setDouble(host, Double.parseDouble(value));
    }
    else if (valueClass == float.class) {
      accessor.setFloat(host, Float.parseFloat(value));
    }
    else if (valueClass == long.class) {
      accessor.setLong(host, Long.parseLong(value));
    }
    else if (valueClass == short.class) {
      accessor.setShort(host, Short.parseShort(value));
    }
    else if (valueClass.isEnum()) {
      Object deserializedValue = null;
      for (Object enumConstant : valueClass.getEnumConstants()) {
        if (enumConstant.toString().equals(value)) {
          deserializedValue = enumConstant;
        }
      }
      accessor.set(host, deserializedValue);
    }
    else if (Date.class.isAssignableFrom(valueClass)) {
      try {
        accessor.set(host, new Date(Long.parseLong(value)));
      }
      catch (NumberFormatException e) {
        accessor.set(host, new Date(0));
      }
    }
    else {
      Object deserializedValue = value;
      if (valueClass == Boolean.class) {
        deserializedValue = Boolean.parseBoolean(value);
      }
      else if (valueClass == Integer.class) {
        deserializedValue = Integer.parseInt(value);
      }
      else if (valueClass == Short.class) {
        deserializedValue = Short.parseShort(value);
      }
      else if (valueClass == Long.class) {
        deserializedValue = Long.parseLong(value);
      }
      else if (valueClass == Double.class) {
        deserializedValue = Double.parseDouble(value);
      }
      else if (valueClass == Float.class) {
        deserializedValue = Float.parseFloat(value);
      }
      accessor.set(host, deserializedValue);
    }
  }
}
