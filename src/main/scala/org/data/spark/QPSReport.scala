package org.data.spark

import com.typesafe.config.Config
import org.apache.spark.sql.functions._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row

object QPSReport {
  
  @transient
  var insertQPS: InsertQPS = null
  var driver: String = null
  var url: String = null
  var user: String = null
  var password: String= null
  
  def process(config: Config, df: DataFrame): Unit = {
		  process(config, df.collectAsList())
  }
  
  def process(config: Config, rows: java.util.List[Row]): Unit = {

    if(insertQPS == null)
    {
      driver = "com.mysql.jdbc.Driver"
      url = config.getString("mysql.bi.url")
      user = config.getString("mysql.bi.user")
      password = config.getString("mysql.bi.password")
      insertQPS = new InsertQPS(driver, url, user, password)
      insertQPS.open()
    }
    
    val iterator = rows.iterator()
    while (iterator.hasNext())
      insertQPS.process(iterator.next())
    
    insertQPS.clearBatch()
  }
  
  
  def close(): Unit = {
    insertQPS.close()
  }
}