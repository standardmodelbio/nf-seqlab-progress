package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.Session

import java.lang.reflect.Method

@CompileStatic
class ConsoleSlot {

    private static final List<String> GETTERS = ['getLogObserver', 'getAnsiLogObserver']
    private static final List<String> SETTERS = ['setLogObserver', 'setAnsiLogObserver']

    static boolean supported(Session session) {
        return getter(session) != null && !setters(session).isEmpty()
    }

    static Object get(Session session) {
        Method method = getter(session)
        if (method == null) {
            return null
        }
        return method.invoke(session)
    }

    static boolean set(Session session, Object observer) {
        Method method = setters(session).find { Method candidate ->
            candidate.parameterTypes[0].isInstance(observer)
        }
        if (method == null) {
            return false
        }
        method.invoke(session, observer)
        return true
    }

    private static Method getter(Session session) {
        return session.class.methods.find { Method method ->
            method.parameterCount == 0 && method.name in GETTERS
        }
    }

    private static List<Method> setters(Session session) {
        return session.class.methods.findAll { Method method ->
            method.parameterCount == 1 && method.name in SETTERS
        }
    }
}

