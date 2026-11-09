package com.interntrack;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PresentationTest {
    @Test public void statusBadgesKeepTheirRequiredDistinctColoursAndLabels() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] statuses={"Rejected","Offer","Interview","Withdrawn","Assessment"};
        for(String status:statuses) {
            var badge=Ui.statusBadge(context,status);
            assertEquals(status,badge.getText().toString());
            int colour=badge.getCurrentTextColor();
            int red=Color.red(colour),green=Color.green(colour),blue=Color.blue(colour);
            switch(status) {
                case "Rejected": assertTrue(red>green&&red>blue); break;
                case "Offer": assertTrue(green>red&&green>blue); break;
                case "Interview":
                case "Assessment": assertTrue(red>blue&&green>blue); break;
                case "Withdrawn": assertTrue(Math.abs(red-green)<50&&Math.abs(green-blue)<50); break;
            }
            assertNotNull(((GradientDrawable)badge.getBackground()).getColor());
        }
    }
    @Test public void datesAndDocumentSizesAreReadable() {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.UK);
            assertEquals("12 Aug 2026",Ui.dateLabel("2026-08-12"));
            assertEquals("invalid",Ui.dateLabel("invalid"));
            assertEquals("",Ui.fileSize(0)); assertEquals("512 B",Ui.fileSize(512));
            assertEquals("1 KB",Ui.fileSize(1024)); assertEquals("1.5 MB",Ui.fileSize(1572864));
        } finally { Locale.setDefault(previous); }
    }
}
