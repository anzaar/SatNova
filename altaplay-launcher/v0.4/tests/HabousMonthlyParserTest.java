package com.gathin.tvhome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.LocalDate;
import java.util.Arrays;
public final class HabousMonthlyParserTest {
    static void check(boolean ok,String detail){
        if(!ok)throw new AssertionError(detail);
    }
    public static void main(String[] argv)throws Exception{
        if(argv.length!=1)throw new IllegalArgumentException("Pass verified official monthly JS dataset path");
        String official=new String(Files.readAllBytes(Path.of(argv[0])),StandardCharsets.UTF_8);
        HabousMonthlyParser.Month nov=HabousMonthlyParser.parse(official,YearMonth.of(2026,11),2);
        check(nov.cities.size()==190,"Must match current official city list: "+nov.cities.size());
        check(nov.cities.stream().anyMatch(c->c.id==2&&"الخميسات".equals(c.ar)),"Khemisset city code");
        check(nov.times.size()==30,"November full day count");
        String[] nov20=nov.times.get(LocalDate.of(2026,11,20));
        check(Arrays.equals(nov20,new String[]{"05:29","06:56","12:15","14:59","17:24","18:40"}),"Khemisset day20 exact official values: "+Arrays.toString(nov20));
        boolean rejected=false;
        try{HabousMonthlyParser.parse(official,YearMonth.of(2026,12),2);}catch(IllegalArgumentException ok){rejected=true;}
        check(rejected,"Must reject mismatched months");
        rejected=false;
        try{HabousMonthlyParser.parse(official,YearMonth.of(2026,11),999);}catch(IllegalArgumentException ok){rejected=true;}
        check(rejected,"Must reject unpublished city code");
        System.out.println("PASS: certified official November dataset, 190 cities, 30 calendar days, city=2 and 20-Nov published values; rejects mismatched dates/cities");
    }
}
