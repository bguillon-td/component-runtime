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
package org.talend.sdk.component.proxy.test.component;

import java.util.Map;

import lombok.Data;
import org.talend.sdk.component.api.component.MigrationHandler;
import org.talend.sdk.component.api.component.Version;
import org.talend.sdk.component.api.configuration.Option;
import org.talend.sdk.component.api.configuration.constraint.Required;
import org.talend.sdk.component.api.configuration.type.DataStore;

@Data
@Version(migrationHandler = Connection2.Connection2Migration.class)
@DataStore("Connection-2")
public class Connection2 {

    @Option
    @Required
    private String url;

    @Option
    private String username;

    public static class Connection2Migration implements MigrationHandler {

        @Override
        public Map<String, String> migrate(final int incomingVersion, final Map<String, String> incomingData) {
            incomingData.put("url", "http://migrated");
            return incomingData;
        }
    }
}
