package com.larv.ide.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.larv.ide.R;

/**
 * One project-view row. Draws the faint vertical indentation guides that make
 * nesting easy to follow, the way IntelliJ's project tree does.
 */
public class FileTreeItemView extends LinearLayout {

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int basePaddingLeft;
    private int indentPx;
    private int depth;

    public FileTreeItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1f);
        guidePaint.setColor(ContextCompat.getColor(context, R.color.divider));
        guidePaint.setAlpha(120);
        indentPx = Math.round(10 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        basePaddingLeft = getPaddingLeft();
    }

    /** Indent the row and draw one guide per ancestor level. */
    public void setDepth(int depth) {
        this.depth = depth;
        setPadding(basePaddingLeft + depth * indentPx,
            getPaddingTop(), getPaddingRight(), getPaddingBottom());
        invalidate();
    }

    public int getDepth() {
        return depth;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int chevronCenter = basePaddingLeft + Math.round(5.5f * getResources().getDisplayMetrics().density);
        for (int level = 0; level < depth; level++) {
            float x = chevronCenter + level * indentPx;
            canvas.drawLine(x, 0, x, getHeight(), guidePaint);
        }
    }
}
