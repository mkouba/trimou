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
package org.trimou.engine.segment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trimou.engine.MustacheEngine;
import org.trimou.engine.MustacheTagInfo;
import org.trimou.engine.context.ExecutionContext;
import org.trimou.engine.context.ValueWrapper;
import org.trimou.engine.parser.Template;
import org.trimou.exception.MustacheException;
import org.trimou.exception.MustacheProblem;
import org.trimou.handlebars.Helper;
import org.trimou.handlebars.HelperDefinition;
import org.trimou.handlebars.HelperDefinition.ValuePlaceholder;
import org.trimou.handlebars.HelperValidator;
import org.trimou.handlebars.Options;
import org.trimou.util.Checker;
import org.trimou.util.Strings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Wraps {@link Helper} instance and handles its execution (e.g. builds
 * {@link Options} instance).
 *
 * @author Martin Kouba
 * @see HelperAwareSegment
 */
class HelperExecutionHandler {

    private final Helper helper;

    private final OptionsBuilder optionsBuilder;

    /**
     *
     * @param helper
     * @param optionsBuilder
     */
    private HelperExecutionHandler(Helper helper, OptionsBuilder optionsBuilder) {
        this.helper = helper;
        this.optionsBuilder = optionsBuilder;
    }

    /**
     *
     * @param name
     * @param configuration
     * @param segment
     * @return a handler for the given name or <code>null</code> if no such
     *         helper exists
     */
    static HelperExecutionHandler from(String name, MustacheEngine engine,
            HelperAwareSegment segment) {

        // First detect unterminated literals
        Iterator<String> parts = HelperValidator.splitHelperName(name, segment);

        Helper helper = engine.getConfiguration().getHelpers()
                .get(parts.next());

        if (helper == null) {
            return null;
        }

        ImmutableList.Builder<Object> params = ImmutableList.builder();
        ImmutableMap.Builder<String, Object> hash = ImmutableMap.builder();

        while (parts.hasNext()) {
            String part = parts.next();
            // TODO KeySplitter should be responsible for key validation
            // https://github.com/trimou/trimou/issues/56
            // String literal may contain anything
            int position = HelperValidator
                    .getFirstDeterminingEqualsCharPosition(part);
            if (position != -1) {
                hash.put(
                        part.substring(0, position),
                        getLiteralOrPlaceholder(
                                part.substring(position + 1, part.length()),
                                engine, segment));
            } else {
                params.add(getLiteralOrPlaceholder(part, engine, segment));
            }
        }

        OptionsBuilder optionsBuilder = new OptionsBuilder(params.build(),
                hash.build(), segment, engine);

        // Let the helper validate the tag definition
        helper.validate(optionsBuilder);

        return new HelperExecutionHandler(helper, optionsBuilder);
    }

    /**
     *
     * @param appendable
     * @param executionContext
     */
    Appendable execute(Appendable appendable, ExecutionContext executionContext) {

        DefaultOptions options = optionsBuilder.build(appendable,
                executionContext);
        try {
            helper.execute(options);
            return options.getAppendable();
        } finally {
            options.release();
        }
    }

    private static Object getLiteralOrPlaceholder(String value,
            MustacheEngine engine, HelperAwareSegment segment) {
        Object literal = engine.getConfiguration().getLiteralSupport()
                .getLiteral(value, segment.getTagInfo());
        return literal != null ? literal : new DefaultValuePlaceholder(value);
    }

    private static class OptionsBuilder implements HelperDefinition {

        private final List<Object> parameters;

        private final Map<String, Object> hash;

        private final HelperAwareSegment segment;

        private final MustacheEngine engine;

        // true if not placeholder found, also if params list is empty
        private final boolean isParamValuePlaceholderFound;

        // true if not placeholder found, also if hash map is empty
        private final boolean isHashValuePlaceholderFound;

        private OptionsBuilder(List<Object> parameters,
                Map<String, Object> hash, HelperAwareSegment segment,
                MustacheEngine engine) {
            this.parameters = parameters;
            this.hash = hash;
            this.segment = segment;
            this.engine = engine;
            this.isParamValuePlaceholderFound = initParamValuePlaceholderFound(parameters);
            this.isHashValuePlaceholderFound = initHashValuePlaceholderFound(hash);
        }

        @Override
        public MustacheTagInfo getTagInfo() {
            return segment.getTagInfo();
        }

        @Override
        public List<Object> getParameters() {
            return parameters;
        }

        @Override
        public Map<String, Object> getHash() {
            return hash;
        }

        @Override
        public String getContentLiteralBlock() {
            if (segment instanceof ContainerSegment) {
                return ((ContainerSegment) segment).getContentLiteralBlock();
            } else {
                return Strings.EMPTY;
            }
        }

        public DefaultOptions build(Appendable appendable,
                ExecutionContext executionContext) {

            List<ValueWrapper> valueWrappers = new ArrayList<ValueWrapper>();
            List<Object> finalParams;
            Map<String, Object> finalHash;

            if (isParamValuePlaceholderFound) {
                // At this point parameters list is never empty
                int size = parameters.size();
                switch (size) {
                case 1:
                    // Very often there will be only single param
                    finalParams = Collections
                            .singletonList(resolveValue(parameters.get(0),
                                    valueWrappers, executionContext));
                    break;
                default:
                    finalParams = new ArrayList<Object>(size);
                    for (Object param : parameters) {
                        finalParams.add(resolveValue(param, valueWrappers,
                                executionContext));
                    }
                    finalParams = Collections.unmodifiableList(finalParams);
                    break;
                }
            } else {
                finalParams = parameters;
            }

            if (isHashValuePlaceholderFound) {
                // At this point hash map is never empty
                int size = hash.size();
                switch (size) {
                case 1:
                    Entry<String, Object> singleEntry = hash.entrySet()
                            .iterator().next();
                    finalHash = Collections.singletonMap(
                            singleEntry.getKey(),
                            resolveValue(singleEntry.getValue(), valueWrappers,
                                    executionContext));
                    break;
                default:
                    finalHash = new HashMap<String, Object>();
                    for (Entry<String, Object> entry : hash.entrySet()) {
                        finalHash.put(
                                entry.getKey(),
                                resolveValue(entry.getValue(), valueWrappers,
                                        executionContext));
                    }
                    finalHash = Collections.unmodifiableMap(finalHash);
                    break;
                }
            } else {
                finalHash = hash;
            }

            return new DefaultOptions(appendable, executionContext, segment,
                    finalParams, finalHash, valueWrappers, engine);
        }

        private Object resolveValue(Object value,
                List<ValueWrapper> valueWrappers,
                ExecutionContext executionContext) {

            if (value instanceof ValuePlaceholder) {
                ValueWrapper wrapper = executionContext
                        .getValue(((ValuePlaceholder) value).getName());
                valueWrappers.add(wrapper);
                return wrapper.get();
            } else {
                return value;
            }
        }

        private boolean initParamValuePlaceholderFound(List<Object> parameters) {
            if (parameters.isEmpty()) {
                return false;
            }
            for (Object param : parameters) {
                if (param instanceof ValuePlaceholder) {
                    return true;
                }
            }
            return false;
        }

        private boolean initHashValuePlaceholderFound(Map<String, Object> hash) {
            if (hash.isEmpty()) {
                return false;
            }
            for (Entry<String, Object> entry : hash.entrySet()) {
                if (entry.getValue() instanceof ValuePlaceholder) {
                    return true;
                }
            }
            return false;
        }

    }

    private static class DefaultOptions implements Options {

        private static final Logger logger = LoggerFactory
                .getLogger(DefaultOptions.class);

        protected final List<ValueWrapper> valueWrappers;

        protected Appendable appendable;

        protected int pushed;

        protected ExecutionContext executionContext;

        private final MustacheEngine engine;

        private final HelperAwareSegment segment;

        private final List<Object> parameters;

        private final Map<String, Object> hash;

        /**
         *
         * @param appendable
         * @param executionContext
         * @param segment
         * @param parameters
         * @param hash
         * @param valueWrappers
         * @param engine
         */
        DefaultOptions(Appendable appendable,
                ExecutionContext executionContext, HelperAwareSegment segment,
                List<Object> parameters, Map<String, Object> hash,
                List<ValueWrapper> valueWrappers, MustacheEngine engine) {
            this.appendable = appendable;
            this.valueWrappers = valueWrappers;
            this.executionContext = executionContext;
            this.pushed = 0;
            this.executionContext = executionContext;
            this.segment = segment;
            this.parameters = parameters;
            this.hash = hash;
            this.engine = engine;
        }

        @Override
        public List<Object> getParameters() {
            return parameters;
        }

        @Override
        public Map<String, Object> getHash() {
            return hash;
        }

        @Override
        public void append(CharSequence sequence) {
            try {
                appendable.append(sequence);
            } catch (IOException e) {
                throw new MustacheException(MustacheProblem.RENDER_IO_ERROR, e);
            }
        }

        @Override
        public void fn() {
            appendable = segment.fn(appendable, executionContext);
        }

        @Override
        public void partial(String templateId) {
            partial(templateId, appendable);
        }

        @Override
        public void push(Object contextObject) {
            pushed++;
            executionContext = executionContext.setContextObject(contextObject);
        }

        @Override
        public Object pop() {
            if (pushed > 0) {
                pushed--;
                Object top = executionContext.getFirstContextObject();
                executionContext = executionContext.getParent();
                return top;
            }
            throw new MustacheException(
                    MustacheProblem.RENDER_HELPER_INVALID_POP_OPERATION);
        }

        @Override
        public Object peek() {
            return executionContext.getFirstContextObject();
        }

        @Override
        public Object getValue(String key) {
            ValueWrapper wrapper = executionContext.getValue(key);
            valueWrappers.add(wrapper);
            return wrapper.get();
        }

        @Override
        public void partial(String templateId, Appendable appendable) {
            partial(templateId, appendable, executionContext);
        }

        @Override
        public void executeAsync(final HelperExecutable executable) {
            // For async execution we need to wrap the original appendable
            final AsyncAppendable asyncAppendable = new AsyncAppendable(
                    appendable);

            // Now submit the executable and get the future
            ExecutorService executor = engine.getConfiguration()
                    .geExecutorService();
            if (executor == null) {
                throw new MustacheException(
                        MustacheProblem.RENDER_ASYNC_PROCESSING_ERROR,
                        "ExecutorService must be set in order to submit an asynchronous task");
            }
            Future<AsyncAppendable> future = executor
                    .submit(new Callable<AsyncAppendable>() {
                        @Override
                        public AsyncAppendable call() throws Exception {
                            // We need a separate appendable for the async
                            // execution
                            DefaultOptions asyncOptions = new DefaultOptions(
                                    new AsyncAppendable(asyncAppendable),
                                    executionContext, segment, parameters,
                                    hash, new ArrayList<ValueWrapper>(), engine);
                            executable.execute(asyncOptions);
                            return (AsyncAppendable) asyncOptions
                                    .getAppendable();
                        }
                    });
            asyncAppendable.setFuture(future);
            this.appendable = asyncAppendable;
        }

        @Override
        public String source(String templateId) {
            Checker.checkArgumentNotEmpty(templateId);

            String mustacheSource = engine.getMustacheSource(templateId);

            if (mustacheSource == null) {
                throw new MustacheException(
                        MustacheProblem.RENDER_INVALID_PARTIAL_KEY,
                        "No mustache template found for the given key: %s %s",
                        templateId, segment.getOrigin());
            }
            return mustacheSource;
        }

        @Override
        public Appendable getAppendable() {
            return appendable;
        }

        @Override
        public void fn(Appendable appendable) {
            segment.fn(appendable, executionContext);
        }

        @Override
        public MustacheTagInfo getTagInfo() {
            return segment.getTagInfo();
        }

        @Override
        public String getContentLiteralBlock() {
            if (segment instanceof ContainerSegment) {
                return ((ContainerSegment) segment).getContentLiteralBlock();
            } else {
                return Strings.EMPTY;
            }
        }

        protected void partial(String templateId, Appendable appendable,
                ExecutionContext executionContext) {
            Checker.checkArgumentsNotNull(templateId, appendable);

            Template partialTemplate = (Template) engine
                    .getMustache(templateId);

            if (partialTemplate == null) {
                throw new MustacheException(
                        MustacheProblem.RENDER_INVALID_PARTIAL_KEY,
                        "No partial found for the given key: %s %s",
                        templateId, segment.getOrigin());
            }
            // Note that indentation is not supported
            partialTemplate.getRootSegment().execute(appendable,
                    executionContext);
        }

        void release() {
            int wrappersSize = valueWrappers.size();
            if (wrappersSize == 1) {
                valueWrappers.get(0).release();
            } else if (wrappersSize > 1) {
                for (ValueWrapper wrapper : valueWrappers) {
                    wrapper.release();
                }
            }
            if (pushed > 0) {
                logger.info(
                        "{} remaining objects pushed on the context stack will be automatically garbage collected [helperName: {}, template: {}]",
                        new Object[] {
                                pushed,
                                HelperValidator
                                        .splitHelperName(
                                                segment.getTagInfo().getText(),
                                                segment).next(),
                                segment.getTagInfo().getTemplateName() });
            }
        }

    }

    private static class DefaultValuePlaceholder implements ValuePlaceholder {

        private final String name;

        public DefaultValuePlaceholder(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

    }

}
