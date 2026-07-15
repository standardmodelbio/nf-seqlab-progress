package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session

@CompileStatic
class DashboardClaims {

    private static final Map<Session, SeqlabProgressObserver> CLAIMS =
        Collections.synchronizedMap(new WeakHashMap<Session, SeqlabProgressObserver>())

    static void put(Session session, SeqlabProgressObserver observer) {
        CLAIMS[session] = observer
    }

    static boolean contains(Session session) {
        return CLAIMS.containsKey(session)
    }

    static SeqlabProgressObserver get(Session session) {
        return CLAIMS[session]
    }

    static void remove(Session session) {
        CLAIMS.remove(session)
    }

    static void clear() {
        CLAIMS.clear()
    }
}

