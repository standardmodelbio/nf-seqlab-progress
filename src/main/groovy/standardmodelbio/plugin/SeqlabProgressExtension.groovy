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
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint

/**
 * Implements a custom function which can be imported by
 * Nextflow scripts.
 */
@CompileStatic
class SeqlabProgressExtension extends PluginExtensionPoint {

    private ProgressRuntime runtime

    @Override
    protected void init(Session session) {
        runtime = ProgressRuntimes.getOrCreate(session)
    }

    @Function
    void registerProgressInputs(Collection<Map<String, ?>> inputs) {
        runtime.registerInputs(inputs)
    }

    @Function
    void registerProgressStages(
        Collection<Map<String, ?>> stages,
        Collection<Map<String, ?>> processes
    ) {
        runtime.registerStages(stages, processes)
    }

    @Function
    Map<String, Object> withProgressIdentity(Map<String, ?> metadata) {
        Map<String, Object> enriched = new LinkedHashMap<>()
        enriched.putAll((Map) metadata)
        String fileId = (metadata['file_id'] ?: metadata['id'])?.toString()
        if (!fileId) {
            throw new IllegalArgumentException('Progress metadata requires id or file_id')
        }
        String parentFileId = (
            metadata['parent_file_id'] ?:
            metadata['parent_id'] ?:
            fileId
        ).toString()
        enriched['file_id'] = fileId
        enriched['parent_file_id'] = parentFileId
        return enriched
    }

}
