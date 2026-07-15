package standardmodelbio.plugin.io

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import standardmodelbio.plugin.model.ProgressSnapshot

import java.nio.file.Files
import java.nio.file.Path

@CompileStatic
class ProgressSnapshotReader {

    ProgressSnapshot read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null
        }
        try {
            Object parsed = new JsonSlurper().parseText(Files.readString(path))
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException('Progress snapshot must contain a JSON object')
            }
            return ProgressSnapshot.fromMap((Map) parsed)
        }
        catch (IllegalArgumentException error) {
            throw error
        }
        catch (Exception error) {
            throw new IllegalArgumentException("Unable to parse progress snapshot '${path}': ${error.message}", error)
        }
    }
}

