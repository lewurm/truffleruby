/*
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.processor;

import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.api.dsl.CachedLanguage;
import org.truffleruby.builtins.CoreMethod;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;

public class CoreModuleChecks {
    static void checks(
            CoreModuleProcessor processor,
            int[] lowerFixnum,
            CoreMethod coreMethod,
            TypeElement klass,
            boolean hasZeroArgument) {
        byte[] lowerArgs = null;

        TypeElement klassIt = klass;
        while (!processor.isNodeBaseType(klassIt)) {
            for (Element el : klassIt.getEnclosedElements()) {
                if (!(el instanceof ExecutableElement)) {
                    continue; // we are interested only in executable elements
                }

                final ExecutableElement specializationMethod = (ExecutableElement) el;

                Specialization specializationAnnotation = specializationMethod.getAnnotation(Specialization.class);
                if (specializationAnnotation == null) {
                    continue; // we are interested only in Specialization methods
                }

                lowerArgs = checkLowerFixnumArguments(processor, specializationMethod, lowerArgs);
                if (coreMethod != null) {
                    checkAmbiguousOptionalArguments(
                            processor,
                            coreMethod,
                            specializationMethod,
                            specializationAnnotation);
                }

            }

            klassIt = processor
                    .getProcessingEnvironment()
                    .getElementUtils()
                    .getTypeElement(klassIt.getSuperclass().toString());
        }

        if (lowerArgs == null) {
            processor.error("could not find specializations (lowerArgs == null)", klass);
            return;
        }

        // Verify against the lowerFixnum annotation
        for (int i = 0; i < lowerArgs.length; i++) {
            boolean shouldLower = lowerArgs[i] == 0b01; // int without long
            if (shouldLower && !contains(lowerFixnum, hasZeroArgument ? i : i + 1)) {
                processor.error("should use lowerFixnum for argument " + (hasZeroArgument ? i : i + 1), klass);
            }
        }
    }

    private static byte[] checkLowerFixnumArguments(
            CoreModuleProcessor processor,
            ExecutableElement specializationMethod,
            byte[] lowerArgs) {
        List<? extends VariableElement> parameters = specializationMethod.getParameters();
        int start = 0;

        if (parameters.size() > 0 && processor.isSameType(parameters.get(0).asType(), processor.virtualFrameType)) {
            start++;
        }

        int end = parameters.size();
        for (int i = end - 1; i >= start; i--) {
            boolean cached = parameters.get(i).getAnnotation(Cached.class) != null ||
                    parameters.get(i).getAnnotation(CachedLibrary.class) != null;
            if (cached) {
                end--;
            } else {
                break;
            }
        }

        if (lowerArgs == null) {
            lowerArgs = new byte[end - start];
        } else {
            assert lowerArgs.length == end - start;
        }

        for (int i = start; i < end; i++) {
            TypeKind argumentType = parameters.get(i).asType().getKind();
            if (argumentType == TypeKind.INT) {
                lowerArgs[i - start] |= 0b01;
            } else if (argumentType == TypeKind.LONG) {
                lowerArgs[i - start] |= 0b10;
            }
        }
        return lowerArgs;
    }

    private static boolean contains(int[] array, int value) {
        for (int n = 0; n < array.length; n++) {
            if (array[n] == value) {
                return true;
            }
        }
        return false;
    }

    private static void checkAmbiguousOptionalArguments(
            CoreModuleProcessor processor,
            CoreMethod coreMethod,
            ExecutableElement specializationMethod,
            Specialization specializationAnnotation) {
        List<? extends VariableElement> parameters = specializationMethod.getParameters();
        int n = parameters.size() - 1;
        // Ignore all the @Cached methods from our consideration.
        while (n >= 0 &&
                (parameters.get(n).getAnnotation(Cached.class) != null ||
                        parameters.get(n).getAnnotation(CachedLibrary.class) != null ||
                        parameters.get(n).getAnnotation(CachedContext.class) != null ||
                        parameters.get(n).getAnnotation(CachedLanguage.class) != null)) {
            n--;
        }

        if (coreMethod.needsBlock()) {
            if (n < 0) {
                processor.error("invalid block method parameter position for", specializationMethod);
                return;
            }
            isParameterBlock(processor, parameters.get(n));
            n--; // Ignore block argument.
        }

        if (coreMethod.rest()) {
            if (n < 0) {
                processor.error("missing rest method parameter", specializationMethod);
                return;
            }

            if (parameters.get(n).asType().getKind() != TypeKind.ARRAY) {
                processor.error("rest method parameter is not array", parameters.get(n));
                return;
            }
            n--; // ignore final Object[] argument
        }

        for (int i = 0; i < coreMethod.optional(); i++, n--) {
            if (n < 0) {
                processor.error("invalid optional parameter count for", specializationMethod);
                continue;
            }
            isParameterUnguarded(processor, specializationAnnotation, parameters.get(n));
        }
    }

    private static void isParameterUnguarded(
            CoreModuleProcessor processor,
            Specialization specializationAnnotation,
            VariableElement parameter) {
        String name = parameter.getSimpleName().toString();

        // A specialization will only be called if the types of the arguments match its declared parameter
        // types. So a specialization with a declared optional parameter of type NotProvided will only be
        // called if that argument is not supplied. Similarly a specialization with a RubyDynamicObject optional
        // parameter will only be called if the value has been supplied.
        //
        // Since Object is the super type of NotProvided any optional parameter declaration of type Object
        // must have additional guards to check whether this specialization should be called, or must make
        // it clear in the parameter name (by using unused or maybe prefix) that it may not have been
        // provided or is not used.

        if (processor.isSameType(
                parameter.asType(),
                processor.objectType) &&
                !name.startsWith("unused") &&
                !name.startsWith("maybe") &&
                !isGuarded(name, specializationAnnotation.guards())) {
            processor.error(
                    "Since Object is the super type of NotProvided any optional parameter declaration of type Object " +
                            "must have additional guards to check whether this specialization should be called, " +
                            "or must make it clear in the parameter name (by using unused or maybe prefix) " +
                            "that it may not have been provided or is not used.",
                    parameter);
        }

    }

    private static boolean isGuarded(String name, String[] guards) {
        for (String guard : guards) {
            if (guard.equals("wasProvided(" + name + ")") ||
                    guard.equals("wasNotProvided(" + name + ")") ||
                    (!guard.startsWith("!") && guard.endsWith(".isRubyString(" + name + ")"))) {
                return true;
            }
        }
        return false;
    }

    private static void isParameterBlock(CoreModuleProcessor processor, VariableElement parameter) {
        final TypeMirror blockType = parameter.asType();

        if (!(processor.isSameType(blockType, processor.nilType) ||
                processor.isSameType(blockType, processor.rubyProcType) ||
                processor.isSameType(blockType, processor.objectType))) {
            processor.error("A block parameter must be of type Nil, RubyProc or Object.", parameter);
        }
    }
}
