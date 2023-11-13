package com.ly.doc.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ly.doc.constants.DocAnnotationConstants;
import com.ly.doc.constants.DocGlobalConstants;
import com.ly.doc.constants.TornaConstants;
import com.ly.doc.constants.ValidatorAnnotations;
import com.ly.doc.model.ApiConfig;
import com.ly.doc.model.DocJavaField;
import com.ly.doc.model.torna.TornaRequestInfo;
import com.power.common.util.CollectionUtil;
import com.power.common.util.StringUtil;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameterizedType;
import com.thoughtworks.qdox.model.JavaType;
import com.thoughtworks.qdox.model.expression.AnnotationValue;
import com.thoughtworks.qdox.model.expression.AnnotationValueList;
import com.thoughtworks.qdox.model.expression.TypeRef;
import com.thoughtworks.qdox.model.impl.DefaultJavaField;
import com.thoughtworks.qdox.model.impl.DefaultJavaParameterizedType;

public class Utils {

  // TornaUtil

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

  // JavaClassUtil

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

  public static List<AnnotationValue> getAnnotationValues(List<String> validates, JavaAnnotation javaAnnotation) {
    List<AnnotationValue> annotationValueList = new ArrayList<>();
    String simpleName = javaAnnotation.getType().getValue();
    if (simpleName.equalsIgnoreCase(ValidatorAnnotations.VALIDATED)) {
      if (Objects.nonNull(javaAnnotation.getProperty(DocAnnotationConstants.VALUE_PROP))) {
        AnnotationValue v = javaAnnotation.getProperty(DocAnnotationConstants.VALUE_PROP);
        if (v instanceof AnnotationValueList) {
          annotationValueList = ((AnnotationValueList) v).getValueList();
        }
        if (v instanceof TypeRef) {
          annotationValueList.add(v);
        }
      }
    } else if (validates.contains(simpleName)) {
      if (Objects.nonNull(javaAnnotation.getProperty(DocAnnotationConstants.GROUP_PROP))) {
        AnnotationValue v = javaAnnotation.getProperty(DocAnnotationConstants.GROUP_PROP);
        if (v instanceof AnnotationValueList) {
          annotationValueList = ((AnnotationValueList) v).getValueList();
        }
        if (v instanceof TypeRef) {
          annotationValueList.add(v);
        }
      }
    }
    return annotationValueList;
  }

  public static Map<String, String> getJsonIgnoresProp(JavaAnnotation annotation, String propName) {
    Map<String, String> ignoreFields = new HashMap<>();
    Object ignoresObject = annotation.getNamedParameter(propName);
    if (Objects.isNull(ignoresObject)) {
      return ignoreFields;
    }
    if (ignoresObject instanceof String) {
      String prop = StringUtil.removeQuotes(ignoresObject.toString());
      ignoreFields.put(prop, null);
      return ignoreFields;
    }
    LinkedList<String> ignorePropList = (LinkedList) ignoresObject;
    for (String str : ignorePropList) {
      String prop = StringUtil.removeQuotes(str);
      ignoreFields.put(prop, null);
    }
    return ignoreFields;
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

  // DocClassUtil

  /**
   * get class names by generic class name.<br>
   * "controller.R<T,A>$Data<T,A>" =====> ["T,A", "T,A"]
   * 
   * @param typeName generic class name
   * @return array of string
   */
  public static String[] getGicName(String typeName) {
    StringBuilder builder = new StringBuilder(typeName.length());
    List<String> ginNameList = new ArrayList<>();
    int ltLen = 0;
    for (char c : typeName.toCharArray()) {
      if (c == '<' || c == '>') {
        ltLen += (c == '<') ? 1 : -1;
        // Skip the outermost symbols <
        if (c == '<' && ltLen == 1) {
          continue;
        }
      }
      if (ltLen > 0) {
        builder.append(c);
      } else if (ltLen == 0 && c == '>') {
        ginNameList.add(builder.toString());
        builder.setLength(0);
      }
    }
    return ginNameList.toArray(new String[0]);
  }

  /**
   * Automatic repair of generic split class names
   *
   * @param arr arr of class name
   * @return array of String
   */
  public static String[] classNameFix(String[] arr) {
    List<String> classes = new ArrayList<>();
    List<Integer> indexList = new ArrayList<>();
    int globIndex = 0;
    int length = arr.length;
    for (int i = 0; i < length; i++) {
      if (classes.size() > 0) {
        int index = classes.size() - 1;
        if (!isClassName(classes.get(index))) {
          globIndex = globIndex + 1;
          if (globIndex < length) {
            indexList.add(globIndex);
            String className = classes.get(index) + "," + arr[globIndex];
            classes.set(index, className);
          }
        } else {
          globIndex = globIndex + 1;
          if (globIndex < length) {
            if (isClassName(arr[globIndex])) {
              indexList.add(globIndex);
              classes.add(arr[globIndex]);
            } else {
              if (!indexList.contains(globIndex) && !indexList.contains(globIndex + 1)) {
                indexList.add(globIndex);
                classes.add(arr[globIndex] + "," + arr[globIndex + 1]);
                globIndex = globIndex + 1;
                indexList.add(globIndex);
              }
            }
          }
        }
      } else {
        if (isClassName(arr[i])) {
          indexList.add(i);
          classes.add(arr[i]);
        } else {
          if (!indexList.contains(i) && !indexList.contains(i + 1)) {
            globIndex = i + 1;
            classes.add(arr[i] + "," + arr[globIndex]);
            indexList.add(i);
            indexList.add(i + 1);
          }
        }
      }
    }
    return classes.toArray(new String[0]);
  }

  private static boolean isClassName(String className) {
    className = className.replaceAll("[^<>]", "");
    Stack<Character> stack = new Stack<>();
    for (char c : className.toCharArray()) {
      if (c == '<') {
        stack.push('>');
      } else if (stack.isEmpty() || c != stack.pop()) {
        return false;
      }
    }
    return stack.isEmpty();
  }

}
