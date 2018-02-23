/** *****************************************************************************
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Dmitry Kornilov - initial implementation
 ***************************************************************************** */
package org.eclipse.yasson.internal;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.json.bind.JsonbException;
import org.eclipse.yasson.internal.model.*;
import org.eclipse.yasson.internal.model.customization.CreatorCustomization;
import org.eclipse.yasson.internal.properties.MessageKeys;
import org.eclipse.yasson.internal.properties.Messages;

/**
 * Created a class internal model.
 *
 * @author Dmitry Kornilov
 */
class ClassParser {

    public static final String IS_PREFIX = "is";

    public static final String GET_PREFIX = "get";

    public static final String SET_PREFIX = "set";

    private final JsonbContext jsonbContext;

    ClassParser(JsonbContext jsonbContext) {
        this.jsonbContext = jsonbContext;
    }

    /**
     * true if the getter or the setter is public
     */
    private boolean isAnyMethodPublic(Property property) {
        Method getter = property.getGetter();
        Method setter = property.getSetter();
        return getter != null && Modifier.isPublic(getter.getModifiers())
                || setter != null && Modifier.isPublic(setter.getModifiers());
    }

    /**
     * should the given property being completely removed ?
     */
    private boolean shouldRemoveField(Property property) {
        Field f = property.getField();

        if (f != null) {
            // field exists
            int mod = f.getModifiers();

            return Modifier.isStatic(mod) || Modifier.isTransient(mod) // remove static and transient fields in all case
                    || !(isAnyMethodPublic(property) // but keep field with public getter or setter
                    || (Modifier.isPublic(mod) //  or public fields without getter setter
                    && property.getGetter() == null
                    && property.getSetter() == null));
        }

        return false;
    }

    /**
     * Parse class fields and getters setters. Merge to java bean like properties.
     */
    public void parseProperties(ClassModel classModel, JsonbAnnotatedElement<Class<?>> classElement) {

        final Map<String, Property> classProperties = new HashMap<>();
        parseFields(classElement, classProperties);
        parseClassAndInterfaceMethods(classElement, classProperties);

        // remove useless fields (transient, static, private, ...)
        //classProperties.entrySet().removeIf(entry -> shouldRemoveField(entry.getValue()));

        //add sorted properties from parent, if they are not overridden in current class
        final List<PropertyModel> sortedProperties = getSortedParentProperties(classModel, classElement, classProperties);
        //sort and add properties from current class
        sortedProperties.addAll(jsonbContext.getConfigProperties().getPropertyOrdering().orderProperties(classProperties, classModel, jsonbContext));

        checkPropertyNameClash(sortedProperties, classModel.getType());

        //reference property to creator parameter by name to merge configuration in runtime
        JsonbCreator creator = classModel.getClassCustomization().getCreator();
        if (creator != null) {
            sortedProperties.forEach((propertyModel -> {
                for (CreatorModel creatorModel : creator.getParams()) {
                    if (creatorModel.getName().equals(propertyModel.getPropertyName())) {
                        CreatorCustomization customization = (CreatorCustomization) creatorModel.getCustomization();
                        customization.setPropertyModel(propertyModel);
                    }
                }
            }));
        }
        classModel.setProperties(sortedProperties);

    }

    private void parseClassAndInterfaceMethods(JsonbAnnotatedElement<Class<?>> classElement, Map<String, Property> classProperties) {
        Class<?> concreteClass = classElement.getElement();
        parseMethods(concreteClass, classElement, classProperties);
        for (Class<?> ifc : jsonbContext.getAnnotationIntrospector().collectInterfaces(concreteClass)) {
            parseIfaceMethodAnnotations(ifc, classProperties);
        }
    }

    private void parseIfaceMethodAnnotations(Class<?> ifc, Map<String, Property> classProperties) {
        Method[] declaredMethods = AccessController.doPrivileged((PrivilegedAction<Method[]>) ifc::getDeclaredMethods);
        for (Method method : declaredMethods) {
            final String methodName = method.getName();
            if (!isPropertyMethod(method)) {
                continue;
            }
            String propertyName = toPropertyMethod(methodName);
            final Property property = classProperties.get(propertyName);
            if (property == null) {
                //May happen for classes which both extend a class with some method and implement interface with same method.
                continue;
            }
            JsonbAnnotatedElement<Method> methodElement = isGetter(method) ?
                    property.getGetterElement() : property.getSetterElement();
            //Only push iface annotations if not overridden on impl classes
            for (Annotation ann : method.getDeclaredAnnotations()) {
                if (methodElement.getAnnotation(ann.annotationType()) == null) {
                    methodElement.putAnnotation(ann);
                }
            }
        }
    }

    private void parseMethods(Class<?> clazz, JsonbAnnotatedElement<Class<?>> classElement, Map<String, Property> classProperties) {
        Method[] declaredMethods = AccessController.doPrivileged((PrivilegedAction<Method[]>) clazz::getDeclaredMethods);
        for (Method method : declaredMethods) {
            String name = method.getName();
            if (!isPropertyMethod(method)) {
                continue;
            }
            final String propertyName = toPropertyMethod(name);

            Property property = classProperties.computeIfAbsent(propertyName, n -> new Property(n, classElement));

            if (isSetter(method)) {
                property.setSetter(method);
            } else {
                property.setGetter(method);
            }
        }
    }

    private boolean isGetter(Method m) {
        return (m.getName().startsWith(GET_PREFIX) || m.getName().startsWith(IS_PREFIX)) && m.getParameterCount() == 0;
    }

    private boolean isSetter(Method m) {
        return m.getName().startsWith(SET_PREFIX) && m.getParameterCount() == 1;
    }

    private String toPropertyMethod(String name) {
        return Introspector.decapitalize(name.substring(name.startsWith(IS_PREFIX) ? 2 : 3, name.length()));
    }

    private boolean isPropertyMethod(Method m) {
        return isGetter(m) || isSetter(m);
    }

    private void parseFields(JsonbAnnotatedElement<Class<?>> classElement, Map<String, Property> classProperties) {
        Field[] declaredFields = AccessController.doPrivileged(
                (PrivilegedAction<Field[]>) () -> classElement.getElement().getDeclaredFields());
        for (Field field : declaredFields) {
            final String name = field.getName();
            if (field.isSynthetic()) {
                continue;
            }
            final Property property = new Property(name, classElement);
            property.setField(field);
            classProperties.put(name, property);
        }
    }

    private void checkPropertyNameClash(List<PropertyModel> collectedProperties, Class cls) {
        final List<PropertyModel> checkedProperties = new ArrayList<>();
        for (PropertyModel collectedPropertyModel : collectedProperties) {
            for (PropertyModel checkedPropertyModel : checkedProperties) {

                if ((checkedPropertyModel.getReadName().equals(collectedPropertyModel.getReadName())
                        && checkedPropertyModel.isReadable() && collectedPropertyModel.isReadable())
                        || (checkedPropertyModel.getWriteName().equals(collectedPropertyModel.getWriteName()))
                        && checkedPropertyModel.isWritable() && collectedPropertyModel.isWritable()) {
                    throw new JsonbException(Messages.getMessage(MessageKeys.PROPERTY_NAME_CLASH,
                            checkedPropertyModel.getPropertyName(), collectedPropertyModel.getPropertyName(),
                            cls.getName()));
                }
            }
            checkedProperties.add(collectedPropertyModel);
        }
    }

    /**
     * Merges current class properties with parent class properties.
     * If javabean property is declared in more than one inheritance levels,
     * merge field, getters and setters of that property.
     * <p>
     * For example BaseClass contains field foo and getter getFoo. In BaseExtensions there is a setter setFoo.
     * All three will be merged for BaseExtension.
     * <p>
     * Such property is sorted based on where its getter or field is located.
     */
    private List<PropertyModel> getSortedParentProperties(ClassModel classModel, JsonbAnnotatedElement<Class<?>> classElement, Map<String, Property> classProperties) {
        List<PropertyModel> sortedProperties = new ArrayList<>();
        //Pull properties from parent
        if (classModel.getParentClassModel() != null) {
            for (PropertyModel parentProp : classModel.getParentClassModel().getSortedProperties()) {
                final Property current = classProperties.get(parentProp.getPropertyName());
                //don't replace overridden properties
                if (current == null) {
                    sortedProperties.add(parentProp);
                } else {
                    //merge
                    final Property merged = mergeProperty(current, parentProp, classElement);
                    ReflectionPropagation propagation = new ReflectionPropagation(current, jsonbContext);
                    if (propagation.isReadable()) {
                        classProperties.replace(current.getName(), merged);
                    } else {
                        sortedProperties.add(new PropertyModel(classModel, merged, jsonbContext));
                        classProperties.remove(current.getName());
                    }

                }
            }
        }
        return sortedProperties;
    }

    private Property mergeProperty(Property current, PropertyModel parentProp, JsonbAnnotatedElement<Class<?>> classElement) {
        Field field = current.getField() != null
                ? current.getField() : parentProp.getPropagation().getField();
        Method getter = current.getGetter() != null
                ? current.getGetter() : parentProp.getPropagation().getGetter();
        Method setter = current.getSetter() != null
                ? current.getSetter() : parentProp.getPropagation().getSetter();

        Property merged = new Property(parentProp.getPropertyName(), classElement);
        if (field != null) {
            merged.setField(field);
        }
        if (getter != null) {
            merged.setGetter(getter);
        }
        if (setter != null) {
            merged.setSetter(setter);
        }
        return merged;
    }

}
