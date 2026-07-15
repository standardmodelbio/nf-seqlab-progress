/*
 * Copyright 2025, Seqera Labs
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

package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.trace.TraceObserverFactoryV2
import nextflow.trace.TraceObserverV2
import org.pf4j.Extension

/**
 * Implements a factory object required to create
 * the {@link SeqlabProgressObserver} instance.
 */
@CompileStatic
@Extension(ordinal = -1000)
class SeqlabProgressFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        ProgressRuntime runtime = ProgressRuntimes.getOrCreate(session)
        if (session.ansiLog && ConsoleSlot.supported(session) && ConsoleSlot.get(session) == null) {
            SeqlabProgressObserver observer = new SeqlabProgressObserver(runtime, true)
            if (ConsoleSlot.set(session, observer)) {
                DashboardClaims.put(session, observer)
                session.ansiLog = false
                return List.<TraceObserverV2>of(observer)
            }
        }
        return List.<TraceObserverV2>of(new SeqlabProgressObserver(runtime, false))
    }

}
