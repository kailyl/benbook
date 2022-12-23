package edu.upenn.cis.nets2120.rank;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;

import edu.upenn.cis.nets2120.config.Config;
import edu.upenn.cis.nets2120.storage.SparkConnector;
import edu.upenn.cis.nets2120.rank.FetchData;
import scala.Tuple2;



import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Collection;
import java.util.List;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;

import edu.upenn.cis.nets2120.config.Config;
import edu.upenn.cis.nets2120.storage.DynamoConnector;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

// compute ranks not through livy
// code is outdated and used just for testing locally
public class ComputeRanks {
	/**
	 * The basic logger
	 */
	static Logger logger = LogManager.getLogger(ComputeRanks.class);

	/**
	 * Connection to Apache Spark
	 */
	SparkSession spark;
	
	JavaSparkContext context;
	
	FetchData fetch;
	
	DynamoDB db;
	
	public ComputeRanks() {
		System.setProperty("file.encoding", "UTF-8");
		fetch = new FetchData();
	}

	/**
	 * Initialize the database connection and open the file
	 * 
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public void initialize() throws IOException, InterruptedException {
		logger.info("Connecting to Spark...");

		spark = SparkConnector.getSparkConnection();
		context = SparkConnector.getSparkContext();
		
		logger.debug("Connected!");
		
		try {
			fetch.initialize();
			fetch.run();
			fetch.shutdown();
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Graph builder
	 * 
	 * @param filePath
	 * @return JavaPairRDD: (from, to)
	 */
	JavaPairRDD<String, String> getGraph(String filePath) {
		return context
			// load graph file into spark
			.textFile(filePath, Config.PARTITIONS)
			// split on spaces
			.map(line -> line.toString().split("\\s+"))
			// (from, to)
			.mapToPair(arr -> new Tuple2<>(arr[0], arr[1]))
			.distinct();
	}
	
	// (from, (to, wt/outdeg))
	JavaPairRDD<String, Tuple2<String, Double>> getEdgeTransfer(JavaPairRDD<String, String> graph, double weight) {
		return graph.join(
			graph
			// (from, 1) x outdeg
			.mapToPair(x -> new Tuple2<String, Double>(x._1, 1.0))
			// (from, outdeg)
			.reduceByKey((x, y) -> x + y)
			// (from, wt/outdeg)
			.mapToPair(x -> new Tuple2<String, Double>(x._1, weight / x._2))
		);
	}

	/**
	 * Main functionality in the program: read and process the social network
	 * 
	 * @throws IOException File read, network, and other errors
	 * @throws InterruptedException User presses Ctrl-C
	 */
	public void run(int imax, double dmax, boolean debug) throws IOException, InterruptedException {
		logger.info("Running");
		System.out.println(getGraph(Config.GRAPH_USERS_S3).count());
		System.out.println("my willto");
		
		// changed for injections...
		// (from, (to, wt))
		JavaPairRDD<String, Tuple2<String, Double>> edgeTransfer = 
			getEdgeTransfer(getGraph(Config.GRAPH_USERS_FILE), 0.3)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_A_FILE), 1.0)
			)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_C_FILE), 1.0)
			)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_UA_FILE), 0.3)
			)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_UC_FILE), 0.2)
			)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_UU_FILE), 0.2)
			)
			.union(
				getEdgeTransfer(getGraph(Config.GRAPH_TILDE_FILE), 1.0)
			);

		System.out.println(
			"This graph contains " + 
			edgeTransfer
				.keys()
				.distinct()
				.count() +
			" nodes and " + 
			edgeTransfer
				.count() + 
			" edges"
		);
		
		// (user, (user, 1))
		JavaPairRDD<String, Tuple2<String, Double>> ranks = 
			// (user, user)
			getGraph(Config.GRAPH_USERS_FILE)
			// (user, (user, 1))
			.mapToPair(x -> new Tuple2<String, Tuple2<String, Double>>(x._1, new Tuple2<String, Double>(x._2, 1.0)));
		
		double decay = 0.15;
		double dcurr = Double.MAX_VALUE;
		for (int i = 1; i <= imax && dcurr >= dmax; ++i) {
			JavaPairRDD<String, Tuple2<String, Double>> newRanks = edgeTransfer
				// (from, ((to, wt), (user, from's rank))
				.join(ranks)
				// ((to, user), wt * from's rank))
				.mapToPair(x -> new Tuple2<Tuple2<String, String>, Double>(
					new Tuple2<String, String> (x._2._1._1, x._2._2._1),
					x._2._2._2 * x._2._1._2)
				)
				// ((to, user), total user rank)
				.reduceByKey((a, b) -> a + b)
				// (to, (user, rank))
				.mapToPair(x -> new Tuple2<String, Tuple2<String, Double>>(
					x._1._1,
					new Tuple2<String, Double> (x._1._2, x._2))
				)
				// drop if rank is less than 20 percent of the convergence threshold
				.filter(x -> x._2._2 >= 0.2 * dmax);
			
			JavaPairRDD<String, Double> totalRanks = newRanks
				// (to, rank) x user
				.mapToPair(x -> new Tuple2<String, Double>(x._1, x._2._2))
				// (to, total rank)
				.reduceByKey((a, b) -> a + b);
			
			newRanks = newRanks
				// (to, ((user, rank), total rank))
				.join(totalRanks)
				// (to, (user, normalized rank))
				.mapToPair(x -> new Tuple2<String, Tuple2<String, Double>>(
					x._1,
					new Tuple2<String, Double> (x._2._1._1, x._2._1._2 / x._2._2))
				);
			
			// collect new ranks and print if in debug mode
			if (true) {
				System.out.println("round " + i);
				newRanks
					.collect()
					.stream()
					.forEach(
						x -> System.out.println(x._1 + " " + x._2._1 + " " + x._2._2)	
					);
				System.out.println("round " + i);
			}
			
			// updating round current max distance
			dcurr = newRanks
				// (node, ((user, new rank), (user, old rank)))
				.join(ranks)
				// ((user, new rank), (user, old rank))
				.values()
				// abs rank diff
				.map(x -> Math.abs(x._1._2 - x._2._2))
				// max abs rank diff
				.reduce((a, b) -> Math.max(a, b));
			
			// dont quit if still discovering nodes
			if (newRanks.keys().distinct().count() != ranks.keys().distinct().count())
				dcurr = Double.MAX_VALUE;
			
			// update all ranks
			ranks = newRanks;
		}
		
		List<Tuple2<Tuple2<String, String>, Double>> feed = ranks
			// only keep articles
			.filter(x -> x._1.charAt(x._1.length() - 1) == 'a')
			// only keep today's articles. USE INPUTTED (?) DATE WHEN DONE TESTING
			.filter(x -> x._1.substring(0, 10).equals("2023-05-26"))
			// ((user, article), rank) 
			.mapToPair(x -> new Tuple2<Tuple2<String, String>, Double>(
				new Tuple2<String, String>(x._2._1, x._1), x._2._2
			))
			// delete viewed articles
			.subtractByKey(
				// (user, article) viewed
				getGraph(Config.GRAPH_UA_FILE)
					.mapToPair(x -> new Tuple2<Tuple2<String, String>, Double>(x, 0.0))
			)
			// drop _a and _u suffixes
			.mapToPair(x -> new Tuple2<Tuple2<String, String>, Double>(
				new Tuple2<String, String>(
					x._1._1.substring(0, x._1._1.length() - 2), 
					x._1._2.substring(0, x._1._2.length() - 2)
				),
				x._2
			))
			// ((user, article), rank) list
			.collect();
		
		db = DynamoConnector.getConnection(Config.DYNAMODB_URL);
		Set<Item> cache = new HashSet<>();
		int batchSize = 25;
		
		// todo: delete old articles
		for (Tuple2<Tuple2<String, String>, Double> entry : feed) {
			System.out.println(entry._1._1 + " suggestion weight " + entry._2 + " article " + entry._1._2);
			cache.add(new Item()
				.withPrimaryKey("username", entry._1._1, "article", entry._1._2)
				.withNumber("weight", entry._2)
			);
			if (cache.size() == batchSize) { writeBatch(cache); }
		}
		// write any leftover items
		if (cache.size() != 0) { writeBatch(cache); }
		
		logger.info("*** Finished social network ranking! ***");
	}
	
	/**
	 * Sends and clears cache of items. If any items are left unprocessed method
	 * continually tries again until all items are processed.
	 * 
	 * @param cache       Batch of 25 or less items to write to our database
	 */
	private void writeBatch(Collection<Item> cache) {
		Map<String, List<WriteRequest>> unproc = 
			db.batchWriteItem(new TableWriteItems("feeds").withItemsToPut(cache))
				.getUnprocessedItems();
		System.out.println(db.batchWriteItem(new TableWriteItems("feeds").withItemsToPut(cache)));
		while (!unproc.isEmpty()) {
			unproc = db.batchWriteItemUnprocessed(unproc).getUnprocessedItems();
		}
		cache.clear();
	}

	/**
	 * Graceful shutdown
	 */
	public void shutdown() {
		logger.info("Shutting down");

		if (spark != null)
			spark.close();
	}

	public static void main(String[] args) {
		System.out.println("hi");
		final ComputeRanks cr = new ComputeRanks();

		try {
			cr.initialize();
			int imax = args.length > 0 ? Integer.parseInt(args[0]) : 15; // max number iters
			double dmax = args.length > 1 ? Double.parseDouble(args[1]) : 0.02; // convergence threshold
			boolean debug = args.length > 2;
			cr.run(imax, dmax, debug);
		} catch (final IOException ie) {
			logger.error("I/O error: ");
			ie.printStackTrace();
		} catch (final InterruptedException e) {
			e.printStackTrace();
		} finally {
			cr.shutdown();
		}
	}

}
