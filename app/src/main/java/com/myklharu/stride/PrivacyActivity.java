package com.myklharu.stride;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shown by Health Connect when you ask why Stride wants your step data. */
public class PrivacyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        t.setTextSize(16);
        t.setText("How Stride uses your health data\n\n"
                + "Stride reads only your daily step count from Health Connect. It uses it to fill in the Steps tile, "
                + "check your step target and show your step chart.\n\n"
                + "Your steps are stored on this phone, inside the Stride app. They aren't sent to any server, sold or shared.\n\n"
                + "You can turn this off at any time in Health Connect's settings, or by removing Stride's access there.");
        ScrollView s = new ScrollView(this);
        s.addView(t);
        setContentView(s);
        setTitle("Stride and your health data");
    }
}
