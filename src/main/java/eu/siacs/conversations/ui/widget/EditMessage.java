package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.util.QuoteHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditMessage extends AppCompatEditText {

    private static final InputFilter SPAN_FILTER =
            (source, start, end, dest, dstart, dend) ->
                    source instanceof Spanned ? source.toString() : source;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected Handler mTypingHandler = new Handler();
    protected KeyboardListener keyboardListener;
    private OnCommitContentListener mCommitContentListener = null;
    private String[] mimeTypes = null;
    private boolean isUserTyping = false;
    private final Runnable mTypingTimeout =
            new Runnable() {
                @Override
                public void run() {
                    if (isUserTyping && keyboardListener != null) {
                        keyboardListener.onTypingStopped();
                        isUserTyping = false;
                    }
                }
            };
    private boolean lastInputWasTab = false;
    private boolean enterConsumed = false;
    private Boolean lastImeEnterIsSend = null;

    public EditMessage(Context context, AttributeSet attrs) {
        super(context, attrs);
        addTextChangedListener(new Watcher());
    }

    public EditMessage(Context context) {
        super(context);
        addTextChangedListener(new Watcher());
    }

    private static boolean isEnterKey(final int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent e) {
        final boolean isCtrlPressed = e.isCtrlPressed();
        if (isEnterKey(keyCode) && !e.isShiftPressed()) {
            lastInputWasTab = false;
            if (e.getRepeatCount() > 0) {
                // auto-repeat of an Enter we already acted on: never send twice (the second
                // press would see an empty composer)
                if (enterConsumed) {
                    return true;
                }
            } else {
                // a fresh press; a key-up lost to a focus change must not leave this set
                enterConsumed =
                        keyboardListener != null
                                && keyboardListener.onEnterPressed(isCtrlPressed);
                if (enterConsumed) {
                    return true;
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_TAB && !e.isAltPressed() && !isCtrlPressed) {
            if (keyboardListener != null && keyboardListener.onTabPressed(this.lastInputWasTab)) {
                lastInputWasTab = true;
                return true;
            }
        } else {
            lastInputWasTab = false;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent e) {
        if (isEnterKey(keyCode) && enterConsumed) {
            // The key-down was handled as "send". Swallow the matching key-up as well, or
            // TextView runs its own Enter handling on it (editor action, focus advance to the
            // next view, click) - which made the composer lose focus after sending.
            enterConsumed = false;
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    @Override
    public int getAutofillType() {
        return AUTOFILL_TYPE_NONE;
    }

    @Override
    public void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        lastInputWasTab = false;
        if (this.mTypingHandler != null && this.keyboardListener != null) {
            executor.execute(() -> triggerKeyboardEvents(text.length()));
        }
    }

    private void triggerKeyboardEvents(final int length) {
        final KeyboardListener listener = this.keyboardListener;
        if (listener == null) {
            return;
        }
        this.mTypingHandler.removeCallbacks(mTypingTimeout);
        this.mTypingHandler.postDelayed(mTypingTimeout, Config.TYPING_TIMEOUT * 1000);
        if (!isUserTyping && length > 0) {
            this.isUserTyping = true;
            listener.onTypingStarted();
        } else if (length == 0) {
            this.isUserTyping = false;
            listener.onTextDeleted();
        }
        listener.onTextChanged();
    }

    public void setKeyboardListener(KeyboardListener listener) {
        this.keyboardListener = listener;
        if (listener != null) {
            this.isUserTyping = false;
        }
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return super.onTextContextMenuItem(android.R.id.pasteAsPlainText);
            } else {
                Editable editable = getEditableText();
                InputFilter[] filters = editable.getFilters();
                InputFilter[] tempFilters =
                        new InputFilter[filters != null ? filters.length + 1 : 1];
                if (filters != null) {
                    System.arraycopy(filters, 0, tempFilters, 1, filters.length);
                }
                tempFilters[0] = SPAN_FILTER;
                editable.setFilters(tempFilters);
                try {
                    return super.onTextContextMenuItem(id);
                } finally {
                    editable.setFilters(filters);
                }
            }
        } else {
            return super.onTextContextMenuItem(id);
        }
    }

    public void setRichContentListener(String[] mimeTypes, OnCommitContentListener listener) {
        this.mimeTypes = mimeTypes;
        this.mCommitContentListener = listener;
    }

    public void insertAsQuote(String text) {
        text = QuoteHelper.quote(text);
        Editable editable = getEditableText();
        int position = getSelectionEnd();
        if (position == -1) position = editable.length();
        if (position > 0 && editable.charAt(position - 1) != '\n') {
            editable.insert(position++, "\n");
        }
        editable.insert(position, text);
        position += text.length();
        editable.insert(position++, "\n");
        if (position < editable.length() && editable.charAt(position) != '\n') {
            editable.insert(position, "\n");
        }
        setSelection(position);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        final InputConnection ic = super.onCreateInputConnection(editorInfo);
        if (imeEnterIsSend()
                && (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION)
                        == EditorInfo.IME_ACTION_SEND) {
            // Tell only the IME the field is single-line, so it shows the Send action instead
            // of a newline key. The view itself stays multi-line: a single-line TextView moves
            // focus to the next view on Enter and collapses the composer to one line.
            editorInfo.inputType &= ~InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            editorInfo.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }

        if (mimeTypes != null && mCommitContentListener != null && ic != null) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes);
            return InputConnectionCompat.createWrapper(
                    ic,
                    editorInfo,
                    (inputContentInfo, flags, opts) ->
                            EditMessage.this.mCommitContentListener.onCommitContent(
                                    inputContentInfo, flags, opts, mimeTypes));
        } else {
            return ic;
        }
    }

    private boolean usingEnterKey() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
        return p.getBoolean(
                "display_enter_key", getResources().getBoolean(R.bool.display_enter_key));
    }

    private boolean imeEnterIsSend() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
        return usingEnterKey()
                && p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send));
    }

    public void refreshIme() {
        // The view always stays multi-line (see onCreateInputConnection). Only the
        // short-message variation (emoji key instead of enter key) is set on the view, and only
        // when it changes: every setInputType() restarts the IME, which can pop up the soft
        // keyboard while a hardware keyboard is in use.
        final int current = getInputType();
        int wanted = current | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if (usingEnterKey()) {
            wanted &= ~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE;
        } else {
            wanted |= InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE;
        }
        final boolean enterIsSend = imeEnterIsSend();
        if (wanted != current) {
            setInputType(wanted); // restarts the IME itself
        } else if (lastImeEnterIsSend != null && lastImeEnterIsSend != enterIsSend) {
            final InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.restartInput(this);
            }
        }
        lastImeEnterIsSend = enterIsSend;
    }

    public interface OnCommitContentListener {
        boolean onCommitContent(
                InputContentInfoCompat inputContentInfo,
                int flags,
                Bundle opts,
                String[] mimeTypes);
    }

    public interface KeyboardListener {
        boolean onEnterPressed(boolean isCtrlPressed);

        void onTypingStarted();

        void onTypingStopped();

        void onTextDeleted();

        void onTextChanged();

        boolean onTabPressed(boolean repeated);
    }

    public static class Watcher implements TextWatcher {
        protected List<ImageSpan> spansToRemove = new ArrayList<>();

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (s instanceof SpannableStringBuilder
                        && ((SpannableStringBuilder) s).getTextWatcherDepth() > 1) return;
            }

            if (!(s instanceof Spannable)) return;
            Spannable text = (Spannable) s;

            if (count > 0 && text != null) { // something deleted
                int end = start + count;
                ImageSpan[] spans = text.getSpans(start, end, ImageSpan.class);
                synchronized (spansToRemove) {
                    for (ImageSpan span : spans) {
                        if (text.getSpanStart(span) < end && start < text.getSpanEnd(span)) {
                            spansToRemove.add(span);
                        }
                    }
                }
            }
        }

        @Override
        public void afterTextChanged(Editable s) {
            List<ImageSpan> toRemove;
            synchronized (spansToRemove) {
                toRemove = new ArrayList<>(spansToRemove);
                spansToRemove.clear();
            }
            for (ImageSpan span : toRemove) {
                if (s.getSpanStart(span) > -1 && s.getSpanEnd(span) > -1) {
                    s.removeSpan(span);
                }
            }
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int count, int after) {}
    }
}
