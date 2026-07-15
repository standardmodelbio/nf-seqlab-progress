package standardmodelbio.plugin

import nextflow.Session
import spock.lang.Specification

/**
 * Implements a basic factory test
 *
 */
class SeqlabProgressObserverTest extends Specification {

    def 'should create the observer instance' () {
        given:
        def factory = new SeqlabProgressFactory()
        when:
        def result = factory.create(Mock(Session))
        then:
        result.size() == 1
        result.first() instanceof SeqlabProgressObserver
    }

}
