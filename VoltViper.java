import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VOLT VIPER  -  1 or 2 players, solid or wrap-around walls, 3 difficulties.
 *
 * Menu:     UP/DOWN select an option, LEFT/RIGHT change it, SPACE/ENTER start
 * 1 player: Arrow keys or WASD
 * 2 players: P1 = WASD, P2 = Arrow keys
 * Game:     SPACE/P pause, M mute, ESC back to menu
 */
public class VoltViper extends JPanel implements ActionListener {

    // ---------- constants ----------
    enum State { MENU, PLAYING, PAUSED, GAME_OVER }

    enum ItemType { GOLD, SLOW, SHIELD }

    enum Difficulty {
        EASY(0.17, 0.006, 0.085, 0.6),
        NORMAL(0.14, 0.009, 0.055, 1.0),
        HARD(0.115, 0.011, 0.042, 1.6);

        final double base, step, min, obstacleFactor;

        Difficulty(double base, double step, double min, double obstacleFactor) {
            this.base = base;
            this.step = step;
            this.min = min;
            this.obstacleFactor = obstacleFactor;
        }
    }

    static final int TILE = 28, COLS = 28, ROWS = 20, HUD = 64;
    static final int FIELD_W = TILE * COLS, FIELD_H = TILE * ROWS;
    static final int W = FIELD_W, H = FIELD_H + HUD;

    static final Color CYAN = new Color(0, 255, 210);
    static final Color MAGENTA = new Color(255, 60, 180);
    static final Color GOLD = new Color(255, 210, 60);
    static final Color PURPLE = new Color(170, 110, 255);
    static final Color BLUE = new Color(90, 170, 255);
    static final Color RED = new Color(255, 70, 110);
    static final Color ORANGE = new Color(255, 150, 40);

    static final Path SAVE = Path.of(System.getProperty("user.home"), ".volt_viper_scores.properties");

    // ---------- helper types ----------
    static class Particle {
        double x, y, vx, vy, life, max, size;
        Color c;
    }

    static class FloatText {
        double x, y, life;
        String s;
        Color c;
    }

    static class Bonus {
        Point p;
        ItemType type;
        double life, max;
    }

    static class Player {
        final int id;
        final Color accent;
        final float hue, span;
        List<Point> snake = new ArrayList<>();
        List<Point> prev = new ArrayList<>();
        final Deque<int[]> queue = new ArrayDeque<>();
        int dx = 1, dy = 0, score, displayScore, combo;
        double comboTimer;
        boolean shield, alive;

        Player(int id, Color accent, float hue, float span) {
            this.id = id;
            this.accent = accent;
            this.hue = hue;
            this.span = span;
        }
    }

    // ---------- sound ----------
    static class Sound {
        boolean enabled = true;
        final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sound");
            t.setDaemon(true);
            return t;
        });

        /** notes = pairs of (frequencyHz, durationMs); frequency 0 = rest */
        void play(int... notes) {
            if (!enabled) return;
            ex.submit(() -> {
                try {
                    AudioFormat fmt = new AudioFormat(22050f, 8, 1, true, false);
                    SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
                    line.open(fmt, 4096);
                    line.start();
                    for (int i = 0; i + 1 < notes.length; i += 2) {
                        int f = notes[i], ms = notes[i + 1];
                        int n = (int) (22050 * ms / 1000.0);
                        byte[] buf = new byte[n];
                        for (int k = 0; k < n; k++) {
                            double env = Math.min(1.0, Math.min(k / 200.0, (n - k) / 500.0));
                            double ph = 2 * Math.PI * f * k / 22050.0;
                            double v = f == 0 ? 0 : Math.sin(ph) * 0.7 + Math.sin(ph * 2) * 0.2;
                            buf[k] = (byte) (v * env * 70);
                        }
                        line.write(buf, 0, n);
                    }
                    line.drain();
                    line.close();
                } catch (Throwable ignored) {
                    // no audio device? just stay silent
                }
            });
        }
    }

    // ---------- game state ----------
    private final Random rnd = new Random();
    private final Sound sound = new Sound();
    private final Timer timer;
    private long lastNanos = System.nanoTime();

    private final Player[] players = {
            new Player(1, CYAN, 0.45f, 0.18f),
            new Player(2, ORANGE, 0.12f, -0.10f)
    };

    private State state = State.MENU;
    private final List<Point> obstacles = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<FloatText> texts = new ArrayList<>();

    private Point food = new Point(0, 0);
    private Bonus bonus = null;

    // options (chosen in the menu)
    private boolean twoPlayer = false;
    private boolean wrap = false;
    private Difficulty difficulty = Difficulty.NORMAL;
    private int menuSel = 0;

    private int foodsEaten, level = 1;
    private double slowTimer = 0;
    private int best = 0;
    private boolean newBest = false;
    private int winner = 0; // 0 = draw / none, 1 or 2 in two-player mode

    private final Properties scores = new Properties();

    private double moveAcc = 0;
    private double time = 0;
    private double shake = 0;

    // background stars
    private final double[] sx = new double[80], sy = new double[80], ss = new double[80], sp = new double[80];

    public VoltViper() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);

        for (int i = 0; i < sx.length; i++) {
            sx[i] = rnd.nextDouble() * W;
            sy[i] = rnd.nextDouble() * H;
            ss[i] = 0.5 + rnd.nextDouble() * 2.0;
            sp[i] = rnd.nextDouble() * 6.28;
        }

        try (InputStream in = Files.newInputStream(SAVE)) {
            scores.load(in);
        } catch (Exception ignored) {
        }
        refreshBest();

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e.getKeyCode());
            }
        });

        timer = new Timer(15, this);
        timer.start();
    }

    // ---------- high scores ----------
    private String scoreKey() {
        return difficulty.name() + "_" + (wrap ? "WRAP" : "SOLID");
    }

    private void refreshBest() {
        try {
            best = Integer.parseInt(scores.getProperty(scoreKey(), "0"));
        } catch (Exception e) {
            best = 0;
        }
    }

    private void saveBest() {
        scores.setProperty(scoreKey(), Integer.toString(best));
        try (OutputStream out = Files.newOutputStream(SAVE)) {
            scores.store(out, "Volt Viper high scores");
        } catch (Exception ignored) {
        }
    }

    // ---------- input ----------
    private int activePlayers() {
        return twoPlayer ? 2 : 1;
    }

    private void handleKey(int code) {
        if (state == State.MENU) {
            menuKey(code);
            return;
        }
        int arrows = twoPlayer ? 1 : 0;
        switch (code) {
            case KeyEvent.VK_W -> queueDir(0, 0, -1);
            case KeyEvent.VK_S -> queueDir(0, 0, 1);
            case KeyEvent.VK_A -> queueDir(0, -1, 0);
            case KeyEvent.VK_D -> queueDir(0, 1, 0);
            case KeyEvent.VK_UP -> queueDir(arrows, 0, -1);
            case KeyEvent.VK_DOWN -> queueDir(arrows, 0, 1);
            case KeyEvent.VK_LEFT -> queueDir(arrows, -1, 0);
            case KeyEvent.VK_RIGHT -> queueDir(arrows, 1, 0);
            case KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> {
                if (state == State.GAME_OVER) startGame();
                else if (state == State.PLAYING) state = State.PAUSED;
                else if (state == State.PAUSED) state = State.PLAYING;
            }
            case KeyEvent.VK_P -> {
                if (state == State.PLAYING) state = State.PAUSED;
                else if (state == State.PAUSED) state = State.PLAYING;
            }
            case KeyEvent.VK_M -> sound.enabled = !sound.enabled;
            case KeyEvent.VK_ESCAPE -> {
                state = State.MENU;
                refreshBest();
            }
            default -> {
            }
        }
    }

    private void menuKey(int code) {
        switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> {
                menuSel = (menuSel + 2) % 3;
                sound.play(600, 25);
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                menuSel = (menuSel + 1) % 3;
                sound.play(600, 25);
            }
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> changeOption(-1);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> changeOption(1);
            case KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> startGame();
            case KeyEvent.VK_M -> sound.enabled = !sound.enabled;
            default -> {
            }
        }
    }

    private void changeOption(int dir) {
        switch (menuSel) {
            case 0 -> twoPlayer = !twoPlayer;
            case 1 -> wrap = !wrap;
            default -> {
                Difficulty[] all = Difficulty.values();
                difficulty = all[(difficulty.ordinal() + dir + all.length) % all.length];
            }
        }
        refreshBest();
        sound.play(760, 30, 900, 30);
    }

    private void queueDir(int playerIndex, int ndx, int ndy) {
        if (state != State.PLAYING || playerIndex >= activePlayers()) return;
        Player p = players[playerIndex];
        if (!p.alive) return;
        int[] last = p.queue.peekLast();
        int cx = last != null ? last[0] : p.dx;
        int cy = last != null ? last[1] : p.dy;
        if (cx == -ndx && cy == -ndy) return; // no reversing
        if (cx == ndx && cy == ndy) return;
        if (p.queue.size() < 2) p.queue.add(new int[]{ndx, ndy});
    }

    // ---------- game flow ----------
    private void startGame() {
        for (Player p : players) {
            p.snake = new ArrayList<>();
            p.prev = new ArrayList<>();
            p.queue.clear();
            p.score = 0;
            p.displayScore = 0;
            p.combo = 0;
            p.comboTimer = 0;
            p.shield = false;
            p.alive = false;
        }
        if (twoPlayer) {
            setupPlayer(players[0], 6, ROWS / 2 - 3, 1);
            setupPlayer(players[1], COLS - 7, ROWS / 2 + 3, -1);
        } else {
            setupPlayer(players[0], COLS / 2, ROWS / 2, 1);
        }
        obstacles.clear();
        particles.clear();
        texts.clear();
        foodsEaten = 0;
        level = 1;
        slowTimer = 0;
        bonus = null;
        newBest = false;
        winner = 0;
        moveAcc = 0;
        shake = 0;
        refreshBest();
        spawnFood();
        state = State.PLAYING;
        sound.play(392, 70, 523, 70, 659, 110);
    }

    private void setupPlayer(Player p, int headX, int headY, int dir) {
        p.dx = dir;
        p.dy = 0;
        p.alive = true;
        p.snake.add(new Point(headX, headY));
        p.snake.add(new Point(headX - dir, headY));
        p.snake.add(new Point(headX - 2 * dir, headY));
        p.prev = new ArrayList<>(p.snake);
    }

    private double currentInterval() {
        double base = Math.max(difficulty.min, difficulty.base - (level - 1) * difficulty.step);
        return slowTimer > 0 ? base * 1.7 : base;
    }

    private boolean isFree(int x, int y) {
        if (x < 0 || x >= COLS || y < 0 || y >= ROWS) return false;
        Point p = new Point(x, y);
        if (obstacles.contains(p)) return false;
        for (int i = 0; i < activePlayers(); i++) {
            if (players[i].snake.contains(p)) return false;
        }
        if (food != null && food.equals(p)) return false;
        return bonus == null || !bonus.p.equals(p);
    }

    private void spawnFood() {
        food = null;
        for (int tries = 0; tries < 1000; tries++) {
            int x = rnd.nextInt(COLS), y = rnd.nextInt(ROWS);
            if (isFree(x, y)) {
                food = new Point(x, y);
                return;
            }
        }
        food = new Point(0, 0);
    }

    private void maybeSpawnBonus() {
        if (bonus != null || rnd.nextDouble() > 0.28) return;
        for (int tries = 0; tries < 200; tries++) {
            int x = rnd.nextInt(COLS), y = rnd.nextInt(ROWS);
            if (isFree(x, y)) {
                Bonus b = new Bonus();
                b.p = new Point(x, y);
                double r = rnd.nextDouble();
                b.type = r < 0.45 ? ItemType.GOLD : (r < 0.75 ? ItemType.SLOW : ItemType.SHIELD);
                b.max = b.life = 8.0;
                bonus = b;
                sound.play(880, 40, 1175, 60);
                return;
            }
        }
    }

    private void addObstacles(int count) {
        int placed = 0;
        for (int attempts = 0; attempts < 500 && placed < count; attempts++) {
            int x = 1 + rnd.nextInt(COLS - 2), y = 1 + rnd.nextInt(ROWS - 2);
            int len = 1 + rnd.nextInt(3);
            boolean horiz = rnd.nextBoolean();
            List<Point> blocks = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < len && ok; i++) {
                int bx = x + (horiz ? i : 0), by = y + (horiz ? 0 : i);
                if (!isFree(bx, by)) {
                    ok = false;
                    break;
                }
                // keep a safe zone around every head
                for (int k = 0; k < activePlayers(); k++) {
                    if (!players[k].alive) continue;
                    Point head = players[k].snake.get(0);
                    if (Math.abs(bx - head.x) + Math.abs(by - head.y) < 6) ok = false;
                }
                blocks.add(new Point(bx, by));
            }
            if (ok) {
                obstacles.addAll(blocks);
                placed += blocks.size();
            }
        }
    }

    private void step() {
        int n = activePlayers();

        // read queued input
        for (int i = 0; i < n; i++) {
            Player p = players[i];
            if (p.alive && !p.queue.isEmpty()) {
                int[] d = p.queue.poll();
                p.dx = d[0];
                p.dy = d[1];
            }
        }

        // 1) work out where every head wants to go
        int[] nx = new int[n], ny = new int[n];
        boolean[] ate = new boolean[n], wallHit = new boolean[n], dead = new boolean[n];
        for (int i = 0; i < n; i++) {
            Player p = players[i];
            if (!p.alive) continue;
            Point h = p.snake.get(0);
            nx[i] = h.x + p.dx;
            ny[i] = h.y + p.dy;
            if (nx[i] < 0 || nx[i] >= COLS || ny[i] < 0 || ny[i] >= ROWS) {
                if (wrap) {
                    nx[i] = (nx[i] + COLS) % COLS;
                    ny[i] = (ny[i] + ROWS) % ROWS;
                } else {
                    wallHit[i] = true;
                }
            }
            ate[i] = !wallHit[i] && food.x == nx[i] && food.y == ny[i];
        }

        // 2) collisions
        for (int i = 0; i < n; i++) {
            Player p = players[i];
            if (!p.alive) continue;
            boolean hit = wallHit[i];
            boolean hitObs = false;
            Point np = new Point(nx[i], ny[i]);
            if (!wallHit[i]) {
                if (obstacles.contains(np)) {
                    hit = true;
                    hitObs = true;
                }
                int limit = ate[i] ? p.snake.size() : p.snake.size() - 1;
                for (int k = 0; k < limit; k++) {
                    if (p.snake.get(k).equals(np)) hit = true;
                }
                for (int j = 0; j < n; j++) {
                    if (j == i || !players[j].alive) continue;
                    Player o = players[j];
                    int oLimit = ate[j] ? o.snake.size() : o.snake.size() - 1;
                    for (int k = 0; k < oLimit; k++) {
                        if (o.snake.get(k).equals(np)) hit = true;
                    }
                    if (!wallHit[j] && nx[j] == nx[i] && ny[j] == ny[i]) hit = true; // head-on
                }
            }

            if (hit && p.shield) {
                p.shield = false;
                double px = (p.snake.get(0).x + 0.5) * TILE, py = (p.snake.get(0).y + 0.5) * TILE;
                burst(px, py, PURPLE, 40, 260);
                addText(px, py, "SHIELD BROKEN", PURPLE);
                sound.play(300, 60, 200, 100);
                shake = Math.max(shake, 0.5);
                if (wallHit[i]) {
                    nx[i] = (nx[i] + COLS) % COLS;
                    ny[i] = (ny[i] + ROWS) % ROWS;
                    wallHit[i] = false;
                }
                if (hitObs) obstacles.remove(new Point(nx[i], ny[i]));
                ate[i] = food.x == nx[i] && food.y == ny[i];
            } else if (hit) {
                dead[i] = true;
            }
        }

        // 3) move everyone who survived
        boolean anyDead = false;
        for (int i = 0; i < n; i++) {
            Player p = players[i];
            if (!p.alive) continue;
            if (dead[i]) {
                anyDead = true;
                continue;
            }
            p.prev = new ArrayList<>(p.snake);
            p.snake.add(0, new Point(nx[i], ny[i]));
            if (ate[i]) {
                eatFood(p, nx[i], ny[i]);
            } else {
                p.snake.remove(p.snake.size() - 1);
            }
            if (bonus != null && bonus.p.x == nx[i] && bonus.p.y == ny[i]) collectBonus(p);
        }

        if (anyDead) {
            for (int i = 0; i < n; i++) if (dead[i]) players[i].alive = false;
            endGame();
        }
    }

    private void eatFood(Player p, int x, int y) {
        foodsEaten++;
        p.combo = p.comboTimer > 0 ? Math.min(5, p.combo + 1) : 1;
        p.comboTimer = 3.0;
        int pts = 10 * p.combo;
        p.score += pts;
        double px = (x + 0.5) * TILE, py = (y + 0.5) * TILE;
        burst(px, py, RED, 22, 200);
        addText(px, py - 10, "+" + pts + (p.combo > 1 ? "  x" + p.combo : ""),
                p.combo > 1 ? (twoPlayer ? p.accent : MAGENTA) : Color.WHITE);
        sound.play(520 + p.combo * 60, 45, 780 + p.combo * 80, 70);
        checkBest(p);

        if (foodsEaten % 5 == 0) {
            level++;
            addText(FIELD_W / 2.0, FIELD_H / 2.0, "LEVEL " + level, CYAN);
            burst(FIELD_W / 2.0, FIELD_H / 2.0, CYAN, 60, 320);
            int base = Math.min(4, 2 + level / 2);
            addObstacles(Math.max(1, (int) Math.round(base * difficulty.obstacleFactor)));
            sound.play(523, 70, 659, 70, 784, 70, 1047, 150);
        }
        spawnFood();
        maybeSpawnBonus();
    }

    private void checkBest(Player p) {
        if (!twoPlayer && p.score > best) {
            best = p.score;
            newBest = true;
        }
    }

    private void collectBonus(Player p) {
        double px = (bonus.p.x + 0.5) * TILE, py = (bonus.p.y + 0.5) * TILE;
        switch (bonus.type) {
            case GOLD -> {
                int pts = 50 * Math.max(1, p.combo);
                p.score += pts;
                checkBest(p);
                burst(px, py, GOLD, 40, 280);
                addText(px, py, "+" + pts + " GOLD!", GOLD);
                sound.play(988, 50, 1319, 50, 1568, 120);
            }
            case SLOW -> {
                slowTimer = 6.0;
                burst(px, py, BLUE, 40, 240);
                addText(px, py, "SLOW-MO", BLUE);
                sound.play(660, 80, 440, 140);
            }
            case SHIELD -> {
                p.shield = true;
                burst(px, py, PURPLE, 40, 240);
                addText(px, py, "SHIELD UP", PURPLE);
                sound.play(440, 60, 554, 60, 659, 120);
            }
        }
        bonus = null;
    }

    private void endGame() {
        state = State.GAME_OVER;
        shake = 1.0;
        for (int i = 0; i < activePlayers(); i++) {
            Player p = players[i];
            if (p.alive) continue;
            for (Point pt : p.snake) {
                burst((pt.x + 0.5) * TILE, (pt.y + 0.5) * TILE, p.accent, 4, 220);
            }
        }
        sound.play(330, 120, 262, 120, 196, 120, 130, 300);

        if (twoPlayer) {
            boolean a1 = players[0].alive, a2 = players[1].alive;
            if (a1 && !a2) winner = 1;
            else if (a2 && !a1) winner = 2;
            else if (players[0].score > players[1].score) winner = 1;
            else if (players[1].score > players[0].score) winner = 2;
            else winner = 0;
        } else if (newBest) {
            saveBest();
        }
    }

    // ---------- fx ----------
    private void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            double v = speed * (0.25 + rnd.nextDouble() * 0.75);
            p.x = x;
            p.y = y;
            p.vx = Math.cos(a) * v;
            p.vy = Math.sin(a) * v;
            p.max = p.life = 0.4 + rnd.nextDouble() * 0.6;
            p.size = 2 + rnd.nextDouble() * 4;
            p.c = c;
            particles.add(p);
        }
    }

    private void addText(double x, double y, String s, Color c) {
        FloatText t = new FloatText();
        t.x = x;
        t.y = y;
        t.s = s;
        t.c = c;
        t.life = 1.2;
        texts.add(t);
    }

    // ---------- update loop ----------
    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1e9);
        lastNanos = now;
        update(dt);
        repaint();
        Toolkit.getDefaultToolkit().sync();
    }

    private void update(double dt) {
        time += dt;

        for (int i = 0; i < sx.length; i++) {
            sy[i] += ss[i] * 12 * dt;
            if (sy[i] > H) {
                sy[i] = 0;
                sx[i] = rnd.nextDouble() * W;
            }
        }

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 0.96;
            p.vy *= 0.96;
        }
        for (Iterator<FloatText> it = texts.iterator(); it.hasNext(); ) {
            FloatText t = it.next();
            t.life -= dt;
            t.y -= 30 * dt;
            if (t.life <= 0) it.remove();
        }

        shake = Math.max(0, shake - dt * 2.2);
        for (Player p : players) {
            if (p.displayScore < p.score) {
                p.displayScore = Math.min(p.score, p.displayScore + Math.max(1, (p.score - p.displayScore) / 6));
            }
        }

        if (state != State.PLAYING) return;

        for (int i = 0; i < activePlayers(); i++) {
            Player p = players[i];
            if (p.comboTimer > 0) {
                p.comboTimer -= dt;
                if (p.comboTimer <= 0) p.combo = 0;
            }
        }
        if (slowTimer > 0) slowTimer = Math.max(0, slowTimer - dt);
        if (bonus != null) {
            bonus.life -= dt;
            if (bonus.life <= 0) bonus = null;
        }

        double interval = currentInterval();
        moveAcc += dt;
        while (moveAcc >= interval && state == State.PLAYING) {
            moveAcc -= interval;
            step();
        }

        // sparkle trail behind each tail
        if (state == State.PLAYING) {
            for (int i = 0; i < activePlayers(); i++) {
                Player pl = players[i];
                if (!pl.alive || pl.snake.isEmpty() || rnd.nextDouble() > 0.35) continue;
                Point tail = pl.snake.get(pl.snake.size() - 1);
                Particle p = new Particle();
                p.x = (tail.x + 0.5) * TILE + rnd.nextGaussian() * 4;
                p.y = (tail.y + 0.5) * TILE + rnd.nextGaussian() * 4;
                p.vx = rnd.nextGaussian() * 12;
                p.vy = rnd.nextGaussian() * 12;
                p.max = p.life = 0.5;
                p.size = 2;
                p.c = pl.shield ? PURPLE : pl.accent;
                particles.add(p);
            }
        }
    }

    // ---------- rendering ----------
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        drawBackground(g);

        double shx = (rnd.nextDouble() - 0.5) * shake * 14;
        double shy = (rnd.nextDouble() - 0.5) * shake * 14;
        Graphics2D f = (Graphics2D) g.create();
        f.translate(shx, HUD + shy);
        drawField(f);
        f.dispose();

        drawHud(g);

        if (state == State.MENU) {
            drawMenu(g);
        } else if (state == State.PAUSED) {
            drawCenterOverlay(g, "PAUSED", CYAN, "Press SPACE to resume", null);
        } else if (state == State.GAME_OVER) {
            if (twoPlayer) {
                String title = winner == 0 ? "DRAW" : "PLAYER " + winner + " WINS";
                Color tc = winner == 0 ? Color.WHITE : players[winner - 1].accent;
                drawCenterOverlay(g, title, tc, "P1  " + players[0].score + "     P2  " + players[1].score,
                        "SPACE = rematch   -   ESC = menu");
            } else {
                String sub = "Score " + players[0].score + (newBest ? "   -   NEW BEST!" : "   -   Best " + best);
                drawCenterOverlay(g, "GAME OVER", RED, sub, "SPACE = play again   -   ESC = menu");
            }
        }
        g.dispose();
    }

    private void drawBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(8, 10, 26), 0, H, new Color(28, 10, 44)));
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < sx.length; i++) {
            int a = (int) (70 + 90 * (0.5 + 0.5 * Math.sin(time * 1.5 + sp[i])));
            g.setColor(new Color(200, 220, 255, a));
            double r = ss[i] * 0.9;
            g.fill(new Ellipse2D.Double(sx[i], sy[i], r, r));
        }
    }

    private void drawField(Graphics2D g) {
        // field backdrop
        g.setColor(new Color(6, 8, 18, 200));
        g.fillRect(0, 0, FIELD_W, FIELD_H);

        // pulsing grid
        int ga = (int) (22 + 12 * Math.sin(time * 2));
        g.setColor(new Color(90, 130, 200, ga));
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= COLS; i++) g.drawLine(i * TILE, 0, i * TILE, FIELD_H);
        for (int i = 0; i <= ROWS; i++) g.drawLine(0, i * TILE, FIELD_W, i * TILE);

        if (state != State.MENU) {
            // obstacles
            for (Point o : obstacles) {
                double x = o.x * TILE, y = o.y * TILE;
                glow(g, x + TILE / 2.0, y + TILE / 2.0, TILE * 0.9, MAGENTA, 50);
                RoundRectangle2D r = new RoundRectangle2D.Double(x + 2, y + 2, TILE - 4, TILE - 4, 8, 8);
                g.setColor(new Color(40, 22, 60));
                g.fill(r);
                g.setColor(new Color(255, 60, 180, 190));
                g.setStroke(new BasicStroke(2f));
                g.draw(r);
            }

            // food
            if (food != null) {
                double cx = (food.x + 0.5) * TILE, cy = (food.y + 0.5) * TILE;
                double pulse = 1 + 0.12 * Math.sin(time * 6);
                glow(g, cx, cy, TILE * 1.3 * pulse, RED, 130);
                double r = TILE * 0.32 * pulse;
                g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - r * 0.3, cy - r * 0.3), (float) (r * 1.4),
                        new float[]{0f, 1f}, new Color[]{new Color(255, 190, 200), RED}));
                g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            }

            if (bonus != null) drawBonus(g);

            for (int i = 0; i < activePlayers(); i++) {
                if (!players[i].snake.isEmpty()) drawSnake(g, players[i]);
            }
        }

        // particles
        for (Particle p : particles) {
            float a = (float) Math.max(0, Math.min(1, p.life / p.max));
            g.setColor(new Color(p.c.getRed(), p.c.getGreen(), p.c.getBlue(), (int) (a * 255)));
            double s = p.size * (0.4 + 0.6 * a);
            g.fill(new Ellipse2D.Double(p.x - s / 2, p.y - s / 2, s, s));
        }

        // floating texts
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        for (FloatText t : texts) {
            float a = (float) Math.max(0, Math.min(1, t.life / 0.6));
            FontMetrics fm = g.getFontMetrics();
            int tx = (int) (t.x - fm.stringWidth(t.s) / 2.0);
            g.setColor(new Color(0, 0, 0, (int) (a * 160)));
            g.drawString(t.s, tx + 2, (int) t.y + 2);
            g.setColor(new Color(t.c.getRed(), t.c.getGreen(), t.c.getBlue(), (int) (a * 255)));
            g.drawString(t.s, tx, (int) t.y);
        }

        // neon border: solid for walls that kill, dashed for wrap-around
        Color bc = wrap ? BLUE : CYAN;
        g.setStroke(new BasicStroke(9f));
        g.setColor(new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 28));
        g.drawRect(0, 0, FIELD_W, FIELD_H);
        if (wrap) {
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{10f, 8f}, (float) (time * 20)));
        } else {
            g.setStroke(new BasicStroke(2f));
        }
        g.setColor(new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 170));
        g.drawRect(0, 0, FIELD_W, FIELD_H);
    }

    private Point2D.Double segPos(Player pl, int i, double t) {
        Point c = pl.snake.get(i);
        Point p = pl.prev.isEmpty() ? c : pl.prev.get(Math.min(i, pl.prev.size() - 1));
        double px = p.x, py = p.y;
        if (Math.abs(c.x - p.x) > 1 || Math.abs(c.y - p.y) > 1) { // wrapped: no sliding across the board
            px = c.x;
            py = c.y;
        }
        return new Point2D.Double((px + (c.x - px) * t) * TILE + TILE / 2.0,
                (py + (c.y - py) * t) * TILE + TILE / 2.0);
    }

    private void drawSnake(Graphics2D g, Player pl) {
        double t = state == State.PLAYING || state == State.PAUSED
                ? Math.min(1.0, moveAcc / currentInterval()) : 1.0;
        t = t * t * (3 - 2 * t); // smoothstep
        boolean dead = !pl.alive;
        int n = pl.snake.size();

        for (int i = n - 1; i >= 0; i--) {
            Point2D.Double p = segPos(pl, i, t);
            float frac = n <= 1 ? 0 : i / (float) (n - 1);
            Color c = dead ? new Color(110, 120, 130)
                    : Color.getHSBColor(pl.hue + pl.span * frac, 0.85f, 1f);
            if (slowTimer > 0 && !dead) c = Color.getHSBColor(0.58f + 0.08f * frac, 0.75f, 1f);

            glow(g, p.x, p.y, TILE * 0.95, pl.shield && !dead ? PURPLE : c, i == 0 ? 90 : 45);

            double size = TILE - 3 - 6 * frac;
            RoundRectangle2D body = new RoundRectangle2D.Double(p.x - size / 2, p.y - size / 2, size, size,
                    size * 0.55, size * 0.55);
            g.setPaint(new GradientPaint((float) (p.x - size / 2), (float) (p.y - size / 2), c.brighter(),
                    (float) (p.x + size / 2), (float) (p.y + size / 2), c.darker()));
            g.fill(body);
            if (pl.shield && !dead) {
                g.setColor(new Color(170, 110, 255, 200));
                g.setStroke(new BasicStroke(2f));
                g.draw(body);
            }
        }

        // eyes
        Point2D.Double h = segPos(pl, 0, t);
        int dx = pl.dx, dy = pl.dy;
        double f = 5, s = 5;
        double e1x = h.x + dx * f - dy * s, e1y = h.y + dy * f + dx * s;
        double e2x = h.x + dx * f + dy * s, e2y = h.y + dy * f - dx * s;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(e1x - 3.4, e1y - 3.4, 6.8, 6.8));
        g.fill(new Ellipse2D.Double(e2x - 3.4, e2y - 3.4, 6.8, 6.8));
        g.setColor(dead ? new Color(200, 40, 40) : new Color(10, 10, 30));
        g.fill(new Ellipse2D.Double(e1x + dx * 1.2 - 1.6, e1y + dy * 1.2 - 1.6, 3.2, 3.2));
        g.fill(new Ellipse2D.Double(e2x + dx * 1.2 - 1.6, e2y + dy * 1.2 - 1.6, 3.2, 3.2));

        // player tag in two-player mode
        if (twoPlayer && !dead) {
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(pl.accent);
            String tag = "P" + pl.id;
            g.drawString(tag, (float) (h.x - g.getFontMetrics().stringWidth(tag) / 2.0), (float) (h.y - TILE * 0.7));
        }
    }

    private void drawBonus(Graphics2D g) {
        double cx = (bonus.p.x + 0.5) * TILE, cy = (bonus.p.y + 0.5) * TILE;
        Color c = bonus.type == ItemType.GOLD ? GOLD : bonus.type == ItemType.SLOW ? BLUE : PURPLE;
        cy += Math.sin(time * 5) * 1.5;

        glow(g, cx, cy, TILE * 1.4, c, 140);

        // countdown ring
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
        g.draw(new Arc2D.Double(cx - TILE * 0.5, cy - TILE * 0.5, TILE, TILE, 90, -360 * bonus.life / bonus.max,
                Arc2D.OPEN));

        double r = TILE * 0.28;
        Path2D shape = new Path2D.Double();
        switch (bonus.type) {
            case GOLD -> { // diamond
                shape.moveTo(cx, cy - r * 1.3);
                shape.lineTo(cx + r, cy);
                shape.lineTo(cx, cy + r * 1.3);
                shape.lineTo(cx - r, cy);
                shape.closePath();
            }
            case SLOW -> shape.append(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2), false);
            case SHIELD -> { // hexagon
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI / 3 * i - Math.PI / 2;
                    double px = cx + Math.cos(a) * r * 1.2, py = cy + Math.sin(a) * r * 1.2;
                    if (i == 0) shape.moveTo(px, py);
                    else shape.lineTo(px, py);
                }
                shape.closePath();
            }
        }
        g.setColor(c);
        g.fill(shape);
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(shape);
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(10, 12, 30, 230));
        g.fillRect(0, 0, W, HUD);
        g.setColor(new Color(0, 255, 210, 90));
        g.fillRect(0, HUD - 2, W, 2);

        Player p1 = players[0], p2 = players[1];

        if (!twoPlayer) {
            stat(g, "SCORE", String.valueOf(p1.displayScore), 22, Color.WHITE, p1.shield);
            stat(g, "LEVEL", String.valueOf(level), 210, CYAN, false);
            stat(g, "BEST", String.valueOf(best), 330, GOLD, false);

            int cx = 470;
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(150, 160, 190));
            g.drawString("COMBO", cx, 22);
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            if (p1.combo > 1) {
                g.setColor(MAGENTA);
                g.drawString("x" + p1.combo, cx, 52);
                g.setColor(new Color(255, 60, 180, 120));
                g.fillRoundRect(cx + 54, 40, (int) (50 * Math.max(0, p1.comboTimer) / 3.0), 8, 8, 8);
            } else {
                g.setColor(new Color(90, 95, 120));
                g.drawString("-", cx, 52);
            }
        } else {
            stat(g, "P1 SCORE", String.valueOf(p1.displayScore), 22, CYAN, p1.shield);
            stat(g, "P2 SCORE", String.valueOf(p2.displayScore), 190, ORANGE, p2.shield);
            stat(g, "LEVEL", String.valueOf(level), 360, Color.WHITE, false);

            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(150, 160, 190));
            g.drawString("COMBO", 470, 22);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.setColor(p1.combo > 1 ? CYAN : new Color(90, 95, 120));
            g.drawString(p1.combo > 1 ? "x" + p1.combo : "-", 470, 50);
            g.setColor(p2.combo > 1 ? ORANGE : new Color(90, 95, 120));
            g.drawString(p2.combo > 1 ? "x" + p2.combo : "-", 520, 50);
        }

        // slow-mo indicator
        int ex = 600;
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(150, 160, 190));
        g.drawString("EFFECTS", ex, 22);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        if (slowTimer > 0) {
            g.setColor(BLUE);
            g.drawString("SLOW", ex, 44);
            g.fillRoundRect(ex + 38, 35, (int) (30 * slowTimer / 6.0), 8, 6, 6);
        } else {
            g.setColor(new Color(90, 95, 120));
            g.drawString("none", ex, 44);
        }

        // mode + sound info
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.setColor(new Color(120, 130, 160));
        String mode = difficulty.name() + " - " + (wrap ? "WRAP" : "SOLID");
        g.drawString(mode, W - 10 - g.getFontMetrics().stringWidth(mode), 14);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        String snd = sound.enabled ? "[M] sound on" : "[M] sound off";
        g.drawString(snd, W - 10 - g.getFontMetrics().stringWidth(snd), 56);
    }

    private void stat(Graphics2D g, String label, String value, int x, Color c, boolean shield) {
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(150, 160, 190));
        g.drawString(label, x, 22);
        if (shield) {
            g.setColor(PURPLE);
            g.drawString("SHIELD", x + g.getFontMetrics().stringWidth(label) + 8, 22);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.setColor(c);
        g.drawString(value, x, 52);
    }

    private void drawMenu(Graphics2D g) {
        g.setColor(new Color(4, 6, 16, 200));
        g.fillRect(0, HUD, W, FIELD_H);

        // decorative sine snake
        for (int i = 0; i < 26; i++) {
            double x = W / 2.0 - 260 + i * 20;
            double y = HUD + 400 + Math.sin(time * 3 - i * 0.45) * 20;
            Color c = Color.getHSBColor(0.45f + 0.18f * (i / 26f), 0.85f, 1f);
            glow(g, x, y, 20, c, 90);
            double r = 9 - i * 0.12;
            g.setColor(c);
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }

        neonText(g, "VOLT VIPER", new Font("SansSerif", Font.BOLD, 68), HUD + 105, CYAN);

        String[] labels = {"MODE", "WALLS", "DIFFICULTY"};
        String[] values = {twoPlayer ? "2 PLAYERS" : "1 PLAYER", wrap ? "WRAP AROUND" : "SOLID", difficulty.name()};
        Color[] valueColors = {twoPlayer ? ORANGE : CYAN, wrap ? BLUE : CYAN,
                difficulty == Difficulty.EASY ? new Color(110, 230, 140)
                        : difficulty == Difficulty.NORMAL ? GOLD : RED};
        for (int i = 0; i < 3; i++) {
            int y = HUD + 190 + i * 46;
            boolean sel = i == menuSel;
            if (sel) {
                g.setColor(new Color(0, 255, 210, 26));
                g.fill(new RoundRectangle2D.Double(W / 2.0 - 250, y - 28, 500, 40, 16, 16));
                g.setColor(new Color(0, 255, 210, 140));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new RoundRectangle2D.Double(W / 2.0 - 250, y - 28, 500, 40, 16, 16));
            }
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.setColor(sel ? Color.WHITE : new Color(150, 160, 190));
            g.drawString(labels[i], W / 2 - 225, y);

            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.setColor(valueColors[i]);
            String v = sel ? "<   " + values[i] + "   >" : values[i];
            FontMetrics fm = g.getFontMetrics();
            g.drawString(v, W / 2 + 90 - fm.stringWidth(v) / 2, y);
        }

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(130, 140, 170));
        centered(g, "UP / DOWN select    LEFT / RIGHT change", HUD + 335);
        if (!twoPlayer) {
            g.setColor(GOLD);
            centered(g, "Best (" + difficulty.name() + ", " + (wrap ? "WRAP" : "SOLID") + "):  " + best, HUD + 358);
        } else {
            g.setColor(ORANGE);
            centered(g, "Last snake alive wins!", HUD + 358);
        }

        double a = 0.5 + 0.5 * Math.sin(time * 4);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(new Color(255, 255, 255, (int) (110 + 145 * a)));
        centered(g, "Press SPACE to start", HUD + FIELD_H - 100);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(170, 180, 210));
        centered(g, twoPlayer ? "P1: WASD     P2: Arrow keys     -     P pause     M mute"
                : "Arrow keys / WASD to steer     -     P pause     M mute", HUD + FIELD_H - 68);
        g.setColor(GOLD);
        centered(g, "Gold = bonus points", HUD + FIELD_H - 44);
        g.setColor(BLUE);
        centered(g, "Blue = slow-mo", HUD + FIELD_H - 27);
        g.setColor(PURPLE);
        centered(g, "Purple = shield (survive one crash)", HUD + FIELD_H - 10);
    }

    private void drawCenterOverlay(Graphics2D g, String title, Color titleColor, String sub, String sub2) {
        g.setColor(new Color(4, 6, 16, 175));
        g.fillRect(0, HUD, W, FIELD_H);
        int cy = HUD + FIELD_H / 2;
        neonText(g, title, new Font("SansSerif", Font.BOLD, 56), cy - 20, titleColor);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(Color.WHITE);
        centered(g, sub, cy + 25);
        if (sub2 != null) {
            double a = 0.5 + 0.5 * Math.sin(time * 4);
            g.setColor(new Color(255, 255, 255, (int) (110 + 145 * a)));
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            centered(g, sub2, cy + 58);
        }
    }

    private void neonText(Graphics2D g, String text, Font font, int y, Color c) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int x = (W - fm.stringWidth(text)) / 2;
        for (int i = 6; i >= 1; i--) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 18));
            for (int ox = -i; ox <= i; ox += i) {
                for (int oy = -i; oy <= i; oy += i) g.drawString(text, x + ox, y + oy);
            }
        }
        g.setColor(c);
        g.drawString(text, x, y);
        g.setColor(new Color(255, 255, 255, 90));
        g.drawString(text, x, y - 1);
    }

    private void centered(Graphics2D g, String text, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (W - fm.stringWidth(text)) / 2, y);
    }

    private void glow(Graphics2D g, double cx, double cy, double r, Color c, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        Paint old = g.getPaint();
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new float[]{0f, 1f},
                new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setPaint(old);
    }

    // ---------- main ----------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Volt Viper");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            VoltViper game = new VoltViper();
            frame.add(game);
            frame.pack();
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}