package com.gathin.tvhome;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Weather status/condition labels shown to the user MUST stay Moroccan Arabic,
 * independent of the language of network and JSON exception messages. */
final class ZenWeatherText {
    private ZenWeatherText(){}
    static String condition(int code,int day){
        if(code==0)return day==0?"سماء صافية ليلاً":"صحو";
        if(code==1)return day==0?"سماء صافية نسبياً":"غائم جزئياً";
        if(code==2)return "غيوم متفرقة";
        if(code==3)return "غائم";
        if(code==45 || code==48)return "ضباب";
        if(code>=51 && code<=57)return "رذاذ";
        if(code>=61 && code<=67)return "أمطار";
        if(code>=71 && code<=77)return "ثلوج";
        if(code>=80 && code<=82)return "زخات مطر";
        if(code>=85 && code<=86)return "زخات ثلج";
        if(code>=95 && code<=99)return "عواصف رعدية";
        return "طقس متغير";
    }
    static String problem(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof SocketTimeoutException)
                return "تأخر اتصال الطقس · إعادة المحاولة";
            if(e instanceof UnknownHostException || e instanceof ConnectException)
                return "لا يوجد اتصال بخدمة الطقس";
            if(e instanceof SSLException)
                return "تعذر الاتصال الآمن بخدمة الطقس";
        }
        return "الطقس غير متاح مؤقتاً";
    }
    static String status(String latest,long ageMs, boolean fetching,String lastError){
        if(latest!=null&&!latest.isEmpty()){
            if(ageMs>=0 && ageMs<=90*60_000L)return latest;
            if(ageMs>=0 && ageMs<=12*60*60_000L)return latest+" · آخر حالة محفوظة";
        }
        if(fetching)return "جاري تحديث الطقس…";
        if(lastError!=null&&!lastError.isEmpty())return lastError;
        return "جاري تحديث الطقس…";
    }
    static long retryDelay(int failures){
        if(failures<=0)return 30*60_000L;
        if(failures==1)return 60_000L;
        if(failures==2)return 3*60_000L;
        if(failures==3)return 5*60_000L;
        return 10*60_000L;
    }
}
