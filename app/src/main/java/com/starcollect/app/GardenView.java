package com.starcollect.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small code-drawn star garden; scales without bitmap assets. */
public final class GardenView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public GardenView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 260f, getHeight() / 210f);
        canvas.save(); canvas.translate((getWidth() - 260 * scale) / 2, (getHeight() - 210 * scale) / 2); canvas.scale(scale, scale);
        paint.setColor(Color.parseColor("#478D77")); canvas.drawCircle(135, 109, 87, paint);
        paint.setColor(Color.parseColor("#69A38A")); canvas.drawOval(29, 173, 245, 210, paint);
        paint.setStrokeWidth(4); paint.setColor(Color.parseColor("#B8D5A9")); canvas.drawLine(121, 157, 121, 188, paint);
        canvas.drawLine(181, 143, 181, 187, paint); canvas.drawLine(72, 148, 72, 184, paint);
        paint.setColor(Color.parseColor("#B8D5A9")); canvas.drawOval(121, 163, 145, 176, paint); canvas.drawOval(50, 159, 72, 171, paint);
        star(canvas, 125, 111, 45, "#FFDE80"); star(canvas, 181, 131, 24, "#F4BE6F"); star(canvas, 70, 132, 27, "#FAE7AC");
        paint.setColor(Color.parseColor("#34594A")); canvas.drawCircle(113, 109, 3, paint); canvas.drawCircle(135, 109, 3, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f); canvas.drawArc(115, 115, 134, 126, 10, 160, false, paint); paint.setStyle(Paint.Style.FILL);
        star(canvas, 56, 50, 7, "#CFE2BA"); star(canvas, 219, 75, 9, "#FFDE80"); star(canvas, 172, 31, 5, "#CFE2BA");
        paint.setColor(Color.parseColor("#B8D5A9")); canvas.drawCircle(31, 93, 3, paint); canvas.drawCircle(210, 38, 3, paint);
        canvas.restore();
    }
    private void star(Canvas canvas, float x, float y, float radius, String color) {
        Path path = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? radius : radius * 0.48f;
            float px = x + (float) Math.cos(angle) * r, py = y + (float) Math.sin(angle) * r;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close(); paint.setColor(Color.parseColor(color)); canvas.drawPath(path, paint);
    }
}
