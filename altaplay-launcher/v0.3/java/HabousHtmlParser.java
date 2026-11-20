package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java, bounded parser for the published Habous monthly prayer table.
 * Does not calculate religious times. Unsupported/old layouts fail closed.
 */
final class HabousHtmlParser {
    private static final Pattern TABLE = Pattern.compile(
        "<table\\b[^>]*\\bid\\s*=\\s*['\"]?horaire['\"]?[^>]*>(.*?)</table>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TR=Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TD=Pattern.compile("<t[dh]\\b[^>]*>(.*?)</t[dh]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPTIONS=Pattern.compile("<option\\b([^>]*)>(.*?)</option>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern CITY_ID=Pattern.compile("(?:[?&]|&amp;)ville=(\\d{1,4})");
    private static final Pattern CLOCK=Pattern.compile("(?:[01]?\\d|2[0-3]):[0-5]\\d");
    private static final Pattern YEAR=Pattern.compile("\\b20\\d{2}\\b");
    private static final String[] MONTHS={"يناير","فبراير","مارس","أبريل","ماي","يونيو","يوليوز",
        "غشت","شتنبر","أكتوبر","نونبر","دجنبر"};
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);

    private HabousHtmlParser(){}

    static final class CityName {
        final int id; final String name;
        CityName(int id,String name){this.id=id;this.name=name;}
    }

    static String clean(String html){
        String s=html.replaceAll("(?is)<[^>]+>"," ");
        s=s.replace("&nbsp;"," ").replace("&#160;"," ").replace("&amp;","&")
            .replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");
        Matcher numeric=Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);").matcher(s);
        StringBuffer buf=new StringBuffer();
        while(numeric.find()){
            try {
                String n=numeric.group(1);
                int cp=n.charAt(0)=='x'?Integer.parseInt(n.substring(1),16):Integer.parseInt(n);
                numeric.appendReplacement(buf,Matcher.quoteReplacement(new String(Character.toChars(cp))));
            }catch(Exception e){numeric.appendReplacement(buf," ");}
        }
        numeric.appendTail(buf);
        return buf.toString().replace('٠','0').replace('١','1').replace('٢','2')
            .replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6')
            .replace('٧','7').replace('٨','8').replace('٩','9').replaceAll("\\s+"," ").trim();
    }

    static List<CityName> cities(String html){
        if(html==null||html.length()>750_000)return Collections.emptyList();
        // Only the ministry's named select, never a menu or arbitrary HTML options.
        Matcher section=Pattern.compile("<select\\b[^>]*\\bname\\s*=\\s*['\"]ville['\"][^>]*>(.*?)</select>",
            Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
        if(!section.find())return Collections.emptyList();
        Matcher m=OPTIONS.matcher(section.group(1));
        Map<Integer,CityName> found=new LinkedHashMap<>();
        while(m.find()){
            Matcher key=CITY_ID.matcher(m.group(1).replace("&amp;","&"));
            if(!key.find())continue;
            int id=Integer.parseInt(key.group(1));
            String name=clean(m.group(2));
            if(id>0&&id<1000&&name.length()>1&&name.length()<80)found.put(id,new CityName(id,name));
        }
        return new ArrayList<>(found.values());
    }

    private static int month(String text){
        String normalized=text.replace("ٱ","").replace("إ","ا").replace("أ","ا");
        if(normalized.contains("يناير")||normalized.toLowerCase(Locale.ROOT).contains("janvier"))return 1;
        if(normalized.contains("فبراير")||normalized.toLowerCase(Locale.ROOT).contains("février"))return 2;
        if(normalized.contains("مارس")||normalized.toLowerCase(Locale.ROOT).contains("mars"))return 3;
        if(normalized.contains("أبريل")||normalized.contains("ابريل")||normalized.toLowerCase(Locale.ROOT).contains("avril"))return 4;
        if(normalized.contains("ماي")||normalized.toLowerCase(Locale.ROOT).contains("mai"))return 5;
        if(normalized.contains("يونيو")||normalized.toLowerCase(Locale.ROOT).contains("juin"))return 6;
        if(normalized.contains("يوليوز")||normalized.toLowerCase(Locale.ROOT).contains("juillet"))return 7;
        if(normalized.contains("غشت")||normalized.toLowerCase(Locale.ROOT).contains("août"))return 8;
        if(normalized.contains("شتنبر")||normalized.contains("سبتمبر")||normalized.toLowerCase(Locale.ROOT).contains("septembre"))return 9;
        if(normalized.contains("أكتوبر")||normalized.contains("اكتوبر")||normalized.toLowerCase(Locale.ROOT).contains("octobre"))return 10;
        if(normalized.contains("نونبر")||normalized.contains("نوفمبر")||normalized.toLowerCase(Locale.ROOT).contains("novembre"))return 11;
        if(normalized.contains("دجنبر")||normalized.contains("ديسمبر")||normalized.toLowerCase(Locale.ROOT).contains("décembre"))return 12;
        return -1;
    }

    static Map<LocalDate,String[]> schedule(String html,LocalDate today){
        if(html==null||today==null||html.length()>750_000)return Collections.emptyMap();
        Matcher found=TABLE.matcher(html);
        if(!found.find())return Collections.emptyMap();
        String table=found.group(1);
        Matcher rows=TR.matcher(table);
        List<String[]> all=new ArrayList<>();
        while(rows.find()){
            Matcher cell=TD.matcher(rows.group(1));
            List<String> cols=new ArrayList<>();
            while(cell.find())cols.add(clean(cell.group(1)));
            if(cols.size()>=3)all.add(cols.toArray(new String[0]));
            if(all.size()>40)return Collections.emptyMap();
        }
        if(all.size()<21)return Collections.emptyMap();
        String header=all.get(0)[2];
        String[] monthLabels=header.split("\\s*/\\s*");
        if(monthLabels.length<1||monthLabels.length>2)return Collections.emptyMap();
        int firstMonth=month(monthLabels[0]);
        int secondMonth=monthLabels.length==2?month(monthLabels[1]):-1;
        if(firstMonth<1||monthLabels.length==2&&secondMonth<1)return Collections.emptyMap();
        int year=today.getYear();
        Matcher yearMatch=YEAR.matcher(html.substring(0,Math.min(found.start(),12000)));
        while(yearMatch.find()) year=Integer.parseInt(yearMatch.group());
        // If an archive is for December/January, start year is the preceding year in January.
        if(firstMonth==12&&today.getMonthValue()==1&&year==today.getYear())year--;
        if(Math.abs(year-today.getYear())>1)return Collections.emptyMap();

        Map<LocalDate,String[]> result=new LinkedHashMap<>();
        int activeMonth=firstMonth,activeYear=year,previousDay=-1;
        for(int i=1;i<all.size();i++){
            String[] row=all.get(i);
            if(row.length<9)continue;
            String dayString=row[2].trim();
            if(!dayString.matches("\\d{1,2}"))continue;
            int day=Integer.parseInt(dayString);
            if(day<1||day>31)continue;
            if(previousDay>=28&&day<previousDay){
                if(secondMonth<1||activeMonth!=firstMonth)return Collections.emptyMap();
                activeMonth=secondMonth;
                if(secondMonth<firstMonth)activeYear++;
            }else if(previousDay>0&&day<=previousDay)return Collections.emptyMap();
            previousDay=day;
            String[] times=new String[6];
            boolean valid=true;LocalTime prev=null;
            for(int j=0;j<6;j++){
                String value=row[j+3].trim();
                if(!CLOCK.matcher(value).matches()){valid=false;break;}
                LocalTime time=LocalTime.parse(value.length()==4?"0"+value:value,TIME);
                if(prev!=null&&!time.isAfter(prev)){valid=false;break;}
                prev=time;times[j]=time.format(TIME);
            }
            if(!valid)continue;
            try{result.put(LocalDate.of(activeYear,activeMonth,day),times);}
            catch(Exception ignored){}
        }
        // Exactly a month of chronological *published* entries, not a calculated timetable.
        return result.size()>=25?result:Collections.emptyMap();
    }
}
