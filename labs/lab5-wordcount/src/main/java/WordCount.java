import java.io.IOException;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

// Lab 5: Hadoop MapReduce word count.
// Build: mvn package      Run: hadoop jar target/wordcount-1.0.jar WordCount input output
public class WordCount {

    // Map: for every word in a line emit (word, 1).
    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
        private static final IntWritable ONE = new IntWritable(1);
        private final Text word = new Text();

        @Override
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            // Lower-case and strip punctuation so "Rollout." and "rollout" count as the same word.
            StringTokenizer it = new StringTokenizer(value.toString().toLowerCase().replaceAll("[^a-z0-9 ]", " "));
            while (it.hasMoreTokens()) {
                word.set(it.nextToken());
                context.write(word, ONE);
            }
        }
    }

    // Reduce: the framework groups all values of one word; sum them.
    // Also used as the combiner, so partial sums are computed on the map side and less data is shuffled.
    public static class IntSumReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
        private final IntWritable result = new IntWritable();

        @Override
        public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException {
            int sum = 0;
            for (IntWritable v : values) sum += v.get();
            result.set(sum);
            context.write(key, result);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: WordCount <input path> <output path>");
            System.exit(2);
        }
        Job job = Job.getInstance(new Configuration(), "word count");
        job.setJarByClass(WordCount.class);
        job.setMapperClass(TokenizerMapper.class);
        job.setCombinerClass(IntSumReducer.class);
        job.setReducerClass(IntSumReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
