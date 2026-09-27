package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session

@CompileStatic
class ProgressRuntimes {

    private static final Map<Session, ProgressRuntime> RUNTIMES =
        Collections.synchronizedMap(new WeakHashMap<Session, ProgressRuntime>())

    /**
     * Resolve the run id the tasks will stamp on their snapshots.
     *
     * This MUST mirror what nf-seqlab's modules export, which is:
     *
     *     NF_SEQLAB_PROGRESS_RUN_ID="${NF_SEQLAB_PROGRESS_RUN_ID:-$(basename "$(dirname "$(dirname "$PWD")")")}"
     *
     * — the env var when set, else the work directory's own name (a task runs
     * in {@code <workDir>/ab/cdef...}, so two dirnames up is the work dir).
     *
     * Falling straight through to {@code session.runName} could never agree
     * with that under a launcher which names its work directory after a
     * campaign: Nextflow mints a fresh run name per run, so every snapshot was
     * rejected with "Snapshot run 'aou_v9_pool_g0' does not match
     * 'nauseous_borg'" and the dashboard sat at 0% for the whole run.
     *
     * The tasks cannot use the run name instead — it changes between runs, and
     * anything varying inside a script block makes every task a cache miss on
     * -resume — so the observer is the side that has to adapt.
     *
     * Display is unaffected: the renderers prefer {@code runName}.
     */
    static String resolveRunId(Session session) {
        Object configured = session?.config?.navigate('env.NF_SEQLAB_PROGRESS_RUN_ID')
        String fromConfig = configured?.toString()
        if (fromConfig) {
            return fromConfig
        }
        String fromEnv = System.getenv('NF_SEQLAB_PROGRESS_RUN_ID')
        if (fromEnv) {
            return fromEnv
        }
        String workDirName = session?.workDir?.fileName?.toString()
        if (workDirName) {
            return workDirName
        }
        return session?.runName ?: session?.uniqueId?.toString() ?: 'nf-seqlab'
    }

    static ProgressRuntime getOrCreate(Session session) {
        synchronized (RUNTIMES) {
            ProgressRuntime runtime = RUNTIMES[session]
            if (runtime == null) {
                String runId = resolveRunId(session)
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
