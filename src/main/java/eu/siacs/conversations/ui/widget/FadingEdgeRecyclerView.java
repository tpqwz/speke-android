package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** A list whose content dissolves into its padding rather than sliding under a hard bar edge. */
public class FadingEdgeRecyclerView extends RecyclerView {

    /** THROUGH draws into the padding. DISSOLVE keeps that padding empty and fades the body edge. */
    public enum Shelf {
        THROUGH,
        DISSOLVE
    }

    private EdgeFade edgeFade;
    private Shelf shelf = Shelf.THROUGH;

    public void setShelf(Shelf next) {
        if (next == null || shelf == next) {
            return;
        }
        shelf = next;
        invalidate();
    }

    public FadingEdgeRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public FadingEdgeRecyclerView(@NonNull final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public FadingEdgeRecyclerView(
            @NonNull final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void draw(@NonNull final Canvas canvas) {
        if (edgeFade == null) {
            edgeFade = new EdgeFade(this);
        }
        if (shelf == Shelf.DISSOLVE) {
            edgeFade.drawSettled(canvas, this, super::draw);
            return;
        }
        edgeFade.draw(canvas, this, super::draw);
    }
}
