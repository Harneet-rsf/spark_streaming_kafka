package org.data.spark

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import java.text.SimpleDateFormat
import java.util.Date
import org.apache.spark.sql.types.DataType
import scala.io.Source
import org.apache.spark.sql.types.StructType

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.DataFrame

import org.apache.spark.sql.types.{ StringType, IntegerType, StructType, TimestampType }
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.HasOffsetRanges
import org.apache.spark.streaming.kafka010.CanCommitOffsets
import org.apache.spark.streaming.{ Seconds, StreamingContext }

import org.apache.kafka.common.serialization.StringDeserializer

import org.apache.log4j.LogManager
import org.apache.log4j.Level
import java.io.File
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import org.apache.spark.streaming.kafka010.ConsumerStrategies

object KafkaConsumerToMySQL {
  
  var databaseName : String = null
  var config : Config = null

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().
    setAppName("Spark Kafka Consumer").
    set("spark.streaming.receiver.writeAheadLog.enable", "true")
    .setMaster("local[*]")
    
    val spark = SparkSession.builder().config(conf).enableHiveSupport().getOrCreate();
    
    args.foreach { x => println(x) }
    
    config = ConfigFactory.parseFile(new File(args(0)))
    databaseName = config.getString("hive.databaseName")
    
    val log = LogManager.getRootLogger
    log.setLevel(Level.toLevel(config.getString("conf.log.level")))
    
    if (!spark.catalog.databaseExists(databaseName))
      spark.sql("""CREATE DATABASE IF NOT EXISTS """ + databaseName)
    spark.sql("""USE """ + databaseName)
    
    val targetFormat = new SimpleDateFormat("yyyy-MM-dd")
    val tableDateFormat = new SimpleDateFormat("yyyyMMdd")
    
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> config.getString("kafka.bootstrap.servers"),
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> config.getString("kafka.group.id"),
      "max.poll.records" -> config.getString("kafka.max.poll.records"),
      "enable.auto.commit" -> (false: java.lang.Boolean),
      "request.timeout.ms" -> config.getString("kafka.request.timeout.ms"),
      "session.timeout.ms" -> config.getString("kafka.session.timeout.ms"),
//      "max.poll.interval.ms" -> "300000",  //50 min
      "auto.offset.reset" -> config.getString("kafka.auto.offset.reset"))
    
    
    val ssc = new StreamingContext(spark.sparkContext, Seconds(1))
    
    println("Calling auctionLog Topic")
    auctionLogPull(spark, ssc, kafkaParams, targetFormat, tableDateFormat)
    
//    println("Calling winLog Topic")
//    winLogPull(spark, ssc, kafkaParams, targetFormat, tableDateFormat)
    
    ssc.start()
    ssc.awaitTermination()
  }

  def auctionLogPull(spark: SparkSession, ssc: StreamingContext, kafkaParams: Map[String, Object], targetFormat: SimpleDateFormat, tableDateFormat: SimpleDateFormat): Unit = {
    import spark.implicits._

    val mySchema = DataType.fromJson(Source.fromFile(config.getString("hive.schema.auctionLog")).toSeq.mkString(""))
      .asInstanceOf[StructType]
    
    println("-----------------------------------Starting auctionLogger Topic-----------------------------------")
    
    val topics = Array(config.getString("kafka.topic.auctionLog"))
    
    
    val stream = KafkaUtils.createDirectStream[String, String](
        ssc,
        PreferConsistent,
        consumerStrategy = ConsumerStrategies.Subscribe[String, String](topics, kafkaParams))
  
    stream.foreachRDD { rdd =>
      
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      
      if (rdd.map(_.value()).toLocalIterator.nonEmpty) {
        val df = rdd.map(_.value()).toDF().selectExpr("CAST(value AS STRING)").as[(String)]
          .select(from_json($"value", mySchema).as("data"))
          .select("data.*")
          .withColumn("log_date", to_date($"adRequestTimestamp"))
          .withColumn("log_hour", hour($"adRequestTimestamp"))
          
       auctionLogPull(spark, df.persist)
      }
      
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges)
    }
  }

  def auctionLogPull(spark: SparkSession, df1: DataFrame): Unit = {
    import spark.implicits._

    df1.write.mode("append").format("json").partitionBy("log_date").saveAsTable(databaseName + "." + config.getString("hive.tmp.auctionTable"))
        
    /*
     * QPS Report
     */
    val qpsDF = df1.filter(!isnull($"log_date")).groupBy("log_date", "log_hour").agg(count("log_date") as "req")
    QPSReport.process(config, qpsDF)
  }

  def winLogPull(spark: SparkSession, ssc: StreamingContext, kafkaParams: Map[String, Object], targetFormat: SimpleDateFormat, tableDateFormat: SimpleDateFormat): Unit = {
    import spark.implicits._

    val mySchema = DataType.fromJson(Source.fromFile(config.getString("hive.schema.winTable")).toSeq.mkString("")).asInstanceOf[StructType]

    val topics = Array(config.getString("kafka.topic.win"))
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      consumerStrategy = ConsumerStrategies.Subscribe[String, String](topics, kafkaParams))

    stream.foreachRDD { rdd =>
      
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      
      if (rdd.map(_.value()).toLocalIterator.nonEmpty) {
        val df = rdd.map(_.value()).toDF().selectExpr("CAST(value AS STRING)").as[(String)]
          .select(from_json($"value", mySchema).as("data"))
          .select("data.*")
          .withColumn("log_date", to_date(to_timestamp($"timestamp", "MMM d, yyyy hh:mm:ss a")))

        winLogPull(spark, df)
      }
      
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges)
    }
  }

  def winLogPull(spark: SparkSession, df: DataFrame): Unit = {
    import spark.implicits._
    
    df.write.mode("append").format("json").partitionBy("log_date").saveAsTable(databaseName + "." + config.getString("hive.tmp.winTable"))
  }
}