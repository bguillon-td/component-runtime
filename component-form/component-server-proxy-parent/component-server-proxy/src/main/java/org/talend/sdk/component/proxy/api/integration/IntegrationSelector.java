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
package org.talend.sdk.component.proxy.api.integration;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Extension;

import org.talend.sdk.component.proxy.api.integration.application.ReferenceService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IntegrationSelector implements Extension {

    private final AtomicReference<Integration> integration = new AtomicReference<>();

    void afterBeanDiscovery(@Observes final AfterBeanDiscovery afterBeanDiscovery) {
        afterBeanDiscovery
                .addBean()
                .id(getClass().getName() + "#integration")
                .scope(ApplicationScoped.class)
                .types(Integration.class, Object.class)
                .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
                .createWith(c -> integration.get());
        afterBeanDiscovery
                .addBean()
                .id(getClass().getName() + "#referenceService")
                .scope(ApplicationScoped.class)
                .types(ReferenceService.class, Object.class)
                .qualifiers(Default.Literal.INSTANCE, Any.Literal.INSTANCE)
                .createWith(c -> integration.get().lookup(ReferenceService.class));
    }

    void afterDeploymentValidation(@Observes final AfterDeploymentValidation afterDeploymentValidation) {
        final Integration integration = findIntegration();
        // no eager validation cause of the injector lookup in guice which is lazy by design
        this.integration.set(new CachedIntegration(integration));
        log.info("Using integration {}", integration.getClass().getSimpleName().replace("Integration", ""));
    }

    private Integration findIntegration() {
        final Iterator<Integration> integrations = ServiceLoader.load(Integration.class).iterator();
        if (integrations.hasNext()) {
            return integrations.next();
        }
        try {
            return new GuiceIntegration();
        } catch (final RuntimeException re) {
            return new CdiIntegration();
        }
    }
}
