package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.trace.TraceObserverFactoryV2
import nextflow.trace.TraceObserverV2
import org.pf4j.Extension

@CompileStatic
@Extension(ordinal = 1000)
class SeqlabProgressRestoreFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        if (DashboardClaims.contains(session)) {
            session.ansiLog = true
        }
        return Collections.emptyList()
    }
}
