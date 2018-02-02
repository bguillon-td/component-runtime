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
package org.talend.sdk.component.runtime.beam.coder;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.apache.ziplock.JarLocation.jarLocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.StreamSupport;

import javax.json.Json;
import javax.json.JsonObject;

import org.apache.beam.sdk.coders.IterableCoder;
import org.junit.jupiter.api.Test;

class JsonpJsonObjectCoderTest {

    private static final String PLUGIN = jarLocation(JsonpJsonObjectCoderTest.class).getAbsolutePath();

    @Test
    void roundTrip() throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final JsonpJsonObjectCoder coder = JsonpJsonObjectCoder.of(PLUGIN);
        coder.encode(Json.createObjectBuilder().add("test", "foo").build(), outputStream);
        final JsonObject jsonObject = coder.decode(new ByteArrayInputStream(outputStream.toByteArray()));
        assertTrue(new String(outputStream.toByteArray()).endsWith("{\"test\":\"foo\"}"));
        assertEquals("foo", jsonObject.getString("test"));
        assertEquals(1, jsonObject.size());
    }

    @Test
    void iterable() throws IOException {
        final IterableCoder<JsonObject> coder = IterableCoder.of(new JsonpJsonObjectCoder("foo",
                Json.createReaderFactory(emptyMap()), Json.createWriterFactory(emptyMap())));
        final Iterator<JsonObject> iterator =
                Collections.singletonList(Json.createObjectBuilder().add("test", "value").build()).iterator();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        coder.encode(() -> iterator, out);
        final Iterable<JsonObject> decode = coder.decode(new ByteArrayInputStream(out.toByteArray()));
        final Collection<JsonObject> result = StreamSupport.stream(decode.spliterator(), false).collect(toList());
        assertEquals(1, result.size());
        assertEquals("value", result.iterator().next().getString("test"));
    }
}