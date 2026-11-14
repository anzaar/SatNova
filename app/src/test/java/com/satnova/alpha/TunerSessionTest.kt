package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class TunerSessionTest {
    @Test fun videoWaitsForConfirmedFinderExit(){
        val session=TunerSession()
        assertFalse(session.mayPlay())
        assertEquals(401,session.enter())
        assertFalse(session.mayReadSignal())
        session.reply(401,0)
        assertTrue(session.mayReadSignal())
        assertEquals(405,session.leave())
        assertFalse(session.mayReadSignal())
        assertFalse(session.mayPlay())
        session.reply(403,0) // An in-flight sample cannot release video startup.
        assertFalse(session.mayPlay())
        session.reply(405,0)
        assertTrue(session.mayPlay())
    }
    @Test fun staleStartAndFailedExitCannotOpenVideo(){
        val session=TunerSession()
        session.enter();session.leave();session.reply(401,0)
        assertFalse(session.mayReadSignal())
        session.reply(405,17)
        assertFalse(session.mayPlay())
        session.leave();session.reply(405,0)
        assertTrue(session.mayPlay())
        session.enter();session.reply(405,0)
        assertFalse(session.mayPlay())
        session.reset()
        assertFalse(session.mayPlay())
        assertEquals(1043,TunerSession.RESTART)
    }
}
