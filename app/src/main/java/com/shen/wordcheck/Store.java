package com.shen.wordcheck;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 数据层：单词、复习记录，存为 App 私有目录下的 words.json */
public class Store {

    /** 复习节点：学习后第几天 */
    public static final int[] NODES = {1, 2, 4, 7, 15, 30};

    public static class Review {
        public int node;      // NODES 的下标
        public long date;     // 实际复习日（epochDay）
        public boolean pass;
        public double secs;   // 从看到题到点“显示释义”的秒数
    }

    public static class Word {
        public long id;
        public String word, meaning, sentence;
        public long start;    // 学习日（epochDay），即第 0 天
        public final List<Review> reviews = new ArrayList<>();

        public int nextNode() {
            return reviews.isEmpty() ? 0 : reviews.get(reviews.size() - 1).node + 1;
        }

        public boolean finished() {
            return nextNode() >= NODES.length;
        }

        public long dueDate() {
            return start + NODES[nextNode()];
        }

        /** 今天复习算哪个节点：如果中间错过了几个节点，算到已到期的最后一个（错过的不补） */
        public int nodeFor(long today) {
            int n = nextNode();
            while (n + 1 < NODES.length && start + NODES[n + 1] <= today) n++;
            return n;
        }
    }

    private final File file;
    public final List<Word> words = new ArrayList<>();

    public Store(Context ctx) {
        file = new File(ctx.getFilesDir(), "words.json");
        load();
    }

    public static long today() {
        return LocalDate.now().toEpochDay();
    }

    public static String fmt(long day) {
        return LocalDate.ofEpochDay(day).toString();
    }

    public List<Word> due(long today) {
        List<Word> out = new ArrayList<>();
        for (Word w : words) {
            if (!w.finished() && w.dueDate() <= today) out.add(w);
        }
        out.sort((a, b) -> Long.compare(a.dueDate(), b.dueDate()));
        return out;
    }

    public int addedOn(long day) {
        int c = 0;
        for (Word w : words) if (w.start == day) c++;
        return c;
    }

    public void add(String word, String meaning, String sentence) {
        Word w = new Word();
        long id = System.currentTimeMillis();
        for (Word o : words) if (o.id >= id) id = o.id + 1;
        w.id = id;
        w.word = word;
        w.meaning = meaning;
        w.sentence = sentence;
        w.start = today();
        words.add(w);
        save();
    }

    public void record(Word w, long today, boolean pass, double secs) {
        Review r = new Review();
        r.node = w.nodeFor(today);
        r.date = today;
        r.pass = pass;
        r.secs = secs;
        w.reviews.add(r);
        save();
    }

    public void delete(Word w) {
        words.remove(w);
        save();
    }

    public int reviewCount() {
        int c = 0;
        for (Word w : words) c += w.reviews.size();
        return c;
    }

    public String toCsv() {
        StringBuilder sb = new StringBuilder(
                "word,meaning,start_date,node_day,due_date,review_date,days_late,pass,seconds\n");
        for (Word w : words) {
            for (Review r : w.reviews) {
                long due = w.start + NODES[r.node];
                sb.append(q(w.word)).append(',')
                  .append(q(w.meaning)).append(',')
                  .append(fmt(w.start)).append(',')
                  .append(NODES[r.node]).append(',')
                  .append(fmt(due)).append(',')
                  .append(fmt(r.date)).append(',')
                  .append(r.date - due).append(',')
                  .append(r.pass ? 1 : 0).append(',')
                  .append(String.format(Locale.US, "%.1f", r.secs)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!file.exists()) return;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Word w = new Word();
                w.id = o.getLong("id");
                w.word = o.getString("word");
                w.meaning = o.getString("meaning");
                w.sentence = o.optString("sentence", "");
                w.start = o.getLong("start");
                JSONArray rs = o.optJSONArray("reviews");
                if (rs != null) {
                    for (int j = 0; j < rs.length(); j++) {
                        JSONObject ro = rs.getJSONObject(j);
                        Review r = new Review();
                        r.node = ro.getInt("node");
                        r.date = ro.getLong("date");
                        r.pass = ro.getBoolean("pass");
                        r.secs = ro.getDouble("secs");
                        w.reviews.add(r);
                    }
                }
                words.add(w);
            }
        } catch (IOException | JSONException e) {
            // 文件损坏时不覆盖它，先改名留底，避免数据被空列表冲掉
            file.renameTo(new File(file.getParent(), "words.broken." + System.currentTimeMillis() + ".json"));
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Word w : words) {
                JSONObject o = new JSONObject();
                o.put("id", w.id);
                o.put("word", w.word);
                o.put("meaning", w.meaning);
                o.put("sentence", w.sentence);
                o.put("start", w.start);
                JSONArray rs = new JSONArray();
                for (Review r : w.reviews) {
                    JSONObject ro = new JSONObject();
                    ro.put("node", r.node);
                    ro.put("date", r.date);
                    ro.put("pass", r.pass);
                    ro.put("secs", r.secs);
                    rs.put(ro);
                }
                o.put("reviews", rs);
                arr.put(o);
            }
            // 先写临时文件再改名，写到一半闪退也不会损坏原文件
            File tmp = new File(file.getParent(), "words.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (IOException | JSONException e) {
            throw new RuntimeException("保存失败", e);
        }
    }
}
