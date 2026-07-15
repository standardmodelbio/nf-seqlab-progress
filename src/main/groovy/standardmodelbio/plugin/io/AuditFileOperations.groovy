package standardmodelbio.plugin.io

import groovy.transform.CompileStatic
import nextflow.file.FileHelper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING

@CompileStatic
interface AuditFileOperations {
    BufferedWriter openAppender(Path path)

    void copy(Path source, Path target)

    boolean deleteIfExists(Path path)
}

@CompileStatic
class NextflowAuditFileOperations implements AuditFileOperations {

    @Override
    BufferedWriter openAppender(Path path) {
        return Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    @Override
    void copy(Path source, Path target) {
        FileHelper.copyPath(source, target, REPLACE_EXISTING)
    }

    @Override
    boolean deleteIfExists(Path path) {
        return Files.deleteIfExists(path)
    }
}
