package standardmodelbio.plugin.io

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@CompileStatic
class ProgressAuditWriter implements Closeable {

    private final BufferedWriter writer
    private boolean closed

    ProgressAuditWriter(Path path) {
        Path parent = path.toAbsolutePath().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        this.writer = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    synchronized void append(Map<String, ?> event) {
        if (closed) {
            throw new IllegalStateException('Progress audit writer is closed')
        }
        writer.write(JsonOutput.toJson(event))
        writer.newLine()
        writer.flush()
    }

    @Override
    synchronized void close() {
        if (!closed) {
            closed = true
            writer.close()
        }
    }
}

