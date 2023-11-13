package com.ly.doc.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ly.doc.constants.DocAnnotationConstants;
import com.ly.doc.constants.DocGlobalConstants;
import com.ly.doc.constants.TornaConstants;
import com.ly.doc.model.ApiConfig;
import com.ly.doc.model.DocJavaField;
import com.ly.doc.model.torna.TornaRequestInfo;
import com.power.common.util.CollectionUtil;
import com.power.common.util.StringUtil;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameterizedType;
import com.thoughtworks.qdox.model.JavaType;
import com.thoughtworks.qdox.model.expression.AnnotationValue;
import com.thoughtworks.qdox.model.expression.TypeRef;
import com.thoughtworks.qdox.model.impl.DefaultJavaField;
import com.thoughtworks.qdox.model.impl.DefaultJavaParameterizedType;

public class Utils {

  // TornaUtils
  public static void printDebugInfo(ApiConfig apiConfig, String responseMsg, Map<String, String> requestJson,
      String category) {
    if (apiConfig.isTornaDebug()) {
      String sb = "Configuration information : \n" +
          "OpenUrl: " +
          apiConfig.getOpenUrl() +
          "\n" +
          "appToken: " +
          apiConfig.getAppToken() +
          "\n";
      System.out.println(sb);
      try {
        JsonElement element = JsonParser.parseString(responseMsg);
        TornaRequestInfo info = new TornaRequestInfo()
            .of()
            .setCategory(category)
            .setCode(element.getAsJsonObject().get(TornaConstants.CODE).getAsString())
            .setMessage(element.getAsJsonObject().get(TornaConstants.MESSAGE).getAsString())
            .setRequestInfo(requestJson)
            .setResponseInfo(responseMsg);
        System.out.println(info.buildInfo());
      } catch (Exception e) {
        // Ex : Nginx Error,Tomcat Error
        System.out.println("Response Error : \n" + responseMsg);
      }
    }
  }

  // JavaClassUtils

  /**
   * Get fields
   *
   * @param cls1            The JavaClass object
   * @param counter         Recursive counter
   * @param addedFields     added fields,Field deduplication
   * @param actualJavaTypes collected actualJavaTypes
   * @return list of JavaField
   */
  public static List<DocJavaField> getFields(JavaClass cls1, int counter, Map<String, DocJavaField> addedFields,
      Map<String, JavaType> actualJavaTypes, ClassLoader classLoader) {
    List<DocJavaField> fieldList = new ArrayList<>();
    if (Objects.isNull(cls1)) {
      return fieldList;
    }
    // ignore enum class
    if (cls1.isEnum()) {
      return fieldList;
    }
    // ignore class in jdk
    String className = cls1.getFullyQualifiedName();
    if (JavaClassValidateUtil.isJdkClass(className)) {
      return fieldList;
    }
    if (cls1.isInterface()) {
      List<JavaMethod> methods = cls1.getMethods();
      for (JavaMethod javaMethod : methods) {
        String methodName = javaMethod.getName();
        int paramSize = javaMethod.getParameters().size();
        boolean enable = false;
        if (methodName.startsWith("get") && !"get".equals(methodName) && paramSize == 0) {
          methodName = StringUtil.firstToLowerCase(methodName.substring(3));
          enable = true;
        } else if (methodName.startsWith("is") && !"is".equals(methodName) && paramSize == 0) {
          methodName = StringUtil.firstToLowerCase(methodName.substring(2));
          enable = true;
        }
        if (!enable || addedFields.containsKey(methodName)) {
          continue;
        }
        String comment = javaMethod.getComment();
        if (StringUtil.isEmpty(comment)) {
          comment = DocGlobalConstants.NO_COMMENTS_FOUND;
        }
        JavaField javaField = new DefaultJavaField(javaMethod.getReturns(), methodName);
        DocJavaField docJavaField = DocJavaField.builder()
            .setDeclaringClassName(className)
            .setFieldName(methodName)
            .setJavaField(javaField)
            .setComment(comment)
            .setDocletTags(javaMethod.getTags())
            .setAnnotations(javaMethod.getAnnotations())
            .setFullyQualifiedName(javaField.getType().getFullyQualifiedName())
            .setGenericCanonicalName(getReturnGenericType(javaMethod, classLoader))
            .setGenericFullyQualifiedName(javaField.getType().getGenericFullyQualifiedName());

        addedFields.put(methodName, docJavaField);
      }
    }

    JavaClass parentClass = cls1.getSuperJavaClass();
    if (Objects.nonNull(parentClass)) {
      getFields(parentClass, counter, addedFields, actualJavaTypes, classLoader);
    }

    List<JavaType> implClasses = cls1.getImplements();
    for (JavaType type : implClasses) {
      JavaClass javaClass = (JavaClass) type;
      getFields(javaClass, counter, addedFields, actualJavaTypes, classLoader);
    }

    actualJavaTypes.putAll(JavaClassUtil.getActualTypesMap(cls1));
    List<JavaMethod> javaMethods = cls1.getMethods();
    for (JavaMethod method : javaMethods) {
      String methodName = method.getName();
      if (method.getAnnotations().size() < 1) {
        continue;
      }
      int paramSize = method.getParameters().size();
      if (methodName.startsWith("get") && !"get".equals(methodName) && paramSize == 0) {
        methodName = StringUtil.firstToLowerCase(methodName.substring(3));
      } else if (methodName.startsWith("is") && !"is".equals(methodName) && paramSize == 0) {
        methodName = StringUtil.firstToLowerCase(methodName.substring(2));
      }
      if (addedFields.containsKey(methodName)) {
        String comment = method.getComment();
        if (Objects.isNull(comment)) {
          comment = addedFields.get(methodName).getComment();
        }
        if (StringUtil.isEmpty(comment)) {
          comment = DocGlobalConstants.NO_COMMENTS_FOUND;
        }
        DocJavaField docJavaField = addedFields.get(methodName);
        docJavaField.setAnnotations(method.getAnnotations());
        docJavaField.setComment(comment);
        docJavaField.setFieldName(methodName);
        docJavaField.setDeclaringClassName(className);
        addedFields.put(methodName, docJavaField);
      }
    }
    if (!cls1.isInterface()) {
      for (JavaField javaField : cls1.getFields()) {
        String fieldName = javaField.getName();
        String subTypeName = javaField.getType().getFullyQualifiedName();

        if (javaField.isStatic() || "this$0".equals(fieldName) ||
            JavaClassValidateUtil.isIgnoreFieldTypes(subTypeName)) {
          continue;
        }
        if (fieldName.startsWith("is") && ("boolean".equals(subTypeName))) {
          fieldName = StringUtil.firstToLowerCase(fieldName.substring(2));
        }
        long count = javaField.getAnnotations().stream()
            .filter(annotation -> DocAnnotationConstants.SHORT_JSON_IGNORE.equals(annotation.getType().getSimpleName()))
            .count();
        if (count > 0) {
          if (addedFields.containsKey(fieldName)) {
            addedFields.remove(fieldName);
          }
          continue;
        }

        DocJavaField docJavaField = DocJavaField.builder();
        boolean typeChecked = false;
        JavaType fieldType = javaField.getType();
        String gicName = fieldType.getGenericCanonicalName();

        String actualType = null;
        if (JavaClassValidateUtil.isCollection(subTypeName) &&
            !JavaClassValidateUtil.isCollection(gicName)) {
          String[] gNameArr = DocClassUtil.getSimpleGicName(gicName);
          actualType = JavaClassUtil.getClassSimpleName(gNameArr[0]);
          docJavaField.setArray(true);
          typeChecked = true;
        }
        if (JavaClassValidateUtil.isPrimitive(subTypeName) && !typeChecked) {
          docJavaField.setPrimitive(true);
          typeChecked = true;
        }
        if (JavaClassValidateUtil.isFile(subTypeName) && !typeChecked) {
          docJavaField.setFile(true);
          typeChecked = true;
        }
        if (javaField.getType().isEnum() && !typeChecked) {
          docJavaField.setEnum(true);
        }
        String comment = javaField.getComment();
        if (Objects.isNull(comment)) {
          comment = DocGlobalConstants.NO_COMMENTS_FOUND;
        }
        // Getting the Original Defined Type of Field
        if (!docJavaField.isFile() || !docJavaField.isEnum() || !docJavaField.isPrimitive()
            || "java.lang.Object".equals(gicName)) {
          String genericFieldTypeName = getFieldGenericType(javaField, classLoader);
          if (StringUtil.isNotEmpty(genericFieldTypeName)) {
            gicName = genericFieldTypeName;
          }
        }
        docJavaField.setComment(comment)
            .setJavaField(javaField)
            .setFullyQualifiedName(subTypeName)
            .setGenericCanonicalName(gicName)
            .setGenericFullyQualifiedName(fieldType.getGenericFullyQualifiedName())
            .setActualJavaType(actualType)
            .setAnnotations(javaField.getAnnotations())
            .setFieldName(fieldName)
            .setDeclaringClassName(className);
        if (addedFields.containsKey(fieldName)) {
          addedFields.remove(fieldName);
          addedFields.put(fieldName, docJavaField);
          continue;
        }
        addedFields.put(fieldName, docJavaField);
      }
    }
    List<DocJavaField> parentFieldList = addedFields.values()
        .stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    fieldList.addAll(parentFieldList);

    return fieldList;
  }

  /**
   * get Actual type list
   *
   * @param javaType JavaClass
   * @return JavaClass
   */
  public static List<JavaType> getActualTypes(JavaType javaType) {
    if (Objects.isNull(javaType)) {
      return new ArrayList<>(0);
    }
    String typeName = javaType.getGenericFullyQualifiedName();
    if (typeName.contains("<")) {
      return ((JavaParameterizedType) javaType).getActualTypeArguments();
    }
    return new ArrayList<>(0);
  }

  public static void addGroupClass(List<AnnotationValue> annotationValueList, Set<String> javaClassList) {
    if (CollectionUtil.isEmpty(annotationValueList)) {
      return;
    }
    for (AnnotationValue annotationValue : annotationValueList) {
      TypeRef typeRef = (TypeRef) annotationValue;
      DefaultJavaParameterizedType annotationValueType = (DefaultJavaParameterizedType) typeRef.getType();
      javaClassList.add(annotationValueType.getGenericFullyQualifiedName());
    }
  }

  public static void addGroupClass(List<AnnotationValue> annotationValueList, Set<String> javaClassList,
      JavaProjectBuilder builder) {
    if (CollectionUtil.isEmpty(annotationValueList)) {
      return;
    }
    for (AnnotationValue annotationValue : annotationValueList) {
      TypeRef typeRef = (TypeRef) annotationValue;
      DefaultJavaParameterizedType annotationValueType = (DefaultJavaParameterizedType) typeRef.getType();
      String genericCanonicalName = annotationValueType.getGenericFullyQualifiedName();
      JavaClass classByName = builder.getClassByName(genericCanonicalName);
      recursionGetAllValidInterface(classByName, javaClassList, builder);
      javaClassList.add(genericCanonicalName);
    }
  }

  /**
   * @param javaField
   * @return
   */
  private static String getFieldGenericType(JavaField javaField, ClassLoader classLoader) {
    if (JavaClassValidateUtil.isPrimitive(javaField.getType().getGenericCanonicalName())
        || (javaField.isFinal() && javaField.isPrivate())) {
      return null;
    }
    String name = javaField.getName();
    try {
      Class c;
      if (Objects.nonNull(classLoader)) {
        c = classLoader.loadClass(javaField.getDeclaringClass().getCanonicalName());
      } else {
        c = Class.forName(javaField.getDeclaringClass().getCanonicalName());
      }
      Field f = c.getDeclaredField(name);
      f.setAccessible(true);
      Type t = f.getGenericType();
      return StringUtil.trim(t.getTypeName());
    } catch (NoSuchFieldException | ClassNotFoundException e) {
      return null;
    }
  }

  private static String getReturnGenericType(JavaMethod javaMethod, ClassLoader classLoader) {
    String methodName = javaMethod.getName();
    String canonicalClassName = javaMethod.getDeclaringClass().getCanonicalName();
    try {
      Class<?> c;
      if (Objects.nonNull(classLoader)) {
        c = classLoader.loadClass(canonicalClassName);
      } else {
        c = Class.forName(canonicalClassName);
      }

      Method m = c.getDeclaredMethod(methodName);
      Type t = m.getGenericReturnType();
      return StringUtil.trim(t.getTypeName());
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      return null;
    }
  }

  private static void recursionGetAllValidInterface(JavaClass classByName, Set<String> javaClassSet,
      JavaProjectBuilder builder) {
    List<JavaType> anImplements = classByName.getImplements();
    if (CollectionUtil.isEmpty(anImplements)) {
      return;
    }
    for (JavaType javaType : anImplements) {
      String genericFullyQualifiedName = javaType.getGenericFullyQualifiedName();
      javaClassSet.add(genericFullyQualifiedName);
      if (Objects.equals("javax.validation.groups.Default", genericFullyQualifiedName)
          || Objects.equals("jakarta.validation.groups.Default", genericFullyQualifiedName)) {
        continue;
      }
      JavaClass implementJavaClass = builder.getClassByName(genericFullyQualifiedName);
      recursionGetAllValidInterface(implementJavaClass, javaClassSet, builder);
    }
  }

}
