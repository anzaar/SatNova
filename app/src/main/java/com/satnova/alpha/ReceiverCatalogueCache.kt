package com.satnova.alpha

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Per physical receiver, never keyed only by its changeable LAN address. */
class ReceiverCatalogueCache(private val directory:File) {
    data class Snapshot(val total:Int,val next:Int,val channels:List<JSONObject>,
        val satellites:List<JSONObject>,val transponders:List<JSONObject>)
    fun load(key:String,expectedTotal:Int=0):Snapshot?=try {
        require(key.matches(Regex("[a-f0-9]{64}")))
        val file=File(directory,"$key.json")
        require(file.length() in 1..20_000_000)
        val root=JSONObject(file.readText())
        require(root.getInt("format")==1 && root.getString("receiver")==key)
        val total=root.getInt("total");val next=root.getInt("next")
        require(total in 0..20000 && next in 0..20000)
        require(expectedTotal<=0 || total==expectedTotal)
        fun list(name:String):List<JSONObject> {
            val rows=root.getJSONArray(name)
            require(rows.length()<=20000)
            return (0 until rows.length()).map{rows.getJSONObject(it)}
        }
        Snapshot(total,next,list("channels"),list("satellites"),list("transponders"))
    }catch(_:Exception){null}
    fun save(key:String,snapshot:Snapshot){
        require(key.matches(Regex("[a-f0-9]{64}")))
        directory.mkdirs()
        val root=JSONObject().put("format",1).put("receiver",key)
            .put("total",snapshot.total).put("next",snapshot.next)
            .put("channels",JSONArray(snapshot.channels))
            .put("satellites",JSONArray(snapshot.satellites))
            .put("transponders",JSONArray(snapshot.transponders))
        val destination=File(directory,"$key.json")
        val temporary=File.createTempFile("catalog-",".tmp",directory)
        try {
            temporary.writeText(root.toString())
            try{Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        }finally{temporary.delete()}
    }
    companion object {
        fun serialIdentity(serial:String,model:String):String? {
            val value=serial.trim()
            if(value.length<4 || value.all{it=='0'} || value.lowercase() in listOf("unknown","null","inconnu"))return null
            return MessageDigest.getInstance("SHA-256").digest(("serial:$value\n$model").toByteArray())
                .joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        fun identity(encodedLogin:ByteArray):String? {
            if(encodedLogin.size!=108)return null
            val bytes=encodedLogin.reversedArray().map{(it.toInt() xor 0x5B).toByte()}.toByteArray()
            if(String(bytes,0,12,Charsets.US_ASCII)!="39WwijOog54a")return null
            val serial=bytes.copyOfRange(12,20)
            val chip=bytes.copyOfRange(52,60)
            if((serial+chip).all{it==0.toByte()})return null
            return MessageDigest.getInstance("SHA-256").digest(serial+chip+bytes.copyOfRange(20,52).takeWhile{it!=0.toByte()}.toByteArray())
                .joinToString(""){"%02x".format(it.toInt() and 255)}
        }
    }
}
