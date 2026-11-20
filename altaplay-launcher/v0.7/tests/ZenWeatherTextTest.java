package com.gathin.tvhome;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;

public final class ZenWeatherTextTest {
    static void ok(boolean test,String message){
        if(!test)throw new AssertionError(message);
    }
    public static void main(String[] ignored){
        String timeout=ZenWeatherText.problem(new SocketTimeoutException("Read timed out"));
        ok(timeout.contains("تأخر اتصال"),"Timeout should be Moroccan Arabic");
        ok(!timeout.contains("Read timed out"),"NEVER leak raw exception English into UI");
        ok(!ZenWeatherText.problem(new UnknownHostException("DNS error")).contains("DNS"),"DNS error must be localized");
        ok(!ZenWeatherText.problem(new ConnectException("Failed to connect")).contains("Failed"),"Connection errors must be localized");
        ok(!ZenWeatherText.problem(new SSLHandshakeException("Certificate error")).contains("Certificate"),"TLS errors must be localized");
        ok("ضباب".equals(ZenWeatherText.condition(45,1)),"WMO fog");
        ok("غائم".equals(ZenWeatherText.condition(3,1)),"WMO overcast");
        ok("زخات مطر".equals(ZenWeatherText.condition(81,1)),"WMO showers");
        ok("عواصف رعدية".equals(ZenWeatherText.condition(95,1)),"WMO thunder");
        ok("سماء صافية ليلاً".equals(ZenWeatherText.condition(0,0)),"Night sky");
        String warm="14°م · ضباب";
        ok(warm.equals(ZenWeatherText.status(warm,4*60_000L,false,timeout)),"fresh success must win over timeout");
        ok(ZenWeatherText.status(warm,3*60*60_000L,false,timeout).contains("آخر حالة محفوظة"),"cached offline state");
        ok(!ZenWeatherText.status(warm,24*60*60_000L,false,timeout).contains("14°م"),"stale forecast must not be presented as current weather");
        ok(ZenWeatherText.status("",0,true,timeout).contains("جاري تحديث"),"Arabic in-flight request");
        ok(ZenWeatherText.retryDelay(1)==60_000L,"first retry");
        ok(ZenWeatherText.retryDelay(2)==3*60_000L,"second retry");
        ok(ZenWeatherText.retryDelay(0)==30*60_000L,"successful refresh interval");
        System.out.println("PASS: Arabic-only weather timeout/DNS/TLS states, WMO codes, offline freshness, bounded retry schedule");
    }
}
