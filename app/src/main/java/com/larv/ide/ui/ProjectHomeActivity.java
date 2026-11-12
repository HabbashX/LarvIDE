package com.larv.ide.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.larv.ide.R;
import com.larv.ide.model.Project;
import com.larv.ide.project.ProjectManager;

import java.io.File;
import java.util.List;

public class ProjectHomeActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_PATH = "project_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF141517);

        TextView title = new TextView(this);
        title.setText("Projects");
        title.setTextSize(22);
        title.setTextColor(0xFFBCBEC4);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Pick a project to open, or create a new one.");
        hint.setTextSize(13);
        hint.setTextColor(0xFF808080);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        ProjectManager pm = new ProjectManager(getApplicationContext());
        List<Project> projects = pm.getProjects();

        ListView list = new ListView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        list.setLayoutParams(lp);
        root.addView(list);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1);
        for (Project p : projects) adapter.add(p.getName());
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Project p = projects.get(position);
            Intent i = new Intent(this, com.larv.ide.activity.MainActivity.class);
            i.putExtra(EXTRA_PROJECT_PATH, p.getRootDir().getAbsolutePath());
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        });

        Button create = new Button(this);
        create.setText("New Project");
        create.setOnClickListener(v -> {
            startActivity(new Intent(this,
                com.larv.ide.ui.setup.ProjectCreatorActivity.class));
            finish();
        });
        root.addView(create);

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
