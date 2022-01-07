/*
 * Copyright (c) 2021, 2022 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.build.maven.archetype;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link Schema}.
 */
class SchemaTest {

    private static final Schema VALIDATOR = new Schema(
            SchemaTest.class.getClassLoader().getResourceAsStream(Schema.RESOURCE_NAME));

    @Test
    void testValidate() {
        VALIDATOR.validate(() -> resource("colors.xml"));
    }

    @Test
    void testValidateNegative() {
        RuntimeException ex = assertThrows(Schema.ValidationException.class,
                () -> VALIDATOR.validate(() -> resource("shapes.xml")));
        assertThat(ex.getCause(), is(instanceOf(SAXParseException.class)));
    }

    @Test
    void testSkipNonArchetypes() {
        VALIDATOR.validate(() -> resource("other.xml"));
    }

    private static InputStream resource(String path) {
        return SchemaTest.class.getResourceAsStream("/schema/" + path);
    }
}
