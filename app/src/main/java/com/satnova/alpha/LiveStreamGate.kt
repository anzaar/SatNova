package com.satnova.alpha

/** Correlates the receiver's unnumbered 1009 replies with one request per TCP session. */
class LiveStreamGate {
    data class Ticket(val epoch:Long,val serial:Long,val channel:String)
    private var epoch=0L
    private var serial=0L
    private var wanted=""
    private var pending:Ticket?=null
    private var active:Ticket?=null
    @Synchronized fun reset(){epoch++;wanted="";pending=null;active=null}
    @Synchronized fun desire(channel:String){if(wanted!=channel){wanted=channel;active=null}}
    @Synchronized fun invalidatePlayback(){active=null}
    @Synchronized fun begin():Ticket? {
        if(wanted.isBlank() || pending!=null)return null
        return Ticket(epoch,++serial,wanted).also{pending=it}
    }
    @Synchronized fun pending()=pending
    /** Consume even an obsolete response before allowing another request of the same type. */
    @Synchronized fun complete(ticket:Ticket):Boolean {
        if(pending!=ticket)return false
        pending=null
        if(ticket.epoch!=epoch || ticket.channel!=wanted)return false
        active=ticket
        return true
    }
    @Synchronized fun fail(ticket:Ticket):Boolean {
        if(pending!=ticket)return false
        pending=null;active=null
        return true
    }
    @Synchronized fun isActive(ticket:Ticket)=active==ticket && ticket.epoch==epoch && ticket.channel==wanted
}
