/*
 * Copyright 2015 Martin Kouba
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
package org.trimou.engine.context;

import org.trimou.engine.config.Configuration;
import org.trimou.engine.config.EngineConfigurationKey;
import org.trimou.engine.resolver.Resolver;

/**
 *
 * @author Martin Kouba
 */
public final class ExecutionContexts {

    private ExecutionContexts() {
    }

    /**
     *
     * @param configuration
     * @return a new global execution context for the given configuration
     */
    public static ExecutionContext newGlobalExecutionContext(Configuration configuration) {
        return new DefaultExecutionContext(null, configuration, configuration.getGlobalData(), null,
                configuration.getIntegerPropertyValue(EngineConfigurationKey.TEMPLATE_RECURSIVE_INVOCATION_LIMIT), null,
                configuration.getResolvers().toArray(new Resolver[configuration.getResolvers().size()]),
                configuration.getContextConverters());
    }

}
