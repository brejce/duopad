package com.splitkey.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 上屏鼠标光标。窗口铺满全屏且透明，箭头位置由 setPos 控制、invalidate 重绘。
 *
 * 不用 updateViewLayout 移动窗口 —— 那样每帧一次跨进程 IPC，必卡。
 */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private float px = 960, py = 540;

    public CursorView(Context ctx) {
        super(ctx);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(0xFF000000);

        arrow.moveTo(0, 0);
        arrow.lineTo(0, 25);
        arrow.lineTo(6, 19);
        arrow.lineTo(11, 28);
        arrow.lineTo(15, 26);
        arrow.lineTo(10, 17);
        arrow.lineTo(18, 17);
        arrow.close();
    }

    /** 移动箭头（屏幕坐标） */
    public void setPos(float x, float y) {
        px = x;
        py = y;
        invalidate();      // 只重绘本进程的 View，不跨进程
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.translate(px, py);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
        c.restore();
    }
}
