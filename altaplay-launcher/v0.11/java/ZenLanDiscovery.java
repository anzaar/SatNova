package com.gathin.tvhome;

import android.content.Context;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Constrained local-LAN discovery for G-MScreen Zen 4. No internet scanning,
 * no UI-thread work, no guessed identity, no destructive receiver commands.
 * Probe port 20000, then verify the login signature and remembered hardware ID.
 */
final class ZenLanDiscovery {
    private ZenLanDiscovery() {}

    static String scan(String rememberedIdentity, String uuid) {
        List<String> candidates = localCandidates();
        if (candidates.isEmpty()) return null;
        ExecutorService pool=Executors.newFixedThreadPool(12);
        CompletionService<String> done=new ExecutorCompletionService<>(pool);
        try {
            for(String ip:candidates) done.submit(()->probe(ip,rememberedIdentity,uuid));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            for(int i=0;i<candidates.size();i++) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)return null;
                Future<String> f=done.poll(remaining,TimeUnit.NANOSECONDS);
                if(f==null)return null;
                try {
                    String found=f.get();
                    if(found!=null)return found;
                } catch(Exception ignored) {}
            }
        }catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }finally{
            pool.shutdownNow();
        }
        return null;
    }

    private static List<String> localCandidates() {
        Set<String> addresses=new java.util.LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null&&interfaces.hasMoreElements()){
                NetworkInterface nif=interfaces.nextElement();
                if(!nif.isUp() || nif.isLoopback() || nif.isVirtual())continue;
                String name=nif.getName();
                if(name!=null && !(name.startsWith("wlan")||name.startsWith("eth")||
                    name.startsWith("en")||name.startsWith("ap")||name.startsWith("wifi")))continue;
                Enumeration<InetAddress> ipList=nif.getInetAddresses();
                while(ipList.hasMoreElements()){
                    InetAddress addr=ipList.nextElement();
                    if(!(addr instanceof Inet4Address))continue;
                    String ip=addr.getHostAddress();
                    if(!ZenProtocol.privateAddress(ip))continue;
                    int pos=ip.lastIndexOf('.');
                    if(pos<0)continue;
                    String subnet=ip.substring(0,pos+1);
                    for(int i=1;i<=254;i++){
                        String candidate=subnet+i;
                        if(!candidate.equals(ip))addresses.add(candidate);
                    }
                    // Two private subnets at most; avoid scanning large networks.
                    if(addresses.size()>=508)break;
                }
                if(addresses.size()>=508)break;
            }
        } catch(Exception ignored){}
        return new ArrayList<>(addresses);
    }

    private static String probe(String host,String oldIdentity,String uuid){
        if(Thread.currentThread().isInterrupted())return null;
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(host,ZenProtocol.PORT),200);
            socket.setSoTimeout(1500);
            String login="<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
               +"<Command request=\"998\"><data>AltaPlay Launcher</data><uuid>"
               +uuid+"</uuid></Command>";
            socket.getOutputStream().write(ZenProtocol.requestFrame(login));
            socket.getOutputStream().flush();
            byte[] block=ZenProtocol.readExactly(socket.getInputStream(),108);
            ZenProtocol.Login info=ZenProtocol.decodeLogin(block);
            if(info.full)return null;
            String identity=ZenClient.identity(block);
            if(!oldIdentity.isEmpty()&&!oldIdentity.equals(identity))return null;
            return host;
        }catch(Exception ignored){return null;}
    }
}
