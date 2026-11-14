package com.shen.wordcheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int C_TEXT = 0xFF222222;
    private static final int C_GREY = 0xFF666666;
    private static final int C_RED = 0xFFD32F2F;
    private static final int C_BLUE = 0xFF1565C0;
    private static final double LIMIT_SECS = 3.0;

    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private boolean onHome = true;

    // 当前这一轮复习
    private List<Store.Word> queue = new ArrayList<>();
    private int qi, qPass;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (onHome) super.onBackPressed();
        else showHome();
    }

    @Override
    protected void onDestroy() {
        stopTicker();
        super.onDestroy();
    }

    // ================= 首页 =================

    private void showHome() {
        LinearLayout p = page("单词诊断", true);
        long today = Store.today();
        int due = store.due(today).size();

        text(p, "今天 " + Store.fmt(today) + " · 共 " + store.words.size() + " 个词", 15).setTextColor(C_GREY);
        text(p, "今日待复习：" + due + " 个", 22).setTypeface(Typeface.DEFAULT_BOLD);

        Button start = button(p, "开始复习", v -> startReview());
        start.setEnabled(due > 0);
        button(p, "添加新词", v -> showAdd());
        button(p, "节点统计", v -> showStats());
        button(p, "全部单词", v -> showList());
        button(p, "导出 CSV", v -> exportCsv());

        text(p, "判定标准：看到高亮词后 3 秒内说出意思才算记住；犹豫、半猜都算没记住。\n"
                + "复习节点：学习当天为第 0 天，之后第 1 / 2 / 4 / 7 / 15 / 30 天各复习一次。\n"
                + "节点错过了不补，按已到期的最后一个节点记录。", 14).setTextColor(C_GREY);
    }

    // ================= 复习 =================

    private void startReview() {
        queue = store.due(Store.today());
        qi = 0;
        qPass = 0;
        showCard();
    }

    private void showCard() {
        if (qi >= queue.size()) {
            showDone();
            return;
        }
        final Store.Word w = queue.get(qi);
        final long today = Store.today();
        int node = w.nodeFor(today);
        long late = today - (w.start + Store.NODES[node]);

        LinearLayout p = page("复习 " + (qi + 1) + " / " + queue.size(), false);
        text(p, "第 " + Store.NODES[node] + " 天节点" + (late > 0 ? "（晚了 " + late + " 天）" : ""), 15)
                .setTextColor(C_GREY);

        CharSequence prompt = w.sentence.isEmpty() ? bold(w.word) : highlight(w.sentence, w.word);
        TextView q = text(p, prompt, 22);
        q.setPadding(0, dp(20), 0, dp(20));

        final TextView timer = text(p, "0.0 秒", 30);
        timer.setGravity(Gravity.CENTER);

        final Button reveal = button(p, "显示释义", null);
        final LinearLayout answer = new LinearLayout(this);
        answer.setOrientation(LinearLayout.VERTICAL);
        p.addView(answer);

        final long t0 = SystemClock.elapsedRealtime();
        ticker = new Runnable() {
            @Override
            public void run() {
                double s = (SystemClock.elapsedRealtime() - t0) / 1000.0;
                timer.setText(String.format(Locale.US, "%.1f 秒", s));
                timer.setTextColor(s > LIMIT_SECS ? C_RED : C_TEXT);
                handler.postDelayed(this, 100);
            }
        };
        handler.post(ticker);

        reveal.setOnClickListener(v -> {
            stopTicker();
            final double secs = (SystemClock.elapsedRealtime() - t0) / 1000.0;
            timer.setText(String.format(Locale.US, "%.1f 秒", secs));
            timer.setTextColor(secs > LIMIT_SECS ? C_RED : C_TEXT);
            reveal.setVisibility(View.GONE);

            text(answer, w.word + "：" + w.meaning, 20).setTypeface(Typeface.DEFAULT_BOLD);
            if (secs > LIMIT_SECS) {
                text(answer, "超过 3 秒，按标准应算没记住", 15).setTextColor(C_RED);
            }
            button(answer, "记住了", x -> {
                store.record(w, today, true, secs);
                qPass++;
                qi++;
                showCard();
            });
            button(answer, "没记住", x -> {
                store.record(w, today, false, secs);
                qi++;
                showCard();
            });
        });
    }

    private void showDone() {
        LinearLayout p = page("本轮完成", false);
        text(p, "复习 " + queue.size() + " 个，记住 " + qPass + " 个", 20);
        button(p, "返回首页", v -> showHome());
    }

    // ================= 添加 =================

    private void showAdd() {
        LinearLayout p = page("添加新词", false);
        final EditText word = input(p, "单词", false);
        final EditText meaning = input(p, "释义", false);
        final EditText sentence = input(p, "例句（可选）。变形词用 *星号* 标出，如：She *ran* home.", true);
        final TextView status = text(p, "今天已添加 " + store.addedOn(Store.today()) + " 个", 15);
        status.setTextColor(C_GREY);

        button(p, "保存并继续添加", v -> {
            String a = word.getText().toString().trim();
            String m = meaning.getText().toString().trim();
            String s = sentence.getText().toString().trim();
            if (a.isEmpty() || m.isEmpty()) {
                toast("单词和释义不能为空");
                return;
            }
            store.add(a, m, s);
            word.setText("");
            meaning.setText("");
            sentence.setText("");
            word.requestFocus();
            status.setText("已添加：" + a + "（明天第一次复习）· 今天已添加 "
                    + store.addedOn(Store.today()) + " 个");
        });
        button(p, "返回", v -> showHome());
    }

    // ================= 统计 =================

    private void showStats() {
        LinearLayout p = page("节点统计", false);
        int k = Store.NODES.length;
        int[] n = new int[k], pass = new int[k], fast = new int[k];
        for (Store.Word w : store.words) {
            for (Store.Review r : w.reviews) {
                n[r.node]++;
                if (r.pass) pass[r.node]++;
                if (r.pass && r.secs <= LIMIT_SECS) fast[r.node]++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-6s %4s %8s %10s\n", "节点", "次数", "记住率", "3秒内记住"));
        for (int i = 0; i < k; i++) {
            sb.append(String.format(Locale.US, "第%2d天 %5d %8s %10s\n",
                    Store.NODES[i], n[i], rate(pass[i], n[i]), rate(fast[i], n[i])));
        }
        TextView t = text(p, sb.toString(), 15);
        t.setTypeface(Typeface.MONOSPACE);

        text(p, "怎么看：\n"
                + "· 第 1、2 天就低（比如低于八成）→ 第一次学的时候没记牢\n"
                + "· 第 1、2 天还行，第 7/15/30 天明显下降 → 长期保持的问题\n"
                + "· 每个节点都差不多低 → 先检查判定标准和例句\n"
                + "· 某个节点次数少于 10，先别下结论\n"
                + "· “记住率”和“3秒内记住”差得多，说明判定偏松", 14).setTextColor(C_GREY);
    }

    private static String rate(int a, int b) {
        return b == 0 ? "—" : String.format(Locale.US, "%d%%", Math.round(100.0 * a / b));
    }

    // ================= 全部单词 =================

    private void showList() {
        LinearLayout p = page("全部单词（" + store.words.size() + "）", false);
        text(p, "长按某个词可以删除", 14).setTextColor(C_GREY);
        long today = Store.today();

        List<Store.Word> list = new ArrayList<>(store.words);
        list.sort((a, b) -> Long.compare(b.start, a.start));
        for (final Store.Word w : list) {
            StringBuilder sb = new StringBuilder();
            sb.append(w.word).append(" — ").append(w.meaning).append('\n');
            sb.append("学于 ").append(Store.fmt(w.start)).append(" · ");
            if (w.finished()) {
                sb.append("全部节点已完成");
            } else {
                long due = w.dueDate();
                sb.append("下次：").append(due <= today ? "今天" : Store.fmt(due));
            }
            if (!w.reviews.isEmpty()) {
                sb.append('\n');
                for (Store.Review r : w.reviews) {
                    sb.append(Store.NODES[r.node]).append(r.pass ? "✓ " : "✗ ");
                }
            }
            TextView row = text(p, sb.toString(), 16);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("删除 " + w.word + " 和它的复习记录？")
                        .setPositiveButton("删除", (d, i) -> {
                            store.delete(w);
                            showList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
        }
    }

    // ================= 导出 =================

    private void exportCsv() {
        if (store.reviewCount() == 0) {
            toast("还没有复习记录");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "wordcheck.csv");
        i.putExtra(Intent.EXTRA_TEXT, store.toCsv());
        startActivity(Intent.createChooser(i, "导出 CSV"));
    }

    // ================= 工具 =================

    /** 有 *星号* 就高亮星号之间的部分；否则按单词原形在句子里查找（不区分大小写） */
    private CharSequence highlight(String sentence, String word) {
        if (sentence.contains("*")) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            boolean on = false;
            int from = 0;
            for (int i = 0; i < sentence.length(); i++) {
                char c = sentence.charAt(i);
                if (c == '*') {
                    if (on) mark(sb, from, sb.length());
                    else from = sb.length();
                    on = !on;
                } else {
                    sb.append(c);
                }
            }
            return sb;
        }
        SpannableString ss = new SpannableString(sentence);
        String low = sentence.toLowerCase(Locale.ROOT);
        String key = word.toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return ss;
        int i = low.indexOf(key);
        while (i >= 0) {
            mark(ss, i, i + key.length());
            i = low.indexOf(key, i + key.length());
        }
        return ss;
    }

    private static void mark(android.text.Spannable s, int a, int b) {
        if (a >= b) return;
        s.setSpan(new ForegroundColorSpan(C_BLUE), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private CharSequence bold(String s) {
        SpannableString ss = new SpannableString(s);
        mark(ss, 0, s.length());
        return ss;
    }

    private LinearLayout page(String title, boolean home) {
        stopTicker();
        onHome = home;
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(16), dp(20), dp(24));
        sv.addView(ll);
        setContentView(sv);
        text(ll, title, 24).setTypeface(Typeface.DEFAULT_BOLD);
        return ll;
    }

    private TextView text(LinearLayout parent, CharSequence s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(t, lp);
        return t;
    }

    private Button button(LinearLayout parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(18);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(b, lp);
        return b;
    }

    private EditText input(LinearLayout parent, String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(18);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(2);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(e, lp);
        return e;
    }

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
