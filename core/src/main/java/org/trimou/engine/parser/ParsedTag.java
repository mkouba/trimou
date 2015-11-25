/*
 * Copyright 2013 Martin Kouba
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
package org.trimou.engine.parser;

import org.trimou.engine.MustacheTagType;

/**
 *
 * @author Martin Kouba
 */
final class ParsedTag {

    private final String content;

    private final MustacheTagType type;

    ParsedTag(String content, MustacheTagType type) {
        this.content = content;
        this.type = type;
    }

    /**
     * @return the tag content
     */
    String getContent() {
        return content;
    }

    /**
     * @return the tag type
     */
    MustacheTagType getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("ParsedTag [content=%s, type=%s]", content, type);
    }

}
