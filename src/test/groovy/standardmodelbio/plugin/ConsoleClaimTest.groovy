package standardmodelbio.plugin

import nextflow.Session
import nextflow.trace.AnsiLogObserver
import nextflow.trace.DefaultObserverFactory
import spock.lang.Specification
import spock.lang.TempDir
import standardmodelbio.plugin.io.ProgressAuditWriter

import java.nio.file.Path

class ConsoleClaimTest extends Specification {

    @TempDir
    Path workDir

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

    def 'occupied ANSI console degrades the dashboard to plain output'() {
        given:
        def session = new Session()
        def stock = new AnsiLogObserver()
        session.ansiLog = true
        session.config = [params: [
            outdir: workDir.toString(),
            progress_mode: 'auto',
            progress_refresh_seconds: 60,
        ]]
        session.outputDir = workDir
        ConsoleSlot.set(session, stock)
        def runtime = new ProgressRuntime('test-run', 'Test run')
        def observer = new SeqlabProgressObserver(
            runtime,
            false,
            [TERM: 'xterm-256color'],
        )

        when:
        observer.onFlowCreate(session)

        then:
        noExceptionThrown()

        cleanup:
        observer?.onFlowComplete()
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

    def 'flow completion releases a console slot owned by the dashboard'() {
        given:
        def session = new Session()
        session.ansiLog = true
        session.config = [params: [
            outdir: workDir.toString(),
            progress_mode: 'off',
            progress_refresh_seconds: 60,
        ]]
        session.outputDir = workDir
        def runtime = new ProgressRuntime('owned-run', 'Owned run')
        def observer = new SeqlabProgressObserver(runtime, true, [TERM: 'dumb'])
        assert ConsoleSlot.set(session, observer)
        DashboardClaims.put(session, observer)
        observer.onFlowCreate(session)

        when:
        observer.onFlowComplete()

        then:
        ConsoleSlot.get(session) == null
        !DashboardClaims.contains(session)
    }

    def 'flow completion does not clear a replacement console owner'() {
        given:
        def session = new Session()
        session.ansiLog = true
        session.config = [params: [
            outdir: workDir.toString(),
            progress_mode: 'off',
            progress_refresh_seconds: 60,
        ]]
        session.outputDir = workDir
        def runtime = new ProgressRuntime('replaced-run', 'Replaced run')
        def observer = new SeqlabProgressObserver(runtime, true, [TERM: 'dumb'])
        def replacement = new AnsiLogObserver()
        assert ConsoleSlot.set(session, observer)
        DashboardClaims.put(session, observer)
        observer.onFlowCreate(session)
        assert ConsoleSlot.set(session, replacement)

        when:
        observer.onFlowComplete()

        then:
        ConsoleSlot.get(session).is(replacement)
    }

    def 'audit close failure does not block console claim or runtime cleanup'() {
        given:
        def session = configuredSession(new Session())
        def registeredRuntime = ProgressRuntimes.getOrCreate(session)
        def observer = new SeqlabProgressObserver(registeredRuntime, true, [TERM: 'dumb'])
        assert ConsoleSlot.set(session, observer)
        DashboardClaims.put(session, observer)
        observer.onFlowCreate(session)
        replaceAuditWriter(
            observer,
            new FailingCloseAuditWriter(workDir.resolve('failing-audit.jsonl')),
        )

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
        ConsoleSlot.get(session) == null
        !DashboardClaims.contains(session)
        !ProgressRuntimes.getOrCreate(session).is(registeredRuntime)

        cleanup:
        ProgressRuntimes.remove(session)
    }

    def 'console reflection failure does not block claim or runtime cleanup'() {
        given:
        def session = configuredSession(new Session())
        def registeredRuntime = ProgressRuntimes.getOrCreate(session)
        def observer = new SeqlabProgressObserver(registeredRuntime, true, [TERM: 'dumb'])
        assert ConsoleSlot.set(session, observer)
        DashboardClaims.put(session, observer)
        observer.onFlowCreate(session)
        ConsoleSlot.metaClass.'static'.clearIfOwned = { Session ignored, Object owner ->
            throw new IllegalStateException('injected console reflection failure')
        }

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
        ConsoleSlot.get(session).is(observer)
        !DashboardClaims.contains(session)
        !ProgressRuntimes.getOrCreate(session).is(registeredRuntime)

        cleanup:
        GroovySystem.metaClassRegistry.removeMetaClass(ConsoleSlot)
        ConsoleSlot.clearIfOwned(session, observer)
        ProgressRuntimes.remove(session)
    }

    private Session configuredSession(Session session) {
        session.ansiLog = true
        session.config = [params: [
            outdir: workDir.toString(),
            progress_mode: 'off',
            progress_refresh_seconds: 60,
        ]]
        session.outputDir = workDir
        return session
    }

    private static void replaceAuditWriter(
        SeqlabProgressObserver observer,
        ProgressAuditWriter replacement
    ) {
        def field = SeqlabProgressObserver.getDeclaredField('auditWriter')
        field.accessible = true
        ((ProgressAuditWriter) field.get(observer))?.close()
        field.set(observer, replacement)
    }
}

class FailingCloseAuditWriter extends ProgressAuditWriter {

    FailingCloseAuditWriter(Path path) {
        super(path)
    }

    @Override
    synchronized void close() {
        super.close()
        throw new IOException('injected audit close failure')
    }
}
