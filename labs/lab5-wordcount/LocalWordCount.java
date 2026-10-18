import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Lab 5 (no Hadoop needed): the same word count, written as explicit map, shuffle and reduce phases.
// Mappers and reducers run in parallel threads; the shuffle partitions words by hash, like Hadoop's
// default HashPartitioner. Output has the same "word<TAB>count" format as Hadoop's part-r-* files.
// Run: javac LocalWordCount.java && java LocalWordCount input 2
public class LocalWordCount {

    public static void main(String[] args) throws Exception {
        Path inputDir = Paths.get(args.length > 0 ? args[0] : "input");
        int reducers = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        List<Path> files;
        try (Stream<Path> s = Files.list(inputDir)) {
            files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) throw new IllegalArgumentException("no input files in " + inputDir);

        ExecutorService pool = Executors.newFixedThreadPool(4);

        // MAP: one task per input split (here one file); each emits partial counts (acts as its own combiner).
        List<Future<Map<String, Integer>>> mapOut = new ArrayList<>();
        for (Path f : files) mapOut.add(pool.submit(() -> map(f)));

        // SHUFFLE: send each word to reducer hash(word) mod R, so one reducer sees all counts of a word.
        List<Map<String, List<Integer>>> partitions = new ArrayList<>();
        for (int r = 0; r < reducers; r++) partitions.add(new HashMap<>());
        for (Future<Map<String, Integer>> fut : mapOut) {
            for (Map.Entry<String, Integer> e : fut.get().entrySet()) {
                int r = (e.getKey().hashCode() & Integer.MAX_VALUE) % reducers;
                partitions.get(r).computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }

        // REDUCE: each reducer sums the values of its words.
        List<Future<TreeMap<String, Integer>>> redOut = new ArrayList<>();
        for (Map<String, List<Integer>> part : partitions) {
            redOut.add(pool.submit(() -> {
                TreeMap<String, Integer> out = new TreeMap<>();
                part.forEach((w, counts) -> out.put(w, counts.stream().mapToInt(Integer::intValue).sum()));
                return out;
            }));
        }
        pool.shutdown();

        Path outDir = Paths.get("output");
        Files.createDirectories(outDir);
        TreeMap<String, Integer> all = new TreeMap<>();
        for (int r = 0; r < reducers; r++) {
            TreeMap<String, Integer> part = redOut.get(r).get();
            all.putAll(part);
            List<String> lines = part.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).collect(Collectors.toList());
            Files.write(outDir.resolve(String.format("part-r-%05d", r)), lines);
        }
        System.out.println(files.size() + " input files, " + reducers + " reducers, " + all.size() + " distinct words");
        System.out.println("Top 10 words:");
        all.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                .forEach(e -> System.out.printf("  %-12s %d%n", e.getKey(), e.getValue()));
        System.out.println("Full results written to output/part-r-*");
    }

    static Map<String, Integer> map(Path file) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            for (String w : line.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
                if (!w.isEmpty()) counts.merge(w, 1, Integer::sum);
            }
        }
        System.out.println("map " + file.getFileName() + " -> " + counts.size() + " distinct words");
        return counts;
    }
}
