package at.vystorm.winter.season;

import java.time.*;

/** Pure boundaries for durable reward identities and one-shot New Year celebrations. */
public final class HolidayRules {
    private HolidayRules() { }
    public static String giftId(LocalDate date,String holiday,int adventWeek,MonthDay christmasStart,MonthDay christmasEnd){
        int year=date.getYear();
        if(holiday.equals("NEW_YEAR")&&date.getMonthValue()==12)year++;
        if(holiday.equals("CHRISTMAS")&&christmasStart.compareTo(christmasEnd)>0&&MonthDay.from(date).compareTo(christmasStart)<0)year--;
        return year+"/"+holiday+(holiday.equals("ADVENT")?"/"+adventWeek:"");
    }
    public static boolean crossedNewYear(ZonedDateTime previous,ZonedDateTime now){
        return previous!=null&&!now.isBefore(previous)&&previous.getYear()!=now.getYear()
                &&now.getDayOfYear()==1&&Duration.between(previous,now).getSeconds()<=5;
    }
}
