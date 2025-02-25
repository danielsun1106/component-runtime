/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.runtime.reflect;

import static lombok.AccessLevel.PRIVATE;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = PRIVATE)
public class Defaults {

    private static final Handler HANDLER;

    static {
        final String version = System.getProperty("java.version", "1.8");
        final Constructor<MethodHandles.Lookup> constructor = findLookupConstructor();
        if (version.startsWith("1.8.") || version.startsWith("8.")) { // j8
            HANDLER = (clazz, method, proxy, args) -> constructor
                    .newInstance(clazz, MethodHandles.Lookup.PRIVATE)
                    .unreflectSpecial(method, clazz)
                    .bindTo(proxy)
                    .invokeWithArguments(args);
        } else { // j > 8 - can need some --add-opens, we will add a module-info later to be clean when dropping j8
            final Method privateLookup = findPrivateLookup();
            final int mode = MethodHandles.Lookup.PRIVATE | (MethodHandles.Lookup.PACKAGE << 1 /* module */);
            HANDLER = (clazz, method, proxy, args) -> MethodHandles.Lookup.class
                    .cast(privateLookup.invoke(null, clazz, constructor.newInstance(clazz, mode)))
                    .unreflectSpecial(method, clazz)
                    .bindTo(proxy)
                    .invokeWithArguments(args);
        }
    }

    public static boolean isDefaultAndShouldHandle(final Method method) {
        return method.isDefault();
    }

    public static Object handleDefault(final Class<?> declaringClass, final Method method, final Object proxy,
            final Object[] args) throws Throwable {
        return HANDLER.handle(declaringClass, method, proxy, args);
    }

    private interface Handler {

        Object handle(Class<?> clazz, Method method, Object proxy, Object[] args) throws Throwable;
    }

    private static Method findPrivateLookup() {
        try {
            return MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Constructor<MethodHandles.Lookup> findLookupConstructor() {
        try {
            final Constructor<MethodHandles.Lookup> constructor =
                    MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
            return constructor;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
