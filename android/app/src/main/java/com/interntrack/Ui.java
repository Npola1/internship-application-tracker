package com.interntrack;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class Ui {
    private Ui() { }
    static int dp(Context c, int size) { return (int) (size * c.getResources().getDisplayMetrics().density + 0.5f); }
    static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }
    static int ink() { return Color.rgb(20, 34, 57); }
    static int muted() { return Color.rgb(86, 100, 123); }
    static int blue() { return Color.rgb(37, 73, 216); }
    private static int statusColor(Context c, String status) {
        int resource = switch (status) {
            case "Rejected" -> R.color.status_rejected;
            case "Offer" -> R.color.status_offer;
            case "Interview" -> R.color.status_interview;
            case "Withdrawn" -> R.color.status_withdrawn;
            case "Assessment" -> R.color.status_assessment;
            default -> R.color.primary;
        };
        return c.getColor(resource);
    }
    private static int statusSurface(Context c, String status) {
        int resource = switch (status) {
            case "Rejected" -> R.color.status_rejected_background;
            case "Offer" -> R.color.status_offer_background;
            case "Interview" -> R.color.status_interview_background;
            case "Withdrawn" -> R.color.status_withdrawn_background;
            case "Assessment" -> R.color.status_assessment_background;
            default -> R.color.status_blue_background;
        };
        return c.getColor(resource);
    }
    private static GradientDrawable statusBackground(Context c, String status, int radius) {
        int color = statusColor(c, status);
        GradientDrawable background = new GradientDrawable();
        background.setColor(statusSurface(c, status));
        background.setCornerRadius(dp(c, radius));
        background.setStroke(dp(c, 1), Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)));
        return background;
    }
    static TextView statusBadge(Context c, String status) {
        TextView badge = text(c, status, 12, statusColor(c, status), true);
        badge.setBackground(statusBackground(c, status, 30));
        badge.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        badge.setIncludeFontPadding(false);
        return badge;
    }
    static void styleStatusCounter(TextView counter, String status) {
        Context c = counter.getContext();
        counter.setTextColor(statusColor(c, status));
        counter.setBackground(statusBackground(c, status, 18));
    }
    static ArrayAdapter<String> statusAdapter(Context c, String[] options) {
        return new ArrayAdapter<String>(c, R.layout.spinner_item, options) {
            { setDropDownViewResource(R.layout.spinner_dropdown_item); }
            private View colorRow(View view, int position) {
                TextView label = (TextView) view;
                String status = getItem(position);
                label.setTextColor(statusColor(c, status));
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor("Interview".equals(status) ? c.getColor(R.color.status_interview_accent) : statusColor(c, status));
                dot.setBounds(0, 0, dp(c, 8), dp(c, 8));
                label.setCompoundDrawablesRelative(dot, null, null, null);
                label.setCompoundDrawablePadding(dp(c, 8));
                return label;
            }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return colorRow(super.getView(position, recycled, parent), position);
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                return colorRow(super.getDropDownView(position, recycled, parent), position);
            }
        };
    }
    static ArrayAdapter<String> choiceAdapter(Context c, List<String> options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(c, R.layout.spinner_item, options);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return adapter;
    }
    static void setCounter(TextView counter, int value, String label) {
        String number = Integer.toString(value);
        SpannableString text = new SpannableString(number + "\n" + label);
        text.setSpan(new RelativeSizeSpan(2f), 0, number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new TypefaceSpan("sans-serif-medium"), 0, number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.85f), number.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        counter.setText(text);
    }
    static LinearLayout card(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(c, 18)); bg.setStroke(dp(c, 1), Color.rgb(223, 229, 239));
        root.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(18, 37, 73, 216)), bg, null));
        root.setElevation(dp(c, 1));
        root.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(c, 14); root.setLayoutParams(params);
        return root;
    }
    static ImageView icon(Context c, int resource, int size, int color) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }
    private static View iconTile(Context c, int resource) {
        LinearLayout tile = new LinearLayout(c);
        tile.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(c.getColor(R.color.status_blue_background));
        background.setCornerRadius(dp(c, 12));
        tile.setBackground(background);
        tile.addView(icon(c, resource, 23, blue()));
        return tile;
    }
    static LinearLayout applicationHeader(Context c, String company, String role) {
        String[] words = company.trim().split("\\s+");
        String initials = company.trim().isEmpty() ? "IT" : new String(Character.toChars(words[0].codePointAt(0)));
        if (words.length > 1) initials += new String(Character.toChars(words[words.length - 1].codePointAt(0)));
        TextView avatar = text(c, initials.toUpperCase(Locale.getDefault()), 15, blue(), true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(c.getColor(R.color.status_blue_background));
        background.setCornerRadius(dp(c, 12));
        avatar.setBackground(background);
        return headingRow(c, avatar, company, role);
    }
    static LinearLayout documentHeader(Context c, String name, String subtitle) {
        return headingRow(c, iconTile(c, R.drawable.ic_document), name, subtitle);
    }
    private static LinearLayout headingRow(Context c, View leading, String title, String subtitle) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(leading, new LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)));
        LinearLayout copy = new LinearLayout(c);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(c, 12));
        params.setMarginEnd(dp(c, 8));
        row.addView(copy, params);
        TextView heading = text(c, title, 16, ink(), true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        gap(c, copy, 3);
        TextView detail = text(c, subtitle, 13, muted(), false);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        row.addView(icon(c, R.drawable.ic_chevron_right, 16, muted()));
        return row;
    }
    static LinearLayout metaRow(Context c, int resource, String label, int color) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon(c, resource, 16, color));
        TextView copy = text(c, label, 12, color, false);
        copy.setSingleLine(true);
        copy.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(c, 8));
        row.addView(copy, params);
        return row;
    }
    static void divider(Context c, LinearLayout parent) {
        View line = new View(c);
        line.setBackgroundColor(Color.rgb(237, 241, 247));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(c, 1));
        params.topMargin = dp(c, 12);
        params.bottomMargin = dp(c, 12);
        parent.addView(line, params);
    }
    static LinearLayout emptyState(Context c, String title, String message, int resource) {
        LinearLayout state = card(c);
        state.setGravity(Gravity.CENTER_HORIZONTAL);
        state.setPadding(dp(c, 20), dp(c, 28), dp(c, 20), dp(c, 28));
        state.addView(iconTile(c, resource), new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        gap(c, state, 16);
        TextView heading = text(c, title, 17, ink(), true);
        heading.setGravity(Gravity.CENTER);
        state.addView(heading);
        gap(c, state, 8);
        TextView body = text(c, message, 13, muted(), false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(c, 3), 1f);
        state.addView(body);
        return state;
    }
    static String dateLabel(String value) {
        try { return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())); }
        catch (Exception ex) { return value; }
    }
    static String fileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    static TextView spacer(Context c, String message) {
        TextView text = text(c, message, 14, muted(), false);
        text.setGravity(Gravity.CENTER); text.setPadding(dp(c, 18), dp(c, 36), dp(c, 18), dp(c, 36));
        return text;
    }
    static void gap(Context c, LinearLayout root, int dp) {
        View space = new View(c); root.addView(space, new LinearLayout.LayoutParams(1, Ui.dp(c, dp)));
    }
}
