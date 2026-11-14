package com.satnova.alpha

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One serial writer, with remote presses ahead of queued catalogue/telemetry work. */
class ReceiverExecutor : ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
    PriorityBlockingQueue<Runnable>()) {
    private val sequence=AtomicLong()
    private class Task(val priority:Int,val order:Long,val action:Runnable):Runnable,Comparable<Task>{
        override fun run()=action.run()
        override fun compareTo(other:Task):Int {
            val rank=priority.compareTo(other.priority)
            return if(rank!=0)rank else order.compareTo(other.order)
        }
    }
    override fun execute(command:Runnable)=super.execute(Task(1,sequence.getAndIncrement(),command))
    fun executeRemote(command:Runnable)=super.execute(Task(0,sequence.getAndIncrement(),command))
}
