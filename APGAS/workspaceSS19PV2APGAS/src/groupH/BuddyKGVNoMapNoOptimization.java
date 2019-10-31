package groupH;

import apgas.Place;
import apgas.util.GlobalRef;

import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static apgas.Constructs.*;

public class BuddyKGVNoMapNoOptimization {

    public static void main(String[] args) {

        final int places = places().size();

        final long startTime = System.nanoTime();

        final int n = Integer.valueOf(args[0]);
        final int m = Integer.valueOf(args[1]);
        final int seedA = Integer.valueOf(args[2]);
        final int seedB = Integer.valueOf(args[3]);
        final int minKgv = Integer.valueOf(args[4]);
        final boolean verbose = Boolean.parseBoolean(args[5]);

        final int[][] a = new int[n][n];
        final int[][] b = new int[n][n];
        initializeArrays(a, b, seedA, seedB, n, m);

        final GlobalRef<Buddy[][]> results = new GlobalRef<>(places(), () -> new Buddy[n][n]);

        final int workers = numLocalWorkers();
        final int linesPerPlace = n / places;
        final int linesPerThread = linesPerPlace / workers;

        final int bufferSize = 33 * m;

        final GlobalRef<ConcurrentHashMap<Pair, Integer>> kgvBuffer = new GlobalRef<>(places(), () -> new ConcurrentHashMap<>(bufferSize, 0.75f, workers));

//        System.out.println("Init time: " + ((System.nanoTime() - startTime) / 1E9D) + " sec");
//        final long calcStartTime = System.nanoTime();

        finish(() -> {
            for (final Place place : places()) {
                asyncAt(place, () -> {
                    final int placeID = here().id;
                    final Buddy[][] placeResults = results.get();
                    final ConcurrentHashMap<Pair, Integer> placeKGVBuffer = kgvBuffer.get();

                    for (int worker = 0; worker < workers; worker++) {
                        final int workerID = worker;
                        async(() -> {
                            final int start = placeID * linesPerPlace + workerID * linesPerThread;
                            final int end = start + linesPerThread;

                            for (int y = start; y < end; y++) {
                                for (int x = 0; x < n; x++) {
                                    final int number = a[y][x];
                                    if (minKgv == -1) {
                                        placeResults[y][x] = findMaxBuddy(number, b, placeKGVBuffer);
                                    } else {
                                        placeResults[y][x] = findBuddy(number, b, minKgv, placeKGVBuffer);
                                    }
                                }
                            }
                        });
                    }
                });
            }
        });

//        System.out.println("Calc time: " + ((System.nanoTime() - calcStartTime) / 1E9D) + " sec");
//        final long copyStartTime = System.nanoTime();

        if (places > 1) {
            finish(() -> {
                for (final Place place : places()) {
                    if (place.equals(results.home())) continue;
                    final int start = place.id * linesPerPlace;
                    final int end = start + linesPerPlace;
                    asyncAt(place, () -> {
                        final Buddy[][] placeResults = results.get();
                        asyncAt(place(0), () -> {
                            final Buddy[][] homeResults = results.get();
                            System.arraycopy(placeResults, start, homeResults, start, end - start);
                        });
                    });
                }
            });
        }

//        System.out.println("Copy time: " + ((System.nanoTime() - copyStartTime) / 1E9D) + " sec");

        printResults(a, b, results.get(), verbose);

        final long endTime = System.nanoTime();
        System.out.println("Process time = " + ((endTime - startTime) / 1E9D) + " sec");
    }

    private static void initializeArrays(final int[][] a,
                                         final int[][] b,
                                         final int seedA,
                                         final int seedB,
                                         final int n,
                                         final int m) {
        final Random random = new Random();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                random.setSeed(seedA + Integer.parseInt(i + "" + j));
                a[i][j] = random.nextInt(m) + 1;
                random.setSeed(seedB + Integer.parseInt(i + "" + j));
                b[i][j] = random.nextInt(m) + 1;
            }
        }
    }

    private static Buddy findMaxBuddy(final int number,
                                     final int[][] b,
                                     final ConcurrentHashMap<Pair, Integer> kgvBuffer) {
        int maxKGV = 0;
        int xPos = 0;
        int yPos = 0;
        for (int y = 0; y < b.length; y++) {
            for (int x = 0; x < b.length; x++) {
                final int other = b[y][x];

                final int kgv = calculateKGV(number, other, kgvBuffer);

                if (kgv > maxKGV) {
                    maxKGV = kgv;
                    xPos = x;
                    yPos = y;
                }
            }
        }

        return new Buddy(yPos, xPos, maxKGV);
    }

    private static Buddy findBuddy(final int number,
                                  final int[][] b,
                                  final int minKgv,
                                  final ConcurrentHashMap<Pair, Integer> kgvBuffer) {
        for (int y = 0; y < b.length; y++) {
            for (int x = 0; x < b.length; x++) {
                final int other = b[y][x];

                final int kgv = calculateKGV(number, other, kgvBuffer);
                if (kgv >= minKgv) {
                    return new Buddy(y, x, kgv);
                }
            }
        }

        return Buddy.none;
    }

    private static int calculateKGV(final int a,
                                    final int b,
                                    final ConcurrentHashMap<Pair, Integer> kgvBuffer) {
        return kgvBuffer.computeIfAbsent(
                new Pair(a, b),
                kvg -> a * b / calculateGGT(a, b));
    }

    private static int calculateGGT(final int a,
                                    final int b) {
        int newA = a;
        int newB = b;
        while (newB != 0) {
            if (newA > newB) {
                newA -= newB;
            } else {
                newB -= newA;
            }
        }
        return newA;
    }

    private static void printResults(final int[][] a,
                                     final int[][] b,
                                     final Buddy[][] buddies,
                                     final boolean verbose) {
        final boolean foundKGV = checkAllBuddies(buddies);
        if (foundKGV) {
            System.out.println("all elements have found buddies");
        } else {
            printWithNoBuddies(buddies);
        }

        if (verbose) {
            System.out.println("a = ");
            printArray(a);
            System.out.println("b = ");
            printArray(b);
            printBuddies(buddies);
            printKGVs(buddies);
        }
    }

    private static boolean checkAllBuddies(final Buddy[][] buddies) {
        for (int y = 0; y < buddies.length; y++) {
            for (int x = 0; x < buddies.length; x++) {
                if (buddies[y][x].kgv < 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void printWithNoBuddies(final Buddy[][] buddies) {
        final StringBuilder sb = new StringBuilder();
        sb.append("there are no buddies for the following elements\n");
        sb.append('[');
        boolean appended = false;
        for (int y = 0; y < buddies.length; y++) {
            for (int x = 0; x < buddies.length; x++) {
                if (buddies[y][x].kgv < 1) {
                    if (appended) {
                        sb.append(", ");
                    } else {
                        appended = true;
                    }
                    sb.append(y).append(':').append(x);
                }
            }
        }
        sb.append(']');
        System.out.println(sb.toString());
    }

    private static void printArray(final int[][] array) {
        final StringBuilder sb = new StringBuilder();
        for (int y = 0; y < array.length; y++) {
            sb.append('[');
            boolean appended = false;
            for (int x = 0; x < array.length; x++) {
                if (appended) {
                    sb.append(", ");
                } else {
                    appended = true;
                }
                sb.append(array[y][x]);
            }
            sb.append(']').append('\n');
        }
        System.out.print(sb.toString());
    }

    private static void printBuddies(final Buddy[][] buddies) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Found buddy positions in b=\n");
        for (int y = 0; y < buddies.length; y++) {
            sb.append('[');
            boolean appended = false;
            for (int x = 0; x < buddies.length; x++) {
                if (appended) {
                    sb.append(", ");
                } else {
                    appended = true;
                }
                sb.append(buddies[y][x]);
            }
            sb.append(']').append('\n');
        }
        System.out.print(sb.toString());
    }

    private static void printKGVs(final Buddy[][] buddies) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Found kgVs=\n");
        for (int y = 0; y < buddies.length; y++) {
            sb.append('[');
            boolean appended = false;
            for (int x = 0; x < buddies.length; x++) {
                if (appended) {
                    sb.append(", ");
                } else {
                    appended = true;
                }
                sb.append(buddies[y][x].kgv);
            }
            sb.append(']').append('\n');
        }
        System.out.print(sb.toString());
    }

    static class Buddy implements Serializable {
        static final long serialVersionUID = 42L;
        static final Buddy none = new Buddy(-1, -1, -1);

        final int y;
        final int x;
        final int kgv;

        Buddy(final int y, final int x, final int kgv) {
            this.y = y;
            this.x = x;
            this.kgv = kgv;
        }

        @Override
        public String toString() {
            return y + ":" + x;
        }
    }

    static class Pair {
        final int first;
        final int second;
        final int hashCode;

        Pair(final int first, final int second) {
            if (first >= second) {
                this.first = first;
                this.second = second;
            } else {
                this.first = second;
                this.second = first;
            }
            hashCode = 33 * this.first ^ this.second;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Pair)) return false;
            final Pair o = (Pair) other;
            return first == o.first && second == o.second;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
