package standardmodelbio.plugin.io

import groovy.transform.CompileStatic
import nextflow.file.FileHelper

import java.nio.file.Path
import java.util.function.Function

@CompileStatic
class AuditPathResolver {

    private final Function<String, Path> pathParser

    AuditPathResolver() {
        this(new Function<String, Path>() {
            @Override
            Path apply(String value) {
                return FileHelper.asPath(value)
            }
        })
    }

    AuditPathResolver(Function<String, Path> pathParser) {
        this.pathParser = Objects.requireNonNull(pathParser, 'pathParser')
    }

    Path resolve(Object configuredOutdir, Path fallback) {
        if (configuredOutdir instanceof Path) {
            return configuredOutdir as Path
        }
        String value = configuredOutdir?.toString()?.trim()
        return value ? pathParser.apply(value) : fallback
    }
}
