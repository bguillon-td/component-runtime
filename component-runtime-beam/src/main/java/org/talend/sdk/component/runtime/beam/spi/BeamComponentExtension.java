/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.runtime.beam.spi;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.talend.sdk.component.design.extension.flows.FlowsFactory;
import org.talend.sdk.component.runtime.base.Delegated;
import org.talend.sdk.component.runtime.beam.Versions;
import org.talend.sdk.component.runtime.beam.design.BeamFlowFactory;
import org.talend.sdk.component.runtime.beam.factory.service.AutoValueFluentApiFactory;
import org.talend.sdk.component.runtime.beam.factory.service.PluginCoderFactory;
import org.talend.sdk.component.runtime.beam.impl.BeamMapperImpl;
import org.talend.sdk.component.runtime.beam.impl.BeamProcessorChainImpl;
import org.talend.sdk.component.runtime.beam.transformer.BeamIOTransformer;
import org.talend.sdk.component.runtime.input.Mapper;
import org.talend.sdk.component.runtime.manager.ComponentFamilyMeta;
import org.talend.sdk.component.runtime.output.Processor;
import org.talend.sdk.component.spi.component.ComponentExtension;

public class BeamComponentExtension implements ComponentExtension {

    @Override
    public boolean isActive() {
        try {
            ofNullable(Thread.currentThread().getContextClassLoader())
                    .orElseGet(ClassLoader::getSystemClassLoader)
                    .loadClass("org.apache.beam.sdk.transforms.PTransform");
            return true;
        } catch (final NoClassDefFoundError | ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public <T> T unwrap(final Class<T> type, final Object... args) {
        if ("org.talend.sdk.component.design.extension.flows.FlowsFactory".equals(type.getName()) && args != null
                && args.length == 1 && ComponentFamilyMeta.BaseMeta.class.isInstance(args[0])) {
            if (ComponentFamilyMeta.ProcessorMeta.class.isInstance(args[0])) {
                try {
                    final FlowsFactory factory = FlowsFactory.get(ComponentFamilyMeta.BaseMeta.class.cast(args[0]));
                    factory.getOutputFlows();
                    return type.cast(factory);
                } catch (final Exception e) { // no @ElementListener, let's default for native transforms
                    return type.cast(BeamFlowFactory.OUTPUT); // default
                }
            }
        }
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        return null;
    }

    @Override
    public Collection<ClassFileTransformer> getTransformers() {
        if (Boolean.getBoolean("talend.component.beam.transformers.skip")) {
            return emptySet();
        }
        return singleton(new BeamIOTransformer());
    }

    @Override
    public void onComponent(final ComponentContext context) {
        if (org.apache.beam.sdk.transforms.PTransform.class.isAssignableFrom(context.getType())) {
            context.skipValidation();
        }
    }

    @Override
    public boolean supports(final Class<?> componentType) {
        return componentType == Mapper.class || componentType == Processor.class;
    }

    @Override
    public Map<Class<?>, Object> getExtensionServices(final String plugin) {
        return new HashMap<Class<?>, Object>() {

            {
                put(AutoValueFluentApiFactory.class, new AutoValueFluentApiFactory());
                put(PluginCoderFactory.class, new PluginCoderFactory(plugin));
            }
        };
    }

    @Override
    public <T> T convert(final ComponentInstance instance, final Class<T> component) {
        try {
            if (Mapper.class == component) {
                return (T) new BeamMapperImpl(
                        (org.apache.beam.sdk.transforms.PTransform<org.apache.beam.sdk.values.PBegin, ?>) instance
                                .instance(),
                        instance.plugin(), instance.family(), instance.name());
            }
            if (Processor.class == component) {
                return (T) new BeamProcessorChainImpl(
                        (org.apache.beam.sdk.transforms.PTransform<org.apache.beam.sdk.values.PCollection<?>, org.apache.beam.sdk.values.PDone>) instance
                                .instance(),
                        null, instance.plugin(), instance.family(), instance.name());
            }
        } catch (final RuntimeException re) { // create a passthrough impl to ensure it can be unwrapped
            if (component.isInterface()) {
                final Object actualInstance = instance.instance();
                return (T) Proxy
                        .newProxyInstance(component.getClassLoader(), new Class<?>[] { component, Delegated.class },
                                (proxy, method, args) -> {
                                    if (Object.class == method.getDeclaringClass()) {
                                        return method.invoke(actualInstance, args);
                                    }
                                    if (Delegated.class == method.getDeclaringClass()) {
                                        return actualInstance;
                                    }
                                    if ("plugin".equals(method.getName()) && method.getParameterCount() == 0) {
                                        return instance.plugin();
                                    }
                                    if ("name".equals(method.getName()) && method.getParameterCount() == 0) {
                                        return instance.name();
                                    }
                                    if ("rootName".equals(method.getName()) && method.getParameterCount() == 0) {
                                        return instance.family();
                                    }
                                    throw new UnsupportedOperationException(
                                            "this method is not supported (" + method + ")", re);
                                });
            }
            throw re;
        }
        throw new IllegalArgumentException("unsupported " + component + " by " + getClass());
    }

    @Override
    public Collection<String> getAdditionalDependencies() {
        return singletonList(Versions.GROUP + ":" + Versions.ARTIFACT + ":jar:" + Versions.VERSION);
    }
}
