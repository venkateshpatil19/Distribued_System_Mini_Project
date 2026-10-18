# Lab 5: MapReduce word count (Hadoop)

**Aim:** count word frequencies with the MapReduce model on Hadoop.

**Real-world link:** the input is DeployGuard release notes and agent logs. Counting terms such as `rollback` or `suspected` across many log files is a typical batch job MapReduce was designed for.

## Files
- `src/main/java/WordCount.java`: the Hadoop job. The mapper emits `(word, 1)`, and `IntSumReducer` sums counts; it is also the combiner, so partial sums happen before the shuffle.
- `pom.xml`: builds `target/wordcount-1.0.jar` with `hadoop-client` 3.3.6 as a provided dependency.
- `LocalWordCount.java`: the same algorithm with explicit map, shuffle (hash partitioning) and reduce phases on threads. It needs no Hadoop and produces Hadoop-style `part-r-0000N` files.
- `input/`: sample input files.

## Run on Hadoop (single-node / pseudo-distributed)
```bash
mvn package
hdfs dfs -mkdir -p /user/$USER/wc/input
hdfs dfs -put input/* /user/$USER/wc/input
hadoop jar target/wordcount-1.0.jar WordCount /user/$USER/wc/input /user/$USER/wc/output
hdfs dfs -cat /user/$USER/wc/output/part-r-00000
```
Local mode, with no HDFS: `hadoop jar target/wordcount-1.0.jar WordCount input output`. The output directory must not exist yet.

Hadoop on Windows needs `winutils.exe` and `HADOOP_HOME`; WSL or a Linux VM is simpler.

## Run without Hadoop
```bash
javac LocalWordCount.java
java LocalWordCount input 2        # 2 reducers
```

## Recorded output (LocalWordCount)
```
map agent-log.txt -> 17 distinct words
map release-notes.txt -> 35 distinct words
2 input files, 2 reducers, 49 distinct words
Top 10 words:
  agent        6
  rollout      5
  percent      4
  revision     4
  three        4
```

## Verification status
- `LocalWordCount`: run and verified (output above).
- `WordCount` on Hadoop: the code is the standard Hadoop 3 job, but it was **not executed** in the build environment, because Hadoop and Maven Central downloads were blocked there. Run the commands above on a machine with Hadoop installed and paste the output into your lab record.

## Flow
```
input splits -> map (word,1) -> combine (partial sums) -> shuffle/sort by key -> reduce (sum) -> part-r-* files
```
