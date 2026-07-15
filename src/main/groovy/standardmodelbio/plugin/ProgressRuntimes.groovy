package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session

@CompileStatic
class ProgressRuntimes {

    private static final Map<Session, ProgressRuntime> RUNTIMES =
        Collections.synchronizedMap(new WeakHashMap<Session, ProgressRuntime>())

    static ProgressRuntime getOrCreate(Session session) {
        synchronized (RUNTIMES) {
            ProgressRuntime runtime = RUNTIMES[session]
            if (runtime == null) {
                String runId = session.runName ?: session.uniqueId?.toString() ?: 'nf-seqlab'
                String runName = session.runName ?: runId
                runtime = new ProgressRuntime(runId, runName)
                RUNTIMES[session] = runtime
            }
            return runtime
        }
    }

    static void remove(Session session) {
        RUNTIMES.remove(session)
    }
}
