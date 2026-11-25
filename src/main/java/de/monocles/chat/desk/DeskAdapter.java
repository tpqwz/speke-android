package de.monocles.chat.desk;

import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import eu.siacs.conversations.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The rows of the reading that is on screen. */
public final class DeskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int EMPTY = 100;
    private static final int[] AVATAR = {
        0xFF4AA3FF, 0xFF6DCC8A, 0xFFC678DD, 0xFFE5C07B, 0xFFE06C75, 0xFF56C7D6
    };

    public interface Clicks {
        void onRow(Desk.Row row);

        void onRowLong(Desk.Row row);

        void onReply(Desk.Row row);

        void onFold(Desk.Row row);

        void onOpenBoard(Desk.Row row);

        void onChip(Desk.Row row);
    }

    private final Clicks clicks;
    private final List<Desk.Row> rows = new ArrayList<>();
    private String emptyText = "";
    private boolean empty = true;

    public DeskAdapter(Clicks clicks) {
        this.clicks = clicks;
    }

    public void setRows(List<Desk.Row> next, String emptyText) {
        rows.clear();
        if (next != null) {
            rows.addAll(next);
        }
        this.emptyText = emptyText == null ? "" : emptyText;
        empty = rows.isEmpty();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return empty ? 1 : rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (empty) {
            return EMPTY;
        }
        return rows.get(position).kind.ordinal();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Desk.Row.Kind.THREAD.ordinal()) {
            return new ThreadHolder(inflater.inflate(R.layout.item_desk_thread, parent, false));
        }
        if (viewType == Desk.Row.Kind.POST.ordinal()) {
            return new PostHolder(inflater.inflate(R.layout.item_desk_post, parent, false));
        }
        if (viewType == Desk.Row.Kind.STREAM.ordinal()) {
            return new StreamHolder(inflater.inflate(R.layout.item_desk_stream, parent, false));
        }
        if (viewType == Desk.Row.Kind.DAY.ordinal()) {
            return new DayHolder(inflater.inflate(R.layout.item_desk_day, parent, false));
        }
        return new PlainHolder(inflater.inflate(R.layout.item_desk_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (empty) {
            ((PlainHolder) holder).bindEmpty(emptyText);
            return;
        }
        Desk.Row row = rows.get(position);
        if (holder instanceof ThreadHolder thread) {
            thread.bind(row, clicks);
        } else if (holder instanceof PostHolder post) {
            post.bind(row, clicks);
        } else if (holder instanceof StreamHolder stream) {
            stream.bind(row, clicks);
        } else if (holder instanceof DayHolder day) {
            day.bind(row);
        } else if (holder instanceof PlainHolder plain) {
            plain.bind(row, clicks);
        }
    }

    private static void avatar(TextView view, String author) {
        float radius = 14 * view.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius);
        int hash = author == null ? 0 : author.hashCode();
        shape.setColor(AVATAR[Math.floorMod(hash, AVATAR.length)]);
        view.setBackground(shape);
        view.setText(initials(author));
    }

    private static String initials(String author) {
        if (author == null || author.isBlank()) {
            return "";
        }
        String[] parts = author.trim().split("\\s+");
        String first = parts[0].isEmpty() ? "" : parts[0].substring(0, 1);
        String second = parts.length > 1 && !parts[1].isEmpty() ? parts[1].substring(0, 1) : "";
        return (first + second).toUpperCase(Locale.ROOT);
    }

    private static String replies(View view, int count) {
        return view.getResources().getQuantityString(R.plurals.desk_replies, count, count);
    }

    private static String files(View view, int count) {
        if (count <= 0) {
            return "";
        }
        return " · " + view.getResources().getQuantityString(R.plurals.desk_files_count, count, count);
    }

    private static void margin(View view, int startDp, int topDp, int endDp, int bottomDp) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams margins)) {
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        margins.setMarginStart((int) (startDp * density));
        margins.topMargin = (int) (topDp * density);
        margins.setMarginEnd((int) (endDp * density));
        margins.bottomMargin = (int) (bottomDp * density);
        view.setLayoutParams(margins);
    }

    static final class ThreadHolder extends RecyclerView.ViewHolder {
        private final TextView avatar;
        private final TextView text;
        private final TextView meta;
        private final TextView count;

        ThreadHolder(View view) {
            super(view);
            avatar = view.findViewById(R.id.desk_avatar);
            text = view.findViewById(R.id.desk_row_text);
            meta = view.findViewById(R.id.desk_row_meta);
            count = view.findViewById(R.id.desk_row_count);
        }

        void bind(Desk.Row row, Clicks clicks) {
            avatar(avatar, row.author);
            text.setText(row.text);
            meta.setText(row.author + " · " + row.when + files(itemView, row.fileCount));
            count.setText(
                    row.replies <= 0
                            ? itemView.getContext().getString(R.string.desk_message)
                            : replies(itemView, row.replies));
            itemView.setActivated(row.focused);
            itemView.setOnClickListener(v -> clicks.onRow(row));
            itemView.setOnLongClickListener(v -> false);
        }
    }

    static final class PostHolder extends RecyclerView.ViewHolder {
        private final View rule;
        private final TextView avatar;
        private final TextView author;
        private final TextView when;
        private final TextView body;
        private final View chip;
        private final TextView chipKind;
        private final TextView chipName;
        private final TextView chipDetail;
        private final TextView reply;
        private final TextView fold;

        PostHolder(View view) {
            super(view);
            rule = view.findViewById(R.id.desk_rule);
            avatar = view.findViewById(R.id.desk_avatar);
            author = view.findViewById(R.id.desk_author);
            when = view.findViewById(R.id.desk_when);
            body = view.findViewById(R.id.desk_body);
            chip = view.findViewById(R.id.desk_chip);
            chipKind = view.findViewById(R.id.desk_chip_kind);
            chipName = view.findViewById(R.id.desk_chip_name);
            chipDetail = view.findViewById(R.id.desk_chip_detail);
            reply = view.findViewById(R.id.desk_reply);
            fold = view.findViewById(R.id.desk_fold);
        }

        void bind(Desk.Row row, Clicks clicks) {
            float density = itemView.getResources().getDisplayMetrics().density;
            int start = row.depth == 0 ? 12 : 12 + row.depth * 14;
            margin(itemView, start, row.depth == 0 ? 10 : 0, 12, row.depth == 0 ? 0 : 2);
            if (row.depth == 0) {
                itemView.setBackgroundResource(R.drawable.bg_desk_card);
                itemView.setPadding(
                        (int) (12 * density),
                        (int) (12 * density),
                        (int) (12 * density),
                        (int) (12 * density));
                rule.setVisibility(View.GONE);
            } else {
                itemView.setBackgroundResource(R.drawable.bg_desk_reply);
                itemView.setPadding(0, (int) (8 * density), 0, (int) (4 * density));
                rule.setVisibility(View.GONE);
            }
            itemView.setActivated(row.focused);
            avatar(avatar, row.author);
            author.setText(row.author);
            when.setText(row.when);
            body.setText(row.text);
            body.setVisibility(row.text == null || row.text.isEmpty() ? View.GONE : View.VISIBLE);
            if (row.chip == null) {
                chip.setVisibility(View.GONE);
            } else {
                chip.setVisibility(View.VISIBLE);
                chipKind.setText(row.chipKind == null ? "" : row.chipKind);
                chipName.setText(row.chip);
                if (row.chipDetail == null || row.chipDetail.isEmpty()) {
                    chipDetail.setVisibility(View.GONE);
                } else {
                    chipDetail.setVisibility(View.VISIBLE);
                    chipDetail.setText(row.chipDetail);
                }
                chip.setOnClickListener(v -> clicks.onChip(row));
            }
            reply.setOnClickListener(v -> clicks.onReply(row));
            if (row.replies <= 0) {
                fold.setVisibility(View.GONE);
            } else {
                fold.setVisibility(View.VISIBLE);
                fold.setText(row.collapsed ? replies(itemView, row.replies) : itemView.getContext().getString(R.string.desk_fold));
                fold.setOnClickListener(v -> clicks.onFold(row));
            }
            itemView.setOnClickListener(v -> clicks.onRow(row));
            itemView.setOnLongClickListener(v -> false);
        }
    }

    static final class StreamHolder extends RecyclerView.ViewHolder {
        private final LinearLayout bubble;
        private final View quoteLine;
        private final TextView quote;
        private final TextView sender;
        private final TextView text;
        private final TextView when;
        private String lastId;
        private long lastTap;

        StreamHolder(View view) {
            super(view);
            bubble = view.findViewById(R.id.desk_bubble);
            quoteLine = view.findViewById(R.id.desk_quote_line);
            quote = view.findViewById(R.id.desk_row_quote);
            sender = view.findViewById(R.id.desk_sender);
            text = view.findViewById(R.id.desk_row_text);
            when = view.findViewById(R.id.desk_when);
        }

        void bind(Desk.Row row, Clicks clicks) {
            LinearLayout outer = (LinearLayout) itemView;
            outer.setGravity(row.outgoing ? Gravity.END : Gravity.START);
            bubble.setBackgroundResource(
                    row.outgoing ? R.drawable.bg_desk_bubble_out : R.drawable.bg_desk_bubble_in);
            bubble.setActivated(row.focused);
            if (row.quote == null || row.quote.isEmpty()) {
                quoteLine.setVisibility(View.GONE);
            } else {
                quoteLine.setVisibility(View.VISIBLE);
                quote.setText(row.quote);
            }
            if (row.outgoing) {
                sender.setVisibility(View.GONE);
            } else {
                sender.setVisibility(View.VISIBLE);
                sender.setText(row.author);
            }
            text.setText(row.text);
            when.setText(row.when);
            itemView.setOnClickListener(
                    v -> {
                        long now = SystemClock.uptimeMillis();
                        if (row.id.equals(lastId) && now - lastTap < 400) {
                            lastTap = 0;
                            lastId = null;
                            clicks.onOpenBoard(row);
                        } else {
                            lastTap = now;
                            lastId = row.id;
                        }
                    });
            itemView.setOnLongClickListener(
                    v -> {
                        clicks.onOpenBoard(row);
                        return true;
                    });
        }
    }

    static final class DayHolder extends RecyclerView.ViewHolder {
        private final TextView text;

        DayHolder(View view) {
            super(view);
            text = view.findViewById(R.id.desk_row_text);
        }

        void bind(Desk.Row row) {
            if ("today".equals(row.text)) {
                text.setText(itemView.getContext().getString(R.string.desk_today));
            } else if ("yesterday".equals(row.text)) {
                text.setText(itemView.getContext().getString(R.string.desk_yesterday));
            } else {
                text.setText(row.text);
            }
        }
    }

    static final class PlainHolder extends RecyclerView.ViewHolder {
        private final TextView text;
        private final TextView quote;
        private final TextView folded;

        PlainHolder(View view) {
            super(view);
            text = view.findViewById(R.id.desk_row_text);
            quote = view.findViewById(R.id.desk_row_quote);
            folded = view.findViewById(R.id.desk_row_folded);
        }

        void bindEmpty(String emptyText) {
            itemView.setBackground(null);
            text.setText(emptyText);
            text.setTextColor(itemView.getContext().getColor(R.color.desk_muted));
            text.setGravity(Gravity.CENTER);
            text.setPadding(48, 72, 48, 72);
            quote.setVisibility(View.GONE);
            folded.setVisibility(View.GONE);
            itemView.setOnClickListener(null);
            itemView.setOnLongClickListener(null);
        }

        void bind(Desk.Row row, Clicks clicks) {
            itemView.setBackgroundResource(R.drawable.bg_desk_card);
            margin(itemView, 12, 10, 12, 0);
            text.setText(row.text);
            text.setTextColor(itemView.getContext().getColor(R.color.desk_text));
            text.setGravity(Gravity.START);
            float density = itemView.getResources().getDisplayMetrics().density;
            int pad = (int) (12 * density);
            text.setPadding(pad, pad, pad, pad);
            quote.setVisibility(View.GONE);
            folded.setVisibility(View.GONE);
            itemView.setOnClickListener(v -> clicks.onRow(row));
            itemView.setOnLongClickListener(v -> false);
        }
    }
}
