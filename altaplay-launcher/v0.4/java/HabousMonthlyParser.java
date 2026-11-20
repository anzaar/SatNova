package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safely reads the Ministry's monthly JavaScript JSON wrapper, without running JS.
 * Published values are copied verbatim; no astronomical calculations or offsets.
 *
 * Source: https://habous.gov.ma/data/horaires-YYYY-MM.js
 */
final class HabousMonthlyParser {
    private static final Pattern YEAR=Pattern.compile("\"annee\"\\s*:\\s*(\\d{4})");
    private static final Pattern MONTH=Pattern.compile("\"mois\"\\s*:\\s*(\\d{1,2})");
    private static final Pattern CITY=Pattern.compile("\\{\\s*\"c\"\\s*:\\s*(\\d{1,4})\\s*,\\s*\"n\"\\s*:\\s*\"([^\"]{1,90})\"\\s*,\\s*\"h\"\\s*:\\s*\\[");
    private static final Pattern CLOCK=Pattern.compile("(?<!\\d)\\d{2}:\\d{2}(?!\\d)");
    private static final Pattern SIX=Pattern.compile("\\[\\s*\"(\\d{2}:\\d{2})\"\\s*,\\s*\"(\\d{2}:\\d{2})\"\\s*,\\s*\"(\\d{2}:\\d{2})\"\\s*,\\s*\"(\\d{2}:\\d{2})\"\\s*,\\s*\"(\\d{2}:\\d{2})\"\\s*,\\s*\"(\\d{2}:\\d{2})\"\\s*\\]");
    private static final String[] COLUMN_NAMES={"Fajr","Chorouq","Dhuhr","Asr","Maghrib","Ishae"};
    private HabousMonthlyParser(){}

    static final class City {
        final int id;
        final String ar;
        City(int id,String ar){this.id=id;this.ar=ar;}
    }
    static final class Month {
        final YearMonth period;
        final List<City> cities;
        final Map<LocalDate,String[]> times;
        Month(YearMonth period,List<City> cities,Map<LocalDate,String[]> times){
            this.period=period;this.cities=cities;this.times=times;
        }
    }
    private static int one(Pattern p,String s){
        Matcher match=p.matcher(s);
        if(!match.find())throw new IllegalArgumentException("Missing official month metadata");
        return Integer.parseInt(match.group(1));
    }
    private static int bracketEnd(String source,int open){
        if(open<0||open>=source.length()||source.charAt(open)!='[')throw new IllegalArgumentException("Invalid prayer array");
        int depth=0;boolean quoted=false;boolean escaped=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"')quoted=false;
            }else if(c=='"')quoted=true;
            else if(c=='[')depth++;
            else if(c==']'&&--depth==0)return i+1;
        }
        throw new IllegalArgumentException("Unclosed prayer array");
    }

    static Month parse(String js,YearMonth requested,int cityId){
        if(js==null||js.length()<2000||js.length()>900_000||requested==null||cityId<1||cityId>999)
            throw new IllegalArgumentException("Invalid official prayer dataset size or parameters");
        if(!js.startsWith("(window.HORAIRES_MOIS=window.HORAIRES_MOIS||[]).push("))
            throw new IllegalArgumentException("Unexpected official month format");
        if(js.lastIndexOf(')')<=js.indexOf('{'))throw new IllegalArgumentException("No JSON object");
        int year=one(YEAR,js),month=one(MONTH,js);
        YearMonth actual=YearMonth.of(year,month);
        if(!requested.equals(actual))throw new IllegalArgumentException("Incorrect official publication month");
        if(!Pattern.compile("\"fuseau\"\\s*:\\s*\"GMT\"").matcher(js).find())
            throw new IllegalArgumentException("Unexpected official timezone");
        int cols=js.indexOf("\"cols\"");
        int citiesIndex=js.indexOf("\"villes\"");
        if(cols<0||citiesIndex<cols)throw new IllegalArgumentException("Missing official columns/cities");
        String colsText=js.substring(cols,citiesIndex);
        int previous=-1;
        for(String col:COLUMN_NAMES){
            int pos=colsText.indexOf("\""+col+"\"",previous+1);
            if(pos<=previous)throw new IllegalArgumentException("Unexpected prayer column ordering");
            previous=pos;
        }
        Matcher matcher=CITY.matcher(js);
        ArrayList<City> cities=new ArrayList<>();
        Map<LocalDate,String[]> rows=null;
        while(matcher.find()){
            int id=Integer.parseInt(matcher.group(1));
            String name=matcher.group(2).trim();
            if(name.length()<2||id<=0||id>999)throw new IllegalArgumentException("Invalid city record");
            cities.add(new City(id,name));
            int open=matcher.end()-1;
            int end=bracketEnd(js,open);
            if(id==cityId){
                String h=js.substring(open,end);
                Matcher row=SIX.matcher(h);
                LinkedHashMap<LocalDate,String[]> parsed=new LinkedHashMap<>();
                int day=1;
                while(row.find()){
                    String[] times=new String[6];
                    LocalTime last=null;
                    for(int k=0;k<6;k++){
                        String value=row.group(k+1);
                        if(!CLOCK.matcher(value).matches())throw new IllegalArgumentException("Malformed clock");
                        LocalTime clock=LocalTime.parse(value);
                        if(last!=null&&!clock.isAfter(last))throw new IllegalArgumentException("Invalid prayer ordering");
                        times[k]=value;
                        last=clock;
                    }
                    if(day>actual.lengthOfMonth())throw new IllegalArgumentException("Too many published days");
                    parsed.put(actual.atDay(day++),times);
                }
                if(parsed.size()!=actual.lengthOfMonth())
                    throw new IllegalArgumentException("Incomplete Ministry month");
                rows=parsed;
            }
        }
        if(cities.size()<150||rows==null)throw new IllegalArgumentException("Ministry city not found or truncated city list");
        return new Month(actual,Collections.unmodifiableList(cities),Collections.unmodifiableMap(rows));
    }
}
