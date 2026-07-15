package standardmodelbio.plugin

import nextflow.Session
import nextflow.trace.AnsiLogObserver
import nextflow.trace.DefaultObserverFactory
import spock.lang.Specification

class ConsoleClaimTest extends Specification {

    def cleanup() {
        DashboardClaims.clear()
    }

    def 'claim runs before core and restore runs after core'() {
        given:
        def session = new Session()
        session.ansiLog = true

        when:
        def claimed = new SeqlabProgressFactory().create(session)

        then:
        claimed.size() == 1
        claimed.first() instanceof SeqlabProgressObserver
        ConsoleSlot.get(session).is(claimed.first())
        !session.ansiLog

        when:
        def coreObservers = new DefaultObserverFactory().create(session)

        then:
        !coreObservers.any { it instanceof AnsiLogObserver }

        when:
        def restored = new SeqlabProgressRestoreFactory().create(session)

        then:
        restored.isEmpty()
        session.ansiLog
        ConsoleSlot.get(session).is(claimed.first())
    }

    def 'occupied console slot is left untouched'() {
        given:
        def session = new Session()
        def stock = new AnsiLogObserver()
        session.ansiLog = true
        ConsoleSlot.set(session, stock)

        when:
        def observers = new SeqlabProgressFactory().create(session)

        then:
        observers.size() == 1
        ConsoleSlot.get(session).is(stock)
        session.ansiLog
        !((SeqlabProgressObserver) observers.first()).ansiClaimed
    }

    def 'non-ANSI sessions still receive a plain progress observer'() {
        given:
        def session = new Session()
        session.ansiLog = false

        when:
        def observers = new SeqlabProgressFactory().create(session)

        then:
        observers.size() == 1
        ConsoleSlot.get(session) == null
        !((SeqlabProgressObserver) observers.first()).ansiClaimed
    }
}

