package com.tools.cnc.ui.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;

/**
 * Shows an image with the detected crop rectangle overlaid, plus two draggable
 * "ignore" lines marking the status bar (top) and navigation bar (bottom).
 *
 * <p>Everything the view draws is expressed in <em>bitmap</em> coordinates and
 * converted to view coordinates through a centre-fit rectangle computed in
 * {@link #computeDst()}, so the overlaid geometry always tracks the image.</p>
 *
 * <p>The view lives inside a {@link android.widget.ScrollView}. It only claims
 * the touch stream when the finger lands close to one of the two ignore lines;
 * otherwise it stays transparent to touch and the page scrolls normally.</p>
 */
public class CropPreviewView extends View {

    /** Notified while and after the user drags one of the ignore lines. */
    public interface OnIgnoreLineChangeListener {

        /** Called continuously during a drag, in bitmap pixels. */
        void onIgnoreLineDragging(int topInset, int bottomInset);

        /** Called once the finger is lifted, in bitmap pixels. */
        void onIgnoreLineChanged(int topInset, int bottomInset);
    }

    private static final int COLOR_MASK = 0x73000000;
    private static final int COLOR_CROP = 0xFFE53935;
    private static final int COLOR_IGNORE = 0xFFF9A825;

    /** Upper bound for a single ignore band, as a share of the image height. */
    private static final float MAX_INSET_RATIO = 0.35f;

    private static final int MODE_NONE = 0;
    private static final int MODE_TOP = 1;
    private static final int MODE_BOTTOM = 2;

    @Nullable
    private Bitmap bitmap;
    @Nullable
    private Rect contentBounds;
    private int ignoreTop;
    private int ignoreBottom;

    private final RectF dst = new RectF();

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ignorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float handleRadius;
    private final float touchRadius;
    private final float labelPadding;

    @Nullable
    private OnIgnoreLineChangeListener listener;

    private int dragMode = MODE_NONE;
    private float downY;
    private int downIgnoreTop;
    private int downIgnoreBottom;

    public CropPreviewView(Context context) {
        this(context, null);
    }

    public CropPreviewView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        density = getResources().getDisplayMetrics().density;
        handleRadius = 6f * density;
        // Grab radius is deliberately much larger than the touch slop - the line
        // itself is only a couple of pixels tall.
        touchRadius = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), 26f * density);
        labelPadding = 6f * density;

        maskPaint.setColor(COLOR_MASK);
        maskPaint.setStyle(Paint.Style.FILL);

        cropPaint.setColor(COLOR_CROP);
        cropPaint.setStyle(Paint.Style.STROKE);
        cropPaint.setStrokeWidth(2f * density);

        ignorePaint.setColor(COLOR_IGNORE);
        ignorePaint.setStyle(Paint.Style.STROKE);
        ignorePaint.setStrokeWidth(2f * density);
        ignorePaint.setPathEffect(new DashPathEffect(
                new float[]{6f * density, 4f * density}, 0f));

        handlePaint.setColor(COLOR_IGNORE);
        handlePaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(11f * density);
        labelPaint.setTextAlign(Paint.Align.LEFT);

        labelBgPaint.setColor(COLOR_IGNORE);
        labelBgPaint.setStyle(Paint.Style.FILL);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void setOnIgnoreLineChangeListener(@Nullable OnIgnoreLineChangeListener l) {
        this.listener = l;
    }

    /** Replaces the displayed image and resets both ignore bands to zero. */
    public void setBitmap(@Nullable Bitmap bmp) {
        this.bitmap = bmp;
        this.contentBounds = null;
        this.ignoreTop = 0;
        this.ignoreBottom = 0;
        invalidate();
    }

    @Nullable
    public Bitmap getBitmap() {
        return bitmap;
    }

    /** Sets the crop rectangle in bitmap coordinates. */
    public void setContentBounds(@Nullable Rect bounds) {
        this.contentBounds = (bounds == null) ? null : new Rect(bounds);
        invalidate();
    }

    /** Sets both ignore bands, in bitmap pixels. */
    public void setIgnoreInsets(int top, int bottom) {
        int h = (bitmap == null) ? 0 : bitmap.getHeight();
        int maxInset = Math.max(1, Math.round(h * MAX_INSET_RATIO));

        int safeTop = clamp(top, 0, Math.min(maxInset, Math.max(0, h - 1)));
        int safeBottom = clamp(bottom, 0, Math.min(maxInset, Math.max(0, h - safeTop - 1)));

        if (safeTop == ignoreTop && safeBottom == ignoreBottom) {
            return;
        }
        ignoreTop = safeTop;
        ignoreBottom = safeBottom;
        invalidate();
    }

    public int getIgnoreTop() {
        return ignoreTop;
    }

    public int getIgnoreBottom() {
        return ignoreBottom;
    }

    /** Bitmap width, or 0 while no image is set. */
    public int getBitmapWidth() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    /** Bitmap height, or 0 while no image is set. */
    public int getBitmapHeight() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    // -------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------

    /** Centre-fits the bitmap inside the padded view bounds. */
    private void computeDst() {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            dst.setEmpty();
            return;
        }
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (vw <= 0 || vh <= 0) {
            dst.setEmpty();
            return;
        }
        float scale = Math.min(vw / (float) bitmap.getWidth(), vh / (float) bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float left = getPaddingLeft() + (vw - w) / 2f;
        float top = getPaddingTop() + (vh - h) / 2f;
        dst.set(left, top, left + w, top + h);
    }

    /** Bitmap pixel to view pixel scale factor, or 0 when nothing is drawn. */
    private float displayScale() {
        if (bitmap == null || bitmap.getWidth() <= 0 || dst.isEmpty()) {
            return 0f;
        }
        return dst.width() / bitmap.getWidth();
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeDst();

        if (bitmap == null || bitmap.isRecycled() || dst.isEmpty()) {
            return;
        }

        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);

        float scale = displayScale();
        if (scale <= 0f) {
            return;
        }

        if (contentBounds != null && !contentBounds.isEmpty()) {
            float l = dst.left + contentBounds.left * scale;
            float t = dst.top + contentBounds.top * scale;
            float r = dst.left + contentBounds.right * scale;
            float b = dst.top + contentBounds.bottom * scale;

            canvas.drawRect(dst.left, dst.top, dst.right, t, maskPaint);
            canvas.drawRect(dst.left, b, dst.right, dst.bottom, maskPaint);
            canvas.drawRect(dst.left, t, l, b, maskPaint);
            canvas.drawRect(r, t, dst.right, b, maskPaint);

            canvas.drawRect(l, t, r, b, cropPaint);
        }

        drawIgnoreLine(canvas, dst.top + ignoreTop * scale, ignoreTop, true, scale);
        drawIgnoreLine(canvas, dst.top + (bitmap.getHeight() - ignoreBottom) * scale,
                ignoreBottom, false, scale);
    }

    private void drawIgnoreLine(Canvas canvas, float y, int insetPx, boolean isTop, float scale) {
        if (y < dst.top - 1f || y > dst.bottom + 1f) {
            return;
        }

        canvas.drawLine(dst.left, y, dst.right, y, ignorePaint);
        canvas.drawCircle(dst.left, y, handleRadius, handlePaint);
        canvas.drawCircle(dst.right, y, handleRadius, handlePaint);

        String text = (isTop ? "忽略上 " : "忽略下 ") + insetPx + "px";
        float textWidth = labelPaint.measureText(text);
        float boxWidth = textWidth + labelPadding * 2f;
        float boxHeight = labelPaint.getTextSize() + labelPadding * 1.6f;

        float boxLeft = dst.right - boxWidth;
        float boxTop = isTop ? y - boxHeight - 4f * density : y + 4f * density;

        // Keep the chip inside the view even when the line hugs an edge.
        if (boxTop < 0f) {
            boxTop = y + 4f * density;
        }
        if (boxTop + boxHeight > getHeight()) {
            boxTop = y - boxHeight - 4f * density;
        }
        if (boxLeft < dst.left) {
            boxLeft = dst.left;
        }

        float radius = boxHeight / 2f;
        canvas.drawRoundRect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight,
                radius, radius, labelBgPaint);
        float baseline = boxTop + boxHeight / 2f
                - (labelPaint.descent() + labelPaint.ascent()) / 2f;
        canvas.drawText(text, boxLeft + labelPadding, baseline, labelPaint);
    }

    // -------------------------------------------------------------------------
    // Touch handling
    // -------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        computeDst();
        float scale = displayScale();
        if (scale <= 0f) {
            return false;
        }

        float topLineY = dst.top + ignoreTop * scale;
        float bottomLineY = dst.top + (bitmap.getHeight() - ignoreBottom) * scale;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float y = event.getY();
                float dTop = Math.abs(y - topLineY);
                float dBottom = Math.abs(y - bottomLineY);

                if (dTop <= touchRadius && dTop <= dBottom) {
                    dragMode = MODE_TOP;
                } else if (dBottom <= touchRadius) {
                    dragMode = MODE_BOTTOM;
                } else {
                    dragMode = MODE_NONE;
                }

                if (dragMode == MODE_NONE) {
                    // Let the surrounding ScrollView handle the gesture.
                    return false;
                }

                downY = y;
                downIgnoreTop = ignoreTop;
                downIgnoreBottom = ignoreBottom;
                requestParentDisallowIntercept(true);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (dragMode == MODE_NONE) {
                    return false;
                }
                int deltaPx = Math.round((event.getY() - downY) / scale);
                applyDrag(deltaPx);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragMode == MODE_NONE) {
                    return false;
                }
                dragMode = MODE_NONE;
                requestParentDisallowIntercept(false);
                if (listener != null) {
                    listener.onIgnoreLineChanged(ignoreTop, ignoreBottom);
                }
                return true;
            }

            default:
                return super.onTouchEvent(event);
        }
    }

    private void applyDrag(int deltaPx) {
        if (bitmap == null || dragMode == MODE_NONE) {
            return;
        }
        int height = bitmap.getHeight();
        int maxInset = Math.max(1, Math.round(height * MAX_INSET_RATIO));

        int newTop = ignoreTop;
        int newBottom = ignoreBottom;

        if (dragMode == MODE_TOP) {
            newTop = clamp(downIgnoreTop + deltaPx, 0,
                    Math.min(maxInset, Math.max(0, height - ignoreBottom - 1)));
        } else {
            // Dragging the bottom line downwards grows its inset, hence the minus.
            newBottom = clamp(downIgnoreBottom - deltaPx, 0,
                    Math.min(maxInset, Math.max(0, height - ignoreTop - 1)));
        }

        if (newTop == ignoreTop && newBottom == ignoreBottom) {
            return;
        }

        // Update locally so the drag stays smooth, then tell the host to re-run
        // detection with the new insets.
        ignoreTop = newTop;
        ignoreBottom = newBottom;
        invalidate();

        if (listener != null) {
            listener.onIgnoreLineDragging(ignoreTop, ignoreBottom);
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private static int clamp(int value, int min, int max) {
        if (min > max) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
