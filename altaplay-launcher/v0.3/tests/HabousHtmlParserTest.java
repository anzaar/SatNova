package com.gathin.tvhome;

import java.time.LocalDate;
import java.util.Map;

/** Lightweight no-Android parser regression tests for the exact Habous table schema. */
public final class HabousHtmlParserTest {
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args){
        StringBuilder page=new StringBuilder("<html><h2>جمادى الأولى 1448</h2><p>أكتوبر / نونبر 2026</p>")
            .append("<select name='ville'><option value='horaire_hijri_2.php?ville=1'>الرباط</option>")
            .append("<option value='horaire_hijri_2.php?ville=2' selected='selected'>الخميسات</option></select>")
            .append("<table id='horaire'><tr><td>الأيام</td><td>جمادى الأولى</td>")
            .append("<td>أكتوبر / نونبر</td><td>الصبح</td><td>الشروق</td>")
            .append("<td>الظهر</td><td>العصر</td><td>المغرب</td><td>العشاء</td></tr>");
        LocalDate first=LocalDate.of(2026,10,14);
        for(int i=0;i<30;i++){
            LocalDate date=first.plusDays(i);
            page.append("<tr><td>الجمعة</td><td>").append(i+1)
                .append("</td><td>").append(date.getDayOfMonth())
                .append("</td><td>05:30</td><td>07:00</td><td>12:30</td>")
                .append("<td>15:30</td><td>18:00</td><td>19:30</td></tr>");
        }
        page.append("</table></html>");
        check(HabousHtmlParser.cities(page.toString()).size()==2,"Habous city selector");
        check(HabousHtmlParser.cities(page.toString()).get(1).name.equals("الخميسات"),"Default city ID");
        Map<LocalDate,String[]> result=HabousHtmlParser.schedule(page.toString(),LocalDate.of(2026,11,2));
        check(result.size()==30,"Expected 30 published dates, got "+result.size());
        check(result.containsKey(LocalDate.of(2026,10,31)),"October month");
        check(result.containsKey(LocalDate.of(2026,11,1)),"November month rollover");
        check("05:30".equals(result.get(LocalDate.of(2026,11,2))[0]),"Prayer timing");
        check(HabousHtmlParser.schedule("<html>05:30 07:00</html>",LocalDate.of(2026,11,2)).isEmpty(),"Reject non-ministry table");
        System.out.println("PASS Habous cities, table schema, calendar rollover and rejection");
    }
}
