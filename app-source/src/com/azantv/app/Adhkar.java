package com.azantv.app;

import java.util.ArrayList;
import java.util.List;

/** Remembrances said after the obligatory prayers, shown together in one card after the iqama. */
public final class Adhkar {
    private Adhkar() {}

    /** text, its gold label shown at the end of the line (count or name, may be empty), and a group number. */
    public static final class Item {
        public final String label, text;
        public final int page;
        Item(String label, String text, int page) { this.label = label; this.text = text; this.page = page; }
    }

    public static List<Item> forPrayer(int prayer) {
        List<Item> out = new ArrayList<>();
        out.add(new Item("ثلاث مرات", "أَسْتَغْفِرُ اللَّهَ", 0));
        out.add(new Item("", "اللَّهُمَّ أَنْتَ السَّلَامُ وَمِنْكَ السَّلَامُ، تَبَارَكْتَ يَا ذَا الْجَلَالِ وَالْإِكْرَامِ", 0));
        out.add(new Item("", "لَا إِلَٰهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ وَهُوَ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ، اللَّهُمَّ لَا مَانِعَ لِمَا أَعْطَيْتَ، وَلَا مُعْطِيَ لِمَا مَنَعْتَ، وَلَا يَنْفَعُ ذَا الْجَدِّ مِنْكَ الْجَدُّ", 0));
        out.add(new Item("ثلاثًا وثلاثين مرة لكلٍّ منها", "سُبْحَانَ اللَّهِ • الْحَمْدُ لِلَّهِ • اللَّهُ أَكْبَرُ", 1));
        out.add(new Item("تمام المئة", "لَا إِلَٰهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ وَهُوَ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ", 1));
        if (prayer == PrayTimes.FAJR || prayer == PrayTimes.MAGHRIB) {
            out.add(new Item("عشر مرات", "لَا إِلَٰهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، يُحْيِي وَيُمِيتُ، وَهُوَ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ", 1));
        }
        out.add(new Item("", "اللَّهُمَّ أَعِنِّي عَلَىٰ ذِكْرِكَ وَشُكْرِكَ وَحُسْنِ عِبَادَتِكَ", 0));
        out.add(new Item("آيَةُ الْكُرْسِيِّ", "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ …", 2));
        boolean fm = prayer == PrayTimes.FAJR || prayer == PrayTimes.MAGHRIB;
        out.add(new Item(fm ? "ثلاث مرات" : "مرة واحدة", "ثُمَّ قِرَاءَةُ سُورَةِ الْإِخْلَاصِ  ۝  سُورَةِ الْفَلَقِ  ۝  سُورَةِ النَّاسِ", 3));
        return out;
    }
}
