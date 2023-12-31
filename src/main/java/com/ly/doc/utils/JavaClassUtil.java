/*
 * smart-doc
 *
 * Copyright (C) 2018-2023 smart-doc
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.ly.doc.utils;

import com.ly.doc.constants.*;
import com.ly.doc.model.ApiConfig;
import com.ly.doc.model.ApiDataDictionary;
import com.power.common.model.EnumDictionary;
import com.power.common.util.CollectionUtil;
import com.power.common.util.EnumUtil;
import com.power.common.util.StringUtil;
import com.ly.doc.builder.ProjectDocConfigBuilder;
import com.ly.doc.model.DocJavaField;
import com.ly.doc.model.torna.EnumInfo;
import com.ly.doc.model.torna.Item;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;
import com.thoughtworks.qdox.model.expression.AnnotationValue;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handle JavaClass
 *
 * @author yu 2019/12/21.
 */
public class JavaClassUtil {

    /**
     * Get fields
     *
     * @param cls1        The JavaClass object
     * @param counter     Recursive counter
     * @param addedFields added fields,Field deduplication
     * @return list of JavaField
     */
    public static List<DocJavaField> getFields(JavaClass cls1, int counter, Map<String, DocJavaField> addedFields,
            ClassLoader classLoader) {
        Map<String, JavaType> actualJavaTypes = new HashMap<>(10);
        List<DocJavaField> fields = Utils.getFields(cls1, counter, addedFields, actualJavaTypes, classLoader);

        for (DocJavaField field : fields) {
            String genericCanonicalName = field.getGenericCanonicalName();
            if (Objects.isNull(genericCanonicalName)) {
                continue;
            }
            JavaType actualJavaType = actualJavaTypes.get(genericCanonicalName);
            if (Objects.isNull(actualJavaType)) {
                continue;
            }
            field.setGenericCanonicalName(
                    genericCanonicalName.replace(genericCanonicalName, actualJavaType.getGenericCanonicalName()));
            field.setFullyQualifiedName(field.getFullyQualifiedName().replace(genericCanonicalName,
                    actualJavaType.getFullyQualifiedName()));
            field.setActualJavaType(actualJavaType.getFullyQualifiedName());
        }
        return fields;
    }

    /**
     * get enum value
     *
     * @param javaClass    enum class
     * @param formDataEnum is return method
     * @return Object
     */
    public static Object getEnumValue(JavaClass javaClass, boolean formDataEnum) {
        List<JavaField> javaFields = javaClass.getEnumConstants();
        if (Objects.isNull(javaFields)) {
            throw new RuntimeException(javaClass.getName() + " enum not existed");
        }
        List<JavaMethod> methodList = javaClass.getMethods();
        String methodName = null;
        for (JavaMethod method : methodList) {
            List<JavaAnnotation> annotations = method.getAnnotations();
            for (JavaAnnotation annotation : annotations) {
                String annotationName = annotation.getType().getValue();
                // enum serialize while use JsonValue and JsonCreator annotation
                if (DocAnnotationConstants.JSON_VALUE.equals(annotationName)
                        || DocAnnotationConstants.JSON_CREATOR.equals(annotationName)) {
                    methodName = method.getName();
                    break;
                }
            }
        }
        Object value = null;
        int index = 0;
        for (JavaField javaField : javaFields) {
            String simpleName = javaField.getType().getSimpleName();
            StringBuilder valueBuilder = new StringBuilder();
            valueBuilder.append("\"").append(javaField.getName()).append("\"").toString();
            if (formDataEnum) {
                value = valueBuilder.toString();
                return value;
            }
            if (!JavaClassValidateUtil.isPrimitive(simpleName) && index < 1) {
                if (CollectionUtil.isNotEmpty(javaField.getEnumConstantArguments()) && Objects.nonNull(methodName)) {
                    // enum serialize while use JsonValue
                    value = javaField.getEnumConstantArguments().get(0);
                } else {
                    value = valueBuilder.toString();
                }
            }
            index++;
        }
        return value;
    }

    public static String getEnumParams(JavaClass javaClass) {
        List<JavaField> javaFields = javaClass.getEnumConstants();
        StringBuilder stringBuilder = new StringBuilder();
        for (JavaField javaField : javaFields) {
            // string comment
            String exception = javaField.getInitializationExpression();
            stringBuilder.append(javaField.getName());
            stringBuilder.append("(").append(exception).append(")").append("<br/>");
        }
        return stringBuilder.toString();
    }

    public static List<String> getEnumValues(JavaClass javaClass) {
        List<JavaField> javaFields = javaClass.getEnumConstants();
        List<String> enums = new ArrayList<>();
        for (JavaField javaField : javaFields) {
            enums.add(javaField.getName());
        }
        return enums;
    }

    /**
     * get enum info by java class
     *
     * @param javaClass the java class info
     * @param builder   builder
     * @return EnumInfo
     * @author chen qi
     * @since 1.0.0
     */
    public static EnumInfo getEnumInfo(JavaClass javaClass, ProjectDocConfigBuilder builder) {
        if (Objects.isNull(javaClass) || !javaClass.isEnum()) {
            return null;
        }
        if (Objects.nonNull(javaClass.getTagByName(DocTags.IGNORE))) {
            return null;
        }
        // todo support the field described by @see

        ApiConfig apiConfig = builder.getApiConfig();
        ClassLoader classLoader = apiConfig.getClassLoader();
        ApiDataDictionary dataDictionary = apiConfig.getDataDictionary(javaClass.getFullyQualifiedName());

        EnumInfo enumInfo = new EnumInfo();
        String comment = javaClass.getComment();
        DocletTag apiNoteTag = javaClass.getTagByName(DocTags.API_NOTE);
        enumInfo.setName(comment);
        enumInfo.setDescription(DocUtil.getEscapeAndCleanComment(
                Optional.ofNullable(apiNoteTag).map(DocletTag::getValue).orElse(StringUtil.EMPTY)));
        List<JavaField> enumConstants = javaClass.getEnumConstants();

        // value can use invoke method to get value, desc too
        if (Objects.nonNull(dataDictionary)) {
            Class<?> enumClass = dataDictionary.getEnumClass();
            if (enumClass.isInterface()) {
                try {
                    enumClass = classLoader.loadClass(javaClass.getFullyQualifiedName());
                } catch (ClassNotFoundException e) {
                    return enumInfo;
                }
            }
            List<EnumDictionary> enumInformation = EnumUtil.getEnumInformation(enumClass, dataDictionary.getCodeField(),
                    dataDictionary.getDescField());
            List<Item> itemList = enumInformation.stream()
                    .map(i -> new Item(i.getName(), i.getType(), i.getValue(), i.getDesc()))
                    .collect(Collectors.toList());
            enumInfo.setItems(itemList);
            if (StringUtils.isNotEmpty(dataDictionary.getTitle())) {
                enumInfo.setName(dataDictionary.getTitle());
            }
            return enumInfo;
        }

        List<Item> collect = enumConstants.stream().map(cons -> {
            Item item = new Item();
            String name = cons.getName();
            String enumComment = cons.getComment();
            item.setName(name);
            item.setType("string");
            item.setValue(name);
            item.setDescription(enumComment);
            return item;
        }).collect(Collectors.toList());
        enumInfo.setItems(collect);
        return enumInfo;
    }

    /**
     * Get annotation simpleName
     *
     * @param annotationName annotationName
     * @return String
     */
    public static String getAnnotationSimpleName(String annotationName) {
        return getClassSimpleName(annotationName);
    }

    /**
     * Get className
     *
     * @param className className
     * @return String
     */
    public static String getClassSimpleName(String className) {
        if (className.contains(".")) {
            if (className.contains("<")) {
                className = className.substring(0, className.indexOf("<"));
            }
            int index = className.lastIndexOf(".");
            className = className.substring(index + 1);
        }
        if (className.contains("[")) {
            int index = className.indexOf("[");
            className = className.substring(0, index);
        }
        return className;
    }

    /**
     * get Actual type
     *
     * @param javaClass JavaClass
     * @return JavaClass
     */
    public static JavaType getActualType(JavaClass javaClass) {
        return Utils.getActualTypes(javaClass).get(0);
    }

    /**
     * get Actual type map
     *
     * @param javaClass JavaClass
     * @return Map
     */
    public static Map<String, JavaType> getActualTypesMap(JavaClass javaClass) {
        Map<String, JavaType> genericMap = new HashMap<>(10);
        List<JavaTypeVariable<JavaGenericDeclaration>> variables = javaClass.getTypeParameters();
        if (variables.size() < 1) {
            return genericMap;
        }
        List<JavaType> javaTypes = Utils.getActualTypes(javaClass);
        for (int i = 0; i < variables.size(); i++) {
            if (javaTypes.size() > 0) {
                genericMap.put(variables.get(i).getName(), javaTypes.get(i));
            }
        }
        return genericMap;
    }

    /**
     * Obtain Validate Group classes
     *
     * @param annotations the annotations of controller method param
     * @param builder     builder
     * @return the group annotation value
     */
    public static Set<String> getParamGroupJavaClass(List<JavaAnnotation> annotations, JavaProjectBuilder builder) {
        if (CollectionUtil.isEmpty(annotations)) {
            return new HashSet<>(0);
        }
        Set<String> javaClassList = new HashSet<>();
        List<String> validates = DocValidatorAnnotationEnum.listValidatorAnnotations();
        for (JavaAnnotation javaAnnotation : annotations) {
            List<AnnotationValue> annotationValueList = Utils.getAnnotationValues(validates, javaAnnotation);
            Utils.addGroupClass(annotationValueList, javaClassList, builder);
        }
        return javaClassList;
    }

    /**
     * Obtain Validate Group classes
     *
     * @param javaAnnotation the annotation of controller method param
     * @return the group annotation value
     */
    public static Set<String> getParamGroupJavaClass(JavaAnnotation javaAnnotation) {
        if (Objects.isNull(javaAnnotation)) {
            return new HashSet<>(0);
        }
        Set<String> javaClassList = new HashSet<>();
        List<String> validates = DocValidatorAnnotationEnum.listValidatorAnnotations();
        List<AnnotationValue> annotationValueList = Utils.getAnnotationValues(validates, javaAnnotation);
        Utils.addGroupClass(annotationValueList, javaClassList);
        String simpleAnnotationName = javaAnnotation.getType().getValue();
        // add default group
        if (javaClassList.size() == 0 && JavaClassValidateUtil.isJSR303Required(simpleAnnotationName)) {
            javaClassList.add("javax.validation.groups.Default");
        }
        return javaClassList;
    }

    public static String getClassTagsValue(final JavaClass cls, final String tagName, boolean checkComments) {
        if (StringUtil.isNotEmpty(tagName)) {
            StringBuilder result = new StringBuilder();
            List<DocletTag> tags = cls.getTags();
            for (int i = 0; i < tags.size(); i++) {
                if (!tagName.equals(tags.get(i).getName())) {
                    continue;
                }
                String value = tags.get(i).getValue();
                if (StringUtil.isEmpty(value) && checkComments) {
                    throw new RuntimeException("ERROR: #" + cls.getName()
                            + "() - bad @" + tagName + " javadoc from " + cls.getName()
                            + ", must be add comment if you use it.");
                }
                if (tagName.equals(tags.get(i).getName())) {
                    if (result.length() > 0) {
                        result.append(",");
                    }
                    result.append(value);
                }
            }
            return result.toString();
        }
        return "";
    }

    /**
     * Get Map of final field and value
     *
     * @param clazz Java class
     * @return Map
     * @throws IllegalAccessException IllegalAccessException
     */
    public static Map<String, String> getFinalFieldValue(Class<?> clazz) throws IllegalAccessException {
        String className = getClassSimpleName(clazz.getName());
        Field[] fields = clazz.getDeclaredFields();
        Map<String, String> constants = new HashMap<>();
        for (Field field : fields) {
            if (Modifier.isPrivate(field.getModifiers())) {
                continue;
            }
            if (Modifier.isFinal(field.getModifiers()) && Modifier.isStatic(field.getModifiers())) {
                String name = field.getName();
                constants.put(className + "." + name, String.valueOf(field.get(null)));
            }
        }
        return constants;
    }

    public static void genericParamMap(Map<String, String> genericMap, JavaClass cls, String[] globGicName) {
        if (Objects.isNull(cls) || Objects.isNull(cls.getTypeParameters())) {
            return;
        }
        List<JavaTypeVariable<JavaGenericDeclaration>> variables = cls.getTypeParameters();
        if (variables.size() > 0) {
            for (int i = 0; i < cls.getTypeParameters().size() && i < globGicName.length; i++) {
                genericMap.put(variables.get(i).getName(), globGicName[i]);
            }
            return;
        }
        try {
            Class c = Class.forName(cls.getCanonicalName());
            TypeVariable[] tValue = c.getTypeParameters();
            for (int i = 0; i < tValue.length && i < globGicName.length; i++) {
                genericMap.put(tValue[i].getName(), globGicName[i]);
            }
        } catch (ClassNotFoundException e) {
            // skip
        }
    }

    public static String javaTypeFormat(String returnType) {
        if (returnType.contains("?")) {
            return returnType.replaceAll("[?\\s]", "").replaceAll("extends", "");
        }
        return returnType;
    }

    public static boolean isTargetChildClass(String sourceClass, String targetClass) {
        try {
            if (sourceClass.equals(targetClass)) {
                return true;
            }
            Class c = Class.forName(sourceClass);
            while (c != null) {
                if (c.getName().equals(targetClass)) {
                    return true;
                }
                c = c.getSuperclass();
            }
        } catch (ClassNotFoundException e) {
            e.getMessage();
            return false;
        }
        return false;
    }

    public static Map<String, String> getClassJsonIgnoreFields(JavaClass cls) {
        if (Objects.isNull(cls)) {
            return Collections.EMPTY_MAP;
        }
        List<JavaAnnotation> classAnnotation = cls.getAnnotations();
        Map<String, String> ignoreFields = new HashMap<>();
        for (JavaAnnotation annotation : classAnnotation) {
            String simpleAnnotationName = annotation.getType().getValue();
            if (DocAnnotationConstants.SHORT_JSON_IGNORE_PROPERTIES.equalsIgnoreCase(simpleAnnotationName)) {
                return Utils.getJsonIgnoresProp(annotation, DocAnnotationConstants.VALUE_PROP);
            }
            if (DocAnnotationConstants.SHORT_JSON_TYPE.equals(simpleAnnotationName)) {
                return Utils.getJsonIgnoresProp(annotation, DocAnnotationConstants.IGNORE_PROP);
            }
        }
        return ignoreFields;
    }
}
