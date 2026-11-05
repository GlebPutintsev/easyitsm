package ru.easyitsm.sla.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Интервалы, исключённые из счёта SLA: паузы и периоды, когда заявка была решена.
 * Время в миллисекундах эпохи, интервалы {@code [from, to)}; незакрытая пауза тянется до {@link Long#MAX_VALUE}.
 * Интервалы отсортированы и слиты.
 */
final class Gaps {

    static final Gaps NONE = new Gaps(new long[0], new long[0]);

    final long[] from;
    final long[] to;

    private Gaps(long[] from, long[] to) {
        this.from = from;
        this.to = to;
    }

    /** @param intervals пары {@code {from, to}}, где {@code to == null} означает незакрытый интервал */
    static Gaps of(List<Long[]> intervals) {
        List<long[]> sorted = new ArrayList<>();
        for (Long[] i : intervals) {
            long f = i[0];
            long t = i[1] == null ? Long.MAX_VALUE : i[1];
            if (t > f) {
                sorted.add(new long[] {f, t});
            }
        }
        sorted.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] cur : sorted) {
            long[] last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && cur[0] <= last[1]) {
                last[1] = Math.max(last[1], cur[1]);
            } else {
                merged.add(cur);
            }
        }
        long[] f = new long[merged.size()];
        long[] t = new long[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            f[i] = merged.get(i)[0];
            t[i] = merged.get(i)[1];
        }
        return new Gaps(f, t);
    }

    /** Начало незакрытого интервала или {@link Long#MAX_VALUE}, если такого нет. */
    long openFrom() {
        int n = to.length;
        return n > 0 && to[n - 1] == Long.MAX_VALUE ? from[n - 1] : Long.MAX_VALUE;
    }

    /** Длина части {@code [s, e)}, не покрытой интервалами. */
    long uncovered(long s, long e) {
        long sum = 0;
        long cur = s;
        for (int i = 0; i < from.length && cur < e; i++) {
            if (to[i] <= cur) {
                continue;
            }
            if (from[i] >= e) {
                break;
            }
            if (from[i] > cur) {
                sum += from[i] - cur;
            }
            cur = Math.max(cur, to[i]);
        }
        if (cur < e) {
            sum += e - cur;
        }
        return sum;
    }

    /**
     * Ищет момент внутри {@code [s, e)}, когда будет набрано {@code need} миллисекунд непокрытого времени.
     *
     * @return момент или {@code -(набрано) - 1}, если на отрезке столько времени нет
     */
    long reach(long s, long e, long need) {
        long cur = s;
        long got = 0;
        for (int i = 0; i < from.length && cur < e; i++) {
            if (to[i] <= cur) {
                continue;
            }
            if (from[i] >= e) {
                break;
            }
            if (from[i] > cur) {
                long len = from[i] - cur;
                if (got + len >= need) {
                    return cur + (need - got);
                }
                got += len;
            }
            cur = Math.max(cur, to[i]);
        }
        if (cur < e) {
            long len = e - cur;
            if (got + len >= need) {
                return cur + (need - got);
            }
            got += len;
        }
        return -got - 1;
    }
}
