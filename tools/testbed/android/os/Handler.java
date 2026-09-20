package android.os;

import java.util.ArrayList;
import java.util.List;

/** 試験用の偽物。postDelayed は時刻を見て自前で走らせる。 */
public class Handler {
    private static final class Item { Runnable r; long dueNs; }
    private final List<Item> items = new ArrayList<>();

    public boolean postDelayed(Runnable r, long delayMs) {
        Item i = new Item();
        i.r = r;
        i.dueNs = SystemClock.nowNs + delayMs * 1000000L;
        items.add(i);
        return true;
    }

    public void removeCallbacks(Runnable r) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).r == r) items.remove(i);
        }
    }

    /** 時刻が来たものを走らせる。テストドライバが毎サンプル呼ぶ。 */
    public void pump() {
        for (int i = 0; i < items.size(); ) {
            if (items.get(i).dueNs <= SystemClock.nowNs) {
                Runnable r = items.remove(i).r;
                r.run();
            } else {
                i++;
            }
        }
    }
}
