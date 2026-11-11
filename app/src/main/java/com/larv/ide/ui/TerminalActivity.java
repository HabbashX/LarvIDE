package com.larv.ide.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.larv.ide.R;
import com.larv.ide.ui.fragment.TerminalFragment;

import java.io.File;

public class TerminalActivity extends AppCompatActivity {

    public static final String EXTRA_WORKDIR = "workdir";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout container = new FrameLayout(this);
        container.setId(View.generateViewId());
        setContentView(container);

        TerminalFragment fragment = new TerminalFragment();
        getSupportFragmentManager().beginTransaction()
            .replace(container.getId(), fragment).commitNow();

        String workdir = getIntent().getStringExtra(EXTRA_WORKDIR);
        fragment.openSession(workdir == null ? null : new File(workdir));
    }
}
