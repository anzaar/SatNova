package com.satnova.alpha

/** G-MScreen SatRequestManager: START 401, SQ 403, END 405 (bare commands). */
class TunerSession {
    private enum class Phase { UNKNOWN, STARTING, SIGNAL, ENDING, IDLE, FAILED }
    private var phase=Phase.UNKNOWN
    @Synchronized fun reset(){phase=Phase.UNKNOWN}
    @Synchronized fun enter():Int {phase=Phase.STARTING;return START}
    @Synchronized fun leave():Int {phase=Phase.ENDING;return END}
    @Synchronized fun reply(type:Int,status:Int):Boolean {
        if(type==START && phase==Phase.STARTING){phase=if(status==0)Phase.SIGNAL else Phase.FAILED;return true}
        if(type==END && phase==Phase.ENDING){phase=if(status==0)Phase.IDLE else Phase.FAILED;return true}
        return false
    }
    @Synchronized fun mayReadSignal()=phase==Phase.SIGNAL
    @Synchronized fun mayPlay()=phase==Phase.IDLE
    companion object {const val START=401;const val END=405;const val RESTART=1043}
}
