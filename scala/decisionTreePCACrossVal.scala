import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row

import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.ml.evaluation.{MulticlassClassificationEvaluator, BinaryClassificationEvaluator}
import org.apache.spark.ml.feature.{VectorAssembler, StringIndexer, VectorIndexer, IndexToString, PCA}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}

import org.apache.spark.mllib.linalg.Matrix
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.distributed.RowMatrix

import org.apache.spark.rdd.RDD

import org.apache.spark.sql.SparkSession

import better.files._
import better.files.File._

object Catraca {

	def main(args: Array[String]) = {

		// Check arguments
		if(args.length < 2 ) {
			println("Usage: scala.scl <csv dataset> <which slaves and how many cores>")
			sys.exit(1)
		}

		val csv_dataset = args(0)

		// Create spark session
		val spark = SparkSession
						.builder()
						.appName("Catraca")
						.config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
						.master("spark://master:7077")
						.getOrCreate

		// Define dataset schema, dataset csv generated by flowtbag https://github.com/DanielArndt/flowtbag		
		val schema = StructType(
			StructField("srcip", StringType, false) ::              //Feature 1
			StructField("srcport", IntegerType, false) ::           //Feature 2
			StructField("dstip", StringType, false) ::              //Feature 3
			StructField("dstport", IntegerType, false) ::           //Feature 4
			StructField("proto", IntegerType, false) ::             //Feature 5
			StructField("total_fpackets", IntegerType, false) ::    //Feature 6	
			StructField("total_fvolume", IntegerType, false) ::     //Feature 7
			StructField("total_bpackets", IntegerType, false) ::    //Feature 8
			StructField("total_bvolume", IntegerType, false) ::     //Feature 9
			StructField("min_fpktl", IntegerType, false) ::         //Feature 10
			StructField("mean_fpktl", IntegerType, false) ::        //Feature 11
			StructField("max_fpktl", IntegerType, false) ::         //Feature 12
			StructField("std_fpktl", IntegerType, false) ::         //Feature 13
			StructField("min_bpktl", IntegerType, false) ::         //Feature 14
			StructField("mean_bpktl", IntegerType, false) ::        //Feature 15
			StructField("max_bpktl", IntegerType, false) ::         //Feature 16	
			StructField("std_bpktl", IntegerType, false) ::         //Feature 17
			StructField("min_fiat", IntegerType, false) ::          //Feature 18
			StructField("mean_fiat", IntegerType, false) ::         //Feature 19
			StructField("max_fiat", IntegerType, false) ::          //Feature 20
			StructField("std_fiat", IntegerType, false) ::          //Feature 21
			StructField("min_biat", IntegerType, false) ::          //Feature 22
			StructField("mean_biat", IntegerType, false) ::         //Feature 23
			StructField("max_biat", IntegerType, false) ::          //Feature 24
			StructField("std_biat", IntegerType, false) ::          //Feature 25
			StructField("duration", IntegerType, false) ::          //Feature 26	
			StructField("min_active", IntegerType, false) ::        //Feature 27
			StructField("mean_active", IntegerType, false) ::       //Feature 28
			StructField("max_active", IntegerType, false) ::        //Feature 29
			StructField("std_active", IntegerType, false) ::        //Feature 30
			StructField("min_idle", IntegerType, false) ::          //Feature 31
			StructField("mean_idle", IntegerType, false) ::         //Feature 32
			StructField("max_idle", IntegerType, false) ::          //Feature 33
			StructField("std_idle", IntegerType, false) ::          //Feature 34
			StructField("sflow_fpackets", IntegerType, false) ::    //Feature 35
			StructField("sflow_fbytes", IntegerType, false) ::      //Feature 36	
			StructField("sflow_bpackets", IntegerType, false) ::    //Feature 37
			StructField("sflow_bbytes", IntegerType, false) ::      //Feature 38
			StructField("fpsh_cnt", IntegerType, false) ::          //Feature 39
			StructField("bpsh_cnt", IntegerType, false) ::          //Feature 40
			StructField("furg_cnt", IntegerType, false) ::          //Feature 41
			StructField("burg_cnt", IntegerType, false) ::          //Feature 42
			StructField("total_fhlen", IntegerType, false) ::       //Feature 43
			StructField("total_bhlen", IntegerType, false) ::       //Feature 44
			StructField("dscp", IntegerType, false) ::              //Feature 45
			StructField("label", IntegerType, false) ::             //Class Label: 0-Normal; 1-Attack
			Nil)

		// Load CSV data
		val data = spark.read.format("csv").schema(schema).load(csv_dataset)

		// Create vector assembler to produce a feature vector for each record for use in MLlib
                // First 45 csv fields are features, the 46th field is the label. Remove IPs from features.
		val assembler = new VectorAssembler()
			.setInputCols((schema.fieldNames.toList.takeRight(41).dropRight(1)).toArray)
			.setOutputCol("baseFeatures")

		// Assemble feature vector in new dataframe
		val assembledData = assembler.transform(data)

		// Create PCA model(reduce to 6 principal components)
		val pca = new PCA()
			.setInputCol("baseFeatures")
			.setOutputCol("features")
			.setK(6)
			.fit(assembledData)
		
		// Reduce assembled data
		val reducedData = pca.transform(assembledData).select("features","label")

		//Create a DecisionTree model trainer
		val dt = new DecisionTreeClassifier()
			.setMaxDepth(10)
			.setImpurity("entropy")

		val paramGrid = new ParamGridBuilder()
			.build()

		// Cross validation requires parameters on a grid
		val crossval = new CrossValidator()
			.setEstimator(dt)
			.setEstimatorParamMaps(paramGrid)
			.setEvaluator(new MulticlassClassificationEvaluator)
			.setNumFolds(10)

		// Split the data into training and test sets (30% held out for testing)
		val Array(trainingData, testData) = reducedData.randomSplit(Array(0.7, 0.3))

		val modeltimerstart = System.currentTimeMillis()

		//Train model
		val model = crossval.fit(trainingData)

		val modeltimerend = System.currentTimeMillis()

		//Time to create model
		val modelTime = (modeltimerend - modeltimerstart)/1000.0

		val testtimerstart = System.currentTimeMillis()

		//Make predictions
		val predictions = model.transform(testData)

		val testtimerend = System.currentTimeMillis()

		//Time to test model
		val testTime = (testtimerend - testtimerstart)/1000.0

		//Select (prediction, true label) and compute metrics
		val f1Evaluator = new MulticlassClassificationEvaluator()
			.setMetricName("f1")

		val weightedPrecisionEvaluator = new MulticlassClassificationEvaluator()
			.setMetricName("weightedPrecision")

		val weightedRecallEvaluator = new MulticlassClassificationEvaluator()
			.setMetricName("weightedRecall")

		val accuracyEvaluator = new MulticlassClassificationEvaluator()
			.setMetricName("accuracy")

		val aucEvaluator = new BinaryClassificationEvaluator()
			.setMetricName("areaUnderROC")

		val f1 = f1Evaluator.evaluate(predictions)
		val weightedPrecision = weightedPrecisionEvaluator.evaluate(predictions)
		val weightedRecall = weightedRecallEvaluator.evaluate(predictions)
		val accuracy = accuracyEvaluator.evaluate(predictions)
		val auc = aucEvaluator.evaluate(predictions)

		//Stop spark session
		spark.stop()

		val csvFile: File = "/home/gta/catraca/results/scala/csv/decisionTreePCACrossVal.csv"
			.toFile
			.createIfNotExists()

		val dirname = StringBuilder.newBuilder
		val filename = StringBuilder.newBuilder

		for (x <- 1 to (args.length-1)) {
			csvFile.append(args(x) + ",")

			if (args(x) != 0){

				if (dirname.length != 0) {dirname.append("+")}
				if (filename.length != 0) {filename.append("+")}

				filename.append("slave0" + x.toString + "-" + args(x).toString + "Cores")
				dirname.append("slave0" + x.toString)
			}
		}

		csvFile.append(f1.toString + "," + weightedPrecision.toString + "," + weightedRecall.toString + "," + accuracy.toString + "," + auc.toString + "," + modelTime.toString + "," + testTime.toString + "\n")

		val txtPath = "/home/gta/catraca/results/scala/" + dirname.toString() + "/" + filename.toString() + "-decisionTreePCACrossVal.txt"

		val txtFile: File = txtPath
			.toFile
			.createIfNotExists()

	    txtFile.append("\n\n+-----------------------------------------------+\n")
	    txtFile.append("+                 Test Metrics                  +\n")
	    txtFile.append("+-----------------------------------------------+\n")
	    for (x <- 1 to (args.length-1)){
	    	txtFile.append(f"| Number of Cores in Slave " + x.toString + "         | " + args(x).toString + " |\n")
	    }
	    txtFile.append(f"| F1-Score                           | $f1%.7f |\n")
	    txtFile.append(f"| Weighted Precision                 | $weightedPrecision%.7f |\n")
	    txtFile.append(f"| Weighted Recall                    | $weightedRecall%.7f |\n")
	    txtFile.append(f"| Accuracy                           | $accuracy%.7f |\n")
	    txtFile.append(f"| Area Under ROC                     | $auc%.7f |\n")
	    txtFile.append(f"| Model Time (in seconds)            | $modelTime%.7f |\n")
	    txtFile.append(f"| Test Time (in seconds)             | $testTime%.7f |\n")
	    txtFile.append("+-----------------------------------------------+\n\n")

	    println("")

	}
}
