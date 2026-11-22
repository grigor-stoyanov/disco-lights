package com.grigor.camlink;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pick a role for this phone: the one that films, or the one that remote-controls. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101018"));
        int p = dp(24);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Camera Link");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Put both phones on the same Wi-Fi (or one phone's hotspot).\n"
                + "Pick what this phone does:");
        sub.setTextColor(Color.parseColor("#B0B0C0"));
        sub.setTextSize(16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(12), 0, dp(32));
        root.addView(sub);

        root.addView(bigButton("🎥  This phone FILMS", "#2E7D32", CameraActivity.class));
        root.addView(bigButton("📱  This phone is the REMOTE", "#1565C0", ControllerActivity.class));

        setContentView(root);
    }

    private Button bigButton(String text, String color, final Class<?> target) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(20);
        btn.setAllCaps(false);
        btn.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96));
        lp.bottomMargin = dp(20);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> startActivity(new Intent(this, target)));
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
